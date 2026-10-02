package slop.miho.adhdium.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.MutableCubic
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.sin
import slop.miho.adhdium.R
import slop.miho.adhdium.data.FlagStyle
import slop.miho.adhdium.ui.ServersUiState
import slop.miho.adhdium.ui.components.CountryFlag
import slop.miho.adhdium.ui.components.ErrorCard
import slop.miho.adhdium.ui.components.InfoChip
import slop.miho.adhdium.ui.components.SpinningDiamond
import slop.miho.adhdium.ui.components.rememberHapticDeniedKicks
import slop.miho.adhdium.ui.components.rememberHapticPoke
import slop.miho.adhdium.ui.components.WavyPullToRefreshBox
import slop.miho.adhdium.ui.components.pullShiftedPadding
import slop.miho.adhdium.ui.wallpapers.LocalHasWallpaper
import slop.miho.adhdium.ui.wallpapers.LocalSurfaceOpacity
import slop.miho.adhdium.ui.wallpapers.ReportBarCoverage

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ServersScreen(
    state: ServersUiState,
    tunnelUp: Boolean,
    visible: Boolean,
    flagStyle: FlagStyle = FlagStyle.TWEMOJI,
    onRefresh: () -> Unit,
    onAutoRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Refresh on entering the page (and when the tunnel comes up while it is open), then
    // keep the data warm once a minute while the page is still on screen. Off-screen pages
    // and a down tunnel never touch the panel.
    LaunchedEffect(visible, tunnelUp) {
        if (!visible || !tunnelUp) return@LaunchedEffect
        onRefresh()
        while (isActive) {
            delay(60_000)
            onAutoRefresh()
        }
    }

    // Each denied pull-to-refresh attempt bumps this counter, shaking the "connect first" chip.
    var deniedShake by remember { mutableIntStateOf(0) }

    // Rows sit under the floating bar while the list outgrows the screen; while loading the
    // page is one centered indicator, with nothing beneath the bar at all.
    val listState = rememberLazyListState()
    val contentUnderBar by remember {
        derivedStateOf { !state.isLoading && (listState.canScrollForward || listState.canScrollBackward) }
    }
    val rowsReachBar by rememberContentUnderBar(listState)
    ReportBarCoverage(contentUnderBar, underlaid = !state.isLoading && rowsReachBar)

    // Wavy pull indicator: the wave grows with the pull while connected; without a tunnel
    // the bar stays flat and grumbles on release instead of refreshing.
    WavyPullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        enabled = tunnelUp,
        onRefreshDenied = { deniedShake++ },
        modifier = modifier.fillMaxSize(),
    ) { pullShift ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .screenPadding(),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator()
            }
            return@WavyPullToRefreshBox
        }

        // Rows travel under the status bar and the floating bar (fading out behind the
        // shell gradients) and rest clear of both thanks to the content padding.
        val topContentPadding = WindowInsets.statusBars.asPaddingValues()
            .calculateTopPadding() + 24.dp
        val bottomContentPadding = WindowInsets.navigationBars.asPaddingValues()
            .calculateBottomPadding() + FloatingBottomBarReservedHeight
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .screenPadding(),
            // The pull pushes the rows down through this top padding (see WavyPullToRefreshBox).
            contentPadding = remember(topContentPadding, bottomContentPadding, pullShift) {
                pullShiftedPadding(topContentPadding, bottomContentPadding, pullShift)
            },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // A refresh failure sits above everything, shaped like a server row itself.
            state.error?.let { error ->
                item { ServersError(message = error) }
            }

            // Stats chips above the list; without a connection they turn into one red notice.
            item { ServersHeader(state = state, tunnelUp = tunnelUp, shakeTick = deniedShake) }

            // The list is always shown; only the live values (ping, exit IP) hide without the
            // tunnel so no stale cache is presented as current.
            item {
                ServerGroup(
                    state = state,
                    tunnelUp = tunnelUp,
                    flagStyle = flagStyle,
                    onSelect = onSelect,
                    // Disconnected: a row's denial travels up to the "connect first" chip —
                    // the same wiggle a denied pull-to-refresh gives it.
                    onConnectDenied = if (tunnelUp) null else ({ deniedShake++ }),
                )
            }
        }
    }
}

