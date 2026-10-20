package slop.miho.chumpy.core

import android.content.Context
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import slop.miho.chumpy.data.DailyTrafficStore

/**
 * Owner of the Xray core (the gomobile bindings of 2dust/AndroidLibXrayLite). Lives only in
 * the ":core" process, which stays alive while the VPN is up, independently of the UI — and
 * can also be spun up bound-only, purely to answer [measureOutboundDelay] pings.
 *
 * The controller itself is created once and driven with startLoop/stopLoop; those calls and
 * every state change run on the single worker thread, like the wireguard backend before it.
 * [measureOutboundDelay] is a native static and safe to call concurrently from binder threads
 * (v2rayNG does exactly that from its speedtest worker pool); it waits for the one-time
 * environment init to finish first.
 */
object XrayCore {
    private const val TAG = "Chumpy/XrayCore"

    /** Where the geo databases live once copied out of the APK assets. */
    private const val ASSET_DIR = "xray_assets"

    /** How long the core's stop call may take before it counts as wedged. */
    private const val STOP_GRACE_MILLIS = 5_000L

    private const val GEOIP = "geoip.dat"
    private const val GEOSITE = "geosite.dat"

    private lateinit var appContext: Context

    /** The core's own worker: init, start/stop loop and stats queries queue here. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "xray-worker") }

    /** Released once initCoreEnv ran (successfully or not) — pings wait on it. */
    private val initLatch = CountDownLatch(1)

    @Volatile
    private var initFailed: String? = null

    @Volatile
    private var controller: CoreController? = null

    /** Whether the core loop is running (i.e. a config was started and not stopped). */
    @Volatile
    var isRunning: Boolean = false
        private set

    private val callbacks = RemoteCallbackList<ITunnelCallback>()

    @Volatile
    private var status = TunnelStatus()

    @Volatile
    private var connectedAtMillis = 0L

    /** The last TUN fd the VPN service handed us; -1 when the interface is gone. */
    @Volatile
    private var tunFd: Int = -1

    private var pendingUiRx = 0L
    private var pendingUiTx = 0L
    private var pendingDailyRx = 0L
    private var pendingDailyTx = 0L
    private var lastDailyFlush = 0L
    private var lastUiTrafficAt = 0L

    private val coreCallback = object : CoreCallbackHandler {
        override fun startup(): Long {
            Log.i(TAG, "Core startup")
            return 0
        }

        override fun shutdown(): Long {
            Log.i(TAG, "Core shutdown")
            return 0
        }

        override fun onEmitStatus(l: Long, s: String?): Long {
            if (!s.isNullOrBlank()) Log.d(TAG, "Core status: $s")
            return 0
        }
    }

