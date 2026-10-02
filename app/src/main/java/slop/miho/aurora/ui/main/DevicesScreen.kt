package slop.miho.aurora.ui.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.sin
import slop.miho.aurora.R
import slop.miho.aurora.data.BackendDevice
import slop.miho.aurora.ui.DevicesUiState
import slop.miho.aurora.ui.components.ErrorCard
import slop.miho.aurora.ui.components.InfoChip
import slop.miho.aurora.ui.components.QrCode
import slop.miho.aurora.ui.components.WavyPullToRefreshBox
import slop.miho.aurora.ui.components.pullShiftedPadding
import slop.miho.aurora.ui.components.pressBounce
import slop.miho.aurora.ui.components.rememberHapticDeniedKicks
import slop.miho.aurora.ui.components.rememberHapticPoke
import slop.miho.aurora.ui.wallpapers.LocalSurfaceOpacity
import slop.miho.aurora.ui.wallpapers.ReportBarCoverage

/**
 * The subnet's devices, built on the servers screen's chassis: the wavy pull-to-refresh (with
 * the denied "connect first" wiggle while offline), the same loading and error slots, and the
 * same quieted look while the tunnel is down. The rows themselves speak the fonts screen's
 * language — a square tile under the icon — because devices are things to look at, not
 * options to pick. Tapping a card opens its actions beneath it: rename, config, disable —
 * the last one hiding delete behind a long press, exactly one confirmed mistake deep.
 * Below the list, apart from it, sits the "New device" card — shown only when the panel's
 * own answer ("trusted", re-asked with every refresh) allows creating peers, and hidden
 * entirely while the tunnel is down since the panel is unreachable then.
 */
