package slop.miho.aurora.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import slop.miho.aurora.MainActivity
import slop.miho.aurora.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The VPN itself: one service that is both the VpnService (it owns the TUN descriptor) and
 * the AIDL facade the UI talks to, modelled on v2rayNG's CoreVpnService. It runs in ":core"
 * next to [XrayCore], comes up as a foreground service on connect, and answers three start
 * paths with the same code: the app's connect command, the QS tile, and the system's own
 * Always-on restart (a null intent / the VpnService interface action).
 *
 * A connect while the tunnel is already up is a reload: the interface stays, only the core
 * loop restarts with the fresh config the UI just wrote — that is the server-switch path.
 */
class AuroraVpnService : VpnService() {
    companion object {
        private const val TAG = "Aurora/VpnService"
        private const val CHANNEL_ID = "aurora-core"
        private const val NOTIFICATION_ID = 1

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

    private var tunInterface: ParcelFileDescriptor? = null

    /** The uptime ticker's home; cancelled with the service. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var uptimeTicker: Job? = null

    /** All VPN setup/teardown runs here; concurrent commands are collapsed by the lock. */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "vpn-worker") }
    private val starting = AtomicBoolean(false)

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
        val action = if (systemVpnStart) ACTION_CONNECT else intent?.action
        when (action) {
            ACTION_DISCONNECT -> stopVpn()
            else -> startOrReloadVpn()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // The system took the VPN away (revoked, or another VPN took over).
        Log.w(TAG, "VPN revoked")
        stopVpn()
    }

    override fun onDestroy() {
        super.onDestroy()
        closeTun()
        stopUptimeTicker()
        scope.cancel()
        cancelNotification()
    }

    private fun startOrReloadVpn() {
        if (!starting.compareAndSet(false, true)) {
            Log.w(TAG, "A start is already in progress")
            return
        }
        XrayCore.publish(TunnelStatus(TunnelState.CONNECTING))
        worker.execute {
            try {
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
                    val tun = establishTun() ?: throw IllegalStateException(getString(R.string.error_tun_creation))
                    tunInterface = tun
                    XrayCore.startLoop(config, tun.fd)?.let { throw it }
                }
                XrayCore.publish(TunnelStatus(TunnelState.UP))
                ensureForeground()
                startUptimeTicker()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bring the tunnel up", e)
                XrayCore.stopLoop()
                closeTun()
                stopUptimeTicker()
                XrayCore.publish(TunnelStatus(TunnelState.DOWN, describe(e)))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } finally {
                starting.set(false)
            }
        }
    }

    private fun stopVpn() {
        XrayCore.publish(TunnelStatus(TunnelState.DISCONNECTING))
        stopUptimeTicker()
        worker.execute {
            try {
                XrayCore.stopLoop()
                // stopSelf must run before the interface close — the core needs its fd valid
                // through its async shutdown, or the next start finds the port still bound.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Thread.sleep(100)
                closeTun()
            } finally {
                XrayCore.publish(TunnelStatus(TunnelState.DOWN))
            }
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
        return builder.establish()
    }

    private fun closeTun() {
        try {
            tunInterface?.close()
        } catch (_: Exception) {
        }
        tunInterface = null
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
        // The state reads as the title — "Connected" — and the line under it names the
        // server (its flag emoji left behind) with how long the tunnel has carried it.
        val title = when (status.state) {
            TunnelState.UP -> getString(R.string.notif_connected)
            TunnelState.CONNECTING -> getString(R.string.notif_connecting)
            else -> getString(R.string.notif_disconnected)
        }
        val text = when (status.state) {
            TunnelState.UP -> profile + " " + getString(R.string.notif_separator) + " " +
                formatUptime(System.currentTimeMillis() - XrayCore.upSince())
            else -> profile
        }
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

    /** The tunnel's age the notification carries beside the server: whole units only,
     *  growing into the next shape as it passes each mark. */
    private fun formatUptime(durationMillis: Long): String {
        val minutes = durationMillis / 60_000
        return when {
            minutes < 1 -> getString(R.string.notif_uptime_seconds, durationMillis / 1_000)
            minutes < 60 -> getString(R.string.notif_uptime_minutes, minutes)
            minutes < 24 * 60 -> getString(
                R.string.notif_uptime_hours,
                minutes / 60,
                minutes % 60,
            )
            else -> getString(
                R.string.notif_uptime_days,
                minutes / (24 * 60),
                minutes / 60 % 24,
            )
        }
    }

    /**
     * The uptime clock beside the server: once a minute, while the tunnel is up, the
     * notification re-tells its line — silently, [android.app.Notification.Builder.setOnlyAlertOnce]
     * keeps it from announcing itself again.
     */
    private fun startUptimeTicker() {
        stopUptimeTicker()
        uptimeTicker = scope.launch {
            while (isActive) {
                delay(60_000)
                if (XrayCore.currentStatus().state != TunnelState.UP) break
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    private fun stopUptimeTicker() {
        uptimeTicker?.cancel()
        uptimeTicker = null
    }

    private fun cancelNotification() {
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
