package slop.miho.chumpy.sub

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.util.zip.GZIPInputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import android.net.Uri
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy

import slop.miho.chumpy.xray.XrayConfigBuilder.LOCAL_SOCKS_PORT

/**
 * Fetches a subscription body with cancellation and an overall deadline. When the tunnel is up the
 * request goes through the running core's loopback SOCKS inbound — the app package is
 * excluded from the VPN routes, so this is the only way a blocked panel stays reachable
 * (the arrangement Hiddify uses, and the reason the config always listens on it).
 *
 * Every fetch dresses up as the Happ client when the global spoof is on: the fingerprint
 * is read at fetch time, so a flip in the settings is live on the very next fetch.
 */
class SubscriptionFetcher(
    private val tunnelUp: () -> Boolean,
    private val happSpoof: () -> HappSpoof.Fingerprint?,
) {
    private companion object {
        /**
         * A deliberately chatty client UA: panels key their response format off it. The
         * v2rayNG keyword buys the generic link list, the plain name the raw-config
         * body — both parsers handle those; sing-box/clash keywords would be answered
         * with Clash YAML or sing-box JSON, so they stay out.
         */
        const val USER_AGENT = "Chumpy/1.0 (Android) v2rayNG/1.10"

        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000

        /** Panels that serve anything larger are doing something else; do not OOM the app. */
        const val MAX_BODY_BYTES = 8 * 1024 * 1024
        val directClient by lazy {
            OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        }
        val tunnelClient by lazy {
            directClient.newBuilder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", LOCAL_SOCKS_PORT)))
            .build()
        }
    }

    data class FetchResult(
        val body: String,
        val metadata: SubscriptionMetadata,
    )

    /**
     * Throws [IOException] with a readable message on failure. The Happ fingerprint,
     * when the settings carry one, takes the UA and the x- headers over from the app's
     * own identity.
     */
    suspend fun fetch(rawUrl: String): FetchResult = suspendCancellableCoroutine { continuation ->
        val client = if (tunnelUp()) tunnelClient else directClient
        val request = Request.Builder().url(normalizeUrl(rawUrl)).get()
        val fingerprint = happSpoof()
        if (fingerprint != null) HappSpoof.headers(fingerprint).forEach { (name, value) -> request.header(name, value) }
        else request.header("User-Agent", USER_AGENT).header("Accept", "*/*")
        val call = client.newCall(request.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                        val input = it.body?.byteStream() ?: throw IOException("Empty subscription body")
                        val bytes = (if (it.header("Content-Encoding").equals("gzip", true)) GZIPInputStream(input) else input)
                            .use { stream -> stream.readNBytesCompat(MAX_BODY_BYTES) }
                        val body = String(bytes, Charsets.UTF_8)
                        if (body.isBlank()) throw IOException("Empty subscription body")
                        FetchResult(body, SubscriptionHeaders.parse(it.headers.toMultimap()))
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    suspend fun fetchGeodata(url: String, target: java.io.File): Unit = suspendCancellableCoroutine { continuation ->
        val parsed = url.toHttpUrlOrNull()
        if (parsed == null || !parsed.isHttps || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            continuation.resumeWithException(IOException("Geodata requires an HTTPS URL without embedded credentials"))
            return@suspendCancellableCoroutine
        }
        val base = if (tunnelUp()) tunnelClient else directClient
        val client = base.newBuilder().followSslRedirects(false).callTimeout(45, java.util.concurrent.TimeUnit.SECONDS).build()
        val call = client.newCall(Request.Builder().url(parsed).header("User-Agent", USER_AGENT).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful || !it.request.url.isHttps) throw IOException("Geodata HTTP ${it.code}")
                        val input = it.body?.byteStream()?.buffered() ?: throw IOException("Empty geodata")
                        input.mark(2)
                        val gzip = input.read() == 0x1f && input.read() == 0x8b
                        input.reset()
                        val stream = if (gzip) GZIPInputStream(input) else input
                        stream.use { source -> target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                if (!continuation.isActive) throw IOException("Cancelled")
                                val count = source.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > slop.miho.chumpy.data.GeodataFiles.MAX_BYTES) throw IOException("Geodata exceeds 64 MiB")
                                output.write(buffer, 0, count)
                            }
                            if (total == 0L) throw IOException("Empty geodata")
                        } }
                    }
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }

    /**
     * A subscription URL without a scheme means https; hosts serving plain http spell it
     * out themselves (and the manifest allows cleartext for exactly that).
     */
    private fun normalizeUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        return if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "https://$trimmed"
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
