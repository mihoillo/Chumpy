package slop.miho.aurora.ui.main

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.annotation.DrawableRes
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.roundToInt
import slop.miho.aurora.BuildConfig
import slop.miho.aurora.R
import slop.miho.aurora.data.Accent
import slop.miho.aurora.data.FlagStyle
import slop.miho.aurora.data.IconVariant
import slop.miho.aurora.data.ThemeMode
import slop.miho.aurora.data.UiSettings
import slop.miho.aurora.ui.components.AccentRow
import slop.miho.aurora.ui.components.CountryFlag
import slop.miho.aurora.ui.components.RainbowFlag
import slop.miho.aurora.ui.components.pressBounce
import slop.miho.aurora.ui.components.rememberHapticPoke
import slop.miho.aurora.ui.fonts.FontPickerRow
import slop.miho.aurora.ui.theme.backdropScheme
import slop.miho.aurora.ui.wallpapers.LocalBackdropDark
import slop.miho.aurora.ui.wallpapers.LocalHasWallpaper
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
            // The app icon and the wallpaper share this block: both dress the app itself,
            // the icon its face and the picture its backdrop. The picker sits above.
            SettingsGroup(
                {
                    IconVariantRow(
                        selected = settings.iconVariant,
                        onSelect = { variant -> onChange { it.copy(iconVariant = variant) } },
                    )
                },
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
                        title = stringResource(R.string.settings_simple_transitions),
                        supporting = stringResource(R.string.settings_simple_transitions_hint),
                        // Fade circle for the simple switch, sliding arrows while the
                        // carousel strip is on.
                        icon = if (settings.simpleTransitions) {
                            R.drawable.ic_opacity
                        } else {
                            R.drawable.ic_swap_horizontal
                        },
                        checked = settings.simpleTransitions,
                        onChange = { checked -> onChange { it.copy(simpleTransitions = checked) } },
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
    // A place to dress the launch intent — the trusted networks row opens the per-app
    // screen on its own page, not the apps' one.
    configureIntent: (Intent.() -> Unit)? = null,
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
                val intent = Intent(context, target)
                configureIntent?.invoke(intent)
                context.startActivity(intent)
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
 * The background refresh's doorway, the screen-link row's twin: the count under the
 * title, and the triangle — the per-app row's own — pointing down while the fold is
 * closed, flipping over to point up as it opens.
 */
@Composable
private fun BackgroundRefreshRow(
    supporting: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val poke = rememberHapticPoke()
    // Bumped in the click handler: the pop keys off it, so it fires on every click —
    // the press state alone can go unnoticed on a quick tap.
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
                onToggle()
            }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_background_refresh),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The triangle bounces alone when tapped.
        Box(
            modifier = Modifier.pressBounce(pressed, taps),
        ) {
            FlipTriangleTile(pressed = pressed, expanded = expanded)
        }
    }
}

/**
 * The triangle tile flipping its point: down while the fold is closed, up while it is
 * open — the trip runs through the per-app row's own right-pointing pose, 180 degrees
 * on a spring a touch looser than the scheme's own, so the point swings just past its
 * mark and settles.
 */
@Composable
private fun FlipTriangleTile(pressed: Boolean, expanded: Boolean) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val resting = colors.secondaryContainer
    val container by animateColorAsState(
        targetValue = if (pressed) lerp(resting, colors.onSurface, 0.1f) else resting,
        animationSpec = motion.defaultEffectsSpec(),
        label = "flip triangle container",
    )
    val rotation by animateFloatAsState(
        targetValue = if (expanded) -90f else 90f,
        animationSpec = spring(
            dampingRatio = 0.65f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "flip triangle rotation",
    )
    Surface(
        shape = TriangleTilePolygon.toShape(),
        color = container,
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer { rotationZ = rotation },
    ) {}
}

/**
 * The "Connection" settings page. One screen, four blocks: how traffic is routed — the
 * mode's tile, the country's flag on it like the fonts' tiles carry their own glyphs —
 * the per-app bypass list's doorway, the URL the real ping measurements fetch through
 * every server, and the background refresh's fold holding the per-subscription
 * switches. Same grouped-list language as the interface page.
 */
