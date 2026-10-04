package slop.miho.aurora.ui.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import slop.miho.aurora.R
import slop.miho.aurora.data.SettingsStore
import slop.miho.aurora.ui.components.ActionTile
import slop.miho.aurora.ui.theme.AuroraTheme

/**
 * The camera half of the import: a full-screen viewfinder that reads one QR code and
 * hands its text back to the caller in the result intent ([KEY_TEXT]) — the same string
 * the file picker would have read out of a file, routed the same way. The camera
 * permission is asked for here and only here, on first launch; a denial simply closes
 * the scanner, nothing else in the app ever needs it.
 */
class ScannerActivity : ComponentActivity() {

    companion object {
        /** The decoded text, handed back to the caller in the result intent. */
        const val KEY_TEXT = "text"
    }

    /** One code settles the whole screen: the first decode wins, the rest race for nothing. */
    private var settled = false

    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) startScanning() else finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startScanning()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startScanning() {
        setContent {
            val ui by SettingsStore.settings(this).collectAsStateWithLifecycle()
            AuroraTheme(settings = ui) {
                ScannerScreen(
                    onClose = { finish() },
                    onResult = ::report,
                )
            }
        }
    }

    private fun report(text: String) {
        if (settled) return
        settled = true
        setResult(RESULT_OK, Intent().putExtra(KEY_TEXT, text))
        finish()
    }
}

/**
 * The viewfinder: the camera fills the screen edge to edge, a rounded frame marks where
 * to aim, the close tile keeps its corner. The frames decode on a worker thread — only
 * the latest frame is ever queued — and the first hit hops back to the main thread and
 * settles the activity.
 */
@Composable
private fun ScannerScreen(
    onClose: () -> Unit,
    onResult: (String) -> Unit,
) {
    val context = LocalContext.current
    val owner = context as LifecycleOwner
    val main = remember { ContextCompat.getMainExecutor(context) }
    // The frames decode off the main thread, one at a time — the latest frame wins.
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    LaunchedEffect(previewView, executor) {
        // Camera start-up is the one thing that can fail here (no camera, busy, buggy
        // provider); a failure leaves the viewfinder black with the close tile alive.
        runCatching {
            val provider = suspendCancellableCoroutine { cont ->
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    try {
                        cont.resume(future.get())
                    } catch (error: Exception) {
                        cont.resumeWithException(error)
                    }
                }, main)
            }
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { frame ->
                val text = decodeQr(frame)
                frame.close()
                if (text != null) main.execute { onResult(text) }
            }
            provider.unbindAll()
            provider.bindToLifecycle(
                owner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        ActionTile(
            icon = R.drawable.ic_close,
            label = stringResource(R.string.scan_close),
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp),
        )
        // The aim frame: a rounded window the size a code wants to be in the viewfinder.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(264.dp)
                .clip(RoundedCornerShape(32.dp))
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(32.dp),
                ),
        )
        // The hint rides on a translucent chip so it reads on any feed the camera shows.
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.scan_hint),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * The Y plane straight from the frame: zxing finds the code's finder patterns itself, so
 * the sensor's rotation never matters — only the pixels do. TRY_HARDER costs a little
 * per frame and buys the skewed, small, half-lit codes a real screen throws at a camera.
 */
private fun decodeQr(frame: ImageProxy): String? = runCatching {
    val plane = frame.planes[0]
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val source = PlanarYUVLuminanceSource(
        data,
        plane.rowStride,
        frame.height,
        0,
        0,
        frame.width,
        frame.height,
        false,
    )
    QRCodeReader().decode(
        BinaryBitmap(HybridBinarizer(source)),
        mapOf(DecodeHintType.TRY_HARDER to true),
    ).text
}.getOrNull()
