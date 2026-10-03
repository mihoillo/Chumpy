package slop.miho.aurora.core

import android.content.Context
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

    fun has(context: Context): Boolean = File(dir(context), CONFIG).isFile

    fun write(context: Context, configJson: String, profileName: String?) {
        val directory = dir(context)
        writeAtomic(File(directory, CONFIG), configJson)
        writeAtomic(File(directory, PROFILE), profileName.orEmpty())
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

/** The profile name without its country flag: panels decorate their server names with
 *  flag emoji, which reads as noise where the notification and the QS tile carry the
 *  name beside a state of its own. */
internal fun stripFlag(name: String): String = buildString {
    for (ch in name) if (ch.code !in 0x1F1E6..0x1F1FF) append(ch)
}.trim()
