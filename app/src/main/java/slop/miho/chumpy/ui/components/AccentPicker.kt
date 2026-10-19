package slop.miho.chumpy.ui.components

import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import slop.miho.chumpy.R
import slop.miho.chumpy.data.Accent
import slop.miho.chumpy.data.WallpaperPalette

/**
 * The interface accent picker, shared by the settings screen and the wallpaper screen's own
 * interface section: adaptive (the system's Material You) first, then the three seeds pulled
 * from the picked wallpaper, then the seven fixed hues and black-and-white — generously
 * spaced and horizontally scrollable, so the palette is never squeezed onto one line. Small
 * strips separate the adaptive option and the wallpaper seeds from the fixed colors.
 *
 * The seeds only exist with a wallpaper picked: with [monetPlaceholders] (the wallpaper
 * screen, where the slots are part of the layout's shape) they stay as empty circles,
 * everywhere else the missing seeds simply drop out. [title] renders a leading label when
 * the row stands alone in a group.
 */
@Composable
fun AccentRow(
    selected: Accent,
    onSelect: (Accent) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    monetPlaceholders: Boolean = false,
) {
    val context = LocalContext.current
    val monetHues by WallpaperPalette.hues(context).collectAsStateWithLifecycle()
    // The monochrome swatch follows the active theme: white in dark, black in light.
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    // The seeds' slots show while a wallpaper is picked (some seeds may still be missing),
    // or always when the caller keeps the slots as placeholders.
    val monetVisible = monetPlaceholders || !monetHues.isNullOrEmpty()
    Column(modifier) {
        title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
        // The scroll runs the card's full width with the padding inside the scrolled
        // content: the swatches travel edge to edge, sliding under the card's rounding
        // instead of stopping short of it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            // Tighter than it looks: every swatch's wrapper carries its own margin
            // around the 48dp color, so the circles sit a breath further apart
            // than the raw spacing says.
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The adaptive swatch shows the actual wallpaper-derived color, like the system
            // picker. Below Android 12 there is no Material You, so the option is not
            // offered at all.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val adaptive = remember { dynamicLightColorScheme(context).primary }
                Swatch(
                    color = adaptive,
                    label = stringResource(R.string.settings_accent_adaptive),
                    selected = selected == Accent.ADAPTIVE,
                    onClick = { onSelect(Accent.ADAPTIVE) },
                )
                SwatchDivider()
            }
            // The wallpaper's own seeds, straight out of the picture.
            if (monetVisible) {
                Accent.entries.filter { it.fromWallpaper }.forEachIndexed { index, accent ->
                    val hue = monetHues?.getOrNull(index)
                    if (hue != null) {
                        Swatch(
                            color = seedSwatchColor(hue),
                            label = stringResource(accent.labelRes),
                            selected = selected == accent,
                            onClick = { onSelect(accent) },
                        )
                    } else {
                        EmptySwatch()
                    }
                }
                SwatchDivider()
            }
            // The rainbow has no swatch of its own: it hides behind a long tap on the
            // monochrome one. While it is on no swatch is checked, as if a color off the
            // palette were picked; any tap picks a plain accent again.
            Accent.entries
                .filterNot { it == Accent.ADAPTIVE || it.fromWallpaper || it == Accent.RAINBOW }
                .forEach { accent ->
                    // The sky swatch shows its exact source color, the monochrome one
                    // follows the active theme; the rest are their hues made vivid.
                    val color = when (accent) {
                        Accent.MONO -> if (darkTheme) Color.White else Color.Black
                        Accent.SKY -> Color(0xFF6FACED)
                        else -> accent.swatchColor()
                    }
                    Swatch(
                        color = color,
                        label = stringResource(accent.labelRes),
                        selected = selected == accent,
                        onClick = { onSelect(accent) },
                        onLongClick = if (accent == Accent.MONO) {
                            { onSelect(Accent.RAINBOW) }
                        } else {
                            null
                        },
                    )
                }
        }
    }
}

/**
 * A small vertical outline-variant strip separating the palette's groups; the font picker
 * borrows it to set the user's fonts apart from the built-in ones.
 */
