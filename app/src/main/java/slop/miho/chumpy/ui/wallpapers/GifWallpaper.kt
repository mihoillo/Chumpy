package slop.miho.chumpy.ui.wallpapers

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

@Composable
internal fun rememberGifWallpaper(wallpaper: DecodedWallpaper): GifWallpaper? {
    val file = wallpaper.gifFile ?: return null
    if (Build.VERSION.SDK_INT < 28) return null
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    if (lifecycleState != Lifecycle.State.RESUMED) return null

    return key(wallpaper) {
        val gif by produceState<GifWallpaper?>(null) {
            value = withContext(Dispatchers.IO) { GifWallpaper.decode(file) }
        }
        gif?.let { animation ->
            DisposableEffect(animation) {
                animation.start()
                onDispose { animation.stop() }
            }
        }
        gif
    }
}

internal class GifWallpaper private constructor(private val drawable: Drawable) : Drawable.Callback {
    private val animation = drawable as Animatable
    private val handler = Handler(Looper.getMainLooper())
    private val frame = mutableIntStateOf(0)
    private var desaturation = 0f

    fun start() {
        drawable.callback = this
        animation.start()
    }

    fun stop() {
        animation.stop()
        drawable.callback = null
        handler.removeCallbacksAndMessages(drawable)
    }

    override fun invalidateDrawable(who: Drawable) {
        frame.intValue++
    }

    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
        handler.postAtTime(what, who, `when`)
    }

    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
        handler.removeCallbacks(what, who)
    }

    fun DrawScope.drawFrame(origin: IntOffset, dimensions: IntSize, desaturate: Float) {
        val saturationLoss = if (desaturate > 0.001f) desaturate else 0f
        if (saturationLoss != desaturation) {
            desaturation = saturationLoss
            drawable.colorFilter = if (saturationLoss == 0f) null else {
                ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1f - saturationLoss) })
            }
        }
        drawable.setBounds(origin.x, origin.y, origin.x + dimensions.width, origin.y + dimensions.height)
        // Observe frame changes in the draw phase so advancing a GIF redraws only the backdrop.
        frame.intValue
        drawIntoCanvas { drawable.draw(it.nativeCanvas) }
    }

    companion object {
        @RequiresApi(28)
        fun decode(file: File): GifWallpaper? {
            val drawable = try {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val width = info.size.width
                    val height = info.size.height
                    val longest = maxOf(width, height)
                    if (longest > MAX_GIF_DIMENSION) {
                        val scale = MAX_GIF_DIMENSION.toDouble() / longest
                        decoder.setTargetSize(
                            (width * scale).roundToInt().coerceAtLeast(1),
                            (height * scale).roundToInt().coerceAtLeast(1),
                        )
                    }
                }
            } catch (_: IOException) {
                return null
            }
            return (drawable as? AnimatedImageDrawable)?.let {
                it.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                GifWallpaper(it)
            }
        }

        private const val MAX_GIF_DIMENSION = 1080
    }
}
