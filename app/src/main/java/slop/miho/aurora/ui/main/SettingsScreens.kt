package slop.miho.aurora.ui.main

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.annotation.DrawableRes
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.roundToInt
import slop.miho.aurora.R
import slop.miho.aurora.data.Accent
import slop.miho.aurora.data.FlagStyle
import slop.miho.aurora.data.ThemeMode
import slop.miho.aurora.data.UiSettings
import slop.miho.aurora.ui.components.AccentRow
import slop.miho.aurora.ui.components.CountryFlag
import slop.miho.aurora.ui.components.RainbowFlag
import slop.miho.aurora.ui.components.pressBounce
import slop.miho.aurora.ui.components.rememberHapticPoke
import slop.miho.aurora.ui.fonts.FontPickerRow
import slop.miho.aurora.ui.wallpapers.ReportBarCoverage
import slop.miho.aurora.ui.wallpapers.WallpaperActivity
import slop.miho.aurora.ui.wallpapers.withSurfaceOpacity

/**
 * The "Interface" settings page. One screen, two sections — Appearance and Privacy — each a
 * stack of headerless grouped blocks (MD3 expressive grouped lists: tonal surface-container
 * groups, extra-large outer corners, extra-small inner corners, 2dp gaps instead of dividers).
 * No screen headline: the bottom bar pill already says where we are.
 */