/**
 * The chip row above the server list: server counts while connected, a single red
 * "connect first" notice while not. Centered, unlike the start-aligned home chips.
 * [shakeTick] bumps on every denied pull-to-refresh attempt, wiggling the notice.
 */
@Composable
private fun ServersHeader(state: ServersUiState, tunnelUp: Boolean, shakeTick: Int) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = tunnelUp,
        transitionSpec = {
            fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.defaultEffectsSpec())
        },
        label = "servers header",
    ) { up ->
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (up) {
                InfoChip(
                    value = stringResource(R.string.servers_total, state.exits.size),
                    icon = R.drawable.ic_servers,
                    animateValue = true,
                )
                InfoChip(
                    value = stringResource(
                        R.string.servers_available,
                        state.exits.count { it.available },
                    ),
                    icon = R.drawable.ic_plug,
                    animateValue = true,
                )
            } else {
                // 1 right after a denied pull, decaying to 0; the chip wiggles left-right
                // alongside the "denied" haptic kicks.
                val shake = remember { Animatable(0f) }
                LaunchedEffect(shakeTick) {
                    if (shakeTick == 0) return@LaunchedEffect
                    shake.snapTo(1f)
                    shake.animateTo(0f, tween(durationMillis = SHAKE_MILLIS, easing = LinearEasing))
                }
                Box(
                    Modifier.graphicsLayer {
                        translationX =
                            sin(shake.value * 2.0 * PI * SHAKE_WOBBLES).toFloat() *
                                SHAKE_AMPLITUDE.toPx() * shake.value
                    },
                ) {
                    InfoChip(
                        value = stringResource(R.string.servers_connect_network),
                        icon = R.drawable.ic_error,
                        container = colors.errorContainer,
                        content = colors.onErrorContainer,
                    )
                }
            }
        }
    }
}

/**
 * A refresh failure sits above everything as the shared error card.
 */
@Composable
private fun ServersError(message: String) {
    ErrorCard(message = stringResource(R.string.servers_refresh_failed, message))
}

/**
 * All servers in one MD3 segmented list container: large outer corners, extra-small inner
 * corners and a 2dp gap between rows instead of dividers.
 */
