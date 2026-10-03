package slop.miho.aurora.ui.perapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import slop.miho.aurora.R
import slop.miho.aurora.data.ConnectionStore
import slop.miho.aurora.data.SettingsStore
import slop.miho.aurora.ui.components.WavyPullToRefreshBox
import slop.miho.aurora.ui.components.pressBounce
import slop.miho.aurora.ui.components.pullShiftedPadding
import slop.miho.aurora.ui.components.rememberHapticPoke
import slop.miho.aurora.ui.main.FloatingBottomBarReservedHeight
import slop.miho.aurora.ui.main.PillDestination
import slop.miho.aurora.ui.main.SubScreenBottomBar
import slop.miho.aurora.ui.main.screenPadding
import slop.miho.aurora.ui.theme.AuroraTheme
import slop.miho.aurora.ui.wallpapers.LocalSurfaceOpacity

/**
 * The per-app proxy settings, a deep-dive screen in the wallpapers' footsteps: every
 * launchable app in one list, each row a toggle — picked apps ignore the tunnel and
 * connect directly (v2rayNG's bypass arrangement). The system back gesture and the bar's
 * back button both simply finish this activity.
 */
class PerAppProxyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val ui by SettingsStore.settings(this).collectAsStateWithLifecycle()
            val connection by ConnectionStore.settings(this).collectAsStateWithLifecycle()
            AuroraTheme(settings = ui) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    PerAppProxyScreen(
                        enabled = connection.perAppEnabled,
                        bypassed = connection.bypassedApps,
                        onToggleEnabled = { enabled ->
                            ConnectionStore.update(this) { settings ->
                                settings.copy(perAppEnabled = enabled)
                            }
                        },
                        onToggle = { pkg ->
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

/** The bar's single destination: this screen's own pill. */
private enum class PerAppDestination(override val icon: Int, override val label: Int) : PillDestination {
    APPS(R.drawable.ic_apps, R.string.per_app_title),
}

/** One launchable app, icon included, loaded off the main thread. */
private data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap,
)

@Composable
fun PerAppProxyScreen(
    enabled: Boolean,
    bypassed: Set<String>,
    onToggleEnabled: (Boolean) -> Unit,
    onToggle: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
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
    // The master switch's own height, measured as laid out — the list rests clear of the
    // block whatever the fonts and scale make of its two lines.
    var toggleBlockHeight by remember { mutableStateOf(80.dp) }

    val surface = MaterialTheme.colorScheme.surface
    Box(modifier = modifier.fillMaxSize()) {
        // The wave grows with the pull while the list re-queries; the first load rides
        // the same wave, so the wait is never a blank page.
        WavyPullToRefreshBox(
            isRefreshing = loading,
            onRefresh = { refreshes++ },
            modifier = Modifier.fillMaxSize(),
        ) { pullShift ->
            // Rows travel under the toggle above and the floating bar below (fading out
            // behind the gradients) and rest clear of both thanks to the content padding.
            // The pull pushes the rows down through the top padding (see
            // WavyPullToRefreshBox).
            val topContentPadding = WindowInsets.statusBars.asPaddingValues()
                .calculateTopPadding() + 24.dp + toggleBlockHeight + 24.dp
            val bottomContentPadding = WindowInsets.navigationBars.asPaddingValues()
                .calculateBottomPadding() + FloatingBottomBarReservedHeight
            val outer = MaterialTheme.shapes.extraLarge
            val inner = MaterialTheme.shapes.extraSmall
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .screenPadding(),
                // The servers list's segmented container: 2dp gaps between rows instead
                // of dividers, each block carrying the group's outer corners on its
                // first and last rows, the small inner ones on the rest.
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = remember(
                    topContentPadding,
                    bottomContentPadding,
                    pullShift,
                ) {
                    pullShiftedPadding(topContentPadding, bottomContentPadding, pullShift)
                },
            ) {
                itemsIndexed(selected, key = { _, app -> app.packageName }) { index, app ->
                    AppRow(
                        app = app,
                        bypassed = app.packageName in bypassed,
                        active = enabled,
                        onToggle = { onToggle(app.packageName) },
                        shape = sectionShape(index, selected.lastIndex, outer, inner),
                    )
                }
                if (selected.isNotEmpty() && rest.isNotEmpty()) {
                    item(key = "block gap") { Spacer(modifier = Modifier.height(12.dp)) }
                }
                itemsIndexed(rest, key = { _, app -> app.packageName }) { index, app ->
                    AppRow(
                        app = app,
                        bypassed = app.packageName in bypassed,
                        active = enabled,
                        onToggle = { onToggle(app.packageName) },
                        shape = sectionShape(index, rest.lastIndex, outer, inner),
                    )
                }
            }
        }

        // The master switch rides over the list, and the rows dissolve behind the
        // gradient under it instead of meeting a hard edge — the same fade the shell
        // draws below the floating bar.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .screenPadding()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 24.dp),
        ) {
            PerAppToggleRow(
                enabled = enabled,
                count = bypassed.size,
                onChange = onToggleEnabled,
                modifier = Modifier.onSizeChanged { size ->
                    with(density) { toggleBlockHeight = size.height.toDp() }
                },
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .drawBehind {
                        drawRect(
                            Brush.verticalGradient(
                                listOf(surface.copy(alpha = 0.97f), surface.copy(alpha = 0f)),
                            ),
                        )
                    },
            )
        }

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

        SubScreenBottomBar(
            items = PerAppDestination.entries.toList(),
            selected = PerAppDestination.APPS,
            onSelect = {},
            onBack = onBack,
            backdrop = null,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * The per-app proxy's master switch, the screen's own first block: the toggle tile
 * leading on the left — the block mark while the bypass list applies, the globe once the
 * whole list stops mattering — the title and its story beside it. The whole row taps.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PerAppToggleRow(
    enabled: Boolean,
    count: Int,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
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
        modifier = modifier.fillMaxWidth(),
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
            // The toggle tile, leading: the accent while the bypass list applies, the
            // crossfading mark in the middle — the block mark while on, the globe once
            // the whole list stops mattering.
            val toggleResting = if (enabled) colors.primary else colors.secondaryContainer
            val toggleContainer by animateColorAsState(
                targetValue = if (pressed) {
                    lerp(toggleResting, colors.onSurface, 0.1f)
                } else {
                    toggleResting
                },
                animationSpec = motion.defaultEffectsSpec(),
                label = "per-app toggle tile container",
            )
            val toggleContent by animateColorAsState(
                targetValue = if (enabled) colors.onPrimary else colors.onSecondaryContainer,
                animationSpec = motion.defaultEffectsSpec(),
                label = "per-app toggle tile content",
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
                        label = "per-app toggle tile icon",
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
                    text = stringResource(R.string.per_app_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                )
                Text(
                    text = when {
                        !enabled -> stringResource(R.string.per_app_disabled)
                        count == 0 -> stringResource(R.string.per_app_hint)
                        else -> stringResource(R.string.per_app_count, count)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
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

/**
 * One app's row, the server row's twin: the app's own icon bare in the leading slot (no
 * backdrop — launcher icons carry their own shape), the name with the package under it.
 * A picked app wears the accent — it's on the blacklist, staying out of the tunnel — and
 * the tap trades the row between the two states. While the master switch is off the rows
 * all go quiet: dimmed, untappable, the picks' block still sitting where it froze.
 */
@Composable
private fun AppRow(
    app: AppEntry,
    bypassed: Boolean,
    active: Boolean,
    onToggle: () -> Unit,
    shape: Shape,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val poke = rememberHapticPoke()
    val containerColor by animateColorAsState(
        targetValue = (if (bypassed && active) colors.primaryContainer else colors.surfaceContainer)
            .copy(alpha = LocalSurfaceOpacity.current),
        animationSpec = motion.defaultEffectsSpec(),
        label = "app row container",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            !active -> colors.onSurface.copy(alpha = 0.38f)
            bypassed -> colors.onPrimaryContainer
            else -> colors.onSurface
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "app row content",
    )
    val supportingColor by animateColorAsState(
        targetValue = when {
            !active -> colors.onSurfaceVariant.copy(alpha = 0.38f)
            bypassed -> colors.onPrimaryContainer.copy(alpha = 0.78f)
            else -> colors.onSurfaceVariant
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "app row supporting",
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
                    text = app.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Text(
                    text = app.packageName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = {
                // The app's own icon, bare: launcher icons already carry their shape
                // and padding, so no tile behind it. It drains to grayscale — the row's
                // quiet state — while the master switch is off.
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
            },
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
