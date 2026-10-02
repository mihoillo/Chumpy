package slop.miho.adhdium.data

import android.content.Context
import com.wireguard.config.Config
import java.io.File

/**
 * The imported WireGuard config. Kept in no-backup storage because it holds the private key;
 * read by the ":tunnel" process, written by the UI process.
 */
object ProfileStore {
    private const val CONFIG_FILE = "tunnel.conf"
    private const val PREFS = "profile"

    fun configFile(context: Context) = File(context.noBackupFilesDir, CONFIG_FILE)

    fun hasProfile(context: Context) = configFile(context).isFile

    fun readConfig(context: Context): Config? {
        val file = configFile(context)
        if (!file.isFile) return null
        return file.inputStream().use(Config::parse)
    }

    /** Validates [text] as a WireGuard config and stores it; throws BadConfigException if invalid. */
    fun save(context: Context, text: String) {
        text.byteInputStream().use(Config::parse)
        val target = configFile(context)
        val tmp = File(target.parentFile, "$CONFIG_FILE.tmp")
        tmp.writeText(text)
        check(tmp.renameTo(target)) { "Couldn't store the config" }
        // The imported filename is a device/config label, not the user identity.
        // The greeting is populated from the backend peer.subnet_name instead.
    }

}