@Composable
fun ConnectionScreen(
    connection: slop.miho.aurora.data.ConnectionSettings,
    subscriptions: List<slop.miho.aurora.db.SubscriptionEntity>,
    flagStyle: slop.miho.aurora.data.FlagStyle,
    settings: slop.miho.aurora.data.UiSettings,
    onChangeConnection: ((slop.miho.aurora.data.ConnectionSettings) -> slop.miho.aurora.data.ConnectionSettings) -> Unit,
    onChangeSettings: ((slop.miho.aurora.data.UiSettings) -> slop.miho.aurora.data.UiSettings) -> Unit,
    onToggleAutoUpdate: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val contentUnderBar by remember(scrollState) {
        derivedStateOf { scrollState.canScrollForward || scrollState.canScrollBackward }
    }
    val contentReachesBar by rememberContentUnderBar(scrollState)
    ReportBarCoverage(contentUnderBar, underlaid = contentReachesBar)
    // The keyboard counts as the edge while it is up: the delay test URL's field — and
    // the fingerprint's own — stays above the keys, the focused one asking for its
    // visibility again once the keys have risen. The bar's reserved height drops out
    // for the same reason: the bar rides the navigation bars only, so while the keys
    // stand over it there is nothing under them to keep the seat for — but the page
    // still keeps its own margin off the edge, the same 16 it holds everywhere else.
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Column(
        modifier = modifier
            .fillMaxSize()
            .screenPadding()
            .verticalScroll(scrollState)
            .padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 24.dp,
                bottom = WindowInsets.navigationBars.union(WindowInsets.ime)
                    .asPaddingValues().calculateBottomPadding() +
                    if (imeOpen) 16.dp else FloatingBottomBarReservedHeight,
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Routing, the per-app bypass and the trusted networks share one block: all three
        // decide what the tunnel carries — whole countries by their sites and addresses,
        // single apps by name, whole Wi-Fis by trust.
        SettingsGroup(
            {
                RoutingRow(
                    selected = connection.routingMode,
                    flagStyle = flagStyle,
                    onSelect = { mode ->
                        onChangeConnection { it.copy(routingMode = mode) }
                    },
                )
            },
            {
                ScreenLinkRow(
                    title = stringResource(R.string.per_app_title),
                    supporting = when {
                        !connection.perAppEnabled -> stringResource(R.string.per_app_disabled)
                        connection.bypassedApps.isEmpty() -> stringResource(R.string.per_app_hint)
                        else -> stringResource(R.string.per_app_count, connection.bypassedApps.size)
                    },
                    icon = R.drawable.ic_apps,
                    target = slop.miho.aurora.ui.perapp.PerAppProxyActivity::class.java,
                )
            },
            {
                ScreenLinkRow(
                    title = stringResource(R.string.trusted_title),
                    supporting = when {
                        !connection.trustedWifiEnabled -> stringResource(R.string.per_app_disabled)
                        connection.trustedWifiSsids.isEmpty() -> stringResource(R.string.trusted_hint)
                        else -> stringResource(R.string.trusted_count, connection.trustedWifiSsids.size)
                    },
                    icon = R.drawable.ic_wifi,
                    target = slop.miho.aurora.ui.perapp.PerAppProxyActivity::class.java,
                    configureIntent = {
                        putExtra(
                            slop.miho.aurora.ui.perapp.PerAppProxyActivity.EXTRA_PAGE,
                            slop.miho.aurora.ui.perapp.PerAppProxyActivity.PAGE_NETWORKS,
                        )
                    },
                )
            },
        )
        // The Happ spoof's own block, between the carrying and the measuring. The header
        // carries two rows of its own, separated by the fold rows' own 2dp seam: the
        // toggle ("Spoof as Happ", Enabled/Disabled) and the strings' doorway beneath
        // it — "Edit spoof strings" with the background refresh's own triangle — so a
        // working spoof sits compact, two rows tall, and the fingerprint's seven fields
        // ride folded behind the doorway, one row each, editable whatever the toggle
        // says. Enabling the spoof closes the fold: the identity is set, the block goes
        // quiet. The regenerator leads the fold's list, a fresh identity one deliberate
        // tap above the strings it rewrites.
        var happOpen by rememberSaveable { mutableStateOf(false) }
        FoldingGroup(
            expanded = happOpen,
            headerRows = listOf(
                {
                    HappToggleRow(
                        checked = settings.happSpoof,
                        onToggle = { enabled ->
                            onChangeSettings { it.copy(happSpoof = enabled) }
                            if (enabled) happOpen = false
                        },
                    )
                },
                {
                    EditSpoofStringsRow(
                        expanded = happOpen,
                        onToggle = { happOpen = !happOpen },
                    )
                },
            ),
            rows = listOf(
                {
                    HappRegenerateRow(
                        onRegenerate = {
                            onChangeSettings {
                                it.copy(
                                    happUserId = slop.miho.aurora.sub.HappSpoof.randomUserId(),
                                    happHwid = slop.miho.aurora.sub.HappSpoof.randomHwid(),
                                )
                            }
                        },
                    )
                },
                {
                    HappFieldRow(
                        label = stringResource(R.string.happ_app_version),
                        value = settings.happAppVersion,
                        onChange = { value -> onChangeSettings { it.copy(happAppVersion = value) } },
                    )
                },
                {
                    HappFieldRow(
                        label = stringResource(R.string.happ_locale),
                        value = settings.happLocale,
                        onChange = { value -> onChangeSettings { it.copy(happLocale = value) } },
                    )
                },
                {
                    HappFieldRow(
                        label = stringResource(R.string.happ_os),
                        value = settings.happOs,
                        onChange = { value -> onChangeSettings { it.copy(happOs = value) } },
                    )
                },
                {
                    HappFieldRow(
                        label = stringResource(R.string.happ_os_version),
                        value = settings.happOsVersion,
                        onChange = { value -> onChangeSettings { it.copy(happOsVersion = value) } },
                    )
                },
                {
                    HappFieldRow(
                        label = stringResource(R.string.happ_device_model),
                        value = settings.happDeviceModel,
                        onChange = { value -> onChangeSettings { it.copy(happDeviceModel = value) } },
                    )
                },
                {
                    HappFieldRow(
                        label = stringResource(R.string.happ_user_id),
                        value = settings.happUserId,
                        onChange = { value -> onChangeSettings { it.copy(happUserId = value) } },
                    )
                },
                {
                    HappFieldRow(
                        label = stringResource(R.string.happ_hwid),
                        value = settings.happHwid,
                        onChange = { value -> onChangeSettings { it.copy(happHwid = value) } },
                    )
                },
            ),
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
            // The panel's own schedule, one row per subscription behind a fold: the
            // doorway counts how many refresh, the switches riding under it inside the
            // same card — the Happ block's own fold, unrolling with the seam.
            var refreshOpen by rememberSaveable { mutableStateOf(false) }
            val autoCount = subscriptions.count { it.autoUpdate }
            FoldingGroup(
                expanded = refreshOpen,
                headerRows = listOf(
                    {
                        BackgroundRefreshRow(
                            supporting = when {
                                autoCount == 0 -> stringResource(R.string.per_app_disabled)
                                else -> stringResource(
                                    R.string.settings_background_refresh_count,
                                    autoCount,
                                )
                            },
                            expanded = refreshOpen,
                            onToggle = { refreshOpen = !refreshOpen },
                        )
                    }
                ),
                rows = subscriptions.map { subscription ->
                    @Composable {
                        ToggleRow(
                            title = subscription.name.ifEmpty { subscription.url },
                            supporting = null,
                            icon = R.drawable.ic_clock,
                            checked = subscription.autoUpdate,
                            onChange = { enabled ->
                                onToggleAutoUpdate(subscription.id, enabled)
                            },
                        )
                    }
                },
            )
        }
    }
}

