package slop.miho.chumpy.sub

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Happ routing header and the two source sets it merges with: a panel's header
 * profile carries the provider's geodata URLs, a raw config body carries its own, and
 * the body wins per database key.
 */
class GeodataSourcesTest {
    private val onadd = "happ://routing/onadd/"
    private val add = "happ://routing/add/"

    private fun header(
        profile: String,
        prefix: String = onadd,
        encode: (String) -> String = { LinkUtils.encodeBase64(it) },
    ): String = prefix + encode(profile)

    private fun sources(profile: String): Map<String, String> =
        GeodataSources.parse(GeodataSources.fromHappHeader(header(profile)))

    private fun profile(geoip: String?, geosite: String?): String = buildString {
        append('{')
        val entries = listOfNotNull(
            geoip?.let { "\"Geoipurl\":\"$it\"" },
            geosite?.let { "\"Geositeurl\":\"$it\"" },
        )
        append(entries.joinToString(","))
        append('}')
    }

    @Test
    fun `plain base64 header yields both databases`() {
        val parsed = sources(profile("https://cdn.example.org/geoip.dat", "https://cdn.example.org/geosite.dat"))
        assertEquals("https://cdn.example.org/geoip.dat", parsed["geoip.dat"])
        assertEquals("https://cdn.example.org/geosite.dat", parsed["geosite.dat"])
    }

    @Test
    fun `url encoded base64 header is decoded`() {
        val payload = profile("https://cdn.example.org/geoip.dat", "https://cdn.example.org/geosite.dat")
        val encoded = LinkUtils.encodeURIComponent(LinkUtils.encodeBase64(payload))
        val parsed = GeodataSources.parse(GeodataSources.fromHappHeader(onadd + encoded))
        assertEquals("https://cdn.example.org/geoip.dat", parsed["geoip.dat"])
        assertEquals("https://cdn.example.org/geosite.dat", parsed["geosite.dat"])
    }

    @Test
    fun `base64url without padding is decoded`() {
        // The trailing "ÿÿÿ" forces '+' or '/' into the standard alphabet, so the
        // URL-safe rewrite actually exercises both substitutions.
        val payload = """{"Geoipurl":"https://cdn.example.org/geoip.dat?q=ÿÿÿ",""" +
            """"Geositeurl":"https://cdn.example.org/geosite.dat"}"""
        val standard = LinkUtils.encodeBase64(payload)
        assertTrue(standard.contains('+') || standard.contains('/'))
        val base64url = standard.replace('+', '-').replace('/', '_').trimEnd('=')
        val parsed = GeodataSources.parse(GeodataSources.fromHappHeader(onadd + base64url))
        assertEquals("https://cdn.example.org/geoip.dat?q=ÿÿÿ", parsed["geoip.dat"])
        assertEquals("https://cdn.example.org/geosite.dat", parsed["geosite.dat"])
    }

    @Test
    fun `uppercase prefix is accepted`() {
        val parsed = GeodataSources.parse(
            GeodataSources.fromHappHeader(
                header(profile("https://cdn.example.org/geoip.dat", null), "HAPP://ROUTING/ONADD/"),
            ),
        )
        assertEquals("https://cdn.example.org/geoip.dat", parsed["geoip.dat"])
    }

    @Test
    fun `older add prefix is accepted`() {
        val parsed = GeodataSources.parse(
            GeodataSources.fromHappHeader(header(profile(null, "https://cdn.example.org/geosite.dat"), add)),
        )
        assertEquals("https://cdn.example.org/geosite.dat", parsed["geosite.dat"])
    }

    @Test
    fun `missing key leaves only the other database`() {
        val parsed = sources(profile("https://cdn.example.org/geoip.dat", null))
        assertEquals("https://cdn.example.org/geoip.dat", parsed["geoip.dat"])
        assertNull(parsed["geosite.dat"])
    }

    @Test
    fun `empty urls produce no sources`() {
        assertEquals("", GeodataSources.fromHappHeader(header(profile("", ""))))
    }

