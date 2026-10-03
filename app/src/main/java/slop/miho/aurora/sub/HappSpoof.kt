package slop.miho.aurora.sub

import android.os.Build
import slop.miho.aurora.db.SubscriptionEntity
import java.net.HttpURLConnection
import java.security.SecureRandom

/**
 * The Happ panel's client fingerprint, per subscription. Some panels serve (or even
 * admit) only the official Happ app: the client identifies itself with the "Happ" user
 * agent and a set of x- headers — a device model, its OS, and the two identifiers the
 * panel binds the account to, the user id and the HWID. Both are random: nothing about
 * the real device leaks into them, and they are generated once and kept, so the panel
 * keeps seeing the same "device" across updates.
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

    /** The device's own OS version and model — the honest defaults for a fresh spoof. */
    fun defaultOsVersion(): String = Build.VERSION.RELEASE.takeIf { it.isNotBlank() } ?: "16"
    fun defaultDeviceModel(): String = Build.MODEL.takeIf { it.isNotBlank() } ?: "Pixel 7 Pro"

    /**
     * Dresses the connection up as the Happ client: the UA carries the version, OS and
     * user id; the x- headers carry the rest of the fingerprint. Empty fields fall back
     * to the defaults, exactly as the stored-empty fields of a fresh subscription do.
     */
    fun apply(connection: HttpURLConnection, subscription: SubscriptionEntity) {
        val appVersion = subscription.happAppVersion.ifBlank { DEFAULT_APP_VERSION }
        val os = subscription.happOs.ifBlank { DEFAULT_OS }
        val osVersion = subscription.happOsVersion.ifBlank { defaultOsVersion() }
        val deviceModel = subscription.happDeviceModel.ifBlank { defaultDeviceModel() }
        val locale = subscription.happLocale.ifBlank { DEFAULT_LOCALE }
        val userId = subscription.happUserId.ifBlank { randomUserId() }
        val hwid = subscription.happHwid.ifBlank { randomHwid() }
        connection.setRequestProperty("User-Agent", "Happ/$appVersion/$os/$userId")
        connection.setRequestProperty("x-device-locale", locale)
        connection.setRequestProperty("x-hwid", hwid)
        connection.setRequestProperty("x-device-os", os)
        connection.setRequestProperty("x-ver-os", osVersion)
        connection.setRequestProperty("x-device-model", deviceModel)
        connection.setRequestProperty("accept-encoding", "gzip")
    }
}
