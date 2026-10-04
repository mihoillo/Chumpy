package slop.miho.aurora.ui.welcome

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import slop.miho.aurora.R
import slop.miho.aurora.data.UiSettings
import slop.miho.aurora.data.Accent
import slop.miho.aurora.ui.components.SubmitFieldRow
import slop.miho.aurora.ui.components.rememberHapticPoke
import slop.miho.aurora.ui.main.isSmallScreen
import slop.miho.aurora.ui.theme.AuroraTheme

/**
 * The first-run screen, in the home layout's own clothes: the app's mark and the waving
 * hand sit where the home greeting does, left-aligned at the middle of the page, and the
 * way in is pinned to the bottom as one toggle-like row — the field, and the tile that
 * is a paste while the field is empty and the arrow once it holds something. Whatever
 * the field holds picks the door: an http(s) link becomes a subscription, anything
 * else — a vmess/vless/trojan/ss/socks/hy2 link or a whole WireGuard conf — rides the
 * import. Nothing inserts itself into the field; the paste is the tile's own job.
 */
@Composable
fun WelcomeScreen(
    onAddSubscription: (url: String, name: String?, onDone: (String?) -> Unit) -> Unit,
    onImport: (text: String, onDone: (String?) -> Unit) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }

    // The snackbar shows whatever the add/import reported; "—" only if it said nothing.
    fun reportError(error: String?) {
        if (error != null) scope.launch { snackbarHostState.showSnackbar(error) }
    }

    // The file door: whatever the picker hands over rides the import as if pasted —
    // a WireGuard conf, a share link, a raw config, all the same parsers.
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                }.getOrNull()
            }
            if (text == null) {
                reportError(context.getString(R.string.import_read_failed))
            } else {
                onImport(text) { error -> reportError(error) }
            }
        }
    }

    // The one door: a link starting with http is a subscription; everything else the
    // import's own parsers know rides the import.
    fun submit() {
        val text = url.trim()
        if (text.isEmpty() || adding) return
        adding = true
        if (text.startsWith("http")) {
            onAddSubscription(text, null) { error ->
                adding = false
                reportError(error)
            }
        } else {
            onImport(text) { error ->
                adding = false
                reportError(error)
            }
        }
    }

    // No insets from the Scaffold: the card carries its own bottom law — navbar, or the
    // keyboard over it, never both stacked — and nothing else pads the bottom.
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Landscape cutouts on the sides stay clear, as on Home.
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = if (isSmallScreen()) 16.dp else 32.dp),
        ) {
            Box(Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(),
                ) {
                    // The app's face beside the hand, both the same size.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_aurora_mark),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp),
                        )
                        WavingHand(modifier = Modifier.size(56.dp))
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = stringResource(R.string.welcome_title),
                        style = MaterialTheme.typography.displayMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.welcome_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // The greeting keeps its distance from the cards below.
            Spacer(Modifier.height(24.dp))

            // The way in, two doors stacked: the field card, then the file door under
            // it. The bottom inset is the navbar or the keyboard, never both: open, the
            // cards ride straight on the keys.
            Column(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    // A breath between the cards and whatever sits under them — the
                    // navbar or the keyboard alike.
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The one button, the toggle row's own shape: the field standing in for
                // the title, the tile for the switch — paste while empty, the arrow to
                // go once it holds something.
                WelcomeSubmitRow(
                    value = url,
                    onValueChange = { url = it },
                    onSubmit = ::submit,
                    modifier = Modifier.fillMaxWidth(),
                )
                ImportConfigRow(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                )
            }
        }
    }
}

/**
 * The file door, the settings' toggle rows' own shape and backdrop: the open folder's
 * tile standing where the switch's would, the title beside it — one tap and the
 * system's picker hands the file's text over to the import.
 */
@Composable
private fun ImportConfigRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val poke = rememberHapticPoke()
    Surface(
        onClick = {
            poke()
            onClick()
        },
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_folder_open),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Text(
                text = stringResource(R.string.import_config),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/**
 * The one button, laid out like the settings' toggle rows and on the same tonal
 * backdrop: the field-and-tile pair is the shared [SubmitFieldRow] — a paste into the
 * empty field, the arrow to carry what it holds through the door.
 */
@Composable
private fun WelcomeSubmitRow(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        SubmitFieldRow(
            value = value,
            onValueChange = onValueChange,
            onSubmit = onSubmit,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** The wavy hand from Google Fonts, waving the way the emoji did: a couple of swings
 *  pivoting at the wrist, then a rest, forever. The mark's fingers point to the
 *  top-right corner — its wrist sits at the opposite one, and the swing turns about
 *  that corner, sweeping the fingers across the top. */
@Composable
private fun WavingHand(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "wave")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 2400
                0f at 0
                16f at 160
                -8f at 320
                16f at 480
                -4f at 640
                10f at 800
                0f at 1000 using LinearEasing
                0f at 2400
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "wave rotation",
    )
    Icon(
        painter = painterResource(R.drawable.ic_waving_hand),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.graphicsLayer {
            rotationZ = rotation
            transformOrigin = TransformOrigin(0.22f, 0.78f)
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun WelcomeScreenPreview() {
    AuroraTheme(settings = UiSettings(accent = Accent.VIOLET)) {
        WelcomeScreen(
            onAddSubscription = { _, _, onDone -> onDone(null) },
            onImport = { _, onDone -> onDone(null) },
        )
    }
}
