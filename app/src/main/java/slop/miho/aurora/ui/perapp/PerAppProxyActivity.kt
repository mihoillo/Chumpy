package slop.miho.aurora.ui.perapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorMatrixColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import slop.miho.aurora.R
import slop.miho.aurora.data.ConnectionStore
import slop.miho.aurora.data.SettingsStore
import slop.miho.aurora.ui.components.SystemBarsIconsDark
import slop.miho.aurora.ui.components.WavyPullToRefreshBox
import slop.miho.aurora.ui.components.pressBounce
import slop.miho.aurora.ui.components.pullShiftedPadding
import slop.miho.aurora.ui.components.rememberHapticPoke
import slop.miho.aurora.ui.main.FloatingBottomBarReservedHeight
import slop.miho.aurora.ui.main.PillDestination
import slop.miho.aurora.ui.main.SubScreenBottomBar
import slop.miho.aurora.ui.main.recordBehindBar
import slop.miho.aurora.ui.main.rememberContentUnderBar
import slop.miho.aurora.ui.main.screenPadding
import slop.miho.aurora.ui.theme.AuroraTheme
import slop.miho.aurora.ui.theme.LocalDarkTheme
import slop.miho.aurora.ui.wallpapers.LocalBarCoverage
import slop.miho.aurora.ui.wallpapers.LocalBarUnderlay
import slop.miho.aurora.ui.wallpapers.LocalSurfaceOpacity
import slop.miho.aurora.ui.wallpapers.ReportBarCoverage

/**
 * The per-app proxy and the trusted networks, two pages of one deep-dive screen in the
 * wallpapers' footsteps. The apps page is the bypass list — picked apps ignore the tunnel
 * and connect directly (v2rayNG's arrangement); the networks page is the same story for
 * Wi-Fi names — on a trusted one the tunnel stands down entirely, traffic going direct.
 * The bar carries both pages as pills, a quick switch apart; the system back gesture and
 * the bar's back button both simply finish this activity.
 */
class PerAppProxyActivity : ComponentActivity() {
    companion object {
        /** Which page the screen opens on: [PAGE_APPS] or [PAGE_NETWORKS]. */
        const val EXTRA_PAGE = "page"
        const val PAGE_APPS = "apps"
        const val PAGE_NETWORKS = "networks"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val startPage = if (intent.getStringExtra(EXTRA_PAGE) == PAGE_NETWORKS) {
            PerAppPage.NETWORKS
        } else {
            PerAppPage.APPS
        }
        setContent {
            val ui by SettingsStore.settings(this).collectAsStateWithLifecycle()
            val connection by ConnectionStore.settings(this).collectAsStateWithLifecycle()
            AuroraTheme(settings = ui) {
                // The window's own bars answer to the theme, as in the main shell — there
                // is no wallpaper here for them to hang on instead.
                SystemBarsIconsDark(dark = LocalDarkTheme.current)
                Surface(color = MaterialTheme.colorScheme.surface) {
                    PerAppProxyScreen(
                        initialPage = startPage,
                        frost = ui.navigationBarBlur,
                        appsEnabled = connection.perAppEnabled,
                        bypassedApps = connection.bypassedApps,
                        trustedWifiEnabled = connection.trustedWifiEnabled,
                        trustedWifiSsids = connection.trustedWifiSsids,
                        onToggleAppsEnabled = { enabled ->
                            ConnectionStore.update(this) { settings ->
                                settings.copy(perAppEnabled = enabled)
                            }
                        },
                        onToggleApp = { pkg ->
                            ConnectionStore.update(this) { settings ->
                                settings.copy(
                                    bypassedApps = settings.bypassedApps.let {
                                        if (pkg in it) it - pkg else it + pkg
                                    },
                                )
                            }
                        },
                        onToggleTrustedWifiEnabled = { enabled ->
                            ConnectionStore.update(this) { settings ->
                                settings.copy(trustedWifiEnabled = enabled)
                            }
                        },
                        onToggleTrustedWifi = { ssid ->
                            ConnectionStore.update(this) { settings ->
                                settings.copy(
                                    trustedWifiSsids = settings.trustedWifiSsids.let {
                                        if (ssid in it) it - ssid else it + ssid
                                    },
                                )
                            }
                        },
                        onBack = ::finish,
                    )
                }
            }
        }
    }
}

