package slop.miho.aurora.xray

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import slop.miho.aurora.db.ProfileEntity
import slop.miho.aurora.db.ProfileType

/**
 * The outbound rendering, focused on the case the core's loader is strictest about: a
 * vless outbound must declare its security type, not just carry realitySettings —
 * "vless without TLS or other encryption is prohibited" is exactly that field missing.
 */
class OutboundBuilderTest {
    @Test
    fun `vless reality declares its security type`() {
        val profile = ProfileEntity(
            id = "1",
            subscriptionId = null,
            type = ProfileType.VLESS,
            remarks = "Reality exit",
            server = "reality.example.com",
            serverPort = 443,
            password = "b831381d-6324-4d53-ad4f-8cda48b30811",
            network = "tcp",
            security = "reality",
            flow = "xtls-rprx-vision",
            sni = "www.microsoft.com",
            fingerprint = "chrome",
            publicKey = "pbkValue",
            shortId = "sidValue",
            spiderX = "/spider",
        )
        val outbound = OutboundBuilder.build(profile)
        val stream = outbound["streamSettings"]!!.jsonObject
        assertEquals("reality", stream["security"]!!.jsonPrimitive.content)
        assertNotNull(stream["realitySettings"])
        assertEquals("xtls-rprx-vision", outbound["settings"]!!.jsonObject["flow"]!!.jsonPrimitive.content)
    }

    @Test
    fun `vmess tls declares its security type and defaults the network`() {
        val profile = ProfileEntity(
            id = "1",
            subscriptionId = null,
            type = ProfileType.VMESS,
            remarks = "TLS exit",
            server = "v2.example.com",
            serverPort = 443,
            password = "b831381d-6324-4d53-ad4f-8cda48b30811",
            network = "",
            security = "tls",
            sni = "v2.example.com",
        )
        val stream = OutboundBuilder.build(profile)["streamSettings"]!!.jsonObject
        assertEquals("tls", stream["security"]!!.jsonPrimitive.content)
        assertEquals("tcp", stream["network"]!!.jsonPrimitive.content)
        assertNotNull(stream["tlsSettings"])
    }

    @Test
    fun `plain tcp omits the stream settings section`() {
        val profile = ProfileEntity(
            id = "1",
            subscriptionId = null,
            type = ProfileType.VLESS,
            server = "192.168.1.10",
            serverPort = 1080,
            password = "b831381d-6324-4d53-ad4f-8cda48b30811",
        )
        assertTrue(OutboundBuilder.build(profile)["streamSettings"] == null)
    }
}
