package slop.miho.aurora.db

import androidx.room.Entity
import androidx.room.PrimaryKey

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
)
