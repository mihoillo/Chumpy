package slop.miho.adhdium.ui.wallpapers

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.annotation.DrawableRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import slop.miho.adhdium.R
import slop.miho.adhdium.data.Accent
import slop.miho.adhdium.data.DimTone
import slop.miho.adhdium.data.UiSettings
import slop.miho.adhdium.data.WallpaperPalette
import slop.miho.adhdium.data.WallpaperRegistry
import slop.miho.adhdium.ui.components.AccentRow
import slop.miho.adhdium.ui.components.ErrorCard
import slop.miho.adhdium.ui.components.InfoChip
import slop.miho.adhdium.ui.components.RefreshRateRequest
import slop.miho.adhdium.ui.components.SystemBarsIconsDark
import slop.miho.adhdium.ui.components.pressBounce
import slop.miho.adhdium.ui.components.rememberHapticPoke
import slop.miho.adhdium.ui.main.FloatingBottomBarReservedHeight
import slop.miho.adhdium.ui.main.PillDestination
import slop.miho.adhdium.ui.main.isSmallScreen
import slop.miho.adhdium.ui.main.SubScreenBottomBar
import slop.miho.adhdium.ui.main.recordBehindBar
import slop.miho.adhdium.ui.main.rememberContentUnderBar
import slop.miho.adhdium.ui.main.screenPadding
import slop.miho.adhdium.ui.theme.LocalDarkTheme
import slop.miho.adhdium.ui.theme.backdropScheme
import kotlin.math.roundToInt

/** The screen's two pages, both reachable from the bar. */
private enum class WallpaperPage(override val icon: Int, override val label: Int) : PillDestination {
    PREVIEW(R.drawable.ic_visibility, R.string.wallpapers_preview),
    SETTINGS(R.drawable.ic_tune, R.string.wallpapers_settings),
}

/**
 * Identity of a page switch: a new object per navigation. AnimatedContent keys its content
 * slots by the target's identity, so a page re-entered while its previous copy is still
 * animating out (fast A → B → A) replays the full slide-up instead of crossfading the old
 * copy back in place — the same trick the main shell's pager uses.
 */
private class PageTarget(val page: WallpaperPage)


/**
 * The wallpaper settings, a deep-dive in the fonts screen's footsteps. Two pages under one
 * floating bar: a preview mimicking the home screen (the picker button where the server card
 * sits, the dimming slider where the connect button sits) and a settings page with the scrim
 * tone, the dimming/blur sliders, the page-zoom toggle and the offline grayscale one. The
 * picked wallpaper itself fills the screen behind both pages, scrimmed by the dimming
 * setting.
 */