/**
 * The routing mode, picked right in its row the way the font is, titleless as the fonts'
 * crowded form. The modes run as one horizontally scrolling strip of tiles: the
 * whole-world mode's block tile first, then the regions — each its own flag (the
 * plain-globe style spells it out in its two letters instead). The selected mode's story
 * runs under the strip: a region tells which one stays out of the tunnel, the
 * whole-world mode routes everything.
 */
@Composable
private fun RoutingRow(
    selected: slop.miho.aurora.xray.RoutingMode,
    flagStyle: slop.miho.aurora.data.FlagStyle,
    onSelect: (slop.miho.aurora.xray.RoutingMode) -> Unit,
) {
    val hint = regionNameRes(selected)?.let { name ->
        stringResource(R.string.settings_routing_dont_route, stringResource(name))
    } ?: stringResource(R.string.settings_routing_global_hint)
    Column(Modifier.fillMaxWidth()) {
        // The scroll runs the card's full width with the padding inside the scrolled
        // content, as in the font picker: the tiles slide under the card's rounding
        // instead of stopping short of it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            slop.miho.aurora.xray.RoutingMode.entries.forEach { mode ->
                key(mode) {
                    val region = mode.region
                    RoutingTile(
                        label = regionNameRes(mode)?.let { name -> stringResource(name) }
                            ?: stringResource(R.string.settings_routing_global),
                        selected = selected == mode,
                        onClick = { onSelect(mode) },
                    ) {
                        if (region == null) {
                            Icon(
                                painter = painterResource(R.drawable.ic_block),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        } else {
                            when (flagStyle) {
                                slop.miho.aurora.data.FlagStyle.TWEMOJI ->
                                    CountryFlag(code = region.uppercase(), twemoji = true)
                                slop.miho.aurora.data.FlagStyle.SYSTEM ->
                                    CountryFlag(code = region.uppercase(), twemoji = false)
                                slop.miho.aurora.data.FlagStyle.GLOBE ->
                                    Text(
                                        text = region.uppercase(),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                            }
                        }
                    }
                }
            }
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )
    }
}

