package slop.miho.adhdium.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import androidx.lifecycle.compose.LifecycleResumeEffect
import slop.miho.adhdium.R
import slop.miho.adhdium.core.TunnelState
import slop.miho.adhdium.core.TunnelStatus
import slop.miho.adhdium.data.FlagStyle
import slop.miho.adhdium.ui.HomeUiState
import slop.miho.adhdium.ui.components.CountryFlag
import slop.miho.adhdium.ui.components.ErrorCard
import slop.miho.adhdium.ui.components.InfoChip
import slop.miho.adhdium.ui.components.rememberHapticDeniedKicks
import slop.miho.adhdium.ui.components.rememberHapticPoke
import slop.miho.adhdium.ui.components.rememberHapticPokes
import slop.miho.adhdium.data.UiSettings
import slop.miho.adhdium.data.Accent
import slop.miho.adhdium.ui.theme.AdhdiumTheme
import slop.miho.adhdium.ui.theme.LocalDarkTheme
import slop.miho.adhdium.ui.theme.LocalUiSettings
import slop.miho.adhdium.ui.theme.backdropScheme
import slop.miho.adhdium.ui.wallpapers.ReportBarCoverage
import slop.miho.adhdium.ui.wallpapers.withSurfaceOpacity
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun HomeScreen(
    name: String?,
    status: TunnelStatus,
    home: HomeUiState,
    exitIp: String?,
    /** Whether the page is the one on screen; coming into view re-measures the ping. */
    visible: Boolean = true,
    /** Re-measures the ping now; `true` blanks the old value while the new one is on its way. */
    onRefreshPing: (showPending: Boolean) -> Unit = {},
    /** Whether the exit IP chip reads "Hidden"; a tap on the chip calls [onToggleIp]. */
    ipHidden: Boolean = false,
    onToggleIp: () -> Unit = {},
    /** The country of the exit the traffic actually leaves through, for the region card's flag. */
    exitCountry: String? = null,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenServers: () -> Unit,
    /** Opens the stats screen; a tap on the loss warning chip goes there. */
    onOpenStats: () -> Unit = {},
    // Bumped once per "this device → go connect" request from the devices screen: the page
    // opens and the connect button blinks, so the ask has a destination rather than a hope.
    // Cleared through [onAttentionHandled] once taken, so it plays exactly once.
    attentionTick: Int = 0,
    onAttentionHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val up = status.state == TunnelState.UP

    // A fresh ping every time Home comes into view — switched to, or the app brought back
    // from the background — rather than whatever the loop measured up to 15 seconds ago
    // while the user was elsewhere. Quietly: the old value stays until the new one lands.
    LifecycleResumeEffect(visible) {
        if (visible) onRefreshPing(false)
        onPauseOrDispose {}
    }

    // The connect result talks through the skin: a double poke when the tunnel comes up,
    // three grumpy kicks (the same denied-pull ones) when it fails. Only transitions fire —
    // not every recomposition — so restoring an already-up tunnel stays silent.
    val successPokes = rememberHapticPokes(2)
    val failureKicks = rememberHapticDeniedKicks()
    var seenState by remember { mutableStateOf(status.state) }
    LaunchedEffect(status.state) {
        if (status.state == TunnelState.UP && seenState == TunnelState.CONNECTING) successPokes()
        seenState = status.state
    }
    var errorAnnounced by remember { mutableStateOf(false) }
    LaunchedEffect(status.error) {
        if (status.error != null) {
            if (!errorAnnounced) {
                errorAnnounced = true
                failureKicks()
            }
        } else {
            errorAnnounced = false
        }
    }

    // Text lying bare on the background — the title and the greeting, the cards are exempt —
    // recolors into the backdrop scheme's own roles, but only when the user says so: the flip
    // is theirs, tapped on the wallpapers preview, never an automatic too-dark guess. The
    // flipped look is the scheme opposite to the theme the app is in — the dark one over a
    // light app, the light one over a dark app — so the tap always lands on visibly
    // different colors; picking the dark scheme outright would change nothing in a dark app.
    val textScheme = backdropScheme(
        darkTheme = LocalUiSettings.current.wallpaperTextDark != LocalDarkTheme.current,
    )
    val titleColor by animateColorAsState(
        targetValue = textScheme.primary,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "home title color",
    )
    val greetingColor by animateColorAsState(
        targetValue = textScheme.onSurface,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "home greeting color",
    )

    // The whole screen is bottom-anchored: everything floats just above the connect button
    // and scrolls up when there is more content than fits.
    val scroll = rememberScrollState()
    // Content is under the floating bar exactly while the greeting column outgrows its
    // slot: at rest the bar hangs over empty space, and only a scroll pushes rows beneath
    // it. The boolean is derived so the flip — not every scroll pixel — recomposes.
    val contentUnderBar by remember {
        derivedStateOf { scroll.canScrollForward || scroll.canScrollBackward }
    }
    // The column scrolls above the connect button, never in under the bar itself.
    ReportBarCoverage(contentUnderBar, underlaid = false)
    Column(
        modifier = modifier
            .fillMaxSize()
            // Landscape cutouts on the sides stay clear; the top flows under the status
            // bar, where the shell gradient fades the scrolling content out.
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            // Home breathes wider than the lists, except on small screens: there every dp
            // goes to the greeting and the chips.
            .padding(horizontal = if (isSmallScreen()) 16.dp else 32.dp),
    ) {
        Box(Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .verticalScroll(scroll),
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    color = titleColor,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = greetingText(name),
                    style = MaterialTheme.typography.displayMedium,
                    color = greetingColor,
                )

                Spacer(Modifier.height(16.dp))
                InfoChips(
                    status = status,
                    home = home,
                    exitIp = exitIp,
                    ipHidden = ipHidden,
                    onToggleIp = onToggleIp,
                    onRefreshPing = { onRefreshPing(true) },
                    onOpenStats = onOpenStats,
                )

                Spacer(Modifier.height(12.dp))
                // A failed connect takes over the region card's slot, with the same appear
                // motion; it leaves again as soon as the next attempt clears the error.
                TunnelAppear(visible = status.error != null) {
                    ErrorCard(
                        message = status.error.orEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                home.exitLabel?.let { label ->
                    // The region card still slides in when the tunnel comes up.
                    TunnelAppear(visible = up) {
                        ServerCard(
                            label = label,
                            country = exitCountry,
                            flagStyle = LocalUiSettings.current.flagStyle,
                            onClick = onOpenServers,
                        )
                    }
                }
                // Keeps the bottom-anchored content clear of the connect button.
                Spacer(Modifier.height(16.dp))
            }
        }

        // Fixed above the floating bar, not part of it.
        ConnectButton(
            state = status.state,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            attentionTick = attentionTick,
            onAttentionHandled = onAttentionHandled,
            modifier = Modifier
                .navigationBarsPadding()
                .padding(bottom = FloatingBottomBarReservedHeight + 8.dp),
        )
    }
}