    @Test
    fun `malformed input produces no sources`() {
        val cases = listOf(
            "",
            "not a link",
            onadd + "@@@",
            header("[]"),
            header("null"),
        )
        cases.forEach { case -> assertEquals("case: $case", "", GeodataSources.fromHappHeader(case)) }
    }

    @Test
    fun `oversized header is ignored`() {
        val huge = onadd + "A".repeat(256 * 1024 + 1)
        assertEquals("", GeodataSources.fromHappHeader(huge))
    }

    @Test
    fun `keys are matched case-insensitively`() {
        val lower = GeodataSources.parse(
            GeodataSources.fromHappHeader(header("""{"geoipurl":"https://cdn.example.org/geoip.dat"}""")),
        )
        assertEquals("https://cdn.example.org/geoip.dat", lower["geoip.dat"])
        val mixed = GeodataSources.parse(
            GeodataSources.fromHappHeader(
                header(
                    """{"GeoipUrl":"https://cdn.example.org/geoip.dat",""" +
                        """"GEOSITEURL":"https://cdn.example.org/geosite.dat"}""",
                ),
            ),
        )
        assertEquals("https://cdn.example.org/geoip.dat", mixed["geoip.dat"])
        assertEquals("https://cdn.example.org/geosite.dat", mixed["geosite.dat"])
    }

    @Test
    fun `merge keeps the other database and lets overrides win`() {
        val defaults = """{"geosite.dat":"https://header.example.org/geosite.dat",""" +
            """"geoip.dat":"https://header.example.org/geoip.dat"}"""
        val overrides = """{"geosite.dat":"https://body.example.org/geosite.dat"}"""
        val merged = GeodataSources.parse(GeodataSources.merge(defaults, overrides))
        assertEquals("https://body.example.org/geosite.dat", merged["geosite.dat"])
        assertEquals("https://header.example.org/geoip.dat", merged["geoip.dat"])
    }

    @Test
    fun `merge tolerates malformed sides`() {
        val defaults = """{"geosite.dat":"https://header.example.org/geosite.dat"}"""
        val malformedOverrides = GeodataSources.parse(GeodataSources.merge(defaults, "not json"))
        assertEquals("https://header.example.org/geosite.dat", malformedOverrides["geosite.dat"])
        val malformedDefaults = GeodataSources.parse(GeodataSources.merge("not json", defaults))
        assertEquals("https://header.example.org/geosite.dat", malformedDefaults["geosite.dat"])
        assertEquals("", GeodataSources.merge("not json", "[]"))
    }

    @Test
    fun `merge takes an already parsed defaults map`() {
        val merged = GeodataSources.parse(
            GeodataSources.merge(
                mapOf("geoip.dat" to "https://header.example.org/geoip.dat"),
                """{"geosite.dat":"https://body.example.org/geosite.dat"}""",
            ),
        )
        assertEquals("https://header.example.org/geoip.dat", merged["geoip.dat"])
        assertEquals("https://body.example.org/geosite.dat", merged["geosite.dat"])
    }

    @Test
    fun `parse safely turns garbage into no sources`() {
        assertTrue(GeodataSources.parseSafely("not json").isEmpty())
        assertTrue(GeodataSources.parseSafely("[]").isEmpty())
        assertEquals(
            mapOf("geosite.dat" to "https://cdn.example.org/geosite.dat"),
            GeodataSources.parseSafely("""{"geosite.dat":"https://cdn.example.org/geosite.dat"}"""),
        )
    }

    @Test
    fun `config body sources win over the header`() {
        val fromHeader = GeodataSources.fromHappHeader(
            header(profile("https://header.example.org/geoip.dat", "https://header.example.org/geosite.dat")),
        )
        val body = Json.parseToJsonElement(
            """{"geodata":{"assets":[{"file":"geosite.dat","url":"https://body.example.org/geosite.dat"}]}}""",
        ) as JsonObject
        val fromBody = GeodataSources.fromConfig(body)
        val merged = GeodataSources.parse(GeodataSources.merge(fromHeader, fromBody))
        assertEquals("https://body.example.org/geosite.dat", merged["geosite.dat"])
        assertEquals("https://header.example.org/geoip.dat", merged["geoip.dat"])
    }
}