@Composable
fun InterfaceScreen(
    settings: UiSettings,
    onChange: ((UiSettings) -> UiSettings) -> Unit,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    // The groups sit under the floating bar while the page outgrows the screen; derived so
    // only the flip recomposes, not every scroll pixel.
    val contentUnderBar by remember(scrollState) {
        derivedStateOf { scrollState.canScrollForward || scrollState.canScrollBackward }
    }
    val contentReachesBar by rememberContentUnderBar(scrollState)
    ReportBarCoverage(contentUnderBar, underlaid = contentReachesBar)
    // Padding after verticalScroll is content padding: it scrolls away at the top and
    // keeps the last row clear of the floating bar at the bottom.
    Column(
        modifier = modifier
            .fillMaxSize()
            .screenPadding()
            .verticalScroll(scrollState)
            .padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 24.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                    FloatingBottomBarReservedHeight,
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // No section header any more: the blocks stand on their own, at the gap they had
        // under it.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsGroup(
                {
                    IconChoiceRow(
                        title = stringResource(R.string.settings_colors),
                        options = listOf(
                            IconChoice(
                                R.drawable.ic_brightness_auto,
                                R.string.settings_theme_system,
                                ThemeMode.SYSTEM,
                            ),
                            IconChoice(
                                R.drawable.ic_light_mode,
                                R.string.settings_theme_light,
                                ThemeMode.LIGHT,
                            ),
                            // The long tap turns on AMOLED: the dark theme on a pure black
                            // background. It lights the dark tile; a plain tap goes back.
                            IconChoice(
                                R.drawable.ic_dark_mode,
                                R.string.settings_theme_dark,
                                ThemeMode.DARK,
                                onLongClick = { onChange { it.copy(themeMode = ThemeMode.AMOLED) } },
                            ),
                        ),
                        selected = if (settings.themeMode == ThemeMode.AMOLED) ThemeMode.DARK else settings.themeMode,
                        onSelect = { mode -> onChange { it.copy(themeMode = mode) } },
                    )
                },
                {
                    AccentRow(
                        selected = settings.accent,
                        onSelect = { accent -> onChange { it.copy(accent = accent) } },
                    )
                },
            )
            // The wallpapers live in their own screen, between the colors and the fonts.
            SettingsGroup(
                {
                    ScreenLinkRow(
                        title = stringResource(R.string.settings_wallpapers),
                        supporting = stringResource(R.string.settings_wallpapers_hint),
                        icon = R.drawable.ic_wallpaper,
                        target = WallpaperActivity::class.java,
                    )
                },
            )
            // The fonts are picked right in their row, no screen of their own. The flags'
            // look shares their block: both are about how text and glyphs are drawn.
            // While the rainbow accent is on, the previews fly its flag.
            val rainbow = settings.accent == Accent.RAINBOW
            SettingsGroup(
                {
                    FontPickerRow(
                        selected = settings.fontId,
                        onSelect = { id -> onChange { it.copy(fontId = id) } },
                    )
                },
                {
                    IconChoiceRow(
                        title = stringResource(R.string.settings_flag_style),
                        // Like the font row, the picked style is named under the title.
                        showSelectedLabel = true,
                        options = listOf(
                            IconChoice(R.string.settings_flag_system, FlagStyle.SYSTEM) {
                                PreviewFlag(rainbow, twemoji = false)
                            },
                            IconChoice(R.string.settings_flag_twemoji, FlagStyle.TWEMOJI) {
                                PreviewFlag(rainbow, twemoji = true)
                            },
                            IconChoice(
                                R.drawable.ic_public,
                                R.string.settings_flag_globe,
                                FlagStyle.GLOBE,
                            ),
                        ),
                        selected = settings.flagStyle,
                        onSelect = { style -> onChange { it.copy(flagStyle = style) } },
                    )
                },
            )
            // The navigation bar's own block: which bar, and its frost.
            val classicBar = classicNavigationBar()
            val smallScreen = isSmallScreen()
            val smallByWidth = smallScreenByWidth()
            SettingsGroup(
                {
                    ToggleRow(
                        title = stringResource(R.string.settings_classic_navbar),
                        // While the small-screen layout is forced, the hint says so — it's
                        // the only trace of the long tap besides the margins themselves.
                        supporting = stringResource(
                            when (settings.smallScreen) {
                                true -> R.string.settings_small_screen_forced_on
                                false -> R.string.settings_small_screen_forced_off
                                null -> R.string.settings_classic_navbar_hint
                            },
                        ),
                        // A hidden switch: the long tap forces the small-screen layout the
                        // other way, and forcing it back to what the width says lets go.
                        onLongClick = {
                            val flipped = !smallScreen
                            onChange { it.copy(smallScreen = flipped.takeIf { forced -> forced != smallByWidth }) }
                        },
                        // The edge-to-edge bar while on, the inset floating one while off.
                        icon = if (classicBar) {
                            R.drawable.ic_bottom_navigation
                        } else {
                            R.drawable.ic_call_to_action
                        },
                        // Shows the resolved state: unpicked, the screen's default.
                        checked = classicBar,
                        onChange = { checked ->
                            onChange { it.copy(classicNavigationBar = checked) }
                        },
                    )
                },
                {
                    ToggleRow(
                        title = stringResource(R.string.settings_navbar_blur),
                        supporting = stringResource(R.string.settings_navbar_blur_hint),
                        // Frosted glass while on, plain translucency while off.
                        icon = if (settings.navigationBarBlur) {
                            R.drawable.ic_blur_on
                        } else {
                            R.drawable.ic_opacity
                        },
                        checked = settings.navigationBarBlur,
                        onChange = { checked ->
                            onChange { it.copy(navigationBarBlur = checked) }
                        },
                    )
                },
                // The intensity is a row of its own, unfolding under the toggle only while
                // the frost is on: the higher, the denser the pill's tint and the deeper its blur.
                {
                    SliderRow(
                        icon = R.drawable.ic_blur_on,
                        hint = stringResource(R.string.settings_navbar_blur_intensity),
                        value = settings.navigationBarBlurIntensity,
                        // The floor keeps a ghost of the bar legible; the ceiling keeps a
                        // ghost of what passes beneath — the frost never fully dissolves
                        // either side.
                        valueRange = 0.35f..0.85f,
                        onChange = { value ->
                            onChange { it.copy(navigationBarBlurIntensity = value) }
                        },
                    )
                },
                isVisible = { index -> index != 2 || settings.navigationBarBlur },
            )
            SettingsGroup(
                {
                    ToggleRow(
                        title = stringResource(R.string.settings_slow_animations),
                        supporting = stringResource(R.string.settings_slow_animations_hint),
                        // Low speedometer while on, the plain one while off: normal or slow,
                        // nothing faster.
                        icon = if (settings.slowAnimations) {
                            R.drawable.ic_speed_slow
                        } else {
                            R.drawable.ic_speed_normal
                        },
                        checked = settings.slowAnimations,
                        onChange = { checked -> onChange { it.copy(slowAnimations = checked) } },
                    )
                },
                {
                    ToggleRow(
                        title = stringResource(R.string.settings_expressive_transitions),
                        supporting = stringResource(R.string.settings_expressive_transitions_hint),
                        // Sliding arrows while the carousel strip is on, fade circle for the
                        // direct switch.
                        icon = if (settings.expressiveTransitions) {
                            R.drawable.ic_swap_horizontal
                        } else {
                            R.drawable.ic_opacity
                        },
                        checked = settings.expressiveTransitions,
                        onChange = { checked -> onChange { it.copy(expressiveTransitions = checked) } },
                    )
                },
            )
            // Haptics get their own block, right under the animation settings.
            SettingsGroup(
                {
                    ToggleRow(
                        title = stringResource(R.string.settings_haptic_feedback),
                        supporting = stringResource(R.string.settings_haptic_feedback_hint),
                        // Vibrating phone while on, crossed-out hand while off.
                        icon = if (settings.hapticFeedback) {
                            R.drawable.ic_vibration
                        } else {
                            R.drawable.ic_do_not_touch
                        },
                        checked = settings.hapticFeedback,
                        onChange = { checked -> onChange { it.copy(hapticFeedback = checked) } },
                    )
                },
            )
        }
    }
}

