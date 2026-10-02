package slop.miho.adhdium.ui.components

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import android.view.Display
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Keeps the system bars' icon color in step with what actually sits behind them. The stock
 * edge-to-edge setup follows the SYSTEM's dark mode, but this app carries its own theme
 * toggle that may disagree with it — and over a wallpaper the bars hang on the picture, not
 * on the theme at all. So every screen that owns a window reports here: a dark backdrop
 * means light icons, a light one dark icons, for both bars at once.
 */
@Composable
fun SystemBarsIconsDark(dark: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, dark) {
        val window = view.findWindow()
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            // "Light appearance" means dark icons — right over a light backdrop, wrong over
            // a dark one.
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
        onDispose { }
    }
}

/**
 * Asks the display for its high gear while [active] — the page-switch animations drive their
 * own frames, and on a dynamically-refreshed screen the system doesn't always clock that on
 * its own and keeps idling at the low rate. The request is the highest rate the current
 * resolution offers; on a fixed-rate display that's just the rate already showing, so the
 * call costs nothing. Control hands back to the system (rate 0) the moment [active] drops.
 *
 * From Android 15 on the request is a per-view frame-rate category instead: it votes for the
 * high gear without touching the window's layout params, so it costs no relayout round trip
 * and no forced mode switch — on the window path every page switch paid both, twice. Several
 * requests may overlap (a page switch inside a page switch); a count keeps the vote standing
 * until the last one lets go.
 */
@Composable
fun RefreshRateRequest(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, active) {
        if (!active) return@DisposableEffect onDispose { }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            view.holdHighFrameRate()
            onDispose { view.releaseHighFrameRate() }
        } else {
            val window = view.findWindow()
            window?.boostRefreshRate()
            onDispose { window?.relaxRefreshRate() }
        }
    }
}

/** Live high-rate requests per view, so overlapping ones don't cancel each other early. */
private val highRateHolds = java.util.WeakHashMap<View, Int>()

@androidx.annotation.RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private fun View.holdHighFrameRate() {
    val holds = highRateHolds[this] ?: 0
    highRateHolds[this] = holds + 1
    if (holds == 0) requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private fun View.releaseHighFrameRate() {
    val holds = (highRateHolds[this] ?: 1) - 1
    if (holds > 0) {
        highRateHolds[this] = holds
    } else {
        highRateHolds.remove(this)
        requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
    }
}

/** Unwraps theme wrappers to the owning activity's window, or null outside an activity. */
private fun View.findWindow(): Window? {
    var context = context
    while (context is ContextWrapper) {
        if (context is Activity) return context.window
        context = context.baseContext
    }
    return null
}

private fun Window.boostRefreshRate() {
    val display = currentDisplay() ?: return
    val current = display.mode
    // Only rates reachable at the current resolution: a rate that demands a mode switch to
    // another resolution is no favor to anyone mid-animation.
    val best = display.supportedModes
        .filter { mode ->
            mode.physicalWidth == current.physicalWidth &&
                mode.physicalHeight == current.physicalHeight
        }
        .maxByOrNull { it.refreshRate }?.refreshRate
        ?: return
    if (best <= display.refreshRate + RATE_EPSILON) return
    attributes = attributes.apply { preferredRefreshRate = best }
}

private fun Window.relaxRefreshRate() {
    if (attributes.preferredRefreshRate == 0f) return
    attributes = attributes.apply { preferredRefreshRate = 0f }
}

private fun Window.currentDisplay(): Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    context.display
} else {
    @Suppress("DEPRECATION")
    windowManager.defaultDisplay
}

private const val RATE_EPSILON = 0.01f
