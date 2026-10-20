package slop.miho.chumpy.data

import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal object AtomicSnapshot {
    @Synchronized
    fun read(file: File): String? = try {
        file.bufferedReader().use { it.readText() }
    } catch (_: FileNotFoundException) {
        null
    }

    @Synchronized
    fun write(file: File, text: String) {
        val temporary = File.createTempFile(file.name + ".", ".tmp", file.parentFile)
        try {
            temporary.outputStream().use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}
