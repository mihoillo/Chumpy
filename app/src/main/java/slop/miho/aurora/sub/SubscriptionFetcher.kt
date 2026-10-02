package slop.miho.aurora.sub

import android.net.Uri
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.zip.GZIPInputStream

/**
 * Fetches a subscription body over plain HttpURLConnection. When the tunnel is up the
 * request goes through the running core's loopback SOCKS inbound — the app package is
 * excluded from the VPN routes, so this is the only way a blocked panel stays reachable
 * (the arrangement Hiddify uses, and the reason the config always listens on
 * [slop.miho.aurora.xray.XrayConfigBuilder.LOCAL_SOCKS_PORT]).
 */
class SubscriptionFetcher(private val tunnelUp: () -> Boolean) {
    private companion object {
        const val TAG = "Aurora/SubFetch"

        /**
         * A deliberately chatty client UA: panels key their response format off it, and the
         * generic-link format is what both keywords produce.
         */
        const val USER_AGENT = "Aurora/1.0 (Android) sing-box/1.10 v2rayNG/1.10"

        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000

        /** Panels that serve anything larger are doing something else; do not OOM the app. */
        const val MAX_BODY_BYTES = 8 * 1024 * 1024
    }

    data class FetchResult(
        val body: String,
        val metadata: SubscriptionMetadata,
    )

    /** Throws [IOException] with a readable message on failure. */
    fun fetch(rawUrl: String): FetchResult {
        val url = normalizeUrl(rawUrl)
        val connection = (java.net.URL(url).openConnection(proxyOrNull()) as java.net.HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code")
            }
            val bytes = (if (connection.contentEncoding.equals("gzip", ignoreCase = true)) {
                GZIPInputStream(connection.inputStream)
            } else {
                connection.inputStream
            }).use { it.readNBytesCompat(MAX_BODY_BYTES) }
            val headers = connection.headerFields.entries
                .filter { it.key != null }
                .associate { (key, values) -> key to values }
            val body = String(bytes, Charsets.UTF_8)
            if (body.isBlank()) throw IOException("Empty subscription body")
            return FetchResult(body, SubscriptionHeaders.parse(headers))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * A subscription URL without a scheme means https; hosts serving plain http spell it
     * out themselves (and the manifest allows cleartext for exactly that).
     */
    private fun normalizeUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun proxyOrNull(): Proxy? {
        if (!tunnelUp()) return null
        return Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 10808)).also {
            Log.d(TAG, "Fetching through the running tunnel")
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = read(chunk)
            if (read < 0) break
            total += read
            if (total > limit) throw IOException("Subscription body too large")
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    /** The link host of a subscription URL — the fallback display name. */
    fun hostNameOf(rawUrl: String): String = try {
        Uri.parse(normalizeUrl(rawUrl)).host ?: rawUrl
    } catch (_: Exception) {
        rawUrl
    }
}
