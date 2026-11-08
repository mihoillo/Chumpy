package slop.miho.chumpy.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import slop.miho.chumpy.R
import slop.miho.chumpy.sub.PhoneImportServer

@Composable
fun PhoneImportAction(initialFocus: Boolean = false, wallpaper: Boolean = false, onImport: (String, (String?) -> Unit) -> Unit) {
    val television = LocalConfiguration.current.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK ==
        android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    if (!television || !slop.miho.chumpy.ui.wallpapers.LocalPageOnStage.current) return
    var open by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(initialFocus, television) {
        if (initialFocus && television) {
            androidx.compose.runtime.withFrameNanos { }
            requester.requestFocus()
        }
    }
    TextButton(onClick = { open = true }, modifier = Modifier.focusRequester(requester)
        .focusRing(circular = true, inset = true, gap = 1.dp)) {
        Text(stringResource(if (wallpaper) R.string.phone_wallpaper_action else R.string.phone_import_action))
    }
    if (open) PhoneImportDialog(onDismiss = { open = false }, wallpaper = wallpaper, onImport = onImport)
}

@Composable
private fun PhoneImportDialog(onDismiss: () -> Unit, wallpaper: Boolean, onImport: (String, (String?) -> Unit) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val maxContentHeight = with(density) { (windowSize.height * 0.65f).toDp() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dismiss by rememberUpdatedState(onDismiss)
    var server by remember { mutableStateOf<PhoneImportServer?>(null) }
    var failed by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycle) {
        val session = slop.miho.chumpy.sub.PhoneImportSession()
        var job: kotlinx.coroutines.Job? = null
        fun start() {
            if (job != null) return
            job = scope.launch {
                val created = withContext(Dispatchers.IO) {
                    runCatching {
                        session.open {
                            val page = resources.openRawResource(if (wallpaper) R.raw.phone_wallpaper else R.raw.phone_import)
                                .bufferedReader().use { it.readText() }
                            PhoneImportServer.open(context.applicationContext, page, image = wallpaper)
                        }
                    }.getOrNull()
                }
                server = created
                failed = created == null
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                    session.close()
                    job?.cancel()
                    dismiss()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        onDispose { lifecycle.removeObserver(observer); session.close(); job?.cancel() }
    }
    val receiver = server
    val state = if (receiver != null) receiver.state.collectAsStateWithLifecycle().value else null
    val qrUrl = state?.takeUnless { it.expired || it.pending != null }?.url
    val qrPixels = with(density) { 176.dp.roundToPx() }.coerceIn(256, 640)
    val qr by produceState<Bitmap?>(null, qrUrl, qrPixels) {
        value = null
        val url = qrUrl ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, qrPixels, qrPixels,
                mapOf(EncodeHintType.MARGIN to 4))
            val pixels = IntArray(matrix.width * matrix.height) { index ->
                if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
            Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        }
    }
    val wide = with(density) { windowSize.width.toDp() } >= 700.dp
    AlertDialog(
        modifier = Modifier.then(if (wide) Modifier.width(760.dp) else Modifier)
            .controllerWindowInput { if (!importing) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = !wide),
        onDismissRequest = { if (!importing) onDismiss() },
        title = { Text(stringResource(if (wallpaper) R.string.phone_wallpaper_action else R.string.phone_import_action)) },
        text = {
            Column(Modifier.heightIn(max = maxContentHeight)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    failed -> Text(stringResource(R.string.phone_import_no_network))
                    state?.expired == true -> Text(stringResource(R.string.phone_import_expired))
                    importing -> Text(stringResource(R.string.phone_import_importing))
                    state?.pending != null -> Text(stringResource(if (wallpaper) R.string.phone_wallpaper_received else R.string.phone_import_received))
                    state != null -> ReceiverDetails(state, qr, wide, wallpaper)
                    else -> Text(stringResource(R.string.phone_import_starting))
                }
                error?.let { Text(it) }
            }
        },
        confirmButton = {
            val pending = state?.pending
            if (pending != null) TextButton(enabled = !importing, onClick = {
                importing = true
                error = null
                onImport(pending) { message ->
                    importing = false
                    if (message == null) onDismiss() else error = message
                }
            }, modifier = Modifier.focusRing(circular = true, inset = true, gap = 1.dp)) { Text(stringResource(R.string.phone_import_confirm)) }
        },
        dismissButton = {
            TextButton(enabled = !importing, onClick = onDismiss, modifier = Modifier.focusRing(circular = true, inset = true, gap = 1.dp)) {
                Text(stringResource(R.string.subscription_cancel))
            }
        },
    )
}

@Composable
private fun ReceiverDetails(state: PhoneImportServer.State, qr: Bitmap?, wide: Boolean, wallpaper: Boolean) {
    val picture: @Composable () -> Unit = {
        qr?.let { Image(it.asImageBitmap(), stringResource(if (wallpaper) R.string.phone_wallpaper_qr else R.string.phone_import_qr),
            Modifier.size(176.dp).background(Color.White).padding(4.dp)) }
    }
    val instructions: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (wallpaper) R.string.phone_wallpaper_instructions else R.string.phone_import_instructions))
            Text(state.url.substringBefore('#'))
            Text(stringResource(R.string.phone_import_code, state.pin))
            Text(stringResource(R.string.phone_import_local))
        }
    }
    if (wide) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        picture()
        Column(Modifier.weight(1f)) { instructions() }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { picture() }
        instructions()
    }
}
