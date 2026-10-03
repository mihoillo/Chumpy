package slop.miho.aurora.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import slop.miho.aurora.db.ProfileEntity
import slop.miho.aurora.db.ProfileType

/**
 * The routing rules, asserted on their actual rendered shape: every rule must carry a
 * non-empty matcher — a rule that parses down to nothing but an outboundTag is what the
 * core rejects with "app/router: this rule has no effective fields" (an empty
 * ip/domain/protocol array unmarshals to zero conditions).
 */
class XrayConfigBuilderTest {
    private val profile = ProfileEntity(
        id = "1",
        subscriptionId = null,
        type = ProfileType.VLESS,
        remarks = "Reality exit",
        server = "reality.example.com",
        serverPort = 443,
        password = "b831381d-6324-4d53-ad4f-8cda48b30811",
        network = "tcp",
        security = "reality",
        sni = "www.microsoft.com",
        fingerprint = "chrome",
        publicKey = "pbk",
        shortId = "sid",
    )

    @Test
    fun `global rules all carry effective fields`() {
        val rules = rulesOf(XrayConfigBuilder.buildFull(profile, RoutingMode.GLOBAL))
        assertEquals(3, rules.size)
        // Every rule parses to at least one condition, and every array matcher is filled.
        rules.forEach { rule ->
            // port/network are scalar matchers; the rest must be non-empty arrays.
            val scalar = rule.keys.any { it == "port" || it == "network" }
            val arrayMatchers = rule.keys - "type" - "outboundTag" - "port" - "network"
            assertTrue("rule has no matcher fields", scalar || arrayMatchers.isNotEmpty())
            arrayMatchers.forEach { key -> assertTrue("$key is empty", rule[key]!!.jsonArray.isNotEmpty()) }
        }
        // The private nets go direct in every mode.
        assertTrue(rules.any { it["ip"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content == "geoip:private" })
        assertTrue(rules.any { it["domain"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content == "geosite:private" })
    }

    @Test
    fun `ru bypass rules all carry effective fields`() {
        val rules = rulesOf(XrayConfigBuilder.buildFull(profile, RoutingMode.BYPASS_RU))
        assertEquals(6, rules.size)
        rules.forEach { rule ->
            val scalar = rule.keys.any { it == "port" || it == "network" }
            val arrayMatchers = rule.keys - "type" - "outboundTag" - "port" - "network"
            assertTrue("rule has no matcher fields", scalar || arrayMatchers.isNotEmpty())
            arrayMatchers.forEach { key -> assertTrue("$key is empty", rule[key]!!.jsonArray.isNotEmpty()) }
        }
        val domains = rules.flatMap { r ->
            (r["domain"]?.jsonArray ?: emptyList()).map { it.jsonPrimitive.content }
        }
        assertTrue("category-ru missing", domains.contains("geosite:category-ru"))
        val ips = rules.flatMap { r ->
            (r["ip"]?.jsonArray ?: emptyList()).map { it.jsonPrimitive.content }
        }
        assertTrue("geoip:ru missing", ips.contains("geoip:ru"))
        val protocols = rules.flatMap { r ->
            (r["protocol"]?.jsonArray ?: emptyList()).map { it.jsonPrimitive.content }
        }
        assertTrue("bittorrent missing", protocols.contains("bittorrent"))
    }

    @Test
    fun `small regions ride the TLD instead of a geosite list`() {
        val rules = rulesOf(XrayConfigBuilder.buildFull(profile, RoutingMode.BYPASS_TR))
        val domains = rules.flatMap { r ->
            (r["domain"]?.jsonArray ?: emptyList()).map { it.jsonPrimitive.content }
        }
        assertEquals(listOf("domain:tr"), domains.filterNot { it == "geosite:private" })
        val ips = rules.flatMap { r ->
            (r["ip"]?.jsonArray ?: emptyList()).map { it.jsonPrimitive.content }
        }
        assertTrue("geoip:tr missing", ips.contains("geoip:tr"))
    }

    @Test
    fun `iran bypass carries the geosite list and the TLD`() {
        val rules = rulesOf(XrayConfigBuilder.buildFull(profile, RoutingMode.BYPASS_IR))
        val domains = rules.flatMap { r ->
            (r["domain"]?.jsonArray ?: emptyList()).map { it.jsonPrimitive.content }
        }
        assertTrue("category-ir missing", domains.contains("geosite:category-ir"))
        assertTrue("domain:ir missing", domains.contains("domain:ir"))
    }

    @Test
    fun `quic block matches port and network`() {
        val rules = rulesOf(XrayConfigBuilder.buildFull(profile, RoutingMode.GLOBAL))
        val quic = rules.first { it["outboundTag"]?.jsonPrimitive?.content == "block" }
        assertEquals("443", quic["port"]?.jsonPrimitive?.content)
        assertEquals("udp", quic["network"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a balancer session spreads its members over the proxy tag prefix`() {
        val balancer = profile.copy(
            id = "9",
            type = ProfileType.BALANCER,
            remarks = "Автовыбор",
            balancerStrategy = "leastping",
        )
        val config = Json.parseToJsonElement(
            XrayConfigBuilder.buildFull(
                balancer,
                RoutingMode.GLOBAL,
                members = listOf(profile, profile.copy(id = "2", server = "second.example.com")),
            ),
        ).jsonObject

        // The members ride as their own outbounds: the first keeps the plain proxy tag
        // — the default a dead balancer falls back to — the rest the prefix.
        val tags = config["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertEquals(listOf("proxy", "proxy-2", "direct", "block"), tags)

        // One balancer over them all, the strategy the panel asked for.
        val balancers = config["routing"]!!.jsonObject["balancers"]!!.jsonArray
        val b = balancers.single().jsonObject
        assertEquals("balancer", b["tag"]!!.jsonPrimitive.content)
        assertEquals("proxy", b["selector"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("leastping", b["strategy"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        // The fall-through rides the balancer, and nothing else does.
        val rules = rulesOf(config.toString())
        val catchAll = rules.last()
        assertEquals("balancer", catchAll["balancerTag"]!!.jsonPrimitive.content)
        assertEquals("tcp,udp", catchAll["network"]!!.jsonPrimitive.content)
        assertTrue(rules.dropLast(1).none { it.containsKey("balancerTag") })

        // The observing strategy gets its probe source: one root-level observatory over
        // the members, probing the test URL.
        val observatory = config["observatory"]!!.jsonObject
        assertEquals("proxy", observatory["subjectSelector"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("https://www.gstatic.com/generate_204", observatory["probeURL"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a plain session keeps no balancer and no observatory`() {
        val config = Json.parseToJsonElement(
            XrayConfigBuilder.buildFull(profile, RoutingMode.GLOBAL),
        ).jsonObject
        assertTrue(config["routing"]!!.jsonObject.containsKey("balancers").not())
        assertTrue(config.containsKey("observatory").not())
        val tags = config["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertEquals(listOf("proxy", "direct", "block"), tags)
    }

    private fun rulesOf(config: String) =
        Json.parseToJsonElement(config)
            .jsonObject["routing"]!!.jsonObject["rules"]!!.jsonArray
            .map { it.jsonObject }
}
