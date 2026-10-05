package slop.miho.aurora.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import slop.miho.aurora.AuroraApp
import slop.miho.aurora.Graph
import slop.miho.aurora.R
import slop.miho.aurora.core.CoreConfigFile
import slop.miho.aurora.core.TunnelClient
import slop.miho.aurora.core.TunnelState
import slop.miho.aurora.core.TunnelStatus
import slop.miho.aurora.data.ConnectionSettings
import slop.miho.aurora.data.ConnectionStore
import slop.miho.aurora.data.DailyTraffic
import slop.miho.aurora.data.DailyTrafficStore
import slop.miho.aurora.ui.components.splitLeadingFlag
import slop.miho.aurora.data.SettingsStore
import slop.miho.aurora.data.UiSettings
import slop.miho.aurora.db.ProfileEntity
import slop.miho.aurora.db.ProfileType
import slop.miho.aurora.db.STANDALONE_SUBSCRIPTION_ID
import slop.miho.aurora.db.SubscriptionEntity
import slop.miho.aurora.db.balancerMemberKeys
import slop.miho.aurora.sub.SubscriptionRepository
import slop.miho.aurora.xray.RoutingMode
import slop.miho.aurora.xray.XrayConfigBuilder

/** Home screen extras: everything besides the tunnel status itself. */
data class HomeUiState(
    /** The active subscription's name — the server card's first header line. */
    val subscriptionName: String? = null,
    /** The selected profile's name, its leading flag lifted out into [profileFlag]. */
    val profileLabel: String? = null,
    /** The flag emoji the selected profile's name begins with, if it does. */
    val profileFlag: String? = null,
    /** Round-trip through the running tunnel, refreshed every 15 s while connected. */
    val pingMillis: Long? = null,
    /** Wall-clock millis of the last transition to UP; null while down. */
    val uptimeStartMillis: Long? = null,
    /** The recent failed probes, but only once they cross the warning threshold — null
     *  means the tunnel is healthy (or nothing has been measured yet), and the chip stays
     *  hidden. */
    val recentLossPercent: Float? = null,
)

/** The servers screen's list: the selected subscription's profiles, plus the transient bits. */
data class ServersUiState(
    val profiles: List<ProfileEntity> = emptyList(),
    val selectedProfileId: String? = null,
    /** Whether a real-ping sweep over the list is running (the pull-to-refresh action). */
    val isPinging: Boolean = false,
    /** Whether the subscription is being fetched right now (the update button). */
    val isUpdating: Boolean = false,
    val error: String? = null,
)

/** One point of the traffic graph: a 15-second bucket's average rates, with the core's
 *  counters as of its end. The averages keep the points rare while the sampling underneath
 *  stays once a second — the last bucket keeps updating live until it settles. */
data class TrafficSample(
    val atMillis: Long,
    val rxTotal: Long,
    val txTotal: Long,
    val downBps: Long,
    val upBps: Long,
)

/** One 15-second window of connectivity probes: how many were sent, how many never completed. */
data class LossBucket(val atMillis: Long, val sent: Int, val lost: Int)

/** The stats screen's live history, kept in memory only: the core's outbound counters are
 *  read once a second — every read answers the bytes since the previous one, the core
 *  resets its counters on each read — and folded into 15-second buckets, the probes run
 *  every fifteen and land in the same buckets, and both trim to the graph window.
 *  Disconnecting freezes the history in place instead of clearing — the graphs stay on
 *  screen, and [frozenAtMillis] records when the data stopped moving. */
data class StatsUiState(
    val samples: List<TrafficSample> = emptyList(),
    val buckets: List<LossBucket> = emptyList(),
    /** The session's accumulated transfer, folded here from the per-query deltas (the
     *  core resets its counters on every read) — the frozen connection's total. */
    val rxTotal: Long = 0,
    val txTotal: Long = 0,
    /** When the graphs froze — the moment the tunnel went down; null while connected. */
    val frozenAtMillis: Long? = null,
    /** How long the frozen connection lasted; null until a connection ends. */
    val connectedMillis: Long? = null,
    /** The recent failed probes, but only once they cross the warning threshold — the red
     *  chip's trigger on both this screen and home. Null means no warning. */
    val recentLossPercent: Float? = null,
    /** The week's daily totals for the seven-bar chart, oldest first, persisted between runs. */
    val week: List<DailyTraffic> = emptyList(),
)

