package slop.miho.aurora.ui.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import slop.miho.aurora.R
import slop.miho.aurora.ui.theme.LocalDarkTheme
import slop.miho.aurora.ui.theme.LocalUiSettings
import slop.miho.aurora.ui.theme.backdropScheme
import slop.miho.aurora.ui.wallpapers.LocalBarCanFade
import slop.miho.aurora.ui.wallpapers.LocalBarCoverage
import slop.miho.aurora.ui.wallpapers.LocalBarUnderlay
import slop.miho.aurora.ui.wallpapers.LocalHasWallpaper
import slop.miho.aurora.ui.wallpapers.scrimColor
import slop.miho.aurora.ui.wallpapers.LocalSurfaceOpacity

private val BarHeight = 64.dp
private val BarVerticalPadding = 12.dp
private val ItemHeight = 48.dp

/** The frost's blur reach at full intensity — deep enough to soften the rows passing under. */
private val NavFrostBlur = 24.dp

/**
 * The alpha the bar's surface paint currently wears, provided by [BarSurface] for the pills
 * inside it: the glass's see-throughness while the frost is up, the plain surface opacity
 * while it's down. The pills' selection indicator follows it, so the indicator fades with
 * the glass instead of popping against it. A reader, not a value: it animates, and the
 * indicators call it from their draw blocks.
 */
private val LocalBarTint = staticCompositionLocalOf<() -> Float> { { 1f } }

/**
 * How far the bar has stepped up for content reaching in under it, 0 to 1, provided by
 * [BarSurface] for the pills inside it. A reader too, called only at draw time.
 */
private val LocalBarLift = staticCompositionLocalOf<() -> Float> { { 0f } }

/** The lift's transition: slow enough to read as a shift, not a snap. */
private const val BAR_LIFT_MILLIS = 500

/** How far the selection indicator moves from its container role toward the accent at full lift. */
private const val INDICATOR_LIFT = 0.2f

/** Space screens must keep free at the bottom (on top of navigation bar insets). */
val FloatingBottomBarReservedHeight = BarHeight + BarVerticalPadding * 2

/**
 * The share of the bar's height content must reach in under it before the bar counts as
 * having content behind it: a row's edge just grazing the pill is noise, not something the
 * bar floats over.
 */
private const val BAR_UNDERLAY_SHARE = 0.25f

/**
 * How far content must run on past the bottom padding the bar reserves (the nav inset plus
 * [FloatingBottomBarReservedHeight]) to reach [BAR_UNDERLAY_SHARE] of the way into the pill:
 * through the gap under the bar's top edge, then the share itself.
 */
@Composable
private fun underlayDepth(): Float =
    with(LocalDensity.current) { (BarVerticalPadding + BarHeight * BAR_UNDERLAY_SHARE).toPx() }

/**
 * Whether a full-height scrolling page — bottom padding as [underlayDepth] expects — has
 * content reaching properly in under the bar. Derived, so only the flips recompose.
 */
@Composable
fun rememberContentUnderBar(scroll: ScrollState): State<Boolean> {
    val depth = underlayDepth()
    return remember(scroll, depth) {
        derivedStateOf { scroll.viewportSize > 0 && scroll.maxValue - scroll.value > depth }
    }
}

/** The lazy-list counterpart of the [ScrollState] variant, read off the visible items. */
@Composable
fun rememberContentUnderBar(list: LazyListState): State<Boolean> {
    val depth = underlayDepth()
    return remember(list, depth) {
        derivedStateOf {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            when {
                last == null -> false
                // More rows below the screen: the visible ones run through under the bar.
                last.index < info.totalItemsCount - 1 -> true
                // The final row: how far it runs past where the reserved padding begins.
                else -> last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding) > depth
            }
        }
    }
}

/**
 * Floating pill navigation with a settings button to its right.
 *
 * Opening settings morphs the bar: the tab pill collapses into a round back button while the
 * settings button grows into a pill of settings categories — and back again.
 *
 * [backdrop] is everything the bar floats over — the wallpaper and the screens — recorded by
 * the shell while the frost is on: away from Home the pills re-draw the slice of it that
 * sits behind them, blurred, under a translucent tint, as their glass.
 *
 * With the classic bar picked (see [classicNavigationBar]) the same destinations sit in an
 * edge-to-edge MD3 navigation bar instead, the gear as its last item.
 */
