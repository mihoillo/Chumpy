package slop.miho.chumpy.sub

import android.os.Build
import slop.miho.chumpy.data.UiSettings
import java.net.HttpURLConnection
import java.security.SecureRandom
import java.util.Locale

/**
 * The Happ panel's client fingerprint, one for the whole app. Some panels serve (or even
 * admit) only the official Happ app: the client identifies itself with the "Happ" user
 * agent and a set of x- headers — a device model, its OS, and the two identifiers the
 * panel binds the account to, the user id and the HWID. Both are random: nothing about
 * the real device leaks into them, and they are generated once and kept, so the panel
 * keeps seeing the same "device" across fetches and restarts.
 *
 * Ported from BetterExclave's HappSpoof, cut to what a plain HttpURLConnection needs.
 */
object HappSpoof {
    /** The Happ version the spoof claims; pinned, like a real client's would be. */
    const val DEFAULT_APP_VERSION = "3.21.1"

    private const val DEFAULT_OS = "Android"
    private const val DEFAULT_LOCALE = "en"
    private const val USER_ID_DIGITS = 20
    private const val HWID_BYTES = 8

    private val rng = SecureRandom()

    /**
     * The fingerprint as a value: what a fetch dresses up as. Null — the fetch goes out
     * with the app's own UA and no x- headers.
     */
    data class Fingerprint(
        val appVersion: String,
        val os: String,
        val osVersion: String,
        val deviceModel: String,
        val locale: String,
        val userId: String,
        val hwid: String,
    )

    /** A 20-digit user id, the shape Happ's own ids carry. */
    fun randomUserId(): String {
        val sb = StringBuilder(USER_ID_DIGITS)
        sb.append(rng.nextInt(9) + 1)
        repeat(USER_ID_DIGITS - 1) { sb.append(rng.nextInt(10)) }
        return sb.toString()
    }

    /** A 16-hex-character HWID. */
    fun randomHwid(): String {
        val bytes = ByteArray(HWID_BYTES)
        rng.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** The device's own OS version and model — the honest defaults of a fresh fingerprint. */
    fun defaultOsVersion(): String = Build.VERSION.RELEASE.takeIf { it.isNotBlank() } ?: "16"
    fun defaultDeviceModel(): String = Build.MODEL.takeIf { it.isNotBlank() } ?: "Pixel 7 Pro"
    fun defaultLocale(): String = Locale.getDefault().language.take(2).ifBlank { DEFAULT_LOCALE }

    /** The fingerprint a fetch carries, or null when the spoof is off in the settings. */
    fun UiSettings.happFingerprint(): Fingerprint? {
        if (!happSpoof) return null
        return Fingerprint(
            appVersion = happAppVersion.ifBlank { DEFAULT_APP_VERSION },
            os = happOs.ifBlank { DEFAULT_OS },
            osVersion = happOsVersion.ifBlank { defaultOsVersion() },
            deviceModel = happDeviceModel.ifBlank { defaultDeviceModel() },
            locale = happLocale.ifBlank { DEFAULT_LOCALE },
            userId = happUserId.ifBlank { randomUserId() },
            hwid = happHwid.ifBlank { randomHwid() },
        )
    }

    /**
     * Dresses the connection up as the Happ client: the UA carries the version, OS and
     * user id; the x- headers carry the rest of the fingerprint.
     */
    fun apply(connection: HttpURLConnection, fingerprint: Fingerprint) {
        connection.setRequestProperty("User-Agent", "Happ/${fingerprint.appVersion}/${fingerprint.os}/${fingerprint.userId}")
        connection.setRequestProperty("x-device-locale", fingerprint.locale)
        connection.setRequestProperty("x-hwid", fingerprint.hwid)
        connection.setRequestProperty("x-device-os", fingerprint.os)
        connection.setRequestProperty("x-ver-os", fingerprint.osVersion)
        connection.setRequestProperty("x-device-model", fingerprint.deviceModel)
        connection.setRequestProperty("accept-encoding", "gzip")
    }
}
