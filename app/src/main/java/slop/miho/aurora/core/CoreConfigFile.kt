package slop.miho.aurora.core

import android.content.Context
import slop.miho.aurora.ui.components.splitLeadingFlag
import slop.miho.aurora.ui.components.splitLeadingPrideFlag
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

    /** The last config the UI rendered, or null when there is nothing to connect with. */
    fun readConfig(context: Context): String? {
        val file = File(dir(context), CONFIG)
        return if (file.isFile) file.readText() else null
    }

    /** The connected profile's display name, for the notification and the tile. */
    fun readProfileName(context: Context): String? {
        val file = File(dir(context), PROFILE)
        return if (file.isFile) file.readText().trim().takeIf { it.isNotEmpty() } else null
    }

    /** The connected profile's subscription name — the hop the notification tells
     *  before the region. Null for manual profiles: no subscription to name. */
    fun readSubscriptionName(context: Context): String? {
        val file = File(dir(context), SUBSCRIPTION)
        return if (file.isFile) file.readText().trim().takeIf { it.isNotEmpty() } else null
    }

    fun has(context: Context): Boolean = File(dir(context), CONFIG).isFile

    fun write(context: Context, configJson: String, profileName: String?, subscriptionName: String?) {
        val directory = dir(context)
        writeAtomic(File(directory, CONFIG), configJson)
        writeAtomic(File(directory, PROFILE), profileName.orEmpty())
        writeAtomic(File(directory, SUBSCRIPTION), subscriptionName.orEmpty())
    }

    private fun writeAtomic(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            // A same-directory rename only fails on exotic filesystems; fall back in place.
            target.writeText(content)
            tmp.delete()
        }
    }
}

/** The profile name the way the server list shows it: the leading flag lifted off —
 *  the pride emoji first, then the country's — the same pair of splits the rows do,
 *  leaving the name that reads beside the row's flag. */
internal fun stripFlag(name: String): String {
    val afterPride = splitLeadingPrideFlag(name).second
    return splitLeadingFlag(afterPride).second.trim()
}
