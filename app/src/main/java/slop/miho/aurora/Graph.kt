package slop.miho.aurora

import android.content.Context
import androidx.room.Room
import slop.miho.aurora.core.TunnelState
import slop.miho.aurora.db.AuroraDatabase
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
        ).build()
        subscriptions = SubscriptionRepository(
            context = context.applicationContext,
            db = database,
            tunnelUp = { tunnelClient.status.value.state == TunnelState.UP },
        )
    }
}