/**
 * The one view model behind the main screen: subscriptions and their profiles (Room,
 * observed live), the selected server and routing mode, the tunnel connection itself, and
 * the measurement loops (real pings, traffic counters, loss probes) that feed Home and
 * Stats.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<Application>()
    private val tunnelClient = AuroraApp.tunnelClient
    private val repository: SubscriptionRepository = Graph.subscriptions

    /** UI preferences; every change is persisted immediately. */
    val settings: StateFlow<UiSettings> = SettingsStore.settings(app)

    fun updateSettings(transform: (UiSettings) -> UiSettings) {
        SettingsStore.update(app, transform)
    }

    /**
     * One-shot: the first config just landed, and the tunnel's falls speak through the
     * alert — on 13+ that needs the notifications permission, asked right there, once,
     * and never again after a decline.
     */
    private val _notificationsAsk = MutableStateFlow(false)
    val notificationsAsk: StateFlow<Boolean> = _notificationsAsk.asStateFlow()

    private fun maybeAskNotifications() {
        if (_notificationsAsk.value) return
        // Only the FIRST config earns the ask: an app that already has servers has
        // already had its moment. The value is the pre-add snapshot — Room's own flow
        // catches up to the insert later, off this coroutine's beat.
        if (subscriptions.value.isNotEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (settings.value.notificationsPermissionAsked) return
        // Asked is asked: the flag goes down before the dialog even shows, so a decline —
        // or a process death mid-dialog — is the user's final word, not an invitation.
        updateSettings { it.copy(notificationsPermissionAsked = true) }
        _notificationsAsk.value = true
    }

    /** Connection preferences: selection, routing mode, test URL, per-app bypass. */
    val connection: StateFlow<ConnectionSettings> = ConnectionStore.settings(app)

    fun updateConnection(transform: (ConnectionSettings) -> ConnectionSettings) {
        val before = connection.value
        ConnectionStore.update(app, transform)
        val after = connection.value
        // A routing change rewrites the running config; the tunnel reloads in place.
        if (before.routingMode != after.routingMode && tunnelStatus.value.state == TunnelState.UP) {
            applyConfigAndConnect()
        }
    }

    val tunnelStatus: StateFlow<TunnelStatus> = tunnelClient.status

    init {
        // The app filter and the trusted networks both live outside the config — the one
        // on the TUN interface, the other at the connect gate — so a change to either
        // asks for a full restart rather than a reload. The store itself is watched, so
        // changes made outside updateConnection (the per-app screen writes its own) ride
        // the same path.
        var previous = connection.value
        viewModelScope.launch {
            connection.drop(1).collect { current ->
                val bypassChanged = previous.bypassedApps != current.bypassedApps ||
                    previous.perAppEnabled != current.perAppEnabled
                val trustedChanged = previous.trustedWifiSsids != current.trustedWifiSsids ||
                    previous.trustedWifiEnabled != current.trustedWifiEnabled
                if ((bypassChanged || trustedChanged) && tunnelStatus.value.state == TunnelState.UP) {
                    tunnelClient.disconnect()
                    applyConfigAndConnect()
                }
                previous = current
            }
        }
    }

    /** The Xray core version string, for the about screen. */
    private val _coreVersion = MutableStateFlow<String?>(null)
    val coreVersion: StateFlow<String?> = _coreVersion.asStateFlow()

    /** Whether the core's loop is up — the about screen's status half. */
    val coreRunning: StateFlow<Boolean> = tunnelStatus
        .map { it.state == TunnelState.UP }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // ------------------------------------------------------------ subscriptions

    /** Every subscription, oldest first; the servers screen's dropdown list. */
    val subscriptions: StateFlow<List<SubscriptionEntity>> =
        repository.subscriptions()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The subscription whose profiles the servers screen shows. */
    val selectedSubscriptionId: StateFlow<String?> =
        combine(subscriptions, connection, repository.manualProfileCount) { subs, conn, manual ->
            // Deleted while selected: fall back to the first, or to nothing at all. The
            // standalone group keeps its selection only while it has something in it.
            conn.selectedSubscriptionId
                ?.takeIf { id -> id == STANDALONE_SUBSCRIPTION_ID && manual > 0 || subs.any { it.id == id } }
                ?: subs.firstOrNull()?.id
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** How many standalone configs wait outside any subscription. */
    val manualProfileCount: StateFlow<Int> =
        repository.manualProfileCount
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _updatingSubscriptionId = MutableStateFlow<String?>(null)

    /** The selected subscription's own row, for the card. */
    val selectedSubscription: StateFlow<SubscriptionEntity?> =
        combine(subscriptions, selectedSubscriptionId) { subs, id ->
            when (id) {
                STANDALONE_SUBSCRIPTION_ID -> SubscriptionEntity(
                    id = STANDALONE_SUBSCRIPTION_ID,
                    name = app.getString(R.string.standalone_name),
                )
                else -> subs.firstOrNull { it.id == id }
            }
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _servers = MutableStateFlow(ServersUiState())
    val servers: StateFlow<ServersUiState> = _servers.asStateFlow()

    /** The profile a connect uses, resolved against the current list. */
    val selectedProfile: StateFlow<ProfileEntity?> =
        combine(_servers, connection) { servers, conn ->
            servers.profiles.firstOrNull { it.id == conn.selectedProfileId }
                ?: servers.profiles.firstOrNull()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The setup gate: true once anything connectable exists — a subscription with servers
     * or a manually imported profile. Null while the first read is still in flight.
     */
    val setupDone: StateFlow<Boolean?> =
        combine(subscriptions, repository.manualProfileCount) { subs, manual ->
            subs.isNotEmpty() || manual > 0
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Home screen extras: identity chips, ping, uptime, loss. */
    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    /**
     * The stats screen's live history, kept in memory only. Declared before the init block:
     * its collector starts on Dispatchers.Main.immediate and emits synchronously from inside
     * the constructor, so it must already exist when observeTunnelForStats() runs. The week
     * is seeded from disk; the graphs themselves start empty.
     */
    private val _stats = MutableStateFlow(StatsUiState(week = DailyTrafficStore.readWeek(app)))
    val stats: StateFlow<StatsUiState> = _stats.asStateFlow()

    /**
     * Nothing has a profile until something is imported; the welcome screen watches this
     * gate and the main shell never renders before it opens.
     */
    private fun observeProfiles() {
        viewModelScope.launch {
            selectedSubscriptionId
                .flatMapLatest { id ->
                    // The balancer's internals — the outbounds of a config whose routing
                    // carries a surviving balancer — stay in the store (the balancer's
                    // members resolve by linkKey at connect) but never stand in the
                    // list: they are the group's plumbing, its parameters, not servers
                    // to pick from. A config presenting the same server on its own
                    // un-hides the row on the parser's side.
                    // The standalone group's id is a label for the manual profiles' null
                    // bucket.
                    repository.profilesOf(id?.takeUnless { it == STANDALONE_SUBSCRIPTION_ID })
                        .map { rows -> rows.filterNot { it.hidden } }
                }
                .combine(connection) { profiles, conn ->
                    val selected = profiles.firstOrNull { it.id == conn.selectedProfileId }
                    // A selection that no longer exists falls back to the list's head —
                    // persisted the moment it is made, so it survives restarts.
                    if (selected == null && profiles.isNotEmpty()) {
                        ConnectionStore.update(app) { it.copy(selectedProfileId = profiles.first().id) }
                    }
                    profiles
                }
                .collect { profiles ->
                    _servers.update { state ->
                        state.copy(
                            profiles = profiles,
                            selectedProfileId = profiles.firstOrNull { it.id == connection.value.selectedProfileId }?.id
                                ?: profiles.firstOrNull()?.id,
                        )
                    }
                    // A brand-new list with nothing pinged yet gets one silent sweep, so
                    // the screen opens with answers instead of question marks.
                    if (profiles.isNotEmpty() && profiles.all { it.testDelayMillis == null }) {
                        pingServers()
                    }
                }
        }
    }

    init {
        observeProfiles()
        observeTunnelForHome()
        observeTunnelForStats()
        observeIdentityChips()
        viewModelScope.launch {
            // The ":core" process spins up with the bind, but the bind itself is async —
            // the first ask often lands before the binder answers. Keep asking until it
            // does; the version never changes mid-session, so one answer settles it.
            while (_coreVersion.value == null) {
                _coreVersion.value = tunnelClient.coreVersion()?.let(::conciseCoreVersion)
                if (_coreVersion.value == null) delay(500)
            }
        }
        reparseAfterUpgrade()
    }

    /**
     * The core's banner ("Xray 26.3.27 (Xray, Penetrates Everything.) d2758a0 …") trimmed
     * to what a version line wants: the name and the number.
     */
    private fun conciseCoreVersion(raw: String): String =
        raw.lineSequence().firstOrNull()?.substringBefore(" (")?.ifBlank { raw } ?: raw

    /**
     * The parser grows — balancers, dialects, member hiding — while the store keeps
     * whatever the last fetch wrote. So the first launch after an upgrade re-fetches
     * every subscription once, silently: a fresh parse under the current rules, the
     * failures landing on their cards the way any update's would. First run has nothing
     * to re-fetch; the marker is still written, so only upgrades ever pay for this.
     */
    private fun reparseAfterUpgrade() {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = app.getSharedPreferences("aurora_upgrade", android.content.Context.MODE_PRIVATE)
            val versionCode = runCatching {
                app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
            }.getOrDefault(0L)
            val lastSeen = prefs.getLong("version_code", 0L)
            prefs.edit().putLong("version_code", versionCode).apply()
            if (lastSeen == 0L || lastSeen >= versionCode) return@launch
            repository.subscriptions().first().forEach { subscription ->
                repository.updateSubscription(subscription.id)
            }
        }
    }

    /** Home's server card follows the selection and the selected subscription. */
    private fun observeIdentityChips() {
        viewModelScope.launch {
            combine(selectedSubscription, selectedProfile) { subscription, profile ->
                // The flag panels put at the front of the name moves into the card's icon
                // slot; the card's header says subscription first, region second.
                val split = profile?.let { p -> splitLeadingFlag(p.remarks.ifEmpty { p.server }) }
                _home.update {
                    it.copy(
                        subscriptionName = subscription?.name,
                        profileFlag = split?.first,
                        profileLabel = split?.second?.ifEmpty { profile?.server },
                    )
                }
            }.collect { }
        }
    }

    // ------------------------------------------------------------ tunnel control

    /**
     * Connects (or reloads, if already up) with the selected profile and the selected
     * routing mode. The caller is responsible for the VPN consent; the service expects it.
     */
    fun connect() {
        if (selectedProfile.value == null) return
        applyConfigAndConnect()
    }

    fun disconnect() = tunnelClient.disconnect()

    private fun applyConfigAndConnect() {
        val profile = selectedProfile.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            // A balancer's members are resolved against the store, not the screen's
            // list — the screen hides the members, the store keeps them: linkKeys from
            // the column, rows from the subscription. A balancer whose members have all
            // fallen away degrades to the whole subscription — still a pick, just a
            // wider one.
            val members = if (profile.type == ProfileType.BALANCER) {
                val keys = profile.balancerMemberKeys.toSet()
                val rows = repository
                    .profilesOf(selectedSubscriptionId.value?.takeUnless { it == STANDALONE_SUBSCRIPTION_ID })
                    .first()
                val resolved = rows.filter {
                    it.type != ProfileType.BALANCER && it.linkKey in keys
                }
                resolved.ifEmpty { rows.filter { it.type != ProfileType.BALANCER } }
            } else {
                emptyList()
            }
            val config = XrayConfigBuilder.buildFull(
                profile,
                connection.value.routingMode,
                members,
                connection.value.delayTestUrl,
            )
            CoreConfigFile.write(
                app,
                config,
                profile.remarks.ifEmpty { profile.server },
                selectedSubscription.value?.name,
            )
            tunnelClient.connect()
        }
    }

    /**
     * Selects a server: persists the choice and, if the tunnel is up, reloads the running
     * core with the new profile in place — the interface stays, the session does not drop.
     */
    fun selectProfile(id: String) {
        if (_servers.value.selectedProfileId == id) return
        ConnectionStore.update(app) { it.copy(selectedProfileId = id) }
        if (tunnelStatus.value.state == TunnelState.UP) {
            applyConfigAndConnect()
        }
    }

    // ------------------------------------------------------------ subscriptions actions

    /**
     * Adds a subscription (the welcome screen and the dropdown's last row). The field
     * takes whatever the user pasted: a panel URL grows a subscription, anything else —
     * a share link, a WireGuard conf, a raw config — lands in the standalone group.
     */
    fun addSubscription(url: String, name: String? = null, onDone: (String?) -> Unit = {}) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            addManualProfiles(trimmed, onDone)
            return
        }
        viewModelScope.launch {
            repository.addSubscription(trimmed, name)
                .onSuccess { sub ->
                    ConnectionStore.update(app) { it.copy(selectedSubscriptionId = sub.id) }
                    _servers.update { it.copy(error = null) }
                    maybeAskNotifications()
                    onDone(null)
                }
                .onFailure { error ->
                    // A bad paste lands on the error card, not in a toast that is gone
                    // in a second — the add row's own failure path.
                    _servers.update {
                        it.copy(error = error.message ?: app.getString(R.string.error_unknown))
                    }
                    onDone(error.message ?: app.getString(R.string.error_unknown))
                }
        }
    }

    /** Refreshes the selected subscription (the card's update button). */
    fun updateSelectedSubscription() {
        val id = selectedSubscriptionId.value ?: return
        updateSubscription(id)
    }

    fun updateSubscription(id: String) {
        // The standalone group has no URL to re-fetch; its rows are only ever the
        // user's own pastes.
        if (id == STANDALONE_SUBSCRIPTION_ID) return
        if (_updatingSubscriptionId.value != null) return
        _updatingSubscriptionId.value = id
        _servers.update { it.copy(isUpdating = true, error = null) }
        viewModelScope.launch {
            repository.updateSubscription(id)
                .onSuccess { _servers.update { it.copy(isUpdating = false, error = null) } }
                .onFailure { error ->
                    _servers.update {
                        it.copy(isUpdating = false, error = error.message ?: app.getString(R.string.error_unknown))
                    }
                }
            _updatingSubscriptionId.value = null
        }
    }

    /** Deletes a subscription and everything it brought in — or the standalone group. */
    fun deleteSubscription(id: String) {
        viewModelScope.launch {
            if (id == STANDALONE_SUBSCRIPTION_ID) {
                repository.deleteManualProfiles()
            } else {
                repository.deleteSubscription(id)
            }
            if (connection.value.selectedSubscriptionId == id) {
                ConnectionStore.update(app) { it.copy(selectedSubscriptionId = null, selectedProfileId = null) }
            }
        }
    }

    /** Switches the servers screen to another subscription. */
    fun selectSubscription(id: String) {
        if (selectedSubscriptionId.value == id) return
        ConnectionStore.update(app) { it.copy(selectedSubscriptionId = id, selectedProfileId = null) }
        _servers.update { ServersUiState() }
    }

    /** The per-subscription auto-update toggle. */
    fun setSubscriptionAutoUpdate(id: String, enabled: Boolean) {
        viewModelScope.launch { repository.setSubscriptionAutoUpdate(id, enabled) }
    }

    /** Manual import: a link, a WireGuard .conf body, a raw config, or a list of links. */
    fun addManualProfiles(text: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            repository.addManualProfiles(text)
                .onSuccess { count ->
                    if (count > 0) {
                        // The group the configs landed in becomes the shown one, the
                        // same "select what you just added" the subscriptions follow.
                        ConnectionStore.update(app) {
                            it.copy(selectedSubscriptionId = STANDALONE_SUBSCRIPTION_ID, selectedProfileId = null)
                        }
                        _servers.update { it.copy(error = null) }
                        maybeAskNotifications()
                    }
                    onDone(if (count == 0) app.getString(R.string.import_nothing_new) else null)
                }
                .onFailure { error ->
                    _servers.update {
                        it.copy(error = error.message ?: app.getString(R.string.error_unknown))
                    }
                    onDone(error.message ?: app.getString(R.string.error_unknown))
                }
        }
    }

    // ------------------------------------------------------------ real pings

    /**
     * A real-ping sweep over the current list, one measurement core per profile in the
     * ":core" process, a handful at a time. Pull-to-refresh on the servers screen is this;
     * every profile's chip answers with its own number — or a dash, when the server is
     * unreachable.
     */
    fun pingServers() {
        // Balancers have no endpoint to measure: their "ping" is whichever member the
        // live routing is on, which the live loop reads anyway.
        val profiles = _servers.value.profiles.filter { it.type != ProfileType.BALANCER }
        if (profiles.isEmpty() || _servers.value.isPinging) return
        _servers.update { it.copy(isPinging = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val testUrl = connection.value.delayTestUrl
            val semaphore = Semaphore(PING_CONCURRENCY)
            val now = System.currentTimeMillis()
            val jobs = profiles.map { profile ->
                launch {
                    semaphore.withPermit {
                        val config = XrayConfigBuilder.buildTest(profile)
                        val delay = tunnelClient.measureDelay(config, testUrl)
                        if (currentCoroutineContext().isActive) {
                            repository.setProfileDelay(profile.id, delay, now)
                        }
                    }
                }
            }
            jobs.joinAllSafe()
            _servers.update { it.copy(isPinging = false) }
        }
    }

    private suspend fun List<Job>.joinAllSafe() {
        forEach { runCatching { it.join() } }
    }

    /**
     * Measures the selected server's delay right now — the servers screen's selected row
     * and Home's chip share the result. Returns null when there is nothing to measure.
     */
    fun pingSelected() {
        val profile = selectedProfile.value ?: return
        // A balancer is measured by living: the live loop reports the tunnel's own delay.
        if (profile.type == ProfileType.BALANCER) return
        viewModelScope.launch(Dispatchers.IO) {
            val delay = tunnelClient.measureDelay(
                XrayConfigBuilder.buildTest(profile),
                connection.value.delayTestUrl,
            )
            repository.setProfileDelay(profile.id, delay, System.currentTimeMillis())
        }
    }

    // ------------------------------------------------------------ home + stats loops

    /**
     * Uptime and the 15-second live-delay loop live here. The same probe feeds the loss
     * buckets: a probe that fails to fetch through the tunnel is a lost sample, the way
     * the panel's TCP handshake used to be.
     */
    private fun observeTunnelForHome() {
        viewModelScope.launch {
            tunnelStatus
                .distinctUntilChanged { old, new -> old.state == new.state }
                .collect { status ->
                    when (status.state) {
                        TunnelState.UP -> {
                            _home.update {
                                it.copy(uptimeStartMillis = tunnelClient.connectedAtMillis() ?: System.currentTimeMillis())
                            }
                            startPingLoop()
                        }

                        else -> {
                            pingJob?.cancel()
                            _home.update {
                                it.copy(pingMillis = null, uptimeStartMillis = null, recentLossPercent = null)
                            }
                        }
                    }
                }
        }
    }

    private var pingJob: Job? = null

    /**
     * Runs the live probe every 15 s while the tunnel is up: each round refreshes Home's
     * ping chip and lands in the stats screen's loss buckets. Coming into view restarts
     * the loop from this moment, so the chip answers immediately.
     */
    fun refreshPing(showPending: Boolean = false) {
        if (tunnelStatus.value.state != TunnelState.UP) return
        if (showPending) _home.update { it.copy(pingMillis = null) }
        startPingLoop()
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(STATS_START_DELAY_MILLIS)
            while (isActive && tunnelStatus.value.state == TunnelState.UP) {
                val now = System.currentTimeMillis()
                val delay = tunnelClient.measureLiveDelay(connection.value.delayTestUrl)
                if (tunnelStatus.value.state == TunnelState.UP && delay != null) {
                    if (delay >= 0) {
                        _home.update { it.copy(pingMillis = delay) }
                    } else {
                        _home.update { it.copy(pingMillis = null) }
                    }
                    recordProbe(now, lost = delay < 0)
                }
                pacedDelay(
                    if (appInForeground) PING_INTERVAL_MILLIS else BACKGROUND_PROBE_MILLIS,
                )
            }
        }
    }

    // The stats session's bookkeeping.
    private var sessionStartMillis = 0L

    private var lastFlushAtMillis = 0L

    /**
     * Whether the app's UI is on screen, told by the activity's start/stop. The stats loops
     * sample once a second in the foreground and take a background stride (30 s) once the
     * app leaves it — the history stays warm behind the scenes, so returning doesn't find
     * a cold graph that has to re-fill from nothing.
     */
    @Volatile private var appInForeground = true

    /** Set from MainActivity.onStart/onStop; see [appInForeground]. */
    fun setAppInForeground(value: Boolean) {
        appInForeground = value
    }

    /**
     * The stats graphs' feeds run only while the tunnel is up: the traffic counters are
     * sampled every second (a local binder call, no network) and the tunnel is probed
     * every fifteen (the same live-delay measurement Home's chip uses). Both start with
     * the tunnel, so the history has already filled by the time the screen is opened.
     * Coming down does not clear anything: the graphs freeze where they stand, the
     * freeze's timestamp and the connection's length are recorded for the screen's chips,
     * and a new connection simply keeps appending — the trimming window sorts the old
     * session out.
     */
    private fun observeTunnelForStats() {
        viewModelScope.launch {
            tunnelStatus
                .distinctUntilChanged { old, new -> old.state == new.state }
                .collect { status ->
                    when (status.state) {
                        TunnelState.UP -> {
                            sessionStartMillis = tunnelClient.connectedAtMillis()
                                ?: System.currentTimeMillis()
                            _stats.update { it.copy(frozenAtMillis = null, connectedMillis = null) }
                            startTrafficLoop()
                        }

                        else -> {
                            trafficJob?.cancel()
                            // Only the first non-UP moment after a connection freezes the
                            // graphs; the states that follow (CONNECTING after a failure and
                            // such) must not restamp them.
                            val now = System.currentTimeMillis()
                            _stats.update { state ->
                                state.copy(
                                    frozenAtMillis = state.frozenAtMillis ?: now,
                                    connectedMillis = state.connectedMillis
                                        ?: sessionStartMillis.takeIf { it != 0L }
                                            ?.let { start -> now - start },
                                )
                            }
                        }
                    }
                }
        }
    }

    /**
     * The traffic feed: asks the core for its counters once a second and folds the bytes
     * into 15-second buckets. Every query answers the bytes since the *previous* one —
     * the core resets its counters on each read, the Xray way — so the rates are deltas
     * over their own intervals and the session's totals grow here, one delta at a time;
     * a reload that swaps the core underneath goes by unnoticed, both cores count from
     * zero. The graph's points are the bucket averages (bytes over time, a background
     * stride's longer interval weighed in), so they stay rare while the sampling
     * underneath keeps its cadence, and the in-progress bucket keeps updating live until
     * it settles. The day's bucket flushes at most once a minute; the exit flush in the
     * finally block catches the session's tail — cancellation runs it too.
     */
    private fun startTrafficLoop() {
        trafficJob?.cancel()
        trafficJob = viewModelScope.launch(Dispatchers.IO) {
            var lastAtMillis = 0L
            var sessionRx = 0L
            var sessionTx = 0L
            var sinceFlushRx = 0L
            var sinceFlushTx = 0L
            var bucketStart = 0L
            var bucketMillis = 0L
            var bucketDownBytes = 0L
            var bucketUpBytes = 0L
            try {
                kotlinx.coroutines.delay(STATS_START_DELAY_MILLIS)
                while (isActive && tunnelStatus.value.state == TunnelState.UP) {
                    val traffic = tunnelClient.trafficBytes()
                    val now = System.currentTimeMillis()
                    if (traffic != null && lastAtMillis != 0L) {
                        val down = traffic[0]
                        val up = traffic[1]
                        val elapsed = (now - lastAtMillis).coerceAtLeast(1L)
                        sessionRx += down
                        sessionTx += up
                        sinceFlushRx += down
                        sinceFlushTx += up
                        val currentBucket = (now / TRAFFIC_BUCKET_MILLIS) * TRAFFIC_BUCKET_MILLIS
                        if (currentBucket != bucketStart) {
                            bucketStart = currentBucket
                            bucketMillis = 0L
                            bucketDownBytes = 0L
                            bucketUpBytes = 0L
                        }
                        bucketMillis += elapsed
                        bucketDownBytes += down
                        bucketUpBytes += up
                        val bucketDown = bucketDownBytes * 1_000 / bucketMillis
                        val bucketUp = bucketUpBytes * 1_000 / bucketMillis
                        _stats.update { state ->
                            val samples = state.samples.toMutableList()
                            val index = samples.indexOfLast { it.atMillis == bucketStart }
                            val point = TrafficSample(
                                atMillis = bucketStart,
                                rxTotal = sessionRx,
                                txTotal = sessionTx,
                                downBps = bucketDown,
                                upBps = bucketUp,
                            )
                            if (index >= 0) samples[index] = point else samples.add(point)
                            state.copy(
                                samples = trimWindow(samples),
                                rxTotal = sessionRx,
                                txTotal = sessionTx,
                            )
                        }
                        if (now - lastFlushAtMillis >= DAILY_FLUSH_MILLIS) {
                            flushDailyTraffic(sinceFlushRx, sinceFlushTx)
                            sinceFlushRx = 0L
                            sinceFlushTx = 0L
                            lastFlushAtMillis = now
                        }
                    }
                    // A null answer means the call never reached the core — its counters
                    // kept counting, nothing is lost by moving the clock on.
                    lastAtMillis = now
                    pacedDelay(
                        if (appInForeground) TRAFFIC_SAMPLE_MILLIS else BACKGROUND_SAMPLE_MILLIS,
                    )
                }
            } finally {
                flushDailyTraffic(sinceFlushRx, sinceFlushTx)
            }
        }
    }

    private fun flushDailyTraffic(rx: Long, tx: Long) {
        if (rx <= 0 && tx <= 0) return
        val week = DailyTrafficStore.addToday(app, rx.coerceAtLeast(0), tx.coerceAtLeast(0))
        _stats.update { it.copy(week = week) }
    }

    /** One probe outcome lands in its 15-second bucket and re-weighs the warning window. */
    private fun recordProbe(atMillis: Long, lost: Boolean) {
        val bucketStart = (atMillis / PROBE_BUCKET_MILLIS) * PROBE_BUCKET_MILLIS
        _stats.update { state ->
            val buckets = state.buckets.toMutableList()
            val index = buckets.indexOfLast { it.atMillis == bucketStart }
            if (index >= 0) {
                val bucket = buckets[index]
                buckets[index] = bucket.copy(sent = bucket.sent + 1, lost = bucket.lost + if (lost) 1 else 0)
            } else {
                buckets.add(LossBucket(bucketStart, 1, if (lost) 1 else 0))
            }
            val warning = recentLoss(buckets)
            _home.update { it.copy(recentLossPercent = warning) }
            state.copy(buckets = trimLossBuckets(buckets), recentLossPercent = warning)
        }
    }

    /** The failed-probe share over the last [RECENT_LOSS_WINDOW_MILLIS] — null while
     *  nothing was probed, and only set on the states once it crosses the threshold. */
    private fun recentLoss(buckets: List<LossBucket>): Float? {
        val cutoff = System.currentTimeMillis() - RECENT_LOSS_WINDOW_MILLIS
        val recent = buckets.filter { it.atMillis >= cutoff }
        val sent = recent.sumOf { it.sent }
        if (sent == 0) return null
        val percent = recent.sumOf { it.lost } * 100f / sent
        return percent.takeIf { it >= LOSS_WARNING_THRESHOLD }
    }

    /**
     * Sleeps [totalMillis] in one-second slices instead of one unbroken delay: a return to
     * the foreground cuts the remaining wait down to a second, so the graphs pick their
     * live pace back up almost immediately instead of finishing a whole background stride.
     */
    private suspend fun pacedDelay(totalMillis: Long) {
        var remaining = totalMillis
        while (remaining > 0 && currentCoroutineContext().isActive) {
            kotlinx.coroutines.delay(minOf(1_000L, remaining))
            remaining -= 1_000L
            if (appInForeground && remaining > TRAFFIC_SAMPLE_MILLIS) {
                remaining = TRAFFIC_SAMPLE_MILLIS
            }
        }
    }

    /**
     * The stats screen's live history, kept in memory only. The week is seeded from disk;
     * the graphs themselves start empty.
     */
    private var trafficJob: Job? = null

    private companion object {
        /** How many one-off measurement cores may run at once in ":core". */
        const val PING_CONCURRENCY = 3

        /** The graph window: history older than this falls off the left edge. */
        const val STATS_WINDOW_MILLIS = 15 * 60_000L

        const val TRAFFIC_SAMPLE_MILLIS = 1_000L

        /** Home's ping / the loss probe cadence — one loop serves both. */
        const val PING_INTERVAL_MILLIS = 15_000L

        /** The graphs' point granularity: rates and probes fold into 15-second buckets. */
        const val TRAFFIC_BUCKET_MILLIS = 15_000L
        const val PROBE_BUCKET_MILLIS = 15_000L

        /** How long the loops wait after the tunnel comes up before collecting anything. */
        const val STATS_START_DELAY_MILLIS = 3_000L

        /** The loops' background stride: the app gone from screen, the history keeps
         *  trickling in — rare enough to be cheap, often enough to stay warm. */
        const val BACKGROUND_SAMPLE_MILLIS = 30_000L
        const val BACKGROUND_PROBE_MILLIS = 30_000L

        /** The warning window and threshold: failed probes over the last two minutes above
         *  10% trip the red chips on the stats screen and home. */
        const val RECENT_LOSS_WINDOW_MILLIS = 2 * 60_000L
        const val LOSS_WARNING_THRESHOLD = 10f

        /** The day bucket's flush cadence; the loop's exit flushes the tail. */
        const val DAILY_FLUSH_MILLIS = 60_000L

        fun trimWindow(samples: List<TrafficSample>): List<TrafficSample> {
            val cutoff = System.currentTimeMillis() - STATS_WINDOW_MILLIS
            return samples.dropWhile { it.atMillis < cutoff }
        }

        fun trimLossBuckets(buckets: List<LossBucket>): List<LossBucket> {
            val cutoff = System.currentTimeMillis() - STATS_WINDOW_MILLIS
            return buckets.dropWhile { it.atMillis < cutoff }
        }
    }
}
