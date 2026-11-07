package slop.miho.chumpy.ui.main

import android.content.Intent

import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.ColorUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import slop.miho.chumpy.R
import slop.miho.chumpy.data.DailyTraffic
import slop.miho.chumpy.data.FlagStyle
import slop.miho.chumpy.db.ProfileEntity
import slop.miho.chumpy.db.ProfileType
import slop.miho.chumpy.db.STANDALONE_SUBSCRIPTION_ID
import slop.miho.chumpy.db.SubscriptionEntity
import slop.miho.chumpy.db.balancerMemberKeys
import slop.miho.chumpy.ui.ServersUiState
import slop.miho.chumpy.ui.scan.ScannerActivity
import slop.miho.chumpy.ui.components.ActionTile
import slop.miho.chumpy.ui.components.CardCallout
import slop.miho.chumpy.ui.components.CardMenu
import slop.miho.chumpy.ui.components.CookieBackdrop
import slop.miho.chumpy.ui.components.EmojiFlag
import slop.miho.chumpy.ui.components.ErrorCard
import slop.miho.chumpy.ui.components.InfoChip
import slop.miho.chumpy.ui.components.PrideFlagIcon
import slop.miho.chumpy.ui.components.SubmitFieldRow
import slop.miho.chumpy.ui.components.splitLeadingFlag
import slop.miho.chumpy.ui.components.splitLeadingPrideFlag
import slop.miho.chumpy.ui.components.rememberHapticPoke
import slop.miho.chumpy.ui.components.rememberHapticSoftPoke
import slop.miho.chumpy.ui.components.WavyPullToRefreshBox
import slop.miho.chumpy.ui.components.pressBounce
import slop.miho.chumpy.ui.components.pullShiftedPadding
import slop.miho.chumpy.ui.components.focusRing
import slop.miho.chumpy.ui.wallpapers.LocalSurfaceOpacity
import slop.miho.chumpy.ui.wallpapers.ReportBarCoverage
import slop.miho.chumpy.ui.wallpapers.withSurfaceOpacity
import slop.miho.chumpy.ui.theme.LocalUiSettings

/**
 * The servers screen: one subscription card up top (the database tile with the reload
 * button under it; the card's tap unrolls the subscription list below it with the same
 * fold the settings pages use, pushing the servers down — they stay in place, drawn dim
 * and taken out of touch while the chooser stands, and a tap anywhere over them folds
 * it back up), then that subscription's servers as one segmented list. Pull-to-refresh
 * measures every server through the core — the list is always reachable, tunnel or
 * not, since picking a server never needs one. The rows draw the flag a name begins
 * with (per [flagStyle]); the name itself is shown without it.
 */
@Composable
fun ServersScreen(
    subscriptions: List<SubscriptionEntity>,
    selectedSubscription: SubscriptionEntity?,
    state: ServersUiState,
    week: List<DailyTraffic> = emptyList(),
    manualCount: Int = 0,
    flagStyle: FlagStyle = FlagStyle.TWEMOJI,
    tunnelUp: Boolean = false,
    onUpdate: () -> Unit,
    onPing: () -> Unit,
    onSelectSubscription: (String) -> Unit,
    onAddSubscription: (url: String, name: String?, onDone: (String?) -> Unit) -> Unit,
    onDeleteSubscription: (String) -> Unit,
    onDeleteManualProfile: (String) -> Unit,
    onTogglePinned: (String, Boolean) -> Unit,
    onToggleSubscriptionPinned: (String, Boolean) -> Unit,
    onToggleConcealed: (String, Boolean) -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The subscription dropdown's open state; survives page switches so a dialog opened
    // from an unfolded card still has its list waiting behind it.
    var dropdownOpen by rememberSaveable { mutableStateOf(false) }

    // The add row's own open face and its text, riding the same survival law.
    var addOpen by rememberSaveable { mutableStateOf(false) }
    var addUrl by rememberSaveable { mutableStateOf("") }

    // The add row's open face folds with the chooser itself: the field and its two
    // doors never outlive the menu they opened from — reopening starts the list clean.
    LaunchedEffect(dropdownOpen) {
        if (!dropdownOpen) addOpen = false
    }

    // The reveal mode's own flag — the header's chip drives it. Keyed to the selected
    // subscription: switching groups closes the curtain, the next one opens with its
    // own hidden ones out of sight again.
    var revealConcealed by rememberSaveable(selectedSubscription?.id) { mutableStateOf(false) }

    // Back closes the fold the way a tap anywhere over the dimmed list does — but the
    // gesture gets to lead it. The fold keeps two clocks of its own, the same two the
    // AnimatedVisibility ran it by: a spring for the height, a quicker fade for the
    // content. A carried-all-the-way gesture hands them back where the finger left
    // them and the close finishes on the settle the flag's flip always runs; an
    // abandoned one walks them home through the composition's own scope — the
    // handler's cancelled coroutine must not carry the walk, it dies mid-stride.
    val motion = MaterialTheme.motionScheme
    val foldScope = rememberCoroutineScope()
    val foldHeight = remember { Animatable(if (dropdownOpen) 1f else 0f) }
    val foldAlpha = remember { Animatable(if (dropdownOpen) 1f else 0f) }
    // The gesture's own hands on the two clocks, NaN while nobody holds them — the
    // same law the page strip's own drag follows.
    var foldHeightLead by remember { mutableFloatStateOf(Float.NaN) }
    var foldAlphaLead by remember { mutableFloatStateOf(Float.NaN) }
    LaunchedEffect(dropdownOpen) {
        foldHeight.animateTo(if (dropdownOpen) 1f else 0f, motion.defaultSpatialSpec())
    }
    // The fade rides its own quicker clock, the AnimatedVisibility's own pairing.
    LaunchedEffect(dropdownOpen) {
        foldAlpha.animateTo(if (dropdownOpen) 1f else 0f, motion.defaultEffectsSpec())
    }
    // The fold as it stands: the gesture's hands while it holds it, the clocks' own
    // otherwise.
    val foldShown = { if (!foldHeightLead.isNaN()) foldHeightLead else foldHeight.value }
    val foldFade = { if (!foldAlphaLead.isNaN()) foldAlphaLead else foldAlpha.value }

    PredictiveBackHandler(enabled = dropdownOpen) { progress ->
        try {
            progress.collect { event ->
                // The fade runs ahead of the height — the same lead the fold's own
                // close keeps between them.
                foldHeightLead = 1f - event.progress
                foldAlphaLead = (1f - event.progress).coerceAtLeast(0.25f).let {
                    (it - 0.25f) / 0.75f
                }
            }
            // Carried all the way: the clocks are handed back where the finger left
            // them and the close finishes on the settle the flag's flip always runs.
            foldHeight.snapTo(if (foldHeightLead.isNaN()) 1f else foldHeightLead)
            foldAlpha.snapTo(if (foldAlphaLead.isNaN()) 1f else foldAlphaLead)
            foldHeightLead = Float.NaN
            foldAlphaLead = Float.NaN
            dropdownOpen = false
        } catch (e: CancellationException) {
            // Abandoned: the clocks walk themselves home through the composition's
            // own scope — the handler's cancelled coroutine must not carry the walk,
            // it dies mid-stride and leaves the fold stranded.
            if (!foldHeightLead.isNaN()) {
                val hand = foldHeightLead
                val fadeHand = foldAlphaLead
                foldHeightLead = Float.NaN
                foldAlphaLead = Float.NaN
                foldScope.launch {
                    foldHeight.snapTo(hand)
                    foldAlpha.snapTo(fadeHand)
                    launch { foldHeight.animateTo(1f, motion.defaultSpatialSpec()) }
                    foldAlpha.animateTo(1f, motion.defaultEffectsSpec())
                }
            }
            throw e
        }
    }

    // The row menu a long press opens: the row holding it, whatever kind of row — a
    // subscription's, a loose config's, a subscription's own config. One menu on the
    // whole screen; while it stands, every card but its host drops into the chooser's
    // own half-light, and the popup's scrim keeps the list out of touch on its own.
    var menuHost by remember { mutableStateOf<String?>(null) }
    // The menu's second face: the delete ask — the menu traded for its Да and Нет
    // while the question itself rides above the row. One flag serves whichever row
    // holds the menu, because only one can.
    var menuAskingDelete by remember { mutableStateOf(false) }

    // Opening a menu (or closing one) always starts from the first face.
    fun openMenu(id: String?) {
        menuHost = id
        menuAskingDelete = false
    }

    // Both menus' copies ride the same hand: whatever a row was born from, verbatim.
    val clipboard = LocalClipboardManager.current
    val foldPoke = rememberHapticPoke()

    // Rows sit under the floating bar while the list outgrows the screen.
    val listState = rememberLazyListState()
    val contentUnderBar by remember {
        derivedStateOf { listState.canScrollForward || listState.canScrollBackward }
    }
    val rowsReachBar by rememberContentUnderBar(listState)
    ReportBarCoverage(contentUnderBar, underlaid = rowsReachBar)

    // The wave grows with the pull while a ping sweep runs; the servers are reachable with
    // or without the tunnel, so the pull always answers.
    WavyPullToRefreshBox(
        isRefreshing = state.isPinging,
        onRefresh = onPing,
        enabled = true,
        modifier = modifier.fillMaxSize(),
    ) { pullShift ->
        // Rows travel under the status bar and the floating bar (fading out behind the
        // shell gradients) and rest clear of both thanks to the content padding.
        val topContentPadding = WindowInsets.statusBars.asPaddingValues()
            .calculateTopPadding() + 24.dp
        val bottomContentPadding = WindowInsets.navigationBars.asPaddingValues()
            .calculateBottomPadding() + LocalBarReservedHeight.current
        val rows = remember(state.profiles, state.concealed, revealConcealed) {
            if (revealConcealed) (state.profiles + state.concealed).sortedWith(
                compareBy({ !it.pinned }, { it.order }, { it.remarks }),
            ) else state.profiles
        }
        val foldAlpha = lerp(1f, 0.45f, foldShown())
        val headerAlpha by animateFloatAsState(
            targetValue = if (menuHost != null) 0.45f else foldAlpha,
            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(), label = "servers dim",
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().screenPadding(),
            contentPadding = remember(topContentPadding, bottomContentPadding, pullShift) {
                pullShiftedPadding(topContentPadding, bottomContentPadding, pullShift)
            },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            state.error?.let { error ->
                item(key = "error") {
                    Box(Modifier.padding(bottom = 6.dp)) {
                        ErrorCard(message = stringResource(R.string.subscription_update_failed, error))
                    }
                }
            }
            item(key = "subscription") {
                Box(Modifier.padding(bottom = 6.dp)) {
                    SubscriptionCard(subscription = selectedSubscription, isUpdating = state.isUpdating,
                        expanded = dropdownOpen, dimmed = menuHost != null, week = week,
                        onToggle = { dropdownOpen = !dropdownOpen }, onUpdate = onUpdate)
                }
            }
            item(key = "header") {
                Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    if (foldShown() > 0f) {
                        Box(Modifier.fillMaxWidth().clipToBounds()
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                val height = (placeable.height * foldShown()).roundToInt()
                                layout(placeable.width, height) { placeable.place(0, height - placeable.height) }
                            }.graphicsLayer { alpha = foldFade() }) {
                            Column(Modifier.padding(bottom = 8.dp)) {
                                SubscriptionDropdown(
                                    subscriptions = subscriptions,
                                    selectedId = selectedSubscription?.id,
                                    week = week,
                                    manualCount = manualCount,
                                    addOpen = addOpen,
                                    addUrl = addUrl,
                                    onAddOpenChange = { addOpen = it },
                                    onAddUrlChange = { addUrl = it },
                                    onAdd = { url, onDone -> onAddSubscription(url, null, onDone) },
                                    onSelect = { id ->
                                        dropdownOpen = false
                                        if (id != selectedSubscription?.id) onSelectSubscription(id)
                                    },
                                    onDeleteRequest = { onDeleteSubscription(it.id) },
                                    onTogglePinned = onToggleSubscriptionPinned,
                                    menuHost = menuHost,
                                    onMenuHostChange = { openMenu(it) },
                                    menuAskingDelete = menuAskingDelete,
                                    onMenuAskingChange = { menuAskingDelete = it },
                                    onCopyText = { text -> clipboard.setText(AnnotatedString(text)) },
                                )
                            }
                        }
                    }
                    Box {
                        Box(Modifier.graphicsLayer { alpha = headerAlpha }) {
                            ServersHeader(state = state, expired = selectedSubscription?.let { isExpired(it) } == true,
                                onToggleReveal = { revealConcealed = !revealConcealed })
                        }
                        if (dropdownOpen) {
                            Box(Modifier.matchParentSize().clickable(
                                interactionSource = remember { MutableInteractionSource() }, indication = null,
                            ) { foldPoke(); dropdownOpen = false })
                        }
                    }
                }
            }
            if (rows.isEmpty()) item(key = "empty") {
                Box(Modifier.graphicsLayer { alpha = headerAlpha }) {
                    ServersEmpty()
                    if (dropdownOpen) {
                        Box(Modifier.matchParentSize().clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                        ) { foldPoke(); dropdownOpen = false })
                    }
                }
            }
            itemsIndexed(rows, key = { _, profile -> "profile:${profile.id}" }, contentType = { _, _ -> "server" }) { index, profile ->
                Box(Modifier.graphicsLayer { alpha = foldAlpha }) {
                    ServerGroupRow(
                        profile = profile,
                        index = index,
                        total = rows.size,
                        selectedId = state.selectedProfileId,
                        flagStyle = flagStyle,
                        tunnelUp = tunnelUp,
                        onSelect = onSelect,
                        onDelete = { onDeleteManualProfile(it.id) },
                        onTogglePinned = onTogglePinned,
                        onToggleConcealed = onToggleConcealed,
                        menuHost = menuHost,
                        onMenuHostChange = { openMenu(it) },
                        menuAskingDelete = menuAskingDelete,
                        onMenuAskingChange = { menuAskingDelete = it },
                        onCopyText = { text -> clipboard.setText(AnnotatedString(text)) },
                    )
                    if (dropdownOpen) {
                        Box(Modifier.matchParentSize().clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                        ) { foldPoke(); dropdownOpen = false })
                    }
                }
            }
        }
    }
}

