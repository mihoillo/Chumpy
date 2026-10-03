package slop.miho.aurora.sub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import slop.miho.aurora.db.ProfileType
import slop.miho.aurora.db.balancerMemberKeys
import slop.miho.aurora.db.computeLinkKey

/**
 * The raw-config subscription dialect, exercised on the shapes panels actually serve: an
 * array of single-server configs named by their "remarks" with aggregator bundles mixed
 * in, one standalone config with several outbounds, and the flat settings dialect the
 * app's own builder writes — plus the verdicts that keep it out of the other dialects.
 */
class ConfigParserTest {
    /** One server's config page: the vless+reality shape panel-style panels serve. */
    private val single = """
        {
          "remarks": "🇩🇪 Германия #3",
          "outbounds": [
            {
              "tag": "🇩🇪 Германия #3",
              "protocol": "vless",
              "settings": {
                "vnext": [
                  {
                    "address": "1.2.3.4",
                    "port": 443,
                    "users": [
                      { "id": "00000000-1111-2222-3333-444444444444", "encryption": "none", "flow": "xtls-rprx-vision" }
                    ]
                  }
                ]
              },
              "streamSettings": {
                "network": "tcp",
                "security": "reality",
                "realitySettings": {
                  "serverName": "pl4.example.org",
                  "publicKey": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                  "shortId": "0000000000000000",
                  "fingerprint": "edge"
                }
              }
            },
            { "tag": "direct", "protocol": "freedom", "settings": {} },
            { "tag": "block", "protocol": "blackhole", "settings": {} }
          ],
          "routing": { "rules": [] },
          "dns": { "servers": [{ "address": "https://77.88.8.8/dns-query" }] }
        }
    """

    /** The aggregator: the same server again — plus one more — under group labels. */
    private val aggregator = """
        {
          "remarks": "🇪🇺⚡️ Автовыбор",
          "outbounds": [
            {
              "tag": "auto-🇩🇪 Германия #3",
              "protocol": "vless",
              "settings": {
                "vnext": [
                  {
                    "address": "1.2.3.4",
                    "port": 443,
                    "users": [
                      { "id": "00000000-1111-2222-3333-444444444444", "encryption": "none", "flow": "xtls-rprx-vision" }
                    ]
                  }
                ]
              },
              "streamSettings": {
                "network": "tcp",
                "security": "reality",
                "realitySettings": {
                  "serverName": "pl4.example.org",
                  "publicKey": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                  "shortId": "0000000000000000",
                  "fingerprint": "edge"
                }
              }
            },
            {
              "tag": "auto-🇬🇧 Англия",
              "protocol": "vless",
              "settings": {
                "vnext": [
                  {
                    "address": "1.2.3.5",
                    "port": 443,
                    "users": [
                      { "id": "00000000-1111-2222-3333-444444444444", "encryption": "none", "flow": "xtls-rprx-vision" }
                    ]
                  }
                ]
              },
              "streamSettings": {
                "network": "tcp",
                "security": "reality",
                "realitySettings": {
                  "serverName": "pl4.example.org",
                  "publicKey": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                  "shortId": "0000000000000000",
                  "fingerprint": "edge"
                }
              }
            },
            { "tag": "direct", "protocol": "freedom", "settings": {} }
          ]
        }
    """

    @Test
    fun `a single-server config parses with remarks as the name`() {
        val profiles = ConfigParser.parse(single)
        assertNotNull(profiles)
        val p = profiles!!.single()
        assertEquals(ProfileType.VLESS, p.type)
        assertEquals("🇩🇪 Германия #3", p.remarks)
        assertEquals("1.2.3.4", p.server)
        assertEquals(443, p.serverPort)
        assertEquals("00000000-1111-2222-3333-444444444444", p.password)
        assertEquals("none", p.method)
        assertEquals("xtls-rprx-vision", p.flow)
        assertEquals("tcp", p.network)
        assertEquals("reality", p.security)
        assertEquals("pl4.example.org", p.sni)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", p.publicKey)
        assertEquals("0000000000000000", p.shortId)
        assertEquals("edge", p.fingerprint)
    }

    @Test
    fun `aggregator copies of individually-named servers stay out`() {
        val profiles = ConfigParser.parse("[$single,$aggregator]")
        // Germany is named by its individual config's remarks; England only exists in
        // the aggregator, so it joins under its group tag.
        assertEquals(2, profiles!!.size)
        val germany = profiles.first { it.server == "1.2.3.4" }
        assertEquals("🇩🇪 Германия #3", germany.remarks)
        val england = profiles.first { it.server == "1.2.3.5" }
        assertEquals("auto-🇬🇧 Англия", england.remarks)
    }

