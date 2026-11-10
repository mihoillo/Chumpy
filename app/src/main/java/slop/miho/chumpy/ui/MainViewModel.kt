package slop.miho.chumpy.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import slop.miho.chumpy.ChumpyApp
import slop.miho.chumpy.Graph
import slop.miho.chumpy.R
import slop.miho.chumpy.core.CoreConfigFile
import slop.miho.chumpy.core.TunnelState
import slop.miho.chumpy.core.TunnelStatus
import slop.miho.chumpy.core.UpdateIntents
import slop.miho.chumpy.core.UpdateNotifier
import slop.miho.chumpy.core.UpdateRequests
import slop.miho.chumpy.data.ConnectionSettings
import slop.miho.chumpy.data.ConnectionStore
import slop.miho.chumpy.data.DailyTraffic
import slop.miho.chumpy.data.DailyTrafficStore
import slop.miho.chumpy.ui.components.splitLeadingFlag
import slop.miho.chumpy.data.SettingsStore
import slop.miho.chumpy.data.ServersSort
import slop.miho.chumpy.data.UiSettings
import slop.miho.chumpy.data.comparator
import slop.miho.chumpy.sub.UpdateCheck
import slop.miho.chumpy.sub.UpdatePrefs
import slop.miho.chumpy.sub.UpdateRelease
import slop.miho.chumpy.db.ProfileEntity
import slop.miho.chumpy.db.ProfileType
import slop.miho.chumpy.db.STANDALONE_SUBSCRIPTION_ID
import slop.miho.chumpy.db.SubscriptionEntity
import slop.miho.chumpy.db.balancerMemberKeys
import slop.miho.chumpy.sub.SubscriptionRepository
import slop.miho.chumpy.xray.XrayConfigBuilder
import slop.miho.chumpy.xray.RoutingMode

/** Home screen extras: everything besides the tunnel status itself. */
data class HomeUiState(
    /** The active subscription's name — the server card's first header line. */
    val subscriptionName: String? = null,
    /** The selected profile's name, its leading flag lifted out into [profileFlag]. */
    val profileLabel: String? = null,
    /** The flag emoji the selected profile's name begins with, if it does. */
    val profileFlag: String? = null,
    /** Whether the active subscription's calendar has already run out — the red chip. */
    val subscriptionExpired: Boolean = false,
    /** Round-trip through the running tunnel, refreshed every 15 s while connected. */
    val pingMillis: Long? = null,
    /** Wall-clock millis of the last transition to UP; null while down. */
    val uptimeStartMillis: Long? = null,
    /** The recent failed probes, but only once they cross the warning threshold — null
     *  means the tunnel is healthy (or nothing has been measured yet), and the chip stays
     *  hidden. */
    val recentLossPercent: Float? = null,
)

/**
 * The update checker's state. A release the repo holds beyond the running build shows the
 * home screen's chip; the chip's own story — a download's percent, a finished APK — is
 * told in the live update notification, the state here only mirrors it for the chip's
 * text. [hidden] is the user's long press: this release's chip is waved away until the
 * repo holds a newer one; [downloadedFile] is the APK that already sits on disk;
 * [paused] is a download held at its pause, its bytes kept for the resume;
 * [checking] is the about screen's manual ask in flight.
 */
data class UpdateUiState(
    val available: UpdateRelease? = null,
    val hidden: Boolean = false,
    val downloading: Boolean = false,
    val downloadPercent: Int = 0,
    val downloadedFile: java.io.File? = null,
    val paused: Boolean = false,
    val checking: Boolean = false,
)

