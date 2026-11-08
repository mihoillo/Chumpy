package slop.miho.chumpy.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The controller mode's focus ring: a primary-colored stroke sitting [gap] beyond the
 * node's own bounds — the selection reads as reaching past the button, not hugging it.
 * In touch mode the modifier draws nothing at all and costs no more than a state read.
 *
 * [circular] rounds the ring's ends to the node's own height (pills, chips, circles);
 * [cornerRadius] shapes them otherwise (tiles and their 16dp corners). The ring follows
 * its node's focus on its own, unless [focused] hands it a borrowed one — a row's tile
 * wearing the ring while the row itself carries the focus.
 *
 * [inset] flips the ring inside the bounds instead of past them — for the tiles whose
 * paint is clipped by the surface they sit in (the bar's round buttons, whose frost
 * surface cuts everything past its own circle). [path] takes the ring off the rounded
 * rectangle entirely: the lambda is handed the ring's own bounds — the node outset by
 * its reach — and its path is stroked as is, for the tiles whose own shape is neither
 * a circle nor a rounded square (the fold's triangle).
 */
fun Modifier.focusRing(
    cornerRadius: Dp = 16.dp,
    circular: Boolean = false,
    gap: Dp = 2.dp,
    strokeWidth: Dp = 2.dp,
    focused: (() -> Boolean)? = null,
    inset: Boolean = false,
    path: ((Size) -> Path)? = null,
    shape: Shape? = null,
): Modifier = composed {
    var ownFocused by remember { mutableStateOf(false) }
    val controller = LocalInputMode.current == InputMode.CONTROLLER
    val lit = if (controller) (focused?.invoke() ?: ownFocused) else false
    val motion = MaterialTheme.motionScheme
    val reveal by animateFloatAsState(
        targetValue = if (lit) 1f else 0f,
        animationSpec = motion.fastEffectsSpec(),
        label = "focus ring",
    )
    val ringColor = MaterialTheme.colorScheme.primary
    this
        .then(if (focused == null) Modifier.onFocusChanged { ownFocused = it.isFocused } else Modifier)
        .drawWithContent {
            drawContent()
            if (reveal > 0.01f) {
                val stroke = strokeWidth.toPx()
                // How far the ring's centerline sits past the node's own bounds — or,
                // inset, short of them.
                val reach = gap.toPx() + stroke / 2
                val color = ringColor.copy(alpha = ringColor.alpha * reveal)
                if (shape != null) {
                    val pad = if (inset) -reach else reach
                    translate(-pad, -pad) {
                        drawOutline(
                            shape.createOutline(Size(size.width + pad * 2f, size.height + pad * 2f), layoutDirection, this),
                            color = color,
                            style = Stroke(stroke),
                        )
                    }
                } else if (path != null) {
                    // The path is built for the ring's own bounds; the node's corner is
                    // where those bounds begin, outset by the reach.
                    translate(-reach, -reach) {
                        drawPath(
                            path = path(Size(size.width + reach * 2f, size.height + reach * 2f)),
                            color = color,
                            style = Stroke(stroke),
                        )
                    }
                } else {
                    val pad = if (inset) -reach else reach
                    val radius = if (circular) {
                        (size.minDimension + pad * 2f) / 2f
                    } else {
                        (cornerRadius.toPx() + pad).coerceAtLeast(0f)
                    }
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(-pad, -pad),
                        size = Size(size.width + pad * 2f, size.height + pad * 2f),
                        cornerRadius = CornerRadius(radius, radius),
                        style = Stroke(stroke),
                    )
                }
            }
        }
}
