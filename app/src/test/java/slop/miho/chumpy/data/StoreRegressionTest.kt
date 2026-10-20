package slop.miho.chumpy.data

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class StoreRegressionTest {
    @Test
    fun `core reads the published filter instead of a stale preferences cache`() {
        val context = RuntimeEnvironment.getApplication()
        ConnectionStore.update(context) {
            it.copy(bypassedApps = setOf("test.app"), perAppEnabled = false, autoReconnect = true)
        }
        context.getSharedPreferences("connection_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("per_app_enabled", true).putBoolean("auto_reconnect", false)
            .remove("bypassed_apps").commit()
        val coreSettings = ConnectionStore.current(context)
        assertEquals(setOf("test.app"), coreSettings.bypassedApps)
        assertFalse(coreSettings.perAppEnabled)
        assertTrue(ConnectionStore.autoReconnectFresh(context))
    }

    @Test
    fun `tile appearance does not initialize Happ preferences in the core process`() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("ui_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        SettingsStore.tileSettings(context)
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `daily traffic survives reopening and adds deltas without replacing previous bytes`() {
        val context = RuntimeEnvironment.getApplication()
        context.getFileStreamPath("daily-traffic.json").delete()
        DailyTrafficStore.addToday(context, 100, 20)
        DailyTrafficStore.addToday(context, 200, 30)
        val day = DailyTrafficStore.readWeek(context).single()
        assertEquals(300L, day.rx)
        assertEquals(50L, day.tx)
    }
}