@Composable
fun DevicesScreen(
    state: DevicesUiState,
    tunnelUp: Boolean,
    visible: Boolean,
    onRefresh: () -> Unit,
    onAutoRefresh: () -> Unit,
    onCreateDevice: (String) -> Unit,
    onRename: (Int, String) -> Unit,
    onToggleDevice: (Int) -> Unit,
    onDeleteDevice: (Int) -> Unit,
    loadDeviceConfig: suspend (Int) -> String?,
    // "This device" doesn't manage itself here — its one action points home, at the button
    // that owns connecting.
    onOpenHome: () -> Unit,
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

    // The one card whose actions are unfolded; -1 = all folded. Survives page switches so a
    // dialog opened from an unfolded card still has its row waiting behind it.
    var expandedId by rememberSaveable { mutableIntStateOf(-1) }

    // One dialog at a time, each holding the device it acts on.
    var renameTarget by remember { mutableStateOf<BackendDevice?>(null) }
    var deleteTarget by remember { mutableStateOf<BackendDevice?>(null) }
    var configTarget by remember { mutableStateOf<BackendDevice?>(null) }
    // The new-device dialog carries no target — the device doesn't exist yet.
    var newDeviceOpen by remember { mutableStateOf(false) }

    // "Download" hands the config to a file the user picks with SAF: no storage permission,
    // no file the app chose on the user's behalf. The text rides this variable between the
    // dialog's button and the launcher's callback — in memory, nowhere else.
    val context = LocalContext.current
    var downloadPayload by remember { mutableStateOf<Pair<String, String>?>(null) }
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val payload = downloadPayload
        if (uri != null && payload != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(payload.second.toByteArray())
                }
            }
        }
        downloadPayload = null
    }

    // Rows sit under the floating bar while the list outgrows the screen; while loading the
    // page is one centered indicator, with nothing beneath the bar at all.
    val listState = rememberLazyListState()
    val contentUnderBar by remember {
        derivedStateOf { !state.isLoading && (listState.canScrollForward || listState.canScrollBackward) }
    }
    val rowsReachBar by rememberContentUnderBar(listState)
    ReportBarCoverage(contentUnderBar, underlaid = !state.isLoading && rowsReachBar)

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

        // Rows travel under the status bar and the floating bar (fading out behind the shell
        // gradients) and rest clear of both thanks to the content padding.
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
            // A refresh failure sits above everything, shaped like a device row itself.
            state.error?.let { error ->
                item { DevicesError(message = error) }
            }

            // Stats chips above the list; without a connection they turn into one red notice.
            item { DevicesHeader(state = state, tunnelUp = tunnelUp, shakeTick = deniedShake) }

            item {
                DeviceGroup(
                    state = state,
                    tunnelUp = tunnelUp,
                    expandedId = expandedId,
                    onCardClick = { device ->
                        expandedId = if (device.id == expandedId) -1 else device.id
                    },
                    onRename = { device -> renameTarget = device },
                    onConfig = { device -> configTarget = device },
                    onToggle = { device -> onToggleDevice(device.id) },
                    onDeleteRequest = { device -> deleteTarget = device },
                    onOpenHome = onOpenHome,
                    onConnectDenied = if (tunnelUp) null else ({ deniedShake++ }),
                )
            }

            // The creation card, apart from the segmented list: its own full-width card, only
            // while the panel both answers and allows. Without the tunnel it's gone, not
            // disabled — the panel can't be asked anything then.
            if (tunnelUp && state.trusted) {
                item {
                    NewDeviceCard(onClick = { newDeviceOpen = true })
                }
            }
        }
    }

    if (newDeviceOpen) {
        NewDeviceDialog(
            onConfirm = { name ->
                onCreateDevice(name)
                newDeviceOpen = false
            },
            onDismiss = { newDeviceOpen = false },
        )
    }

    renameTarget?.let { device ->
        RenameDialog(
            device = device,
            onConfirm = { name ->
                onRename(device.id, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { device ->
        DeleteDeviceDialog(
            device = device,
            onConfirm = {
                onDeleteDevice(device.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }

    configTarget?.let { device ->
        ConfigDialog(
            device = device,
            loadConfig = loadDeviceConfig,
            onDismiss = { configTarget = null },
            onDownload = { fileName, text ->
                downloadPayload = fileName to text
                downloadLauncher.launch(fileName)
            },
        )
    }
}

/**
 * The "New device" card — deliberately its own card, apart from the segmented list, because
 * it's an action rather than a device. It wears the list's row language (the square tile)
 * but the tile speaks in the primary container: the screen's one invitation to create
 * something, as opposed to the rows' "things that exist".
 */
@Composable
private fun NewDeviceCard(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val poke = rememberHapticPoke()
    Surface(
        onClick = {
            poke()
            onClick()
        },
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainer.copy(alpha = LocalSurfaceOpacity.current),
        contentColor = colors.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Text(
                text = stringResource(R.string.devices_action_new),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/**
 * The creation dialog, the rename dialog's own recipe: an MD3 AlertDialog with an outlined
 * name field. The name caps at 32 characters, the panel's own limit, and "Add" answers only
 * once the field holds something.
 */
@Composable
private fun NewDeviceDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.devices_action_new)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= MAX_DEVICE_NAME_LENGTH) name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.devices_rename_field)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.devices_new_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.devices_cancel))
            }
        },
    )
}

/**
 * The chip row above the device list: device counts while connected, a single red
 * "connect first" notice while not — the same red stick the servers screen carries, complete
 * with its denied-pull wiggle.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DevicesHeader(state: DevicesUiState, tunnelUp: Boolean, shakeTick: Int) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = tunnelUp,
        transitionSpec = {
            fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.defaultEffectsSpec())
        },
        label = "devices header",
    ) { up ->
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (up) {
                InfoChip(
                    value = stringResource(R.string.devices_total, state.devices.size),
                    icon = R.drawable.ic_devices,
                    animateValue = true,
                )
                InfoChip(
                    value = stringResource(R.string.devices_online, state.devices.count { it.online }),
                    icon = R.drawable.ic_wifi,
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

/** A refresh failure sits above everything as the shared error card. */
@Composable
private fun DevicesError(message: String) {
    ErrorCard(message = stringResource(R.string.devices_refresh_failed, message))
}

/**
 * All devices in one MD3 segmented list container, the fonts screen's grouping: large outer
 * corners, extra-small inner corners and a 2dp gap between rows instead of dividers.
 */