/** The bar's destinations: the apps page and the networks page, one quick switch apart. */
private enum class PerAppPage(override val icon: Int, override val label: Int) : PillDestination {
    APPS(R.drawable.ic_apps, R.string.per_app_title),
    NETWORKS(R.drawable.ic_wifi, R.string.trusted_title),
}

/** One launchable app, icon included, loaded off the main thread. */
private data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap,
)

/** One Wi-Fi network by name; [current] marks the one the device sits on. */
private data class NetworkEntry(
    val ssid: String,
    val current: Boolean,
)

@Composable
private fun PerAppProxyScreen(
    initialPage: PerAppPage,
    frost: Boolean,
    appsEnabled: Boolean,
    bypassedApps: Set<String>,
    trustedWifiEnabled: Boolean,
    trustedWifiSsids: Set<String>,
    onToggleAppsEnabled: (Boolean) -> Unit,
    onToggleApp: (String) -> Unit,
    onToggleTrustedWifiEnabled: (Boolean) -> Unit,
    onToggleTrustedWifi: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableStateOf(initialPage) }
    val surface = MaterialTheme.colorScheme.surface
    // The bar's glass, the shell's own law in this window too: the pages report whether
    // their rows sit under the bar — it steps a tone up over them — and while the frost
    // setting is on everything the bar floats over is recorded, so its pills re-draw the
    // slice behind themselves, blurred, as their glass.
    val barCoverage = remember { mutableStateOf(false) }
    val barUnderlay = remember { mutableStateOf(false) }
    val behindBar = rememberGraphicsLayer()
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .recordBehindBar(behindBar, frost),
        ) {
            CompositionLocalProvider(
                LocalBarCoverage provides barCoverage,
                LocalBarUnderlay provides barUnderlay,
            ) {
                when (page) {
                    PerAppPage.APPS -> AppsPage(
                        enabled = appsEnabled,
                        bypassed = bypassedApps,
                        onToggleEnabled = onToggleAppsEnabled,
                        onToggle = onToggleApp,
                    )
                    PerAppPage.NETWORKS -> NetworksPage(
                        enabled = trustedWifiEnabled,
                        trusted = trustedWifiSsids,
                        onToggleEnabled = onToggleTrustedWifiEnabled,
                        onToggle = onToggleTrustedWifi,
                    )
                }
            }

            // Content scrolls under the status bar and fades out behind this gradient, the
            // shell's own page fade.
            Box(
                modifier = Modifier
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

            // Content scrolls under the floating bar and fades out behind it.
            Box(
                modifier = Modifier
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

        SubScreenBottomBar(
            items = PerAppPage.entries.toList(),
            selected = page,
            onSelect = { page = it },
            onBack = onBack,
            backdrop = behindBar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * The apps page: the bypass list's master switch, then every launchable app, the picked
 * ones' block riding on top.
 */
@Composable
private fun AppsPage(
    enabled: Boolean,
    bypassed: Set<String>,
    onToggleEnabled: (Boolean) -> Unit,
    onToggle: (String) -> Unit,
) {
    val context = LocalContext.current
    // The launcher's own list — labels and icons of every launchable app, re-queried on
    // every pull. Both loads are package-manager work: off the main thread, and the
    // pull's wave makes the wait legible — it also answers the page's own first load.
    var refreshes by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    val apps by produceState(initialValue = emptyList<AppEntry>(), refreshes) {
        loading = true
        value = withContext(Dispatchers.IO) { installedApps(context) }
        loading = false
    }
    // The picked apps' block rides on top, frozen as the page opened: a toggle repaints
    // the row where it sits rather than re-sorting the blocks under the finger — the
    // order only settles again on the next visit.
    val initialBypassed = remember { bypassed }
    val selected = remember(apps) { apps.filter { it.packageName in initialBypassed } }
    val rest = remember(apps) { apps.filter { it.packageName !in initialBypassed } }
    // Computed here, not in the list's scope: MaterialTheme doesn't reach into it.
    val outer = MaterialTheme.shapes.extraLarge
    val inner = MaterialTheme.shapes.extraSmall

    // The page's report into the screen's bar: covered once the list has anywhere to
    // travel, properly under it once the last rows reach past the bar's rim.
    val listState = rememberLazyListState()
    val covered by remember(listState) {
        derivedStateOf { listState.canScrollForward || listState.canScrollBackward }
    }
    val underlaid by rememberContentUnderBar(listState)
    ReportBarCoverage(covered, underlaid = underlaid)

    // The wave grows with the pull while the list re-queries; the first load rides
    // the same wave, so the wait is never a blank page.
    WavyPullToRefreshBox(
        isRefreshing = loading,
        onRefresh = { refreshes++ },
        modifier = Modifier.fillMaxSize(),
    ) { pullShift ->
        // Rows travel under the status bar and the floating bar (fading out behind
        // the gradients) and rest clear of both thanks to the content padding. The
        // pull pushes the rows down through the top padding (see
        // WavyPullToRefreshBox).
        PagedList(
            listState = listState,
            pullShift = pullShift,
            toggle = {
                MasterSwitchRow(
                    title = stringResource(R.string.per_app_title),
                    supporting = stringResource(
                        when {
                            !enabled -> R.string.per_app_disabled
                            bypassed.isEmpty() -> R.string.per_app_hint
                            else -> R.string.per_app_count
                        },
                        if (bypassed.isEmpty()) 1 else bypassed.size,
                    ),
                    enabled = enabled,
                    onChange = onToggleEnabled,
                )
            },
        ) {
            itemsIndexed(selected, key = { _, app -> app.packageName }) { index, app ->
                SelectableRow(
                    headline = app.label,
                    supporting = app.packageName,
                    bypassed = app.packageName in bypassed,
                    active = enabled,
                    onToggle = { onToggle(app.packageName) },
                    shape = sectionShape(index, selected.lastIndex, outer, inner),
                ) {
                    AppIcon(app = app, active = enabled)
                }
            }
            if (selected.isNotEmpty() && rest.isNotEmpty()) {
                item(key = "block gap") { Spacer(modifier = Modifier.height(12.dp)) }
            }
            itemsIndexed(rest, key = { _, app -> app.packageName }) { index, app ->
                SelectableRow(
                    headline = app.label,
                    supporting = app.packageName,
                    bypassed = app.packageName in bypassed,
                    active = enabled,
                    onToggle = { onToggle(app.packageName) },
                    shape = sectionShape(index, rest.lastIndex, outer, inner),
                ) {
                    AppIcon(app = app, active = enabled)
                }
            }
        }
    }
}

/**
 * The networks page: the trusted networks' master switch, then the Wi-Fi names the OS
 * tells us — the one the device sits on, plus whatever was added by hand; Android
 * stopped sharing the saved list itself back at 10, so those two are the whole set.
 * Names need location access; without the grant the page still works, the hand-added
 * networks and the permission's own card standing in for the list.
 */
@Composable
private fun NetworksPage(
    enabled: Boolean,
    trusted: Set<String>,
    onToggleEnabled: (Boolean) -> Unit,
    onToggle: (String) -> Unit,
) {
    val context = LocalContext.current
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> locationGranted = granted }

    var refreshes by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var known by remember { mutableStateOf(emptyList<NetworkEntry>()) }
    LaunchedEffect(refreshes, locationGranted) {
        loading = true
        known = withContext(Dispatchers.IO) { knownNetworks(context) }
        loading = false
    }
    // The picked networks' block rides on top, frozen as the page opened — the apps
    // page's own rule. The hand-added ones surface even when the OS names none.
    val initialTrusted = remember { trusted }
    val entries = remember(known, trusted) {
        (known.map { it.ssid } + trusted).distinct().map { ssid ->
            NetworkEntry(ssid = ssid, current = known.any { it.ssid == ssid && it.current })
        }
    }
    val selected = remember(entries) { entries.filter { it.ssid in initialTrusted } }
    val rest = remember(entries) { entries.filter { it.ssid !in initialTrusted } }
    var pendingAdd by remember { mutableStateOf(false) }
    // Computed here, not in the list's scope: MaterialTheme doesn't reach into it.
    val outer = MaterialTheme.shapes.extraLarge
    val inner = MaterialTheme.shapes.extraSmall

    // The apps page's own report into the screen's bar.
    val listState = rememberLazyListState()
    val covered by remember(listState) {
        derivedStateOf { listState.canScrollForward || listState.canScrollBackward }
    }
    val underlaid by rememberContentUnderBar(listState)
    ReportBarCoverage(covered, underlaid = underlaid)

    WavyPullToRefreshBox(
        isRefreshing = loading,
        onRefresh = { refreshes++ },
        modifier = Modifier.fillMaxSize(),
    ) { pullShift ->
        PagedList(
            listState = listState,
            pullShift = pullShift,
            toggle = {
                MasterSwitchRow(
                    title = stringResource(R.string.trusted_title),
                    supporting = stringResource(
                        when {
                            !enabled -> R.string.per_app_disabled
                            trusted.isEmpty() -> R.string.trusted_hint
                            else -> R.string.trusted_count
                        },
                        if (trusted.isEmpty()) 1 else trusted.size,
                    ),
                    enabled = enabled,
                    onChange = onToggleEnabled,
                )
            },
        ) {
            if (!locationGranted) {
                item(key = "permission") {
                    PermissionCard(onGrant = {
                        permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    })
                }
                item(key = "permission gap") { Spacer(modifier = Modifier.height(12.dp)) }
            }
            itemsIndexed(selected, key = { _, entry -> entry.ssid }) { index, entry ->
                SelectableRow(
                    headline = entry.ssid,
                    supporting = if (entry.current) stringResource(R.string.trusted_current) else null,
                    bypassed = entry.ssid in trusted,
                    active = enabled,
                    onToggle = { onToggle(entry.ssid) },
                    shape = sectionShape(index, selected.lastIndex, outer, inner),
                ) {
                    NetworkTile(bypassed = entry.ssid in trusted, active = enabled)
                }
            }
            if (selected.isNotEmpty() && rest.isNotEmpty()) {
                item(key = "block gap") { Spacer(modifier = Modifier.height(12.dp)) }
            }
            itemsIndexed(rest, key = { _, entry -> entry.ssid }) { index, entry ->
                SelectableRow(
                    headline = entry.ssid,
                    supporting = if (entry.current) stringResource(R.string.trusted_current) else null,
                    bypassed = entry.ssid in trusted,
                    active = enabled,
                    onToggle = { onToggle(entry.ssid) },
                    shape = sectionShape(index, rest.lastIndex, outer, inner),
                ) {
                    NetworkTile(bypassed = entry.ssid in trusted, active = enabled)
                }
            }
            // The hand-add sits at the very end: everything pickable first, the way in
            // below it all.
            if (!locationGranted || selected.isNotEmpty() || rest.isNotEmpty()) {
                item(key = "add gap") { Spacer(modifier = Modifier.height(12.dp)) }
            }
            item(key = "add network") { AddNetworkRow(onClick = { pendingAdd = true }) }
        }
    }

    if (pendingAdd) {
        AddNetworkDialog(
            onConfirm = { ssid ->
                pendingAdd = false
                onToggle(ssid)
            },
            onDismiss = { pendingAdd = false },
        )
    }
}

/** The shared page skeleton: the pull-to-refresh box, the padding law, the list. */
@Composable
private fun PagedList(
    listState: LazyListState,
    pullShift: () -> Dp,
    toggle: @Composable () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    // Rows travel under the status bar and the floating bar (fading out behind the
    // gradients) and rest clear of both thanks to the content padding. The pull pushes
    // the rows down through the top padding (see WavyPullToRefreshBox).
    val topContentPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 24.dp
    val bottomContentPadding = WindowInsets.navigationBars.asPaddingValues()
        .calculateBottomPadding() + FloatingBottomBarReservedHeight
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .screenPadding(),
        // The servers list's segmented container: 2dp gaps between rows instead of
        // dividers, each block carrying the group's outer corners on its first and last
        // rows, the small inner ones on the rest.
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = remember(
            topContentPadding,
            bottomContentPadding,
            pullShift,
        ) {
            pullShiftedPadding(topContentPadding, bottomContentPadding, pullShift)
        },
        content = {
            // The master switch, the content's own first block: it scrolls with the list
            // like every other card, riding under the status bar's fade.
            item(key = "master switch") { toggle() }
            item(key = "switch gap") { Spacer(modifier = Modifier.height(12.dp)) }
            content()
        },
    )
}

/**
 * The master switch row, both pages' first block: the toggle tile leading on the left —
 * the block mark while the list applies, the globe once the whole list stops mattering —
 * the title and its story beside it. The whole row taps.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MasterSwitchRow(
    title: String,
    supporting: String,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val poke = rememberHapticPoke()
    // Bumped in the click handler: the tile's pop keys off it, so it fires on every
    // click — the press state alone can go unnoticed on a quick tap.
    var taps by remember { mutableIntStateOf(0) }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainer.copy(alpha = LocalSurfaceOpacity.current),
        contentColor = colors.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                ) {
                    poke()
                    taps++
                    onChange(!enabled)
                }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The toggle tile, leading: the accent while the list applies, the
            // crossfading mark in the middle.
            val toggleResting = if (enabled) colors.primary else colors.secondaryContainer
            val toggleContainer by animateColorAsState(
                targetValue = if (pressed) {
                    lerp(toggleResting, colors.onSurface, 0.1f)
                } else {
                    toggleResting
                },
                animationSpec = motion.defaultEffectsSpec(),
                label = "master switch tile container",
            )
            val toggleContent by animateColorAsState(
                targetValue = if (enabled) colors.onPrimary else colors.onSecondaryContainer,
                animationSpec = motion.defaultEffectsSpec(),
                label = "master switch tile content",
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = toggleContainer,
                contentColor = toggleContent,
                modifier = Modifier
                    .size(48.dp)
                    .pressBounce(pressed, taps),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Crossfade(
                        targetState = if (enabled) R.drawable.ic_block else R.drawable.ic_public,
                        animationSpec = motion.defaultEffectsSpec(),
                        label = "master switch tile icon",
                    ) { icon ->
                        Icon(
                            painter = painterResource(icon),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                )
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One pickable row, the server row's twin: the leading slot bare — the app's own icon, or
 * a network's mark — the name with a supporting line under it. A picked entry wears the
 * accent — it stays out of the tunnel — and the tap trades the row between the two
 * states. While the master switch is off the rows all go quiet: dimmed, untappable, the
 * picks' block still sitting where it froze.
 */
@Composable
private fun SelectableRow(
    headline: String,
    supporting: String?,
    bypassed: Boolean,
    active: Boolean,
    onToggle: () -> Unit,
    shape: Shape,
    leading: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val poke = rememberHapticPoke()
    val containerColor by animateColorAsState(
        targetValue = (if (bypassed && active) colors.primaryContainer else colors.surfaceContainer)
            .copy(alpha = LocalSurfaceOpacity.current),
        animationSpec = motion.defaultEffectsSpec(),
        label = "pickable row container",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            !active -> colors.onSurface.copy(alpha = 0.38f)
            bypassed -> colors.onPrimaryContainer
            else -> colors.onSurface
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "pickable row content",
    )
    val supportingColor by animateColorAsState(
        targetValue = when {
            !active -> colors.onSurfaceVariant.copy(alpha = 0.38f)
            bypassed -> colors.onPrimaryContainer.copy(alpha = 0.78f)
            else -> colors.onSurfaceVariant
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "pickable row supporting",
    )
    Surface(
        onClick = {
            poke()
            onToggle()
        },
        enabled = active,
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = headline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                if (supporting != null) {
                    Text(
                        text = supporting,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            leadingContent = { leading() },
            // No trailing mark: the picked row already wears the accent.
            colors = ListItemDefaults.colors(
                // The row's Surface already paints the container; painting it here again
                // stacked the translucent color twice, leaving these rows denser than
                // the rest of the app's.
                containerColor = Color.Transparent,
                headlineColor = contentColor,
                supportingColor = supportingColor,
            ),
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The app's own icon, bare: launcher icons already carry their shape and padding, so no
 * tile behind it. It drains to grayscale — the row's quiet state — while the master
 * switch is off.
 */
@Composable
private fun AppIcon(app: AppEntry, active: Boolean) {
    val motion = MaterialTheme.motionScheme
    val saturation by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = motion.defaultEffectsSpec(),
        label = "app icon saturation",
    )
    val iconAlpha by animateFloatAsState(
        targetValue = if (active) 1f else 0.38f,
        animationSpec = motion.defaultEffectsSpec(),
        label = "app icon alpha",
    )
    Image(
        bitmap = app.icon,
        contentDescription = null,
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix().apply { setToSaturation(saturation) },
        ),
        modifier = Modifier
            .size(40.dp)
            .alpha(iconAlpha),
    )
}

/** The permission's own card, standing in for the network list until it is granted. */
@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainer.copy(alpha = LocalSurfaceOpacity.current),
        contentColor = colors.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.trusted_permission_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
            )
            Text(
                text = stringResource(R.string.trusted_permission_text),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            TextButton(onClick = onGrant) {
                Text(stringResource(R.string.trusted_permission_grant))
            }
        }
    }
}

/**
 * The network's own tile, the server row's leading square: the mark on a 40dp tonal
 * container — the accent while trusted, the secondary otherwise, dimmed with the rest
 * while the master switch is off.
 */
@Composable
private fun NetworkTile(bypassed: Boolean, active: Boolean) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val container by animateColorAsState(
        targetValue = when {
            !active -> colors.secondaryContainer.copy(alpha = 0.38f)
            bypassed -> colors.primary
            else -> colors.secondaryContainer
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "network tile container",
    )
    val content by animateColorAsState(
        targetValue = when {
            !active -> colors.onSecondaryContainer.copy(alpha = 0.38f)
            bypassed -> colors.onPrimary
            else -> colors.onSecondaryContainer
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "network tile content",
    )
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = container,
        contentColor = content,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_wifi),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * The hand-add row, the list's last block: the plus on the network tile's own square,
 * the network's name typed in on confirm — trusted straight away.
 */
@Composable
private fun AddNetworkRow(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val poke = rememberHapticPoke()
    Surface(
        onClick = {
            poke()
            onClick()
        },
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainer.copy(alpha = LocalSurfaceOpacity.current),
        contentColor = colors.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.secondaryContainer,
                contentColor = colors.onSecondaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Text(
                text = stringResource(R.string.trusted_add),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
            )
        }
    }
}

/** The hand-add dialog: a name field, "Add" answering only once it holds something. */
@Composable
private fun AddNetworkDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trusted_add_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.trusted_add_field)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.trusted_add_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.trusted_add_cancel))
            }
        },
    )
}

