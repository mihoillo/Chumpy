package slop.miho.aurora.sub

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import slop.miho.aurora.db.AuroraDatabase
import slop.miho.aurora.db.ProfileEntity
import slop.miho.aurora.db.SubscriptionEntity
import slop.miho.aurora.db.computeLinkKey
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Owns subscriptions and their profiles: adding, refreshing, deleting and the manual
 * import path. A refresh replaces the subscription's profile rows wholesale, but profiles
 * whose [slop.miho.aurora.db.ProfileEntity.linkKey] survives carry their identity — and
 * with it the selection state and the last measured ping — over to the new rows.
 *
 * Each subscription with auto-update on gets one unique periodic WorkManager job, its
 * interval taken from the panel's `profile-update-interval` header when present.
 */
class SubscriptionRepository(
    private val context: Context,
    private val db: AuroraDatabase,
    private val tunnelUp: () -> Boolean,
) {
    private companion object {
        const val TAG = "Aurora/SubRepo"
        const val DEFAULT_UPDATE_HOURS = 24L
        const val MIN_UPDATE_HOURS = 1L
        const val MAX_UPDATE_HOURS = 24L * 14
        const val WORK_PREFIX = "subscription-update-"
    }

    private val fetcher = SubscriptionFetcher(tunnelUp)
    private val subscriptions = db.subscriptionDao()
    private val profiles = db.profileDao()

    fun subscriptions(): Flow<List<SubscriptionEntity>> = subscriptions.all()

    /** The manual (subscription-less) profile count, as a flow — the setup gate watches it. */
    val manualProfileCount: Flow<Int> = profiles.manualCount()

    fun profilesOf(subscriptionId: String?): Flow<List<ProfileEntity>> =
        profiles.bySubscription(subscriptionId)

    suspend fun subscriptionById(id: String): SubscriptionEntity? = subscriptions.byId(id)

    suspend fun profileById(id: String): ProfileEntity? = profiles.byId(id)

    suspend fun profileCount(): Int = profiles.count()

    /** Fetches, parses and stores a new subscription; the URL is the identity. */
    suspend fun addSubscription(url: String, name: String? = null): Result<SubscriptionEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (body, metadata) = fetchWithNestedExpansion(url)
                val parsed = SubscriptionParser.parse(body)
                require(parsed.isNotEmpty()) { "No server links found in the response" }

                val entity = SubscriptionEntity(
                    id = UUID.randomUUID().toString(),
                    name = name?.takeIf { it.isNotBlank() }
                        ?: metadata.title
                        ?: fetcher.hostNameOf(url),
                    url = url.trim(),
                    lastUpdateAttempt = System.currentTimeMillis(),
                    lastUpdateSuccess = System.currentTimeMillis(),
                    updateIntervalHours = metadata.updateIntervalHours,
                    uploadBytes = metadata.uploadBytes,
                    downloadBytes = metadata.downloadBytes,
                    totalBytes = metadata.totalBytes,
                    expireEpochSeconds = metadata.expireEpochSeconds,
                )
                subscriptions.upsert(entity)
                profiles.replaceProfiles(entity.id, stamped(parsed, entity.id, oldProfiles = emptyList()))
                scheduleAutoUpdate(entity)
                Log.i(TAG, "Added subscription ${entity.id} with ${parsed.size} profiles")
                entity
            }.onFailure { logFailure("add", null, it) }
        }

    /** Re-fetches a subscription and swaps its profiles in place. */
    suspend fun updateSubscription(id: String): Result<SubscriptionEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val entity = subscriptions.byId(id) ?: error("Subscription not found")
                val attemptAt = System.currentTimeMillis()
                try {
                    val (body, metadata) = fetchWithNestedExpansion(entity.url)
                    val parsed = SubscriptionParser.parse(body)
                    require(parsed.isNotEmpty()) { "No server links found in the response" }

                    val old = profiles.bySubscriptionOnce(id)
                    val updated = entity.copy(
                        name = metadata.title ?: entity.name,
                        lastUpdateAttempt = attemptAt,
                        lastUpdateSuccess = attemptAt,
                        updateIntervalHours = metadata.updateIntervalHours,
                        uploadBytes = metadata.uploadBytes,
                        downloadBytes = metadata.downloadBytes,
                        totalBytes = metadata.totalBytes,
                        expireEpochSeconds = metadata.expireEpochSeconds,
                        lastError = null,
                    )
                    subscriptions.upsert(updated)
                    profiles.replaceProfiles(id, stamped(parsed, id, old))
                    scheduleAutoUpdate(updated)
                    Log.i(TAG, "Updated subscription $id: ${parsed.size} profiles")
                    updated
                } catch (e: Exception) {
                    // The failure lands on the card, not in a toast that is gone in a second.
                    subscriptions.upsert(
                        entity.copy(lastUpdateAttempt = attemptAt, lastError = e.message ?: e.javaClass.simpleName),
                    )
                    throw e
                }
            }.onFailure { logFailure("update", id, it) }
        }

    suspend fun deleteSubscription(id: String) = withContext(Dispatchers.IO) {
        subscriptions.delete(id)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_PREFIX + id)
        Log.i(TAG, "Deleted subscription $id")
    }

    suspend fun setSubscriptionAutoUpdate(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        subscriptions.byId(id)?.let { sub ->
            subscriptions.upsert(sub.copy(autoUpdate = enabled))
            if (enabled) scheduleAutoUpdate(sub) else {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_PREFIX + id)
            }
        }
    }

    /**
     * Manual import: one or more share links (one per line), or a WireGuard .conf body.
     * Returns the number of profiles actually stored — duplicates are skipped.
     */
    suspend fun addManualProfiles(text: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val drafts = if (text.trim().startsWith("[Interface]", ignoreCase = true)) {
                listOfNotNull(LinkParsers.parseWireguardConf(text))
            } else {
                text.lines().mapNotNull { LinkParsers.parse(it) }
            }
            require(drafts.isNotEmpty()) { "No recognizable server link found" }
            val existing = profiles.bySubscriptionOnce(null).map { it.linkKey }.toSet()
            val fresh = drafts.filter { it.computeLinkKey() !in existing }
            if (fresh.isNotEmpty()) {
                profiles.replaceProfiles(
                    null,
                    profiles.bySubscriptionOnce(null) + stamped(fresh, null, oldProfiles = emptyList()),
                )
            }
            fresh.size
        }.onFailure { logFailure("import", null, it) }
    }

    suspend fun deleteManualProfile(id: String) = withContext(Dispatchers.IO) {
        // Manual profiles are few; replace-all is the simplest transactional delete.
        val remaining = profiles.bySubscriptionOnce(null).filter { it.id != id }
        profiles.replaceProfiles(null, remaining)
    }

    /** Stores a measured ping on a profile row, for the list and the home screen. */
    suspend fun setProfileDelay(id: String, delayMillis: Long?, testedAt: Long) =
        withContext(Dispatchers.IO) { profiles.setDelay(id, delayMillis, testedAt) }

    /**
     * Stamps the drafts as rows: identity, ownership, order and — where the linkKey
     * matches an old row of the same subscription — the old id, ping and its timestamp.
     */
    private fun stamped(
        drafts: List<ProfileEntity>,
        subscriptionId: String?,
        oldProfiles: List<ProfileEntity>,
    ): List<ProfileEntity> {
        val oldByKey = oldProfiles.associateBy { it.linkKey }
        return drafts.mapIndexed { index, draft ->
            val key = draft.computeLinkKey()
            val old = oldByKey[key]
            draft.copy(
                id = old?.id ?: UUID.randomUUID().toString(),
                subscriptionId = subscriptionId,
                order = index.toLong(),
                remarks = draft.remarks.ifEmpty { old?.remarks.orEmpty() },
                linkKey = key,
                testDelayMillis = old?.testDelayMillis,
                lastTestAt = old?.lastTestAt,
            )
        }
    }

    /**
     * Some panels answer a subscription URL with a single URL line — a nested
     * subscription. One level of expansion, Hiddify-style, covers the panels that do it.
     */
    private fun fetchWithNestedExpansion(url: String): Pair<String, SubscriptionMetadata> {
        val first = fetcher.fetch(url)
        val nested = SubscriptionParser.isNestedSubscriptionUrl(first.body)
            ?.takeIf { it != url }
            ?: return first.body to first.metadata
        Log.d(TAG, "Subscription is nested; following one level")
        val second = fetcher.fetch(nested)
        return second.body to second.metadata
    }

    private fun scheduleAutoUpdate(subscription: SubscriptionEntity) {
        if (!subscription.autoUpdate) return
        val hours = (subscription.updateIntervalHours?.toLong() ?: DEFAULT_UPDATE_HOURS)
            .coerceIn(MIN_UPDATE_HOURS, MAX_UPDATE_HOURS)
        val request = PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(hours, TimeUnit.HOURS)
            .setInputData(workDataOf(SubscriptionUpdateWorker.KEY_SUBSCRIPTION_ID to subscription.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_PREFIX + subscription.id, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun logFailure(operation: String, id: String?, e: Throwable) {
        Log.w(TAG, "Subscription $operation failed (id=${id ?: "new"}): ${e.message}")
    }
}