/**
 * The chip row above the server list: how many servers the subscription carries and how
 * many answered the last sweep — a real-ping answer, not a cache.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServersHeader(
    state: ServersUiState,
    expired: Boolean,
    onToggleReveal: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state.profiles.isNotEmpty()) {
            // Balancers are picks over servers, not servers — the counts count servers.
            val servers = state.profiles.filter { it.type != ProfileType.BALANCER }
            InfoChip(
                value = stringResource(R.string.servers_total, servers.size),
                icon = R.drawable.ic_servers,
                animateValue = true,
            )
            val reachable = servers.count { (it.testDelayMillis ?: -1L) >= 0L }
            if (reachable > 0) {
                InfoChip(
                    value = stringResource(R.string.servers_online, reachable),
                    icon = R.drawable.ic_plug,
                    animateValue = true,
                )
            }
        }
        // The subscription's own red badge among the counts: a panel whose calendar
        // has already run out says so at a glance, whatever the list's own health.
        if (expired) {
            InfoChip(
                value = stringResource(R.string.subscription_expired_chip),
                container = colors.errorContainer.withSurfaceOpacity(),
                content = colors.onErrorContainer,
            )
        }
        // The hidden ones' own count, the reveal mode's door: a tap calls them back into
        // their own block above the list, another puts them away again. The chip stays
        // gone while nothing is hidden — a list that never hides keeps its row quiet.
        // The eye alone carries the meaning; the number alone carries the count.
        if (state.concealed.isNotEmpty()) {
            InfoChip(
                value = state.concealed.size.toString(),
                icon = R.drawable.ic_visibility_off,
                animateValue = true,
                onClick = onToggleReveal,
            )
        }
    }
}

/**
 * The subscription card, the devices screen's row language grown up: the database tile
 * on its square backing — the list's rows wear the round one; the card keeps the square,
 * like it always did — over what the panel reports, the text standing against the tile
 * stack's top; with nothing to report but the name, the tile carries the reload button
 * at its side and the name centers beside them. The card's own tap trades the card for
 * the chooser's ask: the report rides down and out of view, and on the same backing
 * steps in the ask — the database tile alone, the call to action, the arrow that folds
 * it back — while the button's tap stays a different ask: pulling new servers in.
 * While some row's menu stands open, the card dims with everything else — the menu's
 * own spotlight.
 */
