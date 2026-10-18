package slop.miho.aurora.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import slop.miho.aurora.ui.wallpapers.withSurfaceOpacity

/**
 * A compact value chip used in the info chip rows on the home and servers screens. The value
 * can animate on change (e.g. a refreshed count or an IP appearing) without animating the
 * chip itself appearing or disappearing.
 */
@Composable
fun InfoChip(
    value: String,
    icon: Int? = null,
    leading: (@Composable () -> Unit)? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow.withSurfaceOpacity(),
    content: Color = MaterialTheme.colorScheme.onSurface,
    // Animate the value swapping (e.g. shrug → real IP), not the chip appearing.
    animateValue: Boolean = false,
    // Set for the few chips that answer a tap (the exit IP hides itself); null keeps it inert.
    onClick: (() -> Unit)? = null,
    // The compact cut: labelMedium on a tight pad — the inline badge that rides a value
    // line (the version row's stage chip) without outgrowing it.
    compact: Boolean = false,
) {
    val chipContent: @Composable () -> Unit = {
        Row(
            modifier = if (compact) {
                Modifier.padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
            } else {
                Modifier.padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = content,
                )
                Spacer(Modifier.size(4.dp))
            }
            leading?.invoke()
            if (leading != null) Spacer(Modifier.size(5.dp))
            if (animateValue) {
                val motion = MaterialTheme.motionScheme
                AnimatedContent(
                    targetState = value,
                    transitionSpec = {
                        fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.defaultEffectsSpec())
                    },
                    label = "chip value",
                ) { v ->
                    Text(
                        text = v,
                        style = if (compact) {
                            MaterialTheme.typography.labelMedium
                        } else {
                            MaterialTheme.typography.labelLarge
                        },
                        color = content,
                    )
                }
            } else {
                Text(
                    text = value,
                    style = if (compact) {
                        MaterialTheme.typography.labelMedium
                    } else {
                        MaterialTheme.typography.labelLarge
                    },
                    color = content,
                )
            }
        }
    }
    val shape = RoundedCornerShape(8.dp)
    // A plain clickable rather than Surface(onClick): the clickable Surface pads itself up to
    // the 48dp minimum touch target, which made a tappable chip taller than its neighbors.
    Surface(
        shape = shape,
        color = container,
        modifier = if (onClick != null) Modifier.clip(shape).clickable(onClick = onClick) else Modifier,
        content = chipContent,
    )
}
