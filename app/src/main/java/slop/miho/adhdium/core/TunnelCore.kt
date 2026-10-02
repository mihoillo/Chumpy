package slop.miho.adhdium.core

import android.content.Context
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.util.Log
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import slop.miho.adhdium.R
import slop.miho.adhdium.data.ProfileStore
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Owner of the wireguard-go backend. Lives only in the ":tunnel" process, which stays alive
 * while the VPN is up (the system keeps GoBackend's VpnService bound), independently of the UI.
 */
object TunnelCore {
    private const val TAG = "ADHDium/TunnelCore"
    private const val TUNNEL_NAME = "adhdium"

    private lateinit var context: Context
    private lateinit var backend: GoBackend

    // GoBackend is not thread-safe and its state changes block (DNS retries, tun setup).
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "tunnel-worker") }
    private val callbacks = RemoteCallbackList<ITunnelCallback>()

    @Volatile
    private var status = TunnelStatus()

    @Volatile
    private var connectedAtMillis = 0L

    private val tunnel = object : Tunnel {
        override fun getName() = TUNNEL_NAME

        override fun onStateChange(newState: Tunnel.State) {
            // Also fires when the system tears the VpnService down (revoked, another VPN took over).
            if (newState == Tunnel.State.DOWN && status.state == TunnelState.UP) {
                publish(TunnelStatus(TunnelState.DOWN))
            }
        }
    }

    fun init(context: Context) {
        if (::backend.isInitialized) return
        this.context = context.applicationContext
        backend = GoBackend(this.context)
        GoBackend.setAlwaysOnCallback { connect() }
    }

    fun connect() {
        if (status.state == TunnelState.UP || status.state == TunnelState.CONNECTING) return
        publish(TunnelStatus(TunnelState.CONNECTING))
        worker.execute {
            try {
                val config = ProfileStore.readConfig(context)
                    ?: throw IllegalStateException(context.getString(R.string.error_no_config))
                backend.setState(tunnel, Tunnel.State.UP, config)
                publish(TunnelStatus(TunnelState.UP))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bring tunnel up", e)
                publish(TunnelStatus(TunnelState.DOWN, describe(e)))
            }
        }
    }

    fun disconnect() {
        if (status.state == TunnelState.DOWN || status.state == TunnelState.DISCONNECTING) return
        publish(TunnelStatus(TunnelState.DISCONNECTING))
        worker.execute {
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                publish(TunnelStatus(TunnelState.DOWN))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bring tunnel down", e)
                publish(TunnelStatus(TunnelState.DOWN, describe(e)))
            }
        }
    }

    /** Latest known status; safe to read from any thread. */
    fun currentStatus(): TunnelStatus = status

    fun register(callback: ITunnelCallback) {
        callbacks.register(callback)
        val current = status
        try {
            callback.onStateChanged(current.state.ordinal, current.error)
        } catch (_: RemoteException) {
            callbacks.unregister(callback)
        }
    }

    fun unregister(callback: ITunnelCallback) {
        callbacks.unregister(callback)
    }

    /** When the tunnel last came up (0 while down); the real VPN uptime, not the handshake. */
    fun connectedAtMillis(): Long = connectedAtMillis

    /**
     * Total {rx, tx} bytes moved by the tunnel since it came up; zeros while down. The query
     * itself queues on the worker behind any state change — GoBackend is not thread-safe — so
     * a connect in flight can hold this briefly; the caller waits it out on the binder thread.
     */
    fun trafficBytes(): LongArray {
        if (status.state != TunnelState.UP) return longArrayOf(0L, 0L)
        val stats = try {
            worker.submit(
                Callable { runCatching { backend.getStatistics(tunnel) }.getOrNull() },
            ).get(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // The worker is busy with a state change or the query timed out: no numbers now.
            null
        }
        return longArrayOf(stats?.totalRx() ?: 0L, stats?.totalTx() ?: 0L)
    }

    private fun publish(newStatus: TunnelStatus) {
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

    private fun describe(e: Exception): String = when (e) {
        is BackendException -> when (e.reason) {
            BackendException.Reason.VPN_NOT_AUTHORIZED -> context.getString(R.string.error_vpn_not_authorized)
            BackendException.Reason.DNS_RESOLUTION_FAILURE ->
                context.getString(R.string.error_dns_resolution, e.format.firstOrNull()?.toString().orEmpty())
            BackendException.Reason.UNABLE_TO_START_VPN -> context.getString(R.string.error_unable_to_start_vpn)
            BackendException.Reason.TUN_CREATION_ERROR -> context.getString(R.string.error_tun_creation)
            else -> context.getString(R.string.error_backend, e.reason.name)
        }
        else -> e.message ?: context.getString(R.string.error_unknown)
    }
}