@Composable
fun SwatchDivider() {
    Box(
        modifier = Modifier
            .width(3.dp)
            .height(24.dp)
            .background(
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(2.dp),
            ),
    )
}

/**
 * A round color swatch; the pick's mark is the accent's own ring, drawn a breath off the
 * color — never touching it — while the color itself yields, shrinking a little under the
 * ring so the marked swatch reads no bigger than its neighbors. Pressing squashes it like
 * the square buttons, with the same light poke.
 */
@Composable
private fun Swatch(
    color: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val poke = rememberHapticPoke()
    // Bumped in the click handler: the pop keys off it, so it fires on every click — the
    // press state alone can go unnoticed on a quick tap.
    var taps by remember { mutableIntStateOf(0) }
    val ringWidth by animateDpAsState(
        targetValue = if (selected) 3.dp else 0.dp,
        animationSpec = motion.defaultSpatialSpec(),
        label = "swatch ring width",
    )
    val mark by animateFloatAsState(
        targetValue = if (selected) SWATCH_MARK_SCALE else 1f,
        animationSpec = motion.defaultSpatialSpec(),
        label = "swatch mark scale",
    )
    Box(
        modifier = Modifier
            .size(60.dp)
            .pressBounce(pressed, taps)
            .clip(CircleShape)
            .drawBehind {
                // Drawn only once there's width to draw: a zero-width stroke would still
                // leave its hairline behind, a phantom ring at rest.
                val stroke = ringWidth.toPx()
                if (stroke > 0.5f) {
                    drawCircle(
                        color = colors.primary,
                        style = Stroke(stroke),
                        // The ring rides off the shrinking circle's own rim, the gap
                        // between them held at a breath — the ring closes in as the color
                        // yields, settling level with the neighbors' edges.
                        radius = 24.dp.toPx() * mark + SWATCH_RING_GAP.toPx() + stroke / 2,
                    )
                }
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onLongClick = onLongClick?.let { action ->
                    {
                        poke()
                        taps++
                        action()
                    }
                },
            ) {
                poke()
                taps++
                onClick()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .graphicsLayer {
                    scaleX = mark
                    scaleY = mark
                }
                .clip(CircleShape)
                .background(color),
        )
    }
}

/** How far the color yields under the pick's ring. */
private const val SWATCH_MARK_SCALE = 0.85f

/** The breath between the color and the ring. */
private val SWATCH_RING_GAP = 3.dp

/** The outline circle standing in for a wallpaper seed that isn't there (yet). */
@Composable
private fun EmptySwatch() {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            ),
    )
}

/** The vivid picker color for an accent hue — recognizable, unlike the muted scheme primary. */
private fun Accent.swatchColor(): Color {
    val hsl = floatArrayOf(hue, 0.62f, 0.6f)
    return Color(ColorUtils.HSLToColor(hsl))
}

/** The same vivid treatment for a seed hue pulled from the wallpaper. */
private fun seedSwatchColor(hue: Float): Color {
    val hsl = floatArrayOf(hue, 0.62f, 0.6f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private val Accent.labelRes: Int
    get() = when (this) {
        Accent.ADAPTIVE -> R.string.settings_accent_adaptive
        Accent.WALLPAPER_1 -> R.string.settings_accent_wallpaper_1
        Accent.WALLPAPER_2 -> R.string.settings_accent_wallpaper_2
        Accent.WALLPAPER_3 -> R.string.settings_accent_wallpaper_3
        Accent.RED -> R.string.settings_accent_red
        Accent.ORANGE -> R.string.settings_accent_orange
        Accent.YELLOW -> R.string.settings_accent_yellow
        Accent.GREEN -> R.string.settings_accent_green
        Accent.SKY -> R.string.settings_accent_sky
        Accent.BLUE -> R.string.settings_accent_blue
        Accent.INDIGO -> R.string.settings_accent_indigo
        Accent.VIOLET -> R.string.settings_accent_violet
        Accent.MONO -> R.string.settings_accent_mono
        Accent.RAINBOW -> R.string.settings_accent_rainbow
    }
