package slop.miho.aurora.ui.wallpapers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import slop.miho.aurora.AuroraApp
import slop.miho.aurora.core.TunnelState
import slop.miho.aurora.data.DimTone
import slop.miho.aurora.data.WallpaperPalette
import slop.miho.aurora.data.WallpaperRegistry
import slop.miho.aurora.ui.theme.LocalUiSettings
import slop.miho.aurora.ui.theme.backdropScheme
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/**
 * The shared wallpaper machinery: everything needed to render the picked picture behind a
 * screen — the backdrop recipe, the decoding and the treatment math — so every screen that
 * carries the picture shows the very same one the very same way.
 */

/**
 * The opacity the menus' surfaces — the groups, cards and bars — wear over the wallpaper:
 * the picked setting while a wallpaper is behind them, fully opaque otherwise (transparency
 * without a backdrop beneath is just noise). The icon backings are exempt: they stay opaque
 * whatever this says, so the icons always sit on a solid stage. Provided by whichever shell
 * composes the wallpaper layer; every surface below reads it instead of threading a
 * parameter through them all.
 */
val LocalSurfaceOpacity = staticCompositionLocalOf { 1f }

/**
 * Whether a wallpaper is behind the screen at all. Surfaces that fade further than the
 * surface-opacity setting — unavailable rows, say — only do so over a picture: without one
 * there's nothing to show through, and they stay opaque. Provided by the shell that
 * composes the wallpaper layer.
 */
val LocalHasWallpaper = staticCompositionLocalOf { false }

/**
 * Whether the wallpaper behind the screen is dimmed dark enough that bare text — the words
 * lying right on the picture, no card beneath — recolors into the dark scheme's roles.
 * False without a wallpaper: the plain scheme colors are already right on the flat surface.
 * Provided by the shell that composes the wallpaper layer.
 */
val LocalBackdropDark = staticCompositionLocalOf { false }

/**
 * Whether scrolling content currently sits under the screen's floating bar, reported upward
 * by the screens: the bar reads it (see [barSurfaceOpacity]) and turns opaque over the
 * rows passing beneath it, whatever the surface-opacity setting says — see-through over
 * text is unreadable, transparency is for when the bar hangs over empty space. The shell
 * composes one shared state here; every scrolling screen reports into it.
 */
val LocalBarCoverage = staticCompositionLocalOf<MutableState<Boolean>> { mutableStateOf(false) }

/**
 * Whether content truly reaches in under the floating bar — a real share of the bar's
 * height, not just a scrollable page — reported upward next to [LocalBarCoverage]. The main
 * bar steps its color a tone up while it holds. Pages that scroll above a fixed slot (Home)
 * never get beneath the bar at all, so they report false here whatever their scroll says.
 */
val LocalBarUnderlay = staticCompositionLocalOf<MutableState<Boolean>> { mutableStateOf(false) }

/**
 * Whether the floating bar follows the surface-opacity setting like every other element:
 * true on the main screen and the wallpapers preview, where it hangs over the gently dimmed
 * picture. Everywhere else it stays a step more opaque than the elements around it — the
 * bar floats above them and must not dissolve into the picture. Provided by the shell that
 * composes the bar; [barSurfaceOpacity] reads it.
 */
val LocalBarCanFade = staticCompositionLocalOf { false }

/**
 * A screen's report of whether its content currently sits under the floating bar. Callers
 * pass a value derived from their scroll state (derivedStateOf keeps the boolean from
 * recomposing the screen on every scroll pixel), so the bar learns only of the flips.
 * [underlaid] is the stricter [LocalBarUnderlay] report, the same as [covered] unless given.
 */
@Composable
fun ReportBarCoverage(covered: Boolean, underlaid: Boolean = covered) {
    val coverage = LocalBarCoverage.current
    val underlay = LocalBarUnderlay.current
    SideEffect {
        coverage.value = covered
        underlay.value = underlaid
    }
}

