package com.example.videorecorder.widget

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.example.videorecorder.MainActivity
import com.example.videorecorder.R
import com.example.videorecorder.recording.RecordingService
import com.example.videorecorder.recording.RecordingState
import com.example.videorecorder.recording.RecordingStateHolder
import com.example.videorecorder.settings.SettingsRepository
import com.example.videorecorder.storage.VideoStorageManager

/**
 * One-button home-screen widget: tap to start, tap again to stop.
 *
 * ## Why this can start the camera with the app closed
 * Android 12+ blocks starting a foreground service from the background, and Android 11+
 * additionally denies camera/microphone access to services that were started from the
 * background. Interacting with an app widget is a documented exemption from *both*
 * restrictions, so the tap below is allowed to start a camera+microphone foreground
 * service even with the Activity destroyed and the app off the Recents list.
 *
 * To keep that exemption intact, the widget's PendingIntent starts the service directly
 * via [PendingIntent.getForegroundService]. Bouncing through a BroadcastReceiver first
 * would add a hop between the user's tap and the service start for no benefit.
 *
 * The tap sends a single TOGGLE action rather than START or STOP, so the service decides
 * from its own state what to do. That removes any chance of the widget's rendered state
 * being stale and sending the wrong command.
 */
class RecordingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        Log.i(TAG, "onUpdate for " + appWidgetIds.size + " widget(s)")
        appWidgetIds.forEach { render(context, appWidgetManager, it) }
    }

    override fun onEnabled(context: Context) {
        Log.i(TAG, "First widget added")
    }

    companion object {
        private const val TAG = "Widget"
        private const val REQ_TOGGLE = 20
        private const val REQ_SETUP = 21

        /** Re-renders every widget instance. Safe to call from any thread. */
        fun refresh(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(
                    ComponentName(context.applicationContext, RecordingWidgetProvider::class.java)
                )
                if (ids.isEmpty()) return
                Log.i(TAG, "Refreshing " + ids.size + " widget(s), state=" + RecordingStateHolder.current.state)
                ids.forEach { render(context, manager, it) }
            } catch (t: Throwable) {
                // A widget that fails to redraw must never take the recording down with it.
                Log.w(TAG, "Could not refresh widgets", t)
            }
        }

        /** True when recording can actually start: permission granted and folder usable. */
        fun isConfigured(context: Context): Boolean {
            val cameraGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            if (!cameraGranted) return false
            val settings = SettingsRepository(context)
            return VideoStorageManager(context, settings).hasUsableFolder()
        }

        private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val state = RecordingStateHolder.current.state
            val views = RemoteViews(context.packageName, R.layout.widget_record)

            when (state) {
                RecordingState.RECORDING, RecordingState.STOPPING -> {
                    views.setImageViewResource(R.id.widget_icon, R.drawable.ic_stop)
                    views.setTextViewText(R.id.widget_label, context.getString(R.string.widget_stop))
                    views.setInt(R.id.widget_icon, "setBackgroundResource", R.drawable.widget_button_active)
                }
                RecordingState.STARTING -> {
                    views.setImageViewResource(R.id.widget_icon, R.drawable.ic_record)
                    views.setTextViewText(R.id.widget_label, context.getString(R.string.widget_starting))
                    views.setInt(R.id.widget_icon, "setBackgroundResource", R.drawable.widget_button_active)
                }
                else -> {
                    views.setImageViewResource(R.id.widget_icon, R.drawable.ic_record)
                    views.setTextViewText(R.id.widget_label, context.getString(R.string.widget_record))
                    views.setInt(R.id.widget_icon, "setBackgroundResource", R.drawable.widget_button_idle)
                }
            }

            views.setOnClickPendingIntent(R.id.widget_root, tapIntent(context))
            manager.updateAppWidget(widgetId, views)
        }

        /**
         * While setup is incomplete the tap opens the app instead, because starting a
         * recording that is guaranteed to fail would be worse than explaining why.
         */
        private fun tapIntent(context: Context): PendingIntent =
            if (isConfigured(context)) {
                PendingIntent.getForegroundService(
                    context,
                    REQ_TOGGLE,
                    RecordingService.intent(context, RecordingService.ACTION_TOGGLE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                val intent = Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_SETUP_REQUIRED, true)
                PendingIntent.getActivity(
                    context,
                    REQ_SETUP,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
    }
}
