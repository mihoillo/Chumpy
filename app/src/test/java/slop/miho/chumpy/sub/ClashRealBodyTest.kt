package slop.miho.chumpy.sub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import slop.miho.chumpy.db.ProfileType

/**
 * The real panel clash body — every proxy shape the panel serves, end to end:
 * vless over tcp/reality, grpc with a block alpn list, and ws entries, with quoted
 * emoji names and group/rule sections the parser must step around.
 */
class ClashRealBodyTest {
    private fun realBody(): String = javaClass.getResourceAsStream("/clash_sample.yaml")!!
        .readBytes().decodeToString()

    @Test
    fun `the real panel body parses`() {
        val profiles = ClashYamlParser.parse(realBody())
        assertNotNull(profiles)
        profiles!!
        // 53 entries in the body; identical servers (same endpoint under different
        // location labels) collapse.
        assertEquals(36, profiles.size)
        assertTrue("types", profiles.all { it.type == ProfileType.VLESS || it.type == ProfileType.TROJAN })
        assertEquals(35, profiles.count { it.type == ProfileType.VLESS })
        // The body's one trojan entry, next to the vless fleet.
        val trojan = profiles.single { it.type == ProfileType.TROJAN }
        assertEquals("🇩🇪  Германия #2", trojan.remarks)
        assertEquals("sanitized-trojan-password", trojan.password)
        assertEquals("grpc", trojan.network)
        assertEquals("ajv", trojan.serviceName)
        assertEquals("h2", trojan.alpn)
        assertEquals("de2.example.org", trojan.sni)
        assertTrue("endpoints", profiles.all { it.server.isNotEmpty() && it.serverPort > 0 })
        assertTrue("vless passwords", profiles.filter { it.type == ProfileType.VLESS }.all {
            it.password == "00000000-1111-2222-3333-444444444444" ||
                it.password == "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        })

        // The three transports the panel serves, all present.
        assertTrue("reality", profiles.any { it.network == "tcp" && it.security == "reality" })
        assertTrue("grpc", profiles.any { it.network == "grpc" && it.alpn == "h2" && it.serviceName.isNotEmpty() })
        assertTrue("ws", profiles.any { it.network == "ws" && it.path.isNotEmpty() })

        // Spot-check the first reality entry, field by field.
        val first = profiles.first { it.network == "tcp" && it.security == "reality" }
        assertEquals("reality", first.security)
        assertEquals(443, first.serverPort)
        assertEquals("edge", first.fingerprint)
        assertTrue(first.publicKey.isNotEmpty())
        assertTrue(first.shortId.isNotEmpty())
        assertEquals("xtls-rprx-vision", first.flow)
    }
}
