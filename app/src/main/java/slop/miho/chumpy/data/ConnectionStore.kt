package slop.miho.chumpy.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import slop.miho.chumpy.xray.RoutingMode
import slop.miho.chumpy.xray.XrayConfigBuilder
import java.io.File

/**
 * Connection preferences — the selected subscription and profile, the routing mode and
 * the delay test URL. Kept apart from [SettingsStore] (the interface's own settings) the
 * same way the connection screens are apart from the interface ones.
 */
data class ConnectionSettings(
    /** The subscription whose servers the servers screen shows; null until one exists. */
    val selectedSubscriptionId: String? = null,
    /** The profile a connect uses; restored across restarts. */
    val selectedProfileId: String? = null,
    val routingMode: RoutingMode = RoutingMode.GLOBAL,
    /** The URL real delay measurements fetch through the tested outbound. */
    val delayTestUrl: String = XrayConfigBuilder.DEFAULT_TEST_URL,
    /** Packages the tunnel leaves alone — the per-app bypass list, v2rayNG's arrangement. */
    val bypassedApps: Set<String> = emptySet(),
    /** The per-app proxy's own master switch: off, and the whole list stops applying. */
    val perAppEnabled: Boolean = true,
    /**
     * Every network the networks page holds — trusted and untoggled both, the page's own
     * roster. Nothing is detected from the OS; a name lands here only by the user's hand.
     */
    val savedWifiSsids: Set<String> = emptySet(),
    /** Wi-Fi names the tunnel stands down on — the roster's trusted subset. */
    val trustedWifiSsids: Set<String> = emptySet(),
    /** The trusted networks' own master switch, mirroring the per-app one. */
    val trustedWifiEnabled: Boolean = true,
    /**
     * Whether the system's own demand for the VPN — the always-on restart, the sticky
     * resurrection after the core process died — brings the tunnel back by itself. Off
     * (the default): a fall stands, the alert notification's Reconnect being the way
     * back by the user's own hand.
     */
    val autoReconnect: Boolean = false,
)

object ConnectionStore {
    private const val PREFS = "connection_settings"

    private val state = MutableStateFlow(ConnectionSettings())
    private var seeded = false

    fun settings(context: Context): StateFlow<ConnectionSettings> {
        if (!seeded) {
            state.value = read(context)
            seeded = true
        }
        return state.asStateFlow()
    }

    fun update(context: Context, transform: (ConnectionSettings) -> ConnectionSettings) {
        val updated = transform(settings(context).value)
        write(context, updated)
        state.value = updated
    }

    /**
     * The settings straight off disk. The VPN service reads the bypass list from the
     * :core process, whose in-memory copy would go stale the moment the UI process
     * writes a new one.
     */
    fun current(context: Context): ConnectionSettings = read(context)

    /**
     * The auto-reconnect flag read straight off the prefs *file*, not this process's
     * SharedPreferences cache — which never sees the UI process's writes. The flag rules
     * the mid-flight decisions (a handover re-dials or falls by it), so it must be the
     * value the user last set, not the one this process loaded at birth. Absent from the
     * file, it is the default: off.
     */
    fun autoReconnectFresh(context: Context): Boolean {
        val dataDir = context.applicationInfo.dataDir ?: return false
        val file = File(dataDir, "shared_prefs/$PREFS.xml")
        if (!file.isFile) return false
        return try {
            file.readText().contains("<boolean name=\"$KEY_AUTO_RECONNECT\" value=\"true\"")
        } catch (_: Exception) {
            false
        }
    }

    private fun read(context: Context): ConnectionSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ConnectionSettings(
            selectedSubscriptionId = prefs.getString(KEY_SUB, null),
            selectedProfileId = prefs.getString(KEY_PROFILE, null),
            routingMode = prefs.getString(KEY_ROUTING, null)
                // "RU_BYPASS" is the old spelling of the Russia bypass from before the
                // region list grew; carried over so an update keeps the pick.
                ?.let { stored ->
                    RoutingMode.entries.firstOrNull { it.name == stored }
                        ?: if (stored == "RU_BYPASS") RoutingMode.BYPASS_RU else null
                }
                ?: RoutingMode.GLOBAL,
            delayTestUrl = prefs.getString(KEY_TEST_URL, null)?.takeIf { it.isNotBlank() }
                ?: XrayConfigBuilder.DEFAULT_TEST_URL,
            bypassedApps = prefs.getStringSet(KEY_BYPASS_APPS, null)?.toSet() ?: emptySet(),
            perAppEnabled = prefs.getBoolean(KEY_PER_APP, true),
            // The roster: an install from before it existed starts from its trusted names —
            // the page kept only those, so they become the whole roster, trusted as they were.
            savedWifiSsids = prefs.getStringSet(KEY_SAVED_WIFI, null)?.toSet()
                ?: prefs.getStringSet(KEY_TRUSTED_WIFI, null)?.toSet() ?: emptySet(),
            trustedWifiSsids = prefs.getStringSet(KEY_TRUSTED_WIFI, null)?.toSet() ?: emptySet(),
            trustedWifiEnabled = prefs.getBoolean(KEY_TRUSTED_WIFI_ENABLED, true),
            autoReconnect = prefs.getBoolean(KEY_AUTO_RECONNECT, false),
        )
    }

    private fun write(context: Context, settings: ConnectionSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .also { editor ->
                val sub = settings.selectedSubscriptionId
                if (sub != null) editor.putString(KEY_SUB, sub) else editor.remove(KEY_SUB)
                val profile = settings.selectedProfileId
                if (profile != null) editor.putString(KEY_PROFILE, profile) else editor.remove(KEY_PROFILE)
                if (settings.bypassedApps.isEmpty()) {
                    editor.remove(KEY_BYPASS_APPS)
                } else {
                    editor.putStringSet(KEY_BYPASS_APPS, settings.bypassedApps)
                }
                if (settings.savedWifiSsids.isEmpty()) {
                    editor.remove(KEY_SAVED_WIFI)
                } else {
                    editor.putStringSet(KEY_SAVED_WIFI, settings.savedWifiSsids)
                }
                if (settings.trustedWifiSsids.isEmpty()) {
                    editor.remove(KEY_TRUSTED_WIFI)
                } else {
                    editor.putStringSet(KEY_TRUSTED_WIFI, settings.trustedWifiSsids)
                }
            }
            .putString(KEY_ROUTING, settings.routingMode.name)
            .putString(KEY_TEST_URL, settings.delayTestUrl)
            .putBoolean(KEY_PER_APP, settings.perAppEnabled)
            .putBoolean(KEY_TRUSTED_WIFI_ENABLED, settings.trustedWifiEnabled)
            .putBoolean(KEY_AUTO_RECONNECT, settings.autoReconnect)
            .apply()
    }

    private const val KEY_SUB = "selected_subscription"
    private const val KEY_PROFILE = "selected_profile"
    private const val KEY_ROUTING = "routing_mode"
    private const val KEY_TEST_URL = "delay_test_url"
    private const val KEY_BYPASS_APPS = "bypassed_apps"
    private const val KEY_PER_APP = "per_app_enabled"
    private const val KEY_SAVED_WIFI = "saved_wifi_ssids"
    private const val KEY_TRUSTED_WIFI = "trusted_wifi_ssids"
    private const val KEY_TRUSTED_WIFI_ENABLED = "trusted_wifi_enabled"
    private const val KEY_AUTO_RECONNECT = "auto_reconnect"
}
