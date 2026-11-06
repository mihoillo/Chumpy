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
import androidx.compose.ui.graphics.drawscope.Stroke
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
 */
fun Modifier.focusRing(
    cornerRadius: Dp = 16.dp,
    circular: Boolean = false,
    gap: Dp = 4.dp,
    strokeWidth: Dp = 3.dp,
    focused: (() -> Boolean)? = null,
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
                val out = gap.toPx() + stroke / 2
                val radius = if (circular) {
                    size.minDimension / 2f + out
                } else {
                    cornerRadius.toPx() + out
                }
                drawRoundRect(
                    color = ringColor.copy(alpha = ringColor.alpha * reveal),
                    topLeft = Offset(-out, -out),
                    size = Size(size.width + out * 2f, size.height + out * 2f),
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(stroke),
                )
            }
        }
}
