package slop.miho.chumpy.core

import android.content.Context
import slop.miho.chumpy.data.AtomicSnapshot
import slop.miho.chumpy.ui.components.splitLeadingFlag
import slop.miho.chumpy.ui.components.splitLeadingPrideFlag
import java.io.File

/**
 * The config hand-off between the two processes: the UI process (which owns the profile
 * database) renders the full Xray config JSON and writes it here, and the ":core" process
 * reads it whenever it brings the tunnel up — on a user connect, on an Always-on restart,
 * or on a QS tile toggle. Plain files, written atomically, read on demand: no Room access
 * from the core process, ever.
 */
object CoreConfigFile {
    private const val DIR = "core"
    private const val CONFIG = "config.json"
    private const val PROFILE = "profile.txt"
    private const val SUBSCRIPTION = "subscription.txt"

    private fun dir(context: Context): File = File(context.filesDir, DIR).also { it.mkdirs() }

    data class Snapshot(
        val config: String,
        val profileName: String?,
        val subscriptionName: String?,
        val profileId: String? = null,
        val subscriptionId: String? = null,
        val server: String? = null,
    )

    private var cachedKey: String? = null
    private var cachedSnapshot: Snapshot? = null

    @Synchronized
    fun snapshot(context: Context): Snapshot? = runCatching { readSnapshot(context) }.getOrNull()

    private fun readSnapshot(context: Context): Snapshot? {
        val directory = dir(context)
        val bundle = File(directory, "session.json")
        if (bundle.exists()) return runCatching {
            val attributes = java.nio.file.Files.readAttributes(bundle.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
            val key = "${bundle.absolutePath}:${attributes.fileKey()}:${attributes.size()}:${attributes.lastModifiedTime()}"
            if (cachedKey == key) return cachedSnapshot
            val json = org.json.JSONObject(AtomicSnapshot.read(bundle) ?: return null)
            val config = json.getString("config")
            if (config.isBlank()) {
                cachedKey = key
                cachedSnapshot = null
                return null
            }
            fun field(key: String) = json.optString(key).takeIf { it.isNotEmpty() }
            Snapshot(config, field("profile"), field("subscription"), field("profileId"), field("subscriptionId"), field("server")).also {
                cachedKey = key
                cachedSnapshot = it
            }
        }.getOrNull()
        val config = AtomicSnapshot.read(File(directory, CONFIG))?.takeIf { it.isNotBlank() } ?: return null
        return Snapshot(config, AtomicSnapshot.read(File(directory, PROFILE)),
            AtomicSnapshot.read(File(directory, SUBSCRIPTION)))
    }

    fun readConfig(context: Context): String? = snapshot(context)?.config
    fun readProfileName(context: Context): String? = snapshot(context)?.let { saved ->
        saved.profileName?.trim()?.takeIf {
            it.isNotEmpty() && !(slop.miho.chumpy.data.SettingsStore.tileSettings(context).maskAddresses && it == saved.server)
        }
    }
    fun readSubscriptionName(context: Context): String? = snapshot(context)?.subscriptionName?.trim()?.takeIf { it.isNotEmpty() }
    fun has(context: Context): Boolean = snapshot(context) != null

    @Synchronized
    fun write(context: Context, configJson: String, profileName: String?, subscriptionName: String?,
        profileId: String? = null, subscriptionId: String? = null, server: String? = null) {
        cachedKey = null
        val json = org.json.JSONObject().put("config", configJson)
            .put("profile", profileName.orEmpty()).put("subscription", subscriptionName.orEmpty())
            .put("profileId", profileId.orEmpty()).put("subscriptionId", subscriptionId.orEmpty())
            .put("server", server.orEmpty())
        AtomicSnapshot.write(File(dir(context), "session.json"), json.toString())
    }

    @Synchronized
    fun clear(context: Context) {
        val directory = dir(context)
        cachedKey = null
        cachedSnapshot = null
        AtomicSnapshot.write(File(directory, "session.json"), org.json.JSONObject().put("config", "").toString())
        listOf(CONFIG, PROFILE, SUBSCRIPTION).forEach { File(directory, it).delete() }
    }

}

/** The profile name the way the server list shows it: the leading flag lifted off —
 *  the pride emoji first, then the country's — the same pair of splits the rows do,
 *  leaving the name that reads beside the row's flag. */
internal fun stripFlag(name: String): String {
    val afterPride = splitLeadingPrideFlag(name).second
    return splitLeadingFlag(afterPride).second.trim()
}
