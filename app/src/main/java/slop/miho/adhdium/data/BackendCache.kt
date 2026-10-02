package slop.miho.adhdium.data

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent, sanitized panel snapshot. Only parsed UI fields are stored here; the raw
 * /api/home body is never written because it includes the peer's private WireGuard key.
 */
object BackendCache {
    private const val PROFILE_CACHE_FILE = "backend-cache.json"
    private const val SERVERS_CACHE_FILE = "servers-cache.json"
    private const val DEVICES_CACHE_FILE = "devices-cache.json"
    private const val PROFILE_CACHE_VERSION = 2
    private const val SERVERS_CACHE_VERSION = 2
    private const val DEVICES_CACHE_VERSION = 2

    fun read(context: Context): BackendSnapshot? {
        val file = cacheFile(context, PROFILE_CACHE_FILE)
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (json.optInt("version") != PROFILE_CACHE_VERSION) return null

        return BackendSnapshot(
            profileName = json.optStringOrNull("profile_name"),
            deviceName = json.optStringOrNull("device_name"),
            localIp = json.optStringOrNull("local_ip"),
            currentExitId = json.optStringOrNull("current_exit_id"),
            currentExitLabel = json.optStringOrNull("current_exit_label"),
            actualExitId = json.optStringOrNull("actual_exit_id"),
            fetchedAtMillis = json.optLong("fetched_at_millis"),
        )
    }

    fun write(context: Context, snapshot: BackendSnapshot) {
        val json = JSONObject()
            .put("version", PROFILE_CACHE_VERSION)
            .put("profile_name", snapshot.profileName ?: JSONObject.NULL)
            .put("device_name", snapshot.deviceName ?: JSONObject.NULL)
            .put("local_ip", snapshot.localIp ?: JSONObject.NULL)
            .put("current_exit_id", snapshot.currentExitId ?: JSONObject.NULL)
            .put("current_exit_label", snapshot.currentExitLabel ?: JSONObject.NULL)
            .put("actual_exit_id", snapshot.actualExitId ?: JSONObject.NULL)
            .put("fetched_at_millis", snapshot.fetchedAtMillis)
        writeAtomic(context, PROFILE_CACHE_FILE, json)
    }

    fun readServers(context: Context): ServersCache? {
        val file = cacheFile(context, SERVERS_CACHE_FILE)
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (json.optInt("version") != SERVERS_CACHE_VERSION) return null

        val rawExits = json.optJSONArray("exits") ?: JSONArray()
        val exits = buildList {
            for (index in 0 until rawExits.length()) {
                val exit = rawExits.optJSONObject(index) ?: continue
                add(
                    BackendExit(
                        id = exit.optString("id"),
                        label = exit.optString("label"),
                        hint = exit.optStringOrNull("hint"),
                        xray = exit.optBoolean("xray"),
                        available = exit.optBoolean("available"),
                        pingMillis = if (exit.isNull("ping")) null else exit.optInt("ping"),
                        // Caches from before the flags simply have none until the next fetch.
                        country = normalizeCountryCode(exit.optStringOrNull("country")),
                    ),
                )
            }
        }

        val auto = json.optJSONObject("auto")
        return ServersCache(
            exits = exits,
            auto = auto?.let {
                BackendAutoExit(
                    label = it.optString("label"),
                    hint = it.optStringOrNull("hint"),
                    via = it.optStringOrNull("via"),
                )
            },
            currentExitId = json.optStringOrNull("current_exit_id"),
            actualExitId = json.optStringOrNull("actual_exit_id"),
            exitIp = json.optStringOrNull("exit_ip"),
            fetchedAtMillis = json.optLong("fetched_at_millis"),
        )
    }

    fun writeServers(context: Context, cache: ServersCache) {
        val exitsArray = JSONArray()
        cache.exits.forEach { exit ->
            exitsArray.put(
                JSONObject()
                    .put("id", exit.id)
                    .put("label", exit.label)
                    .put("hint", exit.hint ?: JSONObject.NULL)
                    .put("xray", exit.xray)
                    .put("available", exit.available)
                    .put("ping", exit.pingMillis ?: JSONObject.NULL)
                    .put("country", exit.country ?: JSONObject.NULL),
            )
        }
        val autoJson = cache.auto?.let {
            JSONObject()
                .put("label", it.label)
                .put("hint", it.hint ?: JSONObject.NULL)
                .put("via", it.via ?: JSONObject.NULL)
        }
        val json = JSONObject()
            .put("version", SERVERS_CACHE_VERSION)
            .put("exits", exitsArray)
            .put("auto", autoJson ?: JSONObject.NULL)
            .put("current_exit_id", cache.currentExitId ?: JSONObject.NULL)
            .put("actual_exit_id", cache.actualExitId ?: JSONObject.NULL)
            .put("exit_ip", cache.exitIp ?: JSONObject.NULL)
            .put("fetched_at_millis", cache.fetchedAtMillis)
        writeAtomic(context, SERVERS_CACHE_FILE, json)
    }

