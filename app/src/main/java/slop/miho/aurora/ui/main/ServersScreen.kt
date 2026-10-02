package slop.miho.aurora.ui.main

import androidx.core.graphics.ColorUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import slop.miho.aurora.R
import slop.miho.aurora.data.FlagStyle
import slop.miho.aurora.db.ProfileEntity
import slop.miho.aurora.db.SubscriptionEntity
import slop.miho.aurora.ui.ServersUiState
import slop.miho.aurora.ui.components.CookieBackdrop
import slop.miho.aurora.ui.components.EmojiFlag
import slop.miho.aurora.ui.components.ErrorCard
import slop.miho.aurora.ui.components.InfoChip
import slop.miho.aurora.ui.components.PrideFlagIcon
import slop.miho.aurora.ui.components.splitLeadingFlag
import slop.miho.aurora.ui.components.splitLeadingPrideFlag
import slop.miho.aurora.ui.components.rememberHapticDeniedKicks
import slop.miho.aurora.ui.components.rememberHapticPoke
import slop.miho.aurora.ui.components.WavyPullToRefreshBox
import slop.miho.aurora.ui.components.pullShiftedPadding
import slop.miho.aurora.ui.wallpapers.LocalSurfaceOpacity
import slop.miho.aurora.ui.wallpapers.ReportBarCoverage

/**
 * The servers screen: one subscription card up top (the database tile with the reload
 * button under it; the card's tap unfolds the subscription list below while the servers
 * fade away, and the card itself turns into the chooser's call to action), then that
 * subscription's servers as one segmented list. Pull-to-refresh measures every server
 * through the core — the list is always reachable, tunnel or not, since picking a server
 * never needs one. The rows draw the flag a name begins with (per [flagStyle]); the name
 * itself is shown without it.
 */
