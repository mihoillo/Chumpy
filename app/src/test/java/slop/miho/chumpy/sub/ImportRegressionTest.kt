package slop.miho.chumpy.sub

import slop.miho.chumpy.xray.XrayConfigBuilder
import slop.miho.chumpy.xray.OutboundBuilder
import slop.miho.chumpy.xray.RoutingMode
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

    @Test fun `encoded SOCKS components retain their boundaries`() {
        val profile = LinkParsers.parse("socks://user%3Aname:pa%3Ass@example.com:1080")!!
        assertEquals("user:name", profile.username)
        assertEquals("pa:ss", profile.password)
    }

    @Test fun `missing mandatory credentials are rejected`() {
        assertNull(LinkParsers.parse("vless://example.com:443?security=tls"))
        assertNull(LinkParsers.parse("wg://private@example.com:443?address=10.0.0.2/32"))
        assertNull(LinkParsers.parse("trojan://example.com:443"))
        assertNotNull(LinkParsers.parse("socks://example.com:1080"))
    }

    @Test fun `flow YAML and quoted passwords preserve content`() {
        val flow = "proxies:\n  - {name: Test, type: trojan, server: example.com, port: 443, password: 'it''s, a password'}"
        assertEquals("it's, a password", SubscriptionParser.parse(flow).single().password)
        val escaped = "proxies:\n  - name: Test\n    type: trojan\n    server: example.com\n    port: 443\n    password: \"a\\u003Ab\\n\" # note"
        assertEquals("a:b\n", SubscriptionParser.parse(escaped).single().password)
    }

    @Test fun `BOM does not change JSON import`() {
        val raw = """{"outbounds":[{"protocol":"socks","settings":{"address":"example.com","port":1080}}]}"""
        assertEquals(SubscriptionParser.parse(raw), SubscriptionParser.parse("\uFEFF" + raw))
    }

    @Test fun `custom outbound tags retain block and direct semantics`() {
        fun parse(protocol: String) = ConfigParser.parse("""{
            "outbounds":[{"tag":"proxy","protocol":"socks","settings":{"address":"example.com","port":1080}},
            {"tag":"custom-target","protocol":"$protocol"}],
            "routing":{"rules":[{"type":"field","domain":["domain:example.net"],"outboundTag":"custom-target"}]}
        }""")!!.single()
        assertTrue(XrayConfigBuilder.buildFull(parse("blackhole"), RoutingMode.GLOBAL).contains("\"outboundTag\":\"block\""))
        assertTrue(parse("freedom").routingRules.contains("\"outboundTag\":\"direct\""))
    }

    @Test fun `WireGuard config rejects multiple peers and JSON roundtrips one`() {
        val text = "[Interface]\nPrivateKey = private\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = public\nEndpoint = example.com:443\n"
        assertNull(LinkParsers.parseWireguardConf(text + "[Peer]\nPublicKey = other\nEndpoint = other.example:443\n"))
        val profile = LinkParsers.parseWireguardConf(text)!!
        val parsed = ConfigParser.parse("{\"outbounds\":[${OutboundBuilder.build(profile)}]}")!!.single()
        assertEquals(profile.server, parsed.server)
        assertEquals(profile.publicKey, parsed.publicKey)
        assertEquals(profile.wgSecretKey, parsed.wgSecretKey)
    }

}