@Composable
private fun SubscriptionCard(
    subscription: SubscriptionEntity?,
    isUpdating: Boolean,
    expanded: Boolean,
    dimmed: Boolean,
    week: List<DailyTraffic>,
    onToggle: () -> Unit,
    onUpdate: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val poke = rememberHapticPoke()
    val containerColor by animateColorAsState(
        targetValue = (if (expanded) colors.surfaceContainerHigh else colors.surfaceContainer)
            .copy(alpha = LocalSurfaceOpacity.current),
        animationSpec = motion.defaultEffectsSpec(),
        label = "subscription container",
    )
    val contentColor by animateColorAsState(
        targetValue = colors.onSurface,
        animationSpec = motion.defaultEffectsSpec(),
        label = "subscription content",
    )
    val supportingColor by animateColorAsState(
        targetValue = colors.onSurfaceVariant,
        animationSpec = motion.defaultEffectsSpec(),
        label = "subscription supporting",
    )
    // A row's open menu dims the card with the rest — the same half-light the chooser
    // lays over the servers.
    val dimmedAlpha by animateFloatAsState(
        targetValue = if (dimmed) 0.45f else 1f,
        animationSpec = motion.defaultEffectsSpec(),
        label = "subscription card dim",
    )

    Surface(
        onClick = {
            poke()
            onToggle()
        },
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = dimmedAlpha },
    ) {
        // The card itself trades faces: opening, the report rides down and out of view
        // while the chooser's ask drops in from above on the same backing; closing,
        // the report climbs back up and the ask retracts. The height follows the swap.
        AnimatedContent(
            targetState = expanded,
            transitionSpec = {
                val direction = if (targetState) 1 else -1
                // The height rides along with the face swap, unclipped while it goes.
                ContentTransform(
                    targetContentEnter = slideInVertically(motion.defaultSpatialSpec()) {
                        -direction * it / 2
                    } + fadeIn(motion.defaultEffectsSpec()),
                    initialContentExit = slideOutVertically(motion.defaultSpatialSpec()) {
                        direction * it / 2
                    } + fadeOut(motion.defaultEffectsSpec()),
                    sizeTransform = SizeTransform(clip = false),
                )
            },
            label = "subscription card",
        ) { chooser ->
            if (chooser) {
                SubscriptionChooserFace(
                    contentColor = contentColor,
                    supportingColor = supportingColor,
                )
            } else {
                // The panel's report decides the stance: a full one stands the text up
                // against the top of the tile-and-refresh stack; a name alone centers
                // beside the tile, the refresh button riding at the tile's side — no
                // dead air under a lone title. The stance itself swaps softly when a
                // reload changes what there is to show.
                val sub = subscription
                val hasReport = sub != null && (
                    sub.totalBytes > 0 ||
                        daysTitle(sub.expireEpochSeconds) != null ||
                        consumedText(sub) != null
                    )
                AnimatedContent(
                    targetState = hasReport,
                    transitionSpec = {
                        ContentTransform(
                            targetContentEnter = fadeIn(motion.defaultEffectsSpec()),
                            initialContentExit = fadeOut(motion.defaultEffectsSpec()),
                            sizeTransform = SizeTransform(clip = false),
                        )
                    },
                    label = "subscription report stance",
                ) { full ->
                    if (full && sub != null) {
                        SubscriptionReportFace(
                            subscription = sub,
                            isUpdating = isUpdating,
                            week = week,
                            contentColor = contentColor,
                            supportingColor = supportingColor,
                            onUpdate = onUpdate,
                        )
                    } else {
                        SubscriptionCompactFace(
                            subscription = sub,
                            isUpdating = isUpdating,
                            contentColor = contentColor,
                            supportingColor = supportingColor,
                            onUpdate = onUpdate,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The report face with a full panel report: the tile-and-refresh stack on its square
 * backing — the list's rows wear the round one, the card keeps the square — and the
 * text standing against its top: the name over what the panel reports, the chevron
 * anchored at the foot, clear of the bars.
 */
@Composable
private fun SubscriptionReportFace(
    subscription: SubscriptionEntity,
    isUpdating: Boolean,
    week: List<DailyTraffic>,
    contentColor: Color,
    supportingColor: Color,
    onUpdate: () -> Unit,
) {
    Box {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DatabaseTile(
                    iconRes = if (subscription.id == STANDALONE_SUBSCRIPTION_ID) {
                        R.drawable.ic_cards_stack
                    } else {
                        R.drawable.ic_database
                    },
                )
                if (subscription.url.isNotEmpty()) {
                    UpdateButton(isUpdating = isUpdating, onClick = onUpdate)
                }
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SubscriptionTitleRow(
                    subscription = subscription,
                    contentColor = contentColor,
                    supportingColor = supportingColor,
                )
                // What the panel reports: the bar runs to the column's end, the
                // numbers wait under it.
                SubscriptionInfo(
                    subscription = subscription,
                    week = week,
                    supportingColor = supportingColor,
                    textInset = 32.dp,
                )
            }
        }
        // The chevron points the card's own tap at the list it unfolds — anchored at
        // the card's foot, clear of the bars above it.
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = stringResource(R.string.subscription_expand),
            tint = supportingColor,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp)
                .size(24.dp)
                .graphicsLayer { rotationZ = 90f },
        )
    }
}

/**
 * The report face with nothing but the name to show: the tile with its refresh button
 * standing beside it at the tile's height, the name centered in what remains — the last
 * update's stamp waiting under it on its own line — and the chevron inline at the end,
 * the way the chooser's face carries it.
 */
@Composable
private fun SubscriptionCompactFace(
    subscription: SubscriptionEntity?,
    isUpdating: Boolean,
    contentColor: Color,
    supportingColor: Color,
    onUpdate: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The tile with the button tight against it — the pair reads as one control —
        // and the title a full gap away.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DatabaseTile(
                iconRes = if (subscription?.id == STANDALONE_SUBSCRIPTION_ID) {
                    R.drawable.ic_cards_stack
                } else {
                    R.drawable.ic_database
                },
            )
            if ((subscription?.url?.isNotEmpty()) == true) {
                UpdateButton(isUpdating = isUpdating, onClick = onUpdate, width = 36.dp, height = 48.dp)
            }
        }
        SubscriptionTitleRow(
            subscription = subscription,
            contentColor = contentColor,
            supportingColor = supportingColor,
            modifier = Modifier.weight(1f),
            stampBelow = true,
        )
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = stringResource(R.string.subscription_expand),
            tint = supportingColor,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer { rotationZ = 90f },
        )
    }
}

/**
 * The subscription tile on its square backing — the database everywhere the collection
 * itself shows, the home-storage mark where the card folds into its collapse button.
 */
@Composable
private fun DatabaseTile(iconRes: Int = R.drawable.ic_database) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer,
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * The subscription's name with the last update's stamp — riding at its level, flush
 * against the right edge, or waiting under it on its own line ([stampBelow], the
 * compact face's way).
 */
@Composable
private fun SubscriptionTitleRow(
    subscription: SubscriptionEntity?,
    contentColor: Color,
    supportingColor: Color,
    modifier: Modifier = Modifier,
    stampBelow: Boolean = false,
) {
    val stamp = subscription?.let { updatedText(it) }
    if (stampBelow) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = subscription?.name?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.subscription_none),
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            stamp?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = supportingColor,
                    maxLines = 1,
                )
            }
        }
        return
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = subscription?.name?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.subscription_none),
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        subscription?.let { sub ->
            updatedText(sub)?.let { stamp ->
                Text(
                    text = stamp,
                    style = MaterialTheme.typography.bodyMedium,
                    color = supportingColor,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/** The panel's calendar, already run out: expiry reported and the moment passed. */
private fun isExpired(subscription: SubscriptionEntity): Boolean =
    subscription.expireEpochSeconds > 0 &&
        subscription.expireEpochSeconds <= System.currentTimeMillis() / 1000

/**
 * The chooser's own face, the card the report card becomes while the list is open: the
 * same backing, carrying only the home-storage tile — the collapse button the card turns
 * into — at the tile's full size, the call to action, and the arrow that folds the
 * chooser back.
 */
@Composable
private fun SubscriptionChooserFace(
    contentColor: Color,
    supportingColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DatabaseTile(iconRes = R.drawable.ic_home_storage)
        Text(
            text = stringResource(R.string.subscription_choose),
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = stringResource(R.string.subscription_collapse),
            tint = supportingColor,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer { rotationZ = 270f },
        )
    }
}

/**
 * The update button: the font picker's import button turned on its side — the same
 * stadium, the same press bounce. It measures itself against the tile it belongs to:
 * as wide as the tile but shorter while stacked under it, as tall but narrower while
 * riding beside it — the round-trip arrows spinning slowly while the fetch runs.
 */
@Composable
private fun UpdateButton(
    isUpdating: Boolean,
    onClick: () -> Unit,
    width: Dp = 48.dp,
    height: Dp = 36.dp,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val poke = rememberHapticPoke()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    var taps by remember { mutableIntStateOf(0) }
    val resting = colors.secondaryContainer
    val container by animateColorAsState(
        targetValue = if (pressed) lerp(resting, colors.onSurface, 0.1f) else resting,
        animationSpec = motion.defaultEffectsSpec(),
        label = "subscription update container",
    )
    val spin = if (isUpdating) {
        rememberInfiniteTransition(label = "subscription update spin")
            .animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 1_400, easing = LinearEasing)),
                label = "subscription update spin angle",
            )
    } else {
        null
    }
    Surface(
        onClick = {
            poke()
            taps++
            onClick()
        },
        interactionSource = interactionSource,
        shape = CircleShape,
        color = container,
        contentColor = colors.onSecondaryContainer,
        modifier = Modifier
            .size(width = width, height = height)
            .pressBounce(pressed, taps)
            // The controller's ring rides before the focus target, outside the
            // stadium's own paint — the round ends matching.
            .focusRing(circular = true),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_renew),
                contentDescription = stringResource(R.string.subscription_update),
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = spin?.value ?: 0f },
            )
        }
    }
}

/**
 * The unfolded subscription list: one row per subscription — the servers' own segmented
 * container, Surface подложки, cookie-marked pick and all — with the "add subscription"
 * row closing the list off, one of the rows itself, its icon on the square backing the
 * card's tile wears. A long press on a subscription's row opens the row's own menu: the
 * pin it will one day carry, the URL it was added from (there for the copying, verbatim),
 * and the delete that asks the heavier question. The loose configs' row asks nothing —
 * it owns no subscription to ask about, its members answer their own long press in the
 * server list below.
 */