@Composable
fun ServersScreen(
    subscriptions: List<SubscriptionEntity>,
    selectedSubscription: SubscriptionEntity?,
    state: ServersUiState,
    flagStyle: FlagStyle = FlagStyle.TWEMOJI,
    onUpdate: () -> Unit,
    onPing: () -> Unit,
    onSelectSubscription: (String) -> Unit,
    onAddSubscription: (url: String, name: String?, onDone: (String?) -> Unit) -> Unit,
    onDeleteSubscription: (String) -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The subscription dropdown's open state; survives page switches so a dialog opened
    // from an unfolded card still has its list waiting behind it.
    var dropdownOpen by rememberSaveable { mutableStateOf(false) }

    // One dialog at a time.
    var addOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SubscriptionEntity?>(null) }

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
            // An update failure sits above everything, shaped like a server row itself.
            state.error?.let { error ->
                item { ErrorCard(message = stringResource(R.string.subscription_update_failed, error)) }
            }

            item {
                SubscriptionCard(
                    subscription = selectedSubscription,
                    isUpdating = state.isUpdating,
                    expanded = dropdownOpen,
                    onToggle = { dropdownOpen = !dropdownOpen },
                    onUpdate = onUpdate,
                )
            }

            // The chooser unfolds: the subscription list rides out from under the card
            // while the servers step aside with a fade — one ask at a time.
            item {
                SubscriptionAppear(visible = dropdownOpen) {
                    SubscriptionDropdown(
                        subscriptions = subscriptions,
                        selectedId = selectedSubscription?.id,
                        onSelect = { id ->
                            dropdownOpen = false
                            if (id != selectedSubscription?.id) onSelectSubscription(id)
                        },
                        onAdd = { addOpen = true },
                        onDeleteRequest = { deleteTarget = it },
                    )
                }
            }

            item {
                // The servers fade back in once the chooser folds away.
                val motion = MaterialTheme.motionScheme
                AnimatedVisibility(
                    visible = !dropdownOpen,
                    enter = fadeIn(motion.defaultEffectsSpec()),
                    exit = fadeOut(motion.defaultEffectsSpec()),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ServersHeader(state = state)
                        ServerGroup(
                            profiles = state.profiles,
                            selectedId = state.selectedProfileId,
                            flagStyle = flagStyle,
                            onSelect = onSelect,
                        )
                    }
                }
            }
        }
    }

    if (addOpen) {
        AddSubscriptionDialog(
            onConfirm = { url, name ->
                addOpen = false
                onAddSubscription(url, name) { }
            },
            onDismiss = { addOpen = false },
        )
    }

    deleteTarget?.let { subscription ->
        DeleteSubscriptionDialog(
            subscription = subscription,
            onConfirm = {
                onDeleteSubscription(subscription.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

/**
 * The chip row above the server list: how many servers the subscription carries and how
 * many answered the last sweep — a real-ping answer, not a cache.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServersHeader(state: ServersUiState) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state.profiles.isNotEmpty()) {
            InfoChip(
                value = stringResource(R.string.servers_total, state.profiles.size),
                icon = R.drawable.ic_servers,
                animateValue = true,
            )
            val reachable = state.profiles.count { (it.testDelayMillis ?: -1L) > 0L }
            if (reachable > 0) {
                InfoChip(
                    value = stringResource(R.string.servers_online, reachable),
                    icon = R.drawable.ic_plug,
                    animateValue = true,
                )
            }
        }
    }
}

/**
 * The subscription card, the devices screen's row language grown up: the database tile
 * on its square backing — the list's rows wear the round one; the card keeps the square,
 * like it always did — with the reload button tucked under it, the subscription's name
 * over what the panel reports. The card's own tap unfolds the subscription list — the
 * info flies out from under the name as it does, and the title flips to the chooser's
 * call to action — while the button's tap is a different ask: pulling new servers in.
 */
@Composable
private fun SubscriptionCard(
    subscription: SubscriptionEntity?,
    isUpdating: Boolean,
    expanded: Boolean,
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

    Surface(
        onClick = {
            poke()
            onToggle()
        },
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The leading column: the database tile on its square backing — the list's
            // rows wear the round one, the card keeps the square — the reload button
            // under it: the subscription's face, and its refresh, stacked.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = colors.secondaryContainer,
                    contentColor = colors.onSecondaryContainer,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_database),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                UpdateButton(isUpdating = isUpdating, onClick = onUpdate)
            }
            Column(Modifier.weight(1f)) {
                // The title flips between the subscription's name — the last update's
                // stamp riding at its level — and the chooser's call to action as the
                // list comes and goes.
                AnimatedContent(
                    targetState = expanded,
                    transitionSpec = {
                        (fadeIn(motion.defaultEffectsSpec()) +
                            slideInVertically(motion.defaultSpatialSpec()) { it / 2 }) togetherWith
                            fadeOut(motion.defaultEffectsSpec())
                    },
                    label = "subscription title",
                ) { chooser ->
                    if (chooser) {
                        Text(
                            text = stringResource(R.string.subscription_choose),
                            style = MaterialTheme.typography.bodyLarge,
                            color = contentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = subscription?.name?.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.subscription_none),
                                style = MaterialTheme.typography.bodyLarge,
                                color = contentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            subscription?.let { sub ->
                                updatedText(sub)?.let { stamp ->
                                    Text(
                                        text = stamp,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = supportingColor,
                                        maxLines = 1,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                subscription?.let { sub ->
                    // What the panel reports flies out from under the name as the chooser
                    // opens, and settles back under it as the chooser folds.
                    AnimatedVisibility(
                        visible = !expanded,
                        enter = fadeIn(motion.defaultEffectsSpec()) +
                            expandVertically(motion.defaultSpatialSpec()) +
                            slideInVertically(motion.defaultSpatialSpec()) { it / 2 },
                        exit = fadeOut(motion.defaultEffectsSpec()) +
                            shrinkVertically(motion.defaultSpatialSpec()) +
                            slideOutVertically(motion.defaultSpatialSpec()) { it },
                    ) {
                        SubscriptionInfo(
                            subscription = sub,
                            supportingColor = supportingColor,
                        )
                    }
                }
            }
            // The chevron points the card's own tap at the list it unfolds, flipping as it does.
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = stringResource(R.string.subscription_expand),
                tint = supportingColor,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer { rotationZ = if (expanded) 270f else 90f },
            )
        }
    }
}

/** The update button: the round-trip arrows, spinning slowly while the fetch runs. */
@Composable
private fun UpdateButton(isUpdating: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val poke = rememberHapticPoke()
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
            onClick()
        },
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceContainerHigh.copy(alpha = LocalSurfaceOpacity.current),
        contentColor = colors.onSurfaceVariant,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_renew),
                contentDescription = stringResource(R.string.subscription_update),
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer { rotationZ = spin?.value ?: 0f },
            )
        }
    }
}

