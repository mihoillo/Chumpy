package slop.miho.aurora.ui.welcome

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import slop.miho.aurora.R
import slop.miho.aurora.data.UiSettings
import slop.miho.aurora.data.Accent
import slop.miho.aurora.ui.main.screenMargin
import slop.miho.aurora.ui.theme.AuroraTheme

/**
 * The first-run screen: the subscription URL is the way in, a pasted link or a WireGuard
 * config the side door for servers that arrive one at a time. Until one of the two lands,
 * there is nothing to connect to, so this is all the app shows.
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
    var importOpen by remember { mutableStateOf(false) }

    // A subscription URL already on the clipboard is usually the one being added; offer it
    // as the field's starting text rather than making the user paste it themselves.
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) {
        clipboard.getText()?.text?.trim()?.takeIf { it.startsWith("http") }?.let { url = it }
    }

    // The snackbar shows whatever the add/import reported; "—" only if it said nothing.
    fun reportError(error: String?) {
        if (error != null) scope.launch { snackbarHostState.showSnackbar(error) }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = screenMargin())
                .verticalScroll(rememberScrollState())
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            WavingHand()
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.welcome_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(48.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                label = { Text(stringResource(R.string.subscription_url_field)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { if (url.isNotBlank() && !adding) {
                        adding = true
                        onAddSubscription(url.trim(), null) { error ->
                            adding = false
                            reportError(error)
                        }
                    } },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    adding = true
                    onAddSubscription(url.trim(), null) { error ->
                        adding = false
                        reportError(error)
                    }
                },
                enabled = url.isNotBlank() && !adding,
                modifier = Modifier
                    .height(56.dp)
                    .fillMaxWidth(),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_download),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.welcome_add), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { importOpen = true }) {
                Text(stringResource(R.string.welcome_import_link))
            }
        }
    }

    if (importOpen) {
        ImportLinkDialog(
            onConfirm = { text ->
                importOpen = false
                onImport(text) { error -> reportError(error) }
            },
            onDismiss = { importOpen = false },
        )
    }
}

/**
 * The side door: one text field that takes anything the parsers know — a vmess/vless/
 * trojan/ss/socks/hy2 link, or a whole WireGuard .conf pasted in. Importing through the
 * loopback isn't possible here (there is no tunnel yet), so a bare URL is fine.
 */
@Composable
private fun ImportLinkDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) {
        // A server link on the clipboard is the likeliest thing to import.
        clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("http") }
            ?.let { text = it }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_link_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.import_paste_field)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
            ) {
                Text(stringResource(R.string.import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.subscription_cancel))
            }
        },
    )
}

/** A 👋 that waves a couple of times, rests, and repeats — pivoting at the wrist. */
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
    Text(
        text = "👋",
        fontSize = 88.sp,
        modifier = modifier.graphicsLayer {
            rotationZ = rotation
            transformOrigin = TransformOrigin(0.75f, 0.9f)
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