/** The servers screen's list: the selected subscription's profiles, plus the transient bits. */
data class ServersUiState(
    val profiles: List<ProfileEntity> = emptyList(),
    /** The subscription's concealed rows, kept out of [profiles] — the header's chip
     *  counts them and the reveal mode shows them. */
    val concealed: List<ProfileEntity> = emptyList(),
    val selectedProfileId: String? = null,
    /** The list's own order, the header's chip picks it. */
    val sort: ServersSort = ServersSort.DEFAULT,
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
    private val tunnelClient = ChumpyApp.tunnelClient
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

    fun consumeNotificationsAsk() {
        _notificationsAsk.value = false
    }

    private fun maybeAskNotifications(firstConfigEver: Boolean) {
        if (_notificationsAsk.value) return
        // The "first ever" was captured BEFORE the insert: Room's own flow can report
        // the new row before the add's own callback runs, and the ask rides an insert
        // into an empty app, not one that raced a collector.
        if (!firstConfigEver) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (settings.value.notificationsPermissionAsked) return
        // Asked is asked: the flag goes down before the dialog even shows, so a decline —
        // or a process death mid-dialog — is the user's final word, not an invitation.
        updateSettings { it.copy(notificationsPermissionAsked = true) }
        _notificationsAsk.value = true
    }

    // ------------------------------------------------------------ update checker

    private val _update = MutableStateFlow(UpdateUiState())

    /** The repo's latest release beyond the running build, as the home chip tells it. */
    val update: StateFlow<UpdateUiState> = _update.asStateFlow()

    /** The running download's job — paused by cancelling it, the partial kept for the resume. */
    private var downloadJob: Job? = null

    /**
     * Asks the repo on every app open whether it holds something newer. The check is
     * silent by design: a miss (offline, GitHub unreachable, a reshaped payload) leaves
     * the screen exactly as it was — the next open asks again.
     */
    private suspend fun checkForUpdate() {
        val release = try {
            UpdateCheck.fetch { tunnelStatus.value.state == TunnelState.UP }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Log.w("Chumpy/Update", "Update check failed", failure)
            null
        } ?: return
        if (release.version <= UpdateCheck.currentVersion()) {
            // Up to date: any leftover notification and file belong to a version already
            // installed — the shade line goes, the tag on disk forgets.
            UpdateNotifier.cancel(app)
            UpdatePrefs.clearDownloaded(app)
            return
        }
        val downloaded = UpdatePrefs.downloadedTag(app)
            ?.takeIf { it == release.tag }
            ?.let { UpdatePrefs.apkFile(app, it) }
            ?.takeIf { it.isFile }
        val partialIsThere = UpdateCheck.partialOf(UpdatePrefs.apkFile(app, release.tag)).isFile
        // A process death takes the download's job with it; a shade line for a stream
        // nobody runs anymore goes with it too.
        if (downloaded == null && !_update.value.downloading && !_update.value.paused) {
            UpdateNotifier.cancel(app)
        }
        _update.update { current ->
            current.copy(
                available = release,
                hidden = UpdatePrefs.hiddenTag(app) == release.tag,
                downloadedFile = downloaded,
                // A pause only stands while its bytes stand under it; a fresh process
                // calls a partial merely resumable.
                paused = current.paused && partialIsThere,
            )
        }
        // A process death between the download and the install took the shade line with
        // it; an APK already on disk puts it back — unless this release was waved away,
        // which stands until the repo holds something newer.
        if (downloaded != null && UpdatePrefs.hiddenTag(app) != release.tag) {
            UpdateNotifier.ready(app)
        }
    }

    /**
     * The about screen's own ask: the repo is polled again on demand, the answer landing
     * wherever the state already speaks — the chip, the about card. While it runs the
     * row says so; a second tap lands on the same running check.
     */
    fun recheckUpdate() {
        if (_update.value.checking) return
        _update.update { it.copy(checking = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                checkForUpdate()
            } finally {
                _update.update { it.copy(checking = false) }
            }
        }
    }

    /**
     * The chip's long press and the notification's own Dismiss: this release is waved
     * away — the home chip and the shade line, until the repo holds something newer. A
     * download still running for it stops and its partial goes; the about screen's
     * banner stays, it only ever follows the repo.
     */
    fun hideUpdate() {
        val tag = _update.value.available?.tag ?: return
        UpdatePrefs.hideTag(app, tag)
        if (_update.value.downloading || _update.value.paused) {
            downloadJob?.cancel()
            downloadJob = null
            UpdateCheck.discardPartial(UpdatePrefs.apkFile(app, tag))
        }
        _update.update { it.copy(downloading = false, paused = false, hidden = true) }
        UpdateNotifier.cancel(app)
    }

    /**
     * The chip's and the about card's tap: installs an APK already on disk, holds a
     * running download at its pause, and otherwise starts one — a paused partial picked
     * back up from wherever its bytes reached.
     */
    fun onUpdateChipTap() {
        val state = _update.value
        val release = state.available ?: return
        when {
            state.downloading -> pauseUpdate()
            state.downloadedFile != null -> installUpdate()
            else -> startUpdateDownload(release)
        }
    }

    /** The download's own pause: the job is cancelled, the partial kept for the resume. */
    private fun pauseUpdate() {
        downloadJob?.cancel()
        downloadJob = null
        val state = _update.value
        _update.update { it.copy(downloading = false, paused = true) }
        state.available?.let { release ->
            UpdateNotifier.paused(app, state.downloadPercent, release.tag)
        }
    }

    /** The shade's own resume: the same start, the partial picked back up. */
    fun resumeUpdate() {
        val release = _update.value.available ?: return
        if (_update.value.downloading) return
        startUpdateDownload(release)
    }

    /** The shade's own cancel: the download stops clean and the partial goes. */
    fun cancelUpdateDownload() {
        val release = _update.value.available
        downloadJob?.cancel()
        downloadJob = null
        if (release != null) UpdateCheck.discardPartial(UpdatePrefs.apkFile(app, release.tag))
        _update.update { it.copy(downloading = false, paused = false, downloadPercent = 0) }
        UpdateNotifier.cancel(app)
    }

    /**
     * The download itself — the progress lives in the live update notification. A
     * partial from an earlier pause or a failure restarts from wherever its bytes
     * stopped, through a range, so a pause costs nothing but time.
     */
    private fun startUpdateDownload(release: UpdateRelease) {
        val target = UpdatePrefs.apkFile(app, release.tag)
        val startPercent = UpdateCheck.partialPercent(release, target)
        _update.update { it.copy(downloading = true, paused = false, downloadPercent = startPercent) }
        UpdateNotifier.downloading(app, startPercent, release.tag)
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                var lastPosted = startPercent
                UpdateCheck.download(
                    release,
                    target,
                    { tunnelStatus.value.state == TunnelState.UP },
                ) { percent ->
                    _update.update { current ->
                        if (current.downloading) current.copy(downloadPercent = percent) else current
                    }
                    // A chunk can land between percents; the shade only hears the changes.
                    if (percent != lastPosted) {
                        UpdateNotifier.downloading(app, percent, release.tag)
                        lastPosted = percent
                    }
                }
                UpdatePrefs.markDownloaded(app, release.tag)
                _update.update { it.copy(downloading = false, downloadPercent = 100, downloadedFile = UpdatePrefs.apkFile(app, release.tag)) }
                UpdateNotifier.ready(app)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Log.w("Chumpy/Update", "Update download failed", failure)
                _update.update { it.copy(downloading = false, downloadPercent = 0) }
                UpdateNotifier.cancel(app)
            }
        }
    }

    /**
     * Hands the downloaded APK to the system's installer. The installer's own consent (the
     * "install unknown apps" blessing, Oreo and newer) is asked by the system: without it
     * the user lands on the settings page that grants it, and the notification stays for
     * another tap. The tag is read from the store, not the state — the notification's tap
     * after a process death must find the file too.
     */
    fun installUpdate() {
        val tag = UpdatePrefs.downloadedTag(app) ?: return
        val file = UpdatePrefs.apkFile(app, tag).takeIf { it.isFile } ?: return
        val uri = FileProvider.getUriForFile(app, "slop.miho.chumpy.fileprovider", file)
        val install = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val target = if (app.packageManager.canRequestPackageInstalls()) install
        else Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(target) }
            .onFailure { Log.w("Chumpy/Update", "Install request failed", it) }
    }

    /** Whether nothing exists yet — the ask rides only the very first config's landing. */
    private fun appIsEmpty(): Boolean =
        subscriptions.value.isEmpty() && manualProfileCount.value == 0

    /** Connection preferences: selection, routing mode, test URL, per-app bypass. */
    val connection: StateFlow<ConnectionSettings> = ConnectionStore.settings(app)

    fun updateConnection(transform: (ConnectionSettings) -> ConnectionSettings) {
        val before = connection.value
        ConnectionStore.update(app, transform)
        val after = connection.value
        // A routing change rewrites the running config; the tunnel reloads in place.
        if (before.routingMode != after.routingMode &&
            tunnelStatus.value.state in setOf(TunnelState.UP, TunnelState.CONNECTING)) {
            applyConfigAndConnect()
        }
    }

    val tunnelStatus: StateFlow<TunnelStatus> = tunnelClient.status

    private var connectJob: Job? = null
    private val connectGeneration = java.util.concurrent.atomic.AtomicInteger()
    private val configurationMutex = Mutex()
    private var sweepJob: Job? = null
    @Volatile private var sweepGeneration = 0L
    private val pingSemaphore = Semaphore(MAX_PING_CONCURRENCY)


    init {
        // The app filter lives outside the config — it rides the TUN interface itself —
        // so a change to it asks for a full restart rather than a reload. The store
        // itself is watched, so changes made outside updateConnection (the per-app
        // screen writes its own) ride the same path.
        var previous = connection.value
        viewModelScope.launch {
            connection.drop(1).collect { current ->
                val bypassChanged = previous.bypassedApps != current.bypassedApps ||
                    previous.perAppEnabled != current.perAppEnabled || previous.bypassLan != current.bypassLan
                if (bypassChanged && tunnelStatus.value.state in setOf(TunnelState.UP, TunnelState.CONNECTING)) {
                    applyConfigAndConnect()
                }
                previous = current
            }
        }
    }

    /** The Xray core version string, for the about screen. */
    private val _coreVersion = MutableStateFlow<String?>(null)
    val coreVersion: StateFlow<String?> = _coreVersion.asStateFlow()

    /**
     * The core's captured log tail. A snapshot poll rather than a stream: the viewer is
     * the only reader, and it asks only while its fold stands open — once a second,
     * binder-cheap, and null simply means the core process is not up yet.
     */
    val coreLogs: StateFlow<String?> = flow {
        while (true) {
            emit(tunnelClient.coreLogs())
            delay(1000)
        }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1000), null)

    /** Whether the core's loop is up — the about screen's status half. */
    val coreRunning: StateFlow<Boolean> = tunnelStatus
        .map { it.state == TunnelState.UP }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // ------------------------------------------------------------ subscriptions

    /** Every subscription, the pinned ones standing the dropdown's ground floor. */
    val subscriptions: StateFlow<List<SubscriptionEntity>> =
        repository.subscriptions()
            .map { subs -> subs.sortedBy { !it.pinned } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The subscription whose profiles the servers screen shows. */
    val selectedSubscriptionId: StateFlow<String?> =
        combine(subscriptions, connection, repository.manualProfileCount) { subs, conn, manual ->
            // Deleted while selected: fall back to the first, or to nothing at all. The
            // standalone group keeps its selection only while it has something in it.
            conn.selectedSubscriptionId
                ?.takeIf { id -> id == STANDALONE_SUBSCRIPTION_ID && manual > 0 || subs.any { it.id == id } }
                ?: subs.firstOrNull()?.id
                ?: STANDALONE_SUBSCRIPTION_ID.takeIf { manual > 0 }
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
    private data class ActiveIdentity(val profile: ProfileEntity, val subscription: SubscriptionEntity?)
    private val activeIdentity = MutableStateFlow<ActiveIdentity?>(null)

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    /**
     * The stats screen's live history, kept in memory only. Declared before the init block:
     * its collector starts on Dispatchers.Main.immediate and emits synchronously from inside
     * the constructor, so it must already exist when observeTunnelForStats() runs. The week
     * is seeded from disk; the graphs themselves start empty.
     */
    private var pingJob: Job? = null
    private var trafficJob: Job? = null
    private var sessionStartMillis = 0L
    private var lastFlushAtMillis = 0L

    private val _stats = MutableStateFlow(StatsUiState(week = DailyTrafficStore.readWeek(app)))
    val stats: StateFlow<StatsUiState> = _stats.asStateFlow()

    /**
     * Nothing has a profile until something is imported; the welcome screen watches this
     * gate and the main shell never renders before it opens.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeProfiles() {
        viewModelScope.launch {
            selectedSubscriptionId
                .flatMapLatest { id ->
                    // The balancer's internals — the outbounds of a config whose routing
                    // carries a surviving balancer — stay in the store (the balancer's
                    // members resolve by linkKey at connect) but never stand in the
                    // list, not even in the reveal mode: they are the group's plumbing,
                    // its parameters, not servers to pick from. A config presenting the
                    // same server on its own un-hides the row on the parser's side.
                    // The standalone group's id is a label for the manual profiles' null
                    // bucket. The user's own hide — the concealed flag — keeps its rows
                    // in the store too, but those the reveal mode can call back.
                    repository.profilesOf(id?.takeUnless { it == STANDALONE_SUBSCRIPTION_ID })
                        .map { rows -> id to rows }
                        .flowOn(Dispatchers.Default)
                }
                .combine(connection) { (id, rows), conn -> Triple(id, rows, conn) }
                .collect { (id, rows, conn) ->
                    val requested = conn.selectedSubscriptionId
                    if (requested != null && requested != id &&
                        (requested == STANDALONE_SUBSCRIPTION_ID || subscriptions.value.any { it.id == requested })) {
                        return@collect
                    }
                    val (visible, concealed) = withContext(Dispatchers.Default) {
                        val sort = conn.serversSort.comparator()
                        rows.filterNot { it.hidden || it.concealed }.sortedWith(sort) to
                            rows.filter { it.concealed && !it.hidden }.sortedWith(sort)
                    }
                    if (visible.none { it.id == conn.selectedProfileId } && visible.isNotEmpty()) {
                        ConnectionStore.update(app) { current ->
                            if (current.selectedSubscriptionId == requested) current.copy(selectedProfileId = visible.first().id)
                            else current
                        }
                    }
                    _servers.update { state ->
                        state.copy(profiles = visible, concealed = concealed,
                            sort = conn.serversSort,
                            selectedProfileId = visible.firstOrNull { it.id == connection.value.selectedProfileId }?.id
                                ?: visible.firstOrNull()?.id)
                    }
                    if (visible.any { it.type != ProfileType.BALANCER && it.lastTestAt == null }) {
                        startServerSweep(true)
                    }
                }
        }
    }

    init {
        observeProfiles()
        observeTunnelForHome()
        observeTunnelForStats()
        observeIdentityChips()
        // Every app open asks the repo for a newer release — silent on any miss.
        viewModelScope.launch(Dispatchers.IO) { checkForUpdate() }
        // The update notification's own buttons, coming home: pause, resume, cancel the
        // download, wave the release away — the same hands the chip's taps reach.
        viewModelScope.launch {
            UpdateRequests.actions.collect { action ->
                when (action) {
                    UpdateIntents.PAUSE -> if (_update.value.downloading) pauseUpdate()
                    UpdateIntents.RESUME -> resumeUpdate()
                    UpdateIntents.CANCEL -> cancelUpdateDownload()
                    UpdateIntents.DISMISS -> hideUpdate()
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
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
            val prefs = app.getSharedPreferences("chumpy_upgrade", android.content.Context.MODE_PRIVATE)
            val versionCode = runCatching {
                val info = app.packageManager.getPackageInfo(app.packageName, 0)
                if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
            }.getOrDefault(0L)
            val lastSeen = prefs.getLong("version_code", 0L)
            if (lastSeen >= versionCode) return@launch
            val pending = repository.subscriptions().first()
            if (lastSeen == 0L && pending.isEmpty()) {
                prefs.edit().putLong("version_code", versionCode).apply()
                return@launch
            }
            var succeeded = true
            pending.forEach { subscription ->
                if (repository.updateSubscription(subscription.id).isFailure) succeeded = false
            }
            if (succeeded) prefs.edit().putLong("version_code", versionCode).apply()
        }
    }

    /** Home's server card follows the selection and the selected subscription. */
    private fun observeIdentityChips() {
        viewModelScope.launch {
            val liveIdentity = combine(activeIdentity, subscriptions) { active, current ->
                active?.copy(subscription = active.subscription?.let { saved ->
                    current.firstOrNull { it.id == saved.id } ?: saved
                })
            }
            val clock = flow {
                while (currentCoroutineContext().isActive) {
                    val now = System.currentTimeMillis()
                    emit(now)
                    val expiry = (activeIdentity.value?.subscription ?: selectedSubscription.value)?.expireEpochSeconds ?: 0
                    delay(if (expiry > now / 1000) ((expiry * 1000 - now) + 1).coerceIn(1, 60_000) else 60_000)
                }
            }
            combine(selectedSubscription, selectedProfile, settings, liveIdentity, clock) { selectedSub, selected, ui, active, now ->
                val subscription = if (active != null) active.subscription else selectedSub
                val profile = active?.profile ?: selected
                // The flag panels put at the front of the name moves into the card's icon
                // slot; the card's header says subscription first, region second.
                val split = profile?.let { p -> splitLeadingFlag(p.remarks.ifEmpty { if (ui.maskAddresses) "" else p.server }) }
                // The addresses' curtain reaches Home too: a nameless server's card
                // keeps the address out of sight — the region line stands empty
                // rather than saying where it goes.
                val fallback = if (ui.maskAddresses) "" else profile?.server
                _home.update {
                    it.copy(
                        subscriptionName = subscription?.name,
                        profileFlag = split?.first,
                        profileLabel = split?.second?.ifEmpty { fallback },
                        // The panel's calendar, read once per report: a subscription
                        // whose expiry has already passed wears the red chip.
                        subscriptionExpired = subscription != null &&
                            subscription.expireEpochSeconds > 0 &&
                            subscription.expireEpochSeconds <= now / 1000,
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
        applyConfigAndConnect()
    }

    fun disconnect() {
        val generation = connectGeneration.incrementAndGet()
        connectJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            configurationMutex.withLock {
                if (generation == connectGeneration.get()) tunnelClient.disconnect()
            }
        }
    }

    private fun applyConfigAndConnect(
        profile: ProfileEntity? = _servers.value.profiles.firstOrNull { it.id == connection.value.selectedProfileId }
            ?: _servers.value.profiles.firstOrNull(),
    ) {
        profile ?: return
        val requestedConnection = connection.value
        val generation = connectGeneration.incrementAndGet()
        connectJob?.cancel()
        connectJob = viewModelScope.launch(Dispatchers.IO) {
            configurationMutex.withLock {
                try {
                    val resolvedProfile = repository.profileById(profile.id)
                        ?: throw IllegalStateException("Selected server no longer exists")
                    require(!resolvedProfile.hidden && !resolvedProfile.concealed) { "Selected server is hidden" }
                    // A balancer's members are resolved against the store, not the screen's
                    // list — the screen hides the members, the store keeps them: linkKeys from
                    // the column, rows from the subscription. A balancer whose members have all
                    // fallen away degrades to the whole subscription — still a pick, just a
                    // wider one.
                    val members = if (resolvedProfile.type == ProfileType.BALANCER) {
                        val keys = resolvedProfile.balancerMemberKeys.toSet()
                        val rows = repository
                            .profilesOf(resolvedProfile.subscriptionId)
                            .first()
                        val resolved = rows.filter {
                            it.type != ProfileType.BALANCER && it.linkKey in keys
                        }
                        resolved.ifEmpty { rows.filter { it.type != ProfileType.BALANCER } }
                    } else {
                        emptyList()
                    }
                    val config = repository.prepareGeodata(resolvedProfile, XrayConfigBuilder.buildFull(
                        resolvedProfile,
                        requestedConnection.routingMode,
                        members,
                        requestedConnection.delayTestUrl,
                    ), requestedConnection.routingMode == RoutingMode.GLOBAL && resolvedProfile.routingRules.isNotBlank())
                    val subscriptionName = resolvedProfile.subscriptionId?.let { repository.subscriptionById(it)?.name }
                    currentCoroutineContext().ensureActive()
                    val requestContext = currentCoroutineContext()
                    synchronized(CoreConfigFile) {
                        requestContext.ensureActive()
                        if (generation == connectGeneration.get()) {
                            CoreConfigFile.write(app, config,
                                resolvedProfile.remarks.ifEmpty { if (settings.value.maskAddresses) "" else resolvedProfile.server },
                                subscriptionName, resolvedProfile.id, resolvedProfile.subscriptionId, resolvedProfile.server)
                            tunnelClient.connect()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (generation == connectGeneration.get()) _servers.update { it.copy(error = e.message ?: app.getString(R.string.error_unknown)) }
                }
            }
        }
    }

    /**
     * Selects a server: persists the choice and, if the tunnel is up, reloads the running
     * core with the new profile in place — the interface stays, the session does not drop.
     */
    fun selectProfile(id: String) {
        if (_servers.value.selectedProfileId == id) return
        ConnectionStore.update(app) { it.copy(selectedProfileId = id) }
        // The pick is a use: the "last used" order remembers which hand the user played.
        viewModelScope.launch { repository.setProfileLastUsed(id, System.currentTimeMillis()) }
        if (tunnelStatus.value.state in setOf(TunnelState.UP, TunnelState.CONNECTING)) {
            applyConfigAndConnect(_servers.value.profiles.firstOrNull { it.id == id })
        }
    }

    /**
     * Picks the servers list's own order — the header's chip. The choice outlives the
     * app: it rides the connection settings, and the list re-sorts itself off the same
     * flow that carries them.
     */
    fun setServersSort(sort: ServersSort) {
        ConnectionStore.update(app) { it.copy(serversSort = sort) }
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
        val firstConfigEver = appIsEmpty()
        viewModelScope.launch {
            repository.addSubscription(trimmed, name)
                .onSuccess { sub ->
                    ConnectionStore.update(app) { it.copy(selectedSubscriptionId = sub.id) }
                    _servers.update { it.copy(error = null) }
                    maybeAskNotifications(firstConfigEver)
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
            configurationMutex.withLock {
                invalidateConfiguration(subscriptionId = id)
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
    }

    /**
     * Deletes one loose config — the row's own long press asks for it. The pick clears
     * with the row if it pointed there; the rest of the group stands.
     */
    fun deleteManualProfile(id: String) {
        viewModelScope.launch {
            configurationMutex.withLock {
                invalidateConfiguration(profileId = id)
                repository.deleteManualProfile(id)
                if (connection.value.selectedProfileId == id) {
                    ConnectionStore.update(app) { it.copy(selectedProfileId = null) }
                }
            }
        }
    }

    private suspend fun invalidateConfiguration(profileId: String? = null, subscriptionId: String? = null) {
        withContext(Dispatchers.IO) {
            synchronized(CoreConfigFile) {
                val snapshot = CoreConfigFile.snapshot(app)
                val selectionMatches = profileId != null && connection.value.selectedProfileId == profileId ||
                    subscriptionId != null && connection.value.selectedSubscriptionId == subscriptionId
                if (selectionMatches) {
                    connectGeneration.incrementAndGet()
                    connectJob?.cancel()
                }
                val matches = snapshot != null && (
                    profileId != null && (snapshot.profileId == profileId ||
                        snapshot.profileId == null && connection.value.selectedProfileId == profileId) ||
                    subscriptionId != null && (snapshot.subscriptionId == subscriptionId ||
                        subscriptionId == STANDALONE_SUBSCRIPTION_ID && snapshot.subscriptionId == null ||
                        snapshot.profileId == null && connection.value.selectedSubscriptionId == subscriptionId))
                if (matches) {
                    connectGeneration.incrementAndGet()
                    connectJob?.cancel()
                    CoreConfigFile.clear(app)
                    tunnelClient.disconnect()
                }
            }
        }
    }

    /** The pin's own switch, on whatever row the menu stands — the list re-sorts live. */
    fun toggleProfilePinned(id: String, pinned: Boolean) {
        viewModelScope.launch { repository.setProfilePinned(id, pinned) }
    }

    /** The hide's own switch — the row leaves the list for the header's chip to count. */
    fun toggleProfileConcealed(id: String, concealed: Boolean) {
        viewModelScope.launch { repository.setProfileConcealed(id, concealed) }
    }

    /** The subscription pin's own switch — the dropdown re-sorts live. */
    fun toggleSubscriptionPinned(id: String, pinned: Boolean) {
        viewModelScope.launch { repository.setSubscriptionPinned(id, pinned) }
    }

    /** Switches the servers screen to another subscription. */
    fun selectSubscription(id: String) {
        if (selectedSubscriptionId.value == id) return
        sweepGeneration++
        sweepJob?.cancel()
        ConnectionStore.update(app) { it.copy(selectedSubscriptionId = id, selectedProfileId = null) }
        _servers.update { ServersUiState() }
    }

    /** The per-subscription auto-update toggle. */
    fun setSubscriptionAutoUpdate(id: String, enabled: Boolean) {
        viewModelScope.launch { repository.setSubscriptionAutoUpdate(id, enabled) }
    }

    /** Manual import: a link, a WireGuard .conf body, a raw config, or a list of links. */
    fun addManualProfiles(text: String, onDone: (String?) -> Unit) {
        val firstConfigEver = appIsEmpty()
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
                        maybeAskNotifications(firstConfigEver)
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
    fun pingServers() = startServerSweep(false)

    private fun startServerSweep(onlyUntested: Boolean) {
        if (sweepJob?.isActive == true) return
        val profiles = _servers.value.profiles.filter {
            it.type != ProfileType.BALANCER && (!onlyUntested || it.lastTestAt == null)
        }
        if (profiles.isEmpty()) return
        val generation = ++sweepGeneration
        _servers.update { it.copy(isPinging = true) }
        sweepJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val testUrl = connection.value.delayTestUrl
                coroutineScope {
                    val results = Channel<Pair<String, Long?>>(Channel.UNLIMITED)
                    val writer = launch {
                        while (true) {
                            val first = results.receiveCatching().getOrNull() ?: break
                            val batch = mutableListOf(first)
                            delay(150)
                            while (true) batch.add(results.tryReceive().getOrNull() ?: break)
                            repository.setProfileDelays(batch, System.currentTimeMillis())
                        }
                    }
                    try {
                        val next = java.util.concurrent.atomic.AtomicInteger()
                        List(pingWorkerCount(profiles.size)) {
                            launch {
                                while (currentCoroutineContext().isActive) {
                                    val profile = profiles.getOrNull(next.getAndIncrement()) ?: break
                                    pingSemaphore.withPermit {
                                        currentCoroutineContext().ensureActive()
                                        val measured = try {
                                            tunnelClient.measureDelay(XrayConfigBuilder.buildTest(profile), testUrl)
                                        } catch (e: CancellationException) { throw e }
                                        catch (_: Exception) { null }
                                        currentCoroutineContext().ensureActive()
                                        results.send(profile.id to measured)
                                    }
                                }
                            }
                        }.joinAll()
                    } finally { results.close() }
                    writer.join()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == sweepGeneration) _servers.update { it.copy(error = e.message ?: app.getString(R.string.error_unknown)) }
            } finally {
                if (generation == sweepGeneration) _servers.update { it.copy(isPinging = false) }
            }
        }
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
                            val active = withContext(Dispatchers.IO) {
                                val snapshot = CoreConfigFile.snapshot(app)
                                val profile = snapshot?.profileId?.let { repository.profileById(it) }
                                    ?: selectedProfile.value
                                profile?.let { ActiveIdentity(it, it.subscriptionId?.let { id -> repository.subscriptionById(id) }) }
                            }
                            if (tunnelStatus.value.state != TunnelState.UP) return@collect
                            activeIdentity.value = active
                            _home.update {
                                it.copy(uptimeStartMillis = tunnelClient.connectedAtMillis() ?: System.currentTimeMillis())
                            }
                            startPingLoop()
                        }

                        else -> {
                            if (status.state == TunnelState.DOWN) activeIdentity.value = null
                            pingJob?.cancel()
                            _home.update {
                                it.copy(pingMillis = null, uptimeStartMillis = null, recentLossPercent = null)
                            }
                        }
                    }
                }
        }
    }


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
                currentCoroutineContext().ensureActive()
                if (tunnelStatus.value.state == TunnelState.UP) {
                    _home.update { it.copy(pingMillis = delay?.takeIf { value -> value >= 0 }) }
                    recordProbe(now, lost = delay == null || delay < 0)
                }
                pacedDelay(
                    if (appInForeground) PING_INTERVAL_MILLIS else BACKGROUND_PROBE_MILLIS,
                )
            }
        }
    }

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
            var lastAtMillis = System.currentTimeMillis()
            var sessionRx = 0L
            var sessionTx = 0L
            var bucketStart = 0L
            var bucketMillis = 0L
            var bucketDownBytes = 0L
            var bucketUpBytes = 0L
            try {
                kotlinx.coroutines.delay(STATS_START_DELAY_MILLIS)
                while (isActive && tunnelStatus.value.state == TunnelState.UP) {
                    val traffic = tunnelClient.trafficBytes()
                    val now = System.currentTimeMillis()
                    if (traffic != null) {
                        val down = traffic[0]
                        val up = traffic[1]
                        val elapsed = (traffic.getOrNull(2) ?: (now - lastAtMillis)).coerceAtLeast(1L)
                        sessionRx += down
                        sessionTx += up
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
                            refreshDailyTraffic()
                            lastFlushAtMillis = now
                        }
                    }
                    if (traffic != null) lastAtMillis = now
                    pacedDelay(
                        if (appInForeground) TRAFFIC_SAMPLE_MILLIS else BACKGROUND_SAMPLE_MILLIS,
                    )
                }
            } finally {
                refreshDailyTraffic()
            }
        }
    }

    private fun refreshDailyTraffic() {
        val week = DailyTrafficStore.readWeek(app)
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
        val startedInForeground = appInForeground
        while (remaining > 0 && currentCoroutineContext().isActive) {
            kotlinx.coroutines.delay(minOf(1_000L, remaining))
            remaining -= 1_000L
            if (!startedInForeground && appInForeground) return
        }
    }

    /**
     * The stats screen's live history, kept in memory only. The week is seeded from disk;
     * the graphs themselves start empty.
     */

    private companion object {
        const val MAX_PING_CONCURRENCY = 10
        const val PING_SERVERS_PER_WORKER = 10

        fun pingWorkerCount(profileCount: Int): Int {
            if (profileCount == 0) return 0
            val workers = (profileCount - 1) / PING_SERVERS_PER_WORKER + 1
            return minOf(workers, MAX_PING_CONCURRENCY)
        }

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
