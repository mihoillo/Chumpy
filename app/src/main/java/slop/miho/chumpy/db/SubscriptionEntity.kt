package slop.miho.chumpy.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** The id the standalone group's row wears — the home of configs pasted outside any
 *  subscription. No row with this id stands in the subscriptions table; it is the
 *  dropdown's synthetic first entry, resolved to the manual profiles (whose
 *  subscriptionId is null) wherever a real id would be. */
const val STANDALONE_SUBSCRIPTION_ID = "standalone"

/**
 * One subscription: a URL whose body is a set of server links, plus the metadata panels
 * attach via response headers — traffic counters and expiry from `subscription-userinfo`,
 * the display name from `profile-title`/`content-disposition`, the refresh hint from
 * `profile-update-interval`.
 */
@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val id: String,
    /** Display name; falls back to the host of [url] until the panel names it. */
    val name: String = "",
    val url: String = "",
    val createdAt: Long = System.currentTimeMillis(),

    /** Wall-clock millis of the last fetch attempt, successful or not. */
    val lastUpdateAttempt: Long = 0,
    /** Wall-clock millis of the last successful refresh. */
    val lastUpdateSuccess: Long = 0,
    /** The panel's `profile-update-interval` hint in hours; null means "use the default". */
    val updateIntervalHours: Int? = null,
    /** Whether WorkManager keeps refreshing this subscription in the background. */
    val autoUpdate: Boolean = true,

    // Traffic counters from subscription-userinfo; zeros mean "not reported".
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val totalBytes: Long = 0,
    /** Expiry as epoch seconds; 0 means "not reported". */
    val expireEpochSeconds: Long = 0,

    /** The message of the last failed update, shown on the subscription card. */
    val lastError: String? = null,

    /**
     * The cached `profile-logo` avatar's file, under files/subscription-logos; null
     * means the default tile. Refreshed with the rest of the metadata — a panel that
     * drops or empties the header takes the logo with it.
     */
    val logoPath: String? = null,

    /**
     * The pin the row menu drives: pinned subscriptions stand the dropdown's ground
     * floor, first whatever the panel's birthday order says. A state like the auto
     * update, not a trait like the URL — it rides the row through metadata refreshes,
     * which never replace the row, only copy over it.
     */
    val pinned: Boolean = false,
)
