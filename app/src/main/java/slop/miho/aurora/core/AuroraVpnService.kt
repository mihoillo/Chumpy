package slop.miho.aurora.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.util.Log
import slop.miho.aurora.MainActivity
import slop.miho.aurora.R
import slop.miho.aurora.data.ConnectionStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The VPN itself: one service that is both the VpnService (it owns the TUN descriptor) and
 * the AIDL facade the UI talks to, modelled on v2rayNG's CoreVpnService. It runs in ":core"
 * next to [XrayCore], comes up as a foreground service on connect, and answers three start
 * paths with the same code: the app's connect command, the QS tile, and the system's own
 * Always-on restart (a null intent / the VpnService interface action) — the last only when
 * auto-reconnect says so: off, the restart is declined, the tunnel stays down and the fall
 * notice's Reconnect is the way back. Auto-reconnect owns a fall's aftermath too: answered
 * at once, the climb back beginning the moment the fall is finished — no waiting — and the
 * fall notice edited to the outcome: restored, or the failure that ended it. Three falls
 * in a row that a foreign VPN caused stand the auto-reconnect down: a fight the VPN keeps
 * winning is a loop, not a connection.
 *
 * A connect while the tunnel is already up is a reload: the interface stays, only the core
 * loop restarts with the fresh config the UI just wrote — that is the server-switch path.
 *
 * The same reload, silent and invisible, answers the network handovering under the tunnel
 * (v2rayNG's NetworkMonitor, its shape kept): the core's outbound sockets ride the physical
 * network the system's own request names, and a switch — cellular to Wi-Fi, a Wi-Fi's own
 * reconnect — strands them on the network that went away, where a cheap ROM's radios leave
 * them half-open: writes sink in, nothing comes back, the state machine still calling the
 * tunnel up. A handover re-dials the core onto whatever now carries the traffic, silently,
 * the toggle never asked: the phone kept the tunnel alive through the switch, so the
 * re-dial is maintenance, not a bring-back — auto-reconnect is for the phones that
 * strangle tunnels, and on the ones that don't, the tunnel simply rides on. A fall the
 * re-dial cannot mend — and the revoke another VPN's arrival brings — stops the tunnel
 * and says so out loud: an alert notification with a Reconnect button of its own. The revoke is also asked for directly, every few seconds while the tunnel
 * runs: prepare() answers null exactly while this app is the system's VPN of record, so
 * a ROM that never delivers onRevoke — a dead tunnel the state machine still calls
 * up — falls the same way, seconds late at worst. And a ROM that goes further, granting
 * the other VPN without revoking this one and keeping prepare() null, is caught by the
 * same poll's other question: a VPN routing this app is never this app's own — the app
 * is excluded from its own tunnel — so a VPN transport on the active network falls the
 * tunnel too. That fall is soft on the interface: the fd stays, because this ROM removes
 * the foreign VPN together with ours the moment our own interface dies — the interface
 * retires at the next connect instead, never on the fall.
 */
class AuroraVpnService : VpnService() {
    companion object {
        private const val TAG = "Aurora/VpnService"
        private const val CHANNEL_ID = "aurora-core"
        private const val NOTIFICATION_ID = 1

        /** The interrupted-tunnel alert's own channel and slot — never the running one's. */
        private const val ALERT_CHANNEL_ID = "aurora-alerts"
        private const val ALERT_NOTIFICATION_ID = 2

        /**
         * The TUN interface a soft fall left standing. A foreign VPN holding the system
         * cannot be torn down together with ours — this ROM removes the newcomer when the
         * authorized VPN's own interface dies — so the fd outlives the service instead,
         * retiring at the next connect or with the process. Companion-level on purpose:
         * the service may die long before either happens.
         */
        @Volatile
        private var keptTun: ParcelFileDescriptor? = null

        /**
         * How long a handover waits before its re-dial: switches flap — a Wi-Fi reconnect
         * bounces the underlying network around — and every flap restarts the wait, so a
         * storm folds into one re-dial (v2rayNG's own debounce).
         */
        private const val HANDOVER_DEBOUNCE_MILLIS = 1_000L

        /**
         * How often the authorization is re-asked while the tunnel runs. prepare() answers
         * null exactly while this app is the system's VPN of record, so a ROM that never
         * delivers onRevoke leaves a dead VPN claiming to be up for at most this long —
         * and no longer.
         */
        private const val AUTHORIZATION_CHECK_MILLIS = 5_000L

        /**
         * How many times in a row auto-reconnect may answer a foreign-VPN fall before it
         * stands down: each answer is a fight for the slot, and a fight the VPN keeps
         * winning is a loop otherwise. Reset the moment a tunnel stays up.
         */
        private const val AUTO_RECONNECT_ATTEMPT_CAP = 3

        const val ACTION_CONNECT = "slop.miho.aurora.core.CONNECT"
        const val ACTION_DISCONNECT = "slop.miho.aurora.core.DISCONNECT"

        /** The TUN's own address; the core receives everything routed into it. */
        private const val TUN_ADDRESS = "172.19.0.1"
        private const val TUN_PREFIX = 30
        private const val TUN_MTU = 1500

        fun startConnect(context: Context) {
            context.startForegroundService(
                Intent(context, AuroraVpnService::class.java).setAction(ACTION_CONNECT),
            )
        }

        fun startDisconnect(context: Context) {
            val intent = Intent(context, AuroraVpnService::class.java).setAction(ACTION_DISCONNECT)
            // The service is foreground while running, so a plain start is enough there; a
            // not-yet-started service answers DISCONNECT by stopping immediately.
            try {
                context.startService(intent)
            } catch (_: Exception) {
                context.startForegroundService(intent)
            }
        }
    }

    private val binder = object : ITunnelService.Stub() {
        override fun connect() {
            // Same path as the intent route: through onStartCommand, in one place.
            startService(Intent(this@AuroraVpnService, AuroraVpnService::class.java).setAction(ACTION_CONNECT))
        }

        override fun disconnect() {
            startService(Intent(this@AuroraVpnService, AuroraVpnService::class.java).setAction(ACTION_DISCONNECT))
        }

        override fun connectedAtMillis(): Long = XrayCore.upSince()

        override fun trafficBytes(): LongArray = XrayCore.trafficBytes()

        override fun measureDelay(configJson: String?, testUrl: String?): Long =
            XrayCore.measureOutboundDelay(configJson.orEmpty(), testUrl.orEmpty())

        override fun measureLiveDelay(testUrl: String?): Long =
            XrayCore.measureLiveDelay(testUrl.orEmpty())

        override fun coreVersion(): String = XrayCore.coreVersion()

        override fun registerCallback(callback: ITunnelCallback?) {
            if (callback != null) XrayCore.register(callback)
        }

        override fun unregisterCallback(callback: ITunnelCallback?) {
            if (callback != null) XrayCore.unregister(callback)
        }
    }

    // Read from the connectivity callbacks' thread as well as the worker — the watch's
    // underlying-networks declaration asks whether the interface stands.
    @Volatile
    private var tunInterface: ParcelFileDescriptor? = null

    /** All VPN setup/teardown runs here; concurrent commands are collapsed by the lock. */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "vpn-worker") }
    private val starting = AtomicBoolean(false)

    /**
     * The physical network the tunnel last rode, as the system's own request names it —
     * null from [onLost] until the next [onAvailable]. [Volatile][Volatile] because the
     * connectivity callbacks arrive off this service's threads.
     */
    @Volatile
    private var upstream: Network? = null

    /** Whether the watch's first report already named the network we are on. */
    @Volatile
    private var watchSeeded = false

    @Volatile
    private var watching = false

    /** The watch's own handle on the connectivity service, for the default's transports. */
    @Volatile
    private var connectivityManager: ConnectivityManager? = null

    /**
     * Bumped every time the watch stops: a re-dial from an older watch drops instead of
     * bringing a stopped tunnel back — nothing resurrects the VPN after a revoke.
     */
    private val watchGeneration = AtomicInteger(0)

    /** The handover timer's own looper; holds re-dials and authorization checks only. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Set by the auto-reconnect: the next tunnel outcome — up, or failed — edits the fall notice. */
    @Volatile
    private var announceAutoReconnect = false

    /** Consecutive auto-reconnect answers to foreign-VPN falls; reset by a tunnel that stays up. */
    @Volatile
    private var autoReconnectAttempts = 0

    /**
     * The authorization self-check: prepare() is a pure query, no dialog, and it answers
     * null exactly while this app is the system's VPN of record. A non-null answer while
     * the loop runs means the authorization was pulled out from under us — another app's
     * VPN was granted, or the user revoked in settings — and the ROM never told
     * [onRevoke] about it: the tunnel stands dead with the state machine still calling
     * it up. It falls here instead, the same way: stopped, and said out loud.
     *
     * The same poll asks one more thing the ROM may lie about: whether a VPN *other than
     * this one* now routes this app. The app stands outside its own tunnel (the core's
     * sockets must not loop back in), so a VPN seen carrying us is never ours — another
     * VPN took the system over, and a ROM that grants it without revoking us and keeps
     * prepare() answering null leaves the tunnel just as dead, just as silently. The
     * active network's own transports say it straight, consent or no consent.
     */
    private val authorizationCheck: Runnable = Runnable {
        worker.execute {
            if (!watching || !XrayCore.isRunning) return@execute
            // The foreign VPN is asked first: while it holds the system, prepare() keeps
            // answering null on this ROM, and it is our own interface teardown — not the
            // consent — that would take the newcomer down with us.
            val foreign = foreignVpnHoldsTheDefault()
            if (foreign || prepare(this) != null) {
                Log.w(TAG, "The VPN authorization is gone — or another VPN holds the default — and onRevoke never came; stopping")
                val auto = ConnectionStore.autoReconnectFresh(this)
                if (foreign && auto) autoReconnectAttempts++
                // The promise text only while the answer is coming: beyond the cap, the
                // notice states the fall and leaves it to the user's own hand.
                val answering = foreign && auto && autoReconnectAttempts <= AUTO_RECONNECT_ATTEMPT_CAP
                stopVpn(
                    getString(
                        if (answering) R.string.notif_interrupted_auto
                        else R.string.notif_interrupted_revoked,
                    ),
                    keepTun = foreign,
                    reconnectAfter = answering,
                )
            } else {
                // A tunnel that stays up forgives the count: whatever fought it before,
                // this one holds.
                autoReconnectAttempts = 0
                mainHandler.postDelayed(authorizationCheck, AUTHORIZATION_CHECK_MILLIS)
            }
        }
    }

    /**
     * Whether a VPN other than this one routes this app's traffic now — the app is
     * excluded from its own tunnel, so a VPN transport on the active network means the
     * carrier is someone else's.
     */
    @Suppress("DEPRECATION")
    private fun foreignVpnHoldsTheDefault(): Boolean {
        // The service handle falls back to a fresh lookup: the watch outlives the tunnel
        // watch's own teardown, which nulls the cached one.
        val connectivity = connectivityManager
            ?: getSystemService(ConnectivityManager::class.java)
            ?: return false
        val active = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(active) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /**
     * Watches the network that carries the tunnel, v2rayNG's way: the plain
     * registerDefaultNetworkCallback would report the VPN's own interface as the default
     * network the moment it stands up, so the physical network is requested by its
     * capabilities instead — the request asks for THE default network, not every network
     * that fits (and CHANGE_NETWORK_STATE is what lets it be made).
     */
    private val networkWatch = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = upstream
            upstream = network
            // Declare what carries the VPN: the system's picture of the tunnel — its
            // meteredness above all — follows the real network, not the one that died.
            if (tunInterface != null) setUnderlyingNetworks(arrayOf(network))
            if (!watchSeeded) {
                // The registration's own first report names the network we started on.
                watchSeeded = true
                return
            }
            // A change — or a network come back from a total loss, the Wi-Fi-only
            // reconnect that never bounces through cellular — hands the core over.
            if (previous != network) scheduleHandover()
        }

        override fun onLost(network: Network) {
            if (network != upstream) return
            upstream = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        // The gomobile runtime makes blocking calls from threads we don't control.
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        XrayCore.init(this)
    }

    override fun onBind(intent: Intent) = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground before anything heavy: the start contract requires it within seconds.
        ensureForeground()
        // Always-on restarts from the OS deliver the VpnService interface action, or null.
        val systemVpnStart = intent == null || intent.action == VpnService.SERVICE_INTERFACE
        // The flag read off the file, not this process's prefs cache: the UI wrote it,
        // and the cache never saw that write.
        if (systemVpnStart && !ConnectionStore.autoReconnectFresh(this)) {
            // The system wants its VPN back — the always-on engine's restart, the sticky
            // resurrection after the core process died — but nobody asked for it.
            // Auto-reconnect off: the tunnel stays down, and the alert's Reconnect is
            // the way back by the user's own hand. On, the restart is the auto-reconnect
            // itself: the tunnel comes back at once, wherever it fell.
            Log.i(TAG, "System restart declined: auto-reconnect is off")
            stopVpn(getString(R.string.notif_interrupted_off))
            return START_NOT_STICKY
        }
        val action = if (systemVpnStart) ACTION_CONNECT else intent?.action
        when (action) {
            ACTION_DISCONNECT -> stopVpn()
            else -> startOrReloadVpn()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // The system took the VPN away (revoked, or another VPN took over): the tunnel
        // falls the way v2rayNG's does — down, now, and said out loud, the alert's
        // Reconnect being the way back. Auto-reconnect on: the fall is answered at once.
        Log.w(TAG, "VPN revoked")
        stopVpn(
            getString(R.string.notif_interrupted_revoked),
            reconnectAfter = ConnectionStore.autoReconnectFresh(this),
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTunnelWatch()
        closeTun()
        cancelNotification()
    }

    private fun startOrReloadVpn() {
        if (!starting.compareAndSet(false, true)) {
            Log.w(TAG, "A start is already in progress")
            return
        }
        XrayCore.publish(TunnelStatus(TunnelState.CONNECTING))
        worker.execute {
            // A new attempt supersedes whatever fall the last one announced.
            cancelAlert()
            try {
                // A trusted Wi-Fi carries the traffic itself: the tunnel stands down, the
                // status line saying why instead of an error.
                val connection = ConnectionStore.current(this)
                if (connection.trustedWifiEnabled && currentWifiSsid() in connection.trustedWifiSsids) {
                    stopTunnelWatch()
                    XrayCore.publish(
                        TunnelStatus(TunnelState.DOWN, getString(R.string.trusted_stand_down)),
                    )
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@execute
                }
                if (prepare(this) != null) {
                    throw SecurityException(getString(R.string.error_vpn_not_authorized))
                }
                val config = CoreConfigFile.readConfig(this)
                    ?: throw IllegalStateException(getString(R.string.error_no_config))
                if (XrayCore.isRunning) {
                    // Reload: the interface stays up, only the core loop restarts with the
                    // fresh config — server switch while connected.
                    XrayCore.stopLoop()
                    XrayCore.startLoop(config, XrayCore.currentTunFd())?.let { throw it }
                } else {
                    // The interface a soft fall kept standing retires here, right before
                    // its replacement — never standing alone, never mid-fall.
                    closeKeptTun()
                    val tun = establishTun() ?: throw IllegalStateException(getString(R.string.error_tun_creation))
                    tunInterface = tun
                    XrayCore.startLoop(config, tun.fd)?.let { throw it }
                }
                XrayCore.publish(TunnelStatus(TunnelState.UP))
                // The guards ride with the running tunnel: handovers under it re-dial it,
                // and the authorization check catches the revoke a ROM never reports.
                startTunnelWatch()
                if (announceAutoReconnect) {
                    announceAutoReconnect = false
                    notifyReconnected()
                }
                ensureForeground()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bring the tunnel up", e)
                if (announceAutoReconnect) {
                    // The auto-reconnect's own outcome: the notice that promised the climb
                    // back now says how it ended. Reconnect stays its action — the user's
                    // own hand can try what the automation could not.
                    announceAutoReconnect = false
                    notifyReconnectFailed(describe(e))
                }
                stopTunnelWatch()
                XrayCore.stopLoop()
                closeTun()
                XrayCore.publish(TunnelStatus(TunnelState.DOWN, describe(e)))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } finally {
                starting.set(false)
            }
        }
    }

    /**
     * [keepTun] is the soft fall — for the foreign-VPN takeover, where this ROM removes
     * the newcomer's VPN together with ours the moment our own interface dies. The
     * interface stands down without dying: nothing routes into it anymore, the core has
     * stopped, only the fd stays, retiring at the next connect or with the process.
     *
     * [reconnectAfter] is auto-reconnect's answer: the moment the fall is finished, the
     * climb back begins — no waiting, the next tunnel goes up at once, and its outcome
     * (up, or failed) is what the fall notice is edited to say.
     */
    private fun stopVpn(interruptionReason: String? = null, keepTun: Boolean = false, reconnectAfter: Boolean = false) {
        // The watch goes first: no handover may schedule behind a stop, and one already
        // waiting must die with it — nothing brings the tunnel back from the dead.
        stopTunnelWatch()
        // A stop retires the fall notice too: the tunnel is down by the user's own hand,
        // not fallen; the next connect is a tap in the app, not a rescue.
        cancelAlert()
        XrayCore.publish(TunnelStatus(TunnelState.DISCONNECTING))
        worker.execute {
            try {
                XrayCore.stopLoop()
                // stopSelf must run before the interface close — the core needs its fd valid
                // through its async shutdown, or the next start finds the port still bound.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Thread.sleep(100)
                if (keepTun) {
                    keptTun = tunInterface
                    tunInterface = null
                } else {
                    closeTun()
                }
            } finally {
                XrayCore.publish(TunnelStatus(TunnelState.DOWN))
                if (interruptionReason != null) {
                    notifyInterrupted(interruptionReason)
                }
                if (reconnectAfter) {
                    // The answer the toggle promised: no waiting, the climb back begins
                    // the moment the fall is finished. The outcome edits the notice.
                    Log.i(TAG, "Auto-reconnect: answering the fall at once")
                    announceAutoReconnect = true
                    startOrReloadVpn()
                }
            }
        }
    }

    /**
     * Starts the handover's re-dial after the debounce; a watch that stopped meanwhile
     * drops its own timer's callback by the generation check.
     *
     * A handover is not a fall, so the toggle has no word here: the phone carried the
     * tunnel through the switch — the fd stands, the state machine calls the tunnel up —
     * and the re-dial is silent maintenance, not a bring-back. Auto-reconnect is for the
     * phones that strangle the tunnel on their own; on the ones that don't, the tunnel
     * rides on. A foreign VPN's arrival still fires this watch first, its network reading
     * as the new one under the tunnel — the re-dial may chain through the newcomer for a
     * moment, and the authorization poll falls the tunnel properly within seconds of it.
     */
    private fun scheduleHandover() {
        val generation = watchGeneration.get()
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (generation == watchGeneration.get() && XrayCore.isRunning) {
                worker.execute { redialCore(generation) }
            }
        }, HANDOVER_DEBOUNCE_MILLIS)
        Log.i(TAG, "The network under the tunnel changed; re-dialing in ${HANDOVER_DEBOUNCE_MILLIS}ms")
    }

    /**
     * The handover re-dial itself, v2rayNG's reload: silent — the interface, the
     * notification, the status line all stay, the core loop restarts and every outbound
     * dials on the network that now carries the traffic. The generation check is the
     * ownership check: a watch that stopped between the timer and here drops this, and
     * nothing resurrects a stopped tunnel. A re-dial that cannot mend the tunnel brings
     * it down properly and says so — the alert's Reconnect is the way back.
     */
    private fun redialCore(generation: Int) {
        if (generation != watchGeneration.get() || !XrayCore.isRunning) return
        try {
            XrayCore.stopLoop()
            val config = CoreConfigFile.readConfig(this)
                ?: throw IllegalStateException(getString(R.string.error_no_config))
            XrayCore.startLoop(config, XrayCore.currentTunFd())?.let { throw it }
            Log.i(TAG, "The core re-dialed onto the network now under it")
        } catch (e: Exception) {
            Log.e(TAG, "The handover re-dial failed", e)
            stopTunnelWatch()
            XrayCore.stopLoop()
            closeTun()
            XrayCore.publish(TunnelStatus(TunnelState.DOWN, describe(e)))
            notifyInterrupted(describe(e))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Begins the guards that ride with the running tunnel: the network watch that hands
     * the core over, and the authorization self-check that catches the revoke a ROM
     * never reports. Idempotent.
     */
    private fun startTunnelWatch() {
        if (watching) return
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        connectivityManager = connectivity
        try {
            connectivity.requestNetwork(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    .build(),
                networkWatch,
            )
            watching = true
            upstream = null
            watchSeeded = false
            mainHandler.removeCallbacks(authorizationCheck)
            mainHandler.postDelayed(authorizationCheck, AUTHORIZATION_CHECK_MILLIS)
        } catch (e: Exception) {
            Log.w(TAG, "Requesting the default network failed; handovers go unwatched", e)
        }
    }

    /**
     * Stops the guards: the pending re-dial and the authorization check die with their
     * timers, the generation moves so a re-dial already on the worker drops, and the
     * tracked network is forgotten. Idempotent, and safe from every teardown path.
     */
    private fun stopTunnelWatch() {
        mainHandler.removeCallbacksAndMessages(null)
        watchGeneration.incrementAndGet()
        upstream = null
        watchSeeded = false
        connectivityManager = null
        if (!watching) return
        watching = false
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkWatch)
        } catch (_: Exception) {
            // The system already forgot the callback — the watch is down either way.
        }
    }

    private fun establishTun(): ParcelFileDescriptor? {
        val builder = Builder()
            .setMtu(TUN_MTU)
            .addAddress(TUN_ADDRESS, TUN_PREFIX)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        // The core's own sockets must not route back into the tunnel: the app package is
        // excluded from the VPN, and the UI reaches the core's local socks inbound over
        // loopback instead (v2rayNG's arrangement, and every tun-based client's).
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.wtf(TAG, "Own package not found", e)
        }
        // The per-app bypass: packages the user pulled out of the tunnel, the list's own
        // master switch permitting. Read straight off disk — this process's cached copy
        // goes stale the moment the UI writes.
        val connection = ConnectionStore.current(this)
        if (connection.perAppEnabled) {
            connection.bypassedApps.forEach { pkg ->
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Bypassed app not found: $pkg")
                }
            }
        }
        return builder.establish()
    }

    /**
     * The Wi-Fi the device sits on, by name. Android only shares names with apps holding
     * the location permission (and with location itself on); anything else reads as null —
     * an unknown network is never trusted, so the tunnel simply comes up as usual.
     */
    private fun currentWifiSsid(): String? = try {
        @Suppress("DEPRECATION")
        applicationContext.getSystemService(WifiManager::class.java)
            ?.connectionInfo
            ?.ssid
            ?.removeSurrounding("\"")
            ?.takeIf { ssid -> ssid.isNotEmpty() && ssid != "<unknown ssid>" }
    } catch (e: Exception) {
        Log.w(TAG, "Reading the Wi-Fi name failed", e)
        null
    }

    private fun closeTun() {
        try {
            tunInterface?.close()
        } catch (_: Exception) {
        }
        tunInterface = null
    }

    /** Retires the interface a soft fall left standing; its teardown is ours to own. */
    private fun closeKeptTun() {
        try {
            keptTun?.close()
        } catch (_: Exception) {
        }
        keptTun = null
    }

    private fun describe(e: Exception): String =
        e.message ?: getString(R.string.error_unknown)

    private fun ensureForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_vpn),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setShowBadge(false)
                    description = getString(R.string.notif_channel_vpn_desc)
                },
            )
        }
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val status = XrayCore.currentStatus()
        val profile = CoreConfigFile.readProfileName(this)?.let(::stripFlag)
            ?: getString(R.string.notif_profile_unknown)
        val subscription = CoreConfigFile.readSubscriptionName(this)
        // The app's name and the state it is in share the title — "Aurora • Connected" —
        // and the line under it tells the hop the home card does: the subscription, an
        // arrow, then the region — its flag emoji lifted off the way the list does. The
        // "→" renders crooked in the notification's system font; the "›" chevron — the
        // text twin of the chevron the cards point with — sits straight everywhere.
        val state = when (status.state) {
            TunnelState.UP -> getString(R.string.notif_connected)
            TunnelState.CONNECTING -> getString(R.string.notif_connecting)
            else -> getString(R.string.notif_disconnected)
        }
        val title = getString(R.string.app_name) + " " + getString(R.string.notif_separator) + " " + state
        val text = if (subscription != null) "$subscription › $profile" else profile
        // The notification's own off switch: the same disconnect path the app and the QS
        // tile take, without opening anything.
        val disconnect = PendingIntent.getService(
            this,
            1,
            Intent(this, AuroraVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }
        builder
            .setSmallIcon(R.drawable.ic_tile_on)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(intent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (status.state == TunnelState.UP) {
            // The uptime lives where the posted time usually sits: a chronometer the
            // system itself runs from the moment the tunnel came up — it ticks on its
            // own, no re-posting round needed.
            builder.setWhen(XrayCore.upSince()).setUsesChronometer(true)
        } else {
            builder.setShowWhen(false)
        }
        if (status.state == TunnelState.UP || status.state == TunnelState.CONNECTING) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_power),
                    getString(R.string.notif_action_disconnect),
                    disconnect,
                ).build(),
            )
        }
        return builder.build()
    }

    private fun cancelNotification() {
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /**
     * The fall notice: the tunnel stood and fell — another VPN took the tunnel away, or a
     * handover's re-dial could not bring it back — and the line under the title says which.
     * Reconnect is the row's action: the connect path in one tap, no app opening. Its own
     * channel, one that speaks, unlike the running service's silent one; a stop or a new
     * connect attempt retires it.
     */
    private fun notifyInterrupted(reason: String) =
        postFallNotice(R.string.notif_interrupted_title, reason)

    /**
     * The one notice the fall's whole story is told in, one page at a time: the fall
     * itself first, then the climb back's outcome — the title is the page, the reason
     * rides under it. Reconnect carries the row, and Disconnect anyway sits beside it —
     * the visitor's way out, for the user handing the VPN slot to another client:
     * whatever is climbing is stopped, the notice retires, the slot stands free. Both
     * row intents are foreground-service ones: the service is often already gone by the
     * time a row is tapped, and only the foreground kind may be started from that far
     * in the background — the disconnect command turns around and stops before anything
     * is seen.
     */
    private fun postFallNotice(titleRes: Int, reason: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        ensureAlertChannel(manager)
        val reconnect = PendingIntent.getForegroundService(
            this,
            2,
            Intent(this, AuroraVpnService::class.java).setAction(ACTION_CONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnectAnyway = PendingIntent.getForegroundService(
            this,
            3,
            Intent(this, AuroraVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            ALERT_NOTIFICATION_ID,
            Notification.Builder(this, ALERT_CHANNEL_ID)
                // The fall's own glyph: the disconnected state's, not the running
                // service's — the banner says what is, not what was.
                .setSmallIcon(R.drawable.ic_tile_off)
                .setContentTitle(getString(titleRes))
                .setContentText(reason)
                .setContentIntent(reconnect)
                .setAutoCancel(true)
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_power),
                        getString(R.string.notif_action_reconnect),
                        reconnect,
                    ).build(),
                )
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_close),
                        getString(R.string.notif_action_disconnect_anyway),
                        disconnectAnyway,
                    ).build(),
                )
                .build(),
        )
    }

    /** Retires the fall notice: a stop by the user's own hand, or a new attempt. */
    private fun cancelAlert() {
        getSystemService(NotificationManager::class.java)?.cancel(ALERT_NOTIFICATION_ID)
    }

    /**
     * The speaking channel itself: a fall is worth interrupting the screen for, so the
     * importance is HIGH — heads-up, the banner over whatever the user is doing. A
     * channel already on the device keeps its importance forever, though, so one left
     * quieter by an older build is deleted and recreated loud.
     */
    private fun ensureAlertChannel(manager: NotificationManager) {
        val existing = manager.getNotificationChannel(ALERT_CHANNEL_ID)
        if (existing != null && existing.importance < NotificationManager.IMPORTANCE_HIGH) {
            manager.deleteNotificationChannel(ALERT_CHANNEL_ID)
        }
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                getString(R.string.notif_channel_alerts),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setShowBadge(false)
                description = getString(R.string.notif_channel_alerts_desc)
            },
        )
    }

    /**
     * The recovery edit: the fall notice becomes its own answer — restored, the on glyph,
     * the tunnel standing. Same slot as the fall it answers: one notification, telling
     * the story's current page.
     */
    private fun notifyReconnected() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        ensureAlertChannel(manager)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            ALERT_NOTIFICATION_ID,
            Notification.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tile_on)
                .setContentTitle(getString(R.string.notif_reconnected_title))
                .setContentText(getString(R.string.notif_reconnected_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * The failure edit: the climb back did not land, and the notice says so — the error
     * in the fall's own seat, everything else the fall notice already was.
     */
    private fun notifyReconnectFailed(reason: String) =
        postFallNotice(R.string.notif_reconnect_failed_title, reason)
}
