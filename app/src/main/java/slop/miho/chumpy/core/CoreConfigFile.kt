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

    /** The last config the UI rendered, or null when there is nothing to connect with. */
    fun readConfig(context: Context): String? {
        return readAtomic(File(dir(context), CONFIG))
    }

    /** The connected profile's display name, for the notification and the tile. */
    fun readProfileName(context: Context): String? {
        return readAtomic(File(dir(context), PROFILE))?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** The connected profile's subscription name — the hop the notification tells
     *  before the region. Null for manual profiles: no subscription to name. */
    fun readSubscriptionName(context: Context): String? {
        return readAtomic(File(dir(context), SUBSCRIPTION))?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun has(context: Context): Boolean = !readConfig(context).isNullOrBlank()

    private fun readAtomic(file: File): String? = runCatching {
        AtomicSnapshot.read(file)
    }.getOrNull()

    @Synchronized
    fun write(context: Context, configJson: String, profileName: String?, subscriptionName: String?) {
        val directory = dir(context)
        writeAtomic(File(directory, CONFIG), configJson)
        writeAtomic(File(directory, PROFILE), profileName.orEmpty())
        writeAtomic(File(directory, SUBSCRIPTION), subscriptionName.orEmpty())
    }

    private fun writeAtomic(target: File, content: String) {
        AtomicSnapshot.write(target, content)
    }
}

/** The profile name the way the server list shows it: the leading flag lifted off —
 *  the pride emoji first, then the country's — the same pair of splits the rows do,
 *  leaving the name that reads beside the row's flag. */
internal fun stripFlag(name: String): String {
    val afterPride = splitLeadingPrideFlag(name).second
    return splitLeadingFlag(afterPride).second.trim()
}