/**
 * A rounded tonal container of setting rows: extra-large outer corners, extra-small inner
 * corners and a 2dp gap between rows instead of dividers (the MD3 grouped-list pattern the
 * server list also uses).
 *
 * [isVisible] folds rows (past the first) in and out by index. The gap travels with its row,
 * so a folding row takes its gap along; and the group's own clip keeps the outer rounding on
 * whichever row ends up last.
 */
@Composable
private fun SettingsGroup(
    vararg rows: @Composable () -> Unit,
    isVisible: (Int) -> Boolean = { true },
) {
    val outer = MaterialTheme.shapes.extraLarge
    val inner = MaterialTheme.shapes.extraSmall
    val motion = MaterialTheme.motionScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(outer),
    ) {
        rows.forEachIndexed { index, row ->
            val surface: @Composable () -> Unit = {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer.withSurfaceOpacity(),
                    shape = RoundedCornerShape(
                        topStart = if (index == 0) outer.topStart else inner.topStart,
                        topEnd = if (index == 0) outer.topEnd else inner.topEnd,
                        bottomStart = if (index == rows.lastIndex) outer.bottomStart else inner.bottomStart,
                        bottomEnd = if (index == rows.lastIndex) outer.bottomEnd else inner.bottomEnd,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    row()
                }
            }
            if (index == 0) {
                surface()
            } else {
                AnimatedVisibility(
                    visible = isVisible(index),
                    enter = expandVertically(motion.defaultSpatialSpec()) +
                        fadeIn(motion.defaultEffectsSpec()),
                    exit = shrinkVertically(motion.defaultSpatialSpec()) +
                        fadeOut(motion.defaultEffectsSpec()),
                ) {
                    Column(Modifier.padding(top = 2.dp)) {
                        surface()
                    }
                }
            }
        }
    }
}

/**
 * One option of an [IconChoiceRow]: its label (read out, never shown), its value, and the
 * 24dp art drawn in its tile — a plain drawable icon, or anything else (the flag previews).
 * [onLongClick] is an optional hidden action on the long tap.
 */
private class IconChoice<T>(
    val label: Int,
    val value: T,
    val onLongClick: (() -> Unit)? = null,
    val content: @Composable () -> Unit,
) {
    constructor(@DrawableRes icon: Int, label: Int, value: T, onLongClick: (() -> Unit)? = null) : this(
        label,
        value,
        onLongClick,
        { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp)) },
    )
}

