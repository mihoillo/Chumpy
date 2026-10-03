package slop.miho.aurora.ui.perapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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
                        bypassed = connection.bypassedApps,
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
    bypassed: Set<String>,
    onToggle: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // The launcher's own list — labels and icons of every launchable app. Both loads are
    // package-manager work: off the main thread, once, then the rows are pure recomposition.
    val apps by produceState(initialValue = emptyList<AppEntry>()) {
        value = withContext(Dispatchers.IO) { installedApps(context) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .screenPadding()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 24.dp),
        ) {
            Text(
                text = if (bypassed.isEmpty()) {
                    stringResource(R.string.per_app_hint)
                } else {
                    stringResource(R.string.per_app_count, bypassed.size)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            val outer = MaterialTheme.shapes.extraLarge
            val inner = MaterialTheme.shapes.extraSmall
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                // The servers list's segmented container: 2dp gaps between rows instead
                // of dividers, the first and last rows carrying the group's outer
                // corners, the rest the small inner ones.
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                        FloatingBottomBarReservedHeight,
                ),
            ) {
                itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                    val first = index == 0
                    val last = index == apps.lastIndex
                    AppRow(
                        app = app,
                        bypassed = app.packageName in bypassed,
                        onToggle = { onToggle(app.packageName) },
                        shape = RoundedCornerShape(
                            topStart = if (first) outer.topStart else inner.topStart,
                            topEnd = if (first) outer.topEnd else inner.topEnd,
                            bottomStart = if (last) outer.bottomStart else inner.bottomStart,
                            bottomEnd = if (last) outer.bottomEnd else inner.bottomEnd,
                        ),
                    )
                }
            }
        }
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
 * One app's row, the server row's twin: the app's own icon bare in the leading slot (no
 * backdrop — launcher icons carry their own shape), the name with the package under it.
 * A picked app wears the accent — it's on the blacklist, staying out of the tunnel — and
 * the tap trades the row between the two states.
 */
@Composable
private fun AppRow(
    app: AppEntry,
    bypassed: Boolean,
    onToggle: () -> Unit,
    shape: Shape,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val poke = rememberHapticPoke()
    val containerColor by animateColorAsState(
        targetValue = (if (bypassed) colors.primaryContainer else colors.surfaceContainer)
            .copy(alpha = LocalSurfaceOpacity.current),
        animationSpec = motion.defaultEffectsSpec(),
        label = "app row container",
    )
    val contentColor by animateColorAsState(
        targetValue = if (bypassed) colors.onPrimaryContainer else colors.onSurface,
        animationSpec = motion.defaultEffectsSpec(),
        label = "app row content",
    )
    val supportingColor by animateColorAsState(
        targetValue = if (bypassed) {
            colors.onPrimaryContainer.copy(alpha = 0.78f)
        } else {
            colors.onSurfaceVariant
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "app row supporting",
    )
    Surface(
        onClick = {
            poke()
            onToggle()
        },
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
                // and padding, so no tile behind it.
                Image(
                    bitmap = app.icon,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
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