/**
 * The opacity the floating bars wear: fully opaque while content scrolls beneath them, the
 * picked surface opacity on the screens where the bar may fade, and — everywhere else —
 * halfway between the setting and opaque, a step more solid than the elements around it.
 * Animated between the values so the switches don't snap.
 */
@Composable
fun barSurfaceOpacity(): Float {
    val covered = LocalBarCoverage.current.value
    val setting = LocalSurfaceOpacity.current
    val target = when {
        !LocalBarCanFade.current -> setting + (1f - setting) * 0.5f
        covered -> 1f
        else -> setting
    }
    val opacity by animateFloatAsState(
        targetValue = target,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "bar surface opacity",
    )
    return opacity
}

/**
 * A container color the menus wear over the wallpaper: the scheme color at the surface
 * opacity. For animated colors that rest at transparent, multiply the color's own alpha
 * instead — a plain copy() would materialize Transparent's hidden black.
 */
@Composable
fun Color.withSurfaceOpacity(): Color = copy(alpha = LocalSurfaceOpacity.current)

/**
 * The zoom's reach away from Home. Blur hides the zoom's pixel stretching and swallows
 * detail anyway, so the two ride together: no blur keeps the pinch, a deeper pull stretches
 * the picture further — at full blur the reach doubles.
 */
fun wallpaperZoomTarget(blurSetting: Float): Float =
    1f + (WALLPAPER_ZOOM - 1f) * (1f + blurSetting)

/**
 * Whether the dimmed wallpaper behind bare text is too dark to read: the scrim's tone
 * blended over the picture's average luminance, tested against the midpoint. The answer
 * drives the adaptive recolor into the dark scheme's roles.
 */
fun backdropTooDark(wallpaperLuminance: Float, dim: Float, scrim: Color): Boolean =
    (1f - dim) * wallpaperLuminance + dim * scrim.luminance() < 0.5f

/**
 * The decoded wallpaper for screens that merely show it: re-decoded whenever the pick
 * changes. The palette flow refreshes on every set and delete, so it doubles as the change
 * signal — the wallpaper screen, which edits the picture, keys off its own bump instead.
 */
@Composable
internal fun rememberDecodedWallpaper(): DecodedWallpaper? {
    val context = LocalContext.current
    val hues by WallpaperPalette.hues(context).collectAsStateWithLifecycle()
    return produceState<DecodedWallpaper?>(null, hues) {
        val file = WallpaperRegistry.current(context)
        value = withContext(Dispatchers.IO) { file?.let(::decodeWallpaper) }
    }.value
}

/**
 * The wallpaper picture with its whole treatment — the zoom scale, the blur, the dim scrim,
 * the offline grayscale drain — as one recipe. Rendered full-screen it is the layer behind
 * everything; every screen that carries the wallpaper composes this same recipe.
 *
 * Every treatment value is a lambda, read only inside the layer and draw blocks: the page
 * transitions animate them each frame, and read here they cost a redraw of this one layer
 * instead of recomposing whichever shell hosts it.
 */