@Composable
private fun greetingText(name: String?): String {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when (hour) {
        in 5..11 -> R.string.greeting_morning
        in 12..16 -> R.string.greeting_afternoon
        in 17..22 -> R.string.greeting_evening
        else -> R.string.greeting_night
    }
    return stringResource(greeting) + (name?.let { ", $it" } ?: "")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoChips(
    status: TunnelStatus,
    home: HomeUiState,
    exitIp: String?,
    ipHidden: Boolean,
    onToggleIp: () -> Unit,
    onRefreshPing: () -> Unit,
    onOpenStats: () -> Unit,
) {
    val up = status.state == TunnelState.UP
    val colors = MaterialTheme.colorScheme
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        InfoChip(
            value = stringResource(status.state.label),
            // A plain status dot: filled with the accent when connected, hollow when not.
            leading = { StatusDot(up) },
            container = (if (up) colors.secondaryContainer else colors.surfaceContainerLow)
                .withSurfaceOpacity(),
            content = if (up) colors.onSecondaryContainer else colors.onSurfaceVariant,
        )
        InfoChip(
            home.deviceName ?: stringResource(R.string.home_value_unknown),
            R.drawable.ic_device,
        )
        InfoChip(
            home.localIp ?: stringResource(R.string.home_value_unknown),
            R.drawable.ic_ethernet,
        )
        if (up) {
            InfoChip(uptimeText(home.uptimeStartMillis), R.drawable.ic_clock)
            val poke = rememberHapticPoke()
            // 200 ms is where the panel starts feeling far away: the chip goes red from
            // there, matching the loss warning's tone.
            val pingSlow = (home.pingMillis ?: 0L) >= 200L
            InfoChip(
                value = home.pingMillis
                    ?.let { stringResource(R.string.home_ping_ms, it.toInt()) }
                    ?: stringResource(R.string.home_value_pending),
                animateValue = true,
                container = if (pingSlow) {
                    colors.errorContainer.withSurfaceOpacity()
                } else {
                    colors.surfaceContainerLow.withSurfaceOpacity()
                },
                content = if (pingSlow) colors.onErrorContainer else colors.onSurface,
                // A tap re-measures on the spot: "…" until the new value lands.
                onClick = {
                    poke()
                    onRefreshPing()
                },
            )
            // Only present while the recent loss is over the warning threshold: healthy
            // traffic keeps the row quiet. A tap goes to the stats screen, where the graphs
            // behind the warning live.
            home.recentLossPercent?.let { loss ->
                InfoChip(
                    value = stringResource(R.string.stats_loss_percent, loss.roundToInt()),
                    icon = R.drawable.ic_scan_delete,
                    container = colors.errorContainer.withSurfaceOpacity(),
                    content = colors.onErrorContainer,
                    animateValue = true,
                    onClick = {
                        poke()
                        onOpenStats()
                    },
                )
            }
            // The panel sometimes answers this endpoint with a localized error page
            // instead of an address — show the shrug instead of that noise.
            // A tap hides the address behind "Hidden" (and the next one brings it back), so
            // it can stay off screen while sharing or recording it; the choice is remembered.
            InfoChip(
                value = when {
                    ipHidden -> stringResource(R.string.home_value_hidden)
                    else -> exitIp ?: stringResource(R.string.home_value_shrug)
                },
                icon = if (ipHidden) R.drawable.ic_public_off else R.drawable.ic_public,
                animateValue = true,
                onClick = {
                    poke()
                    onToggleIp()
                },
            )
        }
    }
}

