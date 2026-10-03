package slop.miho.aurora.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI-process side of the AIDL link to [AuroraVpnService]. Connect and disconnect travel as
 * service commands (the VPN must be started, not merely bound); the binder carries status
 * callbacks, traffic counters and delay measurements.
 */
class TunnelClient(private val context: Context) {
    private val _status = MutableStateFlow(TunnelStatus())
    val status: StateFlow<TunnelStatus> = _status.asStateFlow()

    private var service: ITunnelService? = null
    private var bound = false

    private val callback = object : ITunnelCallback.Stub() {
        override fun onStateChanged(state: Int, error: String?) {
            _status.value = TunnelStatus(TunnelState.fromOrdinal(state), error)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val remote = ITunnelService.Stub.asInterface(binder)
            service = remote
            call(remote) { it.registerCallback(callback) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // ":core" died; BIND_AUTO_CREATE brings it back and we reconnect automatically.
            service = null
            _status.value = TunnelStatus()
        }
    }

    fun bind() {
        if (bound) return
        bound = context.bindService(
            Intent(context, AuroraVpnService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    fun unbind() {
        if (!bound) return
        service?.let { remote -> call(remote) { it.unregisterCallback(callback) } }
        context.unbindService(connection)
        service = null
        bound = false
    }

    /**
     * Connect (or reload, if already up) using the config the caller just wrote with
     * [CoreConfigFile]. The caller must hold the VPN consent already.
     */
    fun connect() = AuroraVpnService.startConnect(context)

    fun disconnect() = AuroraVpnService.startDisconnect(context)

    /** Wall-clock millis of the last transition to UP, or null while not bound. */
    fun connectedAtMillis(): Long? {
        val remote = service ?: return null
        return try {
            remote.connectedAtMillis()
        } catch (_: RemoteException) {
            null
        }
    }

    /** The tunnel's {rx, tx} byte counters — bytes since the caller's previous query
     *  (the core resets them on every read) — or null while not bound. */
    fun trafficBytes(): LongArray? {
        val remote = service ?: return null
        return try {
            remote.trafficBytes()
        } catch (_: RemoteException) {
            null
        }
    }

    /**
     * A real delay measurement through [configJson], performed by a one-off core instance
     * in the ":core" process. Blocking — call from Dispatchers.IO. Null when unreachable.
     */
    fun measureDelay(configJson: String, testUrl: String): Long? {
        val remote = service ?: return null
        return try {
            remote.measureDelay(configJson, testUrl).takeIf { it >= 0 }
        } catch (_: RemoteException) {
            null
        }
    }

    /**
     * Delay through the *running* tunnel (the probe path for Home's ping chip and the
     * stats screen's loss buckets). Null while not bound or the core is down.
     */
    fun measureLiveDelay(testUrl: String): Long? {
        val remote = service ?: return null
        return try {
            remote.measureLiveDelay(testUrl)
        } catch (_: RemoteException) {
            null
        }
    }

    /** The Xray core version, or null while the core process is unreachable. */
    fun coreVersion(): String? {
        val remote = service ?: return null
        return try {
            remote.coreVersion()
        } catch (_: RemoteException) {
            null
        }
    }

    private fun call(remote: ITunnelService, action: (ITunnelService) -> Unit) {
        try {
            action(remote)
        } catch (_: RemoteException) {
            // onServiceDisconnected will follow.
        }
    }
}