    @Test
    fun `a lone aggregator still hands over its servers`() {
        val profiles = ConfigParser.parse(aggregator)
        assertEquals(2, profiles!!.size)
        assertEquals(setOf("auto-🇩🇪 Германия #3", "auto-🇬🇧 Англия"), profiles.map { it.remarks }.toSet())
    }

    @Test
    fun `the flat settings dialect round-trips`() {
        val flat = """
            {
              "outbounds": [
                {
                  "tag": "flat",
                  "protocol": "vless",
                  "settings": {
                    "address": "example.com",
                    "port": 8443,
                    "id": "b831381d-6324-4d53-ad4f-8cda48b30811",
                    "encryption": "none",
                    "flow": "",
                    "level": 8
                  },
                  "streamSettings": {
                    "network": "ws",
                    "security": "tls",
                    "tlsSettings": { "serverName": "example.com", "alpn": ["h2", "http/1.1"] },
                    "wsSettings": { "path": "/ws", "host": "cdn.example.com" }
                  }
                }
              ]
            }
        """
        val p = ConfigParser.parse(flat)!!.single()
        assertEquals(ProfileType.VLESS, p.type)
        assertEquals("example.com", p.server)
        assertEquals(8443, p.serverPort)
        assertEquals("b831381d-6324-4d53-ad4f-8cda48b30811", p.password)
        assertEquals("tls", p.security)
        assertEquals("h2,http/1.1", p.alpn)
        assertEquals("ws", p.network)
        assertEquals("/ws", p.path)
        assertEquals("cdn.example.com", p.host)
    }

    @Test
    fun `share-link bodies are not this dialect`() {
        assertNull(ConfigParser.parse("vless://b831381d-6324-4d53-ad4f-8cda48b30811@1.2.3.4:443?security=tls#x"))
        assertNull(ConfigParser.parse("[not json at all"))
        assertNull(ConfigParser.parse("""{"outbounds": "not-an-array"}"""))
        assertNull(ConfigParser.parse("""{"dns": {"servers": []}}"""))
    }

    /** The balancer-bearing bundle: the same two servers, plus a routing balancer over them. */
    private val balanced = """
        {
          "remarks": "Автовыбор",
          "outbounds": [
            {
              "tag": "auto-🇩🇪 Германия #3",
              "protocol": "vless",
              "settings": {
                "vnext": [
                  { "address": "1.2.3.4", "port": 443, "users": [ { "id": "00000000-1111-2222-3333-444444444444", "encryption": "none", "flow": "xtls-rprx-vision" } ] }
                ]
              },
              "streamSettings": { "network": "tcp", "security": "reality", "realitySettings": { "serverName": "pl4.example.org", "publicKey": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "shortId": "0000000000000000", "fingerprint": "edge" } }
            },
            {
              "tag": "auto-🇬🇧 Англия",
              "protocol": "vless",
              "settings": {
                "vnext": [
                  { "address": "1.2.3.5", "port": 443, "users": [ { "id": "00000000-1111-2222-3333-444444444444", "encryption": "none", "flow": "xtls-rprx-vision" } ] }
                ]
              },
              "streamSettings": { "network": "tcp", "security": "reality", "realitySettings": { "serverName": "pl4.example.org", "publicKey": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "shortId": "0000000000000000", "fingerprint": "edge" } }
            }
          ],
          "routing": {
            "balancers": [
              { "tag": "auto_Balancer", "selector": ["auto-"], "strategy": { "type": "leastPing" } }
            ]
          }
        }
    """

    @Test
    fun `a config's balancer parses as a profile over its members' linkKeys`() {
        val profiles = ConfigParser.parse("[$single,$balanced]")!!
        val balancer = profiles.first { it.type == ProfileType.BALANCER }
        // One balancer with a remarks prefix: the prefix is the name, the strategy
        // normalized to what the core switches on.
        assertEquals("Автовыбор", balancer.remarks)
        assertEquals("leastping", balancer.balancerStrategy)
        // The members ride as linkKeys and resolve to the servers the array carries —
        // including the one the single config named first, deduped into one row.
        val keys = balancer.balancerMemberKeys.toSet()
        assertEquals(2, keys.size)
        val servers = profiles.filter { it.type != ProfileType.BALANCER }
        assertTrue(servers.any { it.computeLinkKey() in keys && it.remarks == "🇩🇪 Германия #3" })
        assertTrue(servers.any { it.computeLinkKey() in keys && it.remarks == "auto-🇬🇧 Англия" })
    }