/** The region card fades and slides in with the MD3 motion scheme when the tunnel
 * comes up, and shrinks away when it goes down. */
@Composable
private fun TunnelAppear(visible: Boolean, content: @Composable () -> Unit) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.defaultEffectsSpec()) +
            expandVertically(motion.defaultSpatialSpec()) +
            slideInVertically(motion.defaultSpatialSpec()) { it / 3 },
        exit = fadeOut(motion.defaultEffectsSpec()) +
            shrinkVertically(motion.defaultSpatialSpec()) +
            slideOutVertically(motion.defaultSpatialSpec()) { it / 3 },
    ) {
        content()
    }
}

@Composable
private fun StatusDot(up: Boolean) {
    val color = LocalContentColor.current
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .then(
                if (up) Modifier.background(color)
                else Modifier.border(1.5.dp, color, CircleShape),
            ),
    )
}

@Composable
private fun uptimeText(uptimeStartMillis: Long?): String {
    // Tick once a second while the tunnel is up.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(uptimeStartMillis) {
        if (uptimeStartMillis == null) return@LaunchedEffect
        while (isActive) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val uptime = uptimeStartMillis?.let { start -> (now - start).coerceAtLeast(0L) }
    return uptime?.let(::formatUptime) ?: stringResource(R.string.home_value_pending)
}

private fun formatUptime(millis: Long): String {
    val totalSeconds = millis / 1_000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
}

