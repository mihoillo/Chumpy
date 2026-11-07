package slop.miho.chumpy.data

import android.app.Application
import android.content.Context
import org.json.JSONObject
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
    fun `the LAN bypass flag survives the snapshot roundtrip and defaults on`() {
        val context = RuntimeEnvironment.getApplication()
        // The flag rides the published snapshot like its neighbours: what the write
        // publishes is what the core process reads back.
        ConnectionStore.update(context) { it.copy(bypassLan = false) }
        assertFalse(ConnectionStore.current(context).bypassLan)
        // A snapshot without the key — the shape an older version published — reads
        // back as the default: the LAN-friendly one, not false.
        AtomicSnapshot.write(
            context.getFileStreamPath("connection-settings.json"),
            JSONObject().put("routing_mode", "GLOBAL").toString(),
        )
        assertTrue(ConnectionStore.current(context).bypassLan)
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

    @Test fun `tile snapshot includes custom color and privacy setting`() {
        val context = RuntimeEnvironment.getApplication()
        SettingsStore.update(context) {
            it.copy(accent = Accent.CUSTOM, customAccent = 0xFF314159.toInt(), maskAddresses = true)
        }
        val deadline = System.nanoTime() + 2_000_000_000L
        while (SettingsStore.tileSettings(context).customAccent != 0xFF314159.toInt() && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(0xFF314159.toInt(), SettingsStore.tileSettings(context).customAccent)
        assertTrue(SettingsStore.tileSettings(context).maskAddresses)
    }

    @Test fun `day buckets retain the original sampling day`() {
        val context = RuntimeEnvironment.getApplication()
        context.getFileStreamPath("daily-traffic.json").delete()
        val today = java.time.LocalDate.now().toEpochDay()
        DailyTrafficStore.addDay(context, today - 1, 100, 20)
        DailyTrafficStore.addToday(context, 300, 40)
        val days = DailyTrafficStore.readWeek(context)
        assertEquals(100L, days.single { it.epochDay == today - 1 }.rx)
        assertEquals(300L, days.single { it.epochDay == today }.rx)
    }

}
