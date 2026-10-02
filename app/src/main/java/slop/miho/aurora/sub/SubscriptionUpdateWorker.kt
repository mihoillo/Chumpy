package slop.miho.aurora.sub

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import slop.miho.aurora.Graph

/**
 * The periodic background refresh of one subscription. Runs in the main process next to
 * the Room database; a deleted or auto-update-disabled subscription resolves as success
 * so the job does not spin on retries.
 */
class SubscriptionUpdateWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    companion object {
        const val KEY_SUBSCRIPTION_ID = "subscription_id"

        /** One-shot immediate refresh, used by the UI's update button. */
        fun runNow(context: Context, subscriptionId: String) {
            androidx.work.OneTimeWorkRequestBuilder<SubscriptionUpdateWorker>()
                .setInputData(workDataOf(KEY_SUBSCRIPTION_ID to subscriptionId))
                .build()
                .let { request ->
                    androidx.work.WorkManager.getInstance(context).enqueue(request)
                }
        }
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_SUBSCRIPTION_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR to "missing subscription id"))
        val repository = Graph.subscriptions
        val subscription = repository.subscriptionById(id)
            ?: return Result.success() // deleted since the job was scheduled
        if (!subscription.autoUpdate) return Result.success()
        // Stopped (e.g. constraints dropped): success rather than retry — the periodic
        // schedule brings the next refresh on its own.
        if (isStopped) return Result.success()

        return repository.updateSubscription(id).fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        ).also {
            Log.d("Aurora/SubWorker", "Refresh of $id -> $it")
        }
    }
}

private const val KEY_ERROR = "error"
