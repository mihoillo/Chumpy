package slop.miho.adhdium.data

import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import org.json.JSONObject

/** One selectable exit from session.exits. */
data class BackendExit(
    val id: String,
    val label: String,
    val hint: String?,
    val xray: Boolean,
    val available: Boolean,
    val pingMillis: Int?,
    /**
     * The exit's ISO 3166-1 alpha-2 country from geo.country ("FI"), upper case; null when
     * the panel has no geo for it (broken exits come back with geo: null).
     */
    val country: String? = null,
)

/** A geo.country value the flag can be built from: exactly two ASCII letters, upper-cased. */
fun normalizeCountryCode(raw: String?): String? =
    raw?.trim()?.uppercase()?.takeIf { code -> code.length == 2 && code.all { it in 'A'..'Z' } }

/** session.auto, kept separately from normal exits because it has no ping. */
data class BackendAutoExit(
    val label: String,
    val hint: String?,
    val via: String?,
)

/** Values used by the UI. The full server response is intentionally never kept. */
data class BackendSnapshot(
    val profileName: String?,
    val deviceName: String?,
    val localIp: String?,
    val currentExitId: String?,
    val currentExitLabel: String?,
    val actualExitId: String?,
    val fetchedAtMillis: Long,
)

/** Values from /api/session used by the servers screen. */
data class BackendSession(
    val csrf: String,
    val exits: List<BackendExit>,
    val autoExit: BackendAutoExit?,
)

/**
 * One peer of the user's subnet, from /api/devices. The config's contents are deliberately
 * not kept here — they are fetched on demand for the config dialog and never persisted —
 * only the filename the panel suggests for its download.
 */
data class BackendDevice(
    val id: Int,
    val name: String,
    val addr: String,
    val online: Boolean,
    val enabled: Boolean,
    val confFilename: String?,
    val exitLabel: String?,
    val expires: String?,
    val monthRx: String?,
    val monthTx: String?,
)

/** Values from /api/devices used by the devices screen. */
data class BackendDevices(
    val devices: List<BackendDevice>,
    /** The id of the peer this app's config belongs to, to mark "this device". */
    val meId: Int?,
    val subnetName: String?,
    val subnetCidr: String?,
    /**
     * Whether this account may create new peers — the panel's own "trusted" answer, re-asked
     * with every fetch. Deliberately not cached: it's a permission, and the button it gates
     * needs the tunnel up anyway, which always comes with a live fetch.
     */
    val trusted: Boolean,
)

/**
 * Minimal reader for the panel API. /api/home contains a full WireGuard config with a private
 * key, so responses are parsed once and only the fields the UI needs are returned.
 */
class BackendApi(private val baseUrl: String = DEFAULT_BASE_URL) {
    // The panel binds CSRF tokens to the session cookie issued with /api/session.
    // Cookies live in memory only; never persist them or log responses.
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private val homeUrl = URL("$baseUrl/api/home")
    private val sessionUrl = URL("$baseUrl/api/session")
    private val exitUrl = URL("$baseUrl/api/exit")
    private val pingUrl = URL("$baseUrl/api/ping")
    private val exitIpUrl = URL("$baseUrl/api/exit-ip")
    private val devicesUrl = URL("$baseUrl/api/devices")

    /** Device actions live under /api/devices/{id}; the config itself under /devices/{id}. */
    private fun deviceActionUrl(id: Int, action: String? = null) =
        URL("$baseUrl/api/devices/$id" + (action?.let { "/$it" } ?: ""))

    private fun deviceConfigUrl(id: Int) = URL("$baseUrl/devices/$id/config")

    /** Fetches identity and current exit, without persisting the private config body. */
    fun fetchHomeSnapshot(): BackendSnapshot {
        val response = get(homeUrl)
        require(response.ok) { response.errorMessage() }

        val peer = JSONObject(response.body).optJSONObject("peer") ?: JSONObject()
        return BackendSnapshot(
            profileName = peer.optStringOrNull("subnet_name"),
            deviceName = peer.optStringOrNull("name"),
            localIp = peer.optStringOrNull("addr"),
            currentExitId = peer.optStringOrNull("exit"),
            currentExitLabel = peer.optStringOrNull("exit_label"),
            actualExitId = peer.optStringOrNull("via"),
            fetchedAtMillis = System.currentTimeMillis(),
        )
    }