/** The region modes' country names; the whole-world mode has none. */
private fun regionNameRes(mode: slop.miho.aurora.xray.RoutingMode): Int? = when (mode) {
    slop.miho.aurora.xray.RoutingMode.BYPASS_RU -> R.string.region_ru
    slop.miho.aurora.xray.RoutingMode.BYPASS_CN -> R.string.region_cn
    slop.miho.aurora.xray.RoutingMode.BYPASS_IR -> R.string.region_ir
    slop.miho.aurora.xray.RoutingMode.BYPASS_AF -> R.string.region_af
    slop.miho.aurora.xray.RoutingMode.BYPASS_ID -> R.string.region_id
    slop.miho.aurora.xray.RoutingMode.BYPASS_TR -> R.string.region_tr
    slop.miho.aurora.xray.RoutingMode.BYPASS_BR -> R.string.region_br
    slop.miho.aurora.xray.RoutingMode.GLOBAL -> null
}

/**
 * A routing mode's square tile, the font tile's twin: raised tonal container, the accent
 * container while selected, the press squash and the click pop, and the mode's mark in
 * the middle — read out for screen readers, never shown.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RoutingTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val poke = rememberHapticPoke()
    var taps by remember { mutableIntStateOf(0) }
    val resting = if (selected) colors.primary else colors.secondaryContainer
    val container by animateColorAsState(
        targetValue = if (pressed) lerp(resting, colors.onSurface, 0.1f) else resting,
        animationSpec = motion.defaultEffectsSpec(),
        label = "routing tile container",
    )
    val tileContent by animateColorAsState(
        targetValue = if (selected) colors.onPrimary else colors.onSecondaryContainer,
        animationSpec = motion.defaultEffectsSpec(),
        label = "routing tile content",
    )
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = container,
        contentColor = tileContent,
        modifier = Modifier
            .size(48.dp)
            .pressBounce(pressed, taps)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
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
 * The delay test URL's own card: the bare field — filling the card edge to edge, its own
 * inner padding is the card's, so the text sits at the same 16dp as everything else —
 * and the add-font button's narrow twin at the field's end, asleep: a pencil, no press,
 * marking the row for a text field. The description rides under the field, no name.
 * Committed on every keystroke; Done commits the trim.
 */
