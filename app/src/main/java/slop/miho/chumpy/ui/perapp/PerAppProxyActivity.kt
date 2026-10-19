package slop.miho.chumpy.ui.perapp

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import slop.miho.chumpy.R
import slop.miho.chumpy.data.ConnectionStore
import slop.miho.chumpy.data.SettingsStore
import slop.miho.chumpy.ui.components.SystemBarsIconsDark
import slop.miho.chumpy.ui.components.WavyPullToRefreshBox
import slop.miho.chumpy.ui.components.pressBounce
import slop.miho.chumpy.ui.components.pullShiftedPadding
import slop.miho.chumpy.ui.components.rememberHapticPoke
import slop.miho.chumpy.ui.main.FloatingBottomBarReservedHeight
import slop.miho.chumpy.ui.main.PillDestination
import slop.miho.chumpy.ui.main.SubScreenBottomBar
import slop.miho.chumpy.ui.main.recordBehindBar
import slop.miho.chumpy.ui.main.rememberContentUnderBar
import slop.miho.chumpy.ui.main.screenPadding
import slop.miho.chumpy.ui.theme.ChumpyTheme
import slop.miho.chumpy.ui.theme.LocalDarkTheme
import slop.miho.chumpy.ui.wallpapers.LocalBarCoverage
import slop.miho.chumpy.ui.wallpapers.LocalBarUnderlay
import slop.miho.chumpy.ui.wallpapers.LocalSurfaceOpacity
import slop.miho.chumpy.ui.wallpapers.ReportBarCoverage

/**
 * The per-app proxy, a one-page deep-dive screen in the wallpapers' footsteps: the
 * bypass list — picked apps ignore the tunnel and connect directly (v2rayNG's
 * arrangement). The system back gesture and the bar's back button both simply finish
 * this activity.
 */
class PerAppProxyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val ui by SettingsStore.settings(this).collectAsStateWithLifecycle()
            val connection by ConnectionStore.settings(this).collectAsStateWithLifecycle()
            ChumpyTheme(settings = ui) {
                // The window's own bars answer to the theme, as in the main shell — there
                // is no wallpaper here for them to hang on instead.
                SystemBarsIconsDark(dark = LocalDarkTheme.current)
                Surface(color = MaterialTheme.colorScheme.surface) {
                    PerAppProxyScreen(
                        frost = ui.navigationBarBlur,
                        appsEnabled = connection.perAppEnabled,
                        bypassedApps = connection.bypassedApps,
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
                        onBack = ::finish,
                    )
                }
            }
        }
    }
}

/** The bar's one destination, the apps page. */
private enum class PerAppPage(override val icon: Int, override val label: Int) : PillDestination {
    APPS(R.drawable.ic_apps, R.string.per_app_title),
}

/** One launchable app, icon included, loaded off the main thread. */
private data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap,
)

@Composable
private fun PerAppProxyScreen(
    frost: Boolean,
    appsEnabled: Boolean,
    bypassedApps: Set<String>,
    onToggleAppsEnabled: (Boolean) -> Unit,
    onToggleApp: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                AppsPage(
                    enabled = appsEnabled,
                    bypassed = bypassedApps,
                    onToggleEnabled = onToggleAppsEnabled,
                    onToggle = onToggleApp,
                )
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
            selected = PerAppPage.APPS,
            onSelect = { },
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
                    supporting = when {
                        !enabled -> stringResource(R.string.per_app_disabled)
                        bypassed.isEmpty() -> stringResource(R.string.per_app_hint)
                        else -> pluralStringResource(
                            R.plurals.per_app_count,
                            bypassed.size,
                            bypassed.size,
                        )
                    },
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
 * One pickable row, the server row's twin: the leading slot bare — the app's own icon —
 * the name with a supporting line under it. A picked entry wears the accent — it stays
 * out of the tunnel — and the tap trades the row between the two states. While the
 * master switch is off the rows all go quiet: dimmed, untappable, the picks' block
 * still sitting where it froze.
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
                icon = iconAtRowSize(info.loadIcon(pm)),
            )
        }
        .distinctBy { it.packageName }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
}

/**
 * The icon at the size the row can use, never the size it shipped with. The plain
 * conversion keeps the drawable's own pixels, and a broken launcher icon — an OEM
 * theme's or a stray apk's — can come out thousands of pixels square: a 6977×6977
 * one (194MB) crashed a OnePlus 8T the moment its row drew, the hardware canvas
 * refusing anything past 100MB. Anything over the cap rides down proportionally;
 * anything under it stands as it shipped.
 */
private fun iconAtRowSize(drawable: Drawable): ImageBitmap {
    val w = drawable.intrinsicWidth
    val h = drawable.intrinsicHeight
    if (w <= 0 || h <= 0 || (w <= MAX_APP_ICON_PX && h <= MAX_APP_ICON_PX)) {
        return drawable.toBitmap().asImageBitmap()
    }
    val scale = MAX_APP_ICON_PX.toFloat() / maxOf(w, h)
    return drawable.toBitmap(
        (w * scale).toInt().coerceAtLeast(1),
        (h * scale).toInt().coerceAtLeast(1),
    ).asImageBitmap()
}

/** The row's 40dp of icon, plus headroom for the densest screens. */
private const val MAX_APP_ICON_PX = 192
