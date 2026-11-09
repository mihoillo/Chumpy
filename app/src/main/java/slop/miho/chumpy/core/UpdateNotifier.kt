package slop.miho.chumpy.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import slop.miho.chumpy.MainActivity
import slop.miho.chumpy.R

/** The actions the update notification's own buttons stand for. */
object UpdateIntents {
    const val PAUSE = "slop.miho.chumpy.action.UPDATE_PAUSE"
    const val RESUME = "slop.miho.chumpy.action.UPDATE_RESUME"
    const val CANCEL = "slop.miho.chumpy.action.UPDATE_CANCEL"
    const val DISMISS = "slop.miho.chumpy.action.UPDATE_DISMISS"

    /** The release the notification was built for, riding every action intent back. */
    const val EXTRA_TAG = "update_tag"
}

/**
 * The pipe the notification's button presses travel down. The download and its pause
 * live in the view model's scope, so [UpdateActionReceiver] drops each action here for
 * it to drink — a cold process has nobody listening, and only the actions that must
 * survive one (a dismiss) are written to the prefs right in the receiver instead.
 */
object UpdateRequests {
    private val _actions = MutableSharedFlow<String>(extraBufferCapacity = 8)

    val actions: SharedFlow<String> = _actions

    fun post(action: String) {
        _actions.tryEmit(action)
    }
}

/**
 * The update download's live tile: the progress while the APK streams in, the pause
 * the re-tap or the shade's own button holds it at, and the finished file after it
 * lands. While the download runs the line is ongoing and offers Pause and Cancel;
 * paused it can be swiped away and offers Resume and Cancel; ready it offers only the
 * Dismiss that waves the release away until the repo holds something newer. On
 * Android 16+ the bar rides the system's live updates surface (ProgressStyle plus the
 * promotion request, the status-bar chip and the promoted drawer placement with it);
 * older devices see the classic determinate progress under the same title. The tap
 * always asks for the install — or, while the download runs, holds it at a pause.
 */
object UpdateNotifier {
    private const val CHANNEL_ID = "chumpy-updates"

    /** The VPN service holds 1 and the tunnel alerts 2; the update takes its own slot. */
    private const val NOTIFICATION_ID = 3

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_updates),
                // Silent: the progress speaks through the promoted surface, not a buzz.
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.notif_channel_updates_desc) },
        )
    }

    private fun builder(context: Context): Notification.Builder {
        // The tap is the install ask, from any shade: open the app with the request.
        val launch = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_INSTALL_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val content = PendingIntent.getActivity(
            context,
            1,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_ID)
            // The connect status keeps its own tile; the download's line wears the
            // download mark, the same one the update card's tile does.
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(context.getString(R.string.notif_update_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(content)
    }

    /** One of the shade's own buttons, aimed at [UpdateActionReceiver] with the tag. */
    private fun button(context: Context, action: String, tag: String, label: Int, icon: Int) =
        Notification.Action.Builder(
            Icon.createWithResource(context, icon),
            context.getString(label),
            PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                Intent(context, UpdateActionReceiver::class.java)
                    .setAction(action)
                    .putExtra(UpdateIntents.EXTRA_TAG, tag),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()

    private fun Notification.Builder.withProgress(percent: Int): Notification.Builder {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            setStyle(
                Notification.ProgressStyle()
                    .setProgress(percent)
                    .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100))),
            )
            // The promoted live update asks as of 36.1/37 (the status chip); on 16.0 the
            // same notification shows as any other ongoing one.
            if (Build.VERSION.SDK_INT >= 37) setRequestPromotedOngoing(true)
        } else {
            setProgress(100, percent, false)
        }
        return this
    }

    /** The download's own line in the shade: the percent as the chunks land, Pause
     *  and Cancel beside it. */
    fun downloading(context: Context, percent: Int, tag: String) {
        ensureChannel(context)
        post(context, builder(context)
            .setContentText(context.getString(R.string.notif_update_downloading))
            .withProgress(percent)
            .addAction(button(context, UpdateIntents.PAUSE, tag, R.string.notif_update_pause, R.drawable.ic_pause))
            .addAction(button(context, UpdateIntents.CANCEL, tag, R.string.notif_update_cancel, R.drawable.ic_close))
            .build())
    }

    /** The held download: the bar stands where it stopped, the line can be swiped away,
     *  Resume and Cancel offered from the shade. */
    fun paused(context: Context, percent: Int, tag: String) {
        ensureChannel(context)
        post(context, builder(context)
            .setContentText(context.getString(R.string.notif_update_paused))
            .withProgress(percent)
            .setOngoing(false)
            .addAction(button(context, UpdateIntents.RESUME, tag, R.string.notif_update_resume, R.drawable.ic_download))
            .addAction(button(context, UpdateIntents.CANCEL, tag, R.string.notif_update_cancel, R.drawable.ic_close))
            .build())
    }

    /** The finished APK: no buttons, the words "ready to install" — and the bar kept
     *  full on purpose: without it the line falls out of the live updates surface
     *  altogether, and the full bar is that surface's own "done" face. The tap asks
     *  the installer. */
    fun ready(context: Context) {
        ensureChannel(context)
        post(context, builder(context)
            .setContentText(context.getString(R.string.update_ready))
            .withProgress(100)
            .build())
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun post(context: Context, notification: Notification) {
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }
}
