package slop.miho.chumpy.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import slop.miho.chumpy.R
import java.io.File
import android.graphics.Typeface

/**
 * The fonts selectable app-wide: the system default, Inter bundled with the app, and font
 * files the user imported. A font is identified by a stable string id — [SYSTEM_ID],
 * [INTER_ID] or the imported file's name.
 */
object FontRegistry {
    const val SYSTEM_ID = "system"
    const val INTER_ID = "inter"

    /**
     * Inter as a variable font: one bundled file covering the whole weight axis, with the
     * relevant MD3 scale weights pinned to named weights so bold text renders true Inter
     * rather than a synthesized fake-bold.
     */
    val inter = FontFamily(
        Font(R.font.inter_variable, weight = FontWeight.Normal),
        Font(
            R.font.inter_variable,
            weight = FontWeight.Medium,
            variationSettings = FontVariation.Settings(FontVariation.weight(500)),
        ),
        Font(
            R.font.inter_variable,
            weight = FontWeight.SemiBold,
            variationSettings = FontVariation.Settings(FontVariation.weight(600)),
        ),
        Font(
            R.font.inter_variable,
            weight = FontWeight.Bold,
            variationSettings = FontVariation.Settings(FontVariation.weight(700)),
        ),
    )

    /**
     * Imported font files, alphabetically. Plain UI data, not secrets, so filesDir (not
     * noBackupFilesDir like the config) is the right home.
     */
    fun customFonts(context: Context): List<File> =
        customDir(context).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in FONT_EXTENSIONS }
            ?.sortedBy { it.nameWithoutExtension.lowercase() }
            ?: emptyList()

    /** The file a custom font id points at, or null if it is gone. */
    fun customFontFile(context: Context, id: String): File? {
        // Ids are file names; reject anything with a path in it out of hand.
        if (id.isEmpty() || id.contains('/') || id.contains(File.separatorChar)) return null
        val file = File(customDir(context), id)
        return if (file.isFile && file.extension.lowercase() in FONT_EXTENSIONS) file else null
    }

    /**
     * Copies a picked font into the app's files. Returns null on success, or a user-facing
     * error message. Re-importing the same font name overwrites the old file.
     */
    fun import(context: Context, uri: Uri): String? {
        return try {
            val displayName = queryDisplayName(context, uri)
                ?: return context.getString(R.string.fonts_import_not_font)
            val extension = displayName.substringAfterLast('.', "").lowercase()
            if (extension !in FONT_EXTENSIONS) return context.getString(R.string.fonts_import_not_font)
            val base = displayName.substringBeforeLast('.')
                .replace(Regex("[^A-Za-z0-9._ -]"), "_")
                .trim()
                .ifEmpty { "font" }
            val target = File(customDir(context), "$base.$extension")
            val result = context.contentResolver.openInputStream(uri)?.use { input ->
                ImportedFile.replace(target, input, MAX_FONT_BYTES) { file ->
                    runCatching { Typeface.createFromFile(file) }.isSuccess
                }
            } ?: return context.getString(R.string.fonts_import_not_font)
            when (result) {
                ImportedFile.Result.SUCCESS -> null
                ImportedFile.Result.TOO_LARGE -> context.getString(R.string.fonts_import_too_large)
                ImportedFile.Result.INVALID -> context.getString(R.string.fonts_import_not_font)
            }
        } catch (_: Exception) {
            context.getString(R.string.error_unknown)
        }
    }

    /**
     * Removes an imported font. Only ever touches files in the fonts folder: the id is
     * resolved through [customFontFile], which refuses anything with a path in it.
     */
    fun delete(context: Context, id: String): Boolean =
        customFontFile(context, id)?.delete() ?: false

    private fun customDir(context: Context): File = File(context.filesDir, "fonts")

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index)
                else null
            } else {
                null
            }
        }

    private val FONT_EXTENSIONS = setOf("ttf", "otf")

    /** Real fonts are well under 5 MB; anything past this is not a font. */
    private const val MAX_FONT_BYTES = 20L * 1024 * 1024
}
