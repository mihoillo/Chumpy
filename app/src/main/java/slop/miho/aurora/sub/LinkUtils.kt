package slop.miho.aurora.sub

import android.util.Base64
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The string plumbing every link parser needs: base64 in both alphabets (subscriptions and
 * VMess links use either), strict RFC 3986 component de/encoding, and IPv6 brackets.
 * Ported from v2rayNG's Utils/extension helpers.
 */
object LinkUtils {
    /** Base64 in the standard and the URL-safe alphabet, without line wraps. */
    fun decodeBase64(text: String): String? {
        if (text.isEmpty()) return null
        return try {
            Base64.decode(text, Base64.NO_WRAP).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            try {
                Base64.decode(text, Base64.NO_WRAP or Base64.URL_SAFE).toString(Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Decode, tolerating missing padding — as subscription bodies often are. */
    fun decode(text: String): String =
        decodeBase64(text) ?: text.trimEnd('=').let { decodeBase64(it) } ?: ""

    fun encodeBase64(text: String, removePadding: Boolean = false): String =
        try {
            val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            if (removePadding) encoded.trimEnd('=') else encoded
        } catch (_: Exception) {
            ""
        }

    /** Decode an encodeURIComponent part; '+' stays a literal plus, unlike form decoding. */
    fun decodeURIComponent(url: String): String =
        try {
            URLDecoder.decode(url.replace("+", "%2B"), Charsets.UTF_8.toString())
        } catch (_: Exception) {
            url
        }

    /** Encode an encodeURIComponent part. */
    fun encodeURIComponent(url: String): String =
        try {
            URLEncoder.encode(url, Charsets.UTF_8.toString()).replace("+", "%20")
        } catch (_: Exception) {
            url
        }

    /** The characters java.net.URI refuses, percent-encoded away. */
    fun fixIllegalUrl(str: String): String = str.replace(" ", "%20").replace("|", "%7C")

    /** Query parameters of a URI, values component-decoded. */
    fun queryParams(uri: URI): Map<String, String> =
        (uri.rawQuery ?: "").split("&").filter { it.isNotEmpty() }.associate { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) {
                pair to ""
            } else {
                pair.substring(0, idx) to decodeURIComponent(pair.substring(idx + 1))
            }
        }

    /** The host without IPv6 brackets, IDN-decoded by java.net.URI itself. */
    val URI.idnHost: String
        get() = host?.replace("[", "")?.replace("]", "").orEmpty()

    /** Brackets a bare IPv6 address; anything else passes through. */
    fun ipv6Brackets(address: String?): String {
        if (address.isNullOrEmpty()) return ""
        return if (isIpv6Address(address) && !address.contains('[')) "[$address]" else address
    }

    fun isIpv6Address(address: String): Boolean =
        address.count { it == ':' } >= 2 && !address.startsWith("[")

    /**
     * Ports from links arrive as anything: "443", 443.0, "443#fragment". This takes the
     * leading integer and falls back to 0 (unset) when there is nothing usable.
     */
    fun parsePort(raw: String?): Int {
        val cleaned = raw?.substringBefore('#')?.trim().orEmpty()
        cleaned.toIntOrNull()?.let { return it }
        cleaned.toDoubleOrNull()?.let { return it.toInt() }
        return 0
    }
}
