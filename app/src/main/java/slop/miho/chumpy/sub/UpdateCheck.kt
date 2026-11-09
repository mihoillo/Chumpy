package slop.miho.chumpy.sub

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import slop.miho.chumpy.BuildConfig
import slop.miho.chumpy.xray.XrayConfigBuilder.LOCAL_SOCKS_PORT

/**
 * A version triplet in the repo's own numbering: "0.6.227" — the major and minor ride the
 * gradle file, the build ticks with every build.sh run. A tag's leading "v" and a version
 * name's builder suffix ("0.6.227-miho") are dress, not digits, and fall away in [parse].
 */
data class AppVersion(val major: Int, val minor: Int, val build: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int = compareValuesBy(
        this, other, { it.major }, { it.minor }, { it.build },
    )

    override fun toString(): String = "$major.$minor.$build"

    companion object {
        /** "v0.6.221", "0.6.227-miho", " 0.7.0 " → the triplet; null when the digits don't come. */
        fun parse(raw: String): AppVersion? {
            val digits = raw.trim()
                .removePrefix("v").removePrefix("V")
                .substringBefore('-').substringBefore(' ').substringBefore('+')
            val parts = digits.split('.')
            if (parts.size != 3) return null
            val major = parts[0].trim().toIntOrNull() ?: return null
            val minor = parts[1].trim().toIntOrNull() ?: return null
            val build = parts[2].trim().toIntOrNull() ?: return null
            return AppVersion(major, minor, build)
        }
    }
}

/** A release the repo holds, trimmed to what an update needs: what to show, what to fetch. */
data class UpdateRelease(
    /** The tag the release goes by ("v0.6.221") — the hide's and the file's own name. */
    val tag: String,
    val version: AppVersion,
    /** The APK asset that fits this device, off the release's browser download URL. */
    val apkUrl: String,
    val apkSize: Long,
)

/**
 * The update checker. Every app open asks GitHub for the repo's latest release; a release
 * that outranks the running build becomes the home screen's chip. Pure JVM everywhere it
 * can be — the parsing and the versioning are plain, so the tests pin them without a
 * network or a device.
 */
object UpdateCheck {
    private const val TAG = "Chumpy/Update"

    /** The repo's latest release, by GitHub's own "latest" (drafts and prereleases excluded). */
    const val RELEASES_URL = "https://api.github.com/repos/ChumpyXray/Chumpy/releases/latest"

    /** A release's payload is a few dozen kilobytes; anything larger is not what we asked for. */
    private const val MAX_BODY_BYTES = 1024 * 1024

