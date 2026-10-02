package slop.miho.aurora.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * A WireGuard config as a QR code, drawn in the theme's own colors — onSurface modules on
 * the surface — so it reads as part of the app instead of a foreign black-and-white sticker;
 * modern scanners read the light-on-dark variant of a dark theme fine. The quiet zone (four
 * blank modules, per the spec) is drawn by the same canvas, so the code scans from edge to
 * edge of whatever size it is given. Row runs merge into single rects to keep the draw
 * cheap for the dense codes a full config produces.
 */
@Composable
fun QrCode(content: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) {
        QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.MARGIN to 0),
        )
    }
    val background = MaterialTheme.colorScheme.surface
    val moduleColor = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        drawRect(background)
        val count = matrix.width
        val cell = size.minDimension / (count + QUIET_ZONE_MODULES * 2)
        val origin = Offset(
            (size.width - cell * count) / 2f,
            (size.height - cell * count) / 2f,
        )
        for (y in 0 until count) {
            var runStart = -1
            for (x in 0..count) {
                val dark = x < count && matrix.get(x, y)
                if (dark && runStart < 0) runStart = x
                if (!dark && runStart >= 0) {
                    // A hair of overlap keeps adjacent runs from leaving sub-pixel seams
                    // when the cell size doesn't divide the pixels evenly.
                    drawRect(
                        color = moduleColor,
                        topLeft = origin + Offset(runStart * cell, y * cell),
                        size = Size((x - runStart) * cell + 0.5f, cell + 0.5f),
                    )
                    runStart = -1
                }
            }
        }
    }
}

private const val QUIET_ZONE_MODULES = 4
