package slop.miho.aurora.sub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import slop.miho.aurora.db.ProfileType
import slop.miho.aurora.db.computeLinkKey

/**
 * The share-link parsers, exercised on the shapes that actually appear in the wild: the
 * v2rayN QR JSON dialect and the URI one, SIP002 and the legacy base64 ss form, hy2 with
 * salamander and port hopping, and a WireGuard .conf body.
 */
class LinkParsersTest {
    @Test
    fun `vmess QR JSON parses`() {
        val json = """{"v":"2","ps":"Helsinki","add":"v2.example.com","port":"443","id":"b831381d-6324-4d53-ad4f-8cda48b30811","aid":"0","net":"ws","type":"none","host":"cdn.example.com","path":"/ws","tls":"tls","scy":"auto"}"""
        val p = LinkParsers.parse("vmess://" + java.util.Base64.getEncoder().encodeToString(json.toByteArray()))
        assertNotNull(p)
        p!!
        assertEquals(ProfileType.VMESS, p.type)
        assertEquals("Helsinki", p.remarks)
        assertEquals("v2.example.com", p.server)
        assertEquals(443, p.serverPort)
        assertEquals("b831381d-6324-4d53-ad4f-8cda48b30811", p.password)
        assertEquals("ws", p.network)
        assertEquals("/ws", p.path)
        assertEquals("tls", p.security)
    }

    @Test
    fun `vmess URI parses`() {
        val p = LinkParsers.parse(
            "vmess://b831381d-6324-4d53-ad4f-8cda48b30811@1.2.3.4:8000?type=tcp&security=none#remarks",
        )
        assertNotNull(p)
        p!!
        assertEquals(ProfileType.VMESS, p.type)
        assertEquals("1.2.3.4", p.server)
        assertEquals(8000, p.serverPort)
        assertEquals("remarks", p.remarks)
    }

    @Test
    fun `vless reality parses`() {
        val p = LinkParsers.parse(
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@1.2.3.4:443?" +
                "encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.google.com" +
                "&fp=chrome&pbk=PUBLICKEY&sid=ab12&spx=%2F#RealityNode",
        )
        assertNotNull(p)
        p!!
        assertEquals(ProfileType.VLESS, p.type)
        assertEquals("xtls-rprx-vision", p.flow)
        assertEquals("reality", p.security)
        assertEquals("www.google.com", p.sni)
        assertEquals("chrome", p.fingerprint)
        assertEquals("PUBLICKEY", p.publicKey)
        assertEquals("ab12", p.shortId)
    }

    @Test
    fun `ss SIP002 with plugin parses`() {
        val p = LinkParsers.parse(
            "ss://" + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("aes-256-gcm:pass123".toByteArray()) +
                "@1.2.3.4:8388?plugin=v2ray-plugin%3Dmode%3Dwebsocket#SipNode",
        )
        assertNotNull(p)
        p!!
        assertEquals(ProfileType.SHADOWSOCKS, p.type)
        assertEquals("aes-256-gcm", p.method)
        assertEquals("pass123", p.password)
        assertEquals(8388, p.serverPort)
        assertEquals("SipNode", p.remarks)
    }

    @Test
    fun `ss legacy base64 parses`() {
        val whole = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-128-gcm:legacy@1.2.3.4:8388".toByteArray())
        val p = LinkParsers.parse("ss://$whole#Legacy")
        assertNotNull(p)
        p!!
        assertEquals(ProfileType.SHADOWSOCKS, p.type)
        assertEquals("aes-128-gcm", p.method)
        assertEquals("legacy", p.password)
        assertEquals(8388, p.serverPort)
    }

    @Test
    fun `hy2 with obfs and hopping parses`() {
        val p = LinkParsers.parse(
            "hy2://letmein@1.2.3.4:443?obfs=salamander&obfs-password=obfspass" +
                "&mport=20000-20100&mportinterval=30#Hy2Node",
        )
        assertNotNull(p)
        p!!
        assertEquals(ProfileType.HYSTERIA2, p.type)
        assertEquals("letmein", p.password)
        assertEquals("obfspass", p.obfsPassword)
        assertEquals(443, p.serverPort)
        assertEquals("20000-20100", p.portHopping)
        assertEquals("30", p.portHoppingInterval)
    }

    @Test
    fun `socks parses`() {
        val p = LinkParsers.parse("socks://user:pass@1.2.3.4:1080#SocksNode")
        assertNotNull(p)
        p!!
        assertEquals(ProfileType.SOCKS, p.type)
        assertEquals("user", p.username)
        assertEquals("pass", p.password)
        assertEquals(1080, p.serverPort)
    }

    @Test
    fun `wireguard conf parses`() {
        val conf = """
            [Interface]
            PrivateKey = ABCDEF=
            Address = 10.0.0.2/32, fd00::2/128
            DNS = 1.1.1.1
            MTU = 1420

            [Peer]
            PublicKey = PUBKEY=
            PresharedKey = PSK=
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = 5.6.7.8:51820
            PersistentKeepalive = 25
        """.trimIndent()
        val p = LinkParsers.parseWireguardConf(conf)
        assertNotNull(p)
        p!!
        assertEquals(ProfileType.WIREGUARD, p.type)
        assertEquals("ABCDEF=", p.wgSecretKey)
        assertEquals("10.0.0.2/32, fd00::2/128".replace(" ", ""), p.wgLocalAddress.replace(" ", ""))
        assertEquals(51820, p.serverPort)
        assertEquals("PUBKEY=", p.publicKey)
        assertEquals("PSK=", p.wgPresharedKey)
        assertEquals(1420, p.wgMtu)
    }

    @Test
    fun `garbage returns null`() {
        assertNull(LinkParsers.parse("not a link"))
        assertNull(LinkParsers.parse("vmess://not-base64-json!!!"))
    }

    @Test
    fun `linkKey is stable across remarks changes and ids`() {
        val a = LinkParsers.parse("socks://user:pass@1.2.3.4:1080#A")!!
        val b = LinkParsers.parse("socks://user:pass@1.2.3.4:1080#B")!!
        assertEquals(a.computeLinkKey(), b.computeLinkKey())
    }
}
