package slop.miho.chumpy.data

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportedFileTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `invalid and oversized imports preserve the existing file`() {
        val target = directory.newFile("wallpaper").apply { writeText("previous") }
        assertEquals(ImportedFile.Result.INVALID,
            ImportedFile.replace(target, ByteArrayInputStream(byteArrayOf()), 10) { true })
        assertEquals(ImportedFile.Result.INVALID,
            ImportedFile.replace(target, ByteArrayInputStream("bad".toByteArray()), 10) { false })
        assertEquals(ImportedFile.Result.TOO_LARGE,
            ImportedFile.replace(target, ByteArrayInputStream("too large".toByteArray()), 3) { true })
        assertEquals("previous", target.readText())
        assertEquals(listOf("wallpaper"), directory.root.list()!!.toList())
    }

    @Test
    fun `provider failure preserves the old file and removes temporary data`() {
        val target = directory.newFile("font.ttf").apply { writeText("previous") }
        val input = object : InputStream() {
            var reads = 0
            override fun read(): Int = if (reads++ < 4) 65 else throw IOException("provider disconnected")
        }
        assertThrows(IOException::class.java) {
            ImportedFile.replace(target, input, 100) { true }
        }
        assertEquals("previous", target.readText())
        assertEquals(listOf("font.ttf"), directory.root.list()!!.toList())
    }

    @Test
    fun `validated import replaces file after validation`() {
        val target = File(directory.root, "fonts/custom.ttf")
        val bytes = "new font".toByteArray()
        assertEquals(ImportedFile.Result.SUCCESS,
            ImportedFile.replace(target, ByteArrayInputStream(bytes), bytes.size.toLong()) {
                assertFalse(target.exists())
                it.readText() == "new font"
            })
        assertArrayEquals(bytes, target.readBytes())
        assertEquals(listOf("custom.ttf"), target.parentFile!!.list()!!.toList())
    }

    @Test
    fun `text imports reject files exceeding the subscription limit`() {
        assertEquals("hy2://test@example.com", ImportedFile.readText(
            ByteArrayInputStream("hy2://test@example.com".toByteArray())))
        assertThrows(IOException::class.java) {
            ImportedFile.readText(ByteArrayInputStream(ByteArray(8 * 1024 * 1024 + 1)))
        }
    }
}
