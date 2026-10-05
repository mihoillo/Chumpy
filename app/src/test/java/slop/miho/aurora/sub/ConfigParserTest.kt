package slop.miho.aurora.sub

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `the real panelsub payload parses and its members stand`() {
        val body = javaClass.getResourceAsStream("/panelsub.json")!!
            .readBytes().decodeToString()
        val parsed = ConfigParser.parse(body)!!
        val balancers = parsed.filter { it.type == ProfileType.BALANCER }
        println("BALANCERS: " + balancers.map { "${it.remarks} -> ${it.balancerStrategy}, members=${it.balancerMemberKeys.size}" })
        val servers = parsed.filter { it.type != ProfileType.BALANCER }
        println("SERVERS: " + servers.size + " " + servers.map { it.remarks })
        // The store stamps linkKeys the way the repository does — one pass, the parse's
        // own order kept. Balancers and their members stand together now: the members
        // are the servers the balancer picks between, still the user's to choose by
        // hand — a pick-everything group is most of what a panel ships.
        val all = parsed.mapIndexed { i, p -> p.copy(linkKey = p.computeLinkKey(), order = i.toLong()) }
        println("ALL: " + all.map { it.remarks })
        // The third balancer (WL-1) is all-hysteria outbounds — a protocol this core
        // does not speak — so parsing drops it whole; its members never stand anywhere.
        assertEquals(2, balancers.size)
        assertEquals(26, servers.size)
        // The list runs in the panel's own order: the seven singles as the configs
        // come, the balancers where their configs stand (WL-2 mid-list, WL-3 later)
        // — and the members they pick between right where theirs do, trailing them.
        assertEquals("🇪🇪WL-2", all.drop(7).first().remarks)
        assertEquals("wlv-7", all.last().remarks)
        assertTrue(all.first().remarks.contains("Эстония"))
    }

    @Test
    fun `aggregator-first bodies name their servers by the solo configs`() {
        // The panel shape: the pick-everything aggregator leads the array, the
        // servers' own configs trail it. The aggregator's copy holds the place, but a
        // server with a config of its own is named by that config — no "auto-" group
        // labels in the list. A member no solo config ever names keeps the group's tag.
        val england = single
            .replace("🇩🇪 Германия #3", "🇬🇧 Англия")
            .replace("1.2.3.4", "1.2.3.5")
        val profiles = ConfigParser.parse("[$balanced,$single,$england]")!!
        assertEquals(3, profiles.size)
        val servers = profiles.filter { it.type != ProfileType.BALANCER }
        assertTrue(servers.any { it.remarks == "🇩🇪 Германия #3" })
        assertTrue(servers.any { it.remarks == "🇬🇧 Англия" })
        assertTrue(servers.none { it.remarks.startsWith("auto-") })
    }

    @Test
    fun `a pick-everything balancer keeps its members in the list`() {
        // The mealkitty shape: one config, every outbound a member of the single
        // balancer its routing sends everything to. The members are every server the
        // panel ships — the balancer is a pick over them, not a replacement for them.
        val body = """
            {
              "routing": {
                "rules": [ { "type": "field", "network": "tcp,udp", "balancerTag": "Super_Balancer" } ],
                "balancers": [ { "tag": "Super_Balancer", "selector": ["proxy"], "strategy": { "type": "leastLoad" } } ]
              },
              "outbounds": [
                {
                  "tag": "proxy",
                  "protocol": "vless",
                  "settings": { "vnext": [ { "address": "1.2.3.8", "port": 443, "users": [ { "id": "00000000-1111-2222-3333-444444444444", "encryption": "none" } ] } ] },
                  "streamSettings": { "network": "grpc", "grpcSettings": { "serviceName": "vless-grpc" }, "security": "reality", "realitySettings": { "serverName": "okmarket.example.org", "publicKey": "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC", "shortId": "00000000" } }
                },
                {
                  "tag": "proxy-2",
                  "protocol": "vless",
                  "settings": { "vnext": [ { "address": "1.2.3.9", "port": 443, "users": [ { "id": "00000000-1111-2222-3333-444444444444", "encryption": "none" } ] } ] },
                  "streamSettings": { "network": "grpc", "grpcSettings": { "serviceName": "vless-grpc" }, "security": "reality", "realitySettings": { "serverName": "bitrix.example.org", "publicKey": "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD", "shortId": "a1b2c3d4" } }
                },
                { "tag": "direct", "protocol": "freedom" },
                { "tag": "block", "protocol": "blackhole" }
              ]
            }
        """
        val profiles = ConfigParser.parse(body)!!
        assertEquals(3, profiles.size)
        val balancer = profiles.single { it.type == ProfileType.BALANCER }
        assertEquals("Super", balancer.remarks)
        assertEquals("leastload", balancer.balancerStrategy)
        assertEquals(2, balancer.balancerMemberKeys.size)
        // Both members parse and stand beside the balancer, pingable and pickable.
        assertEquals(2, profiles.count { it.type == ProfileType.VLESS })
    }

    /**
     * The routing rules the panel's config carried, as the parser filters them: the
     * matcherless direct catch-all and the "tcp,udp" balancer catch-all — which would
     * otherwise swallow everything — stay out; the "outbound" alias folds into
     * "outboundTag"; what keeps a matcher and a target rides every profile from the
     * config, the balancer included.
     */
    @Test
    fun `a config's routing rules ride its profiles, filtered`() {
        val body = """
            {
              "remarks": "🇪🇺 Автовыбор",
              "routing": {
                "rules": [
                    { "type": "field", "protocol": ["bittorrent"], "outboundTag": "direct" },
                    { "type": "field", "outboundTag": "direct" },
                    { "ip": ["203.0.113.0/24"], "outbound": "BLOCK" },
                    { "type": "field", "domain": ["domain:vk.ru", "domain:yandex.ru"], "outboundTag": "direct" },
                    { "type": "field", "network": "tcp,udp", "balancerTag": "panel-VPN" },
                    { "type": "field", "network": "udp", "port": "443,853", "outboundTag": "block" }
                ],
                "balancers": [ { "tag": "panel-VPN", "selector": ["proxy"], "strategy": { "type": "leastPing" } } ]
              },
              "outbounds": [
                {
                  "tag": "proxy",
                  "protocol": "vless",
                  "settings": { "vnext": [ { "address": "1.2.3.4", "port": 443, "users": [ { "id": "00000000-1111-2222-3333-444444444444", "encryption": "none" } ] } ] },
                  "streamSettings": { "network": "tcp", "security": "reality", "realitySettings": { "serverName": "pl4.example.org", "publicKey": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "shortId": "0000000000000000", "fingerprint": "edge" } }
                },
                {
                  "tag": "proxy-2",
                  "protocol": "vless",
                  "settings": { "vnext": [ { "address": "1.2.3.5", "port": 443, "users": [ { "id": "00000000-1111-2222-3333-444444444444", "encryption": "none" } ] } ] },
                  "streamSettings": { "network": "tcp", "security": "reality", "realitySettings": { "serverName": "pl4.example.org", "publicKey": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "shortId": "0000000000000000", "fingerprint": "edge" } }
                }
              ]
            }
        """.trimIndent()
        val profiles = ConfigParser.parse(body)!!
        val rules = Json.parseToJsonElement(
            profiles.first { it.type == ProfileType.VLESS }.routingRules
        ).jsonArray.map { it.jsonObject }
        val balancerRules = Json.parseToJsonElement(
            profiles.first { it.type == ProfileType.BALANCER }.routingRules
        ).jsonArray.map { it.jsonObject }

        // The BT rule, the ip block, the RU bypass and the QUIC block survive — the
        // matcherless direct and the "tcp,udp" catch-alls stay out — on the balancer too.
        assertEquals(4, rules.size)
        assertEquals(4, balancerRules.size)
        assertTrue(rules.any { rule ->
            val protocols = rule["protocol"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
            protocols == listOf("bittorrent") && rule["outboundTag"]?.jsonPrimitive?.content == "direct"
        })
        val ru = rules.first { rule -> rule["domain"]?.jsonArray.orEmpty().isNotEmpty() }
        assertEquals(listOf("domain:vk.ru", "domain:yandex.ru"), ru["domain"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("direct", ru["outboundTag"]?.jsonPrimitive?.content)
        // The "outbound" alias became "outboundTag", the original target kept verbatim —
        // the build-side rewrite is what lowercases it.
        val blocked = rules.first { rule -> rule["ip"]?.jsonArray.orEmpty().isNotEmpty() }
        assertEquals("BLOCK", blocked["outboundTag"]?.jsonPrimitive?.content)
        // The catch-alls never made it in.
        assertTrue(rules.none { it.containsKey("balancerTag") })
        assertTrue(rules.none { it["network"]?.jsonPrimitive?.content == "tcp,udp" })
    }

}
