package slop.miho.chumpy.ui.components

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.text.BasicTextField
import slop.miho.chumpy.ui.components.controllerWindowInput
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import slop.miho.chumpy.ui.components.controllerContextMenu
import slop.miho.chumpy.ui.components.controllerHorizontalFocusGroup
import slop.miho.chumpy.R
import slop.miho.chumpy.data.Accent
import slop.miho.chumpy.data.WallpaperPalette
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The interface accent picker, shared by the settings screen and the wallpaper screen's own
 * interface section: adaptive (the system's Material You) first, then the three seeds pulled
 * from the picked wallpaper, then the seven fixed hues and black-and-white, and the user's
 * own color last behind a divider — generously spaced and horizontally scrollable, so the
 * palette is never squeezed onto one line. Small strips separate the adaptive option, the
 * wallpaper seeds and the custom color from the fixed colors.
 *
 * The seeds only exist with a wallpaper picked: with [monetPlaceholders] (the wallpaper
 * screen, where the slots are part of the layout's shape) they stay as empty circles,
 * everywhere else the missing seeds simply drop out. [title] renders a leading label when
 * the row stands alone in a group.
 *
 * The custom swatch wears the paint-roller mark over its color: while unpicked the color
 * wanders the wheel — a new random one every three seconds, eased into place — and a tap
 * opens the picking dialog (a wheel and a hex field); picked, it holds the stored color.
 */
@Composable
fun AccentRow(
    selected: Accent,
    onSelect: (Accent) -> Unit,
    customColor: Color,
    onPickCustom: (Color) -> Unit,
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
    // The picking dialog opens from the custom swatch; picking hands the color over.
    var customDialog by remember { mutableStateOf(false) }
    if (customDialog) {
        CustomColorDialog(
            initial = customColor,
            onDismiss = { customDialog = false },
            onPick = { color ->
                customDialog = false
                onPickCustom(color)
            },
        )
    }
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
                .controllerHorizontalFocusGroup()
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
                .filterNot {
                    it == Accent.ADAPTIVE || it.fromWallpaper || it == Accent.RAINBOW ||
                        it == Accent.CUSTOM
                }
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
            // The user's own color, the roller over it marking it as the custom one.
            SwatchDivider()
            // A new random color every three seconds while the accent lives unpicked;
            // picked, the wander stops and the stored color stands still.
            var wander by remember { mutableStateOf(randomVividColor()) }
            LaunchedEffect(selected == Accent.CUSTOM) {
                if (selected != Accent.CUSTOM) {
                    while (true) {
                        delay(3000)
                        wander = randomVividColor()
                    }
                }
            }
            val wanderColor by animateColorAsState(
                targetValue = if (selected == Accent.CUSTOM) customColor else wander,
                animationSpec = tween(durationMillis = 2000),
                label = "custom swatch wander",
            )
            Swatch(
                color = wanderColor,
                label = stringResource(R.string.settings_accent_custom),
                selected = selected == Accent.CUSTOM,
                onClick = { customDialog = true },
            ) { color ->
                Icon(
                    painter = painterResource(R.drawable.ic_format_paint),
                    contentDescription = null,
                    // The roller reads in the color's own opposite: ink on light colors,
                    // paper on dark ones.
                    tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                    modifier = Modifier.size(24.dp),
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
 * the square buttons, with the same light poke. [content], when given, draws inside the
 * color (the custom accent's paint roller); it receives the color it sits on.
 */
@Composable
private fun Swatch(
    color: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: (@Composable (Color) -> Unit)? = null,
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
            .focusRing(inset = true, circular = true, gap = 1.dp)
            .controllerContextMenu(onLongClick)
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
            contentAlignment = Alignment.Center,
        ) {
            content?.invoke(color)
        }
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
        Accent.CUSTOM -> R.string.settings_accent_custom
    }

/**
 * The custom accent's picking dialog: a wheel and a hex field over the same color. The
 * wheel answers with hue (the angle) and saturation (the distance from the center) at the
 * color's own lightness, so the wander stays inside the register the color came in; the
 * hex field takes the whole color as #RRGGBB. Picking hands the color out; dismissing
 * leaves everything as it was.
 */
@Composable
private fun CustomColorDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onPick: (Color) -> Unit,
) {
    var color by remember { mutableStateOf(initial) }
    AlertDialog(
        modifier = Modifier.controllerWindowInput(onDismiss),
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_format_paint),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(stringResource(R.string.settings_accent_custom_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                ColorWheel(color = color, onColor = { color = it })
                Spacer(Modifier.height(12.dp))
                if (LocalInputMode.current == InputMode.CONTROLLER) {
                    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(color.toArgb(), it) }
                    listOf(R.string.color_hue, R.string.color_saturation, R.string.color_lightness).forEachIndexed { index, label ->
                        Text(stringResource(label), modifier = Modifier.fillMaxWidth())
                        Slider(value = hsl[index], valueRange = if (index == 0) 0f..360f else 0f..1f,
                            onValueChange = { value -> color = Color(ColorUtils.HSLToColor(hsl.copyOf().also { it[index] = value })) },
                            modifier = Modifier.focusRing(inset = true, gap = 1.dp))
                    }
                }
                HexField(color = color, onColor = { color = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(color) }) {
                Text(stringResource(R.string.settings_accent_custom_pick))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.subscription_cancel))
            }
        },
    )
}

/** The wheel's own face, thumb and all. */
private val WHEEL_SIZE = 240.dp

/** The thumb's own rim. */
private val WHEEL_THUMB = 20.dp

/**
 * Wherever a finger lands on the wheel becomes the color: the angle the hue, the distance
 * from the center the saturation, at the lightness the wheel is painted in.
 */
private fun reportWheel(
    position: Offset,
    size: androidx.compose.ui.unit.IntSize,
    onColor: (Color) -> Unit,
    lightness: Float,
) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = min(size.width, size.height) / 2f
    val dx = position.x - center.x
    val dy = position.y - center.y
    val dist = min(sqrt(dx * dx + dy * dy), radius)
    val hue = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 360.0) % 360.0).toFloat()
    val sat = dist / radius
    val hsl = floatArrayOf(hue, sat, lightness)
    onColor(Color(ColorUtils.HSLToColor(hsl)))
}