/**
 * The unfolded subscription list: one row per subscription — the servers' own segmented
 * container, Surface подложки, cookie-marked pick and all — with the "add subscription"
 * row closing the list off, one of the rows itself, its icon on the square backing the
 * card's tile wears. A long press on a row asks the heavier question: deleting that
 * subscription and everything it brought in.
 */
@Composable
private fun SubscriptionDropdown(
    subscriptions: List<SubscriptionEntity>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onDeleteRequest: (SubscriptionEntity) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val outer = MaterialTheme.shapes.extraLarge
    val inner = MaterialTheme.shapes.extraSmall
    // The add row closes the list off, so it counts when the corner shapes are handed out.
    val rowCount = subscriptions.size + 1
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(outer),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        subscriptions.forEachIndexed { index, subscription ->
            val first = index == 0
            val last = index == rowCount - 1
            SubscriptionRow(
                subscription = subscription,
                selected = subscription.id == selectedId,
                onClick = { onSelect(subscription.id) },
                onLongClick = { onDeleteRequest(subscription) },
                shape = RoundedCornerShape(
                    topStart = if (first) outer.topStart else inner.topStart,
                    topEnd = if (first) outer.topEnd else inner.topEnd,
                    bottomStart = if (last) outer.bottomStart else inner.bottomStart,
                    bottomEnd = if (last) outer.bottomEnd else inner.bottomEnd,
                ),
            )
        }
        AddSubscriptionRow(
            onClick = onAdd,
            shape = RoundedCornerShape(
                topStart = if (subscriptions.isEmpty()) outer.topStart else inner.topStart,
                topEnd = if (subscriptions.isEmpty()) outer.topEnd else inner.topEnd,
                bottomStart = outer.bottomStart,
                bottomEnd = outer.bottomEnd,
            ),
            color = colors.surfaceContainer.copy(alpha = LocalSurfaceOpacity.current),
        )
    }
}

/**
 * The add row, the list's standing invitation: a row like the rest of them — same
 * Surface подложка, same segmented shape, same 40dp tile — except its glyph sits on the
 * square backing the card's tile wears, the round one's straight-laced cousin.
 */
@Composable
private fun AddSubscriptionRow(
    onClick: () -> Unit,
    shape: Shape,
    color: Color,
) {
    val colors = MaterialTheme.colorScheme
    val poke = rememberHapticPoke()
    Surface(
        onClick = {
            poke()
            onClick()
        },
        shape = shape,
        color = color,
        contentColor = colors.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ListItem(
            modifier = Modifier.fillMaxWidth(),
            headlineContent = {
                Text(text = stringResource(R.string.subscription_add), maxLines = 1)
            },
            leadingContent = {
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
            },
            colors = ListItemDefaults.colors(
                // The row's Surface paints the container; doing it here too would stack
                // the translucent color twice.
                containerColor = Color.Transparent,
                headlineColor = colors.onSurface,
            ),
            tonalElevation = 0.dp,
        )
    }
}

/**
 * One subscription in the unfolded list: the server row's own anatomy, copied — the
 * [Surface] подложка with the segmented shape, the [ListItem] inside it, the 40dp
 * leading tile whose round backdrop morphs into the pick's cookie, the name and the
 * update stamp in the headline, what the panel reports in the supporting slot. A long
 * press is the delete question.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubscriptionRow(
    subscription: SubscriptionEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    shape: Shape,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val poke = rememberHapticPoke()
    val kicks = rememberHapticDeniedKicks()
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
                        poke()
                        onClick()
                    },
                    onLongClick = {
                        kicks()
                        onLongClick()
                    },
                ),
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = subscription.name.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.subscription_none),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    updatedText(subscription)?.let { stamp ->
                        Text(
                            text = stamp,
                            style = MaterialTheme.typography.bodySmall,
                            color = supportingColor,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            },
            supportingContent = {
                SubscriptionInfo(
                    subscription = subscription,
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
                        painter = painterResource(R.drawable.ic_database),
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
    }
}

/**
 * What the panel reports about a subscription, drawn the way hiddify draws it: the
 * bandwidth's remaining share — the bar holds what's left, draining as traffic is spent —
 * and the days remaining beside it, over a 30-day stretch. Both take the traffic-light
 * tiers: red under a quarter, amber under half, the theme's primary while it's healthy.
 */