@Composable
fun WallpaperScreen(
    settings: UiSettings,
    onSettings: ((UiSettings) -> UiSettings) -> Unit,
    onSetWallpaper: (Uri) -> String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(WallpaperPage.PREVIEW) }
    var wallpaperSelected by remember {
        mutableStateOf(WallpaperRegistry.current(context) != null)
    }
    // Bumped after every pick/delete so the decoded bitmap re-reads the file.
    var wallpaperVersion by remember { mutableIntStateOf(0) }
    var importError by remember { mutableStateOf<String?>(null) }
    // The wallpaper's position edit mode: entered by a long press on the preview, left by
    // the confirm button (saving the pan) or the back gesture (discarding it).
    var editPosition by remember { mutableStateOf(false) }
    var editOffsetX by remember { mutableFloatStateOf(0f) }
    var editOffsetY by remember { mutableFloatStateOf(0f) }
    var editScale by remember { mutableFloatStateOf(1f) }
    BackHandler(enabled = editPosition) { editPosition = false }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            importError = onSetWallpaper(uri)
            wallpaperSelected = WallpaperRegistry.current(context) != null
            wallpaperVersion++
            // The accent seeds follow the picture: recompute right away.
            WallpaperPalette.refresh(context)
            // A fresh picture lands on its own look: its first seed as the accent, a
            // gentle dim on Home, a stronger dim with a touch of blur everywhere else,
            // and the menu surfaces a touch see-through. The pan resets to centered and
            // the text's recolor decision starts over.
            if (importError == null) {
                onSettings {
                    it.copy(
                        accent = Accent.WALLPAPER_1,
                        wallpaperDim = 0.10f,
                        wallpaperDimScreens = 0.60f,
                        wallpaperBlur = 0.30f,
                        wallpaperTextDark = false,
                        wallpaperOffsetX = 0f,
                        wallpaperOffsetY = 0f,
                        wallpaperUserScale = 1f,
                        surfaceOpacity = 0.85f,
                    )
                }
            }
        }
    }
    // Decoding happens off the main thread; a camera shot can be tens of megapixels.
    val wallpaper by produceState<DecodedWallpaper?>(null, wallpaperVersion) {
        val file = WallpaperRegistry.current(context)
        value = withContext(Dispatchers.IO) { file?.let(::decodeWallpaper) }
    }

    // The gesture that owns the position edit: a long press opens it and the very same
    // fingers then drag — a continuation, not a new gesture. It lives on the root so it sees
    // the whole stream, from the press that opens the mode to the lift that ends the drag.
    val poke = rememberHapticPoke()
    // The gesture lambda outlives recompositions; the settings it reads on entry must stay
    // current, not the copy from whenever it was composed.
    val liveSettings by rememberUpdatedState(settings)
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .pointerInput(wallpaperSelected, page) {
                awaitEachGesture {
                    // The clickables beneath — the greeting, the picker, the edit layer's own
                    // tap-swallow Box — claim their downs in the Main pass, which children run
                    // before this ancestor, so an unconsumed down would never arrive here and
                    // the gesture would go deaf over half the page. The down is taken as it
                    // lies; nothing beneath is touched until the drag itself starts consuming.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!editPosition) {
                        // Only the preview page over a picked picture opens the edit.
                        if (page != WallpaperPage.PREVIEW || !wallpaperSelected) {
                            return@awaitEachGesture
                        }
                        // A lift, a drift past the touch slop or another handler claiming the
                        // events hands the gesture back to the page beneath.
                        if (!awaitLongPress(down)) return@awaitEachGesture
                        poke()
                        // The edit starts from the saved pan and zoom: moving from where the
                        // picture already sits, not from the center.
                        editOffsetX = liveSettings.wallpaperOffsetX
                        editOffsetY = liveSettings.wallpaperOffsetY
                        editScale = liveSettings.wallpaperUserScale
                        editPosition = true
                    }
                    // The fingers that pressed now drag: the pan and pinch ride until the
                    // last finger lifts. A fresh touch during the edit lands here too — the
                    // mode is open, every gesture is a drag.
                    wallpaper?.let { decoded ->
                        val image = decoded.image
                        val cover = maxOf(
                            size.width.toFloat() / image.width,
                            size.height.toFloat() / image.height,
                        )
                        val contain = minOf(
                            size.width.toFloat() / image.width,
                            size.height.toFloat() / image.height,
                        )
                        // The floor is the whole picture just visible — zooming out further
                        // would only shrink it inside its letterbox. The ceiling is four
                        // times the cover.
                        val floor = contain / cover
                        // The drag claims the gesture once the fingers drift past the slop or
                        // a second one joins; until then the touch may still be a plain tap.
                        // The drift is the travel summed since the press, not one event's
                        // step: a slow, careful drag moves a pixel or two per frame and would
                        // otherwise never clear the slop — the touch simply went unheard.
                        var dragging = false
                        var drift = Offset.Zero
                        while (true) {
                            // Read ahead of the page beneath (the Initial pass runs parent
                            // first): the drag owns the stream before a scroll or the
                            // tap-swallow layer can take a share of it.
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.none { it.pressed }) break
                            val pan = event.calculatePan()
                            val zoom = event.calculateZoom()
                            if (!dragging) {
                                if (!pan.x.isNaN() && !pan.y.isNaN()) drift += pan
                                if (drift.getDistance() > viewConfiguration.touchSlop ||
                                    event.changes.count { it.pressed } > 1
                                ) {
                                    dragging = true
                                }
                            }
                            if (dragging && !pan.x.isNaN() && !pan.y.isNaN() && !zoom.isNaN()) {
                                // The slack grows with the live zoom, so the same finger
                                // travel slides further the closer the picture sits to its
                                // natural cover. Below the cover the slack runs dry — the
                                // picture is centered, not panned.
                                editScale = (editScale * zoom).coerceIn(floor, MAX_WALLPAPER_ZOOM)
                                val slackX = (image.width * cover * editScale - size.width) / 2f
                                val slackY = (image.height * cover * editScale - size.height) / 2f
                                // Slack-free axes stay put: nowhere to slide. The pan feeds
                                // the normalized offsets, clamped to the slack.
                                if (slackX > 0f) {
                                    editOffsetX =
                                        (editOffsetX + pan.x / slackX).coerceIn(-1f, 1f)
                                }
                                if (slackY > 0f) {
                                    editOffsetY =
                                        (editOffsetY + pan.y / slackY).coerceIn(-1f, 1f)
                                }
                                // The events are the drag's own: nothing beneath — the
                                // fading chrome, the scrolling page — answers them.
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            },
    ) {
        // The menus' surfaces wear this over the wallpaper: the setting while a picture is
        // picked, fully opaque without one — transparency without a backdrop is just noise.
        // Provided here so every card, tile and bar below reads it without threading.
        val surfaceOpacity = if (wallpaperSelected) settings.surfaceOpacity else 1f
        // The pages report into this whether their content sits under the floating bar;
        // the bar reads it and turns opaque over whatever scrolls beneath it. The bar fades
        // with the setting only on the preview — Home's stand-in; on the settings page it
        // stays a step more opaque, like the main shell's away-from-Home rule.
        val barCoverage = remember { mutableStateOf(false) }
        // The stricter report: content really reaching in under the bar, for its tone step.
        val barUnderlay = remember { mutableStateOf(false) }
        // The picture and the pages are recorded for the bar's frost, as in the main shell.
        val frost = settings.navigationBarBlur
        val behindBar = rememberGraphicsLayer()
        CompositionLocalProvider(
            LocalSurfaceOpacity provides surfaceOpacity,
            LocalBarCoverage provides barCoverage,
            LocalBarUnderlay provides barUnderlay,
            LocalBarCanFade provides (page == WallpaperPage.PREVIEW),
            // For the classic bar's gradient, which takes the scrim's tone over a picture.
            LocalHasWallpaper provides wallpaperSelected,
        ) {
            val motion = MaterialTheme.motionScheme

            // The wallpaper layer, shared by both pages so it stays put under the switch: it
            // zooms (and blurs) in place when the user leaves the preview — Home's stand-in —
            // and settles back on return. The dim rides on the wallpaper itself: Home's
            // strength under the preview, the away-from-Home strength elsewhere.
            val zoomed = page != WallpaperPage.PREVIEW && settings.wallpaperPageZoom
            // The user's own pinch-zoom rides under the page zoom: the base look carries it
            // alone, the away-from-preview look multiplies it in.
            val zoomTarget = wallpaperZoomTarget(settings.wallpaperBlur) * settings.wallpaperUserScale
            val scale by animateFloatAsState(
                targetValue = if (zoomed) zoomTarget else settings.wallpaperUserScale,
                animationSpec = motion.defaultSpatialSpec(),
                label = "wallpaper zoom",
            )
            // The animated values are only ever read inside the backdrop's lambdas, at layer
            // and draw time, so their frames redraw the picture without recomposing the
            // screen.
            val blur by animateDpAsState(
                targetValue = if (page == WallpaperPage.PREVIEW) {
                    0.dp
                } else {
                    MAX_WALLPAPER_BLUR * settings.wallpaperBlur
                },
                animationSpec = motion.defaultSpatialSpec(),
                label = "wallpaper blur",
            )
            val scrim = scrimColor(settings.wallpaperDimTone)
            val scrimAlpha by animateFloatAsState(
                targetValue = when {
                    editPosition -> 0f
                    page == WallpaperPage.PREVIEW -> settings.wallpaperDim
                    else -> settings.wallpaperDimScreens
                },
                animationSpec = motion.defaultEffectsSpec(),
                label = "wallpaper dim",
            )
            // The settings page's section headers lie bare on the dimmed, blurred picture: they
            // recolor by the same too-dark test as the preview's text.
            val settingsBackdropDark = wallpaper?.let { decoded ->
                backdropTooDark(decoded.luminance, settings.wallpaperDimScreens, scrim)
            } ?: false
            // The system bars hang on the picture here, not on the shell: their icons flip by
            // the same too-dark test, against the dim the page actually wears — the preview's
            // gentle one, the settings page's stronger one, none at all in the position edit.
            // Without a picture they answer to the theme alone.
            val barsDark = wallpaper?.let { decoded ->
                val barsDim = when {
                    editPosition -> 0f
                    page == WallpaperPage.PREVIEW -> settings.wallpaperDim
                    else -> settings.wallpaperDimScreens
                }
                backdropTooDark(decoded.luminance, barsDim, scrim)
            } ?: false
            SystemBarsIconsDark(dark = LocalDarkTheme.current || barsDark)

            // The standard page switch, the main screens' direct swap: the new page slides up
            // a third of the screen while the old one fades out. Fresh identity per switch, so
            // a quick A → B → A replays the slide instead of crossfading the old copy back.
            // The chrome — both pages, the edge fades and the bar — dissolves for the
            // position edit: only the bare picture and the confirm button answer.
            val chromeAlpha by animateFloatAsState(
                targetValue = if (editPosition) 0f else 1f,
                animationSpec = motion.defaultEffectsSpec(),
                label = "wallpaper chrome",
            )
            val target = remember(page) { PageTarget(page) }
            // Everything the bar floats over — the picture and the pages — is what its frost
            // blurs; the bar itself and the edit layer stay out of the recording.
            Box(Modifier.fillMaxSize().recordBehindBar(behindBar, frost)) {
                val desaturate = offlineDesaturation()
                wallpaper?.let { decoded ->
                    WallpaperBackdrop(
                        image = decoded.image,
                        // Pinching must track the fingers directly — no spring between them
                        // and the picture — so the edit mode reads its own scale, unanimated.
                        scale = { if (editPosition) editScale else scale },
                        blur = { blur },
                        scrim = scrim,
                        scrimAlpha = { scrimAlpha },
                        desaturate = { desaturate.value },
                        offsetX = { if (editPosition) editOffsetX else settings.wallpaperOffsetX },
                        offsetY = { if (editPosition) editOffsetY else settings.wallpaperOffsetY },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                AnimatedContent(
                    targetState = target,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = chromeAlpha },
                    transitionSpec = {
                        (fadeIn(motion.defaultEffectsSpec()) +
                            slideInVertically(motion.defaultSpatialSpec()) { it / 3 }) togetherWith
                            fadeOut(motion.defaultEffectsSpec())
                    },
                    label = "wallpaper page switch",
                ) { switch ->
                    // The same request the main pager makes: the display's high gear while the
                    // switch itself plays out.
                    RefreshRateRequest(active = transition.isRunning)
                    when (switch.page) {
                        WallpaperPage.PREVIEW -> WallpaperPreviewPage(
                            wallpaperSelected = wallpaperSelected,
                            importError = importError,
                            dim = settings.wallpaperDim,
                            textDark = settings.wallpaperTextDark,
                            onToggleText = {
                                onSettings { it.copy(wallpaperTextDark = !it.wallpaperTextDark) }
                            },
                            onDim = { value -> onSettings { it.copy(wallpaperDim = value) } },
                            onPick = {
                                picker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            onDelete = {
                                WallpaperRegistry.delete(context)
                                wallpaperSelected = false
                                wallpaperVersion++
                                // No picture, no seeds: the picker's slots empty out.
                                WallpaperPalette.refresh(context)
                                // And nothing to see through: the menus' surfaces reset to opaque —
                                // and the bare text returns to the theme's own colors.
                                onSettings {
                                    it.copy(
                                        wallpaperTextDark = false,
                                        wallpaperOffsetX = 0f,
                                        wallpaperOffsetY = 0f,
                                        wallpaperUserScale = 1f,
                                        surfaceOpacity = 1f,
                                    )
                                }
                            },
                        )
                        WallpaperPage.SETTINGS -> WallpaperSettingsPage(
                            settings = settings,
                            onChange = onSettings,
                            backdropDark = settingsBackdropDark,
                            wallpaperSelected = wallpaperSelected,
                        )
                    }
                }
            }

            // Content scrolls under the system bars. Without a picture it fades out behind
            // these gradients, the main shell's plain-shell recipe; over one the photo runs
            // clean out to the very bars — no fade ever sits on the picture itself.
            if (wallpaper == null) {
                AnimatedVisibility(
                    visible = page == WallpaperPage.SETTINGS,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = chromeAlpha },
                    enter = fadeIn(motion.defaultEffectsSpec()),
                    exit = fadeOut(motion.fastEffectsSpec()),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        val surface = MaterialTheme.colorScheme.surface
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp)
                                .drawBehind {
                                    drawRect(
                                        Brush.verticalGradient(
                                            listOf(surface.copy(alpha = 0.97f), surface.copy(alpha = 0f)),
                                        ),
                                    )
                                },
                        )
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(
                                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                        FloatingBottomBarReservedHeight + 8.dp,
                                )
                                .drawBehind {
                                    drawRect(
                                        Brush.verticalGradient(
                                            listOf(surface.copy(alpha = 0f), surface.copy(alpha = 0.97f)),
                                        ),
                                    )
                                },
                        )
                    }
                }
            }

            SubScreenBottomBar(
                items = WallpaperPage.entries,
                selected = page,
                onSelect = { page = it },
                onBack = onBack,
                // The preview is Home's stand-in: in the classic bar its item behaves as
                // Home's does — never an indicator or a label.
                bare = { it == WallpaperPage.PREVIEW },
                backdrop = if (frost) behindBar else null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { alpha = chromeAlpha },
            )

            // The edit mode's own layer: a surface over the bare picture swallowing taps — the
            // vanished buttons beneath must not answer a press aimed at the wallpaper — with
            // the confirm button on top. The drag itself is the root's gesture: it began with
            // the long press and rides the very same fingers.
            AnimatedVisibility(
                visible = editPosition,
                enter = fadeIn(motion.defaultEffectsSpec()),
                exit = fadeOut(motion.fastEffectsSpec()),
            ) {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { }
                    )
                    ConfirmPositionButton(
                        onClick = {
                            onSettings {
                                it.copy(
                                    wallpaperOffsetX = editOffsetX,
                                    wallpaperOffsetY = editOffsetY,
                                    wallpaperUserScale = editScale,
                                )
                            }
                            editPosition = false
                        },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
    
        }
    }
}