@Composable
fun FloatingBottomBar(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    settingsOpen: Boolean,
    onSettingsOpenChange: (Boolean) -> Unit,
    selectedCategory: SettingsCategory,
    onCategorySelected: (SettingsCategory) -> Unit,
    backdrop: GraphicsLayer?,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    // The classic bar stands in for the pills when picked (or, unpicked, on small screens);
    // flipping the setting crossfades one into the other.
    AnimatedContent(
        targetState = classicNavigationBar(),
        transitionSpec = {
            (fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()))
                .using(null)
        },
        contentAlignment = Alignment.BottomCenter,
        modifier = modifier,
        label = "bar style",
    ) { classic ->
        if (classic) {
            ClassicBar(backdrop = backdrop) {
                // The classic form of the morph: the bar's items scroll over, as the pages
                // do — the settings categories come in from the right, where they sit in the
                // strip, and the tabs return from the left — fading as they go.
                val calm = MaterialTheme.motionScheme
                AnimatedContent(
                    targetState = settingsOpen,
                    transitionSpec = {
                        val direction = if (targetState) 1 else -1
                        (slideInHorizontally(calm.defaultSpatialSpec()) { it * direction } +
                            fadeIn(calm.defaultEffectsSpec()))
                            .togetherWith(
                                slideOutHorizontally(calm.defaultSpatialSpec()) { -it * direction } +
                                    fadeOut(calm.defaultEffectsSpec()),
                            )
                            .using(null)
                    },
                    label = "classic tabs <-> categories",
                ) { open ->
                    ClassicBarRow {
                        if (open) {
                            ClassicBarButton(R.drawable.ic_arrow_back, R.string.nav_back) {
                                onSettingsOpenChange(false)
                            }
                            ClassicBarItems(SettingsCategory.entries, selectedCategory, onCategorySelected)
                        } else {
                            // On Home itself every icon recolors along with the bare text.
                            val colors = if (LocalBarCanFade.current) {
                                homeBarItemColors()
                            } else {
                                NavigationBarItemDefaults.colors()
                            }
                            // Home's item never wears the indicator or its label: it's only
                            // ever active on Home, where the greeting already says where the
                            // user is — and leaving it would only play them collapsing.
                            ClassicBarItems(
                                MainTab.entries,
                                selectedTab,
                                onTabSelected,
                                colors,
                                bare = { it == MainTab.HOME },
                            )
                            ClassicBarButton(R.drawable.ic_settings, R.string.nav_settings, colors) {
                                onSettingsOpenChange(true)
                            }
                        }
                    }
                }
            }
        } else {
            FloatingMainBar(
                selectedTab = selectedTab,
                onTabSelected = onTabSelected,
                settingsOpen = settingsOpen,
                onSettingsOpenChange = onSettingsOpenChange,
                selectedCategory = selectedCategory,
                onCategorySelected = onCategorySelected,
                backdrop = backdrop,
            )
        }
    }
}

/** The pills' side gap to the screen edges: Home's own margins, 32dp, 16dp on small screens. */
@Composable
private fun floatingBarMargin() = if (isSmallScreen()) 16.dp else 32.dp

@Composable
private fun FloatingMainBar(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    settingsOpen: Boolean,
    onSettingsOpenChange: (Boolean) -> Unit,
    selectedCategory: SettingsCategory,
    onCategorySelected: (SettingsCategory) -> Unit,
    backdrop: GraphicsLayer?,
) {
    val motion = MaterialTheme.motionScheme
    Row(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = floatingBarMargin(), vertical = BarVerticalPadding)
            // The bar's whole footprint — the gaps between the pills included — belongs to
            // the bar: a press there is consumed here instead of falling through to the
            // page beneath. The pills still win their own touches: children see the events
            // first, so this only eats what lands on the empty stretches.
            .pointerInput(Unit) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarSurface(backdrop = backdrop) {
            AnimatedContent(
                targetState = settingsOpen,
                transitionSpec = { morph(motion) },
                contentAlignment = Alignment.CenterStart,
                label = "tabs <-> back",
            ) { open ->
                if (open) {
                    RoundBarButton(
                        icon = R.drawable.ic_arrow_back,
                        contentDescription = stringResource(R.string.nav_back),
                        onClick = { onSettingsOpenChange(false) },
                    )
                } else {
                    PillItems(MainTab.entries, selectedTab, onTabSelected)
                }
            }
        }
        BarSurface(backdrop = backdrop) {
            AnimatedContent(
                targetState = settingsOpen,
                transitionSpec = { morph(motion) },
                contentAlignment = Alignment.CenterStart,
                label = "settings <-> categories",
            ) { open ->
                if (open) {
                    PillItems(SettingsCategory.entries, selectedCategory, onCategorySelected)
                } else {
                    // The gear spins away as it unfolds into the category pill.
                    val rotation by transition.animateFloat(label = "gear rotation") { state ->
                        if (state == EnterExitState.Visible) 0f else 120f
                    }
                    RoundBarButton(
                        icon = R.drawable.ic_settings,
                        contentDescription = stringResource(R.string.nav_settings),
                        onClick = { onSettingsOpenChange(true) },
                        iconModifier = Modifier.graphicsLayer { rotationZ = rotation },
                    )
                }
            }
        }
    }
}