@Composable
private fun ServerCard(
    label: String,
    country: String?,
    flagStyle: FlagStyle,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        // Rounder than the rows it mirrors: a lone full-width card can afford the soft
        // extra-large corners, while its icon tile stays squarer (the servers' 12dp).
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainer.withSurfaceOpacity(),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            // The same inset all around the icon tile, so the tile sits centered in its
            // corner-to-text run instead of hugging one side.
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                // The same flag the server list shows for this exit, crossfading as the region
                // changes; the globe stands in without a country or when the user prefers it.
                Crossfade(
                    targetState = country?.takeIf { flagStyle != FlagStyle.GLOBE },
                    animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                    label = "server flag",
                ) { code ->
                    if (code != null) {
                        CountryFlag(code = code, twemoji = flagStyle == FlagStyle.TWEMOJI)
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_public),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = colors.onSecondaryContainer,
                        )
                    }
                }
            }
            // Switching the exit region swaps the label with a gentle fade/slide.
            val motion = MaterialTheme.motionScheme
            AnimatedContent(
                targetState = label,
                transitionSpec = {
                    (fadeIn(motion.defaultEffectsSpec()) +
                        slideInVertically(motion.defaultSpatialSpec()) { it / 2 }) togetherWith
                        fadeOut(motion.defaultEffectsSpec())
                },
                label = "server label",
                modifier = Modifier.weight(1f),
            ) { l ->
                Text(
                    text = l,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                )
            }
            // A bare chevron (no stem) pointing at where the tap leads.
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConnectButton(
    state: TunnelState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    attentionTick: Int = 0,
    onAttentionHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val up = state == TunnelState.UP
    // Connected is the loud state: primary fill. Idle is the calm tonal one.
    val container by animateColorAsState(
        targetValue = (if (up) colors.primary else colors.secondaryContainer).withSurfaceOpacity(),
        animationSpec = motion.defaultEffectsSpec(),
        label = "button container",
    )
    val content by animateColorAsState(
        targetValue = if (up) colors.onPrimary else colors.onSecondaryContainer,
        animationSpec = motion.defaultEffectsSpec(),
        label = "button content",
    )
    // The "go connect" blink — "hey, I'm here": the button softly brightens toward the
    // primary fill and back a few times, staying put. 0 is the no-request state.
    val pulse = remember { Animatable(0f) }
    val blinkScope = rememberCoroutineScope()
    LaunchedEffect(attentionTick) {
        if (attentionTick == 0) return@LaunchedEffect
        // The request is spent the moment it's taken: the shell clears it, so coming back
        // Home any other way later doesn't replay the blink. The blink itself runs in the
        // button's own scope, outliving this effect's restart on the cleared tick.
        onAttentionHandled()
        blinkScope.launch {
            // Let the page switch settle first, so the blink plays once Home is on screen.
            delay(350)
            repeat(ATTENTION_PULSES) {
                pulse.animateTo(1f, tween(durationMillis = 280, easing = FastOutSlowInEasing))
                pulse.animateTo(0f, tween(durationMillis = 420, easing = FastOutSlowInEasing))
            }
        }
    }
    // The flash rides the pulse value directly: the spring-colored container lerps toward
    // the primary while the value is high.
    val flashed = lerp(container, colors.primary, pulse.value)
    val busy = state == TunnelState.CONNECTING || state == TunnelState.DISCONNECTING
    // The tap itself pokes like every other button; the result (up or failed) answers later.
    val poke = rememberHapticPoke()
    Button(
        onClick = {
            poke()
            if (up) onDisconnect() else onConnect()
        },
        enabled = !busy,
        colors = ButtonDefaults.buttonColors(
            containerColor = flashed,
            contentColor = content,
            // Keep the same colors while disabled (connecting), otherwise the button
            // greys out and the bar becomes invisible.
            disabledContainerColor = flashed,
            disabledContentColor = content,
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 0.dp),
        modifier = modifier
            .height(64.dp)
            .fillMaxWidth(),
    ) {
        WavyConnectBar(
            state = state,
            color = content,
            trackColor = content.copy(alpha = 0.30f),
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
        )
    }
}

/**
 * MD3 Expressive-style wavy progress bar. Rounded caps everywhere; the wave travels
 * continuously and the bar fills up while connecting and drains while disconnecting.
 */
