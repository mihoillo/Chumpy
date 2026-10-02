package slop.miho.aurora.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wireguard.config.BadConfigException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import slop.miho.aurora.AuroraApp
import slop.miho.aurora.R
import slop.miho.aurora.core.TunnelClient
import slop.miho.aurora.core.TunnelState
import slop.miho.aurora.core.TunnelStatus
import slop.miho.aurora.data.BackendApi
import slop.miho.aurora.data.BackendAutoExit
import slop.miho.aurora.data.BackendDevice
import slop.miho.aurora.data.BackendExit
import slop.miho.aurora.data.BackendCache
import slop.miho.aurora.data.BackendSession
import slop.miho.aurora.data.BackendSnapshot
import slop.miho.aurora.data.DailyTraffic
import slop.miho.aurora.data.DailyTrafficStore
import slop.miho.aurora.data.DevicesCache
import slop.miho.aurora.data.ProfileStore
import slop.miho.aurora.data.ServersCache
import slop.miho.aurora.data.SettingsStore
import slop.miho.aurora.data.UiSettings
import java.io.ByteArrayOutputStream
import java.io.InputStream

data class Profile(val name: String?)

/** Home screen extras: everything besides the tunnel status itself. */
data class HomeUiState(
    val deviceName: String? = null,
    val localIp: String? = null,
    val exitLabel: String? = null,
    /** Round-trip to the panel, refreshed every 15 s while connected. */
    val pingMillis: Long? = null,
    /** Wall-clock millis of the last transition to UP; null while down. */
    val uptimeStartMillis: Long? = null,
    /** The recent loss, but only once it crosses the warning threshold — null means the
     *  tunnel is healthy (or nothing has been measured yet), and the chip stays hidden. */
    val recentLossPercent: Float? = null,
)

data class ServersUiState(
    val exits: List<BackendExit> = emptyList(),
    val auto: BackendAutoExit? = null,
    val currentExitId: String? = null,
    val actualExitId: String? = null,
    val exitIp: String? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isSelecting: Boolean = false,
    val error: String? = null,
)

