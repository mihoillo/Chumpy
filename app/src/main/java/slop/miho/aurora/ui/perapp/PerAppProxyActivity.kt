package slop.miho.aurora.ui.perapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import slop.miho.aurora.R
import slop.miho.aurora.data.ConnectionStore
import slop.miho.aurora.data.SettingsStore
import slop.miho.aurora.ui.components.pressBounce
import slop.miho.aurora.ui.components.rememberHapticPoke
import slop.miho.aurora.ui.main.FloatingBottomBarReservedHeight
import slop.miho.aurora.ui.main.PillDestination
import slop.miho.aurora.ui.main.SubScreenBottomBar
import slop.miho.aurora.ui.main.screenPadding
import slop.miho.aurora.ui.theme.AuroraTheme

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
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                        FloatingBottomBarReservedHeight,
                ),
            ) {
                items(apps, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        bypassed = app.packageName in bypassed,
                        onToggle = { onToggle(app.packageName) },
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
 * One app's row: the app's own icon, its name with its package under it, and the toggle
 * tile — the block mark, in the accent once the app is pulled out of the tunnel, plain
 * tonal while it still rides it. The whole row taps.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: AppEntry,
    bypassed: Boolean,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val poke = rememberHapticPoke()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    var taps by remember { mutableIntStateOf(0) }
    val toggleLabel = stringResource(R.string.per_app_bypass, app.label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Switch,
            ) {
                poke()
                taps++
                onToggle()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The app's icon at the tile's size, as-is — launcher icons already carry their
        // own shape and padding.
        Image(
            bitmap = app.icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val resting = if (bypassed) colors.primary else colors.secondaryContainer
        val container by animateColorAsState(
            targetValue = if (pressed) lerp(resting, colors.onSurface, 0.1f) else resting,
            animationSpec = motion.defaultEffectsSpec(),
            label = "app toggle tile container",
        )
        val content by animateColorAsState(
            targetValue = if (bypassed) colors.onPrimary else colors.onSecondaryContainer,
            animationSpec = motion.defaultEffectsSpec(),
            label = "app toggle tile content",
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = container,
            contentColor = content,
            modifier = Modifier
                .size(40.dp)
                .pressBounce(pressed, taps)
                .semantics { contentDescription = toggleLabel },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_block),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
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