/** The country the flag style tiles preview their look with. */
private const val FLAG_PREVIEW_COUNTRY = "JP"

/** A flag style tile's preview: the country's flag, or the rainbow one while [rainbow]. */
@Composable
private fun PreviewFlag(rainbow: Boolean, twemoji: Boolean) {
    Crossfade(rainbow, animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(), label = "flag preview") { pride ->
        if (pride) {
            RainbowFlag(twemoji)
        } else {
            CountryFlag(code = FLAG_PREVIEW_COUNTRY, twemoji = twemoji)
        }
    }
}

/**
 * The row's triangle tile shape: an equilateral triangle, one corner pointing right — the
 * "go deeper" affordance — with corners rounded about as much as the square tiles.
 */
private val TriangleTilePolygon =
    RoundedPolygon(numVertices = 3, rounding = CornerRounding(0.25f)).normalized()

/**
 * A setting with a few mutually exclusive options, picked with square icon buttons.
 * [showSelectedLabel] names the picked option under the title, as the font row does.
 */
@Composable
private fun <T> IconChoiceRow(
    title: String,
    options: List<IconChoice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    showSelectedLabel: Boolean = false,
) {
    // Same content padding as the accent row below: one unified 16dp from the group edge
    // to the controls in both rows, and 12dp between the buttons.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (showSelectedLabel) {
                options.firstOrNull { it.value == selected }?.let { option ->
                    Text(
                        text = stringResource(option.label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.padding(start = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            options.forEach { option ->
                SquareIconButton(
                    label = stringResource(option.label),
                    selected = option.value == selected,
                    onClick = { onSelect(option.value) },
                    onLongClick = option.onLongClick,
                    content = option.content,
                )
            }
        }
    }
}

/**
 * A square icon toggle: raised tonal container by default, the accent container while
 * selected, with the usual springy color swap. Pressing squashes it down a touch and dips
 * the container tone (a 10% on-surface state layer); every click bounces it back with a
 * pop, so a quick tap reads as clearly as a held one.
 */
@Composable
private fun SquareIconButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val poke = rememberHapticPoke()
    // Bumped in the click handler: the pop keys off it, so it fires on every click — the
    // press state alone can go unnoticed on a quick tap.
    var taps by remember { mutableIntStateOf(0) }
    val resting = (if (selected) colors.primary else colors.secondaryContainer)
    val container by animateColorAsState(
        targetValue = if (pressed) lerp(resting, colors.onSurface, 0.1f) else resting,
        animationSpec = motion.defaultEffectsSpec(),
        label = "icon button container",
    )
    val content by animateColorAsState(
        targetValue = if (selected) colors.onPrimary else colors.onSecondaryContainer,
        animationSpec = motion.defaultEffectsSpec(),
        label = "icon button content",
    )
    // A plain surface with the click on its modifier: Surface's own onClick has no long tap.
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        color = container,
        contentColor = content,
        modifier = Modifier
            .size(48.dp)
            .pressBounce(pressed, taps)
            .clip(shape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
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
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * A boolean setting. The whole row is the touch target, like the server list buttons; the
 * state shows as a square icon tile instead of a switch: tonal while off, accent while on,
 * with the icon swapping to match the state. Laid out by hand rather than via ListItem:
 * ListItem's own vertical geometry shifts with the line count (the tile rides at 8dp on
 * two-line rows and top-aligns on wrapped ones), while here every square button sits at the
 * same 16dp from the card edges as the pickers above.
 */
@Composable
private fun ToggleRow(
    title: String,
    supporting: String?,
    icon: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val poke = rememberHapticPoke()
    // Bumped in the click handler: the tile's pop keys off it, so it fires on every click —
    // the press state alone can go unnoticed on a quick tap.
    var taps by remember { mutableIntStateOf(0) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onLongClick = onLongClick?.let { action ->
                    {
                        poke()
                        action()
                    }
                },
            ) {
                poke()
                taps++
                onChange(!checked)
            }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            supporting?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ToggleIconTile(icon = icon, checked = checked, pressed = pressed, taps = taps)
    }
}

/** The square icon tile that replaces a switch: raised tonal container while off, the
 *  accent container while on, and a crossfading icon in the middle. While the row is held
 *  down the tile squashes and dips its tone, and every click bounces it with a pop. */
@Composable
private fun ToggleIconTile(icon: Int, checked: Boolean, pressed: Boolean, taps: Int) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val resting = (if (checked) colors.primary else colors.secondaryContainer)
    val container by animateColorAsState(
        targetValue = if (pressed) lerp(resting, colors.onSurface, 0.1f) else resting,
        animationSpec = motion.defaultEffectsSpec(),
        label = "toggle tile container",
    )
    val content by animateColorAsState(
        targetValue = if (checked) colors.onPrimary else colors.onSecondaryContainer,
        animationSpec = motion.defaultEffectsSpec(),
        label = "toggle tile content",
    )
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = container,
        contentColor = content,
        modifier = Modifier
            .size(48.dp)
            .pressBounce(pressed, taps),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Crossfade(
                targetState = icon,
                animationSpec = motion.defaultEffectsSpec(),
                label = "toggle tile icon",
            ) { res ->
                Icon(
                    painter = painterResource(res),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/** A slider row: the icon stands in for the label, the percentage keeps the number. */
@Composable
private fun SliderRow(
    icon: Int,
    hint: String,
    value: Float,
    onChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = valueRange,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.wallpapers_percent, (value * 100).roundToInt()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A row that opens a deep-dive screen (the wallpapers) instead of toggling. Same layout
 * as the toggle rows, but the trailing tile is a pair: the icon on its own square tile,
 * tucked just left of the empty right-pointing triangle — the little "menu" the screen
 * unfolds from.
 */
@Composable
private fun ScreenLinkRow(
    title: String,
    supporting: String,
    icon: Int,
    target: Class<*>,
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val poke = rememberHapticPoke()
    // Bumped in the click handler: the pop keys off it, so it fires on every click — the
    // press state alone can go unnoticed on a quick tap.
    var taps by remember { mutableIntStateOf(0) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
            ) {
                poke()
                taps++
                context.startActivity(Intent(context, target))
            }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The pair bounces as one widget when tapped.
        Box(
            modifier = Modifier.pressBounce(pressed, taps),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LinkIconTile(icon = icon, pressed = pressed)
                TriangleTile(pressed = pressed)
            }
        }
    }
}

/**
 * The screen's icon on the standard square tile: raised tonal container, dipping its tone
 * while the row is held down.
 */
@Composable
private fun LinkIconTile(icon: Int, pressed: Boolean) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val resting = colors.secondaryContainer
    val container by animateColorAsState(
        targetValue = if (pressed) lerp(resting, colors.onSurface, 0.1f) else resting,
        animationSpec = motion.defaultEffectsSpec(),
        label = "link tile container",
    )
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = container,
        contentColor = colors.onSecondaryContainer,
        modifier = Modifier.size(48.dp),
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

/**
 * The empty right-pointing triangle: the same tonal raised container as the square tiles,
 * dipping its tone while the row is held down.
 */
@Composable
private fun TriangleTile(pressed: Boolean) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val resting = colors.secondaryContainer
    val container by animateColorAsState(
        targetValue = if (pressed) lerp(resting, colors.onSurface, 0.1f) else resting,
        animationSpec = motion.defaultEffectsSpec(),
        label = "triangle tile container",
    )
    Surface(
        shape = TriangleTilePolygon.toShape(),
        color = container,
        modifier = Modifier.size(48.dp),
    ) {}
}

/**
 * The "Connection" settings page. One screen, three sections: how traffic is routed
 * (everything through the tunnel, or the Russian sites and addresses spared), the URL the
 * real ping measurements fetch through every server, and per-subscription background
 * refresh. Same grouped-list language as the interface page.
 */
@Composable
fun ConnectionScreen(
    connection: slop.miho.aurora.data.ConnectionSettings,
    subscriptions: List<slop.miho.aurora.db.SubscriptionEntity>,
    onChangeConnection: ((slop.miho.aurora.data.ConnectionSettings) -> slop.miho.aurora.data.ConnectionSettings) -> Unit,
    onToggleAutoUpdate: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val contentUnderBar by remember(scrollState) {
        derivedStateOf { scrollState.canScrollForward || scrollState.canScrollBackward }
    }
    val contentReachesBar by rememberContentUnderBar(scrollState)
    ReportBarCoverage(contentUnderBar, underlaid = contentReachesBar)
    Column(
        modifier = modifier
            .fillMaxSize()
            .screenPadding()
            .verticalScroll(scrollState)
            .padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 24.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                    FloatingBottomBarReservedHeight,
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsGroup(
            {
                IconChoiceRow(
                    title = stringResource(R.string.settings_routing),
                    options = listOf(
                        IconChoice(
                            R.drawable.ic_public,
                            R.string.settings_routing_global,
                            slop.miho.aurora.xray.RoutingMode.GLOBAL,
                        ),
                        IconChoice(
                            R.drawable.ic_public_off,
                            R.string.settings_routing_ru,
                            slop.miho.aurora.xray.RoutingMode.RU_BYPASS,
                        ),
                    ),
                    selected = connection.routingMode,
                    onSelect = { mode ->
                        onChangeConnection { it.copy(routingMode = mode) }
                    },
                )
            },
        )
        SettingsGroup(
            {
                // The supporting line under the choice retells the selected mode's story.
                val hint = when (connection.routingMode) {
                    slop.miho.aurora.xray.RoutingMode.GLOBAL -> stringResource(R.string.settings_routing_global_hint)
                    slop.miho.aurora.xray.RoutingMode.RU_BYPASS -> stringResource(R.string.settings_routing_ru_hint)
                }
                PlainRow(
                    title = stringResource(R.string.settings_routing),
                    supporting = hint,
                )
            },
        )
        SettingsGroup(
            {
                TestUrlRow(
                    value = connection.delayTestUrl,
                    onChange = { url -> onChangeConnection { it.copy(delayTestUrl = url) } },
                )
            },
        )
        if (subscriptions.isNotEmpty()) {
            SettingsGroup(
                *subscriptions.map { subscription ->
                    @Composable {
                        ToggleRow(
                            title = subscription.name.ifEmpty { subscription.url },
                            supporting = stringResource(R.string.settings_auto_update_hint),
                            icon = R.drawable.ic_clock,
                            checked = subscription.autoUpdate,
                            onChange = { enabled ->
                                onToggleAutoUpdate(subscription.id, enabled)
                            },
                        )
                    }
                }.toTypedArray(),
            )
        }
    }
}

/** The row that edits the delay test URL; committed on every keystroke, trimmed at the end. */
@Composable
private fun TestUrlRow(
    value: String,
    onChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_test_url),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.settings_test_url_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }
}

/** A plain text row: a title with a supporting line, no icon, no interaction. */
@Composable
private fun PlainRow(
    title: String,
    supporting: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (supporting != null) {
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The "About" page: what Aurora is, and which Xray core build sits under it. The version
 * only exists once the core has booted in its own process; before that, the row says so.
 */
@Composable
fun AboutScreen(
    coreVersion: String?,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val contentUnderBar by remember(scrollState) {
        derivedStateOf { scrollState.canScrollForward || scrollState.canScrollBackward }
    }
    val contentReachesBar by rememberContentUnderBar(scrollState)
    ReportBarCoverage(contentUnderBar, underlaid = contentReachesBar)
    Column(
        modifier = modifier
            .fillMaxSize()
            .screenPadding()
            .verticalScroll(scrollState)
            .padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 24.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                    FloatingBottomBarReservedHeight,
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsGroup(
            {
                PlainRow(
                    title = stringResource(R.string.app_name),
                    supporting = stringResource(R.string.settings_about_hint),
                )
            },
            {
                PlainRow(
                    title = stringResource(R.string.settings_about_core),
                    supporting = coreVersion
                        ?: stringResource(R.string.settings_about_core_unknown),
                )
            },
        )
    }
}
