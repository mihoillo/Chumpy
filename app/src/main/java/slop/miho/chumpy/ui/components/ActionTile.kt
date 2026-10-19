package slop.miho.chumpy.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A square action tile — the settings' toggle tile's own twin, only it presses instead
 * of switching: the 48dp rounded square on the secondary container, the mark centered,
 * the same pressed dip and click pop. One tile, one action. [indicated] false takes the
 * press light off — for the doors that hand over to another activity, where the ripple
 * would hang mid-fade behind the transition.
 */
@Composable
fun ActionTile(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    indicated: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val poke = rememberHapticPoke()
    // Bumped in the click handler: the pop keys off it, so it fires on every click —
    // the press state alone can go unnoticed on a quick tap.
    var taps by remember { mutableIntStateOf(0) }
    val resting = colors.secondaryContainer
    val container by animateColorAsState(
        targetValue = if (indicated && pressed) lerp(resting, colors.onSurface, 0.1f) else resting,
        animationSpec = motion.defaultEffectsSpec(),
        label = "action tile container",
    )
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = container,
        contentColor = colors.onSecondaryContainer,
        modifier = modifier
            .size(48.dp)
            .pressBounce(pressed, taps)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = if (indicated) LocalIndication.current else null,
                role = Role.Button,
            ) {
                poke()
                taps++
                onClick()
            }
            .semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