    fun fetchSession(): BackendSession {
        val response = get(sessionUrl)
        require(response.ok) { response.errorMessage() }

        val json = JSONObject(response.body)
        val rawExits = json.optJSONObject("exits") ?: JSONObject()
        val exits = buildList {
            rawExits.keys().forEach { id ->
                val exit = rawExits.optJSONObject(id) ?: return@forEach
                add(
                    BackendExit(
                        id = id,
                        label = exit.optStringOrNull("label") ?: id,
                        hint = exit.optStringOrNull("hint"),
                        xray = exit.optBoolean("xray"),
                        available = exit.optBoolean("ok"),
                        pingMillis = if (exit.isNull("ping")) null else exit.optInt("ping"),
                        // geo.share (how many lookups agree) is the admin page's business.
                        country = normalizeCountryCode(
                            exit.optJSONObject("geo")?.optStringOrNull("country"),
                        ),
                    ),
                )
            }
        }
        return BackendSession(
            csrf = json.optStringOrNull("csrf").orEmpty(),
            exits = exits,
            autoExit = json.optJSONObject("auto")?.let { raw ->
                BackendAutoExit(
                    label = raw.optStringOrNull("label") ?: "Auto",
                    hint = raw.optStringOrNull("hint"),
                    via = raw.optStringOrNull("via"),
                )
            },
        )
    }

    fun ping(): Boolean = get(pingUrl).ok

    /** The subnet's peers, read-only: everything the devices screen shows. */
    fun fetchDevices(): BackendDevices {
        val response = get(devicesUrl)
        require(response.ok) { response.errorMessage() }

        val json = JSONObject(response.body)
        val devices = buildList {
            json.optJSONArray("devices")?.let { raw ->
                for (i in 0 until raw.length()) {
                    val device = raw.optJSONObject(i) ?: continue
                    add(
                        BackendDevice(
                            id = device.optInt("id"),
                            name = device.optStringOrNull("name")
                                ?: device.optStringOrNull("addr").orEmpty(),
                            addr = device.optStringOrNull("addr").orEmpty(),
                            online = device.optBoolean("online"),
                            enabled = device.optBoolean("enabled"),
                            confFilename = device.optStringOrNull("conf_filename"),
                            exitLabel = device.optStringOrNull("exit_label"),
                            expires = device.optStringOrNull("expires"),
                            monthRx = device.optJSONObject("month")?.optStringOrNull("rx"),
                            monthTx = device.optJSONObject("month")?.optStringOrNull("tx"),
                        ),
                    )
                }
            }
        }
        return BackendDevices(
            devices = devices,
            meId = if (json.isNull("me")) null else json.optInt("me"),
            subnetName = json.optStringOrNull("subnet_name"),
            subnetCidr = json.optStringOrNull("subnet_cidr"),
            trusted = json.optBoolean("trusted"),
        )
    }

    /** Round-trip time of a /api/ping request, or null if the panel didn't answer. */
    fun measurePingMillis(): Long? {
        val start = android.os.SystemClock.elapsedRealtime()
        if (!get(pingUrl, timeoutMillis = PING_TIMEOUT_MILLIS).ok) return null
        return android.os.SystemClock.elapsedRealtime() - start
    }

