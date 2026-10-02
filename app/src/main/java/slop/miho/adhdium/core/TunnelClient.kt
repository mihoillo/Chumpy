package slop.miho.adhdium.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-process side of the AIDL link to [TunnelService]. */
class TunnelClient(private val context: Context) {
    private val _status = MutableStateFlow(TunnelStatus())
    val status: StateFlow<TunnelStatus> = _status.asStateFlow()

    private var service: ITunnelService? = null
    private var pendingAction: ((ITunnelService) -> Unit)? = null
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
            pendingAction?.let { action -> call(remote, action) }
            pendingAction = null
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // ":tunnel" died; BIND_AUTO_CREATE brings it back and we reconnect automatically.
            service = null
            _status.value = TunnelStatus()
        }
    }

    fun bind() {
        if (bound) return
        bound = context.bindService(Intent(context, TunnelService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        if (!bound) return
        service?.let { remote -> call(remote) { it.unregisterCallback(callback) } }
        context.unbindService(connection)
        service = null
        bound = false
    }

    fun connect() = perform { it.connect() }

    fun disconnect() = perform { it.disconnect() }

    /** Wall-clock millis of the last transition to UP, or null while not bound. */
    fun connectedAtMillis(): Long? {
        val remote = service ?: return null
        return try {
            remote.connectedAtMillis()
        } catch (_: RemoteException) {
            null
        }
    }

    /** The tunnel's {rx, tx} byte counters since it came up, or null while not bound. */
    fun trafficBytes(): LongArray? {
        val remote = service ?: return null
        return try {
            remote.trafficBytes()
        } catch (_: RemoteException) {
            null
        }
    }

    private fun perform(action: (ITunnelService) -> Unit) {
        val remote = service
        if (remote != null) call(remote, action) else pendingAction = action
    }

    private fun call(remote: ITunnelService, action: (ITunnelService) -> Unit) {
        try {
            action(remote)
        } catch (_: RemoteException) {
            // onServiceDisconnected will follow.
        }
    }
}