    fun clear(context: Context) {
        cacheFile(context, PROFILE_CACHE_FILE).delete()
        cacheFile(context, SERVERS_CACHE_FILE).delete()
        cacheFile(context, DEVICES_CACHE_FILE).delete()
    }

    /**
     * The cached device list. Online status is deliberately not cached: it is a moment's
     * truth, wrong the moment it lands on disk — read back, every device reports offline
     * until the next live fetch says otherwise.
     */
    fun readDevices(context: Context): DevicesCache? {
        val file = cacheFile(context, DEVICES_CACHE_FILE)
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (json.optInt("version") != DEVICES_CACHE_VERSION) return null

        val rawDevices = json.optJSONArray("devices") ?: JSONArray()
        val devices = buildList {
            for (index in 0 until rawDevices.length()) {
                val device = rawDevices.optJSONObject(index) ?: continue
                add(
                    BackendDevice(
                        id = device.optInt("id"),
                        name = device.optString("name"),
                        addr = device.optString("addr"),
                        online = false,
                        enabled = device.optBoolean("enabled"),
                        confFilename = device.optStringOrNull("conf_filename"),
                        exitLabel = device.optStringOrNull("exit_label"),
                        expires = device.optStringOrNull("expires"),
                        monthRx = device.optStringOrNull("month_rx"),
                        monthTx = device.optStringOrNull("month_tx"),
                    ),
                )
            }
        }
        return DevicesCache(
            devices = devices,
            meId = if (json.isNull("me_id")) null else json.optInt("me_id"),
            subnetName = json.optStringOrNull("subnet_name"),
            subnetCidr = json.optStringOrNull("subnet_cidr"),
            fetchedAtMillis = json.optLong("fetched_at_millis"),
        )
    }

    fun writeDevices(context: Context, cache: DevicesCache) {
        val devicesArray = JSONArray()
        cache.devices.forEach { device ->
            // No "online" field: see [readDevices] — it is never cached.
            devicesArray.put(
                JSONObject()
                    .put("id", device.id)
                    .put("name", device.name)
                    .put("addr", device.addr)
                    .put("enabled", device.enabled)
                    .put("conf_filename", device.confFilename ?: JSONObject.NULL)
                    .put("exit_label", device.exitLabel ?: JSONObject.NULL)
                    .put("expires", device.expires ?: JSONObject.NULL)
                    .put("month_rx", device.monthRx ?: JSONObject.NULL)
                    .put("month_tx", device.monthTx ?: JSONObject.NULL),
            )
        }
        val json = JSONObject()
            .put("version", DEVICES_CACHE_VERSION)
            .put("devices", devicesArray)
            .put("me_id", cache.meId ?: JSONObject.NULL)
            .put("subnet_name", cache.subnetName ?: JSONObject.NULL)
            .put("subnet_cidr", cache.subnetCidr ?: JSONObject.NULL)
            .put("fetched_at_millis", cache.fetchedAtMillis)
        writeAtomic(context, DEVICES_CACHE_FILE, json)
    }

    private fun writeAtomic(context: Context, fileName: String, json: JSONObject) {
        val target = cacheFile(context, fileName)
        val tmp = File(target.parentFile, "$fileName.tmp")
        tmp.writeText(json.toString())
        check(tmp.renameTo(target)) { "Couldn't write backend cache" }
    }

    private fun cacheFile(context: Context, fileName: String): File =
        File(context.filesDir, fileName)

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }
}

data class ServersCache(
    val exits: List<BackendExit>,
    val auto: BackendAutoExit?,
    val currentExitId: String?,
    val actualExitId: String?,
    val exitIp: String?,
    val fetchedAtMillis: Long,
)

data class DevicesCache(
    val devices: List<BackendDevice>,
    val meId: Int?,
    val subnetName: String?,
    val subnetCidr: String?,
    val fetchedAtMillis: Long,
)
