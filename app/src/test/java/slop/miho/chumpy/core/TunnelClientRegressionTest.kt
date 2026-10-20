package slop.miho.chumpy.core

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TunnelClientRegressionTest {
    @Test
    fun `failed native delay is reported as a lost probe`() {
        var delay = -1L
        val remote = object : ITunnelService.Stub() {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun connectedAtMillis() = 0L
            override fun trafficBytes() = longArrayOf(0, 0, 1)
            override fun measureDelay(configJson: String?, testUrl: String?) = delay
            override fun measureLiveDelay(testUrl: String?) = delay
            override fun coreVersion() = "test"
            override fun registerCallback(callback: ITunnelCallback?) = Unit
            override fun unregisterCallback(callback: ITunnelCallback?) = Unit
        }
        val client = TunnelClient(RuntimeEnvironment.getApplication())
        TunnelClient::class.java.getDeclaredField("service").apply { isAccessible = true }.set(client, remote)
        assertNull(client.measureLiveDelay("https://example.com"))
        assertNull(client.measureDelay("{}", "https://example.com"))
        delay = 0L
        assertEquals(0L, client.measureLiveDelay("https://example.com"))
        delay = 123L
        assertEquals(123L, client.measureLiveDelay("https://example.com"))
    }
}
