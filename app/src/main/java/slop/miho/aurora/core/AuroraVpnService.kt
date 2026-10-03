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
import slop.miho.aurora.MainActivity
import slop.miho.aurora.R
import slop.miho.aurora.data.ConnectionStore
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
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bring the tunnel up", e)
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

    private fun stopVpn() {
        XrayCore.publish(TunnelStatus(TunnelState.DISCONNECTING))
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
        // The per-app bypass: packages the user pulled out of the tunnel. Read straight
        // off disk — this process's cached copy goes stale the moment the UI writes.
        ConnectionStore.current(this).bypassedApps.forEach { pkg ->
            try {
                builder.addDisallowedApplication(pkg)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Bypassed app not found: $pkg")
            }
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
}
