package slop.miho.chumpy.sub

import slop.miho.chumpy.db.ProfileEntity
import slop.miho.chumpy.db.computeLinkKey

/**
 * Turns a subscription body into profiles. The body is either a list of share links, one
 * per line, or the same list base64-encoded as a whole (both alphabets); raw Xray configs
 * — whole client configs instead of links — are [ConfigParser]'s to read; some panels also
 * answer with a single plain URL — a nested subscription — which the repository expands
 * by fetching it, the way Hiddify does.
 */
object SubscriptionParser {
    /** The parsed profiles, deduplicated, in body order; empty when nothing parsed. */
    fun parse(body: String): List<ProfileEntity> {
        val text = decodeBody(body)
        // The raw-config dialect first: a config's own DNS block carries "https://"
        // addresses, which would otherwise read as a link list and swallow the body —
        // checked on the decoded text so base64-wrapped configs land here too.
        ConfigParser.parse(text)?.let { return it.distinctBy { it.computeLinkKey() } }
        // Clash YAML next — panels serve it to Clash-flavoured clients and to anyone
        // whose UA happens to carry a keyword they recognise.
        ClashYamlParser.parse(text)?.let { return it.distinctBy { it.computeLinkKey() } }
        val profiles = text.lines().mapNotNull { line ->
            LinkParsers.parse(line)
        }
        // Identical servers repeated in one body collapse to the first occurrence.
        return profiles.distinctBy { it.computeLinkKey() }
    }

    /**
     * Whether the whole body is one nested subscription URL rather than links — a single
     * http(s) line that is not itself a proxy link.
     */
    fun isNestedSubscriptionUrl(body: String): String? {
        val trimmed = decodeBody(body)
        if (trimmed.lines().count { it.isNotBlank() } != 1) return null
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) return null
        return trimmed
    }

    private fun decodeBody(body: String): String {
        val trimmed = body.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[") ||
            Regex("(?m)^proxies:").containsMatchIn(trimmed)) return trimmed
        // Plain link lists announce themselves; base64 bodies don't.
        if (trimmed.contains("://")) return trimmed
        val decoded = LinkUtils.decode(trimmed)
        return if (decoded.isNotBlank()) decoded else trimmed
    }
}
