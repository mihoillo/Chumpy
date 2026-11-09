package slop.miho.chumpy.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import slop.miho.chumpy.MainActivity
import slop.miho.chumpy.R

/**
 * The update download's live tile: the progress while the APK streams in, the finished
 * file after it lands — an ongoing notification either way, so the story stays up until
 * the new version is actually installed. On Android 16+ the bar rides the system's live
 * updates surface (ProgressStyle plus the promotion request, the status-bar chip and
 * the promoted drawer placement with it); older devices see the classic determinate
 * progress under the same title. The tap always asks for the install.
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
            .setSmallIcon(R.drawable.ic_tile_on)
            .setContentTitle(context.getString(R.string.notif_update_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(content)
    }

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

    /** The download's own line in the shade: the percent as the chunks land. */
    fun downloading(context: Context, percent: Int) {
        ensureChannel(context)
        post(context, builder(context)
            .setContentText(context.getString(R.string.notif_update_downloading))
            .withProgress(percent)
            .build())
    }

    /** The finished APK: still ongoing, the bar full, the tap asking for the install. */
    fun ready(context: Context, version: String) {
        ensureChannel(context)
        post(context, builder(context)
            .setContentText(context.getString(R.string.notif_update_ready, version))
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