@Composable
private fun TestUrlRow(
    value: String,
    onChange: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // The tap lands while the bottom edge still sits under the bar — the keyboard rises
    // after, so the field asks for its visibility again once it has: the scroll then
    // hands it the space the keys took.
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(imeBottom, focused) {
        if (focused && imeBottom > 0) requester.bringIntoView()
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 80, not the field's own 56: the stadium gets its 16dp of air above
                // and below to match the sides — the gap reads square, not wider
                // than tall, the add row's cross the same way.
                .heightIn(min = 80.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.settings_test_url)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onChange(value.trim()) }),
                // No outline and no fill of its own: the card is the field's.
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focused = it.isFocused }
                    .bringIntoViewRequester(requester),
            )
            Surface(
                shape = CircleShape,
                color = colors.secondaryContainer,
                contentColor = colors.onSecondaryContainer,
                modifier = Modifier
                    .padding(end = 16.dp)
                    .size(width = 36.dp, height = 48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = null,
                        // The glyph turns inside the backing — the backing stays put.
                        // A pencil resting at its writing angle, tip down.
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer { rotationZ = -45f },
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.settings_test_url_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
}

/**
 * [SettingsGroup]'s body with one fold built in — the header stands, the rows under it
 * unroll and roll back up — because the whole thing is one card, not a doorway group
 * with a second one hanging under it. The seam the fold rides on is the header's own
 * bottom corners: the card's outer ones while the rows are folded, the inner seam once
 * they stand under it, animating on the same clock the rows themselves unroll on.
 */
@Composable
internal fun FoldingGroup(
    expanded: Boolean,
    headerRows: List<@Composable () -> Unit>,
    rows: List<@Composable () -> Unit>,
) {
    val outer = MaterialTheme.shapes.extraLarge
    val inner = MaterialTheme.shapes.extraSmall
    val motion = MaterialTheme.motionScheme
    val density = LocalDensity.current
    // The corner radii come out of the theme's shapes as px against this density, back
    // into Dp — the fold animates that Dp, and a shape rebuilt around a new Dp is a new
    // shape, so the outline follows the animation frame by frame. (Dp corners ignore
    // the shape size they are handed, which is all the theme's shapes are made of.)
    fun CornerSize.dp(): Dp = with(density) { toPx(Size(1f, 1f), this).toDp() }
    val outerTop = outer.topStart.dp()
    val outerBottom = outer.bottomStart.dp()
    val innerTop = inner.topStart.dp()
    val innerBottom = inner.bottomStart.dp()
    // The seam the fold rides on is the header's last row's bottom corners: the card's
    // outer ones while the rows are folded, the inner seam once they stand under it.
    val headerBottom by animateDpAsState(
        targetValue = if (expanded) innerBottom else outerBottom,
        animationSpec = motion.defaultSpatialSpec(),
        label = "happ header bottom corner",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(outer),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // The header's own rows ride as surfaces of their own, separated by the same
        // 2dp seams the fold's rows wear — the first keeps the card's outer top, the
        // last carries the fold's animated seam, the ones between take inner corners
        // all around.
        headerRows.forEachIndexed { index, row ->
            val last = index == headerRows.lastIndex
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer.withSurfaceOpacity(),
                shape = RoundedCornerShape(
                    topStart = if (index == 0) outerTop else innerTop,
                    topEnd = if (index == 0) outerTop else innerTop,
                    bottomStart = if (last) headerBottom else innerBottom,
                    bottomEnd = if (last) headerBottom else innerBottom,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                row()
            }
        }
        // The fold itself: the rows unroll down from the header and roll back up under
        // it, the fade riding strong on top — the background refresh's own trade.
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(motion.defaultSpatialSpec()) +
                fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.defaultSpatialSpec()) +
                fadeOut(motion.defaultEffectsSpec()),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                rows.forEachIndexed { index, row ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer.withSurfaceOpacity(),
                        shape = RoundedCornerShape(
                            topStart = inner.topStart,
                            topEnd = inner.topEnd,
                            bottomStart = if (index == rows.lastIndex) outer.bottomStart else inner.bottomStart,
                            bottomEnd = if (index == rows.lastIndex) outer.bottomEnd else inner.bottomEnd,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        row()
                    }
                }
            }
        }
    }
}

/**
 * The block's header row, plain: the title over the status word, the toggle tile at
 * the end. The tap anywhere toggles the spoof — the strings live under their own fold
 * below, behind the edit row, so the toggle and the editing never get in each other's
 * way.
 */
