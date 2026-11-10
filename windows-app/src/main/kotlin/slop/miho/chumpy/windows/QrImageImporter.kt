package slop.miho.chumpy.windows

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.File
import javax.imageio.ImageIO

internal object QrImageImporter {
    fun read(file: File): String {
        val image = ImageIO.read(file) ?: error("Не удалось открыть изображение.")
        val bitmap = BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(image)))
        return MultiFormatReader().decode(bitmap).text
    }
}