/**
 * Waits out the long-press timeout: true when the press held, false when the finger lifted,
 * drifted past the touch slop or another handler claimed the events — the gesture then
 * belongs to whatever sits beneath, a scroll or a button.
 */
private suspend fun AwaitPointerEventScope.awaitLongPress(down: PointerInputChange): Boolean {
    val slop = viewConfiguration.touchSlop
    // The block returns true when the press is abandoned; null — the timeout's own result —
    // means it held long enough.
    val abandoned = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        var holding = true
        while (holding) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
            val lifted = event.changes.none { it.pressed }
            // Measured from the press, not per event: a slow creep adds up just the same.
            val drifted = change != null && (
                change.isConsumed || (change.position - down.position).getDistance() > slop
                )
            holding = !lifted && !drifted
        }
        true
    }
    return abandoned == null
}

/**
 * The preview: the home screen's silhouette — the greeting answers whether a wallpaper is
 * picked, the picker button sits where the server card does, and the sun-pill slider takes
 * the connect button's spot, tuning Home's scrim live.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WallpaperPreviewPage(
    wallpaperSelected: Boolean,
    importError: String?,
    dim: Float,
    textDark: Boolean,
    onToggleText: () -> Unit,
    onDim: (Float) -> Unit,
    onPick: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // Text lying bare on the background — the title and the greeting, the cards are exempt —
    // recolors into the backdrop scheme's own roles when the user flips it by tapping the text
    // right here: no automatic too-dark guess, the choice is theirs. The flipped look is the
    // scheme opposite to the theme the app is in, so the tap always visibly changes the color
    // — the dark scheme outright would be a no-op in a dark app. The flip itself works only
    // on this page — the same text on the home screen just reads.
    val textScheme = backdropScheme(
        darkTheme = textDark != LocalDarkTheme.current,
    )
    val motion = MaterialTheme.motionScheme
    val titleColor by animateColorAsState(
        targetValue = textScheme.primary,
        animationSpec = motion.defaultEffectsSpec(),
        label = "preview title color",
    )
    val greetingColor by animateColorAsState(
        targetValue = textScheme.onSurface,
        animationSpec = motion.defaultEffectsSpec(),
        label = "preview greeting color",
    )
    val poke = rememberHapticPoke()
    // The tap flips the text's color: quiet, no ripple — the color swap is the whole answer.
    val textInteraction = remember { MutableInteractionSource() }
    val textModifier = if (wallpaperSelected) {
        Modifier.clickable(
            interactionSource = textInteraction,
            indication = null,
            role = Role.Button,
            onClick = {
                poke()
                onToggleText()
            },
        )
    } else {
        Modifier
    }
    // Bottom-anchored like the home screen; scrolls up when content outgrows the screen.
    // A long press anywhere on the preview opens the position edit — the wallpaper is the
    // preview, so the whole page is its handle (the gesture itself lives on the root).
    val scroll = rememberScrollState()
    // Content sits under the floating bar exactly while the page outgrows the screen;
    // derived so only the flip recomposes, not every scroll pixel.
    val contentUnderBar by remember {
        derivedStateOf { scroll.canScrollForward || scroll.canScrollBackward }
    }
    // Home's stand-in: the column scrolls above the bar, never in under it, so the bar
    // keeps its own tone.
    ReportBarCoverage(contentUnderBar, underlaid = false)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            // Home's own margins, tightening on small screens as Home's do.
            .padding(horizontal = if (isSmallScreen()) 16.dp else 32.dp),
    ) {
            Box(Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .verticalScroll(scroll),
                ) {
                    Text(
                        text = stringResource(R.string.wallpapers_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = titleColor,
                        modifier = Modifier.then(textModifier),
                    )
                    Text(
                        text = stringResource(
                            if (wallpaperSelected) R.string.wallpapers_greeting
                            else R.string.wallpapers_greeting_empty,
                        ),
                        style = MaterialTheme.typography.displayMedium,
                        color = greetingColor,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .then(textModifier),
                    )
                    // The page's two bare gestures, spelled out in the same value-chip
                    // language the home screen speaks. Both only concern a placed picture —
                    // nothing to move or recolor without one.
                    if (wallpaperSelected) {
                        Spacer(Modifier.height(16.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            InfoChip(
                                value = stringResource(R.string.wallpapers_move_hint),
                                icon = R.drawable.ic_touch_app,
                            )
                            InfoChip(
                                value = stringResource(R.string.wallpapers_text_hint),
                                icon = R.drawable.ic_contrast,
                            )
                        }
                    }
                    importError?.let { message ->
                        ErrorCard(
                            message = message,
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth(),
                        )
                    }
                    // The picker, with the red remove square sliding in beside it once a
                    // wallpaper exists — the picker yields the width as it appears, and the
                    // square rides at the picker card's own height.
                    Row(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PickWallpaperButton(
                            selected = wallpaperSelected,
                            onClick = onPick,
                            modifier = Modifier.weight(1f),
                        )
                        val motion = MaterialTheme.motionScheme
                        AnimatedVisibility(
                            visible = wallpaperSelected,
                            enter = expandHorizontally(motion.defaultSpatialSpec()) +
                                fadeIn(motion.defaultEffectsSpec()),
                            exit = shrinkHorizontally(motion.fastSpatialSpec()) +
                                fadeOut(motion.fastEffectsSpec()),
                        ) {
                            DeleteWallpaperButton(
                                onClick = onDelete,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .aspectRatio(1f),
                            )
                        }
                    }
                    // Keeps the bottom-anchored content clear of the dimming pill below.
                    Spacer(Modifier.height(16.dp))
                }
            }

            // The connect button's slot: the same accent pill, only quiet — not clickable,
            // and a slider rides in it instead of the wavy progress bar. The sun stands in
            // for the label; the percentage keeps the exact number readable.
            Surface(
                shape = CircleShape,
                color = colors.primary.copy(alpha = LocalSurfaceOpacity.current),
                contentColor = colors.onPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = FloatingBottomBarReservedHeight + 8.dp)
                    .height(64.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_light_mode),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Slider(
                        value = dim,
                        onValueChange = onDim,
                        valueRange = 0f..1f,
                        // No picture, nothing to dim: the slider rests until one is picked.
                        enabled = wallpaperSelected,
                        // The wavy bar's palette: the accent fill under, on-accent strokes
                        // over, the track at the same 30% the connect bar's track runs.
                        colors = SliderDefaults.colors(
                            thumbColor = colors.onPrimary,
                            activeTrackColor = colors.onPrimary,
                            inactiveTrackColor = colors.onPrimary.copy(alpha = 0.30f),
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(
                            R.string.wallpapers_percent,
                            (dim * 100).roundToInt(),
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }

/** The wallpaper picker, in the fonts screen's add-a-font shape — pick, or re-pick. */
@Composable
private fun PickWallpaperButton(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val poke = rememberHapticPoke()
    var taps by remember { mutableIntStateOf(0) }
    val tileContainer by animateColorAsState(
        targetValue = if (pressed) {
            lerp(colors.secondaryContainer, colors.onSurface, 0.1f)
        } else {
            colors.secondaryContainer
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "pick tile container",
    )
    Surface(
        onClick = {
            poke()
            taps++
            onClick()
        },
        interactionSource = interactionSource,
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainer.copy(alpha = LocalSurfaceOpacity.current),
        contentColor = colors.onSurface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = tileContainer,
                contentColor = colors.onSecondaryContainer,
                modifier = Modifier
                    .size(48.dp)
                    .pressBounce(pressed, taps),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (selected) R.string.wallpapers_pick_change
                        else R.string.wallpapers_pick,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

/**
 * The remove button: a plain red square beside the picker, riding at the picker card's
 * height (the row measures it intrinsically), so the two read as one strip. Same press
 * treatment as every other tile.
 */
@Composable
private fun DeleteWallpaperButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val poke = rememberHapticPoke()
    var taps by remember { mutableIntStateOf(0) }
    val resting = colors.errorContainer
    val container by animateColorAsState(
        targetValue = if (pressed) lerp(resting, colors.onErrorContainer, 0.1f) else resting,
        animationSpec = motion.defaultEffectsSpec(),
        label = "delete tile container",
    )
    Surface(
        onClick = {
            poke()
            taps++
            onClick()
        },
        interactionSource = interactionSource,
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        contentColor = colors.onErrorContainer,
        modifier = modifier
            .pressBounce(pressed, taps),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = stringResource(R.string.wallpapers_delete),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * The edit mode's way out: a translucent round button with a check, pinned to the bottom.
 * Translucent on purpose — it sits on the very picture being positioned, so it stays out of
 * the way. A tap saves the pan; the back gesture instead discards it.
 */
@Composable
private fun ConfirmPositionButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val poke = rememberHapticPoke()
    var taps by remember { mutableIntStateOf(0) }
    Surface(
        onClick = {
            poke()
            taps++
            onClick()
        },
        interactionSource = interactionSource,
        shape = CircleShape,
        color = colors.surfaceContainerHighest.copy(alpha = 0.75f),
        contentColor = colors.onSurface,
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = BarVerticalPadding)
            .size(64.dp)
            .pressBounce(pressed, taps),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = stringResource(R.string.wallpapers_done),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * The settings page, in the settings screen's language: sections with a small icon + label
 * heading grouped blocks. The Wallpaper section holds the picture's own treatment — the
 * scrim tone, the dimming and blur sliders, the page-zoom toggle and the offline grayscale
 * mode; the Interface section holds the accent picker with the wallpaper's seed slots kept
 * as empty circles until a picture is picked, and how see-through the menus' surfaces are
 * over the picture. The wallpaper layer beneath already wears the away-from-Home treatment,
 * so the sliders tune what sits under them.
 */
@Composable
private fun WallpaperSettingsPage(
    settings: UiSettings,
    onChange: ((UiSettings) -> UiSettings) -> Unit,
    backdropDark: Boolean,
    wallpaperSelected: Boolean,
) {
    // Bare headers over the dimmed picture recolor the way the preview's text does.
    val headerColor = if (backdropDark) {
        backdropScheme(darkTheme = true).onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val scroll = rememberScrollState()
    // The groups sit under the floating bar while the page outgrows the screen; derived so
    // only the flip recomposes, not every scroll pixel.
    val contentUnderBar by remember {
        derivedStateOf { scroll.canScrollForward || scroll.canScrollBackward }
    }
    val groupsReachBar by rememberContentUnderBar(scroll)
    ReportBarCoverage(contentUnderBar, underlaid = groupsReachBar)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .screenPadding()
            .verticalScroll(scroll)
            .padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 24.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                    FloatingBottomBarReservedHeight,
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        WallpaperSection(
            title = stringResource(R.string.settings_wallpapers),
            icon = R.drawable.ic_wallpaper,
            labelColor = headerColor,
        ) {
            // Nothing to tune without a picture: the whole Wallpaper section rests until
            // one is picked (the picker itself lives on the preview page).
            WallpaperGroup(
                {
                    DimToneRow(
                        selected = settings.wallpaperDimTone,
                        onSelect = { tone -> onChange { it.copy(wallpaperDimTone = tone) } },
                        enabled = wallpaperSelected,
                    )
                },
                {
                    SliderRow(
                        icon = R.drawable.ic_light_mode,
                        // The tone buttons right above already say what this dims.
                        hint = null,
                        value = settings.wallpaperDimScreens,
                        onChange = { value -> onChange { it.copy(wallpaperDimScreens = value) } },
                        enabled = wallpaperSelected,
                    )
                },
                {
                    SliderRow(
                        icon = R.drawable.ic_blur_on,
                        hint = stringResource(R.string.wallpapers_blur_hint),
                        value = settings.wallpaperBlur,
                        onChange = { value -> onChange { it.copy(wallpaperBlur = value) } },
                        enabled = wallpaperSelected,
                    )
                },
            )
            WallpaperGroup(
                {
                    ToggleTileRow(
                        title = stringResource(R.string.wallpapers_zoom),
                        hint = stringResource(R.string.wallpapers_zoom_hint),
                        icon = R.drawable.ic_zoom_in,
                        checked = settings.wallpaperPageZoom,
                        onChange = { checked -> onChange { it.copy(wallpaperPageZoom = checked) } },
                        enabled = wallpaperSelected,
                    )
                },
                {
                    ToggleTileRow(
                        title = stringResource(R.string.wallpapers_grayscale),
                        hint = stringResource(R.string.wallpapers_grayscale_hint),
                        icon = R.drawable.ic_contrast,
                        checked = settings.grayscaleOffline,
                        onChange = { checked -> onChange { it.copy(grayscaleOffline = checked) } },
                        enabled = wallpaperSelected,
                    )
                },
            )
        }
        WallpaperSection(
            title = stringResource(R.string.settings_interface),
            icon = R.drawable.ic_appearance,
            labelColor = headerColor,
        ) {
            WallpaperGroup(
                {
                    AccentRow(
                        selected = settings.accent,
                        onSelect = { accent -> onChange { it.copy(accent = accent) } },
                        monetPlaceholders = true,
                    )
                },
                {
                    SliderRow(
                        icon = R.drawable.ic_opacity,
                        hint = stringResource(R.string.wallpapers_opacity_hint),
                        value = settings.surfaceOpacity,
                        // Nothing to see through without a picture: the slider rests until
                        // one is picked (the surfaces are forced opaque anyway).
                        enabled = wallpaperSelected,
                        // The floor keeps a ghost of the menus legible — they never
                        // fully dissolve into the picture.
                        valueRange = 0.30f..1f,
                        onChange = { value -> onChange { it.copy(surfaceOpacity = value) } },
                    )
                },
            )
        }
    }
}

/** Section label with a small icon, and its headerless setting blocks below. */
@Composable
private fun WallpaperSection(
    title: String,
    @DrawableRes icon: Int,
    labelColor: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = labelColor,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = labelColor,
            )
        }
        content()
    }
}

/**
 * A rounded tonal container of setting rows: extra-large outer corners, extra-small inner
 * corners and a 2dp gap between rows instead of dividers (the settings screen's group).
 */
@Composable
private fun WallpaperGroup(vararg rows: @Composable () -> Unit) {
    val outer = MaterialTheme.shapes.extraLarge
    val inner = MaterialTheme.shapes.extraSmall
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(outer),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        rows.forEachIndexed { index, row ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer
                    .copy(alpha = LocalSurfaceOpacity.current),
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
    }
}

/** The scrim tone: the theme picker's three square icon buttons, auto/light/dark. */
@Composable
private fun DimToneRow(
    selected: DimTone,
    onSelect: (DimTone) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.wallpapers_dim_tone),
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
            Text(
                text = stringResource(R.string.wallpapers_dim_tone_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToneButton(
                icon = R.drawable.ic_brightness_auto,
                label = stringResource(R.string.settings_theme_system),
                selected = selected == DimTone.AUTO,
                onClick = { onSelect(DimTone.AUTO) },
                enabled = enabled,
            )
            ToneButton(
                icon = R.drawable.ic_light_mode,
                label = stringResource(R.string.settings_theme_light),
                selected = selected == DimTone.LIGHT,
                onClick = { onSelect(DimTone.LIGHT) },
                enabled = enabled,
            )
            ToneButton(
                icon = R.drawable.ic_dark_mode,
                label = stringResource(R.string.settings_theme_dark),
                selected = selected == DimTone.DARK,
                onClick = { onSelect(DimTone.DARK) },
                enabled = enabled,
            )
        }
    }
}

