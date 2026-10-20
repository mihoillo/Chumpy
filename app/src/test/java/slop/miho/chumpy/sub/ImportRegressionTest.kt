package slop.miho.chumpy.sub

import org.junit.Assert.*
import org.junit.Test

class ImportRegressionTest {
    private val link = "vless://test@example.com:443?security=tls#Name"

    @Test
    fun `wrapped base64 subscription imports and deduplicates profiles`() {
        val encoded = LinkUtils.encodeBase64("$link\n$link").chunked(16).joinToString("\r\n")
        val profiles = SubscriptionParser.parse(encoded)
        assertEquals(1, profiles.size)
        assertEquals("example.com", profiles.single().server)
    }

    @Test
    fun `base64 nested URL is recognized`() {
        assertEquals("https://example.com/sub", SubscriptionParser.isNestedSubscriptionUrl(
            LinkUtils.encodeBase64("https://example.com/sub"),
        ))
    }

    @Test
    fun `scheme is case insensitive and fragment is decoded exactly once`() {
        val profile = LinkParsers.parse("VMESS://" + LinkUtils.encodeBase64(
            """{"add":"example.com","port":"443","id":"test","ps":"name"}""",
        ))
        assertNotNull(profile)
        assertEquals("literal%20name", LinkParsers.parse(link.substringBefore('#') + "#literal%2520name")!!.remarks)
    }

    @Test
    fun `invalid endpoints are rejected and a plain vless URI can be imported`() {
        assertNull(LinkParsers.parse("vless://test@:443?security=tls"))
        assertNull(LinkParsers.parse("vless://test@example.com:65536?security=tls"))
        assertNull(LinkParsers.parse("vless://test@example.com?security=tls"))
        assertNotNull(LinkParsers.parse("vless://test@example.com:443"))
    }

    @Test
    fun `vmess URI works with one query parameter or none`() {
        assertEquals("ws", LinkParsers.parse("vmess://test@example.com:443?type=ws")!!.network)
        assertEquals("test", LinkParsers.parse("vmess://test@example.com:443")!!.password)
    }

    @Test
    fun `hysteria transport stays canonical when query parameters are present`() {
        assertEquals("hysteria", LinkParsers.parse("hy2://test@example.com?sni=example.com")!!.network)
        assertNull(LinkParsers.parse("ss://invalid@example.com:443"))
    }

    @Test
    fun `raw websocket config preserves host header`() {
        val profile = ConfigParser.parse("""{
            "outbounds":[{"protocol":"vless","settings":{"vnext":[{
                "address":"example.com","port":443,"users":[{"id":"test"}]
            }]},"streamSettings":{"network":"ws","wsSettings":{
                "path":"/socket","headers":{"Host":"front.example.com"}
            }}}]
        }""")!!.single()
        assertEquals("front.example.com", profile.host)
        assertEquals("/socket", profile.path)
    }
}