/**
 * The sub-screens' floating bar — the fonts and wallpapers screens: the main bar's very
 * pills, frost and tone lift, with a round back button that finishes the screen next to
 * the screen's own destinations, the active one a labeled pill. [backdrop] is what the
 * screen records behind the bar (see [recordBehindBar]), as for [FloatingBottomBar].
 */
@Composable
fun <T : PillDestination> SubScreenBottomBar(
    items: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    onBack: () -> Unit,
    backdrop: GraphicsLayer?,
    modifier: Modifier = Modifier,
    bare: (T) -> Boolean = { false },
) {
    // The classic form, when picked: the back arrow, then the screen's destinations. On a
    // Home-like page its icons recolor with the bare text, as on Home; [bare] items go
    // without the indicator and the label, as Home's item does.
    if (classicNavigationBar()) {
        val colors = if (LocalBarCanFade.current) homeBarItemColors() else NavigationBarItemDefaults.colors()
        ClassicBar(backdrop = backdrop, modifier = modifier) {
            ClassicBarRow {
                ClassicBarButton(R.drawable.ic_arrow_back, R.string.nav_back, colors, onClick = onBack)
                ClassicBarItems(items, selected, onSelect, colors, bare)
            }
        }
        return
    }
    Row(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = floatingBarMargin(), vertical = BarVerticalPadding)
            // The whole footprint belongs to the bar, as on the main one.
            .pointerInput(Unit) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarSurface(backdrop = backdrop) {
            RoundBarButton(
                icon = R.drawable.ic_arrow_back,
                contentDescription = stringResource(R.string.nav_back),
                onClick = onBack,
            )
        }
        BarSurface(backdrop = backdrop) {
            PillItems(items, selected, onSelect)
        }
    }
}

/**
 * The classic MD3 navigation bar's container: edge to edge along the bottom, the system
 * navigation inset included, in the same paint as the pills — tone lift, surface opacity on
 * Home, frost away from it. Flat: no shadow, its tone alone sets it apart.
 *
 * Inside it the motion goes without bounce (see [NoBounceMotionScheme]): on the bar's
 * small icons the springs' overshoot never reads as motion, only as a wobble of the press.
 */
@Composable
private fun ClassicBar(
    backdrop: GraphicsLayer?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val base = MaterialTheme.motionScheme
    val calm = remember(base) { NoBounceMotionScheme(base) }
    BarSurface(backdrop = backdrop, floating = false, modifier = modifier.fillMaxWidth()) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme,
            motionScheme = calm,
            shapes = MaterialTheme.shapes,
            typography = MaterialTheme.typography,
            content = content,
        )
    }
}

/**
 * The app's motion scheme with its spatial springs swapped for the effects ones: those are
 * critically damped, so nothing overshoots, and they keep the scheme's pace — the slow
 * animations setting included.
 */
private class NoBounceMotionScheme(private val base: MotionScheme) : MotionScheme by base {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = base.defaultEffectsSpec()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = base.fastEffectsSpec()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = base.slowEffectsSpec()
}

/**
 * The classic bar's items row: M3's own navigation bar, transparent over [ClassicBar]'s
 * paint, its items kept off the screen edges by the same margins as the floating pills.
 */
@Composable
private fun ClassicBarRow(content: @Composable RowScope.() -> Unit) {
    NavigationBar(
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        modifier = Modifier.padding(horizontal = floatingBarMargin()),
        content = content,
    )
}

/**
 * Destinations as classic bar items. The label shows under the active one only — the
 * screen the user is on is named, the rest are icons, as on the pills. [bare] items never
 * wear the indicator or the label, active or not: their icon stays centered and alone.
 */