@Composable
private fun ServerGroup(
    state: ServersUiState,
    tunnelUp: Boolean,
    flagStyle: FlagStyle,
    onSelect: (String) -> Unit,
    // Set while disconnected: every row's denial wiggles the header's "connect first" chip
    // instead of the row itself — the chip is the thing saying why nothing is pickable.
    onConnectDenied: (() -> Unit)?,
) {
    val rows = buildList<@Composable (Shape) -> Unit> {
        state.auto?.let { auto ->
            val viaLabel = auto.via
                ?.let { viaId -> state.exits.firstOrNull { it.id == viaId }?.label }
            add { shape ->
                ServerRow(
                    title = auto.label,
                    hint = auto.hint,
                    trailing = if (tunnelUp && state.currentExitId == AUTO_EXIT_ID) {
                        viaLabel?.let { label -> stringResource(R.string.servers_auto_via, label) }
                    } else null,
                    // Without the tunnel nothing is in use, so nothing reads as selected.
                    selected = tunnelUp && state.currentExitId == AUTO_EXIT_ID,
                    enabled = tunnelUp && !state.isSelecting,
                    onSelect = { onSelect(AUTO_EXIT_ID) },
                    onConnectDenied = onConnectDenied,
                    shape = shape,
                )
            }
        }
        state.exits.forEach { exit ->
            add { shape ->
                ServerRow(
                    title = exit.label,
                    hint = exit.hint,
                    // The name's line carries the status, like a device's address: "Offline"
                    // for a broken exit, otherwise the ping. Ping is a live measurement —
                    // without the tunnel whatever is cached would be stale, so it is not
                    // shown. The exit IP lives on Home only.
                    titleExtra = when {
                        !exit.available -> stringResource(R.string.servers_offline)
                        !tunnelUp -> null
                        else -> exit.pingMillis
                            ?.let { ping -> stringResource(R.string.servers_ping_ms, ping) }
                    },
                    // A slow exit's ping stands out in the accent.
                    titleExtraAlert = exit.available && tunnelUp &&
                        (exit.pingMillis ?: 0) >= HIGH_PING_MILLIS,
                    trailing = null,
                    selected = tunnelUp && state.currentExitId == exit.id,
                    enabled = tunnelUp && exit.available && !state.isSelecting,
                    // The cookie and the diamond only come alive with the tunnel up.
                    animated = tunnelUp,
                    onSelect = { onSelect(exit.id) },
                    onConnectDenied = onConnectDenied,
                    unavailable = !exit.available,
                    country = exit.country,
                    flagStyle = flagStyle,
                    shape = shape,
                )
            }
        }
    }
    if (rows.isEmpty()) return

    val outer = MaterialTheme.shapes.extraLarge
    val inner = MaterialTheme.shapes.extraSmall
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(outer),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        rows.forEachIndexed { index, row ->
            val first = index == 0
            val last = index == rows.lastIndex
            row(
                RoundedCornerShape(
                    topStart = if (first) outer.topStart else inner.topStart,
                    topEnd = if (first) outer.topEnd else inner.topEnd,
                    bottomStart = if (last) outer.bottomStart else inner.bottomStart,
                    bottomEnd = if (last) outer.bottomEnd else inner.bottomEnd,
                ),
            )
        }
    }
}

