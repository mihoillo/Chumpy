package slop.miho.chumpy.core

import java.util.concurrent.atomic.AtomicInteger
import android.app.Application
import android.content.Intent
import android.net.VpnService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class VpnBindingRegressionTest {
    @Test
    fun `system VPN binding retains the framework revocation interface`() {
        val service = ChumpyVpnService()
        val systemBinder = service.onBind(Intent(VpnService.SERVICE_INTERFACE))
        val uiBinder = service.onBind(Intent())
        assertNotNull(systemBinder)
        assertNotNull(uiBinder)
        assertNotSame(systemBinder, uiBinder)
        assertEquals("slop.miho.chumpy.core.ITunnelService", uiBinder!!.interfaceDescriptor)
        assertFalse(systemBinder is ITunnelService)
    }

    @Test fun `status publication serializes the state and callback order`() {
        val core = XrayCore
        core.publish(TunnelStatus(TunnelState.DOWN))
        val lock = core.javaClass.getDeclaredField("callbacks").apply { isAccessible = true }.get(null)!!
        val last = AtomicInteger(-1)
        val callback = object : ITunnelCallback.Stub() {
            override fun onStateChanged(state: Int, error: String?) { last.set(state) }
        }
        core.register(callback)
        lateinit var thread: Thread
        try {
            synchronized(lock) {
                thread = Thread { core.publish(TunnelStatus(TunnelState.UP)) }.also { it.start() }
                val deadline = System.nanoTime() + 2_000_000_000L
                while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals(Thread.State.BLOCKED, thread.state)
                assertEquals(TunnelState.DOWN, core.currentStatus().state)
                core.publish(TunnelStatus(TunnelState.DOWN))
            }
            thread.join(2000)
            assertFalse(thread.isAlive)
            assertEquals(core.currentStatus().state.ordinal, last.get())
        } finally { core.unregister(callback); core.publish(TunnelStatus(TunnelState.DOWN)) }
    }

}