@Composable
private fun <T : PillDestination> RowScope.ClassicBarItems(
    items: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    colors: NavigationBarItemColors = NavigationBarItemDefaults.colors(),
    bare: (T) -> Boolean = { false },
) {
    val bareColors = colors.copy(selectedIndicatorColor = Color.Transparent)
    items.forEach { item ->
        val labeled = !bare(item)
        NavigationBarItem(
            selected = item == selected,
            onClick = { onSelect(item) },
            // The label, shown or not, names the item for accessibility; where it's left
            // out, the icon does instead.
            icon = {
                Icon(
                    // The selected item wears the icon's filled cut, as the pill does.
                    painterResource(if (item == selected) item.filledIcon else item.icon),
                    contentDescription = if (labeled) null else stringResource(item.label),
                )
            },
            label = if (labeled) {
                { Text(stringResource(item.label), maxLines = 1) }
            } else {
                null
            },
            alwaysShowLabel = false,
            colors = if (labeled) colors else bareColors,
        )
    }
}

/** An action rather than a destination in the classic bar (the gear, the back arrow): never selected. */
@Composable
private fun RowScope.ClassicBarButton(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    colors: NavigationBarItemColors = NavigationBarItemDefaults.colors(),
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = false,
        onClick = onClick,
        icon = { Icon(painterResource(icon), contentDescription = null) },
        label = { Text(stringResource(label), maxLines = 1) },
        alwaysShowLabel = false,
        colors = colors,
    )
}

/**
 * The classic bar's item colors on Home: no indicator, and the roles Home's bare text wears —
 * the theme's own scheme until the user flips the text, the one opposite to it after (the
 * flip is a tap on the preview's text, and it must change the color whatever the theme).
 * The active item takes the title's primary, the rest the greeting's quieter variant.
 */
@Composable
private fun homeBarItemColors(): NavigationBarItemColors {
    val scheme = backdropScheme(
        darkTheme = LocalUiSettings.current.wallpaperTextDark != LocalDarkTheme.current,
    )
    return NavigationBarItemDefaults.colors(
        selectedIconColor = scheme.primary,
        selectedTextColor = scheme.primary,
        indicatorColor = Color.Transparent,
        unselectedIconColor = scheme.onSurfaceVariant,
        unselectedTextColor = scheme.onSurfaceVariant,
    )
}

/**
 * Records everything this node draws into [layer] while [on], then shows it as is: the
 * screen's backdrop for the bar's frost. Put it on the box holding the picture and the
 * page — never on one holding the bar itself, which would then frost its own reflection.
 */
fun Modifier.recordBehindBar(layer: GraphicsLayer, on: Boolean): Modifier =
    if (on) {
        drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }
    } else {
        this
    }

/** Contents shrink/grow with a spring while the container follows their size. */
private fun AnimatedContentTransitionScope<Boolean>.morph(motion: MotionScheme): ContentTransform =
    (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.6f))
        .togetherWith(fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.6f))
        .using(SizeTransform(clip = true) { _, _ -> motion.defaultSpatialSpec() })

/**
 * The bar's paint, shared by both bar styles: a [floating] pill with its soft shadow, or
 * the classic bar's flat edge-to-edge rectangle.
 */