/**
 * The HSL wheel: the angle walks the hues, the distance from the center the saturation,
 * and the lightness stays wherever the incoming color sits — the wheel is painted at that
 * lightness, so the thumb never lies about the color it stands on. A drag or a tap moves
 * the thumb and reports the color under it.
 */
@Composable
private fun ColorWheel(
    color: Color,
    onColor: (Color) -> Unit,
) {
    // The lightness the whole wheel renders at, held while the thumb wanders it.
    val lightness = remember(color.toArgb()) {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color.toArgb(), hsl)
        hsl[2]
    }
    val outline = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = Modifier
            .size(WHEEL_SIZE)
            // Both the tap and the drag speak the same language: wherever the finger
            // lands, the thumb and the color follow. Two detectors, two modifiers —
            // each holds its own stream and never starves the other.
            .pointerInput(lightness) {
                detectTapGestures { position -> reportWheel(position, size, onColor, lightness) }
            }
            .pointerInput(lightness) {
                detectDragGestures { change, _ ->
                    change.consume()
                    reportWheel(change.position, size, onColor, lightness)
                }
            },
    ) {
        val radius = min(size.width, size.height) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        // The hues, swept around the wheel at full saturation and the color's lightness.
        val hues = List(13) { index ->
            val hue = index * 30f
            Color(ColorUtils.HSLToColor(floatArrayOf(hue, 1f, lightness)))
        }
        drawCircle(brush = Brush.sweepGradient(hues, center), radius = radius, center = center)
        // The saturation, faded in from the middle: the wheel's own gray at full alpha
        // in the center, gone at the rim.
        val gray = Color(ColorUtils.HSLToColor(floatArrayOf(0f, 0f, lightness)))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(gray.copy(alpha = 1f), gray.copy(alpha = 0f)),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
        // The thumb, riding where the color itself sits on the wheel.
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color.toArgb(), hsl)
        val angle = Math.toRadians(hsl[0].toDouble())
        val dist = hsl[1] * radius
        val thumb = Offset(
            center.x + (dist * cos(angle)).toFloat(),
            center.y + (dist * sin(angle)).toFloat(),
        )
        drawCircle(color = color, radius = WHEEL_THUMB.toPx() / 2, center = thumb)
        drawCircle(
            color = outline,
            radius = WHEEL_THUMB.toPx() / 2,
            center = thumb,
            style = Stroke(width = 3.dp.toPx()),
        )
    }
}

/**
 * The color as #RRGGBB: typing a well-formed hex paints the wheel at once, anything else
 * waits where it is. The text follows the wheel's every move while the field holds no
 * focus, and lets itself be edited the moment it does.
 */
@Composable
private fun HexField(
    color: Color,
    onColor: (Color) -> Unit,
) {
    var text by remember { mutableStateOf(color.toHexText()) }
    var focused by remember { mutableStateOf(false) }
    if (!focused) text = color.toHexText()
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = text,
        onValueChange = { raw ->
            val cleaned = raw.trim().removePrefix("#")
            text = cleaned.take(6)
            // Six well-formed hex digits make a color; anything less keeps the field as
            // a scratchpad until it is complete.
            if (cleaned.matches(Regex("[0-9a-fA-F]{6}"))) {
                onColor(Color(android.graphics.Color.parseColor("#$cleaned")))
            }
        },
        textStyle = MaterialTheme.typography.bodyLarge.merge(
            TextStyle(textAlign = TextAlign.Center, color = colors.onSurface),
        ),
        cursorBrush = Brush.verticalGradient(listOf(colors.primary, colors.primary)),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .onFocusChanged { focused = it.isFocused },
    )
}

/** The color as the hex field speaks it: #RRGGBB, uppercase. */
private fun Color.toHexText(): String =
    "#%06X".format(toArgb() and 0xFFFFFF)

/** A vivid color off the whole wheel — the custom swatch's wander, in the same register the fixed swatches show their hues in. */
private fun randomVividColor(): Color {
    val hsl = floatArrayOf(kotlin.random.Random.nextFloat() * 360f, 0.62f, 0.6f)
    return Color(ColorUtils.HSLToColor(hsl))
}