@Composable
private fun HappToggleRow(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val poke = rememberHapticPoke()
    // Bumped in the click handler: the pop keys off it, so it fires on every click.
    var taps by remember { mutableIntStateOf(0) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
            ) {
                poke()
                taps++
                onToggle(!checked)
            }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.happ_title),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
            )
            // The word the row answers with: what the fetches go out as right now.
            Crossfade(
                targetState = checked,
                animationSpec = motion.defaultEffectsSpec(),
                label = "happ status",
            ) { active ->
                Text(
                    text = stringResource(
                        if (active) R.string.happ_active else R.string.per_app_disabled,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        ToggleIconTile(icon = R.drawable.ic_device, checked = checked, pressed = pressed, taps = taps)
    }
}

/**
 * The strings' doorway, the background refresh row's own twin only one line tall:
 * "Edit spoof strings", and the triangle pointing down while the fold is closed,
 * flipping over to point up as it opens. The tap unrolls the fingerprint's fields —
 * and the regenerator above them — whatever the toggle says.
 */
@Composable
private fun EditSpoofStringsRow(
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val poke = rememberHapticPoke()
    // Bumped in the click handler: the pop keys off it, so it fires on every click —
    // the press state alone can go unnoticed on a quick tap.
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
                onToggle()
            }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.happ_edit),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        // The triangle bounces alone when tapped.
        Box(
            modifier = Modifier.pressBounce(pressed, taps),
        ) {
            FlipTriangleTile(pressed = pressed, expanded = expanded)
        }
    }
}

/**
 * The regenerator, the fold's first row: a fresh identity is a deliberate tap — the
 * toggle's own square tile, now trailing a row of its own over the strings it
 * rewrites. Awake whatever the toggle says: new strings are worth picking before the
 * spoof ever rides.
 */
@Composable
private fun HappRegenerateRow(
    onRegenerate: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val regenSource = remember { MutableInteractionSource() }
    val regenPressed by regenSource.collectIsPressedAsState()
    val poke = rememberHapticPoke()
    // Bumped in the click handler: the pop keys off it, so it fires on every click.
    var regenTaps by remember { mutableIntStateOf(0) }
    // The toggle tile's own resting tone and pressed dip.
    val resting = colors.secondaryContainer
    val container by animateColorAsState(
        targetValue = if (regenPressed) lerp(resting, colors.onSurface, 0.1f) else resting,
        animationSpec = motion.defaultEffectsSpec(),
        label = "regen tile container",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = regenSource,
                indication = LocalIndication.current,
                role = Role.Button,
            ) {
                poke()
                regenTaps++
                onRegenerate()
            }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.happ_regenerate),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        // The tile the header's trailing pair wore, trailing still: the toggle's own
        // square, its renew mark centered.
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = container,
            contentColor = colors.onSecondaryContainer,
            modifier = Modifier
                .size(48.dp)
                .pressBounce(regenPressed, regenTaps),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_renew),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/**
 * One fingerprint field on its own line: a plain row's own two lines — the value where
 * the title goes and the field's name where the supporting goes — only the title line
 * is the editable one, a bare text field with no stadium and no air of its own, so
 * seven of them in a stack read as rows, not as a form. The pencil keeps its place at
 * the end, asleep, marking the row for a text field. Committed on every keystroke;
 * Done commits the trim.
 */