@Composable
private fun SubscriptionDropdown(
    subscriptions: List<SubscriptionEntity>,
    selectedId: String?,
    week: List<DailyTraffic>,
    manualCount: Int,
    addOpen: Boolean,
    addUrl: String,
    onAddOpenChange: (Boolean) -> Unit,
    onAddUrlChange: (String) -> Unit,
    onAdd: (url: String, onDone: (String?) -> Unit) -> Unit,
    onSelect: (String) -> Unit,
    onDeleteRequest: (SubscriptionEntity) -> Unit,
    onTogglePinned: (String, Boolean) -> Unit,
    menuHost: String?,
    onMenuHostChange: (String?) -> Unit,
    menuAskingDelete: Boolean,
    onMenuAskingChange: (Boolean) -> Unit,
    onCopyText: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val outer = MaterialTheme.shapes.extraLarge
    val inner = MaterialTheme.shapes.extraSmall
    val density = LocalDensity.current
    // The standalone group leads the list whenever it has something in it — the cards
    // stack's own row, first above the panels' own — and stays gone entirely while it
    // doesn't, so the list never advertises an empty pocket.
    val groups = if (manualCount > 0) {
        listOf(
            SubscriptionEntity(
                id = STANDALONE_SUBSCRIPTION_ID,
                name = stringResource(R.string.standalone_name),
            ),
        ) + subscriptions
    } else {
        subscriptions
    }
    // The corner radii come out of the theme's shapes as px against this density, back
    // into Dp — the seam animates that Dp, and a shape rebuilt around a new Dp is a new
    // shape, so the outline follows the animation frame by frame. (Dp corners ignore
    // the shape size they are handed, which is all the theme's shapes are made of.)
    fun CornerSize.dp(): Dp = with(density) { toPx(Size(1f, 1f), this).toDp() }
    val outerTop = outer.topStart.dp()
    val outerBottom = outer.bottomStart.dp()
    val innerTop = inner.topStart.dp()
    val innerBottom = inner.bottomStart.dp()
    // The seam the add row rides on is the block's own last bottom corners: the card's
    // outer ones while the row stands folded into it, the inner seam once it steps out.
    val groupBottom by animateDpAsState(
        targetValue = if (addOpen) outerBottom else innerBottom,
        animationSpec = motion.defaultSpatialSpec(),
        label = "subscription block bottom corner",
    )
    // The add row's own top corners bloom the same way, in step with the seam.
    val addTop by animateDpAsState(
        targetValue = if (addOpen || groups.isEmpty()) outerTop else innerTop,
        animationSpec = motion.defaultSpatialSpec(),
        label = "add row top corner",
    )
    // The gap the add row opens under itself: 2dp of seam grows to the button gap's 8.
    val addGap by animateDpAsState(
        targetValue = if (addOpen) 6.dp else 0.dp,
        animationSpec = motion.defaultSpatialSpec(),
        label = "add row gap",
    )
    // The add row wears the same half-light the rows around it do while one of them
    // holds its menu open — the invitation dims with the rest of the list.
    val addDim by animateFloatAsState(
        targetValue = if (menuHost != null) 0.45f else 1f,
        animationSpec = motion.defaultEffectsSpec(),
        label = "add row dim",
    )
    // The subscriptions and the add row stand as one segmented block; the add row's
    // opening is what parts it — the seam under the block blooms into the card's own
    // corners and the button gap of air slides in beneath it.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(outer),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        groups.forEachIndexed { index, subscription ->
            val first = index == 0
            val last = index == groups.lastIndex
            SubscriptionRow(
                subscription = subscription,
                selected = subscription.id == selectedId,
                week = week,
                icon = if (subscription.id == STANDALONE_SUBSCRIPTION_ID) {
                    R.drawable.ic_cards_stack
                } else {
                    R.drawable.ic_database
                },
                onClick = { onSelect(subscription.id) },
                // Only a real subscription's row carries the menu — the pin, its URL,
                // the delete that asks the heavier question. The loose configs' row
                // owns no subscription, so it owns no menu either: its members answer
                // their own long press in the server list below.
                menu = if (subscription.id == STANDALONE_SUBSCRIPTION_ID) {
                    null
                } else {
                    RowMenu(
                        open = menuHost == subscription.id,
                        onOpenChange = { open -> onMenuHostChange(subscription.id.takeIf { open }) },
                        askingDelete = menuAskingDelete,
                        onAskingDeleteChange = onMenuAskingChange,
                        deleteQuestion = stringResource(R.string.subscription_delete_title),
                        canDelete = true,
                        canCopy = true,
                        copyEnabled = subscription.url.isNotBlank(),
                        onCopy = { onCopyText(subscription.url) },
                        onDelete = { onDeleteRequest(subscription) },
                        pinned = subscription.pinned,
                        onTogglePinned = { onTogglePinned(subscription.id, it) },
                    )
                },
                dimmed = menuHost != null && menuHost != subscription.id,
                shape = RoundedCornerShape(
                    topStart = if (first) outerTop else innerTop,
                    topEnd = if (first) outerTop else innerTop,
                    bottomStart = if (last) groupBottom else innerBottom,
                    bottomEnd = if (last) groupBottom else innerBottom,
                ),
            )
        }
        AddSubscriptionRow(
            open = addOpen,
            url = addUrl,
            onOpenChange = onAddOpenChange,
            onUrlChange = onAddUrlChange,
            onAdd = onAdd,
            topCorners = addTop,
            color = colors.surfaceContainer.copy(alpha = LocalSurfaceOpacity.current),
            // The spring's overshoot dips below zero on the way to the seam — padding
            // takes none of that, it is clamped at the seam itself.
            modifier = Modifier
                .graphicsLayer { alpha = addDim }
                .padding(top = addGap.coerceAtLeast(0.dp)),
        )
    }
}

/**
 * The add row, the list's standing invitation — and, tapped, its own working face: the
 * card's face swap laid sideways, the invitation riding out to the right while the
 * welcome screen's field steps in from the left, the field's own cross — the compact
 * card's tight pair — as the way back. Under the field the two doors unroll like a
 * dropdown menu settling open. Submitting folds the face back with the field cleared;
 * a failure leaves it open, the URL kept for another try.
 */