    /**
     * A bare TCP handshake with the panel, through the tunnel: no request is ever sent, the
     * socket closes the moment it connects. It is the packet-loss probe — WireGuard exposes
     * no loss counter of its own, but a handshake that completes means the packets both ways
     * made it. False = the probe was lost (or the panel is down: the graph cannot tell the
     * two apart, and neither is good news).
     */
    fun probeConnectivity(timeoutMillis: Int = PROBE_TIMEOUT_MILLIS): Boolean = try {
        val target = URL(baseUrl)
        Socket().use { socket ->
            socket.soTimeout = timeoutMillis
            socket.connect(InetSocketAddress(target.host, target.port), timeoutMillis)
            true
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Public IP seen through the currently active exit. The endpoint is slow, so callers should
     * treat it as optional and never block the server list on it.
     */
    fun fetchExitIp(): String? {
        val response = get(exitIpUrl, timeoutMillis = EXIT_IP_TIMEOUT_MILLIS)
        if (!response.ok) return null
        // The endpoint can answer with a localized error page; only an address is valid.
        return response.body.trim().takeIf { it.matches(IP_ADDRESS) }
    }

    /** Selects an exit id (or "auto"). */
    fun selectExit(exitId: String) = postJson(exitUrl, JSONObject().put("exit", exitId))

    /** Creates a new peer on the panel; allowed only while /api/devices says "trusted". */
    fun createDevice(name: String) = postJson(devicesUrl, JSONObject().put("name", name))

    /** Renames a peer on the panel. */
    fun renameDevice(id: Int, name: String) =
        postJson(deviceActionUrl(id, "rename"), JSONObject().put("name", name))

    /** Enables or disables a peer, whichever it currently isn't. */
    fun toggleDevice(id: Int) = postJson(deviceActionUrl(id, "toggle"), JSONObject())

    /** Deletes the peer outright — it loses its access to the subnet. */
    fun deleteDevice(id: Int) = postJson(deviceActionUrl(id, "delete"), JSONObject())

    /**
     * The peer's full WireGuard config — private key included — fetched on demand for the
     * config dialog. Never cached, never logged: it goes to the QR code, the clipboard or
     * a file the user picked, and nowhere else.
     */
    fun fetchDeviceConfig(id: Int): String {
        val response = get(deviceConfigUrl(id))
        require(response.ok) { response.errorMessage() }
        return response.body.trim()
    }

    /**
     * Posts JSON to the panel, retrying once with a fresh CSRF token when the server reports
     * that the page is stale — the panel binds tokens to the session cookie, and either side
     * may have rotated while we weren't looking.
     */
    private fun postJson(url: URL, payload: JSONObject) {
        val body = payload.toString().toByteArray()
        val firstAttempt = post(url, body, fetchSession().csrf)
        if (firstAttempt.ok) return
        if (!firstAttempt.isCsrfStale) throw IllegalStateException(firstAttempt.errorMessage())

        val retry = post(url, body, fetchSession().csrf)
        if (!retry.ok) throw IllegalStateException(retry.errorMessage())
    }

    private fun post(url: URL, body: ByteArray, csrf: String): ApiResponse =
        request(url, "POST", TIMEOUT_MILLIS) { connection ->
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-CSRF-Token", csrf)
            // Same-origin checks: the web UI's fetch() always sends these.
            connection.setRequestProperty("Origin", baseUrl)
            connection.setRequestProperty("Referer", "$baseUrl/")
            connection.setFixedLengthStreamingMode(body.size)
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
        }

    private fun get(
        url: URL,
        timeoutMillis: Int = TIMEOUT_MILLIS,
    ): ApiResponse = request(url, "GET", timeoutMillis) { connection ->
        connection.setRequestProperty("Accept", "application/json")
    }

    private inline fun request(
        url: URL,
        method: String,
        timeoutMillis: Int,
        setup: (HttpURLConnection) -> Unit,
    ): ApiResponse {
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.instanceFollowRedirects = false
            val uri = url.toURI()
            cookies.get(uri, emptyMap()).forEach { (name, values) ->
                if (values.isNotEmpty()) connection.setRequestProperty(name, values.joinToString("; "))
            }
            setup(connection)

            val statusCode = connection.responseCode
            cookies.put(uri, connection.headerFields.filterKeys { it != null })
            val body = runCatching {
                if (statusCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }
            }.getOrNull().orEmpty()
            ApiResponse(statusCode in 200..299, statusCode, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private data class ApiResponse(
        val ok: Boolean,
        val statusCode: Int,
        val body: String,
    ) {
        val isCsrfStale: Boolean
            get() = !ok && statusCode == 403 && body.trimStart().startsWith(CSRF_STALE_MARKER)

        fun errorMessage(): String {
            val serverMessage = runCatching { JSONObject(body).optString("error") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            val plain = body.trim().lineSequence().firstOrNull()?.take(120)?.takeIf { it.isNotBlank() }
            return serverMessage ?: plain?.let { "HTTP $statusCode: $it" } ?: "HTTP $statusCode"
        }
    }

    private companion object {
        const val DEFAULT_BASE_URL = "http://10.0.0.1:8080"
        const val TIMEOUT_MILLIS = 5_000
        const val PING_TIMEOUT_MILLIS = 3_000
        const val PROBE_TIMEOUT_MILLIS = 2_000
        const val EXIT_IP_TIMEOUT_MILLIS = 12_000
        const val CSRF_STALE_MARKER = "Страница устарела"
        val IP_ADDRESS = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    }
}