/**
 * One of the tone buttons: a square icon toggle — raised tonal container by default, the
 * accent container while selected, pressed state dipping the tone like the settings' ones.
 * Disabled, it goes quiet instead: the flat on-surface wash M3 gives a dead button.
 */
@Composable
private fun ToneButton(
    icon: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val poke = rememberHapticPoke()
    var taps by remember { mutableIntStateOf(0) }
    val resting = when {
        !enabled -> colors.onSurface.copy(alpha = 0.12f)
        selected -> colors.primary
        else -> colors.secondaryContainer
    }
    val container by animateColorAsState(
        targetValue = if (pressed && enabled) lerp(resting, colors.onSurface, 0.1f) else resting,
        animationSpec = motion.defaultEffectsSpec(),
        label = "tone button container",
    )
    val content by animateColorAsState(
        targetValue = when {
            !enabled -> colors.onSurface.copy(alpha = 0.38f)
            selected -> colors.onPrimary
            else -> colors.onSecondaryContainer
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "tone button content",
    )
    Surface(
        onClick = {
            poke()
            taps++
            onClick()
        },
        enabled = enabled,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(16.dp),
        color = container,
        contentColor = content,
        modifier = Modifier
            .size(48.dp)
            .pressBounce(pressed, taps),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = label,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * A slider row: the icon stands in for the label, the percentage keeps the number, and the
 * optional [hint] explains it underneath.
 */
@Composable
private fun SliderRow(
    icon: Int,
    hint: String?,
    value: Float,
    onChange: (Float) -> Unit,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val contentColor = if (enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(24.dp),
            )
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = valueRange,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.wallpapers_percent, (value * 100).roundToInt()),
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
            )
        }
        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
        }
    }
}

