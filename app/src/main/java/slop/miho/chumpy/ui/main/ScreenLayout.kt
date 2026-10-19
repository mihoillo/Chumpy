package slop.miho.chumpy.ui.main

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import slop.miho.chumpy.ui.theme.LocalUiSettings

/** Below this window width (dp) the app counts the screen as small. */
private const val SMALL_SCREEN_WIDTH_DP = 400

/**
 * Whether the window is a small, narrow one: there the side margins tighten to MD3's
 * compact 16dp, and the classic navigation bar becomes the default. The user can force it
 * either way (see UiSettings.smallScreen); otherwise the width decides.
 */
@Composable
fun isSmallScreen(): Boolean = LocalUiSettings.current.smallScreen ?: smallScreenByWidth()

/** The width's own verdict on [isSmallScreen], whatever the user forced. */
@Composable
fun smallScreenByWidth(): Boolean = LocalConfiguration.current.screenWidthDp < SMALL_SCREEN_WIDTH_DP

/** The classic navigation bar's resolved state: the user's pick, or the screen's default. */
@Composable
fun classicNavigationBar(): Boolean =
    LocalUiSettings.current.classicNavigationBar ?: isSmallScreen()

/** The gap between the screen's side edges and the content: 24dp, 16dp on small screens. */
@Composable
fun screenMargin(): Dp = if (isSmallScreen()) 16.dp else 24.dp

/** Side margins shared by all top-level screens. Content flows under the status bar and
 * the floating bar; the shell draws gradients that fade it out behind both. Landscape
 * display cutouts on the sides stay clear, the top one is handled by the status-bar scrim. */
@Composable
fun Modifier.screenPadding(): Modifier = this
    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
    .padding(horizontal = screenMargin())
