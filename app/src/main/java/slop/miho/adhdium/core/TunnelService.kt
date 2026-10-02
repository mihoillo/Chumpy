package slop.miho.adhdium.core

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** AIDL facade over [TunnelCore] for the UI process. Runs in ":tunnel". */
class TunnelService : Service() {
    private val binder = object : ITunnelService.Stub() {
        override fun connect() = TunnelCore.connect()

        override fun disconnect() = TunnelCore.disconnect()

        override fun connectedAtMillis(): Long = TunnelCore.connectedAtMillis()

        override fun trafficBytes(): LongArray = TunnelCore.trafficBytes()

        override fun registerCallback(callback: ITunnelCallback) = TunnelCore.register(callback)

        override fun unregisterCallback(callback: ITunnelCallback) = TunnelCore.unregister(callback)
    }

    override fun onBind(intent: Intent): IBinder = binder
}