/** A named boolean with an icon tile: the settings' toggle row language. */
@Composable
private fun ToggleTileRow(
    title: String,
    hint: String,
    @DrawableRes icon: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val poke = rememberHapticPoke()
    var taps by remember { mutableIntStateOf(0) }
    val resting = when {
        !enabled -> colors.onSurface.copy(alpha = 0.12f)
        checked -> colors.primary
        else -> colors.secondaryContainer
    }
    val container by animateColorAsState(
        targetValue = if (pressed && enabled) lerp(resting, colors.onSurface, 0.1f) else resting,
        animationSpec = motion.defaultEffectsSpec(),
        label = "toggle tile container",
    )
    val content by animateColorAsState(
        targetValue = when {
            !enabled -> colors.onSurface.copy(alpha = 0.38f)
            checked -> colors.onPrimary
            else -> colors.onSecondaryContainer
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "toggle tile content",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
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
                color = if (enabled) {
                    colors.onSurface
                } else {
                    colors.onSurface.copy(alpha = 0.38f)
                },
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    colors.onSurfaceVariant
                } else {
                    colors.onSurfaceVariant.copy(alpha = 0.38f)
                },
            )
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
        color = container,
            contentColor = content,
            modifier = Modifier
                .size(48.dp)
                .pressBounce(pressed, taps),
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
}

/** The confirm button's gap above the navigation bar, the floating bar's own. */
private val BarVerticalPadding = 12.dp
