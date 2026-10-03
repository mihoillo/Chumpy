package slop.miho.aurora.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY createdAt")
    fun all(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY createdAt")
    suspend fun allOnce(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun byId(id: String): SubscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(subscription: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
abstract class ProfileDao {
    // `IS :subscriptionId` rather than `=`: manual profiles live in the NULL bucket, and
    // SQL's `= NULL` matches nothing — `IS NULL` is the comparison that covers both.
    @Query("SELECT * FROM profiles WHERE subscriptionId IS :subscriptionId ORDER BY `order`, remarks")
    abstract fun bySubscription(subscriptionId: String?): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE subscriptionId IS :subscriptionId ORDER BY `order`, remarks")
    abstract suspend fun bySubscriptionOnce(subscriptionId: String?): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id")
    abstract suspend fun byId(id: String): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertProfiles(profiles: List<ProfileEntity>)

    @Query("DELETE FROM profiles WHERE subscriptionId IS :subscriptionId")
    protected abstract suspend fun deleteProfilesFor(subscriptionId: String?)

    /**
     * One subscription refresh, committed as a unit: the parsed profiles replace the old
     * set, while each profile whose [ProfileEntity.linkKey] survives carries its identity,
     * ping and selection state over to the new row.
     */
    @Transaction
    open suspend fun replaceProfiles(subscriptionId: String?, profiles: List<ProfileEntity>) {
        deleteProfilesFor(subscriptionId)
        insertProfiles(profiles)
    }

    @Query("UPDATE profiles SET testDelayMillis = :delayMillis, lastTestAt = :testedAt WHERE id = :id")
    abstract suspend fun setDelay(id: String, delayMillis: Long?, testedAt: Long)

    @Query("SELECT COUNT(*) FROM profiles")
    abstract suspend fun count(): Int

    /** The manual (subscription-less) profile count, as a flow — the setup gate watches it. */
    @Query("SELECT COUNT(*) FROM profiles WHERE subscriptionId IS NULL")
    abstract fun manualCount(): Flow<Int>
}

/**
 * The profile store. Owned by the UI process only — the ":core" process never opens it; it
 * receives rendered Xray config JSON over the file hand-off in
 * [slop.miho.aurora.core.CoreConfigFile] instead.
 */
@Database(
    entities = [SubscriptionEntity::class, ProfileEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AuroraDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun profileDao(): ProfileDao

    companion object {
        const val NAME = "aurora.db"

        /**
         * v2: the Happ fingerprint columns on subscriptions, and the balancer columns
         * (strategy, member linkKeys) on profiles.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN happSpoof INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN happAppVersion TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN happOs TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN happOsVersion TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN happDeviceModel TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN happLocale TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN happUserId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN happHwid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE profiles ADD COLUMN balancerStrategy TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE profiles ADD COLUMN balancerMembers TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