@Composable
private fun HappFieldRow(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // The same ask as the delay test URL's field: the tap lands before the keyboard
    // does, so the field re-asks for its visibility once the keys have risen.
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(imeBottom, focused) {
        if (focused && imeBottom > 0) requester.bringIntoView()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                val trimmedNow = value.trim()
                if (value != trimmedNow) onChange(trimmedNow)
            }),
            decorationBox = { field ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    field()
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            },
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { focused = it.isFocused }
                .bringIntoViewRequester(requester),
        )
        // The pencil, asleep: a pencil, no press, marking the row for a text field.
        Surface(
            shape = CircleShape,
            color = colors.secondaryContainer,
            contentColor = colors.onSecondaryContainer,
            modifier = Modifier
                .size(width = 36.dp, height = 48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_edit),
                    contentDescription = null,
                    // The glyph turns inside the backing — the backing stays put.
                    // A pencil resting at its writing angle, tip down.
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { rotationZ = -45f },
                )
            }
        }
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
 * The "About" page: the welcome screen's own hero turned upright — the app's mark big
 * at the top of the page, its name under it, the tagline under that — then the build's
 * own numbers, and the project's two homes. The core's version only exists once it has
 * booted in its own process; before that, the row says so.
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
    // The hero lies bare on the wallpaper, and the wallpaper owes it nothing: the mark,
    // the name, the tagline recolor by the dimmed picture's own light — too dark behind
    // and the text turns light, too bright and it turns dark, the backdrop scheme's own
    // roles either way, the swap riding the house's color clock. With no picture the
    // theme carries them, as it always has.
    val heroScheme = if (LocalHasWallpaper.current) {
        backdropScheme(darkTheme = LocalBackdropDark.current)
    } else {
        MaterialTheme.colorScheme
    }
    val motion = MaterialTheme.motionScheme
    val markColor by animateColorAsState(
        targetValue = heroScheme.primary,
        animationSpec = motion.defaultEffectsSpec(),
        label = "about mark color",
    )
    val nameColor by animateColorAsState(
        targetValue = heroScheme.onSurface,
        animationSpec = motion.defaultEffectsSpec(),
        label = "about name color",
    )
    val taglineColor by animateColorAsState(
        targetValue = heroScheme.onSurfaceVariant,
        animationSpec = motion.defaultEffectsSpec(),
        label = "about tagline color",
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .screenPadding()
            .verticalScroll(scrollState)
            .padding(
                // The hero's own sky: the mark stands a mark's height clear of the top
                // edge, so the name under it sits well down the page, on its way to the
                // middle rather than hugging the status bar.
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 96.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                    FloatingBottomBarReservedHeight,
            ),
    ) {
        // The hero, the welcome screen's own text stack standing at the top of the page
        // instead of hanging off its bottom: the mark beside the waving hand there, here
        // grown to stand level with its own name below, the one-liner under that.
        Column(Modifier.fillMaxWidth()) {
            Icon(
                painter = painterResource(R.drawable.ic_aurora_mark),
                contentDescription = null,
                tint = markColor,
                modifier = Modifier.size(96.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displayMedium,
                color = nameColor,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.settings_about_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = taglineColor,
            )
        }

        // The greeting keeps its distance from the cards below, as on the welcome.
        Spacer(Modifier.height(24.dp))

        SettingsGroup(
            {
                PlainRow(
                    title = stringResource(R.string.settings_about_version),
                    supporting = BuildConfig.VERSION_NAME,
                )
            },
            {
                PlainRow(
                    title = stringResource(R.string.settings_about_core),
                    supporting = coreVersion
                        ?: stringResource(R.string.settings_about_core_unknown),
                )
            },
            {
                PlainRow(
                    title = stringResource(R.string.settings_about_build_date),
                    supporting = BuildConfig.BUILD_DATE,
                )
            },
        )

        Spacer(Modifier.height(16.dp))

        SettingsGroup(
            {
                ExternalLinkRow(
                    title = stringResource(R.string.settings_about_telegram),
                    supporting = stringResource(R.string.settings_about_telegram_handle),
                    icon = R.drawable.ic_telegram,
                    url = "https://t.me/AuroraFragments",
                )
            },
            {
                ExternalLinkRow(
                    title = stringResource(R.string.settings_about_github),
                    supporting = stringResource(R.string.settings_about_github_handle),
                    icon = R.drawable.ic_github,
                    url = "https://github.com/mihoillo/Aurora",
                )
            },
        )
    }
}

/**
 * A row that leaves the app: the screen-link row's own layout, but the tap opens the
 * browser on [url] instead of one of this app's own screens. The trailing mark is the
 * brand's own sign on the tile, alone — no "menu" triangle: nothing unfolds here, the
 * row simply goes out.
 */
@Composable
private fun ExternalLinkRow(
    title: String,
    supporting: String,
    icon: Int,
    url: String,
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
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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
        // The tile bounces as one widget when tapped.
        Box(
            modifier = Modifier.pressBounce(pressed, taps),
        ) {
            LinkIconTile(icon = icon, pressed = pressed)
        }
    }
}

/**
 * The launcher icon picker: the three variants as the icons themselves — round, the way a
 * launcher draws them, not glyph-in-a-tile like the other pickers. Aurora is the flat sky
 * art on white, Inverted swaps the two, Themed shows the app's own live scheme (the neutral
 * art over the surface, in the accent's primary). Picking one swaps the launcher entry on
 * the spot, through the activity aliases in the manifest.
 */
