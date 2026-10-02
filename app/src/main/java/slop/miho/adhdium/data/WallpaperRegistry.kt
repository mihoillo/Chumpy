package slop.miho.adhdium.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import slop.miho.adhdium.R

/**
 * The tone of the scrim layered over the wallpaper: follow the app theme, or force a light
 * or dark one regardless of it.
 */
enum class DimTone { AUTO, LIGHT, DARK }

/**
 * The wallpaper itself. The picked image is copied into the app's files (plain UI data, not
 * a secret — filesDir, like the custom fonts), one file that each new pick replaces. The
 * file's presence is the "selected" state; rendering it comes later.
 */
object WallpaperRegistry {
    /** The picked wallpaper file, or null when none has been picked yet. */
    fun current(context: Context): File? {
        val file = File(context.filesDir, WALLPAPER_FILE)
        return if (file.isFile) file else null
    }

    /** Drops the picked wallpaper, if any. */
    fun delete(context: Context) {
        File(context.filesDir, WALLPAPER_FILE).delete()
    }

    /**
     * Copies a picked image in as the wallpaper. Returns null on success, or a user-facing
     * error message. Whatever was picked before is replaced.
     */
    fun set(context: Context, uri: Uri): String? {
        if (!isImage(context, uri)) return context.getString(R.string.wallpapers_import_not_image)
        val target = File(context.filesDir, WALLPAPER_FILE)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    var copied = 0L
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > MAX_IMAGE_BYTES) {
                            target.delete()
                            return context.getString(R.string.wallpapers_import_too_large)
                        }
                        output.write(buffer, 0, read)
                    }
                    if (copied == 0L) {
                        target.delete()
                        return context.getString(R.string.error_unknown)
                    }
                }
            } ?: return context.getString(R.string.error_unknown)
        } catch (_: IOException) {
            target.delete()
            return context.getString(R.string.error_unknown)
        }
        return null
    }

    /** The photo picker hands out image MIME URIs; unlabeled ones are judged by the name. */
    private fun isImage(context: Context, uri: Uri): Boolean {
        if (context.contentResolver.getType(uri)?.startsWith("image/") == true) return true
        // Fall back to the display name's extension for content without a useful MIME type.
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                index >= 0 && cursor.getString(index).substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
            } else {
                false
            }
        } ?: false
    }

    private const val WALLPAPER_FILE = "wallpaper"

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif")

    /** Wallpapers past this are wasting space; screens never need more. */
    private const val MAX_IMAGE_BYTES = 40L * 1024 * 1024
}
