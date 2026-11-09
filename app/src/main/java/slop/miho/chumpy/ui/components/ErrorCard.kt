package slop.miho.chumpy.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import slop.miho.chumpy.R
import slop.miho.chumpy.ui.wallpapers.withSurfaceOpacity

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
 * An available update as the failure card's own cut retold in the accent: the selected
 * server's own palette (primary container, the diamond tile in primary), the download
 * mark over the spinning tile, the release's story as the headline — the surface keeps
 * the house translucency, unlike the failure card's solid error wash. Tapped, it
 * downloads — or pauses one already running — or hands the APK on disk to the installer.
 *
 * While the download runs the text and tile step aside for Material's own wavy linear
 * bar, filled to the percent the stream has carried; a pause brings the text back —
 * the percent held in the line, the mark itself swapped for the pause glyph. Both
 * faces share one frame, so the card keeps its height between them.
 */
@Composable
fun UpdateCard(
    downloading: Boolean,
    paused: Boolean,
    percent: Int,
    message: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val container = colors.primaryContainer.withSurfaceOpacity()
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        contentColor = colors.onPrimaryContainer,
        modifier = modifier,
    ) {
        // The two faces stacked, not swapped: the row always sits in the tree and so
        // always owns the card's height — the bar draws over it while the download
        // runs. Swapping the faces left each its own height (the row's tile and
        // paddings add up to more than any pinned constant), and the card flinched
        // on every switch.
        Box {
            var singleLine by remember { mutableStateOf(false) }
            val tileSize = if (singleLine) 32.dp else 40.dp
            val iconSize = if (singleLine) 20.dp else 24.dp
            ListItem(
                // Out of sight, out of the semantics tree — the card's own click
                // carries the pause either way.
                modifier = if (downloading) {
                    Modifier
                        .alpha(0f)
                        .clearAndSetSemantics { }
                } else {
                    Modifier
                },
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
                        // The update's own tempo: livelier than the failure's slow tick —
                        // something to have noticed, not waited five seconds for.
                        SpinningDiamond(
                            color = colors.primary.withSurfaceOpacity(),
                            tickMillis = UPDATE_DIAMOND_TICK_MILLIS,
                        )
                        Icon(
                            painter = painterResource(
                                if (paused) R.drawable.ic_pause else R.drawable.ic_download,
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(iconSize),
                            tint = colors.onPrimary,
                        )
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = container,
                    headlineColor = colors.onPrimaryContainer,
                    leadingIconColor = colors.onPrimaryContainer,
                ),
                tonalElevation = 0.dp,
            )
            if (downloading) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        // The message's own lines never touch the card's edges; the
                        // bar sits inside the same margins.
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    LinearWavyProgressIndicator(
                        progress = { percent / 100f },
                        color = colors.primary,
                        trackColor = colors.primary.copy(alpha = 0.25f),
                        // The default wave is a banner of its own; a quieter quarter keeps
                        // it a progress line.
                        amplitude = { 0.25f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * A plain square tilted 45° into a diamond. Every [tickMillis] it ticks another quarter turn
 * (which lands on the same silhouette) with a spring that overshoots and settles with a
 * bounce — but only while [animated], otherwise it just sits still at its tilt.
 */
@Composable
fun SpinningDiamond(color: Color, animated: Boolean = true, tickMillis: Long = DIAMOND_TICK_MILLIS) {
    var targetAngle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animated) {
        if (!animated) return@LaunchedEffect
        while (true) {
            delay(tickMillis)
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

/** The update card's livelier tick — the failure's patient one stays its own. */
private const val UPDATE_DIAMOND_TICK_MILLIS = 2_000L
