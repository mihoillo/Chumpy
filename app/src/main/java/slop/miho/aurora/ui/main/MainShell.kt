package slop.miho.aurora.ui.main

import android.net.VpnService
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.flow.StateFlow
import slop.miho.aurora.R
import slop.miho.aurora.core.TunnelState
import slop.miho.aurora.core.TunnelStatus
import slop.miho.aurora.ui.MainViewModel
import slop.miho.aurora.ui.HomeUiState
import slop.miho.aurora.ui.DevicesUiState
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
    // Bumped once per "this device → go connect" request from the devices screen; Home reads
    // it and pulses the connect button.
    var connectAttention by remember { mutableIntStateOf(0) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var selectedCategory by rememberSaveable { mutableStateOf(SettingsCategory.INTERFACE) }

    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    BackHandler(enabled = !settingsOpen && selectedTab != MainTab.HOME) { selectedTab = MainTab.HOME }

    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val tunnelStatus by viewModel.tunnelStatus.collectAsStateWithLifecycle()
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
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
            profileName = profile?.name,
            tunnelStatus = tunnelStatus,
            servers = servers,
            devices = devices,
            home = home,
            // The flow itself, not its value: it ticks once a second, and the STATS page
            // collects it in its own branch rather than recomposing the whole shell.
            statsFlow = viewModel.stats,
            uiSettings = settings,
            onUiSettingsChange = viewModel::updateSettings,
            onOpenServers = {
                selectedTab = MainTab.SERVERS
                settingsOpen = false
            },
            // The home screen's loss warning chip answers a tap with the graphs behind it.
            onOpenStats = {
                selectedTab = MainTab.STATS
                settingsOpen = false
            },
            onRefreshPing = viewModel::refreshPing,
            onRefresh = viewModel::refreshServers,
            // Background refreshes keep the servers page fresh without the pull indicator.
            onAutoRefresh = { viewModel.refreshServers(silent = true) },
            onRefreshDevices = viewModel::refreshDevices,
            onAutoRefreshDevices = { viewModel.refreshDevices(silent = true) },
            onCreateDevice = viewModel::createDevice,
            onRenameDevice = viewModel::renameDevice,
            onToggleDevice = viewModel::toggleDevice,
            onDeleteDevice = viewModel::deleteDevice,
            onDeviceConfig = viewModel::deviceConfig,
            connectAttention = connectAttention,
            // Taken once by Home's connect button; cleared so a later return doesn't replay it.
            onConnectAttentionHandled = { connectAttention = 0 },
            onOpenHome = {
                selectedTab = MainTab.HOME
                settingsOpen = false
                connectAttention++
            },
            onSelect = viewModel::selectServer,
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
                // The gear always lands on the interface settings, not wherever the user left off.
                onSettingsOpenChange = { open ->
                    settingsOpen = open
                    if (open) selectedCategory = SettingsCategory.INTERFACE
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
    profileName: String?,
    tunnelStatus: TunnelStatus,
    servers: ServersUiState,
    devices: DevicesUiState,
    home: HomeUiState,
    statsFlow: StateFlow<StatsUiState>,
    uiSettings: UiSettings,
    onUiSettingsChange: ((UiSettings) -> UiSettings) -> Unit,
    onOpenServers: () -> Unit,
    onOpenStats: () -> Unit,
    onRefreshPing: (showPending: Boolean) -> Unit,
    onRefresh: () -> Unit,
    onAutoRefresh: () -> Unit,
    onRefreshDevices: () -> Unit,
    onAutoRefreshDevices: () -> Unit,
    onCreateDevice: (String) -> Unit,
    onRenameDevice: (Int, String) -> Unit,
    onToggleDevice: (Int) -> Unit,
    onDeleteDevice: (Int) -> Unit,
    onDeviceConfig: suspend (Int) -> String?,
    connectAttention: Int,
    onConnectAttentionHandled: () -> Unit,
    onOpenHome: () -> Unit,
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

        // The screen at a strip position: main tabs at 0..3, settings categories after them.
        @Composable
        fun destinationPage(position: Int) {
            if (position < settingsOffset) {
                when (val tab = MainTab.entries[position]) {
                    MainTab.HOME -> HomeScreen(
                        name = profileName,
                        status = tunnelStatus,
                        home = home,
                        exitIp = servers.exitIp,
                        visible = selectedTab == MainTab.HOME && !settingsOpen,
                        onRefreshPing = onRefreshPing,
                        ipHidden = uiSettings.hideIp,
                        onToggleIp = { onUiSettingsChange { it.copy(hideIp = !it.hideIp) } },
                        // Auto flies the flag of the exit it resolved to: that's where the
                        // traffic actually leaves.
                        exitCountry = servers.exits.firstOrNull {
                            it.id == (servers.actualExitId ?: servers.currentExitId)
                        }?.country,
                        onConnect = onConnect,
                        onDisconnect = onDisconnect,
                        onOpenServers = onOpenServers,
                        onOpenStats = onOpenStats,
                        attentionTick = connectAttention,
                        onAttentionHandled = onConnectAttentionHandled,
                    )
                    MainTab.SERVERS -> ServersScreen(
                        state = servers,
                        tunnelUp = tunnelStatus.state == TunnelState.UP,
                        visible = selectedTab == MainTab.SERVERS && !settingsOpen,
                        flagStyle = uiSettings.flagStyle,
                        onRefresh = onRefresh,
                        onAutoRefresh = onAutoRefresh,
                        onSelect = onSelect,
                    )
                    MainTab.DEVICES -> DevicesScreen(
                        state = devices,
                        tunnelUp = tunnelStatus.state == TunnelState.UP,
                        visible = selectedTab == MainTab.DEVICES && !settingsOpen,
                        onRefresh = onRefreshDevices,
                        onAutoRefresh = onAutoRefreshDevices,
                        onCreateDevice = onCreateDevice,
                        onRename = onRenameDevice,
                        onToggleDevice = onToggleDevice,
                        onDeleteDevice = onDeleteDevice,
                        loadDeviceConfig = onDeviceConfig,
                        onOpenHome = onOpenHome,
                    )
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
                    else -> PlaceholderScreen(
                        title = stringResource(category.label),
                        icon = category.icon,
                    )
                }
            }
        }

        // The pager mode itself swaps with a quick crossfade: the outgoing mode stays
        // composed while it fades out, so a press in progress — the toggle's own bounce —
        // plays out on it instead of being cut off by the subtree swap.
        AnimatedContent(
            targetState = uiSettings.expressiveTransitions,
            transitionSpec = {
                fadeIn(motion.fastEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())
            },
            label = "pager mode",
        ) { expressive ->
            if (!expressive) {
                // The default: a direct switch — only the old and the new screen are composed,
                // the old fades out while the new slides up from below and fades in. No
                // intermediate pages are rendered or scrolled past, which is what lagged the
                // legacy devices.
                // Fresh identity per navigation: every switch gets the full slide, even when the
                // previous one is still running (see PageTarget).
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
                    // The switch plays out over a spring: ask the display for its high gear
                    // while it runs, so a dynamically-refreshed screen doesn't idle at 60.
                    RefreshRateRequest(active = transition.isRunning)
                    destinationPage(page.position)
                }
            } else {
                // Expressive transitions: one continuous strip, scrolled to the target.
                val animatedPositionState = animateFloatAsState(
                    targetValue = targetPosition.toFloat(),
                    animationSpec = motion.defaultSpatialSpec(),
                    label = "page strip position",
                )
                // Which pages are near enough to show: derived, so the strip recomposes only
                // when a page enters or leaves — the per-frame slide itself is read inside
                // each page's layer.
                val pageCount = settingsOffset + SettingsCategory.entries.size
                val nearPages by remember {
                    derivedStateOf {
                        val position = animatedPositionState.value
                        (0 until pageCount).filter { abs(it - position) < 1.5f }
                    }
                }
                // The strip's settle is a long spring the system won't clock on its own —
                // the scroll rides a graphicsLayer, invisible to it — so the same request is
                // made here while the position is still traveling.
                val targetPositionState = rememberUpdatedState(targetPosition)
                val stripTraveling by remember {
                    derivedStateOf { animatedPositionState.value != targetPositionState.value.toFloat() }
                }
                RefreshRateRequest(active = stripTraveling)
                nearPages.forEach { position ->
                    // Keyed so a page keeps its state as neighbors come and go around it.
                    key(position) {
                        Page(
                            position = position,
                            animatedPosition = { animatedPositionState.value },
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
