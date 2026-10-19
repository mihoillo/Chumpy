package slop.miho.chumpy.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import slop.miho.chumpy.db.ProfileEntity
import slop.miho.chumpy.db.ProfileType
import slop.miho.chumpy.sub.LinkParsers

class Hysteria2OutboundTest {

    /**
     * Rows imported before the parser stamped network="hysteria" (and any row whose
     * network never made it in) must still build the hysteria transport — the core
     * answers a bare "tcp" there with "not hysteria transport".
     */
    @Test
    fun `hysteria2 builds its transport without the network field`() {
        val legacy = ProfileEntity(
            id = "",
            subscriptionId = null,
            type = ProfileType.HYSTERIA2,
            remarks = "old row",
            server = "hy.example.com",
            serverPort = 443,
            password = "sekret",
            security = "tls",
            network = "",
        )
        val stream = OutboundBuilder.build(legacy)["streamSettings"]!!.jsonObject
        assertEquals("hysteria", stream["network"]!!.jsonPrimitive.content)
        val hysteria = stream["hysteriaSettings"]!!.jsonObject
        assertEquals(2, hysteria["version"]!!.jsonPrimitive.content.toInt())
        assertEquals("sekret", hysteria["auth"]!!.jsonPrimitive.content)
        val tls = stream["tlsSettings"]!!.jsonObject
        assertEquals(listOf("h3"), tls["alpn"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `parsed hysteria2 link builds the same transport`() {
        val profile = LinkParsers.parse("hysteria2://sekret@hy.example.com:443?sni=hy.example.com#hy")!!
        val outbound = OutboundBuilder.build(profile)
        assertEquals("hysteria", outbound["protocol"]!!.jsonPrimitive.content)
        val stream = outbound["streamSettings"]!!.jsonObject
        assertEquals("hysteria", stream["network"]!!.jsonPrimitive.content)
        assertTrue(stream["hysteriaSettings"]!!.jsonObject.containsKey("auth"))
        assertEquals("hy.example.com", stream["tlsSettings"]!!.jsonObject["serverName"]!!.jsonPrimitive.content)
    }
}