@Composable
private fun SubscriptionInfo(
    subscription: SubscriptionEntity,
    supportingColor: Color,
) {
    val colors = MaterialTheme.colorScheme
    val used = subscription.uploadBytes + subscription.downloadBytes
    val expire = subscription.expireEpochSeconds
    if (subscription.totalBytes <= 0 && expire <= 0) return
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (subscription.totalBytes > 0) {
            val ratio = (((subscription.totalBytes - used).coerceAtLeast(0L)).toFloat() /
                subscription.totalBytes).coerceIn(0f, 1f)
            UsageItem(
                title = stringResource(R.string.subscription_bw_remaining, (ratio * 100).toInt()),
                progress = ratio,
                color = usageColor(ratio, colors),
                trackColor = supportingColor,
                modifier = Modifier.weight(1f),
            )
        }
        if (expire > 0) {
            val secondsLeft = expire - System.currentTimeMillis() / 1000
            val daysLeft = secondsLeft / 86_400
            val title = when {
                daysLeft > 365 -> stringResource(R.string.subscription_days_infinite)
                daysLeft >= 1 -> stringResource(R.string.subscription_days_remaining, daysLeft)
                secondsLeft > 0 -> stringResource(R.string.subscription_hours_left, secondsLeft / 3_600)
                else -> stringResource(R.string.subscription_expired)
            }
            val dayFraction = (secondsLeft.toFloat() / (86_400f * 30f)).coerceIn(0f, 1f)
            val color = if (secondsLeft <= 0) colors.error else usageColor(dayFraction, colors)
            UsageItem(
                title = title,
                progress = if (secondsLeft <= 0) 1f else dayFraction,
                color = color,
                trackColor = supportingColor,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** One reported measure: its number above, its drain below. */
@Composable
private fun UsageItem(
    title: String,
    progress: Float,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = trackColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LinearProgressIndicator(
            progress = { progress },
            color = color,
            trackColor = trackColor.copy(alpha = trackColor.alpha * 0.30f),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The traffic-light tiers hiddify reads off a remaining ratio: red under a quarter,
 *  amber under half, the primary otherwise. */
private fun usageColor(ratio: Float, colors: ColorScheme): Color = when {
    ratio < 0.25f -> colors.error
    ratio < 0.45f -> colors.tertiary
    else -> colors.primary
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
private fun ServerGroup(
    profiles: List<ProfileEntity>,
    selectedId: String?,
    flagStyle: FlagStyle,
    onSelect: (String) -> Unit,
) {
    if (profiles.isEmpty()) {
        ServersEmpty()
        return
    }
    val rows = buildList<@Composable (Shape) -> Unit> {
        profiles.forEach { profile ->
            add { shape ->
                ServerRow(
                    profile = profile,
                    selected = profile.id == selectedId,
                    flagStyle = flagStyle,
                    onSelect = { onSelect(profile.id) },
                    shape = shape,
                )
            }
        }
    }
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
 * while the tunnel is up swaps the server in place.
 */
@Composable
private fun ServerRow(
    profile: ProfileEntity,
    selected: Boolean,
    flagStyle: FlagStyle,
    onSelect: () -> Unit,
    shape: Shape,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    // The pride easter egg: a name leading with the rainbow or the trans flag sets the
    // whole row drifting through the rainbow — the RAINBOW accent's slow cycle, on this
    // one row instead of the whole scheme. The flag itself rides the icon slot, like the
    // country flags do, and comes off the name.
    val (pride, prideStripped) = splitLeadingPrideFlag(profile.remarks.ifEmpty { profile.server })
    // The flag the panel put at the front of the name moves into the icon slot; the
    // headline shows the name without it.
    val (flag, splitTitle) = splitLeadingFlag(prideStripped)
    val title = splitTitle.ifEmpty { profile.server }
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

    Surface(
        onClick = {
            if (!selected) {
                poke()
                onSelect()
            }
        },
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier.fillMaxWidth(),
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
                    // The last real ping: a number, "unreachable", or nothing at all yet.
                    // It appears and goes with a fade, never a jump.
                    AnimatedVisibility(
                        visible = profile.testDelayMillis != null,
                        enter = fadeIn(motion.defaultEffectsSpec()),
                        exit = fadeOut(motion.defaultEffectsSpec()),
                    ) {
                        val ping = profile.testDelayMillis
                        val text = when {
                            ping == null || ping < 0 -> stringResource(R.string.servers_offline)
                            else -> stringResource(R.string.servers_ping_ms, ping.toInt())
                        }
                        Text(
                            text = "  ·  $text",
                            color = extraColor,
                            maxLines = 1,
                        )
                    }
                }
            },
            supportingContent = {
                Text(
                    text = listOfNotNull(
                        protocolLabel(profile),
                        "${profile.server}:${profile.serverPort}",
                    ).joinToString("  ·  "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = {
                // MD3 list leading icon: 24dp icon on a 40dp tonal container. Selection
                // morphs the circle into the slowly spinning MD3 cookie — the pick's mark.
                // A name's leading flag takes the slot (Twemoji or system per the style
                // setting); names without one keep the globe.
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CookieBackdrop(
                        selected = selected,
                        color = iconContainerColor,
                        animated = true,
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

                        else -> Icon(
                            painter = painterResource(R.drawable.ic_public),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = iconColor,
                        )
                    }
                }
            },
            trailingContent = if (selected) {
                {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = stringResource(R.string.servers_selected),
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
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The protocol's own name, as links and panels call it. */
private fun protocolLabel(profile: ProfileEntity): String? = when (profile.type) {
    slop.miho.aurora.db.ProfileType.VMESS -> "vmess"
    slop.miho.aurora.db.ProfileType.VLESS -> "vless"
    slop.miho.aurora.db.ProfileType.TROJAN -> "trojan"
    slop.miho.aurora.db.ProfileType.SHADOWSOCKS -> "ss"
    slop.miho.aurora.db.ProfileType.SOCKS -> "socks"
    slop.miho.aurora.db.ProfileType.WIREGUARD -> "wireguard"
    slop.miho.aurora.db.ProfileType.HYSTERIA2 -> "hysteria2"
}

/**
 * The creation dialog: an MD3 AlertDialog with the URL and an optional name. "Add" answers
 * only once the URL field holds something.
 */
@Composable
private fun AddSubscriptionDialog(
    onConfirm: (url: String, name: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) {
        // A subscription URL already on the clipboard is usually the one being added.
        clipboard.getText()?.text?.trim()?.takeIf { it.startsWith("http") }?.let { url = it }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subscription_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.subscription_url_field)) },
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.subscription_name_field)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim(), name.trim().takeIf { it.isNotEmpty() }) },
                enabled = url.isNotBlank(),
            ) {
                Text(stringResource(R.string.subscription_add_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.subscription_cancel))
            }
        },
    )
}

/**
 * The delete confirmation — the question a subscription row's long press asks before doing
 * the one thing there is no coming back from. The confirm button carries the error color,
 * MD3's way of marking a destructive action.
 */
@Composable
private fun DeleteSubscriptionDialog(
    subscription: SubscriptionEntity,
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
        title = { Text(stringResource(R.string.subscription_delete_title)) },
        text = {
            Text(
                stringResource(
                    R.string.subscription_delete_text,
                    subscription.name.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.subscription_none),
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
            ) {
                Text(stringResource(R.string.subscription_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.subscription_cancel))
            }
        },
    )
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

/** The subscription list rides out from under the card and folds back under it again. */
@Composable
private fun SubscriptionAppear(visible: Boolean, content: @Composable () -> Unit) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.defaultEffectsSpec()) +
            expandVertically(motion.defaultSpatialSpec()) +
            slideInVertically(motion.defaultSpatialSpec()) { it / 4 },
        exit = fadeOut(motion.defaultEffectsSpec()) +
            shrinkVertically(motion.defaultSpatialSpec()) +
            slideOutVertically(motion.defaultSpatialSpec()) { it / 4 },
    ) {
        content()
    }
}