@Composable
private fun ServerRow(
    title: String,
    hint: String?,
    trailing: String?,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    // While disconnected the denial lands on the header's "connect first" chip instead of
    // this row; null (tunnel up, row merely offline or mid-select) shakes the row itself.
    onConnectDenied: (() -> Unit)? = null,
    unavailable: Boolean = false,
    animated: Boolean = true,
    /** The exit's ISO alpha-2 country; null (auto, or no geo yet) keeps the globe. */
    country: String? = null,
    flagStyle: FlagStyle = FlagStyle.TWEMOJI,
    /** A quieter value after the title on the same line (ping or "Offline"), dot-separated. */
    titleExtra: String? = null,
    /** Paints [titleExtra] in the accent instead of the quiet tone (a high ping). */
    titleExtraAlert: Boolean = false,
    shape: Shape = MaterialTheme.shapes.extraSmall,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    // Unpickable (tunnel down, offline, mid-select): MD3 disabled look — greyed content, and
    // a tap on it answers with the denial below instead of selecting.
    val dim = if (enabled) 1f else 0.38f
    // Every row wears the setting's opacity, selected or not. Over a wallpaper an
    // unavailable row fades further still, receding behind the ones that can be picked;
    // without one every row stays opaque — there's nothing to show through.
    val fade = if (unavailable && LocalHasWallpaper.current) UNAVAILABLE_FADE else 0f
    val containerColor by animateColorAsState(
        targetValue = (if (selected) colors.primaryContainer else colors.surfaceContainer)
            .copy(alpha = (LocalSurfaceOpacity.current - fade).coerceAtLeast(0f)),
        animationSpec = motion.defaultEffectsSpec(),
        label = "server container",
    )
    val contentColor by animateColorAsState(
        targetValue = (if (selected) colors.onPrimaryContainer else colors.onSurface).copy(alpha = dim),
        animationSpec = motion.defaultEffectsSpec(),
        label = "server content",
    )
    // The status after the name: a touch quieter than it, as a device's address is — or,
    // for a high ping, the scheme's strong accent tone, full strength.
    val extraColor by animateColorAsState(
        targetValue = if (titleExtraAlert) {
            colors.primary.copy(alpha = dim)
        } else {
            contentColor.copy(alpha = contentColor.alpha * 0.62f)
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "server title extra",
    )
    val supportingColor by animateColorAsState(
        targetValue = (if (selected) {
            colors.onPrimaryContainer.copy(alpha = 0.78f)
        } else {
            colors.onSurfaceVariant
        }).copy(alpha = dim),
        animationSpec = motion.defaultEffectsSpec(),
        label = "server supporting",
    )
    // The icon backings are fully opaque: the spinning cookie's stage is meant to be seen,
    // not guessed at through the row.
    val iconContainerColor by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.secondaryContainer,
        animationSpec = motion.defaultEffectsSpec(),
        label = "server icon container",
    )
    val iconColor by animateColorAsState(
        targetValue = (if (selected) colors.onPrimary else colors.onSecondaryContainer).copy(alpha = dim),
        animationSpec = motion.defaultEffectsSpec(),
        label = "server icon",
    )

    // The tap answers through the skin like the connect button: a crisp poke when the pick
    // changes the server, and — on a row that can't be picked — the grumpy kicks plus a
    // left-right wiggle, the same denial the "connect first" chip answers a denied pull with.
    val poke = rememberHapticPoke()
    val kicks = rememberHapticDeniedKicks()
    var deniedShake by remember { mutableIntStateOf(0) }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(deniedShake) {
        if (deniedShake == 0) return@LaunchedEffect
        shake.snapTo(1f)
        shake.animateTo(0f, tween(durationMillis = SHAKE_MILLIS, easing = LinearEasing))
    }

    Surface(
        // Always clickable: an unpickable row still answers its tap — with the kicks —
        // rather than swallowing it in silence.
        onClick = {
            when {
                enabled && !selected -> {
                    poke()
                    onSelect()
                }
                // Tapping the already-current server changes nothing, and says nothing.
                enabled -> Unit
                else -> {
                    kicks()
                    // Disconnected: the wiggle travels to the "connect first" chip, which is
                    // the thing explaining why nothing here is pickable. Otherwise the row
                    // itself grumbles in place.
                    if (onConnectDenied != null) onConnectDenied() else deniedShake++
                }
            }
        },
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationX =
                    sin(shake.value * 2.0 * PI * SHAKE_WOBBLES).toFloat() *
                        SHAKE_AMPLITUDE.toPx() * shake.value
            },
    ) {
        ListItem(
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // The ping appears and goes (tunnel up/down) with a fade, never a jump.
                    AnimatedVisibility(
                        visible = titleExtra != null,
                        enter = fadeIn(motion.defaultEffectsSpec()),
                        exit = fadeOut(motion.defaultEffectsSpec()),
                    ) {
                        // Keeps the last value on screen while it fades out.
                        var shown by remember { mutableStateOf(titleExtra.orEmpty()) }
                        if (titleExtra != null) shown = titleExtra
                        Text(
                            text = "  ·  $shown",
                            color = extraColor,
                            maxLines = 1,
                        )
                    }
                }
            },
            supportingContent = hint?.let { description ->
                {
                    Text(
                        text = description,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            leadingContent = {
                // MD3 list leading icon: 24dp icon on a 40dp tonal container. Selection morphs
                // the circle into the slowly spinning MD3 cookie; broken servers get the
                // diamond. Unavailable rows keep their normal colors — the shape and the
                // "Offline" label already say enough.
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (unavailable) {
                        SpinningDiamond(color = iconContainerColor, animated = animated)
                    } else {
                        CookieBackdrop(
                            selected = selected,
                            color = iconContainerColor,
                            animated = animated,
                        )
                    }
                    // The flag replaces the globe once the panel knows the exit's country,
                    // unless the user asked for the globe; a broken exit keeps its error mark.
                    if (!unavailable && country != null && flagStyle != FlagStyle.GLOBE) {
                        CountryFlag(
                            code = country,
                            twemoji = flagStyle == FlagStyle.TWEMOJI,
                            // Emoji can't be tinted, so the disabled look fades them instead.
                            modifier = Modifier.alpha(dim),
                        )
                    } else {
                        Icon(
                            painter = painterResource(
                                if (unavailable) R.drawable.ic_error else R.drawable.ic_public,
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = iconColor,
                        )
                    }
                }
            },
            trailingContent = trailing?.let { value ->
                {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelLarge,
                        color = supportingColor,
                        textAlign = TextAlign.End,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            colors = androidx.compose.material3.ListItemDefaults.colors(
                // The row's Surface already paints the container; painting it here again
                // stacked the translucent color twice, leaving these rows denser than the
                // rest of the app's.
                containerColor = Color.Transparent,
                headlineColor = contentColor,
                supportingColor = supportingColor,
                leadingIconColor = contentColor,
                trailingIconColor = supportingColor,
            ),
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The server icon backdrop: a circle at rest that morphs into the MD3 9-sided cookie when
 * selected and back when deselected. The cookie keeps spinning slowly; the icon on top of it
 * stays upright. Without [animated] (no tunnel) the shape freezes and only the morph still
 * runs. Drawn by hand because the library's morph-to-path helpers only speak the framework
 * Path, not the Compose one.
 */
@Composable
internal fun CookieBackdrop(selected: Boolean, color: Color, animated: Boolean) {
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val motion = MaterialTheme.motionScheme
    // 0 = circle, 1 = cookie. The spatial spring overshoots a little, and the morph happily
    // interpolates slightly past its range, which reads as a soft shape bounce.
    val progress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = motion.defaultSpatialSpec(),
        label = "server cookie morph",
    )
    // The spin runs only while there's a cookie to turn: on the resting circle it would be
    // invisible, yet it would keep the display clocking frames for every row on the list.
    // Dropped once the shape has settled back, the angle's reset hides under the circle.
    val shaped by remember { derivedStateOf { progress > 0.001f } }
    val spinAngle: State<Float>? = if (animated && (selected || shaped)) {
        val spin = rememberInfiniteTransition(label = "server cookie spin")
        spin.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                tween(durationMillis = COOKIE_SPIN_MILLIS, easing = LinearEasing),
            ),
            label = "server cookie spin angle",
        )
    } else {
        null
    }
    // Reused per frame: the path is rebuilt from the morph's cubics on every redraw.
    val path = remember { Path() }
    val cubic = remember { MutableCubic() }
    Box(
        Modifier
            .fillMaxSize()
            // Scaled by the morph progress so the rotation eases in with the shape instead of
            // snapping; on the resting circle any rotation would be invisible anyway. Read
            // here, in the layer, so the spin's frames never recompose the row.
            .graphicsLayer { rotationZ = (spinAngle?.value ?: 0f) * progress }
            .drawBehind {
                path.rewind()
                var first = true
                morph.forEachCubic(progress, cubic) { c ->
                    if (first) {
                        path.moveTo(c.anchor0X, c.anchor0Y)
                        first = false
                    }
                    path.cubicTo(
                        c.control0X, c.control0Y,
                        c.control1X, c.control1Y,
                        c.anchor1X, c.anchor1Y,
                    )
                }
                path.close()
                // Polygon coordinates are normalized to 0..1: scale up to this box.
                withTransform({ scale(size.width, size.height, pivot = Offset.Zero) }) {
                    drawPath(path, color)
                }
            },
    )
}

private const val AUTO_EXIT_ID = "auto"

/** From this ping on (ms) an exit counts as slow, and its ping is painted in the accent. */
private const val HIGH_PING_MILLIS = 150
private const val COOKIE_SPIN_MILLIS = 12_000

/** How far below the surface opacity an unavailable row sits over a wallpaper. */
private const val UNAVAILABLE_FADE = 0.10f

/** One denied-pull wiggle: how long it lasts and how far it swings. */
private const val SHAKE_MILLIS = 700
private const val SHAKE_WOBBLES = 3.0
private val SHAKE_AMPLITUDE = 8.dp