    /** The version the running build wears — what a candidate release must outrank. */
    fun currentVersion(): AppVersion = AppVersion.parse(BuildConfig.VERSION_NAME) ?: AppVersion(0, 0, 0)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * The client to talk to GitHub with: through the core's loopback SOCKS inbound while
     * the tunnel is up, the app package sitting outside the VPN's routes (the same
     * arrangement the subscription fetcher rides) — so the check works from behind a
     * block, and direct otherwise.
     */
    private fun clientFor(tunnelUp: Boolean): OkHttpClient = if (!tunnelUp) client
    else client.newBuilder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", LOCAL_SOCKS_PORT)))
        .build()

    /** The device's own ABIs, the system's preference order first — the release's asset picks by it. */
    fun deviceAbis(): List<String> = Build.SUPPORTED_ABIS?.toList() ?: emptyList()

    /**
     * The latest release as this device would take it, or null when it cannot be asked.
     * The check itself is silent — a miss (offline, GitHub down, a reshaped payload) is
     * a no-op this open, and the next open asks again.
     */
    suspend fun fetch(tunnelUp: () -> Boolean): UpdateRelease? = withContext(Dispatchers.IO) {
        val body = suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(RELEASES_URL)
                .header("User-Agent", "Chumpy/${BuildConfig.VERSION_NAME}")
                .header("Accept", "application/vnd.github+json")
                .build()
            val call = clientFor(tunnelUp()).newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                            val bytes = it.body?.byteStream()?.use { stream ->
                                readNBytes(stream, MAX_BODY_BYTES)
                            } ?: throw IOException("Empty release body")
                            if (continuation.isActive) continuation.resume(bytes.toString(Charsets.UTF_8))
                        }
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
            })
        }
        parseLatest(body, deviceAbis()).also { release ->
            Log.i(TAG, "Latest release: ${release?.tag ?: "nothing this device installs"}")
        }
    }

    /**
     * The release payload's two fields that matter: the tag, and the APK asset cut for
     * this device — the first of the device's own ABIs, in the system's preference
     * order, that the release ships a release build for; debug assets skipped. Null
     * when anything an update needs is missing: a release without this device's build
     * offers nothing rather than a universal carry-along at 3.5x the weight.
     */
    fun parseLatest(json: String, abis: List<String>): UpdateRelease? {
        val release = JSONObject(json)
        val tag = release.optString("tag_name").trim()
        val version = if (tag.isNotEmpty()) AppVersion.parse(tag) else null
        if (tag.isEmpty() || version == null) return null
        val assets = release.optJSONArray("assets") ?: return null
        var fits: JSONObject? = null
        for (abi in abis) {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                if (name.endsWith("-$abi.apk") && name.contains("-release-")) {
                    fits = asset
                    break
                }
            }
            if (fits != null) break
        }
        val asset = fits ?: return null
        return UpdateRelease(
            tag = tag,
            version = version,
            apkUrl = asset.optString("browser_download_url").ifEmpty { return null },
            apkSize = asset.optLong("size"),
        )
    }

    /**
     * Streams the release's APK into [target] through a temp file renamed on arrival —
     * only a complete file ever becomes the APK. A download cut short (a failure, a
     * pause, a process death) leaves its half behind in [partialOf] on purpose: the
     * next start picks up from wherever the bytes reached, through an HTTP range, and
     * [discardPartial] is the only thing that truly throws them away. [onProgress]
     * hears the percent as the chunks land; the caller decides how loudly to say it.
     */
    suspend fun download(
        release: UpdateRelease,
        target: File,
        tunnelUp: () -> Boolean,
        onProgress: (percent: Int) -> Unit,
    ): File = suspendCancellableCoroutine { continuation ->
        target.parentFile?.mkdirs()
        val temp = partialOf(target)
        val resumeFrom = temp.takeIf { it.isFile }?.length() ?: 0L
        val request = Request.Builder()
            .url(release.apkUrl)
            .apply { if (resumeFrom > 0L) header("Range", "bytes=$resumeFrom-") }
            .build()
        val call = clientFor(tunnelUp()).newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (it.code == 416) throw IOException("Range not satisfiable")
                        if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                        // A server echoing the range keeps the partial's bytes and appends;
                        // one ignoring it starts the story over, and the fresh stream must
                        // not land on top of the old half.
                        val resuming = resumeFrom > 0L && it.code == 206
                        val bodyLength = it.body?.contentLength() ?: -1L
                        val total = when {
                            resuming && bodyLength >= 0 -> resumeFrom + bodyLength
                            bodyLength >= 0 -> bodyLength
                            else -> release.apkSize
                        }
                        val input = it.body?.byteStream() ?: throw IOException("Empty APK body")
                        java.io.FileOutputStream(temp, resuming).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var copied = if (resuming) resumeFrom else 0L
                            while (true) {
                                if (!continuation.isActive) throw IOException("Cancelled")
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                copied += count
                                if (total > 0) onPercent(copied, total, onProgress)
                            }
                            if (copied == 0L) throw IOException("Empty APK body")
                        }
                        if (!temp.renameTo(target)) throw IOException("Could not finalize the APK")
                    }
                    if (continuation.isActive) continuation.resume(target)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }

    /** The half-written download a pause or a failure left beside [target]. */
    fun partialOf(target: File): File = File(target.parentFile, target.name + ".part")

    /** What a true start-over takes away: the partial beside [target], bytes and all. */
    fun discardPartial(target: File) {
        partialOf(target).delete()
    }

    /** How far the partial beside [target] runs, in whole percents of [release]. */
    fun partialPercent(release: UpdateRelease, target: File): Int {
        if (release.apkSize <= 0) return 0
        val have = partialOf(target).takeIf { it.isFile }?.length() ?: 0L
        return ((have * 100) / release.apkSize).toInt().coerceIn(0, 99)
    }

    private inline fun onPercent(copied: Long, total: Long, report: (Int) -> Unit) {
        report(((copied * 100) / total).toInt().coerceIn(0, 100))
    }

    private fun readNBytes(input: java.io.InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(64 * 1024)
        val body = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw IOException("Release body too large")
            body.write(buffer, 0, read)
        }
        return body.toByteArray()
    }
}

/**
 * The update checker's own little store: the release tag the user waved away (the chip's
 * long press, standing until the repo holds something newer) and the tag whose APK
 * already sits on disk — remembered so a process death between the download and the
 * install does not strand a ready update.
 */
object UpdatePrefs {
    private const val PREFS = "app_updates"
    private const val KEY_HIDDEN_TAG = "hidden_tag"
    private const val KEY_DOWNLOADED_TAG = "downloaded_tag"

    fun hiddenTag(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HIDDEN_TAG, null)

    fun hideTag(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HIDDEN_TAG, tag).apply()
    }

    fun downloadedTag(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DOWNLOADED_TAG, null)

    fun markDownloaded(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DOWNLOADED_TAG, tag).apply()
    }

    fun clearDownloaded(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_DOWNLOADED_TAG).apply()
    }

    /** Where an update's APK lands: the app's own files, under updates/, named by its tag. */
    fun apkFile(context: Context, tag: String): File =
        File(File(context.filesDir, "updates"), "Chumpy-$tag.apk")
}