@Composable
internal fun WallpaperBackdrop(
    image: ImageBitmap,
    scale: () -> Float,
    blur: () -> Dp,
    scrim: Color,
    scrimAlpha: () -> Float,
    desaturate: () -> Float = { 0f },
    offsetX: () -> Float = { 0f },
    offsetY: () -> Float = { 0f },
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        // The picture is drawn by hand at its true rect — the crop's cover factor with the
        // page zoom and the user's zoom multiplied in, centered, then slid by the pan —
        // and this node is the crop window. The content moves inside the window, so a pan
        // pulls real pixels into view; scaling the node's own painting instead would slide
        // a screen-shaped snapshot over emptiness, the picture's off-screen part gone.
        Box(
            Modifier
                .matchParentSize()
                // Modifier.blur's rectangle recipe — clamped edges, clipped to the bounds —
                // set from inside the layer block so the radius may animate without
                // recomposition.
                .graphicsLayer {
                    val radius = blur().toPx()
                    renderEffect = if (radius > 0f) BlurEffect(radius, radius, TileMode.Clamp) else null
                    clip = true
                }
                .drawBehind {
                    val scale = scale()
                    val offsetX = offsetX()
                    val offsetY = offsetY()
                    val desaturate = desaturate()
                    val crop = maxOf(size.width / image.width, size.height / image.height)
                    val w = image.width * crop * scale
                    val h = image.height * crop * scale
                    // The pan, normalized to the slack the scaled picture leaves on each
                    // axis and clamped to it: at or above the cover the picture never
                    // uncovers the window's sides; below it — the whole-picture zoom-out —
                    // there is no slack, the picture sits centered in its letterbox.
                    val slackX = (w - size.width) / 2f
                    val slackY = (h - size.height) / 2f
                    val tx = if (slackX > 0f) (offsetX * slackX).coerceIn(-slackX, slackX) else 0f
                    val ty = if (slackY > 0f) (offsetY * slackY).coerceIn(-slackY, slackY) else 0f
                    // The offline grayscale lives on the picture alone: the theme's colors
                    // and everything drawn over it keep theirs, so the drain is a filter on
                    // this very draw, not a scheme change.
                    val grayscale = if (desaturate > 0.001f) {
                        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1f - desaturate) })
                    } else {
                        null
                    }
                    drawImage(
                        image = image,
                        dstOffset = IntOffset(
                            ((size.width - w) / 2f + tx).roundToInt(),
                            ((size.height - h) / 2f + ty).roundToInt(),
                        ),
                        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                        colorFilter = grayscale,
                        filterQuality = FilterQuality.High,
                    )
                },
        )
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val alpha = scrimAlpha()
                    if (alpha > 0.001f) drawRect(scrim.copy(alpha = alpha))
                },
        )
    }
}

/**
 * How far the wallpaper picture has drained to grayscale: fully drained while the tunnel is
 * down with the setting on, fully colored otherwise, animated between the two so the color
 * washes in as the VPN connects. Read from the app process's own tunnel link, so any screen
 * carrying the backdrop sees it without a view model of its own. Handed out as the state
 * itself, so the backdrop reads it at draw time and the wash doesn't recompose the screen.
 */
@Composable
internal fun offlineDesaturation(): State<Float> {
    val settings = LocalUiSettings.current
    val status by AuroraApp.tunnelClient.status.collectAsStateWithLifecycle()
    return animateFloatAsState(
        targetValue = if (settings.grayscaleOffline && status.state != TunnelState.UP) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "offline wallpaper grayscale",
    )
}

/**
 * The scrim tone: follow the theme, or force light/dark. Both tones are the app schemes'
 * own surfaces — the dark theme's for darkening, the light theme's (Monet's background)
 * for lightening — so the dimmed picture keeps the tonal feel instead of a flat black or
 * white wash.
 */
@Composable
internal fun scrimColor(tone: DimTone): Color {
    val darkSurface = backdropScheme(darkTheme = true).surface
    val lightSurface = backdropScheme(darkTheme = false).surface
    return when (tone) {
        DimTone.AUTO -> if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) darkSurface else lightSurface
        DimTone.LIGHT -> lightSurface
        DimTone.DARK -> darkSurface
    }
}