@Composable
private fun IconVariantRow(
    selected: IconVariant,
    onSelect: (IconVariant) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_app_icon),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // The picked variant's name under the title, like the font and flag rows.
            Text(
                text = stringResource(
                    when (selected) {
                        IconVariant.AURORA -> R.string.settings_icon_aurora
                        IconVariant.INVERTED -> R.string.settings_icon_inverted
                        IconVariant.THEMED -> R.string.settings_icon_themed
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.padding(start = 16.dp),
            // Tighter than the swatches look: every preview's wrapper carries its own
            // margin around the 48dp icon.
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Themed is benched for now — its launcher alias doesn't sit right yet.
            // Everything behind it (the alias, the storage, the QS tile) stays, so it
            // walks back in the day it's fixed.
            IconVariant.entries.filter { it != IconVariant.THEMED }.forEach { variant ->
                IconPreviewButton(
                    variant = variant,
                    selected = variant == selected,
                    onClick = { onSelect(variant) },
                )
            }
        }
    }
}

/**
 * One variant's preview: a 48dp circle carrying the real icon — background color, art
 * color, exactly what the launcher will show — riding centered in a wider wrapper that
 * carries the pick's mark: the accent's ring, grown with a breath of clearance between
 * it and the icon so the two never touch. The art is the monochrome mask (an alpha
 * drawing), tinted per variant, drawn 1.4 times the launcher's own scale — the adaptive
 * icon's safe margins swallow half the circle, and the mark would read as a crumb. The
 * hairline outline keeps the white-on-white and surface-on-surface variants from
 * dissolving into the card.
 */
@Composable
private fun IconPreviewButton(
    variant: IconVariant,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val sky = Color(0xFF6FACED)
    val (background, art) = when (variant) {
        IconVariant.AURORA -> Color.White to sky
        IconVariant.INVERTED -> sky to Color.White
        // The live scheme, as the QS tile and the app itself draw the themed icon.
        IconVariant.THEMED -> colors.surface to colors.primary
    }
    val label = stringResource(
        when (variant) {
            IconVariant.AURORA -> R.string.settings_icon_aurora
            IconVariant.INVERTED -> R.string.settings_icon_inverted
            IconVariant.THEMED -> R.string.settings_icon_themed
        },
    )
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val poke = rememberHapticPoke()
    // Bumped in the click handler: the pop keys off it, so it fires on every click.
    var taps by remember { mutableIntStateOf(0) }
    // The pick's mark: the accent's ring, a breath off the icon — never touching it —
    // while the icon itself yields, shrinking a little under the ring so the marked
    // preview reads no bigger than its neighbors.
    val ringWidth by animateDpAsState(
        targetValue = if (selected) 3.dp else 0.dp,
        animationSpec = motion.defaultSpatialSpec(),
        label = "icon preview ring width",
    )
    val mark by animateFloatAsState(
        targetValue = if (selected) ICON_MARK_SCALE else 1f,
        animationSpec = motion.defaultSpatialSpec(),
        label = "icon preview mark scale",
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
                        // The ring rides off the shrinking icon's own rim, the gap held
                        // at a breath — it closes in as the icon yields, settling level
                        // with the neighbors' edges.
                        radius = 24.dp.toPx() * mark + 3.dp.toPx() + stroke / 2,
                    )
                }
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
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
                .background(background)
                .border(1.dp, colors.outlineVariant, CircleShape),
        ) {
            Image(
                painter = painterResource(
                    when (variant) {
                        IconVariant.AURORA -> R.drawable.ic_launcher_foreground
                        // White art: shown as-is for Inverted, tinted with the live primary
                        // for Themed — the vector is the real launcher art, not a stand-in.
                        IconVariant.INVERTED -> R.drawable.ic_launcher_fg_inverted
                        IconVariant.THEMED -> R.drawable.ic_launcher_fg_inverted
                    },
                ),
                contentDescription = null,
                colorFilter = if (variant == IconVariant.THEMED) ColorFilter.tint(art) else null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.4f),
            )
        }
    }
}

/** How far the icon yields under the pick's ring. */
private const val ICON_MARK_SCALE = 0.85f
