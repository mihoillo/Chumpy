package slop.miho.aurora.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.MutableCubic
import kotlin.math.PI

/**
 * The selection mark's backdrop: a circle at rest that morphs into the MD3 9-sided cookie
 * when selected and back when deselected. The cookie keeps spinning slowly; the icon on top
 * of it stays upright. Without [animated] the shape freezes and only the morph still runs.
 * Drawn by hand because the library's morph-to-path helpers only speak the framework Path,
 * not the Compose one.
 */
@Composable
fun CookieBackdrop(selected: Boolean, color: Color, animated: Boolean) {
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val motion = MaterialTheme.motionScheme
    // 0 = circle, 1 = cookie. The spatial spring overshoots a little, and the morph happily
    // interpolates slightly past its range, which reads as a soft shape bounce.
    val progress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = motion.defaultSpatialSpec(),
        label = "cookie morph",
    )
    // The spin runs only while there's a cookie to turn: on the resting circle it would be
    // invisible, yet it would keep the display clocking frames for every row on the list.
    // Dropped once the shape has settled back, the angle's reset hides under the circle.
    val shaped by remember { derivedStateOf { progress > 0.001f } }
    val spinAngle: State<Float>? = if (animated && (selected || shaped)) {
        val spin = rememberInfiniteTransition(label = "cookie spin")
        spin.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                tween(durationMillis = COOKIE_SPIN_MILLIS, easing = LinearEasing),
            ),
            label = "cookie spin angle",
        )
    } else {
        null
    }
    // Reused per frame: the path is rebuilt from the morph's cubics on every redraw.
    val path = remember { Path() }
    val cubic = remember { MutableCubic() }
    Box(
        Modifier
            .fillMaxSize()
            // Scaled by the morph progress so the rotation eases in with the shape instead of
            // snapping; on the resting circle any rotation would be invisible anyway. Read
            // here, in the layer, so the spin's frames never recompose the row.
            .graphicsLayer { rotationZ = (spinAngle?.value ?: 0f) * progress }
            .drawBehind {
                path.rewind()
                var first = true
                morph.forEachCubic(progress, cubic) { c ->
                    if (first) {
                        path.moveTo(c.anchor0X, c.anchor0Y)
                        first = false
                    }
                    path.cubicTo(
                        c.control0X, c.control0Y,
                        c.control1X, c.control1Y,
                        c.anchor1X, c.anchor1Y,
                    )
                }
                path.close()
                // Polygon coordinates are normalized to 0..1: scale up to this box.
                withTransform({ scale(size.width, size.height, pivot = Offset.Zero) }) {
                    drawPath(path, color)
                }
            },
    )
}

private const val COOKIE_SPIN_MILLIS = 12_000