    /**
     * Idempotent, safe from any process start path. The UI process never calls it: it is
     * invoked from [ChumpyVpnService] and the app's ":core" fork in [slop.miho.chumpy.ChumpyApp].
     */
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        try {
            Seq.setContext(appContext)
        } catch (e: Exception) {
            Log.e(TAG, "Seq.setContext failed", e)
        }
        worker.execute {
            try {
                copyGeoAssets()
                Libv2ray.initCoreEnv(assetsDir().absolutePath, deviceId())
                Log.i(TAG, "Xray core ${Libv2ray.checkVersionX()} environment ready")
            } catch (e: Exception) {
                initFailed = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "Failed to init core environment", e)
            } finally {
                initLatch.countDown()
            }
        }
    }

    /**
     * The geo databases are shipped in the APK; the core wants them as plain files. The
     * copy is unconditional: the databases are slim enough now that re-placing them each
     * start costs less than any version bookkeeping — and a build with changed databases
     * always wins over whatever an older one had left on the device.
     */
    private fun copyGeoAssets() {
        val dir = assetsDir()
        dir.mkdirs()
        listOf(GEOIP, GEOSITE).forEach { name ->
            val target = File(dir, name)
            appContext.assets.open(name).use { input ->
                File(dir, "$name.tmp").let { tmp ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                    if (!tmp.renameTo(target)) {
                        tmp.delete()
                        throw IllegalStateException("Could not place $name")
                    }
                }
            }
            Log.i(TAG, "Installed $name (${File(dir, name).length()} bytes)")
        }
    }

    private fun assetsDir(): File = File(appContext.filesDir, ASSET_DIR)

    /**
     * A stable per-device id for Xray's XUDP base key, as v2rayNG does — a salted hash of
     * ANDROID_ID, never the raw identifier. The core decodes this as base64url and demands
     * exactly 32 bytes out of it (hex strings decode to a multiple of 3 and are rejected),
     * so the digest itself is what gets encoded, unpadded.
     */
    private fun deviceId(): String {
        val androidId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "chumpy"
        val digest = MessageDigest.getInstance("SHA-256").digest("chumpy:$androidId".toByteArray())
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /**
     * Runs the core with [configJson] on the TUN descriptor [fd]. Blocks until the core is
     * up; returns null on success or the failure to show. Must run on the worker.
     */
    fun startLoop(configJson: String, fd: Int): Exception? {
        tunFd = fd
        try {
            awaitInit()
            initFailed?.let { return IllegalStateException(it) }
            val core = controller ?: Libv2ray.newCoreController(coreCallback).also { controller = it }
            core.startLoop(configJson, fd)
            synchronized(this) {
                pendingUiRx = 0L
                pendingUiTx = 0L
                lastUiTrafficAt = SystemClock.elapsedRealtime()
            }
            isRunning = true
            return null
        } catch (e: Exception) {
            isRunning = false
            return e
        }
    }

    /**
     * Stops the core loop; the controller stays alive for the next start. Worker only.
     *
     * The Go call is given a grace period, not an eternity: a wedged loop (seen with the
     * wireguard outbound) would block teardown forever and pin the app in Disconnecting.
     * Past the grace the controller is abandoned — it is unusable anyway, and only
     * process death frees the port it holds — and a reaper tears the core process down
     * once the caller has finished its own unwinding (stopSelf, TUN close, the DOWN
     * broadcast), which it always does within a second or two.
     */
    fun stopLoop() {
        if (!isRunning) return
        sampleTraffic()
        flushDailyTraffic()
        isRunning = false
        val core = controller ?: return
        val stopper = Thread {
            try {
                core.stopLoop()
            } catch (e: Exception) {
                Log.w(TAG, "stopLoop failed", e)
            }
        }
        stopper.start()
        try {
            stopper.join(STOP_GRACE_MILLIS)
        } catch (_: InterruptedException) {
        }
        if (stopper.isAlive) {
            Log.e(TAG, "Core stopLoop wedged; abandoning the controller and exiting")
            controller = null
            Thread {
                try {
                    Thread.sleep(3_000)
                } catch (_: InterruptedException) {
                }
                System.exit(0)
            }.start()
        }
    }

    /** The fd of the live TUN interface, for reloads after a config swap. */
    fun currentTunFd(): Int = tunFd

    fun currentStatus(): TunnelStatus = status

    /** When the tunnel last came up (0 while down); the real VPN uptime. */
    fun upSince(): Long = connectedAtMillis

    fun register(callback: ITunnelCallback) {
        callbacks.register(callback)
        try {
            callback.onStateChanged(status.state.ordinal, status.error)
        } catch (_: RemoteException) {
            callbacks.unregister(callback)
        }
    }

    fun unregister(callback: ITunnelCallback) {
        callbacks.unregister(callback)
    }

    /** Real delay (an HTTP round trip through the config's outbound), -1 on failure. */
    fun measureOutboundDelay(configJson: String, testUrl: String): Long {
        try {
            awaitInit()
            initFailed?.let { return -1L }
            return Libv2ray.measureOutboundDelay(configJson, testUrl)
        } catch (e: Exception) {
            Log.w(TAG, "measureOutboundDelay failed: ${e.message}")
            return -1L
        }
    }

    /**
     * Delay through the *running* tunnel: the controller fetches the URL through the live
     * proxy outbound. -1 when the core is down or the fetch fails — the probe loops treat
     * that as a lost sample.
     */
    fun measureLiveDelay(testUrl: String): Long {
        val core = controller
        if (!isRunning || core == null) return -1L
        return try {
            core.measureDelay(testUrl)
        } catch (e: Exception) {
            Log.w(TAG, "measureLiveDelay failed: ${e.message}")
            -1L
        }
    }

    /**
     * The proxy outbound's {rx, tx} bytes since the *previous* query — the core resets its
     * counters on every read (the Xray way; v2rayNG divides the answer by the seconds
     * since its last query for its speed notification) — so callers fold deltas, they do
     * not watch a total grow. The payload's rows read "tag,uplink|downlink,value;" — the
     * counter names' last segment, not the "up"/"down" shorthand. Zeros while down.
     */
    @Synchronized
    fun trafficBytes(): LongArray {
        sampleTraffic()
        val now = SystemClock.elapsedRealtime()
        return longArrayOf(pendingUiRx, pendingUiTx, (now - lastUiTrafficAt).coerceAtLeast(1L)).also {
            pendingUiRx = 0L
            pendingUiTx = 0L
            lastUiTrafficAt = now
        }
    }

    @Synchronized
    fun sampleTraffic() {
        val delta = queryTrafficBytes()
        pendingUiRx += delta[0]
        pendingUiTx += delta[1]
        pendingDailyRx += delta[0]
        pendingDailyTx += delta[1]
        if (System.currentTimeMillis() - lastDailyFlush >= 60_000L) flushDailyTraffic()
    }

    @Synchronized
    private fun flushDailyTraffic() {
        if (pendingDailyRx == 0L && pendingDailyTx == 0L) return
        try {
            DailyTrafficStore.addToday(appContext, pendingDailyRx, pendingDailyTx)
            pendingDailyRx = 0L
            pendingDailyTx = 0L
            lastDailyFlush = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.w(TAG, "Could not save daily traffic; retaining pending bytes", e)
        }
    }

    private fun queryTrafficBytes(): LongArray {
        if (!isRunning) return longArrayOf(0L, 0L)
        val core = controller ?: return longArrayOf(0L, 0L)
        var rx = 0L
        var tx = 0L
        try {
            val payload = core.queryAllOutboundTrafficStats()
            payload.split(';').forEach { entry ->
                if (entry.isBlank()) return@forEach
                val parts = entry.split(',', limit = 3)
                if (parts.size != 3) return@forEach
                val value = parts[2].toLongOrNull() ?: return@forEach
                // A balancer session spreads its traffic over the member outbounds, all
                // of which ride the proxy tag's prefix — the plain tag included — while
                // direct and block stay out of the count, as they always have.
                if (parts[0] == "proxy" || parts[0].startsWith("proxy-")) when (parts[1]) {
                    "downlink" -> rx += value
                    "uplink" -> tx += value
                }
            }
        } catch (e: Exception) {
            // The stats manager is gone once the core stops; a query racing that is fine.
            Log.w(TAG, "traffic stats query failed: ${e.message}")
        }
        return longArrayOf(rx, tx)
    }

    fun coreVersion(): String = try {
        awaitInit()
        Libv2ray.checkVersionX()
    } catch (_: Exception) {
        "unknown"
    }

    private fun awaitInit() {
        check(initLatch.await(30, TimeUnit.SECONDS)) { "Xray initialization timed out" }
    }

    fun publish(newStatus: TunnelStatus) {
        status = newStatus
        connectedAtMillis = when (newStatus.state) {
            TunnelState.UP -> if (connectedAtMillis == 0L) System.currentTimeMillis() else connectedAtMillis
            else -> 0L
        }
        synchronized(callbacks) {
            val count = callbacks.beginBroadcast()
            try {
                for (i in 0 until count) {
                    try {
                        callbacks.getBroadcastItem(i).onStateChanged(newStatus.state.ordinal, newStatus.error)
                    } catch (_: RemoteException) {
                        // Dead clients are dropped by RemoteCallbackList itself.
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }
}
