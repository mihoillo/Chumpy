package slop.miho.aurora.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import slop.miho.aurora.db.ProfileType
import slop.miho.aurora.sub.LinkParsers

class WireguardConfigTest {

    @Test
    fun `real conf builds a wireguard outbound with the secret key`() {
        val conf = """
            [Interface]
            # Панель управления: http://10.0.0.1:8080
            Address = 10.0.0.3/32
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            MTU = 1280

            [Peer]
            PublicKey = BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=
            Endpoint = wg.example.com:51820
            AllowedIPs = 10.0.0.0/16
            PersistentKeepalive = 25
        """.trimIndent()

        val profile = LinkParsers.parseWireguardConf(conf)!!
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", profile.wgSecretKey)

        val config = Json.parseToJsonElement(XrayConfigBuilder.buildFull(profile, RoutingMode.GLOBAL))
        val outbound = config.jsonObject["outbounds"]!!.jsonArray
            .first { it.jsonObject["tag"]!!.jsonPrimitive.content == "proxy" }
            .jsonObject
        assertEquals("wireguard", outbound["protocol"]!!.jsonPrimitive.content)

        val settings = outbound["settings"]!!.jsonObject
        val secretKey = settings["secretKey"]!!.jsonPrimitive.content
        assertTrue("secret key must not be empty", secretKey.isNotEmpty())
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", secretKey)

        val peers = settings["peers"]!!.jsonArray.first().jsonObject
        assertEquals("wg.example.com:51820", peers["endpoint"]!!.jsonPrimitive.content)
        assertEquals("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=", peers["publicKey"]!!.jsonPrimitive.content)
    }
}
