package com.example.videorecorder.recording

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.videorecorder.widget.RecordingWidgetProvider
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "RecorderApp"

/**
 * The only recording states the app can be in. Every transition goes through
 * [RecordingStateHolder], which is what makes double-start / double-stop impossible.
 */
enum class RecordingState {
    IDLE,
    STARTING,
    RECORDING,
    STOPPING,
    ERROR,
}

data class RecordingStatus(
    val state: RecordingState = RecordingState.IDLE,
    /** [SystemClock.elapsedRealtime] when the current recording actually started, or 0. */
    val startedAtElapsedMs: Long = 0L,
    /** Display name of the file being written, or of the last finished one. */
    val fileName: String? = null,
    val errorMessage: String? = null,
) {
    /** Milliseconds recorded so far, or 0 when not recording. */
    fun elapsedMs(): Long =
        if (state == RecordingState.RECORDING && startedAtElapsedMs > 0L) {
            SystemClock.elapsedRealtime() - startedAtElapsedMs
        } else {
            0L
        }
}

/**
 * Process-wide recording state, shared by the service, the Activity and the widget.
 *
 * All three live in the same process, so a plain singleton is enough and avoids the
 * races an IPC-based design would introduce. The service is the only writer; the
 * Activity and widget are readers. If the process dies the service dies with it, so
 * resetting to [RecordingState.IDLE] on a fresh process is the correct behaviour.
 */
object RecordingStateHolder {

    private val _status = MutableStateFlow(RecordingStatus())
    val status: StateFlow<RecordingStatus> = _status.asStateFlow()

    val current: RecordingStatus get() = _status.value

    /**
     * Applies a new status and refreshes every widget instance.
     * Centralising the widget refresh here guarantees the widget can never show a
     * state that disagrees with the service.
     */
    fun set(context: Context, status: RecordingStatus) {
        val previous = _status.value
        if (previous == status) return
        _status.value = status
        Log.i(TAG, "state ${previous.state} -> ${status.state}" +
            (status.errorMessage?.let { " (${it})" } ?: ""))
        if (previous.state != status.state) {
            RecordingWidgetProvider.refresh(context)
        }
    }

    fun update(context: Context, transform: (RecordingStatus) -> RecordingStatus) {
        set(context, transform(_status.value))
    }
}

/** Formats a duration as HH:MM:SS, e.g. 00:12:43. */
fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}