@Composable
private fun BarSurface(
    backdrop: GraphicsLayer?,
    floating: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val barShape = if (floating) CircleShape else RectangleShape
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val settings = LocalUiSettings.current
    // Home is the one place the bar follows the surface-opacity setting (turning opaque
    // over scrolling rows, like it always has). Everywhere else it owns its own look
    // instead, whatever the setting says: plain and opaque with the frost off, frosted
    // glass with it on.
    val home = LocalBarCanFade.current
    val covered = LocalBarCoverage.current.value
    // The plain container, a tone up while content really reaches in under the bar (see
    // rememberContentUnderBar): the pill then reads apart from the rows it floats over
    // instead of melting into the same surface.
    // One unhurried progress drives both this and the selection indicator's own step (see
    // LocalBarLift), so the two shift together rather than as separate snaps.
    // Every animated value here stays a state and is read only at layer and draw time: the
    // bar is up through every page switch, and reading them in composition recomposed it —
    // pills and all — on each frame of every one.
    val lift = animateFloatAsState(
        targetValue = if (LocalBarUnderlay.current.value) 1f else 0f,
        animationSpec = tween(BAR_LIFT_MILLIS, easing = FastOutSlowInEasing),
        label = "bar lift",
    )
    val containerLow = colors.surfaceContainer
    val containerHigh = colors.surfaceContainerHigh
    val opacity = animateFloatAsState(
        targetValue = if (home) {
            if (covered) 1f else LocalSurfaceOpacity.current
        } else {
            1f
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "bar surface opacity",
    )
    // On Home (and the wallpaper preview, its stand-in) the classic bar lets go of its slab:
    // the paint thins out upward into a gradient, so the bar sits on the picture like the
    // screen's own bottom fade. 0 is the full slab, 1 the gradient.
    val gradient = animateFloatAsState(
        targetValue = if (home && !floating) 1f else 0f,
        animationSpec = motion.defaultEffectsSpec(),
        label = "bar gradient",
    )
    // Over a wallpaper the gradient wears the scrim's own tone, so it reads as the dimming
    // deepening toward the bottom edge rather than as a panel of another color; without
    // one, the plain container.
    val gradientColor = if (LocalHasWallpaper.current) scrimColor(settings.wallpaperDimTone) else null
    // The frost: away from Home, with a picture behind and the setting on, the pill becomes
    // glass — the picture re-drawn inside it, blurred, under a see-through tint. Reaching
    // Home dissolves it: the blur eases to nothing while the glass fades out and the plain
    // rule takes over.
    val frostOn = settings.navigationBarBlur && backdrop != null
    val intensity = settings.navigationBarBlurIntensity
    val frostAlpha = animateFloatAsState(
        targetValue = if (home || !frostOn) 0f else 1f,
        animationSpec = motion.defaultEffectsSpec(),
        label = "bar frost",
    )
    val frostBlur = animateDpAsState(
        targetValue = if (home || !frostOn) 0.dp else NavFrostBlur * intensity,
        animationSpec = motion.defaultSpatialSpec(),
        label = "bar frost blur",
    )
    // The pill's window in the root — where the recorded content lives — tracked so the
    // frost can draw the very slice of it that sits behind the pill. Only the frost's draw
    // reads it, so a move costs that redraw and nothing more.
    var window by remember { mutableStateOf(IntOffset.Zero) }
    // The tint the pills inside should wear: the glass's density while the frost is up, the
    // plain surface opacity while it's down, blended through the frost's own fade so the
    // selection indicator never pops. Handed down as readers, for the pills' draw blocks.
    val tint = remember(intensity, opacity, frostAlpha) {
        {
            val surface = opacity.value
            surface + (intensity - surface) * frostAlpha.value
        }
    }
    val liftReader = remember(lift) { { lift.value } }
    Surface(
        shape = barShape,
        // The container is painted by hand below, behind the content: its tone and opacity
        // animate, and drawn they cost a redraw where a color parameter costs a recomposition.
        color = Color.Transparent,
        contentColor = colors.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 0.dp,
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInRoot()
                window = IntOffset(position.x.roundToInt(), position.y.roundToInt())
            }
            // Floats over scrolling content, so a shadow is warranted here — but a soft one,
            // since the content behind also fades into the bottom gradient. It fades with
            // the surface's opacity too: a see-through bar has nothing solid left to cast one.
            .graphicsLayer {
                val surface = opacity.value
                shadowElevation = if (floating) 3.dp.toPx() else 0f
                shape = barShape
                clip = true
                ambientShadowColor = Color.Black.copy(alpha = 0.06f * surface)
                spotShadowColor = Color.Black.copy(alpha = 0.12f * surface)
            }
            // The plain container, a tone up while content really reaches in under the bar
            // (see rememberContentUnderBar): the pill then reads apart from the rows it floats
            // over instead of melting into the same surface.
            .drawBehind {
                val container = lerp(containerLow, containerHigh, lift.value)
                val bottom = opacity.value
                val fade = gradient.value
                if (fade > 0f) {
                    // The slab's tone slides into the scrim's as it thins out.
                    val tone = gradientColor?.let { lerp(container, it, fade) } ?: container
                    drawRect(
                        Brush.verticalGradient(
                            0f to tone.copy(alpha = bottom * (1f - fade)),
                            1f to tone.copy(alpha = bottom),
                        ),
                    )
                } else {
                    drawRect(container.copy(alpha = bottom))
                }
            },
    ) {
        CompositionLocalProvider(LocalBarTint provides tint, LocalBarLift provides liftReader) {
            Box {
                if (backdrop != null) {
                    Box(
                        Modifier
                            .matchParentSize()
                            // The fade and Modifier.blur's rectangle recipe — clamped edges,
                            // clipped to the bounds — in one layer, set from inside its block.
                            .graphicsLayer {
                                alpha = frostAlpha.value
                                val radius = frostBlur.value.toPx()
                                renderEffect = if (radius > 0f) {
                                    BlurEffect(radius, radius, TileMode.Clamp)
                                } else {
                                    null
                                }
                                clip = true
                            }
                            .drawBehind {
                                // Everything the bar floats over — the picture, the rows,
                                // the cards — re-drawn translated into the pill's own
                                // coordinates: the slice behind the pill lands in it, the
                                // shape shaping the glass.
                                translate(-window.x.toFloat(), -window.y.toFloat()) {
                                    drawLayer(backdrop)
                                }
                            },
                    )
                    // The glass's tint: the scheme's surface, as dense as the intensity — the
                    // higher it goes, the more the bar hides what passes beneath.
                    Box(
                        Modifier
                            .matchParentSize()
                            .drawBehind {
                                val alpha = intensity * frostAlpha.value
                                if (alpha > 0f) {
                                    drawRect(
                                        lerp(containerLow, containerHigh, lift.value).copy(alpha = alpha),
                                    )
                                }
                            },
                    )
                }
                content()
            }
        }
    }
}