@Composable
private fun WavyConnectBar(
    state: TunnelState,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    val wavelength = 28.dp
    val amplitude = 5.dp
    val stroke = 6.dp
    // Expressive linear indicators keep a small gap between the wavy indicator and the track.
    val gap = 4.dp
    val motion = MaterialTheme.motionScheme
    // 0 when disconnected, 1 when connected; animating between them is the fill effect.
    val fill by animateFloatAsState(
        targetValue = if (state == TunnelState.UP || state == TunnelState.CONNECTING) 1f else 0f,
        animationSpec = motion.defaultSpatialSpec(),
        label = "fill",
    )
    // The wave travels one wavelength per cycle, so the loop is seamless. It travels only
    // while some of it shows: drained, the bar is the flat track alone, and a loop running
    // under it would keep the whole screen re-rendering every frame for nothing.
    val waving by remember { derivedStateOf { fill > 0.001f } }
    val phase = if (waving) {
        rememberInfiniteTransition(label = "wave").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
            label = "wave phase",
        )
    } else {
        null
    }

    Canvas(modifier) {
        val widthPx = size.width
        val centerY = size.height / 2f
        val strokePx = stroke.toPx()
        val amplitudePx = amplitude.toPx()
        val wavelengthPx = wavelength.toPx()
        val phasePx = (phase?.value ?: 0f) * wavelengthPx

        fun wavePath(x0: Float, x1: Float, amp: Float): Path {
            val path = Path()
            var x = x0
            // Start on the curve, not on the center line — otherwise the left end bends
            // towards the middle.
            var y = centerY + amp * sin(2.0 * PI * (x + phasePx) / wavelengthPx).toFloat()
            path.moveTo(x, y)
            val step = wavelengthPx / 16f
            while (x < x1) {
                x = min(x + step, x1)
                y = centerY + amp * sin(2.0 * PI * (x + phasePx) / wavelengthPx).toFloat()
                path.lineTo(x, y)
            }
            return path
        }
        val wavyStroke = Stroke(width = strokePx, cap = StrokeCap.Round)
        val gapPx = gap.toPx()
        val inset = strokePx / 2f

        fun flatTrack(x0: Float, x1: Float) {
            if (x1 - x0 <= inset) return
            drawLine(
                color = trackColor,
                start = Offset(max(x0, inset), centerY),
                end = Offset(min(x1, widthPx - inset), centerY),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
        }

        // Per the M3 expressive wavy spec: the track is a plain flat line (never animated),
        // only the indicator is a wave, and a small gap separates the wave end from the track.
        // Connecting fills left-to-right; disconnecting drains towards the right.
        when {
            fill >= 0.999f ->
                drawPath(wavePath(0f, widthPx, amplitudePx), color, style = wavyStroke)

            fill <= 0.001f ->
                flatTrack(0f, widthPx)

            state == TunnelState.DISCONNECTING -> {
                // The wave retreats to the right and exits at the right edge.
                val left = widthPx * (1f - fill)
                flatTrack(0f, left - gapPx)
                drawPath(wavePath(left, widthPx, amplitudePx), color, style = wavyStroke)
            }

            else -> {
                // Filling up from the left, the track behind it.
                val right = widthPx * fill
                flatTrack(right + gapPx, widthPx)
                drawPath(wavePath(0f, right, amplitudePx), color, style = wavyStroke)
            }
        }
    }
}

private val TunnelState.label: Int
    get() = when (this) {
        TunnelState.DOWN -> R.string.home_state_down
        TunnelState.CONNECTING -> R.string.home_state_connecting
        TunnelState.UP -> R.string.home_state_up
        TunnelState.DISCONNECTING -> R.string.home_state_disconnecting
    }

@Composable
private fun stateLabel(state: TunnelState): Int = state.label

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    AdhdiumTheme(settings = UiSettings(accent = Accent.VIOLET)) {
        HomeScreen(
            name = "miho",
            status = TunnelStatus(TunnelState.UP),
            home = HomeUiState(
                deviceName = "nothing",
                localIp = "10.0.0.3",
                exitLabel = "Finland",
                pingMillis = 25,
                uptimeStartMillis = System.currentTimeMillis() - 3_600_000,
            ),
            exitIp = "1.2.3.10",
            onConnect = {},
            onDisconnect = {},
            onOpenServers = {},
        )
    }
}

private const val ATTENTION_PULSES = 3
