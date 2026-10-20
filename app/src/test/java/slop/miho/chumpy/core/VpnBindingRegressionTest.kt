package slop.miho.chumpy.core

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
}
