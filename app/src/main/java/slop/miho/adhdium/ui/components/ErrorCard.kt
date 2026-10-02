package slop.miho.adhdium.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import slop.miho.adhdium.R

/**
 * A failure as a non-clickable card shaped like a list row: error container, spinning
 * diamond icon backdrop, the message as the headline. Shared by the servers refresh failure
 * and the home connect failure so both read as the same kind of "it went wrong".
 *
 * A single-line message takes a slightly smaller icon backdrop so the card stays compact
 * and the space around the icon reads even; wrapped messages keep the regular size.
 */
@Composable
fun ErrorCard(message: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var singleLine by remember { mutableStateOf(false) }
    val tileSize = if (singleLine) 32.dp else 40.dp
    val iconSize = if (singleLine) 20.dp else 24.dp
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.errorContainer,
        contentColor = colors.onErrorContainer,
        modifier = modifier,
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    onTextLayout = { layout -> singleLine = layout.lineCount <= 1 },
                )
            },
            leadingContent = {
                Box(
                    modifier = Modifier.size(tileSize),
                    contentAlignment = Alignment.Center,
                ) {
                    SpinningDiamond(color = colors.error)
                    Icon(
                        painter = painterResource(R.drawable.ic_error),
                        contentDescription = null,
                        modifier = Modifier.size(iconSize),
                        tint = colors.onError,
                    )
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = colors.errorContainer,
                headlineColor = colors.onErrorContainer,
                leadingIconColor = colors.onErrorContainer,
            ),
            tonalElevation = 0.dp,
        )
    }
}

/**
 * A plain square tilted 45° into a diamond. Every few seconds it ticks another quarter turn
 * (which lands on the same silhouette) with a spring that overshoots and settles with a
 * bounce — but only while [animated], otherwise it just sits still at its tilt.
 */
@Composable
fun SpinningDiamond(color: Color, animated: Boolean = true) {
    var targetAngle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animated) {
        if (!animated) return@LaunchedEffect
        while (true) {
            delay(DIAMOND_TICK_MILLIS)
            targetAngle += 90f
        }
    }
    val angle by animateFloatAsState(
        targetValue = targetAngle,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "diamond angle",
    )
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { rotationZ = 45f + angle }
            .clip(RoundedCornerShape(8.dp))
            .background(color),
    )
}

private const val DIAMOND_TICK_MILLIS = 5_000L
