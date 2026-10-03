package slop.miho.aurora

import android.content.Context
import androidx.room.Room
import slop.miho.aurora.core.TunnelState
import slop.miho.aurora.data.SettingsStore
import slop.miho.aurora.db.AuroraDatabase
import slop.miho.aurora.sub.HappSpoof
import slop.miho.aurora.sub.HappSpoof.happFingerprint
import slop.miho.aurora.sub.SubscriptionRepository

/**
 * The main process's few long-lived objects: the Room database and the subscription
 * repository on top of it. The ":core" process never touches this — it receives rendered
 * Xray configs through the file hand-off instead.
 */
object Graph {
    lateinit var database: AuroraDatabase
        private set

    lateinit var subscriptions: SubscriptionRepository
        private set

    fun init(context: Context) {
        if (::database.isInitialized) return
        database = Room.databaseBuilder(
            context.applicationContext,
            AuroraDatabase::class.java,
            AuroraDatabase.NAME,
        ).addMigrations(AuroraDatabase.MIGRATION_1_2, AuroraDatabase.MIGRATION_2_3).build()
        subscriptions = SubscriptionRepository(
            context = context.applicationContext,
            db = database,
            // Read lazily, at fetch time: by then the app object has long since bound the
            // shared tunnel link. Fetches route through the loopback socks inbound whenever
            // the tunnel is up, so a panel behind a block answers anyway.
            tunnelUp = {
                AuroraApp.tunnelClient.status.value.state == TunnelState.UP
            },
            // Also read at fetch time, through the shared settings state: the Happ
            // fingerprint follows the toggle the moment it flips, and every fetch —
            // manual or background, first hop or nested — dresses up the same way.
            happSpoof = {
                SettingsStore.settings(context.applicationContext).value.happFingerprint()
            },
        )
    }
}
