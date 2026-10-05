package slop.miho.aurora.ui.main

import android.net.VpnService
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import slop.miho.aurora.R
import slop.miho.aurora.core.TunnelState
import slop.miho.aurora.core.TunnelStatus
import slop.miho.aurora.ui.MainViewModel
import slop.miho.aurora.ui.HomeUiState
import slop.miho.aurora.ui.ServersUiState
import slop.miho.aurora.ui.StatsUiState
import slop.miho.aurora.data.UiSettings
import slop.miho.aurora.ui.components.RefreshRateRequest
import slop.miho.aurora.ui.components.SystemBarsIconsDark
import slop.miho.aurora.ui.theme.LocalDarkTheme
import slop.miho.aurora.ui.wallpapers.LocalBackdropDark
import slop.miho.aurora.ui.wallpapers.LocalBarCanFade
import slop.miho.aurora.ui.wallpapers.LocalBarCoverage
import slop.miho.aurora.ui.wallpapers.LocalBarUnderlay
import slop.miho.aurora.ui.wallpapers.LocalHasWallpaper
import slop.miho.aurora.ui.wallpapers.LocalSurfaceOpacity
import slop.miho.aurora.ui.wallpapers.MAX_WALLPAPER_BLUR
import slop.miho.aurora.ui.wallpapers.WallpaperBackdrop
import slop.miho.aurora.ui.wallpapers.backdropTooDark
import slop.miho.aurora.ui.wallpapers.offlineDesaturation
import slop.miho.aurora.ui.wallpapers.rememberDecodedWallpaper
import slop.miho.aurora.ui.wallpapers.scrimColor
import slop.miho.aurora.ui.wallpapers.wallpaperZoomTarget

