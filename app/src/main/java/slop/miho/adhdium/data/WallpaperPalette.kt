package slop.miho.adhdium.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The three seed hues pulled from the picked wallpaper — a hand-rolled Monet. A weighted
 * histogram of the saturated pixels' hues; the three strongest bins, neighbors silenced so
 * the seeds stay a hue family apart. Null while no wallpaper is picked (and after it's
 * dropped), an empty list for a picture with no saturated color to speak of.
 */
object WallpaperPalette {
    /** The wallpaper's seed hues as a shared state; seeds itself from disk on first read. */
    fun hues(context: Context): StateFlow<List<Float>?> {
        if (!seeded) {
            seeded = true
            refresh(context)
        }
        return huesState.asStateFlow()
    }

    /** Recomputes after the wallpaper changed: a tiny decode, off the main thread. */
    fun refresh(context: Context) {
        scope.launch { huesState.value = extract(context) }
    }

    private val huesState = MutableStateFlow<List<Float>?>(null)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var seeded = false

    private fun extract(context: Context): List<Float>? {
        val file = WallpaperRegistry.current(context) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PALETTE_DIM) sample *= 2
            inSampleSize = sample
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        // Every saturated, mid-lightness pixel votes its hue bin, louder the more saturated
        // it is — the grays and near-blacks/whites stay out of it.
        val bins = FloatArray(HUE_BINS)
        val hsl = FloatArray(3)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                ColorUtils.colorToHSL(bitmap.getPixel(x, y), hsl)
                if (hsl[1] < 0.15f || hsl[2] < 0.15f || hsl[2] > 0.85f) continue
                val bin = (hsl[0] / 360f * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
                bins[bin] += hsl[1]
            }
        }
        // The heaviest bins one by one; a picked bin's neighbors are silenced so the seeds
        // don't land a single hue family apart.
        val seeds = mutableListOf<Float>()
        val weights = bins.copyOf()
        repeat(SEEDS) {
            val best = weights.indices.maxByOrNull { weights[it] } ?: return@repeat
            if (weights[best] <= 0f) return@repeat
            seeds.add(best * 360f / HUE_BINS)
            for (neighbor in best - 1..best + 1) {
                if (neighbor in weights.indices) weights[neighbor] = 0f
            }
        }
        return seeds
    }

    /** A histogram never needs more pixels than this out of the wallpaper. */
    private const val PALETTE_DIM = 192

    /** Hue bins of 30°: coarse enough to group a family, fine enough to tell families apart. */
    private const val HUE_BINS = 12

    /** How many seed colors the palette offers. */
    private const val SEEDS = 3
}
