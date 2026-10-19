package slop.miho.chumpy.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.exp
import kotlinx.coroutines.flow.collectLatest
import slop.miho.chumpy.ui.theme.LocalUiSettings

/**
 * [PullToRefreshBox] with the MD3 expressive wavy linear indicator instead of the default
 * spinner. While pulling, the bar fills up and its wave grows with the pull distance; a single
 * haptic kick marks the release threshold. While a refresh is in flight, the wave runs along
 * the bar like a regular indeterminate loader.
 *
 * The gesture itself is the stock Material 3 pull-to-refresh. When [enabled] is false (for
 * example, no tunnel connection) the bar stays a flat line, the threshold stays silent and a
 * release past it folds the bar back with three short "denied" haptic kicks instead of
 * calling [onRefresh]; [onRefreshDenied] fires alongside them so the page can call attention
 * to itself (for example by shaking its notice).
 *
 * [content] receives the pull's current shift: how far the rows should give way under the
 * indicator. Lists fold it into their top padding with [pullShiftedPadding].
 */
@Composable
fun WavyPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    state: PullToRefreshState = rememberPullToRefreshState(),
    enabled: Boolean = true,
    threshold: Dp = PullToRefreshDefaults.PositionalThreshold,
    contentAlignment: Alignment = Alignment.TopStart,
    onRefreshDenied: (() -> Unit)? = null,
    content: @Composable BoxScope.(pullShift: () -> Dp) -> Unit,
) {
    // The kicks honor the app-wide haptic-feedback setting, like every other poke.
    val hapticsOn = LocalUiSettings.current.hapticFeedback
    val deniedKicks = rememberHapticDeniedKicks()
    val thresholdPoke = rememberHapticSoftPoke()
    val slow = LocalUiSettings.current.slowAnimations
    val density = LocalDensity.current
    val isRefreshingState = rememberUpdatedState(isRefreshing)

    // The rows' give, in pixels. Under the finger it follows the pull through a rubber band —
    // generous at first, ever heavier the further it stretches. Let go, it no longer follows
    // the gesture's own flat fold-back: it springs to its resting place on a bouncy spring
    // that overshoots a touch past it and settles, so the release reads as a snap back.
    val shift = remember { Animatable(0f) }
    // Whether a finger is on the screen, watched directly: the gesture's own state can't
    // tell a release apart (past the threshold it just holds still until the refresh ends),
    // and the bounce has to start the moment the finger lifts, not after the reload.
    val fingerDown = remember { mutableStateOf(false) }
    LaunchedEffect(state, density) {
        val restingRefresh = with(density) { PullRefreshingShift.toPx() }
        val stretch = with(density) { PullStretchReach.toPx() }
        // Emits (following, px): under the finger the band's give, once let go only the
        // resting target. The gesture keeps animating its own fraction after the release, but
        // the settle pair doesn't change with it, so the spring isn't restarted every frame —
        // a fresh animation stands still on its first frame, and restarting it frame after
        // frame would hold the rows in place until the gesture went quiet.
        snapshotFlow {
            val fraction = state.distanceFraction
            val refreshing = isRefreshingState.value
            // Only a live pull follows the finger; a touch while a refresh is in flight (the
            // gesture holds the fraction at the threshold then) leaves the rows at rest.
            if (fingerDown.value && !refreshing && fraction > 0f) {
                true to stretch * (1f - exp(-fraction * PULL_STRETCH_RATE))
            } else {
                false to if (refreshing) restingRefresh else 0f
            }
        }
            .collectLatest { (following, px) ->
                if (following) {
                    shift.snapTo(px)
                } else {
                    // Restarted only as the refresh starts or ends; the Animatable carries its
                    // velocity into the new target, so a retarget mid-bounce stays one
                    // continuous motion.
                    shift.animateTo(
                        targetValue = px,
                        animationSpec = spring(
                            dampingRatio = PULL_RELEASE_DAMPING,
                            stiffness = if (slow) PULL_RELEASE_STIFFNESS / 2f else PULL_RELEASE_STIFFNESS,
                        ),
                    )
                }
            }
    }
    val pullShift = remember(shift, density) { { with(density) { shift.value.toDp() } } }

    // The gesture itself always works so the content still bounces and folds back; only the
    // wave, the kicks and the actual refresh are gated by `enabled`.
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            if (enabled) {
                onRefresh()
            } else {
                onRefreshDenied?.invoke()
                deniedKicks()
            }
        },
        modifier = modifier.pointerInput(Unit) {
            // Initial pass, nothing consumed: the box just peeks at every touch before the
            // gesture and the list see it.
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    fingerDown.value = event.changes.any { it.pressed }
                }
            }
        },
        state = state,
        contentAlignment = contentAlignment,
        indicator = {
            WavyPullIndicator(
                state = state,
                isRefreshing = isRefreshing,
                enabled = enabled,
                shiftPx = { shift.value },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
        threshold = threshold,
        content = {
            // The rows drag down with the pull (see the rubber band above) through the list's
            // own top padding (see pullShiftedPadding), never by moving the viewport: a
            // translated viewport would open a screen-tall gap under the wave and drag its
            // clip edge down with it, cutting the rows mid-screen. While a refresh is in
            // flight the rows rest a little way down, just clear of the bar.
            content(pullShift)
            // No fade of the content's own: the only gradient in play is the status bar's,
            // the shell's scrim — pinned in the bar, hidden with a wallpaper. A gradient
            // riding the pulled-down content would surface mid-screen.
        },
    )

    // One soft poke when the finger crosses the threshold while dragging, so it is clear
    // the pull is deep enough and letting go will refresh. The soft pulse — a quarter of
    // the tap poke's amplitude — not a feedback constant and not the full poke either:
    // CONFIRM reads as a firm double thud on a good motor, and even the plain poke sits
    // heavy where the gesture only wants a tick.
    LaunchedEffect(state, enabled, hapticsOn) {
        var pastThreshold = false
        snapshotFlow { state.distanceFraction >= 1f && !state.isAnimating }
            .collect { crossed ->
                if (crossed && !pastThreshold && enabled && hapticsOn) {
                    thresholdPoke()
                }
                pastThreshold = crossed
            }
    }
}

