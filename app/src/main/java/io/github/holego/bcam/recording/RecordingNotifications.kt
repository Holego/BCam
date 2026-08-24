package io.github.holego.bcam.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.holego.bcam.MainActivity
import io.github.holego.bcam.R

/**
 * The persistent recording notification and the error notification.
 *
 * The elapsed time is rendered by the system through [NotificationCompat.Builder.setUsesChronometer]
 * rather than by re-posting the notification on a timer. The system keeps the counter
 * ticking on its own, so the service never wakes up just to update text - which is both
 * the cheapest option and the one that survives the screen being off.
 */
object RecordingNotifications {

    const val CHANNEL_RECORDING = "recording"
    const val CHANNEL_ERRORS = "recording_errors"

    /** Must be non-zero: this is the id the foreground service is attached to. */
    const val NOTIFICATION_ID = 1001
    private const val ERROR_NOTIFICATION_ID = 1002

    private const val REQ_CONTENT = 10
    private const val REQ_STOP = 11

    fun ensureChannels(context: Context) {
        // minSdk is 26, so notification channels always exist - no version guard needed.
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val recording = NotificationChannel(
            CHANNEL_RECORDING,
            context.getString(R.string.channel_recording),
            // LOW: the notification must be visible for the whole recording, but it should
            // never make a sound or vibrate while it is being updated.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_recording_description)
            setShowBadge(false)
        }

        val errors = NotificationChannel(
            CHANNEL_ERRORS,
            context.getString(R.string.channel_errors),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_errors_description)
        }

        manager.createNotificationChannel(recording)
        manager.createNotificationChannel(errors)
    }

    /** Shown between the service starting and the camera actually producing frames. */
    fun preparing(context: Context): Notification =
        base(context)
            .setContentTitle(context.getString(R.string.notification_preparing_title))
            .setContentText(context.getString(R.string.notification_preparing_text))
            .build()

    /** The steady-state recording notification, with a live timer and a Stop action. */
    fun recording(context: Context, startedAtElapsedMs: Long): Notification {
        val elapsed = (SystemClock.elapsedRealtime() - startedAtElapsedMs).coerceAtLeast(0L)
        return base(context)
            .setContentTitle(context.getString(R.string.notification_recording_title))
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis() - elapsed)
            .addAction(
                R.drawable.ic_stop,
                context.getString(R.string.notification_stop),
                stopPendingIntent(context),
            )
            .build()
    }

    /** Shown while a stop is in flight, so the user sees their tap was registered. */
    fun stopping(context: Context): Notification =
        base(context)
            .setContentTitle(context.getString(R.string.notification_stopping_title))
            .build()

    fun showError(context: Context, message: String) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ERRORS)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(context.getString(R.string.notification_error_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(contentPendingIntent(context))
            .build()
        // POST_NOTIFICATIONS may be denied; notify() is then a silent no-op, never a crash.
        try {
            NotificationManagerCompat.from(context).notify(ERROR_NOTIFICATION_ID, notification)
        } catch (t: SecurityException) {
            // Nothing to do - the Activity still shows the error in its status line.
        }
    }

    fun clearError(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(ERROR_NOTIFICATION_ID)
        } catch (t: Throwable) {
            // Ignore: clearing a notification must never affect recording.
        }
    }

    private fun base(context: Context): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent(context))

    private fun contentPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            REQ_CONTENT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Stopping goes straight to the service. Interacting with a notification is one of the
     * documented exemptions from the background foreground-service restrictions, so this
     * works with the app fully closed and never opens an Activity.
     */
    private fun stopPendingIntent(context: Context): PendingIntent =
        PendingIntent.getForegroundService(
            context,
            REQ_STOP,
            RecordingService.intent(context, RecordingService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