@Composable
fun MainShell(viewModel: MainViewModel) {
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var selectedCategory by rememberSaveable { mutableStateOf(SettingsCategory.INTERFACE) }

    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    BackHandler(enabled = !settingsOpen && selectedTab != MainTab.HOME) { selectedTab = MainTab.HOME }

    val tunnelStatus by viewModel.tunnelStatus.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val selectedSubscription by viewModel.selectedSubscription.collectAsStateWithLifecycle()
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val home by viewModel.home.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val context = LocalContext.current
    // Connect regardless of the result: if permission was denied the core reports it as an error.
    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.connect()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The wallpaper behind the whole app, the wallpaper screen's own recipe: Home wears
        // the gentle dim only, every other screen adds the stronger dim, the blur and the
        // zoom — animated between the two looks as the user moves around.
        val wallpaper = rememberDecodedWallpaper()
        val homeLook = selectedTab == MainTab.HOME && !settingsOpen
        val motion = MaterialTheme.motionScheme
        // The picture takes a moment to decode, so the app opens on the plain surface; its
        // arrival then plays as the switch back to Home does — a fade under a settle-down
        // from a touch past the cover — rather than a hard pop. Re-armed whenever the
        // picture is gone, so a re-pick replays it.
        var wallpaperArrived by remember { mutableStateOf(false) }
        LaunchedEffect(wallpaper != null) {
            if (wallpaper == null) wallpaperArrived = false else wallpaperArrived = true
        }
        // The animated looks below stay states, read only at layer and draw time inside the
        // backdrop: read here they would recompose the whole shell every animation frame.
        val arrival = animateFloatAsState(
            targetValue = if (wallpaperArrived) 1f else 0f,
            animationSpec = motion.defaultSpatialSpec(),
            label = "wallpaper arrival",
        )
        // The arrival's zoom reach: the same touch past the cover the away-from-Home zoom
        // rides out to.
        val arrivalReach = wallpaperZoomTarget(0f) - 1f
        val zoomed = !homeLook && settings.wallpaperPageZoom
        // The user's own pinch-zoom rides under the page zoom: the base look carries it
        // alone, the away-from-Home look multiplies it in.
        val scale = animateFloatAsState(
            targetValue = if (zoomed) {
                wallpaperZoomTarget(settings.wallpaperBlur) * settings.wallpaperUserScale
            } else {
                settings.wallpaperUserScale
            },
            animationSpec = motion.defaultSpatialSpec(),
            label = "wallpaper zoom",
        )
        val blur = animateDpAsState(
            targetValue = if (homeLook) 0.dp else MAX_WALLPAPER_BLUR * settings.wallpaperBlur,
            animationSpec = motion.defaultSpatialSpec(),
            label = "wallpaper blur",
        )
        val scrim = scrimColor(settings.wallpaperDimTone)
        val scrimAlpha = animateFloatAsState(
            targetValue = if (homeLook) settings.wallpaperDim else settings.wallpaperDimScreens,
            animationSpec = motion.defaultEffectsSpec(),
            label = "wallpaper dim",
        )
        // The picture drains to grayscale while the VPN is down — the picture alone; the
        // theme over it keeps its colors.
        val desaturate = offlineDesaturation()

        // The same rules the wallpaper screen follows, provided once here for every screen
        // below: the menus' surfaces go see-through over the picture, and the bare text
        // recolors when the dimmed picture behind it is too dark.
        val surfaceOpacity = if (wallpaper != null) settings.surfaceOpacity else 1f
        val backdropDark = wallpaper != null && backdropTooDark(
            wallpaper.luminance,
            if (homeLook) settings.wallpaperDim else settings.wallpaperDimScreens,
            scrim,
        )
        // The system bars hang on whatever this shell draws behind them — the theme's own
        // surface without a picture, the dimmed picture over one — so their icons follow the
        // same darkness, not the system's dark mode.
        SystemBarsIconsDark(dark = LocalDarkTheme.current || backdropDark)
        // The screens report into this whether their content sits under the floating bar;
        // the bar reads it and turns opaque over whatever scrolls beneath it. The bar fades
        // with the setting only on Home: everywhere else it stays a step more opaque.
        val barCoverage = remember { mutableStateOf(false) }
        // The stricter report: content really reaching in under the bar, for its tone step.
        val barUnderlay = remember { mutableStateOf(false) }
        // Everything the floating bar floats over — the picture and the screens — is recorded
        // into this layer while the bar's frost is on: the screen shows the layer as is, and
        // the bar re-draws the slice of it behind itself, blurred, as its glass. Off, nothing
        // is recorded and the content draws straight through.
        val frost = settings.navigationBarBlur
        val behindBar = rememberGraphicsLayer()
        CompositionLocalProvider(
            LocalSurfaceOpacity provides surfaceOpacity,
            LocalHasWallpaper provides (wallpaper != null),
            LocalBackdropDark provides backdropDark,
            LocalBarCoverage provides barCoverage,
            LocalBarUnderlay provides barUnderlay,
            LocalBarCanFade provides homeLook,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (frost) {
                            Modifier.drawWithContent {
                                behindBar.record { this@drawWithContent.drawContent() }
                                drawLayer(behindBar)
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
            wallpaper?.let { decoded ->
                WallpaperBackdrop(
                    image = decoded.image,
                    scale = { scale.value * (1f + arrivalReach * (1f - arrival.value)) },
                    blur = { blur.value },
                    scrim = scrim,
                    scrimAlpha = { scrimAlpha.value },
                    desaturate = { desaturate.value },
                    offsetX = { settings.wallpaperOffsetX },
                    offsetY = { settings.wallpaperOffsetY },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = arrival.value },
                )
            }

            MainPagerStrip(
            selectedTab = selectedTab,
            selectedCategory = selectedCategory,
            settingsOpen = settingsOpen,
            subscriptions = subscriptions,
            selectedSubscription = selectedSubscription,
            tunnelStatus = tunnelStatus,
            servers = servers,
            home = home,
            // The flow itself, not its value: it ticks once a second, and the STATS page
            // collects it in its own branch rather than recomposing the whole shell.
            statsFlow = viewModel.stats,
            // The standalone group's row count, the same way: only the SERVERS page
            // ever reads it.
            manualProfileCount = viewModel.manualProfileCount,
            uiSettings = settings,
            connection = viewModel.connection.collectAsStateWithLifecycle().value,
            coreVersion = viewModel.coreVersion.collectAsStateWithLifecycle().value,
            coreRunning = viewModel.coreRunning.collectAsStateWithLifecycle().value,
            onUiSettingsChange = viewModel::updateSettings,
            onChangeConnection = viewModel::updateConnection,
            onOpenServers = {
                selectedTab = MainTab.SERVERS
                settingsOpen = false
            },
            // The home screen's loss warning chip answers a tap with the graphs behind it.
            onOpenStats = {
                selectedTab = MainTab.STATS
                settingsOpen = false
            },
            // The swipe's landing: whatever page the finger carried the strip to, the
            // shell's own selection follows — the two groups' door stays the bar's.
            onSelectPage = { position ->
                if (position < MainTab.entries.size) {
                    selectedTab = MainTab.entries[position]
                    settingsOpen = false
                } else {
                    selectedCategory = SettingsCategory.entries[position - MainTab.entries.size]
                    settingsOpen = true
                }
            },
            onRefreshPing = viewModel::refreshPing,
            onPingServers = viewModel::pingServers,
            onUpdateSubscription = viewModel::updateSelectedSubscription,
            onSelectSubscription = viewModel::selectSubscription,
            onAddSubscription = viewModel::addSubscription,
            onDeleteSubscription = viewModel::deleteSubscription,
            onToggleAutoUpdate = viewModel::setSubscriptionAutoUpdate,
            onSelect = viewModel::selectProfile,
            onConnect = {
                val consent = VpnService.prepare(context)
                if (consent != null) vpnPermission.launch(consent) else viewModel.connect()
            },
            onDisconnect = viewModel::disconnect,
            )
            }

            if (wallpaper == null) {
                // The plain shell: content scrolls under the bars and fades out behind
                // these gradients. They stay out of the frost's recording — under the
                // glass the rows show through instead of the fade.
                val surface = MaterialTheme.colorScheme.surface
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(topScrimHeight())
                        .drawBehind {
                            drawRect(
                                Brush.verticalGradient(
                                    listOf(surface.copy(alpha = 0.97f), surface.copy(alpha = 0f)),
                                ),
                            )
                        },
                )

                // Content scrolls under the floating bar and fades out behind it.
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(bottomScrimHeight())
                        .drawBehind {
                            drawRect(
                                Brush.verticalGradient(
                                    listOf(surface.copy(alpha = 0f), surface.copy(alpha = 0.97f)),
                                ),
                            )
                        },
                )
            }

            FloatingBottomBar(
                selectedTab = selectedTab,
                onTabSelected = {
                    selectedTab = it
                    settingsOpen = false
                },
                settingsOpen = settingsOpen,
                // The gear always lands on the About page, not wherever the user left off.
                onSettingsOpenChange = { open ->
                    settingsOpen = open
                    if (open) selectedCategory = SettingsCategory.ABOUT
                },
                selectedCategory = selectedCategory,
                onCategorySelected = { selectedCategory = it },
                // What the bar floats over, for its frost: the pills re-draw the slice of
                // it that sits behind them.
                backdrop = if (frost) behindBar else null,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** Height of the gradient that fades content out under the status bar. */
@Composable
private fun topScrimHeight() =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp

/** Height of the gradient that fades content out below the floating bar. */
@Composable
private fun bottomScrimHeight() =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        FloatingBottomBarReservedHeight + 8.dp

/**
 * Identity of a navigation target: a new object per navigation. AnimatedContent keys its
 * content slots by the target's identity, so a screen re-entered while its previous copy is
 * still animating out (fast A → B → A) is composed as fresh content and replays the full
 * slide-up + fade — instead of retargeting the old copy's transition, which only crossfades
 * it back in place and reads as the animation being skipped.
 */
private class PageTarget(val position: Int)

/**
 * How far the heavy page must travel before the release counts as a page turn — a
 * quarter of the way; less than that and it springs back home.
 */
private const val DirectSwipeCommitFraction = 0.25f

/**
 * Two paging modes over one destination set: main tabs at strip positions 0..3, settings
 * categories continuing to the right, matching their order in the bottom bar. The default
 * swaps pages directly with a slide-up fade, composing only the old and the new screen;
 * expressive transitions opt back into scrolling the whole strip.
 */
@Composable
private fun MainPagerStrip(
    selectedTab: MainTab,
    selectedCategory: SettingsCategory,
    settingsOpen: Boolean,
    subscriptions: List<slop.miho.aurora.db.SubscriptionEntity>,
    selectedSubscription: slop.miho.aurora.db.SubscriptionEntity?,
    tunnelStatus: TunnelStatus,
    servers: ServersUiState,
    home: HomeUiState,
    statsFlow: StateFlow<StatsUiState>,
    manualProfileCount: StateFlow<Int>,
    uiSettings: UiSettings,
    connection: slop.miho.aurora.data.ConnectionSettings,
    coreVersion: String?,
    coreRunning: Boolean,
    onUiSettingsChange: ((UiSettings) -> UiSettings) -> Unit,
    onChangeConnection: ((slop.miho.aurora.data.ConnectionSettings) -> slop.miho.aurora.data.ConnectionSettings) -> Unit,
    onOpenServers: () -> Unit,
    onOpenStats: () -> Unit,
    onSelectPage: (Int) -> Unit,
    onRefreshPing: (showPending: Boolean) -> Unit,
    onPingServers: () -> Unit,
    onUpdateSubscription: () -> Unit,
    onSelectSubscription: (String) -> Unit,
    onAddSubscription: (url: String, name: String?, onDone: (String?) -> Unit) -> Unit,
    onDeleteSubscription: (String) -> Unit,
    onToggleAutoUpdate: (String, Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageWidth = constraints.maxWidth.toFloat()
        val settingsOffset = MainTab.entries.size
        val targetPosition = if (settingsOpen) {
            settingsOffset + selectedCategory.ordinal
        } else {
            selectedTab.ordinal
        }
        // Owned here, outside either pager mode, so flipping the transition setting doesn't
        // reset the settings page's scroll position.
        val interfaceScroll = rememberScrollState()
        val motion = MaterialTheme.motionScheme
        val scope = rememberCoroutineScope()

        // The swipe's corridor: the two groups never mix. The strip travels the main tabs
        // while the settings are closed and the settings categories while they're open —
        // past STATS the finger finds no next page, and Home is the far wall on the other
        // side. The only doors between the groups stay the bar's own pills.
        val firstPage = if (settingsOpen) settingsOffset else 0
        val lastPage = if (settingsOpen) {
            settingsOffset + SettingsCategory.entries.lastIndex
        } else {
            settingsOffset - 1
        }

        // The strip's position between pages: an animation while the finger is up, the
        // finger's own while it drags. [dragPosition] stands in for the animation for as
        // long as the drag lasts; on release the strip is handed back already where the
        // finger left it and settles the rest of the way to the landing page.
        val stripPosition = remember { Animatable(targetPosition.toFloat()) }
        var dragPosition by remember { mutableFloatStateOf(Float.NaN) }
        // The slot of the page a direct-mode swipe is carrying out, MIN when none: the
        // dragged page alone translates by the drag — every other page stands still.
        var draggedSlot by remember { mutableIntStateOf(Int.MIN_VALUE) }
        // The running drag-following animation, if any: the fly-out after a committed
        // release or the spring home after a failed one. A fresh drag cancels it — the
        // finger takes the page over from wherever the animation has got it.
        var dragAnimJob by remember { mutableStateOf<Job?>(null) }
        LaunchedEffect(targetPosition) {
            stripPosition.animateTo(targetPosition.toFloat(), motion.defaultSpatialSpec())
        }

        // The screen at a strip position: main tabs at 0..2, settings categories after them.
        @Composable
        fun destinationPage(position: Int) {
            if (position < settingsOffset) {
                when (val tab = MainTab.entries[position]) {
                    MainTab.HOME -> HomeScreen(
                        status = tunnelStatus,
                        home = home,
                        visible = selectedTab == MainTab.HOME && !settingsOpen,
                        onRefreshPing = onRefreshPing,
                        onConnect = onConnect,
                        onDisconnect = onDisconnect,
                        onOpenServers = onOpenServers,
                        onOpenStats = onOpenStats,
                    )
                    MainTab.SERVERS -> {
                        // The week rides the stats flow but only its own list is wanted
                        // here: the flow itself ticks once a second, while the week
                        // changes only on a flush — so only the page recomposes then.
                        val week by statsFlow
                            .map { it.week }
                            .distinctUntilChanged()
                            .collectAsStateWithLifecycle(initialValue = emptyList())
                        val manualCount by manualProfileCount.collectAsStateWithLifecycle()
                        ServersScreen(
                            subscriptions = subscriptions,
                            selectedSubscription = selectedSubscription,
                            state = servers,
                            week = week,
                            manualCount = manualCount,
                            flagStyle = uiSettings.flagStyle,
                            tunnelUp = tunnelStatus.state == TunnelState.UP,
                            onUpdate = onUpdateSubscription,
                            onPing = onPingServers,
                            onSelectSubscription = onSelectSubscription,
                            onAddSubscription = onAddSubscription,
                            onDeleteSubscription = onDeleteSubscription,
                            onSelect = onSelect,
                        )
                    }
                    MainTab.STATS -> {
                        // Collected in the branch, not the shell's top: the flow ticks once
                        // a second and only ever recomposes this page.
                        val stats by statsFlow.collectAsStateWithLifecycle()
                        StatsScreen(
                            state = stats,
                            tunnelUp = tunnelStatus.state == TunnelState.UP,
                        )
                    }
                }
            } else {
                when (val category = SettingsCategory.entries[position - settingsOffset]) {
                    SettingsCategory.INTERFACE -> InterfaceScreen(
                        settings = uiSettings,
                        onChange = onUiSettingsChange,
                        scrollState = interfaceScroll,
                    )
                    SettingsCategory.CONNECTION -> ConnectionScreen(
                        connection = connection,
                        subscriptions = subscriptions,
                        flagStyle = uiSettings.flagStyle,
                        settings = uiSettings,
                        onChangeConnection = onChangeConnection,
                        onChangeSettings = onUiSettingsChange,
                        onToggleAutoUpdate = onToggleAutoUpdate,
                    )
                    SettingsCategory.ABOUT -> AboutScreen(
                        coreVersion = coreVersion,
                        coreRunning = coreRunning,
                    )
                }
            }
        }

        // The swipe over the pages: a horizontal draggable riding the whole strip.
        // Children that drag horizontally themselves — the accent row, the flags, the
        // sliders — claim the gesture first and this one stands down: the page turns only
        // where nothing closer wants the drag. The standard pager rule throughout: the
        // finger left drags the page left, the next one in from the right.
        // The mode and the corridor reach the delta handler through updated states, so a
        // flip of either hands the drag fresh bounds with no stale capture.
        val expressiveNow = rememberUpdatedState(!uiSettings.simpleTransitions)
        val firstPageNow = rememberUpdatedState(firstPage)
        val lastPageNow = rememberUpdatedState(lastPage)
        val dragState = rememberDraggableState { delta ->
            // A positive delta: the finger moves right, the strip travels toward the
            // previous page. Both modes move the same coordinate; in the direct mode it
            // only feeds the settle (the release handler), the pages themselves wait for
            // the landing's own animation.
            dragPosition = (dragPosition - delta / pageWidth)
                .coerceIn(firstPageNow.value.toFloat(), lastPageNow.value.toFloat())
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStarted = {
                        // Whatever the released page was still doing — flying out or
                        // springing home — stops here: the finger takes it over.
                        dragAnimJob?.cancel()
                        dragAnimJob = null
                        when {
                            // Mid-return spring: the finger takes the page over from
                            // wherever it has got to.
                            draggedSlot == targetPosition && !dragPosition.isNaN() -> {}
                            // Expressive continuity: wherever the strip's settle stands.
                            expressiveNow.value -> dragPosition = stripPosition.value
                            else -> dragPosition = targetPosition.toFloat()
                        }
                        draggedSlot = targetPosition
                    },
                    onDragStopped = { velocity ->
                        // A quick flick outstrips any position: it carries a full page.
                        val fling = abs(velocity) > pageWidth * 0.8f
                        if (expressiveNow.value) {
                            val landing = (
                                if (fling) {
                                    // Leftward velocity (negative) throws to the next page.
                                    dragPosition.roundToInt() + if (velocity < 0) 1 else -1
                                } else {
                                    dragPosition.roundToInt()
                                }
                                ).coerceIn(firstPageNow.value, lastPageNow.value)
                            // The settle starts from under the finger, wherever it is.
                            val from = dragPosition
                            scope.launch {
                                stripPosition.snapTo(from)
                                stripPosition.animateTo(
                                    landing.toFloat(),
                                    motion.defaultSpatialSpec(),
                                )
                            }
                            dragPosition = Float.NaN
                            draggedSlot = Int.MIN_VALUE
                            if (landing != targetPosition) onSelectPage(landing)
                        } else {
                            // The heavy page: flung or carried a quarter of the way, the
                            // landing takes over — the dragged page flies out the rest of
                            // the way on its own momentum while the landing rises with the
                            // standard slide (the dragged page's own dispose carries the
                            // drag states out behind it, see the direct branch). Not far
                            // enough, and the heavy page springs back home.
                            val offset = dragPosition - targetPosition
                            val step = when {
                                fling -> if (velocity < 0) 1 else -1
                                abs(offset) > DirectSwipeCommitFraction ->
                                    if (offset > 0) 1 else -1
                                else -> 0
                            }
                            val landing = (targetPosition + step)
                                .coerceIn(firstPageNow.value, lastPageNow.value)
                            if (landing != targetPosition) {
                                // The release is a throw: whatever distance the page has
                                // left, it covers on its own — the finger's momentum carries
                                // it fully out in the swipe's direction.
                                val from = dragPosition
                                val out = (
                                    draggedSlot + if (landing > targetPosition) 1 else -1
                                    ).toFloat()
                                dragAnimJob = scope.launch {
                                    animate(
                                        initialValue = from,
                                        targetValue = out,
                                        animationSpec = motion.defaultSpatialSpec(),
                                    ) { value, _ ->
                                        dragPosition = value
                                    }
                                    // If the page's own disposal has already cleaned the
                                    // states behind its fade, our writes since were
                                    // invisible — drop them here too. Otherwise the fade's
                                    // disposal still owns them.
                                    if (draggedSlot == Int.MIN_VALUE) dragPosition = Float.NaN
                                }
                                onSelectPage(landing)
                            } else {
                                val from = dragPosition
                                dragAnimJob = scope.launch {
                                    animate(
                                        initialValue = from,
                                        targetValue = targetPosition.toFloat(),
                                        animationSpec = motion.defaultSpatialSpec(),
                                    ) { value, _ ->
                                        dragPosition = value
                                    }
                                    dragPosition = Float.NaN
                                    draggedSlot = Int.MIN_VALUE
                                }
                            }
                        }
                    },
                ),
        ) {
            // The pager mode itself swaps with a quick crossfade: the outgoing mode stays
            // composed while it fades out, so a press in progress — the toggle's own bounce —
            // plays out on it instead of being cut off by the subtree swap.
            AnimatedContent(
                targetState = uiSettings.simpleTransitions,
                transitionSpec = {
                    fadeIn(motion.fastEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())
                },
                label = "pager mode",
            ) { simple ->
                if (simple) {
                    // Simple transitions: a direct switch — only the old and the new screen
                    // are composed, the old fades out while the new slides up from below and
                    // fades in. No intermediate pages are rendered or scrolled past, which
                    // is what lags the weaker devices.
                    // Fresh identity per navigation: every switch gets the full slide, even
                    // when the previous one is still running (see PageTarget).
                    val target = remember(targetPosition) { PageTarget(targetPosition) }
                    AnimatedContent(
                        targetState = target,
                        transitionSpec = {
                            (fadeIn(motion.defaultEffectsSpec()) +
                                slideInVertically(motion.defaultSpatialSpec()) { it / 3 }) togetherWith
                                fadeOut(motion.defaultEffectsSpec())
                        },
                        label = "direct page switch",
                    ) { page ->
                        // The switch plays out over a spring: ask the display for its high
                        // gear while it runs, so a dynamically-refreshed screen doesn't
                        // idle at 60. The heavy swipe's own travel rides a graphics layer
                        // the same invisible way — same request while the page is in hand.
                        RefreshRateRequest(active = transition.isRunning || !dragPosition.isNaN())
                        // The dragged page alone rides under the finger — the direct
                        // mode's heavy swipe: no next page pulls in; the current one
                        // simply follows the finger out, and on the release it flies the
                        // rest of the way out on its own momentum while the landing rises
                        // with the standard slide. The page's instance carries its own
                        // disposal: when its fade ends and it finally leaves, it takes the
                        // drag's states with it — unless a newer drag has already claimed
                        // them.
                        val slot = page.position
                        val draggedSlotNow = rememberUpdatedState(draggedSlot)
                        DisposableEffect(slot) {
                            onDispose {
                                if (draggedSlotNow.value == slot) {
                                    draggedSlot = Int.MIN_VALUE
                                    dragPosition = Float.NaN
                                }
                            }
                        }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationX = if (
                                        slot == draggedSlotNow.value && !dragPosition.isNaN()
                                    ) {
                                        -(dragPosition - slot) * pageWidth
                                    } else {
                                        0f
                                    }
                                },
                        ) {
                            destinationPage(slot)
                        }
                    }
                } else {
                    // The default — expressive transitions: one continuous strip, scrolled
                    // to the target — and by the finger while it drags (the corridor above).
                    val pageCount = settingsOffset + SettingsCategory.entries.size
                    // Which pages are near enough to show: derived, so the strip recomposes
                    // only when a page enters or leaves — the per-frame slide itself is read
                    // inside each page's layer.
                    val nearPages by remember {
                        derivedStateOf {
                            val position =
                                if (dragPosition.isNaN()) stripPosition.value else dragPosition
                            (0 until pageCount).filter { abs(it - position) < 1.5f }
                        }
                    }
                    // The strip's travel is a long spring or the finger's own drag — either
                    // way the system won't clock it on its own (the scroll rides a
                    // graphicsLayer, invisible to it), so the display is asked for its high
                    // gear while the position is anywhere off its page or still held.
                    val targetPositionState = rememberUpdatedState(targetPosition)
                    val stripBusy by remember {
                        derivedStateOf {
                            !dragPosition.isNaN() ||
                                stripPosition.value != targetPositionState.value.toFloat()
                        }
                    }
                    RefreshRateRequest(active = stripBusy)
                    nearPages.forEach { position ->
                        // Keyed so a page keeps its state as neighbors come and go around it.
                        key(position) {
                            Page(
                                position = position,
                                animatedPosition = {
                                    if (dragPosition.isNaN()) stripPosition.value else dragPosition
                                },
                                pageWidth = pageWidth,
                            ) {
                                destinationPage(position)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Page(
    position: Int,
    animatedPosition: () -> Float,
    pageWidth: Float,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationX = (position - animatedPosition()) * pageWidth },
    ) {
        content()
    }
}
