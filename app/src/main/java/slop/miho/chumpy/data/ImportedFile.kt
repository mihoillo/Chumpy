package slop.miho.chumpy.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal object ImportedFile {
    enum class Result { SUCCESS, TOO_LARGE, INVALID }

    fun readText(input: InputStream): String {
        val output = ByteArrayOutputStream()
        copyLimited(input, output, 8L * 1024 * 1024)
        return output.toString(Charsets.UTF_8.name())
    }

    fun replace(target: File, input: InputStream, limit: Long, valid: (File) -> Boolean): Result {
        val directory = target.absoluteFile.parentFile!!
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create import directory")
        val temporary = File.createTempFile("import-", ".tmp", directory)
        try {
            try {
                temporary.outputStream().use { output ->
                    copyLimited(input, output, limit)
                    output.fd.sync()
                }
            } catch (_: TooLargeException) {
                return Result.TOO_LARGE
            }
            if (temporary.length() == 0L || !valid(temporary)) return Result.INVALID
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            return Result.SUCCESS
        } finally {
            temporary.delete()
        }
    }

    private fun copyLimited(input: InputStream, output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            total += read
            if (total > limit) throw TooLargeException()
            output.write(buffer, 0, read)
        }
    }

    private class TooLargeException : IOException("Imported file too large")
}