/** Decodes the picked image, downscaled so a camera shot doesn't eat memory. */
internal fun decodeWallpaper(file: File): DecodedWallpaper? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DECODE_DIM) sample *= 2
        inSampleSize = sample
    }
    var bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
    // Camera photos store their rotation as EXIF metadata, which BitmapFactory ignores —
    // without this the picture decodes sideways and the fill-crop on it reads as broken.
    // The import itself copies the file's bytes untouched; this is purely a decode-time
    // correction of what the camera meant.
    bitmap = rotateByExif(file, bitmap)
    // Pictures saved with transparent or ghosted padding — sticker PNGs, rounded exports,
    // a scanner's faint frame — would wear that padding as picture: the screen-filling
    // crop would both cut the real content and leave see-through strips at the screen's
    // edges. Trim to the content first.
    bitmap = trimTransparent(bitmap)
    // The average luminance drives the adaptive text color: sampled on a sparse grid,
    // still plenty after the decode's own downsampling. Fully transparent pixels don't
    // count — they aren't picture.
    var sum = 0.0
    var samples = 0
    val step = maxOf(1, minOf(bitmap.width, bitmap.height) / LUMINANCE_GRID)
    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            val pixel = Color(bitmap.getPixel(x, y))
            if (pixel.alpha > 0f) {
                sum += pixel.luminance()
                samples++
            }
            x += step
        }
        y += step
    }
    val luminance = if (samples > 0) (sum / samples).toFloat() else 0.5f
    // The power-of-two sampling can leave a camera shot at up to 16 MP: far past anything a
    // screen shows even zoomed, and every frame of a transition samples it. Scaled down to
    // a pixel budget, then handed to the GPU as a hardware bitmap — one upload, no second
    // copy on the app's heap.
    val pixels = bitmap.width.toLong() * bitmap.height
    if (pixels > MAX_DECODE_PIXELS) {
        val shrink = kotlin.math.sqrt(MAX_DECODE_PIXELS.toDouble() / pixels)
        bitmap = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * shrink).roundToInt().coerceAtLeast(1),
            (bitmap.height * shrink).roundToInt().coerceAtLeast(1),
            true,
        )
    }
    bitmap = bitmap.copy(Bitmap.Config.HARDWARE, false) ?: bitmap
    return DecodedWallpaper(bitmap.asImageBitmap(), luminance)
}

/**
 * The bitmap cropped to its visible content. Any fully transparent margins the file
 * carries — padding around a sticker, the empty corners of a rounded export — would
 * otherwise ride along as picture: the crop counts them and both eats into the real
 * edges and leaves see-through strips at the screen's. Opaque bitmaps pass straight
 * through untouched.
 */
private fun trimTransparent(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    // The bounds of every pixel carrying real ink — a hair above fully clear (a soft
    // export edge, a compression ghost) reads as a see-through band, so it counts as
    // empty too.
    var left = w
    var top = h
    var right = -1
    var bottom = -1
    var index = 0
    for (y in 0 until h) {
        for (x in 0 until w) {
            if (pixels[index] ushr 24 >= TRIM_ALPHA) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
            index++
        }
    }
    // Opaque, fully transparent or content already flush with the edges: as it came.
    if (left > right || top > bottom) return bitmap
    if (left == 0 && top == 0 && right == w - 1 && bottom == h - 1) return bitmap
    return Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
}

/**
 * The picture turned the way the camera meant it. JPEGs from a phone camera are usually
 * stored landscape with the rotation in EXIF; decoding without honoring it stands them
 * on their side. Anything without the tag — most PNGs and WebPs — passes straight through.
 */
private fun rotateByExif(file: File, bitmap: Bitmap): Bitmap {
    val orientation = try {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    } catch (_: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }
    val rotation = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (rotation == 0f) return bitmap
    val matrix = Matrix().apply { postRotate(rotation) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/** The decoded picture plus its average luminance, for the adaptive text color. */
internal class DecodedWallpaper(val image: ImageBitmap, val luminance: Float)

/** How far the wallpaper grows away from Home, before the blur setting deepens it. */
private const val WALLPAPER_ZOOM = 1.08f

/** The blur slider's full pull, mapped onto the wallpaper image. */
internal val MAX_WALLPAPER_BLUR = 32.dp

/** The farthest the user's pinch can magnify the wallpaper. */
internal const val MAX_WALLPAPER_ZOOM = 4f

/** Screens never need more pixels than this out of the wallpaper. */
private const val MAX_DECODE_DIM = 2048

/** The decoded picture's pixel budget: ample for a phone screen under the full pinch zoom. */
private const val MAX_DECODE_PIXELS = 8_000_000L

/** Samples along the bitmap's shorter side when averaging the luminance. */
private const val LUMINANCE_GRID = 64

/** Alpha below this reads as empty: a soft export edge or a compression ghost, not ink. */
private const val TRIM_ALPHA = 16
