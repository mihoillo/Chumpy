package slop.miho.chumpy.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
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

    @Upsert
    suspend fun upsert(subscription: SubscriptionEntity)

    @Query("UPDATE subscriptions SET autoUpdate = :enabled WHERE id = :id")
    suspend fun setAutoUpdate(id: String, enabled: Boolean)

    /** The pin's own switch — a targeted update, the row's metadata untouched. */
    @Query("UPDATE subscriptions SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE subscriptions SET lastUpdateAttempt = :at, lastError = :error WHERE id = :id")
    suspend fun setUpdateError(id: String, at: Long, error: String)

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

    /** The row's last use — a targeted update, the row's identity untouched. */
    @Query("UPDATE profiles SET lastUsedAt = :usedAt WHERE id = :id")
    abstract suspend fun setLastUsed(id: String, usedAt: Long)

    /** The pin's own switch — a targeted update, the row's identity untouched. */
    @Query("UPDATE profiles SET pinned = :pinned WHERE id = :id")
    abstract suspend fun setPinned(id: String, pinned: Boolean)

    /** The user's own hide — a targeted update, the row's identity untouched. */
    @Query("UPDATE profiles SET concealed = :concealed WHERE id = :id")
    abstract suspend fun setConcealed(id: String, concealed: Boolean)

    @Query("SELECT COUNT(*) FROM profiles")
    abstract suspend fun count(): Int

    /** The manual (subscription-less) profile count, as a flow — the setup gate watches it. */
    @Query("SELECT COUNT(*) FROM profiles WHERE subscriptionId IS NULL AND hidden = 0")
    abstract fun manualCount(): Flow<Int>
}

/**
 * The profile store. Owned by the UI process only — the ":core" process never opens it; it
 * receives rendered Xray config JSON over the file hand-off in
 * [slop.miho.chumpy.core.CoreConfigFile] instead.
 */
@Database(
    entities = [SubscriptionEntity::class, ProfileEntity::class],
    version = 10,
    exportSchema = false,
)
abstract class ChumpyDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun profileDao(): ProfileDao

    companion object {
        const val NAME = "chumpy.db"

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

        /**
         * v3: the Happ fingerprint moved out of the subscriptions — one global block in
         * the settings now — so its per-subscription columns go.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TEMP TABLE chumpy_profiles_backup AS SELECT * FROM profiles")
                db.execSQL("""
                    CREATE TABLE subscriptions_new (
                        id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, url TEXT NOT NULL,
                        createdAt INTEGER NOT NULL, lastUpdateAttempt INTEGER NOT NULL,
                        lastUpdateSuccess INTEGER NOT NULL, updateIntervalHours INTEGER,
                        autoUpdate INTEGER NOT NULL, uploadBytes INTEGER NOT NULL,
                        downloadBytes INTEGER NOT NULL, totalBytes INTEGER NOT NULL,
                        expireEpochSeconds INTEGER NOT NULL, lastError TEXT
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO subscriptions_new
                    SELECT id, name, url, createdAt, lastUpdateAttempt, lastUpdateSuccess,
                           updateIntervalHours, autoUpdate, uploadBytes, downloadBytes,
                           totalBytes, expireEpochSeconds, lastError FROM subscriptions
                """.trimIndent())
                db.execSQL("DROP TABLE subscriptions")
                db.execSQL("ALTER TABLE subscriptions_new RENAME TO subscriptions")
                db.execSQL("INSERT OR IGNORE INTO profiles SELECT * FROM chumpy_profiles_backup")
                db.execSQL("DROP TABLE chumpy_profiles_backup")
            }
        }

        /**
         * v4: the panel's own routing rules, carried on every profile imported from a
         * raw config — the balancers' bypass semantics ride on them.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN routingRules TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v5: the hidden flag — an aggregator config's outbounds are the balancer's
         * internals, stored but never listed.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v6: the source link — the share link a profile was born from, kept verbatim
         * for the row menu's copy. Old rows carry none, and their copy stays dim until
         * the next import brings a link along.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN sourceLink TEXT")
            }
        }

        /**
         * v7: the pin — the row menu's own flag, carrying pinned rows to the head of
         * their list.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v8: the subscriptions' own pin, and the user's own hide for servers — the
         * dropdown's pinned rows to its head, the hidden ones out of the list until
         * the header's chip calls them back.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiles ADD COLUMN concealed INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN geodataSources TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v10: the servers list's own ordering grew a "last used" mode — the row's
         * last pick as the connect's profile.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN lastUsedAt INTEGER")
            }
        }

    }
}