@Composable
private fun WavyPullIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    enabled: Boolean,
    shiftPx: () -> Float,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    Box(
        modifier
            // Sits right under the status bar, above the scrolling content.
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 12.dp)
            .graphicsLayer {
                // Fades in with the pull and back out as the bar folds away.
                alpha = state.distanceFraction.coerceIn(0f, 1f)
                // Hangs halfway between its home and the rows' top as they give way, so the
                // pull opens even air above and below it; the bounce's dip under rest leaves
                // it at home.
                translationY = (shiftPx() / 2f).coerceAtLeast(0f)
            },
    ) {
        AnimatedContent(
            targetState = isRefreshing,
            transitionSpec = {
                fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.defaultEffectsSpec())
            },
            label = "pull indicator phase",
        ) { refreshing ->
            if (refreshing) {
                // Refresh in flight: the wave runs along the bar like a regular loader.
                LinearWavyProgressIndicator()
            } else {
                // Pulling: the bar fills with the pull distance and the wave grows with it.
                // Without a connection the line stays flat and just folds back on release.
                LinearWavyProgressIndicator(
                    progress = { state.distanceFraction },
                    amplitude = { if (enabled) state.distanceFraction.coerceIn(0f, 1f) else 0f },
                )
            }
        }
    }
}

/**
 * A list's content padding with the pull's [shift] added on top. The shift is read when the
 * list measures, not when it composes, so a drag only re-lays the rows out frame by frame
 * instead of recomposing the whole screen. Remember it per padding pair.
 */
fun pullShiftedPadding(top: Dp, bottom: Dp, shift: () -> Dp): PaddingValues =
    PullShiftedPadding(top, bottom, shift)

@Stable
private class PullShiftedPadding(
    private val top: Dp,
    private val bottom: Dp,
    private val shift: () -> Dp,
) : PaddingValues {
    // The release spring overshoots past rest, lifting the rows a hair above their place;
    // floored so the padding can never turn negative.
    override fun calculateTopPadding(): Dp = (top + shift()).coerceAtLeast(0.dp)
    override fun calculateBottomPadding(): Dp = bottom
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = 0.dp
    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = 0.dp
}

/**
 * Where the rows rest while a refresh is in flight: the content already starts clear of the
 * wave at rest, so this is only a breath of air under the bar.
 */
private val PullRefreshingShift = 16.dp

/** The rubber band's reach: the give the rows approach, never reach, however far the pull. */
private val PullStretchReach = 120.dp

/** How quickly the band stiffens: at the release threshold the rows have given ~45% of the reach. */
private const val PULL_STRETCH_RATE = 0.6f

/** The release spring: a visible overshoot that settles in one soft bounce. */
private const val PULL_RELEASE_DAMPING = 0.5f
private const val PULL_RELEASE_STIFFNESS = 380f
