package slop.miho.chumpy.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import slop.miho.chumpy.sub.UpdatePrefs

/**
 * The update notification's own buttons landing back in the app. The dismiss stands
 * entirely on its own — the shade line goes and the release's tag is waved away in
 * the prefs right here, so even a process with nobody listening remembers it on the
 * next cold start; the pause, resume and cancel must reach the download's own scope,
 * the running view model, and travel there through [UpdateRequests].
 */
class UpdateActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val action = intent.action) {
            UpdateIntents.DISMISS -> {
                intent.getStringExtra(UpdateIntents.EXTRA_TAG)?.let { UpdatePrefs.hideTag(context, it) }
                UpdateNotifier.cancel(context)
                UpdateRequests.post(action)
            }
            UpdateIntents.PAUSE, UpdateIntents.RESUME, UpdateIntents.CANCEL ->
                UpdateRequests.post(action)
        }
    }
}
