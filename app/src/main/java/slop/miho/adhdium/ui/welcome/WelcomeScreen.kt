package slop.miho.adhdium.ui.welcome

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import slop.miho.adhdium.R
import slop.miho.adhdium.data.UiSettings
import slop.miho.adhdium.data.Accent
import slop.miho.adhdium.ui.main.screenMargin
import slop.miho.adhdium.ui.theme.AdhdiumTheme

@Composable
fun WelcomeScreen(onImport: suspend (Uri) -> String?) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    val errorTemplate = stringResource(R.string.welcome_import_failed)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val error = onImport(uri)
            importing = false
            if (error != null) snackbarHostState.showSnackbar(errorTemplate.format(error))
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = screenMargin()),
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
            Button(
                // Config files have no reliable MIME type, so accept anything and validate on import.
                onClick = { picker.launch(arrayOf("*/*")) },
                enabled = !importing,
                modifier = Modifier
                    .height(56.dp)
                    .widthIn(min = 200.dp),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_file_upload),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.welcome_import), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
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
    AdhdiumTheme(settings = UiSettings(accent = Accent.VIOLET)) {
        WelcomeScreen(onImport = { null })
    }
}