@Composable
private fun DeviceGroup(
    state: DevicesUiState,
    tunnelUp: Boolean,
    expandedId: Int,
    onCardClick: (BackendDevice) -> Unit,
    onRename: (BackendDevice) -> Unit,
    onConfig: (BackendDevice) -> Unit,
    onToggle: (BackendDevice) -> Unit,
    onDeleteRequest: (BackendDevice) -> Unit,
    onOpenHome: () -> Unit,
    // While disconnected a denied tap on a card travels up to the "connect first" chip —
    // the same wiggle a denied pull-to-refresh gives it.
    onConnectDenied: (() -> Unit)?,
) {
    // "This device" leads the list; the rest keep the panel's order.
    val ordered = state.devices.sortedByDescending { it.id == state.meId }
    val rows = buildList<@Composable (Shape) -> Unit> {
        ordered.forEach { device ->
            add { shape ->
                DeviceRow(
                    device = device,
                    isMe = device.id == state.meId,
                    tunnelUp = tunnelUp,
                    expanded = device.id == expandedId,
                    onCardClick = { onCardClick(device) },
                    onRename = { onRename(device) },
                    onConfig = { onConfig(device) },
                    onToggle = { onToggle(device) },
                    onDeleteRequest = { onDeleteRequest(device) },
                    onOpenHome = onOpenHome,
                    onConnectDenied = onConnectDenied,
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

/**
 * One device, in the fonts screen's row language: a 48dp square tile under the icon, the name
 * and the local address sharing the headline line, everything else the panel knows in the
 * supporting text below. Every row wears the plain container — the accent lives on the icons
 * alone, and only for online devices. The device this app's own config belongs to is marked
 * by the servers screen's spinning cookie instead of a square — that one is "you". Tapping
 * the card unfolds its actions; without the tunnel the tap is denied with the same kicks a
 * denied pull gets, since every action needs the panel to answer.
 */
@Composable
private fun DeviceRow(
    device: BackendDevice,
    isMe: Boolean,
    tunnelUp: Boolean,
    expanded: Boolean,
    onCardClick: () -> Unit,
    onRename: () -> Unit,
    onConfig: () -> Unit,
    onToggle: () -> Unit,
    onDeleteRequest: () -> Unit,
    onOpenHome: () -> Unit,
    onConnectDenied: (() -> Unit)?,
    shape: Shape,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    // While the tunnel is down the whole list wears the servers screen's quieted look: the
    // values behind it are live panel data, and without the tunnel they can't be fetched.
    val dim = if (tunnelUp) 1f else 0.38f
    // The row itself stays plain for everyone — no accent slab: the accent belongs to the
    // icons of online devices and nowhere else on this screen.
    val containerColor by animateColorAsState(
        targetValue = colors.surfaceContainer.copy(alpha = LocalSurfaceOpacity.current),
        animationSpec = motion.defaultEffectsSpec(),
        label = "device container",
    )
    val contentColor by animateColorAsState(
        targetValue = colors.onSurface.copy(alpha = dim),
        animationSpec = motion.defaultEffectsSpec(),
        label = "device content",
    )
    val supportingColor by animateColorAsState(
        targetValue = colors.onSurfaceVariant.copy(alpha = dim),
        animationSpec = motion.defaultEffectsSpec(),
        label = "device supporting",
    )
    // The address rides the headline line, a touch quieter than the name, with a middle dot
    // between them — the IP is identity too, just the lesser half of it.
    val addressColor by animateColorAsState(
        targetValue = contentColor.copy(alpha = contentColor.alpha * 0.62f),
        animationSpec = motion.defaultEffectsSpec(),
        label = "device address",
    )
    // The tile is the one place the accent shows — and only while the device is online: an
    // offline device's tile falls back to the plain tonal one.
    val tileContainer by animateColorAsState(
        targetValue = if (device.online) colors.primary else colors.secondaryContainer,
        animationSpec = motion.defaultEffectsSpec(),
        label = "device tile container",
    )
    // The backing stays opaque like the servers' icon backings, but the glyph on it greys
    // out with the rest of the row while the tunnel is down.
    val tileContent by animateColorAsState(
        targetValue = (if (device.online) colors.onPrimary else colors.onSecondaryContainer)
            .copy(alpha = dim),
        animationSpec = motion.defaultEffectsSpec(),
        label = "device tile content",
    )

    // The tap answers through the skin like the servers' rows: a crisp poke when it unfolds
    // the actions, and the grumpy kicks — pointed at the header's "connect first" chip —
    // when there's no tunnel to unfold them with.
    val poke = rememberHapticPoke()
    val kicks = rememberHapticDeniedKicks()

    Surface(
        onClick = {
            if (tunnelUp) {
                poke()
                onCardClick()
            } else {
                kicks()
                onConnectDenied?.invoke()
            }
        },
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                // The tile aligns with the card's top, the name's line — not the middle of the
                // grown row.
                verticalAlignment = Alignment.Top,
            ) {
                if (isMe) {
                    // This device wears the servers screen's spinning cookie instead of a
                    // square — the "you are here" mark — drawn at the squares' own size and in
                    // the tiles' own color treatment. The cookie is the online mark itself:
                    // the moment the VPN drops, it settles back into a circle.
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CookieBackdrop(
                            selected = tunnelUp,
                            color = tileContainer,
                            animated = tunnelUp,
                        )
                        Icon(
                            painter = painterResource(R.drawable.ic_device),
                            contentDescription = null,
                            tint = tileContent,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = tileContainer,
                        contentColor = tileContent,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_device),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = device.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = contentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (device.addr.isNotBlank()) {
                            Text(
                                text = "  ·  ",
                                style = MaterialTheme.typography.bodyLarge,
                                color = addressColor,
                                maxLines = 1,
                            )
                            Text(
                                text = device.addr,
                                style = MaterialTheme.typography.bodyLarge,
                                color = addressColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // Everything else the panel says about the peer, save the config install
                    // itself: where it exits, when it expires, and the month's traffic. The
                    // connection status is deliberately left out — the icon already carries it.
                    val details = listOfNotNull(device.exitLabel, device.expires)
                        .joinToString("  ·  ")
                    val traffic = if (device.monthRx != null || device.monthTx != null) {
                        stringResource(
                            R.string.devices_traffic,
                            device.monthRx ?: "—",
                            device.monthTx ?: "—",
                        )
                    } else {
                        null
                    }
                    val description = listOfNotNull(
                        details.takeIf { it.isNotBlank() },
                        traffic,
                    ).joinToString("\n")
                    if (description.isNotBlank()) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = supportingColor,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // The unfolded actions, sliding in beneath the card's own content.
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(motion.defaultSpatialSpec()) +
                    fadeIn(motion.defaultEffectsSpec()),
                exit = shrinkVertically(motion.defaultSpatialSpec()) +
                    fadeOut(motion.fastEffectsSpec()),
            ) {
                DeviceActions(
                    device = device,
                    isMe = isMe,
                    onOpenHome = onOpenHome,
                    onRename = onRename,
                    onConfig = onConfig,
                    onToggle = onToggle,
                    onDeleteRequest = onDeleteRequest,
                )
            }
        }
    }
}

/**
 * One device's unfolded actions, all MD3 tonal pills in the app's own press treatment.
 * Every device gets rename and config (the panel serves a device's config only while it is
 * enabled, so that action appears only then). Everyone else adds the enable/disable toggle,
 * whose long press asks the heavier question instead: delete. "This device" swaps that
 * toggle for a pointer home, at the button that owns connecting — disabling or deleting
 * yourself would cut the very tunnel the panel is reached through.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceActions(
    device: BackendDevice,
    isMe: Boolean,
    onOpenHome: () -> Unit,
    onRename: () -> Unit,
    onConfig: () -> Unit,
    onToggle: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isMe) {
            DeviceActionButton(
                text = stringResource(R.string.devices_action_connect),
                icon = R.drawable.ic_power,
                onClick = onOpenHome,
            )
        }
        DeviceActionButton(
            text = stringResource(R.string.devices_action_rename),
            icon = R.drawable.ic_edit,
            onClick = onRename,
        )
        if (device.enabled) {
            DeviceActionButton(
                text = stringResource(R.string.devices_action_config),
                icon = R.drawable.ic_content_copy,
                onClick = onConfig,
            )
        }
        if (!isMe) {
            DeviceActionButton(
                text = stringResource(
                    if (device.enabled) R.string.devices_action_disable else R.string.devices_action_enable,
                ),
                icon = R.drawable.ic_power,
                onClick = onToggle,
                onLongClick = onDeleteRequest,
            )
        }
    }
}

/**
 * An action pill: a full-shape tonal button — secondary container over the surface-opacity
 * rule, like every other surface on this screen — carrying the same press bounce and poke
 * the square tiles have. [onLongClick] exists for the toggle's hidden delete path.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceActionButton(
    text: String,
    icon: Int,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val poke = rememberHapticPoke()
    var taps by remember { mutableIntStateOf(0) }
    val container by animateColorAsState(
        targetValue = if (pressed) {
            lerp(colors.secondaryContainer, colors.onSecondaryContainer, 0.12f)
        } else {
            colors.secondaryContainer
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "device action container",
    )
    Surface(
        shape = CircleShape,
        color = container.copy(alpha = LocalSurfaceOpacity.current),
        contentColor = colors.onSecondaryContainer,
        modifier = modifier
            .clip(CircleShape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = {
                    poke()
                    taps++
                    onClick()
                },
                onLongClick = onLongClick?.let { handler ->
                    {
                        poke()
                        taps++
                        handler()
                    }
                },
            )
            .pressBounce(pressed, taps),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * The rename dialog: an MD3 AlertDialog with an outlined text field, seeded with the
 * device's current name.
 */
@Composable
private fun RenameDialog(
    device: BackendDevice,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(device.id) { mutableStateOf(device.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.devices_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.devices_rename_field)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank() && name.trim() != device.name,
            ) {
                Text(stringResource(R.string.devices_rename_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.devices_cancel))
            }
        },
    )
}

/**
 * The delete confirmation — the question the toggle's long press asks before doing the one
 * thing there is no coming back from. The confirm button carries the error color, MD3's way
 * of marking a destructive action.
 */
@Composable
private fun DeleteDeviceDialog(
    device: BackendDevice,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = null,
                tint = colors.error,
            )
        },
        title = { Text(stringResource(R.string.devices_delete_title)) },
        text = { Text(stringResource(R.string.devices_delete_text, device.name)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
            ) {
                Text(stringResource(R.string.devices_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.devices_cancel))
            }
        },
    )
}

/**
 * The config dialog: the device's WireGuard config as a QR code, with "Copy" and "Download"
 * on the left and "Done" on the right, per the screen's own rhythm. The QR is generated
 * locally from the fetched text — theme-colored, quiet zone included — and the text itself
 * stays in memory only: to the QR, to the clipboard, or to a file the user picked.
 * A fetch that fails says so and offers a retry.
 */
@Composable
private fun ConfigDialog(
    device: BackendDevice,
    loadConfig: suspend (Int) -> String?,
    onDismiss: () -> Unit,
    onDownload: (fileName: String, text: String) -> Unit,
) {
    var configText by remember(device.id) { mutableStateOf<String?>(null) }
    var failed by remember(device.id) { mutableStateOf(false) }
    var loadTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(device.id, loadTick) {
        configText = null
        failed = false
        configText = loadConfig(device.id)
        if (configText == null) failed = true
    }

    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val poke = rememberHapticPoke()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // The MD3 dialog recipe: an extra-large shape over the high container tone.
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = colors.surfaceContainerHigh,
            contentColor = colors.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .padding(horizontal = screenMargin()),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.devices_config_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        configText != null -> QrCode(
                            content = configText.orEmpty(),
                            modifier = Modifier.fillMaxSize(),
                        )
                        failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.devices_config_load_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                            TextButton(onClick = { loadTick++ }) {
                                Text(stringResource(R.string.devices_config_load_retry))
                            }
                        }
                        else -> LoadingIndicator()
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            poke()
                            configText?.let { clipboard.setText(AnnotatedString(it)) }
                        },
                        enabled = configText != null,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_content_copy),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.devices_config_copy))
                    }
                    TextButton(
                        onClick = {
                            configText?.let { text ->
                                onDownload(device.confFilename ?: "device-${device.id}.conf", text)
                            }
                        },
                        enabled = configText != null,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_download),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.devices_config_download))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.devices_config_done))
                    }
                }
            }
        }
    }
}

private const val SHAKE_MILLIS = 700
private const val SHAKE_WOBBLES = 3.0
private val SHAKE_AMPLITUDE = 8.dp

/** The panel's own maxlength on a device name. */
private const val MAX_DEVICE_NAME_LENGTH = 32
