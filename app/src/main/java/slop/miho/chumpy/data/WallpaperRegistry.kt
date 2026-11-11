package slop.miho.chumpy.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import android.graphics.BitmapFactory
import slop.miho.chumpy.R

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
    private val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val version: kotlinx.coroutines.flow.StateFlow<Long> = changes

    /** The picked wallpaper file, or null when none has been picked yet. */
    fun current(context: Context): File? {
        val file = File(context.filesDir, WALLPAPER_FILE)
        return if (file.isFile) file else null
    }

    /** Drops the picked wallpaper, if any. */
    fun delete(context: Context) {
        if (File(context.filesDir, WALLPAPER_FILE).delete()) changes.value++
    }

    /**
     * Copies a picked image in as the wallpaper. Returns null on success, or a user-facing
     * error message. Whatever was picked before is replaced.
     */
    fun set(context: Context, uri: Uri): String? {
        return try {
            if (!isImage(context, uri)) return context.getString(R.string.wallpapers_import_not_image)
            // The success story IS null here (see replace) — the stream's own absence is the
            // only miss, and it is judged before the copy's verdict is ever in play. The old
            // one-line elvis lived on the pre-refactor convention (a non-null Result enum)
            // and turned every successful copy into "something went wrong", preset included.
            val input = context.contentResolver.openInputStream(uri)
                ?: return context.getString(R.string.error_unknown)
            input.use { replace(context, it) }
        } catch (failure: Exception) {
            Log.w(TAG, "Wallpaper import from picker ($uri) failed", failure)
            context.getString(R.string.error_unknown)
        }
    }

    internal fun setReceived(context: Context, file: File): String? = try {
        file.inputStream().use { replace(context, it) }
    } catch (failure: Exception) {
        Log.w(TAG, "Wallpaper import from phone (${file.absolutePath}) failed", failure)
        context.getString(R.string.error_unknown)
    }

    private fun replace(context: Context, input: java.io.InputStream): String? {
        val target = File(context.filesDir, WALLPAPER_FILE)
        val result = ImportedFile.replace(target, input, MAX_IMAGE_BYTES) { file ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.outWidth > 0 && options.outHeight > 0
        }
        // The rejection itself is silent by design — the user gets their localized message —
        // but a diagnosis needs the reason in the log: what came in and what the copy said no to.
        if (result != ImportedFile.Result.SUCCESS) {
            Log.w(TAG, "Wallpaper copy into ${target.absolutePath} rejected: $result")
        } else {
            Log.i(TAG, "Wallpaper copy into ${target.absolutePath} OK, ${target.length()} bytes")
        }
        return when (result) {
            ImportedFile.Result.SUCCESS -> { changes.value++; null }
            ImportedFile.Result.TOO_LARGE -> context.getString(R.string.wallpapers_import_too_large)
            ImportedFile.Result.INVALID -> context.getString(R.string.wallpapers_import_not_image)
        }
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

    /** Logcat tag for the import's diagnostics: the swallowed failures live here. */
    private const val TAG = "Chumpy/Wallpaper"

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif", "gif")

    /** Wallpapers past this are wasting space; screens never need more. */
    private const val MAX_IMAGE_BYTES = 40L * 1024 * 1024
}