/** One block's row corners: the outer ones at the block's own ends, the inner between. */
private fun sectionShape(
    index: Int,
    lastIndex: Int,
    outer: CornerBasedShape,
    inner: CornerBasedShape,
): Shape = RoundedCornerShape(
    topStart = if (index == 0) outer.topStart else inner.topStart,
    topEnd = if (index == 0) outer.topEnd else inner.topEnd,
    bottomStart = if (index == lastIndex) outer.bottomStart else inner.bottomStart,
    bottomEnd = if (index == lastIndex) outer.bottomEnd else inner.bottomEnd,
)

/** Every launchable app except ourselves — we're already outside the tunnel. */
private fun installedApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .mapNotNull { info ->
            val pkg = info.activityInfo.packageName
            if (pkg == context.packageName) return@mapNotNull null
            AppEntry(
                packageName = pkg,
                label = info.loadLabel(pm).toString(),
                icon = info.loadIcon(pm).toBitmap().asImageBitmap(),
            )
        }
        .distinctBy { it.packageName }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
}

/**
 * The Wi-Fi the device sits on, by name — the only network the OS names for a modern
 * app: the saved list went away with Android 10 (getConfiguredNetworks answers any
 * targetSdk 29+ app with an empty list, forever), and the scan names whatever happens
 * to be in range, saved or not. The current network plus the hand-added ones are the
 * whole pickable set. Needs the location permission, or the name reads as unknown.
 */
private fun knownNetworks(context: Context): List<NetworkEntry> = try {
    @Suppress("DEPRECATION")
    val current = context.getSystemService(WifiManager::class.java)
        ?.connectionInfo
        ?.ssid
        ?.removeSurrounding("\"")
        ?.takeIf { ssid -> ssid.isNotEmpty() && ssid != "<unknown ssid>" }
    listOfNotNull(current).map { ssid -> NetworkEntry(ssid = ssid, current = true) }
} catch (e: Exception) {
    // A missing permission or an OS that won't share reads as no networks at all.
    emptyList()
}