    @Test
    fun `a balancer under two members is dropped, its fallback carries it`() {
        fun body(selector: List<String>, fallback: String?) = """
            {
              "outbounds": [
                { "tag": "a", "protocol": "vless", "settings": { "vnext": [ { "address": "1.1.1.1", "port": 443, "users": [ { "id": "00000000-1111-2222-3333-444444444444", "encryption": "none" } ] } ] } },
                { "tag": "b", "protocol": "vless", "settings": { "vnext": [ { "address": "2.2.2.2", "port": 443, "users": [ { "id": "00000000-1111-2222-3333-444444444444", "encryption": "none" } ] } ] } }
              ],
              "routing": {
                "balancers": [
                  { "tag": "x", "selector": ${if (selector.isEmpty()) "[]" else selector.joinToString(prefix = "[", separator = ",", postfix = "]") { "\"$it\"" }}, "fallbackTag": ${fallback?.let { "\"$it\"" } ?: "null"} }
                ]
              }
            }
        """

        // A selector that matches only one of two outbounds, no fallback: not worth an entry.
        assertNull(ConfigParser.parse(body(listOf("a"), null))?.firstOrNull { it.type == ProfileType.BALANCER })
        // With the fallback joining in: a degraded balancer still beats none.
        val carried = ConfigParser.parse(body(listOf("a"), "b"))!!
        assertEquals(1, carried.count { it.type == ProfileType.BALANCER })
        assertEquals(2, carried.first { it.type == ProfileType.BALANCER }.balancerMemberKeys.size)
    }

    @Test
    fun `unknown strategies fall back to random`() {
        val body = balanced.replace("\"leastPing\"", "\"chaos\"")
        val balancer = ConfigParser.parse(body)!!.first { it.type == ProfileType.BALANCER }
        assertEquals("random", balancer.balancerStrategy)
    }

    @Test
    fun `SubscriptionParser hands raw configs through with the https DNS trap`() {
        // The DNS block carries "https://" — the link-list reader would swallow the body.
        val profiles = SubscriptionParser.parse("[$single,$aggregator]")
        assertEquals(2, profiles.size)
        val germany = profiles.first { it.server == "1.2.3.4" }
        assertEquals(ProfileType.VLESS, germany.type)
        assertEquals("🇩🇪 Германия #3", germany.remarks)

        // Base64-wrapped configs land through the same door.
        val wrapped = java.util.Base64.getEncoder().encodeToString("[$single]".toByteArray())
        assertEquals(1, SubscriptionParser.parse(wrapped).size)
    }

    @Test
    fun `link lists still parse after the new door`() {
        val profiles = SubscriptionParser.parse(
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@1.2.3.4:443?security=tls&type=tcp#x\n" +
                "trojan://pass@5.6.7.8:443?security=tls#y",
        )
        assertEquals(2, profiles.size)
        assertTrue(profiles.all { it.server.isNotEmpty() })
    }

    @Test
    fun `the real panelsub payload parses and its members hide`() {
        val body = javaClass.getResourceAsStream("/panelsub.json")!!
            .readBytes().decodeToString()
        val parsed = ConfigParser.parse(body)!!
        val balancers = parsed.filter { it.type == ProfileType.BALANCER }
        println("BALANCERS: " + balancers.map { "${it.remarks} -> ${it.balancerStrategy}, members=${it.balancerMemberKeys.size}" })
        val servers = parsed.filter { it.type != ProfileType.BALANCER }
        println("SERVERS: " + servers.size + " " + servers.map { it.remarks })
        // The store stamps linkKeys the way the repository does; the screen hides members.
        val stamped = servers.mapIndexed { i, p -> p.copy(linkKey = p.computeLinkKey(), order = i.toLong()) }
        val balancerRows = balancers.mapIndexed { i, p -> p.copy(linkKey = p.computeLinkKey(), order = i.toLong()) }
        val all = balancerRows + stamped
        val memberKeys = all.asSequence().filter { it.type == ProfileType.BALANCER }
            .flatMap { it.balancerMemberKeys.asSequence() }.toSet()
        val visible = all.filter { it.type == ProfileType.BALANCER || it.linkKey !in memberKeys }
        println("VISIBLE: " + visible.size + " " + visible.map { it.remarks })
        // The third balancer (WL-1) is all-hysteria outbounds — a protocol this core
        // does not speak — so parsing drops it whole; its members never stand anywhere.
        assertEquals(2, balancers.size)
        assertEquals(7, visible.filter { it.type != ProfileType.BALANCER }.size)
        assertTrue(visible.none { it.remarks.startsWith("wl") })
    }

}
