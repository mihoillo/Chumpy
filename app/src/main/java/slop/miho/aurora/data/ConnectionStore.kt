package slop.miho.aurora.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import slop.miho.aurora.xray.RoutingMode
import slop.miho.aurora.xray.XrayConfigBuilder

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

    private fun read(context: Context): ConnectionSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ConnectionSettings(
            selectedSubscriptionId = prefs.getString(KEY_SUB, null),
            selectedProfileId = prefs.getString(KEY_PROFILE, null),
            routingMode = prefs.getString(KEY_ROUTING, null)
                ?.let { stored -> RoutingMode.entries.firstOrNull { it.name == stored } }
                ?: RoutingMode.GLOBAL,
            delayTestUrl = prefs.getString(KEY_TEST_URL, null)?.takeIf { it.isNotBlank() }
                ?: XrayConfigBuilder.DEFAULT_TEST_URL,
        )
    }

    private fun write(context: Context, settings: ConnectionSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .also { editor ->
                val sub = settings.selectedSubscriptionId
                if (sub != null) editor.putString(KEY_SUB, sub) else editor.remove(KEY_SUB)
                val profile = settings.selectedProfileId
                if (profile != null) editor.putString(KEY_PROFILE, profile) else editor.remove(KEY_PROFILE)
            }
            .putString(KEY_ROUTING, settings.routingMode.name)
            .putString(KEY_TEST_URL, settings.delayTestUrl)
            .apply()
    }

    private const val KEY_SUB = "selected_subscription"
    private const val KEY_PROFILE = "selected_profile"
    private const val KEY_ROUTING = "routing_mode"
    private const val KEY_TEST_URL = "delay_test_url"
}