@Composable
private fun AddSubscriptionRow(
    open: Boolean,
    url: String,
    onOpenChange: (Boolean) -> Unit,
    onUrlChange: (String) -> Unit,
    onAdd: (url: String, onDone: (String?) -> Unit) -> Unit,
    topCorners: Dp,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val poke = rememberHapticPoke()
    val density = LocalDensity.current
    // The add in flight: the tile wears Material's loading indicator from the submit
    // until onDone lands, so the row answers the tap the moment it is carried out.
    var adding by remember { mutableStateOf(false) }
    // The card's own corners out of the theme's shapes as Dp, the block's seam-riding
    // tops handed in animated — the rest are fixed: the bottom always the card's outer
    // ones, the seam between the field and the two doors always the inner ones.
    fun CornerSize.dp(): Dp = with(density) { toPx(Size(1f, 1f), this).toDp() }
    val outerBottom = MaterialTheme.shapes.extraLarge.bottomStart.dp()
    val innerTop = MaterialTheme.shapes.extraSmall.topStart.dp()
    val innerBottom = MaterialTheme.shapes.extraSmall.bottomStart.dp()
    // The file door, the welcome screen's own: whatever the picker hands over rides the
    // field's submit as if pasted — a WireGuard conf, a share link, a raw config.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { slop.miho.chumpy.data.ImportedFile.readText(it) }
                }.getOrNull()
            }
            if (text == null) {
                onAdd("") { error -> if (error == null) onOpenChange(false) }
            } else {
                onAdd(text) { error ->
                    if (error == null) {
                        onUrlChange("")
                        onOpenChange(false)
                    }
                }
            }
        }
    }
    // The camera door, the file door's own twin: the scanner's decoded text rides the
    // field's submit the same way — the field clears and the face folds on success.
    val qrScanner = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = result.data?.getStringExtra(ScannerActivity.KEY_TEXT)
        if (text == null) return@rememberLauncherForActivityResult
        onAdd(text) { error ->
            if (error == null) {
                onUrlChange("")
                onOpenChange(false)
            }
        }
    }
    // The card's outline: the animated top corners (the block's seam, blooming into the
    // card's own ones as it steps out) over the fixed outer bottom.
    val cardShape = RoundedCornerShape(
        topStart = topCorners,
        topEnd = topCorners,
        bottomStart = outerBottom,
        bottomEnd = outerBottom,
    )
    // No outer shell: each face carries its own surface, so the 2dp seam between the
    // field and the two doors shows the backdrop through — the same seam the
    // subscription rows wear, not a stripe of the card's own color.
    AnimatedContent(
        targetState = open,
        modifier = modifier
            .fillMaxWidth(),
        transitionSpec = {
                // The card's own face swap laid sideways: opening, the invitation rides
                // out to the right while the field steps in from the left; closing, the
                // field retracts and the invitation comes back. The height rides along,
                // unclipped while it goes.
                val direction = if (targetState) 1 else -1
                ContentTransform(
                    targetContentEnter = slideInHorizontally(motion.defaultSpatialSpec()) {
                        -direction * it / 2
                    } + fadeIn(motion.defaultEffectsSpec()),
                    initialContentExit = slideOutHorizontally(motion.defaultSpatialSpec()) {
                        direction * it / 2
                    } + fadeOut(motion.defaultEffectsSpec()),
                    sizeTransform = SizeTransform(clip = false),
                )
            },
            label = "add subscription row",
        ) { expanded ->
            if (expanded) {
                // The open face stands as its own two-row block: the field's row over
                // the two doors', the same 2dp seam between them the subscription rows
                // wear — the divider asked of the card, the block's own language.
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(
                            topStart = topCorners,
                            topEnd = topCorners,
                            bottomStart = innerBottom,
                            bottomEnd = innerBottom,
                        ),
                        color = color,
                        contentColor = colors.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // 80, not the closed face's 72: the stadium's 16dp of air on the
                                // sides is matched above and below too — the gap reads square,
                                // not wider than tall. The height swap rides the face change.
                                .heightIn(min = 80.dp)
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SubmitFieldRow(
                                value = url,
                                onValueChange = onUrlChange,
                                onSubmit = {
                                    if (adding) return@SubmitFieldRow
                                    adding = true
                                    onAdd(url.trim()) { error ->
                                        adding = false
                                        if (error == null) {
                                            onUrlChange("")
                                            onOpenChange(false)
                                        }
                                    }
                                },
                                autofocus = true,
                                loading = adding,
                                onClose = { onOpenChange(false) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    // The two doors unroll beneath the field like a dropdown menu
                    // settling open: the face swap carries the field in, then the row
                    // itself rolls out — the welcome screen's import card's own layout,
                    // one seam under the URL. The title sits at 24, the field's own
                    // text column: 16 of row padding plus these 8. The padding is even
                    // on all sides: the tile's 48 + 16 + 16 matches the field row's 80.
                    AnimatedVisibility(
                        visibleState = remember {
                            MutableTransitionState(false).apply { targetState = true }
                        },
                        enter = expandVertically(motion.defaultSpatialSpec()) +
                            fadeIn(motion.defaultEffectsSpec()),
                        exit = shrinkVertically(motion.defaultSpatialSpec()) +
                            fadeOut(motion.defaultEffectsSpec()),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(
                                topStart = innerTop,
                                topEnd = innerTop,
                                bottomStart = outerBottom,
                                bottomEnd = outerBottom,
                            ),
                            color = color,
                            contentColor = colors.onSurface,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.import_config),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 8.dp),
                                )
                                ActionTile(
                                    icon = R.drawable.ic_upload_file,
                                    label = stringResource(R.string.import_config),
                                    onClick = { filePicker.launch(arrayOf("*/*")) },
                                )
                                ActionTile(
                                    icon = R.drawable.ic_qr_code_scanner,
                                    label = stringResource(R.string.scan_qr_code),
                                    indicated = false,
                                    onClick = {
                                        qrScanner.launch(
                                            Intent(context, ScannerActivity::class.java),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            } else {
                // The invitation's closed face: the card itself, the tap that opens it
                // riding the ripple of its own shape.
                Surface(
                    onClick = {
                        poke()
                        onOpenChange(true)
                    },
                    shape = cardShape,
                    color = color,
                    contentColor = colors.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp)
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = colors.secondaryContainer,
                            contentColor = colors.onSecondaryContainer,
                            modifier = Modifier.size(40.dp),
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
                            text = stringResource(R.string.subscription_add),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
}

/**
 * One subscription in the unfolded list: the server row's own anatomy, copied — the
 * [Surface] подложка with the segmented shape, the [ListItem] inside it, the 40dp
 * leading tile whose round backdrop morphs into the pick's cookie, the name and the
 * update stamp in the headline, what the panel reports in the supporting slot. A long
 * press is the delete question — for the rows that carry one; the loose configs' row
 * passes null and a press just falls through.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubscriptionRow(
    subscription: SubscriptionEntity,
    selected: Boolean,
    week: List<DailyTraffic>,
    icon: Int,
    onClick: () -> Unit,
    menu: RowMenu?,
    dimmed: Boolean,
    shape: Shape,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val poke = rememberHapticPoke()
    val containerColor by animateColorAsState(
        targetValue = (if (selected) colors.primaryContainer else colors.surfaceContainer)
            .copy(alpha = LocalSurfaceOpacity.current),
        animationSpec = motion.defaultEffectsSpec(),
        label = "subscription row container",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) colors.onPrimaryContainer else colors.onSurface,
        animationSpec = motion.defaultEffectsSpec(),
        label = "subscription row content",
    )
    val supportingColor by animateColorAsState(
        targetValue = if (selected) colors.onPrimaryContainer.copy(alpha = 0.78f) else colors.onSurfaceVariant,
        animationSpec = motion.defaultEffectsSpec(),
        label = "subscription row supporting",
    )
    val iconContainerColor by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.secondaryContainer,
        animationSpec = motion.defaultEffectsSpec(),
        label = "subscription row icon container",
    )
    val iconColor by animateColorAsState(
        targetValue = if (selected) colors.onPrimary else colors.onSecondaryContainer,
        animationSpec = motion.defaultEffectsSpec(),
        label = "subscription row icon",
    )
    // Another row's open menu lays its half-light over this one — the chooser's own
    // 0.45, worn one row at a time while the menu stands.
    val dimmedAlpha by animateFloatAsState(
        targetValue = if (dimmed) 0.45f else 1f,
        animationSpec = motion.defaultEffectsSpec(),
        label = "subscription row dim",
    )
    Surface(
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = dimmedAlpha },
    ) {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                // The tall rows' own measure: rows whose panel reports nothing (the
                // loose configs, the unlimited subs) keep the same height as the rest —
                // one even block, no short planks in the middle of it.
                .heightIn(min = 72.dp)
                .clip(shape)
                .combinedClickable(
                    role = Role.Button,
                    onClick = {
                        poke()
                        onClick()
                    },
                    // The long press opens the row's own menu — the pin, the URL, the
                    // delete — for the rows that have one.
                    onLongClick = menu?.let { rowMenu ->
                        {
                            poke()
                            rowMenu.onOpenChange(true)
                        }
                    },
                ),
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The pin's mark in the list's own scale: a pinned subscription
                    // wears the small pin at its name, the dropdown's own badge —
                    // the fold's window clips at its edge, so the corner carry the
                    // server rows use would lose the head outside it.
                    if (subscription.pinned) {
                        Icon(
                            painter = painterResource(R.drawable.ic_keep),
                            contentDescription = stringResource(R.string.card_menu_pinned),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(16.dp),
                        )
                    }
                    Text(
                        text = subscription.name.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.subscription_none),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    updatedText(subscription)?.let { stamp ->
                        Text(
                            text = stamp,
                            style = MaterialTheme.typography.bodyMedium,
                            color = supportingColor,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            },
            supportingContent = {
                SubscriptionRowInfo(
                    subscription = subscription,
                    week = week,
                    supportingColor = supportingColor,
                )
            },
            leadingContent = {
                // MD3 list leading icon: 24dp icon on a 40dp tonal container. Selection
                // morphs the circle into the slowly spinning MD3 cookie — the pick's mark.
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CookieBackdrop(
                        selected = selected,
                        color = iconContainerColor,
                        animated = true,
                    )
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(24.dp),
                    )
                }
            },
            colors = ListItemDefaults.colors(
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
        )
        // The row's own asks, standing over the row itself: the popup anchors to the
        // Surface, so the menu drops out centered under the whole row.
        menu?.let { rowMenu ->
            CardActionsMenu(
                expanded = rowMenu.open,
                onClose = { rowMenu.onOpenChange(false) },
                askingDelete = rowMenu.askingDelete,
                onAskingDeleteChange = rowMenu.onAskingDeleteChange,
                deleteQuestion = rowMenu.deleteQuestion,
                canDelete = rowMenu.canDelete,
                canCopy = rowMenu.canCopy,
                copyEnabled = rowMenu.copyEnabled,
                onCopy = rowMenu.onCopy,
                onDelete = rowMenu.onDelete,
                pinned = rowMenu.pinned,
                onTogglePinned = rowMenu.onTogglePinned,
            )
        }
    }
}

/**
 * What the panel reports about a subscription, on the card: one bar right after the
 * header, running to the column's end — whichever deadline lands first, the quota's
 * or the calendar's, so the bar always reports the subscription's real cliff. The
 * tunnel's own pace over the recorded week (see [averageDailyBytes]) says how many
 * days the quota holds; days running out sooner, no quota, or no pace on record yet
 * hand the slot to the calendar's stretch — paceless but with a quota, the quota's
 * drain keeps it, the one reading always available. Below the bar: the days' number,
 * then the quota's own reading on one line — the traffic eaten, a dot, the share
 * left (the subscription list's rows keep their own tighter trade). The eaten
 * traffic is hiddify's "used / total"; panels that keep the quota to themselves
 * (total=0, the common unlimited case) still get their used bytes counted out.
 * [textInset] keeps the numbers clear of whatever anchors at the foot (the card's
 * chevron) without pulling the bar short.
 */
@Composable
private fun SubscriptionInfo(
    subscription: SubscriptionEntity,
    week: List<DailyTraffic>,
    supportingColor: Color,
    textInset: Dp = 0.dp,
) {
    val colors = MaterialTheme.colorScheme
    val used = subscription.uploadBytes + subscription.downloadBytes
    val expire = subscription.expireEpochSeconds
    val secondsLeft = expire - System.currentTimeMillis() / 1000
    val days = daysTitle(expire)
    val consumed = consumedText(subscription)
    if (subscription.totalBytes <= 0 && days == null && consumed == null) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // One bar, whichever runs dry first. The quota's runway comes from the week's
        // pace; a paceless quota keeps its own drain in the slot (the one reading
        // always computable), and the days' stretch takes it when the panel set no
        // quota at all or the quota would outlive the calendar.
        val bytesLeft = (subscription.totalBytes - used).coerceAtLeast(0L)
        val avgDailyBytes = averageDailyBytes(week)
        val calendarFirst = expire > 0 &&
            (
                subscription.totalBytes <= 0 ||
                    (avgDailyBytes != null && daysLeft(secondsLeft) < bytesLeft / avgDailyBytes)
                )
        if (calendarFirst) {
            val dayFraction = (secondsLeft.toFloat() / (86_400f * 30f)).coerceIn(0f, 1f)
            UsageBar(
                progress = if (secondsLeft <= 0) 1f else dayFraction,
                color = if (secondsLeft <= 0) colors.error else usageColor(dayFraction, colors),
                trackColor = supportingColor,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (subscription.totalBytes > 0) {
            val ratio = (bytesLeft.toFloat() / subscription.totalBytes).coerceIn(0f, 1f)
            UsageBar(
                progress = ratio,
                color = usageColor(ratio, colors),
                trackColor = supportingColor,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(end = textInset),
        ) {
            // The days' number first, above the quota's own report — time is the axis
            // the card reads on first.
            days?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = supportingColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The quota's report on one line: the traffic eaten, a dot, the share left —
            // one reading, not a number here and its arithmetic under it. The
            // subscription list's rows keep their own tighter trade.
            val percent = if (subscription.totalBytes > 0) {
                val ratio = (((subscription.totalBytes - used).coerceAtLeast(0L)).toFloat() /
                    subscription.totalBytes).coerceIn(0f, 1f)
                stringResource(R.string.subscription_bw_percent, (ratio * 100).toInt())
            } else {
                null
            }
            val trafficLine = listOfNotNull(consumed, percent).joinToString(" · ")
            if (trafficLine.isNotEmpty()) {
                Text(
                    text = trafficLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = supportingColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The subscription rows' own report, compact: one bar up top — the same
 * whichever-runs-dry-first pick the card makes — with the traffic eaten and the days
 * on a single line under them: traffic to the left, days to the right.
 */
@Composable
private fun SubscriptionRowInfo(
    subscription: SubscriptionEntity,
    week: List<DailyTraffic>,
    supportingColor: Color,
) {
    val colors = MaterialTheme.colorScheme
    val used = subscription.uploadBytes + subscription.downloadBytes
    val expire = subscription.expireEpochSeconds
    val secondsLeft = expire - System.currentTimeMillis() / 1000
    val days = daysTitle(expire)
    val consumed = consumedText(subscription)
    if (days == null && consumed == null) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // One bar, whichever runs dry first — the card's own rule, seen from a row.
        val bytesLeft = (subscription.totalBytes - used).coerceAtLeast(0L)
        val avgDailyBytes = averageDailyBytes(week)
        val calendarFirst = expire > 0 &&
            (
                subscription.totalBytes <= 0 ||
                    (avgDailyBytes != null && daysLeft(secondsLeft) < bytesLeft / avgDailyBytes)
                )
        if (calendarFirst) {
            val dayFraction = (secondsLeft.toFloat() / (86_400f * 30f)).coerceIn(0f, 1f)
            UsageBar(
                progress = if (secondsLeft <= 0) 1f else dayFraction,
                color = if (secondsLeft <= 0) colors.error else usageColor(dayFraction, colors),
                trackColor = supportingColor,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (subscription.totalBytes > 0) {
            val ratio = (bytesLeft.toFloat() / subscription.totalBytes).coerceIn(0f, 1f)
            UsageBar(
                progress = ratio,
                color = usageColor(ratio, colors),
                trackColor = supportingColor,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            consumed?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = supportingColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            days?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    // An expired subscription's days read in red — the row's own quiet
                    // way of saying the calendar has run out, the card's badge echoed
                    // where the chip itself doesn't reach.
                    color = if (secondsLeft <= 0) colors.error else supportingColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One reported measure's drain. */
@Composable
private fun UsageBar(
    progress: Float,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    LinearProgressIndicator(
        progress = { progress },
        color = color,
        trackColor = trackColor.copy(alpha = trackColor.alpha * 0.30f),
        modifier = modifier.fillMaxWidth(),
    )
}

/** The days-remaining reading both the bars' numbers and the rows' line share. */
@Composable
private fun daysTitle(expireEpochSeconds: Long): String? {
    if (expireEpochSeconds <= 0) return null
    val secondsLeft = expireEpochSeconds - System.currentTimeMillis() / 1000
    val daysLeft = secondsLeft / 86_400
    return when {
        daysLeft > 365 -> stringResource(R.string.subscription_days_infinite)
        daysLeft >= 1 -> pluralStringResource(R.plurals.subscription_days_remaining, daysLeft.toInt(), daysLeft.toInt())
        secondsLeft > 0 -> stringResource(R.string.subscription_hours_left, secondsLeft / 3_600)
        else -> stringResource(R.string.subscription_expired)
    }
}

/** The traffic eaten over the limit: hiddify's "used / total" — and when the panel
 *  keeps the quota to itself (total=0, the common unlimited case) the limit still
 *  shows, spelled out. */
@Composable
private fun consumedText(subscription: SubscriptionEntity): String? {
    val used = subscription.uploadBytes + subscription.downloadBytes
    if (used <= 0 && subscription.totalBytes <= 0) return null
    return stringResource(
        R.string.subscription_consumed,
        formatBytes(used),
        if (subscription.totalBytes > 0) {
            formatBytes(subscription.totalBytes)
        } else {
            stringResource(R.string.subscription_unlimited)
        },
    )
}

/** The tunnel's average daily burn over the week on record — the days the counters
 *  actually moved, a still day says nothing about pace. Null until one such day
 *  stands in the store: the quota's runway can't be projected without it. */
private fun averageDailyBytes(week: List<DailyTraffic>): Float? {
    val days = week.map { it.rx + it.tx }.filter { it > 0 }
    if (days.isEmpty()) return null
    return days.average().toFloat()
}

/** The days standing between now and the panel's expiry — negative once it has passed. */
private fun daysLeft(secondsLeft: Long): Float = secondsLeft / 86_400f

/** The traffic-light tiers hiddify reads off a remaining ratio: red under a quarter,
 *  amber under half, the primary otherwise. */
private fun usageColor(ratio: Float, colors: ColorScheme): Color = when {
    ratio < 0.25f -> colors.error
    ratio < 0.45f -> colors.tertiary
    else -> colors.primary
}

/** Bytes in the panel's own units, down to whole kilobytes. */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return String.format(java.util.Locale.getDefault(), "%.1f %s", value, units[unit])
}

/**
 * The last update's stamp for the title line: the time of day if it was within the day,
 * the day count in hiddify's shorthand ("2d") once it's older.
 */
@Composable
private fun updatedText(subscription: SubscriptionEntity): String? {
    if (subscription.lastUpdateSuccess <= 0) return null
    val age = System.currentTimeMillis() - subscription.lastUpdateSuccess
    return if (age < 86_400_000L) {
        stringResource(
            R.string.subscription_updated_at,
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(subscription.lastUpdateSuccess)),
        )
    } else {
        stringResource(R.string.subscription_updated_ago, "${age / 86_400_000L}d")
    }
}

/**
 * All servers in one MD3 segmented list container: large outer corners, extra-small inner
 * corners and a 2dp gap between rows instead of dividers.
 */
@Composable
private fun ServerGroupRow(
    profile: ProfileEntity,
    index: Int,
    total: Int,
    selectedId: String?,
    flagStyle: FlagStyle,
    tunnelUp: Boolean,
    onSelect: (String) -> Unit,
    onDelete: (ProfileEntity) -> Unit,
    onTogglePinned: (String, Boolean) -> Unit,
    onToggleConcealed: (String, Boolean) -> Unit,
    menuHost: String?,
    onMenuHostChange: (String?) -> Unit,
    menuAskingDelete: Boolean,
    onMenuAskingChange: (Boolean) -> Unit,
    onCopyText: (String) -> Unit,
) {
    val outer = MaterialTheme.shapes.extraLarge
    val inner = MaterialTheme.shapes.extraSmall
    val shape = RoundedCornerShape(
        topStart = if (index == 0) outer.topStart else inner.topStart,
        topEnd = if (index == 0) outer.topEnd else inner.topEnd,
        bottomStart = if (index == total - 1) outer.bottomStart else inner.bottomStart,
        bottomEnd = if (index == total - 1) outer.bottomEnd else inner.bottomEnd,
    )
    ServerRow(
        profile = profile,
        selected = profile.id == selectedId,
        flagStyle = flagStyle,
        tunnelUp = tunnelUp,
        // A concealed row, called back by the chip, stands where it always
        // stood but stays out of play: the tap that would pick it does
        // nothing — the menu's own Показать is its way back.
        onSelect = if (profile.concealed) {
            {}
        } else {
            { onSelect(profile.id) }
        },
        // Every config row carries the menu, but not every one the same
        // menu: the loose configs own themselves — the pin, the hide and
        // the delete are theirs, the copy of the link they were born from
        // too — while a subscription's configs answer to the subscription,
        // and their menu is the pin and the hide alone.
        menu = RowMenu(
            open = menuHost == profile.id,
            onOpenChange = { open -> onMenuHostChange(profile.id.takeIf { open }) },
            askingDelete = menuAskingDelete,
            onAskingDeleteChange = onMenuAskingChange,
            deleteQuestion = stringResource(R.string.config_delete_title),
            canDelete = profile.subscriptionId == null,
            canCopy = profile.subscriptionId == null,
            copyEnabled = profile.sourceLink != null,
            onCopy = { profile.sourceLink?.let(onCopyText) },
            onDelete = { onDelete(profile) },
            pinned = profile.pinned,
            onTogglePinned = { onTogglePinned(profile.id, it) },
            // Every server row hides — the whole list is the user's own
            // shelf. The subscriptions' own rows (the dropdown) keep the
            // door shut: their menu never passes one.
            canHide = true,
            concealed = profile.concealed,
            onToggleConcealed = { onToggleConcealed(profile.id, it) },
        ),
        dimmed = menuHost != null && menuHost != profile.id,
        shape = shape,
    )
}

/** Nothing to pick yet: the quiet card that says what a pull or an update would do. */
@Composable
private fun ServersEmpty() {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainer.copy(alpha = LocalSurfaceOpacity.current),
        contentColor = colors.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.servers_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.servers_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * One server row: the remark as the headline, the endpoint and protocol in the supporting
 * line, the last real ping after the name — the accent from [HIGH_PING_MILLIS] on — and the
 * spinning cookie marking the pick. Selectable at any time, tunnel or not; a tap that lands
 * while the tunnel is up swaps the server in place. A long press opens the row's own menu —
 * the pin, the link, the delete — and a pinned row wears the pin itself, stuck through its
 * top-right corner at its own angle.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerRow(
    profile: ProfileEntity,
    selected: Boolean,
    flagStyle: FlagStyle,
    tunnelUp: Boolean,
    onSelect: () -> Unit,
    menu: RowMenu?,
    dimmed: Boolean,
    shape: Shape,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    // The pride easter egg: a name leading with the rainbow or the trans flag sets the
    // whole row drifting through the rainbow — the RAINBOW accent's slow cycle, on this
    // one row instead of the whole scheme. The flag itself rides the icon slot, like the
    // country flags do, and comes off the name.
    val (pride, prideStripped) = splitLeadingPrideFlag(
        profile.remarks.ifEmpty { if (LocalUiSettings.current.maskAddresses) "" else profile.server },
    )
    // The flag the panel put at the front of the name moves into the icon slot; the
    // headline shows the name without it.
    val (flag, splitTitle) = splitLeadingFlag(prideStripped)
    // The addresses' curtain: a nameless row would have shown the address itself, so
    // the headline stands empty instead — the address keeps out of sight entirely.
    val masked = LocalUiSettings.current.maskAddresses
    val title = splitTitle.ifEmpty { if (masked) "" else profile.server }
    val darkRow = colors.surface.luminance() < 0.5f
    val cyclingHue = if (pride != null) prideHueCycle() else null

    val containerState = animateColorAsState(
        targetValue = (if (selected) colors.primaryContainer else colors.surfaceContainer)
            .copy(alpha = LocalSurfaceOpacity.current),
        animationSpec = motion.defaultEffectsSpec(),
        label = "server container",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) colors.onPrimaryContainer else colors.onSurface,
        animationSpec = motion.defaultEffectsSpec(),
        label = "server content",
    )
    val extraColor by animateColorAsState(
        targetValue = if ((profile.testDelayMillis ?: 0L) >= HIGH_PING_MILLIS) {
            colors.primary
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
        }),
        animationSpec = motion.defaultEffectsSpec(),
        label = "server supporting",
    )
    val iconContainerState = animateColorAsState(
        targetValue = if (selected) colors.primary else colors.secondaryContainer,
        animationSpec = motion.defaultEffectsSpec(),
        label = "server icon container",
    )
    val iconColor by animateColorAsState(
        targetValue = if (selected) colors.onPrimary else colors.onSecondaryContainer,
        animationSpec = motion.defaultEffectsSpec(),
        label = "server icon",
    )
    // Another row's open menu lays its half-light over this one — the same 0.45 the
    // chooser dims the whole list with, worn row by row. A concealed row wears it
    // always, the reveal mode's own uniform: half-light and the slashed eye at the
    // end saying what the chip called back.
    val dimmedAlpha by animateFloatAsState(
        targetValue = if (dimmed || profile.concealed) 0.45f else 1f,
        animationSpec = motion.defaultEffectsSpec(),
        label = "server row dim",
    )

    // A pride row's container and tile are computed straight from the cycling hue — the
    // hue itself is the animation, so the state animators bow out for those two.
    val containerColor = if (cyclingHue != null) {
        prideColor(cyclingHue, selected, darkRow).copy(alpha = LocalSurfaceOpacity.current)
    } else {
        containerState.value
    }
    val iconContainerColor = if (cyclingHue != null) {
        prideTileColor(cyclingHue, selected, darkRow)
    } else {
        iconContainerState.value
    }
    val poke = rememberHapticPoke()
    // The sweep's answers land one by one, and each hand-over on the row's ping chip —
    // the old number out, the new one flying in — taps the soft poke: a sweep pokes a
    // dozen rows in a couple of seconds, and the full tap amplitude machine-guns on a
    // good motor. The first reading a row composes with is its own, not a change.
    val resultPoke = rememberHapticSoftPoke()
    var measured by remember(profile.id) { mutableStateOf(false) }
    LaunchedEffect(profile.id, profile.testDelayMillis, profile.lastTestAt) {
        if (measured) resultPoke()
        measured = true
    }

    // The row's own frame, clipping to nothing: every Surface clips to its shape,
    // and the pin rides the top-right corner half over the edge — so the pin stands
    // outside the card, pinned through it from the frame. The dim wears the frame,
    // so the pin follows the card wherever the list's light goes.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = dimmedAlpha },
    ) {
        Surface(
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .combinedClickable(
                        role = Role.Button,
                        onClick = {
                            if (!selected) {
                                poke()
                                onSelect()
                            }
                        },
                        // The long press opens the row's own menu — the pin, the
                        // link, the delete — for the loose configs, the rows that
                        // own themselves.
                        onLongClick = menu?.let { rowMenu ->
                            {
                                poke()
                                rowMenu.onOpenChange(true)
                            }
                        },
                    ),
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // The last real ping: a number, "can't ping", or nothing at all yet.
                    // A sweep's fresh answer takes the old one's seat with a hand-over:
                    // the old fades out, the new flies in from the right — the rows trade
                    // their numbers one by one as the sweep answers each of them. A
                    // balancer wears no number: its member of the moment is the live
                    // tunnel's own delay, which Home's chip reads.
                    if (profile.type != ProfileType.BALANCER) AnimatedContent(
                        targetState = profile.testDelayMillis to profile.lastTestAt,
                        transitionSpec = {
                            (fadeIn(motion.defaultEffectsSpec()) +
                                slideInHorizontally(motion.defaultSpatialSpec()) { it / 2 }) togetherWith
                                fadeOut(motion.defaultEffectsSpec())
                        },
                        label = "ping",
                    ) { (delay, _) ->
                        if (delay != null || profile.lastTestAt != null) {
                            val text = when {
                                delay == null || delay < 0 -> stringResource(R.string.servers_offline)
                                else -> stringResource(R.string.servers_ping_ms, delay.toInt())
                            }
                            Text(
                                text = "  ·  $text",
                                color = extraColor,
                                maxLines = 1,
                            )
                        }
                    }
                }
            },
            supportingContent = {
                Text(
                    text = if (profile.type == ProfileType.BALANCER) {
                        // The balancer's own line: how wide it picks, and how.
                        listOfNotNull(
                            pluralStringResource(
                                R.plurals.servers_balancer_members,
                                profile.balancerMemberKeys.size,
                                profile.balancerMemberKeys.size,
                            ),
                            balancerStrategyLabel(profile),
                        ).joinToString("  ·  ")
                    } else {
                        // The addresses' curtain: the protocol keeps its name, the
                        // endpoint keeps out of sight — no placeholder stands in,
                        // the line just ends where the address began.
                        listOfNotNull(
                            protocolLabel(profile),
                            if (masked) null else "${profile.server}:${profile.serverPort}",
                        ).joinToString("  ·  ")
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = {
                // MD3 list leading icon: 24dp icon on a 40dp tonal container. Selection
                // morphs the circle into the MD3 cookie — the pick's mark — and the
                // cookie turns only while the tunnel is up: the spin is the connection's
                // pulse, the shape alone carries the pick while it is down. A name's
                // leading flag takes the slot (Twemoji or system per the style setting);
                // names without one keep the globe.
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CookieBackdrop(
                        selected = selected,
                        color = iconContainerColor,
                        animated = tunnelUp,
                    )
                    when {
                        // The pride flag always rides the tile — the easter egg ignores
                        // the plain-globe flag style, only the country flags obey it.
                        pride != null -> PrideFlagIcon(
                            pride = pride,
                            twemoji = flagStyle == FlagStyle.TWEMOJI,
                        )

                        flag != null && flagStyle != FlagStyle.GLOBE -> EmojiFlag(
                            emoji = flag,
                            twemoji = flagStyle == FlagStyle.TWEMOJI,
                        )

                        profile.type == ProfileType.BALANCER -> Icon(
                            painter = painterResource(R.drawable.ic_explore),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = iconColor,
                        )

                        else -> Icon(
                            painter = painterResource(R.drawable.ic_public),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = iconColor,
                        )
                    }
                }
            },
            // The concealed row's own mark: the slashed eye at the end, the chip's
            // icon riding the row itself — the reveal mode's uniform. Otherwise no
            // trailing mark: the picked row already wears the accent and the cookie,
            // a checkmark on top of them would say the same thing a third time.
            trailingContent = if (profile.concealed) {
                {
                    Icon(
                        painter = painterResource(R.drawable.ic_visibility_off),
                        contentDescription = stringResource(R.string.card_menu_concealed),
                        tint = supportingColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                null
            },
            colors = ListItemDefaults.colors(
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
        )
        }
        // The row's own asks, standing over the row itself: the popup anchors to the
        // row's frame, so the menu drops out centered under the whole row.
        menu?.let { rowMenu ->
            CardActionsMenu(
                expanded = rowMenu.open,
                onClose = { rowMenu.onOpenChange(false) },
                askingDelete = rowMenu.askingDelete,
                onAskingDeleteChange = rowMenu.onAskingDeleteChange,
                deleteQuestion = rowMenu.deleteQuestion,
                canDelete = rowMenu.canDelete,
                canCopy = rowMenu.canCopy,
                copyEnabled = rowMenu.copyEnabled,
                onCopy = rowMenu.onCopy,
                onDelete = rowMenu.onDelete,
                pinned = rowMenu.pinned,
                onTogglePinned = rowMenu.onTogglePinned,
                canHide = rowMenu.canHide,
                concealed = rowMenu.concealed,
                onToggleConcealed = rowMenu.onToggleConcealed,
            )
        }
        // The pin's mark: a pinned row wears the pin itself, stuck through the card's
        // top-right corner — a little over the edge, at the row's own dice-rolled
        // angle, in the accent. The symbol's needle points straight down at rest, so
        // the tilt rolls clockwise, 15° to 45°: the needle walks into the card, the
        // head leans out of the corner. The dice roll once per row and stay rolled —
        // the same row shows the same pin every time it comes around.
        if (profile.pinned) {
            Icon(
                painter = painterResource(R.drawable.ic_keep),
                contentDescription = stringResource(R.string.card_menu_pinned),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 8.dp, y = (-6).dp)
                    .size(28.dp)
                    .graphicsLayer {
                        rotationZ = (profile.id.hashCode().mod(31)) + 15f
                    },
            )
        }
    }
}

/** The protocol's own name, as links and panels call it. */
private fun protocolLabel(profile: ProfileEntity): String? = when (profile.type) {
    slop.miho.chumpy.db.ProfileType.VMESS -> "vmess"
    slop.miho.chumpy.db.ProfileType.VLESS -> "vless"
    slop.miho.chumpy.db.ProfileType.TROJAN -> "trojan"
    slop.miho.chumpy.db.ProfileType.SHADOWSOCKS -> "ss"
    slop.miho.chumpy.db.ProfileType.SOCKS -> "socks"
    slop.miho.chumpy.db.ProfileType.WIREGUARD -> "wireguard"
    slop.miho.chumpy.db.ProfileType.HYSTERIA2 -> "hysteria2"
    // The supporting line of a balancer row is its own — see balancerStrategyLabel.
    slop.miho.chumpy.db.ProfileType.BALANCER -> null
}

/**
 * The balancer row's second half: how the core picks the member of the moment. The
 * strategy column already carries one of the four names the parser normalized to;
 * anything odd fell back to random there, so the label is exhaustive in practice.
 */
@Composable
private fun balancerStrategyLabel(profile: ProfileEntity): String = when (profile.balancerStrategy) {
    "roundrobin" -> stringResource(R.string.servers_balancer_round_robin)
    "leastping" -> stringResource(R.string.servers_balancer_least_ping)
    "leastload" -> stringResource(R.string.servers_balancer_least_load)
    else -> stringResource(R.string.servers_balancer_random)
}

/**
 * One row's asks, collected for its menu: the open flag and the delete ask live with
 * the screen (one menu on the whole screen at a time, one face shown at a time), the
 * doors themselves stay with the row.
 */
private class RowMenu(
    val open: Boolean,
    val onOpenChange: (Boolean) -> Unit,
    val askingDelete: Boolean,
    val onAskingDeleteChange: (Boolean) -> Unit,
    val deleteQuestion: String,
    val canDelete: Boolean,
    val canCopy: Boolean,
    val copyEnabled: Boolean,
    val onCopy: () -> Unit,
    val onDelete: () -> Unit,
    val pinned: Boolean,
    val onTogglePinned: (Boolean) -> Unit,
    val canHide: Boolean = false,
    val concealed: Boolean = false,
    val onToggleConcealed: (Boolean) -> Unit = {},
)

/**
 * The row menu itself: the stock dropdown in its stock clothes. Its first face is the
 * asks — the pin (a live one: pinning a row carries it to the head of its list, and
 * the door says so), the copy of whatever the row was born from (dim when there is
 * nothing behind it to copy), and the delete, the one door that wears red. The delete
 * does not delete: it trades the menu's face for the ask — Да in red over Нет, the way
 * back — while the question itself rides above the row, in the callout. Rows that
 * cannot delete (a subscription's configs answer to the subscription) keep the first
 * face only.
 */
@Composable
private fun CardActionsMenu(
    expanded: Boolean,
    onClose: () -> Unit,
    askingDelete: Boolean,
    onAskingDeleteChange: (Boolean) -> Unit,
    deleteQuestion: String,
    canDelete: Boolean,
    canCopy: Boolean,
    copyEnabled: Boolean,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    pinned: Boolean,
    onTogglePinned: (Boolean) -> Unit,
    canHide: Boolean = false,
    concealed: Boolean = false,
    onToggleConcealed: (Boolean) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val poke = rememberHapticPoke()
    val density = LocalDensity.current
    // The menu holds the widest face it has ever worn: the ask's shorter pair would
    // leave the popup snapping narrower the moment the old face finished walking
    // out. One width for as long as the menu stands, whatever face it wears.
    var heldWidth by remember { mutableStateOf(Dp.Unspecified) }
    CardMenu(expanded = expanded, onDismissRequest = onClose) {
        // The two faces trade places the way the add row's own face swap does — one
        // rides out while the other steps in, the menu standing where it is. The
        // size does not ride along: the popup window takes its next stance at once
        // instead of chasing the content's size frame by frame, and the faces do
        // all the walking themselves.
        AnimatedContent(
            targetState = askingDelete,
            modifier = Modifier
                .widthIn(min = heldWidth)
                .onSizeChanged { size ->
                    val width = with(density) { size.width.toDp() }
                    if (heldWidth == Dp.Unspecified || width > heldWidth) heldWidth = width
                },
            transitionSpec = {
                val direction = if (targetState) 1 else -1
                ContentTransform(
                    targetContentEnter = fadeIn(motion.fastEffectsSpec()) +
                        slideInVertically(motion.fastSpatialSpec()) {
                            direction * it / 4
                        },
                    initialContentExit = fadeOut(motion.fastEffectsSpec()) +
                        slideOutVertically(motion.fastSpatialSpec()) {
                            -direction * it / 4
                        },
                    sizeTransform = SizeTransform(clip = false) { _, _ -> snap() },
                )
            },
            label = "row menu face",
        ) { asking ->
            if (asking) {
                Column {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.card_menu_yes)) },
                        onClick = {
                            onClose()
                            poke()
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(
                                painterResource(R.drawable.ic_delete),
                                contentDescription = null,
                                tint = colors.error,
                            )
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = colors.error,
                            leadingIconColor = colors.error,
                        ),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.card_menu_no)) },
                        onClick = {
                            poke()
                            onAskingDeleteChange(false)
                        },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = null)
                        },
                    )
                }
            } else {
                Column {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (pinned) R.string.card_menu_unpin else R.string.card_menu_pin,
                                ),
                            )
                        },
                        onClick = {
                            onClose()
                            poke()
                            onTogglePinned(!pinned)
                        },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_keep), contentDescription = null)
                        },
                    )
                    if (canCopy) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.card_menu_copy_url)) },
                            enabled = copyEnabled,
                            onClick = {
                                onClose()
                                poke()
                                onCopy()
                            },
                            leadingIcon = {
                                Icon(
                                    painterResource(R.drawable.ic_content_copy),
                                    contentDescription = null,
                                )
                            },
                        )
                    }
                    // The hide's own door, live like the pin's: hiding carries the
                    // row out of the list and into the header's chip count; a revealed
                    // row's door reads the other way, putting it back. Subscription
                    // rows keep the door shut — their servers answer to the panel's
                    // list, not the user's own shelf.
                    if (canHide) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (concealed) R.string.card_menu_unhide else R.string.card_menu_hide,
                                    ),
                                )
                            },
                            onClick = {
                                onClose()
                                poke()
                                onToggleConcealed(!concealed)
                            },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.ic_visibility_off), contentDescription = null)
                            },
                        )
                    }
                    if (canDelete) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.card_menu_delete)) },
                            onClick = {
                                poke()
                                onAskingDeleteChange(true)
                            },
                            leadingIcon = {
                                Icon(
                                    painterResource(R.drawable.ic_delete),
                                    contentDescription = null,
                                    tint = colors.error,
                                )
                            },
                            colors = MenuDefaults.itemColors(
                                textColor = colors.error,
                                leadingIconColor = colors.error,
                            ),
                        )
                    }
                }
            }
        }
    }
    // The question over the row, standing exactly as long as the ask does.
    CardCallout(expanded = expanded && askingDelete) {
        Text(
            text = deleteQuestion,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** From this ping on (ms) a server counts as slow, and its ping is painted in the accent. */
private const val HIGH_PING_MILLIS = 150

// ---------------------------------------------------------------- pride easter egg

/**
 * The pride row's hue: one slow lap of the rainbow, the same 12 seconds the RAINBOW
 * accent takes. Read at every frame, so the row's colors follow the hue around.
 */
@Composable
private fun prideHueCycle(): Float {
    val hue by rememberInfiniteTransition(label = "pride hue").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(PRIDE_CYCLE_MS, easing = LinearEasing)),
        label = "pride hue value",
    )
    return hue
}

/** The pride row's container at a point of the cycle: a soft tint of the hue — deeper
 *  when picked, on the dark side when the app is. */
private fun prideColor(hue: Float, selected: Boolean, dark: Boolean): Color = hslColor(
    hue = hue,
    saturation = if (selected) 0.55f else 0.38f,
    lightness = when {
        dark && selected -> 0.30f
        dark -> 0.22f
        selected -> 0.86f
        else -> 0.93f
    },
)

/** The icon tile's take on the hue: stronger, and the cookie's backing when picked. */
private fun prideTileColor(hue: Float, selected: Boolean, dark: Boolean): Color = hslColor(
    hue = hue,
    saturation = if (selected) 0.70f else 0.50f,
    lightness = when {
        dark && selected -> 0.45f
        dark -> 0.30f
        selected -> 0.58f
        else -> 0.78f
    },
)

private fun hslColor(hue: Float, saturation: Float, lightness: Float): Color =
    Color(ColorUtils.HSLToColor(floatArrayOf(hue, saturation, lightness)))

/** The pride lap: 12 s, the RAINBOW accent's own cycle time. */
private const val PRIDE_CYCLE_MS = 12_000
