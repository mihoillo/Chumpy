package slop.miho.chumpy.sub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import slop.miho.chumpy.db.ProfileType

/**
 * A panel-style clash body — every proxy shape a panel serves, end to end: vless over
 * tcp/reality, grpc with a block alpn list, and ws entries, a trojan next to the vless
 * fleet, with quoted emoji names, a duplicate endpoint that collapses, and group/rule
 * sections the parser must step around.
 */
class ClashPanelBodyTest {
    private fun panelBody(): String = """
        proxies:
        - name: '🇩🇪  Тестия #1'
          type: vless
          server: 1.2.3.4
          port: 443
          uuid: 00000000-1111-2222-3333-444444444444
          network: tcp
          udp: true
          tls: true
          servername: de4.example.org
          client-fingerprint: edge
          reality-opts:
            public-key: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            short-id: '0000000000000000'
          flow: xtls-rprx-vision
        - name: '🇩🇪  Тестия #1 (копия)'
          type: vless
          server: 1.2.3.4
          port: 443
          uuid: 00000000-1111-2222-3333-444444444444
          network: tcp
          udp: true
          tls: true
          servername: de4.example.org
          client-fingerprint: edge
          reality-opts:
            public-key: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            short-id: '0000000000000000'
          flow: xtls-rprx-vision
        - name: '🇬🇧  Тестланд #2'
          type: vless
          server: 1.2.3.5
          port: 443
          uuid: 00000000-1111-2222-3333-444444444444
          network: grpc
          udp: true
          tls: true
          servername: gb4.example.org
          client-fingerprint: chrome
          grpc-opts:
            grpc-service-name: vlgrpc
          alpn:
          - h2
        - name: '🇫🇮  Тестия #3'
          type: vless
          server: 1.2.3.6
          port: 443
          uuid: 00000000-1111-2222-3333-444444444444
          network: ws
          udp: true
          ws-opts:
            path: /api-test
        - name: '🇳🇱  Тест #4'
          type: trojan
          server: 1.2.3.7
          port: 443
          password: fake-trojan-password
          udp: true
          sni: nl4.example.org
          alpn:
          - h2
          network: grpc
          tls: true
          client-fingerprint: chrome
          grpc-opts:
            grpc-service-name: ajv
        proxy-groups:
        - name: PROXY
          type: select
          proxies:
          - auto
          - DIRECT
          - '🇩🇪  Тестия #1'
        - name: auto
          type: url-test
          proxies:
          - '🇩🇪  Тестия #1'
          - '🇬🇧  Тестланд #2'
          url: http://www.gstatic.com/generate_204
          interval: 300
        rules:
        - DOMAIN-SUFFIX,example.com,DIRECT
        - RULE-SET,category-ru,DIRECT
        - MATCH,PROXY
    """.trimIndent()

    @Test
    fun `a panel body parses every shape it serves`() {
        val profiles = ClashYamlParser.parse(panelBody())
        assertNotNull(profiles)
        profiles!!
        // Five entries in the body; the duplicate endpoint — the same server under a
        // second label — collapses.
        assertEquals(4, profiles.size)
        assertTrue("types", profiles.all { it.type == ProfileType.VLESS || it.type == ProfileType.TROJAN })
        assertEquals(3, profiles.count { it.type == ProfileType.VLESS })
        // The body's one trojan entry, next to the vless fleet.
        val trojan = profiles.single { it.type == ProfileType.TROJAN }
        assertEquals("🇳🇱  Тест #4", trojan.remarks)
        assertEquals("fake-trojan-password", trojan.password)
        assertEquals("grpc", trojan.network)
        assertEquals("ajv", trojan.serviceName)
        assertEquals("h2", trojan.alpn)
        assertEquals("nl4.example.org", trojan.sni)
        assertTrue("endpoints", profiles.all { it.server.isNotEmpty() && it.serverPort > 0 })
        assertTrue("vless passwords", profiles.filter { it.type == ProfileType.VLESS }.all {
            it.password == "00000000-1111-2222-3333-444444444444"
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