@Composable
private fun RoundBarButton(
    icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    iconModifier: Modifier = Modifier,
) {
    // No press ripple: the morph into the other shape is already the button's whole
    // response — a ripple on top of it reads as a stutter.
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(BarHeight)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = iconModifier,
        )
    }
}

@Composable
private fun <T : PillDestination> PillItems(
    items: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .padding((BarHeight - ItemHeight) / 2)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            PillItem(
                icon = item.icon,
                filledIcon = item.filledIcon,
                label = stringResource(item.label),
                selected = item == selected,
                onClick = { onSelect(item) },
            )
        }
    }
}

/** Active: icon + label on a tonal indicator. Inactive: just the icon. */
@Composable
private fun PillItem(
    icon: Int,
    filledIcon: Int = icon,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    // The indicator fades in and out with the selection; its color itself steps toward the
    // accent while the bar is lifted, so it doesn't melt into the raised container.
    // Read at draw time only, with the bar's lift and tint: the indicator's fade is a redraw.
    val selection = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = motion.defaultEffectsSpec(),
        label = "item selection",
    )
    val indicatorLow = colors.secondaryContainer
    val indicatorHigh = colors.secondary
    val lift = LocalBarLift.current
    val tint = LocalBarTint.current
    val content by animateColorAsState(
        targetValue = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        animationSpec = motion.defaultEffectsSpec(),
        label = "item content",
    )
    // The label unfolds with the selection and folds without it. Its window is clipped
    // rather than re-constrained: an animating width constraint would re-measure the text
    // every frame — re-wrapping, re-eliding, flickering for the moment the previous page's
    // label collapsed. Measured once at its full size, the text just slides behind the
    // window's edge.
    val labelReveal by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = motion.defaultSpatialSpec(),
        label = "tab label reveal",
    )
    Row(
        modifier = Modifier
            .height(ItemHeight)
            .clip(CircleShape)
            // The indicator: its alpha is the selection's fade, and the bar's tint rides
            // along so it fades with the glass. Nothing at all is drawn under the inactive
            // icons.
            .drawBehind {
                val alpha = selection.value.coerceIn(0f, 1f) * tint()
                if (alpha > 0f) {
                    drawRect(
                        lerp(indicatorLow, indicatorHigh, INDICATOR_LIFT * lift()).copy(alpha = alpha),
                    )
                }
            }
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = (ItemHeight - 24.dp) / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The selected pill's icon is its filled cut, the inactive one's the outlined —
        // the two crossfading through the selection's own fade. Glyphs whose filled cut
        // is the same shape simply sit still.
        Crossfade(
            targetState = selected,
            animationSpec = motion.fastEffectsSpec(),
            label = "pill icon fill",
        ) { active ->
            Icon(
                painter = painterResource(if (active) filledIcon else icon),
                // When selected the visible label already describes the item.
                contentDescription = if (active) null else label,
                tint = content,
            )
        }
        Box(
            modifier = Modifier
                .graphicsLayer { alpha = labelReveal.coerceIn(0f, 1f) }
                .clipToBounds()
                .layout { measurable, _ ->
                    val placeable = measurable.measure(Constraints())
                    // The spring overshoots past zero on the way out; clamped, the window
                    // would report a negative width for a frame and crash the measure.
                    val width = (placeable.width * labelReveal)
                        .roundToInt()
                        .coerceIn(0, placeable.width)
                    layout(width, placeable.height) { placeable.place(0, 0) }
                },
        ) {
            Text(
                text = label,
                color = content,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp),
            )
        }
    }
}