data class DevicesUiState(
    val devices: List<BackendDevice> = emptyList(),
    val meId: Int? = null,
    val subnetName: String? = null,
    val subnetCidr: String? = null,
    /** Whether the panel lets this account create devices — live fetches only, never cached. */
    val trusted: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

/** One point of the traffic graph: a 15-second bucket's average rates, with the WireGuard
 *  counters as of its end. The averages keep the points rare while the sampling underneath
 *  stays once a second — the last bucket keeps updating live until it settles. */
data class TrafficSample(
    val atMillis: Long,
    val rxTotal: Long,
    val txTotal: Long,
    val downBps: Long,
    val upBps: Long,
)

/** One 15-second window of loss probes: how many were sent, how many never completed. */
data class LossBucket(val atMillis: Long, val sent: Int, val lost: Int)

/** The stats screen's live history, kept in memory only: the counters are sampled once a
 *  second and folded into 15-second buckets, the probes run every five and land in the same
 *  buckets, and both trim to the graph window. Disconnecting freezes the history in place
 *  instead of clearing — the graphs stay on screen, and [frozenAtMillis] records when the
 *  data stopped moving. */
data class StatsUiState(
    val samples: List<TrafficSample> = emptyList(),
    val buckets: List<LossBucket> = emptyList(),
    /** The latest WireGuard counters — the frozen connection's total transfer. */
    val rxTotal: Long = 0,
    val txTotal: Long = 0,
    /** When the graphs froze — the moment the tunnel went down; null while connected. */
    val frozenAtMillis: Long? = null,
    /** How long the frozen connection lasted; null until a connection ends. */
    val connectedMillis: Long? = null,
    /** The recent loss, but only once it crosses the warning threshold — the red chip's
     *  trigger on both this screen and home. Null means no warning. */
    val recentLossPercent: Float? = null,
    /** The week's daily totals for the seven-bar chart, oldest first, persisted between runs. */
    val week: List<DailyTraffic> = emptyList(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<Application>()
    // The app process owns the one tunnel link and binds it for its whole lifetime; the view
    // model only drives it.
    private val tunnelClient = AuroraApp.tunnelClient
    private val backendApi = BackendApi()
    private val refreshMutex = Mutex()
    // Separate lock so a slow devices fetch never holds the servers refresh (and vice versa).
    private val devicesMutex = Mutex()

    private val _profile = MutableStateFlow(
        if (ProfileStore.hasProfile(application)) Profile(null) else null,
    )
    val profile: StateFlow<Profile?> = _profile.asStateFlow()

    private val _servers = MutableStateFlow(
        BackendCache.readServers(application)?.let(::serversUiState) ?: ServersUiState(),
    )
    val servers: StateFlow<ServersUiState> = _servers.asStateFlow()

    private val _home = MutableStateFlow(
        BackendCache.read(application)?.let(::homeUiState) ?: HomeUiState(),
    )
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    /** The subnet's peers, seeded from the cache (online status never survives a disk round
     *  trip — read back, every device starts offline) and refreshed live while connected. */
    private val _devices = MutableStateFlow(
        BackendCache.readDevices(application)?.let(::devicesUiState) ?: DevicesUiState(),
    )
    val devices: StateFlow<DevicesUiState> = _devices.asStateFlow()

    /**
     * The stats screen's live history, kept in memory only. Declared before the init block:
     * its collector starts on Dispatchers.Main.immediate and emits synchronously from inside
     * the constructor, so it must already exist when observeTunnelForStats() runs. The week
     * is seeded from disk; the graphs themselves start empty.
     */
    private val _stats = MutableStateFlow(StatsUiState(week = DailyTrafficStore.readWeek(app)))
    val stats: StateFlow<StatsUiState> = _stats.asStateFlow()

    private var trafficJob: kotlinx.coroutines.Job? = null
    private var probeJob: kotlinx.coroutines.Job? = null

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
     * The stats session's bookkeeping, same initialization-order rule as the flows above:
     * the collector writes [sessionStartMillis] synchronously from the constructor, so the
     * field must exist before the init block runs.
     */
    private var sessionStartMillis = 0L

    /** The day-bucket baselines: what has already been flushed out of the live counters. */
    private var lastFlushedRx = 0L
    private var lastFlushedTx = 0L
    private var lastFlushAtMillis = 0L

    /** UI preferences; every change is persisted immediately. Shared with the fonts screen's
     *  activity, so its picks reach this flow too. */
    val settings: StateFlow<UiSettings> = SettingsStore.settings(application)

    fun updateSettings(transform: (UiSettings) -> UiSettings) {
        SettingsStore.update(app, transform)
    }

    val tunnelStatus: StateFlow<TunnelStatus> = tunnelClient.status

    init {
        _profile.value = BackendCache.read(app)?.let { cached -> Profile(cached.profileName) } ?: _profile.value
        observeTunnelForProfile()
        observeTunnelForHome()
        observeTunnelForDevices()
        observeTunnelForStats()
    }

    /** Returns null on success, or a user-facing error message. */
    suspend fun importConfig(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val resolver = app.contentResolver
            val text = resolver.openInputStream(uri)?.use(::readLimited)?.decodeToString()
                ?: return@withContext app.getString(R.string.welcome_invalid_config)
            ProfileStore.save(app, text)
            // A different peer may answer after the next connection, so drop the old identity.
            BackendCache.clear(app)
            _profile.value = Profile(null)
            null
        } catch (_: BadConfigException) {
            app.getString(R.string.welcome_invalid_config)
        } catch (e: Exception) {
            e.message ?: app.getString(R.string.error_unknown)
        }
    }

    fun connect() = tunnelClient.connect()

    fun disconnect() = tunnelClient.disconnect()

    /** Refresh only after the isolated tunnel is up, since the API lives on 10.0.0.0/16. */
    private fun observeTunnelForProfile() {
        viewModelScope.launch {
            tunnelStatus
                .distinctUntilChanged { old, new -> old.state == new.state }
                .collect { status ->
                    if (status.state == TunnelState.UP && _profile.value != null) {
                        refreshServers()
                    }
                }
        }
    }

    /** Uptime and the 15-second ping loop live here; the identity fields come from the cache. */
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

    private var pingJob: kotlinx.coroutines.Job? = null

    /**
     * Measures the ping right now and restarts the 15-second loop from this moment: Home
     * asks for it on coming into view, and on a tap of the ping chip. [showPending] blanks
     * the old value first, so a tap visibly answers with "…" until the new one lands.
     */
    fun refreshPing(showPending: Boolean = false) {
        if (tunnelStatus.value.state != TunnelState.UP) return
        if (showPending) _home.update { it.copy(pingMillis = null) }
        startPingLoop()
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive && tunnelStatus.value.state == TunnelState.UP) {
                val ping = runCatching { backendApi.measurePingMillis() }.getOrNull()
                if (tunnelStatus.value.state == TunnelState.UP) {
                    _home.update { it.copy(pingMillis = ping) }
                }
                kotlinx.coroutines.delay(15_000)
            }
        }
    }

    private fun homeUiState(snapshot: BackendSnapshot) = HomeUiState(
        deviceName = snapshot.deviceName,
        localIp = snapshot.localIp,
        exitLabel = snapshot.currentExitLabel,
    )

    /**
     * Online is a moment's truth, and the only way to know it is through the tunnel: the
     * moment the tunnel drops, every device falls offline in the UI — the last live fetch's
     * flags would be stale fiction, presented as current.
     */
    private fun observeTunnelForDevices() {
        viewModelScope.launch {
            tunnelStatus
                .distinctUntilChanged { old, new -> old.state == new.state }
                .collect { status ->
                    if (status.state != TunnelState.UP && _devices.value.devices.any { it.online }) {
                        _devices.update { state ->
                            state.copy(devices = state.devices.map { it.copy(online = false) })
                        }
                    }
                }
        }
    }

    /**
     * The stats graphs' two feeds run only while the tunnel is up: the traffic counters are
     * sampled every second (a local binder call, no network) and the panel is probed every
     * five (a bare TCP handshake — WireGuard exposes no loss counter of its own). Both start
     * with the tunnel, so the history has already filled by the time the screen is opened.
     * Coming down does not clear anything: the graphs freeze where they stand, the freeze's
     * timestamp and the connection's length are recorded for the screen's chips, and a new
     * connection simply keeps appending — the trimming window sorts the old session out.
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
                            startProbeLoop()
                        }
                        else -> {
                            trafficJob?.cancel()
                            probeJob?.cancel()
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
     * The traffic feed: samples WireGuard's counters once a second and folds the rates into
     * 15-second buckets — the graph's points are the averages, so they stay rare while the
     * sampling underneath keeps its cadence, and the in-progress bucket keeps updating live
     * until it settles. The day's bucket flushes at most once a minute; the exit flush in the
     * finally block catches the session's tail — cancellation runs it too.
     */
    private fun startTrafficLoop() {
        trafficJob?.cancel()
        trafficJob = viewModelScope.launch(Dispatchers.IO) {
            // The previous sample the rates are differenced against; 0 until the first read.
            var lastAtMillis = 0L
            var lastRx = 0L
            var lastTx = 0L
            // The bucket being averaged into: its start wall-clock, its sample count and the
            // rate sums over it. At most one live point per bucket exists in the state.
            var bucketStart = 0L
            var bucketCount = 0
            var bucketDownSum = 0L
            var bucketUpSum = 0L
            try {
                // The tunnel's first seconds are a handshake storm; let it settle before the
                // stats begin, or the graphs open on a lie.
                kotlinx.coroutines.delay(STATS_START_DELAY_MILLIS)
                while (isActive && tunnelStatus.value.state == TunnelState.UP) {
                    val traffic = tunnelClient.trafficBytes()
                    val now = System.currentTimeMillis()
                    if (traffic != null) {
                        val (rx, tx) = traffic[0] to traffic[1]
                        if (lastAtMillis != 0L && rx >= lastRx && tx >= lastTx) {
                            val seconds = (now - lastAtMillis).coerceAtLeast(1L) / 1000.0
                            val downBps = ((rx - lastRx) / seconds).toLong()
                            val upBps = ((tx - lastTx) / seconds).toLong()
                            val currentBucket = (now / TRAFFIC_BUCKET_MILLIS) * TRAFFIC_BUCKET_MILLIS
                            if (currentBucket != bucketStart) {
                                bucketStart = currentBucket
                                bucketCount = 0
                                bucketDownSum = 0L
                                bucketUpSum = 0L
                            }
                            bucketCount++
                            bucketDownSum += downBps
                            bucketUpSum += upBps
                            val bucketDown = bucketDownSum / bucketCount
                            val bucketUp = bucketUpSum / bucketCount
                            _stats.update { state ->
                                val samples = state.samples.toMutableList()
                                val index = samples.indexOfLast { it.atMillis == bucketStart }
                                val point = TrafficSample(
                                    atMillis = bucketStart,
                                    rxTotal = rx,
                                    txTotal = tx,
                                    downBps = bucketDown,
                                    upBps = bucketUp,
                                )
                                if (index >= 0) samples[index] = point else samples.add(point)
                                state.copy(
                                    samples = trimWindow(samples),
                                    rxTotal = rx,
                                    txTotal = tx,
                                )
                            }
                        } else if (rx < lastRx || tx < lastTx) {
                            // The counters went backwards: the tunnel reconnected under us and
                            // the counters restarted. Re-baseline without a point — the gap
                            // would otherwise read as a giant spike or a negative rate.
                        }
                        lastAtMillis = now
                        lastRx = rx
                        lastTx = tx
                        // The day's bucket flushes at most once a minute; the finally block
                        // below catches whatever the session moved after the last one.
                        if (now - lastFlushAtMillis >= DAILY_FLUSH_MILLIS) {
                            flushDailyTraffic(rx - lastFlushedRx, tx - lastFlushedTx)
                            lastFlushedRx = rx
                            lastFlushedTx = tx
                            lastFlushAtMillis = now
                        }
                    }
                    pacedDelay(
                        if (appInForeground) TRAFFIC_SAMPLE_MILLIS else BACKGROUND_SAMPLE_MILLIS,
                    )
                }
            } finally {
                // Cancellation runs this too: the loop's own thread, no races with the
                // collector. The counters may already read as zeros by now, so the flush
                // works off the last values the loop saw — never a fresh read.
                if (lastAtMillis != 0L) {
                    flushDailyTraffic(lastRx - lastFlushedRx, lastTx - lastFlushedTx)
                }
            }
        }
    }

    private fun flushDailyTraffic(rx: Long, tx: Long) {
        if (rx <= 0 && tx <= 0) return
        val week = DailyTrafficStore.addToday(app, rx.coerceAtLeast(0), tx.coerceAtLeast(0))
        _stats.update { it.copy(week = week) }
    }

    private fun startProbeLoop() {
        probeJob?.cancel()
        probeJob = viewModelScope.launch(Dispatchers.IO) {
            // The same settling delay: the first seconds' losses are the handshake's, not
            // the network's.
            kotlinx.coroutines.delay(STATS_START_DELAY_MILLIS)
            while (isActive && tunnelStatus.value.state == TunnelState.UP) {
                val now = System.currentTimeMillis()
                val lost = !backendApi.probeConnectivity()
                if (tunnelStatus.value.state == TunnelState.UP) {
                    val bucketStart = (now / PROBE_BUCKET_MILLIS) * PROBE_BUCKET_MILLIS
                    _stats.update { state ->
                        val buckets = state.buckets.toMutableList()
                        val index = buckets.indexOfLast { it.atMillis == bucketStart }
                        if (index >= 0) {
                            val bucket = buckets[index]
                            buckets[index] = bucket.copy(
                                sent = bucket.sent + 1,
                                lost = bucket.lost + if (lost) 1 else 0,
                            )
                        } else {
                            buckets.add(LossBucket(bucketStart, 1, if (lost) 1 else 0))
                        }
                        // Every probe re-weighs the last two minutes: the warning chips on
                        // the stats screen and home answer to this, not to the whole window.
                        val warning = recentLoss(buckets)
                        _home.update { it.copy(recentLossPercent = warning) }
                        state.copy(
                            buckets = trimLossBuckets(buckets),
                            recentLossPercent = warning,
                        )
                    }
                }
                pacedDelay(
                    if (appInForeground) PROBE_INTERVAL_MILLIS else BACKGROUND_PROBE_MILLIS,
                )
            }
        }
    }

    /** The loss over the last [RECENT_LOSS_WINDOW_MILLIS] — null while nothing was probed,
     *  and only set on the states once it crosses the warning threshold. */
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

    private fun devicesUiState(cache: DevicesCache) = DevicesUiState(
        devices = cache.devices,
        meId = cache.meId,
        subnetName = cache.subnetName,
        subnetCidr = cache.subnetCidr,
    )

    /** @param silent background refresh: no loading/refreshing state, the data just updates. */
    fun refreshServers(silent: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            refreshServersLocked(silent)
        }
    }

    private suspend fun refreshServersLocked(silent: Boolean = false) = refreshMutex.withLock {
        val hadData = _servers.value.exits.isNotEmpty() || _servers.value.auto != null
        if (!silent) {
            _servers.update { it.copy(isLoading = !hadData && it.error == null, isRefreshing = true, error = null) }
        }
        runCatching {
            val session = backendApi.fetchSession()
            val snapshot = backendApi.fetchHomeSnapshot()
            val cache = buildCache(session, snapshot)

            BackendCache.write(app, snapshot)
            BackendCache.writeServers(app, cache)
            _profile.value = Profile(snapshot.profileName)
            _home.update { state ->
                state.copy(
                    deviceName = snapshot.deviceName ?: state.deviceName,
                    localIp = snapshot.localIp ?: state.localIp,
                    exitLabel = snapshot.currentExitLabel ?: state.exitLabel,
                )
            }
            _servers.value = serversUiState(cache)
        }.onSuccess {
            _servers.update { it.copy(isLoading = false, isRefreshing = false, error = null) }
            val cache = BackendCache.readServers(app)
            refreshExitIpAsync(
                expectedCurrentExitId = cache?.currentExitId,
                expectedActualExitId = cache?.actualExitId,
            )
        }.onFailure { error ->
            _servers.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    error = error.message ?: app.getString(R.string.error_unknown),
                )
            }
        }
    }

    /** @param silent background refresh: no loading/refreshing state, the data just updates. */
    fun refreshDevices(silent: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            refreshDevicesLocked(silent)
        }
    }

    private suspend fun refreshDevicesLocked(silent: Boolean = false) = devicesMutex.withLock {
        val hadData = _devices.value.devices.isNotEmpty()
        if (!silent) {
            _devices.update { it.copy(isLoading = !hadData && it.error == null, isRefreshing = true, error = null) }
        }
        runCatching {
            backendApi.fetchDevices()
        }.onSuccess { fetched ->
            // Everything but the online status outlives the run; the online flags stay in
            // the in-memory state only.
            BackendCache.writeDevices(
                app,
                DevicesCache(
                    devices = fetched.devices,
                    meId = fetched.meId,
                    subnetName = fetched.subnetName,
                    subnetCidr = fetched.subnetCidr,
                    fetchedAtMillis = System.currentTimeMillis(),
                ),
            )
            _devices.value = DevicesUiState(
                devices = fetched.devices,
                meId = fetched.meId,
                subnetName = fetched.subnetName,
                subnetCidr = fetched.subnetCidr,
                trusted = fetched.trusted,
            )
        }.onFailure { error ->
            _devices.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    error = error.message ?: app.getString(R.string.error_unknown),
                )
            }
        }
    }

    /**
     * The peer's full config, fetched on demand for the config dialog — through the tunnel,
     * in memory only. Returns null on failure; the config itself never logs or caches.
     */
    suspend fun deviceConfig(deviceId: Int): String? = withContext(Dispatchers.IO) {
        runCatching { backendApi.fetchDeviceConfig(deviceId) }.getOrNull()
    }

    /**
     * Device actions — rename, toggle, delete. Each performs the panel call and then a silent
     * refresh so the list reflects the panel's truth rather than a local guess. A failure
     * surfaces on the devices screen's error slot, the same one a failed refresh uses.
     */
    /** Creates a new peer; the panel itself decides whether this account may. */
    fun createDevice(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runDeviceAction { backendApi.createDevice(name) }
        }
    }

    fun renameDevice(deviceId: Int, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runDeviceAction { backendApi.renameDevice(deviceId, name) }
        }
    }

    fun toggleDevice(deviceId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            runDeviceAction { backendApi.toggleDevice(deviceId) }
        }
    }

    fun deleteDevice(deviceId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            runDeviceAction { backendApi.deleteDevice(deviceId) }
        }
    }

    private suspend fun runDeviceAction(action: suspend () -> Unit) {
        runCatching { action() }
            .onFailure { error ->
                _devices.update {
                    it.copy(error = error.message ?: app.getString(R.string.error_unknown))
                }
            }
            .onSuccess {
                refreshDevicesLocked(silent = true)
            }
    }

    fun selectServer(exitId: String) {
        if (_servers.value.isSelecting) return
        viewModelScope.launch(Dispatchers.IO) {
            _servers.update { it.copy(isSelecting = true, error = null) }
            runCatching {
                backendApi.selectExit(exitId)
                val session = backendApi.fetchSession()
                val snapshot = backendApi.fetchHomeSnapshot()
                val cache = buildCache(session, snapshot)
                BackendCache.write(app, snapshot)
                BackendCache.writeServers(app, cache)
                _profile.value = Profile(snapshot.profileName)
                _home.update { state ->
                    state.copy(
                        deviceName = snapshot.deviceName ?: state.deviceName,
                        localIp = snapshot.localIp ?: state.localIp,
                        exitLabel = snapshot.currentExitLabel ?: state.exitLabel,
                    )
                }
                _servers.value = serversUiState(cache)
            }.onFailure { error ->
                _servers.update {
                    it.copy(
                        isSelecting = false,
                        error = error.message ?: app.getString(R.string.error_unknown),
                    )
                }
                return@launch
            }
            _servers.update { it.copy(isSelecting = false) }
            val cache = BackendCache.readServers(app)
            refreshExitIpAsync(
                expectedCurrentExitId = cache?.currentExitId,
                expectedActualExitId = cache?.actualExitId,
            )
        }
    }

    private fun buildCache(
        session: BackendSession,
        snapshot: BackendSnapshot,
    ): ServersCache = ServersCache(
        exits = session.exits,
        auto = session.autoExit,
        currentExitId = snapshot.currentExitId,
        actualExitId = snapshot.actualExitId,
        exitIp = null,
        fetchedAtMillis = snapshot.fetchedAtMillis,
    )

    private fun serversUiState(cache: ServersCache) = ServersUiState(
        exits = cache.exits,
        auto = cache.auto,
        currentExitId = cache.currentExitId,
        actualExitId = cache.actualExitId,
        exitIp = cache.exitIp,
    )

    /**
     * Exit IP is slow, so fetch it after the fast list is already on screen. Drop stale results
     * if another exit was selected while the request was in flight.
     */
    private fun refreshExitIpAsync(expectedCurrentExitId: String?, expectedActualExitId: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val exitIp = runCatching { backendApi.fetchExitIp() }.getOrNull() ?: return@launch
            val current = _servers.value
            if (current.currentExitId != expectedCurrentExitId ||
                current.actualExitId != expectedActualExitId
            ) {
                return@launch
            }

            _servers.update { it.copy(exitIp = exitIp) }
            BackendCache.readServers(app)
                ?.takeIf { cache ->
                    cache.currentExitId == expectedCurrentExitId &&
                        cache.actualExitId == expectedActualExitId
                }
                ?.let { cache -> BackendCache.writeServers(app, cache.copy(exitIp = exitIp)) }
        }
    }

    private companion object {
        const val MAX_CONFIG_BYTES = 64 * 1024

        /** The graph window: history older than this falls off the left edge. */
        const val STATS_WINDOW_MILLIS = 15 * 60_000L

        const val TRAFFIC_SAMPLE_MILLIS = 1_000L
        const val PROBE_INTERVAL_MILLIS = 5_000L

        /** The graphs' point granularity: rates and probes fold into 15-second buckets. */
        const val TRAFFIC_BUCKET_MILLIS = 15_000L
        const val PROBE_BUCKET_MILLIS = 15_000L

        /** How long the loops wait after the tunnel comes up before collecting anything. */
        const val STATS_START_DELAY_MILLIS = 3_000L

        /** The loops' background stride: the app gone from screen, the history keeps
         *  trickling in — rare enough to be cheap, often enough to stay warm. */
        const val BACKGROUND_SAMPLE_MILLIS = 30_000L
        const val BACKGROUND_PROBE_MILLIS = 30_000L

        /** The warning window and threshold: loss over the last two minutes above 5% trips
         *  the red chips on the stats screen and home. */
        const val RECENT_LOSS_WINDOW_MILLIS = 2 * 60_000L
        const val LOSS_WARNING_THRESHOLD = 5f

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

        /** A WireGuard config is a few hundred bytes; don't slurp arbitrary files. */
        fun readLimited(stream: InputStream): ByteArray? {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) return out.toByteArray()
                if (out.size() + read > MAX_CONFIG_BYTES) return null
                out.write(buffer, 0, read)
            }
        }
    }
}
