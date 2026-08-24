package com.example.videorecorder.recording

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.example.videorecorder.R
import com.example.videorecorder.camera.CameraRepository
import com.example.videorecorder.camera.CompressionOption
import com.example.videorecorder.camera.VideoQualityOption
import com.example.videorecorder.settings.SettingsRepository
import com.example.videorecorder.storage.StorageResult
import com.example.videorecorder.storage.VideoStorageManager

/**
 * Owns everything about an active recording: the CameraX binding, the [VideoCapture] use
 * case, the [Recording], the output file descriptor and the recording state.
 *
 * Nothing here depends on MainActivity. The Activity may be destroyed, removed from
 * Recents or never opened at all - the service keeps recording until it is told to stop.
 *
 * All work happens on the main thread: CameraX delivers its callbacks on the executor we
 * hand it (the main executor), and every state transition is made from those callbacks or
 * from `onStartCommand`. That single-threaded discipline is what makes the state guards
 * below sufficient, with no locks.
 */
class RecordingService : LifecycleService() {

    private lateinit var settings: SettingsRepository
    private lateinit var storage: VideoStorageManager
    private lateinit var cameras: CameraRepository

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var outputPfd: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var outputName: String? = null

    /** Set when the user asks to stop while the camera is still opening. */
    private var stopRequestedWhileStarting = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var orientationListener: OrientationEventListener? = null
    private var deviceRotation = Surface.ROTATION_0

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Guards against a camera that never opens. CameraX can sit in STARTING indefinitely
     * when another app is holding the camera and never releases it; without this the
     * service would stay foreground forever and the UI would be stuck on "preparing".
     */
    private val startTimeout = Runnable {
        if (RecordingStateHolder.current.state == RecordingState.STARTING) {
            Log.e(TAG, "Camera did not start within " + START_TIMEOUT_MS + " ms; giving up")
            fail(getString(R.string.error_camera_busy))
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        storage = VideoStorageManager(this, settings)
        cameras = CameraRepository(this)
        RecordingNotifications.ensureChannels(this)
        startOrientationTracking()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        Log.i(TAG, "onStartCommand action=" + action + " state=" + RecordingStateHolder.current.state)

        // This service is always started with startForegroundService()/getForegroundService(),
        // so Android requires startForeground() within ~5 seconds on *every* start path -
        // including the ones that immediately stop again.
        if (!promoteToForeground()) {
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> handleStop()
            ACTION_TOGGLE -> handleToggle()
            else -> {
                Log.w(TAG, "Unknown action " + action + "; stopping")
                if (RecordingStateHolder.current.state != RecordingState.RECORDING) shutdown()
            }
        }

        // NOT_STICKY: if Android kills the service we must not be silently restarted with a
        // null intent and start recording behind the user's back.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy state=" + RecordingStateHolder.current.state)
        mainHandler.removeCallbacks(startTimeout)
        orientationListener?.disable()
        orientationListener = null

        // If Android tears the service down mid-recording, still ask CameraX to finalize so
        // the MP4 header gets written instead of leaving a truncated file behind.
        recording?.let {
            Log.w(TAG, "Service destroyed while a recording was active; stopping it")
            runCatching { it.stop() }
        }
        recording = null

        releaseWakeLock()
        closeOutput()
        unbindCamera()

        val state = RecordingStateHolder.current.state
        if (state == RecordingState.STARTING ||
            state == RecordingState.RECORDING ||
            state == RecordingState.STOPPING
        ) {
            RecordingStateHolder.set(this, RecordingStatus(RecordingState.IDLE))
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ actions

    private fun handleToggle() {
        when (RecordingStateHolder.current.state) {
            RecordingState.IDLE, RecordingState.ERROR -> handleStart()
            RecordingState.RECORDING -> handleStop()
            RecordingState.STARTING -> {
                // Second tap while the camera is still opening: remember it and stop as soon
                // as the recording actually begins. Stopping earlier would leave CameraX
                // half-initialised.
                Log.i(TAG, "Toggle during STARTING; will stop once recording begins")
                stopRequestedWhileStarting = true
            }
            RecordingState.STOPPING -> Log.i(TAG, "Toggle ignored: already stopping")
        }
    }

    private fun handleStart() {
        val state = RecordingStateHolder.current.state
        if (state != RecordingState.IDLE && state != RecordingState.ERROR) {
            Log.w(TAG, "Start ignored: already in state " + state)
            return
        }
        stopRequestedWhileStarting = false
        RecordingNotifications.clearError(this)

        if (!hasPermission(Manifest.permission.CAMERA)) {
            fail(getString(R.string.error_no_camera_permission))
            return
        }
        storage.folderError()?.let {
            fail(getString(it.messageRes))
            return
        }

        RecordingStateHolder.set(this, RecordingStatus(RecordingState.STARTING))
        promoteToForeground()
        mainHandler.postDelayed(startTimeout, START_TIMEOUT_MS)
        bindCameraAndRecord()
    }

    private fun handleStop() {
        when (RecordingStateHolder.current.state) {
            RecordingState.RECORDING -> {
                val active = recording
                if (active == null) {
                    Log.w(TAG, "RECORDING with no Recording object; cleaning up")
                    finishToIdle()
                    return
                }
                RecordingStateHolder.update(this) { it.copy(state = RecordingState.STOPPING) }
                promoteToForeground()
                Log.i(TAG, "Stopping recording; waiting for Finalize")
                // The file is not complete until VideoRecordEvent.Finalize arrives; all
                // teardown happens there.
                try {
                    active.stop()
                } catch (t: Throwable) {
                    Log.e(TAG, "Recording.stop() threw", t)
                    fail(getString(R.string.error_camerax))
                }
            }
            RecordingState.STARTING -> {
                Log.i(TAG, "Stop during STARTING; will stop once recording begins")
                stopRequestedWhileStarting = true
            }
            RecordingState.STOPPING -> Log.i(TAG, "Stop ignored: already stopping")
            RecordingState.IDLE, RecordingState.ERROR -> {
                Log.i(TAG, "Stop with nothing to stop; shutting the service down")
                shutdown()
            }
        }
    }

    // ------------------------------------------------------------------ camera

    private fun bindCameraAndRecord() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (RecordingStateHolder.current.state != RecordingState.STARTING) {
                Log.w(TAG, "Camera provider ready but state changed; aborting start")
                return@addListener
            }
            try {
                val provider = future.get()
                cameraProvider = provider

                val selector = cameras.resolveSelector(
                    provider,
                    settings.cameraId,
                    settings.lensFacing,
                )
                val quality = resolveQuality(provider, selector)
                Log.i(TAG, "Binding camera with quality=" + quality.label)

                val recorderBuilder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            quality.quality,
                            // If the exact quality becomes unavailable at bind time, take the
                            // nearest lower one rather than failing the recording.
                            FallbackStrategy.lowerQualityOrHigherThan(quality.quality),
                        )
                    )

                // "Standard" returns null here, which leaves the device's own tuned
                // bitrate in place instead of second-guessing it.
                val compression = CompressionOption.fromKey(settings.compressionKey)
                compression.bitRateFor(quality)?.let { bitRate ->
                    Log.i(TAG, "Target video bitrate " + bitRate + " bps (" + compression.key + ")")
                    recorderBuilder.setTargetVideoEncodingBitRate(bitRate)
                }

                val captureBuilder = VideoCapture.Builder(recorderBuilder.build())
                applyFrameRate(captureBuilder)
                val capture = captureBuilder.build()
                // Set before binding so the MP4 rotation matches how the phone is held.
                capture.targetRotation = deviceRotation

                // Only VideoCapture is bound: there is no Preview because there is no UI
                // while recording in the background.
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, capture)
                videoCapture = capture

                beginRecording(capture)
            } catch (t: Throwable) {
                Log.e(TAG, "Camera initialisation failed", t)
                fail(describeCameraError(t))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun resolveQuality(
        provider: ProcessCameraProvider,
        selector: CameraSelector,
    ): VideoQualityOption {
        val desired = VideoQualityOption.fromKey(settings.qualityKey)
        val supported = cameras.supportedQualitiesFor(provider, selector)
        if (supported.isEmpty()) {
            Log.w(TAG, "Camera reported no qualities; using " + desired.label + " and letting CameraX fall back")
            return desired
        }
        val chosen = VideoQualityOption.closest(desired, supported) ?: desired
        if (chosen != desired) {
            Log.w(TAG, "Quality " + desired.label + " unsupported; using " + chosen.label)
            settings.qualityKey = chosen.key
        }
        return chosen
    }

    /**
     * Frame-rate request, via CameraX's own `setTargetFrameRate`.
     *
     * This is a request, not a guarantee: the device may clamp it, typically in low light
     * or when the chosen quality has no matching camcorder profile. We only offer rates
     * the camera advertises (see CameraRepository.readFrameRates) and never assume the
     * request was honoured, so the setting stays optional and defaults to Auto.
     */
    private fun applyFrameRate(builder: VideoCapture.Builder<Recorder>) {
        val fps = settings.frameRate
        if (fps == SettingsRepository.FPS_AUTO) return
        try {
            builder.setTargetFrameRate(Range(fps, fps))
            Log.i(TAG, "Requested " + fps + " fps")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not request " + fps + " fps; leaving it to the device", t)
        }
    }

    private fun beginRecording(capture: VideoCapture<Recorder>) {
        when (val output = storage.createOutput()) {
            is StorageResult.Failure -> fail(getString(output.error.messageRes))
            is StorageResult.Success -> {
                outputPfd = output.pfd
                outputUri = output.documentUri
                outputName = output.displayName

                val options = FileDescriptorOutputOptions.Builder(output.pfd).build()
                var pending: PendingRecording = capture.output.prepareRecording(this, options)
                pending = applyAudio(pending)

                recording = try {
                    pending.start(ContextCompat.getMainExecutor(this), ::onRecordEvent)
                } catch (t: Throwable) {
                    Log.e(TAG, "Recording.start() failed", t)
                    fail(describeCameraError(t))
                    return
                }
                Log.i(TAG, "Recording requested for " + output.documentUri)
            }
        }
    }

    /**
     * Enables audio when the user wants it and the permission is there. A missing
     * RECORD_AUDIO permission downgrades to a silent video instead of failing - the video
     * is the part the user asked for.
     */
    // Lint cannot see the permission check through hasPermission(); the call below is
    // guarded both by that check and by the catch clause.
    @SuppressLint("MissingPermission")
    private fun applyAudio(pending: PendingRecording): PendingRecording {
        if (!settings.audioEnabled) {
            Log.i(TAG, "Audio disabled by setting")
            return pending
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            Log.w(TAG, "RECORD_AUDIO not granted; recording without sound")
            return pending
        }
        return try {
            pending.withAudioEnabled()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not enable audio; recording without sound", t)
            pending
        }
    }

    private fun onRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> onRecordingStarted()
            is VideoRecordEvent.Finalize -> onRecordingFinalized(event)
            // Status fires continuously while recording; logging it would flood logcat.
            is VideoRecordEvent.Status -> Unit
            else -> Log.i(TAG, "VideoRecordEvent: " + event.javaClass.simpleName)
        }
    }

    private fun onRecordingStarted() {
        Log.i(TAG, "Recording started -> " + outputUri)
        mainHandler.removeCallbacks(startTimeout)
        acquireWakeLock()
        RecordingStateHolder.set(
            this,
            RecordingStatus(
                state = RecordingState.RECORDING,
                startedAtElapsedMs = SystemClock.elapsedRealtime(),
                fileName = outputName,
            ),
        )
        promoteToForeground()

        if (stopRequestedWhileStarting) {
            stopRequestedWhileStarting = false
            Log.i(TAG, "Applying the stop that was requested during start-up")
            handleStop()
        }
    }

    private fun onRecordingFinalized(event: VideoRecordEvent.Finalize) {
        val uri = outputUri
        if (event.hasError()) {
            Log.e(
                TAG,
                "Finalize error=" + event.error + " uri=" + uri + " cause=" + event.cause?.message,
                event.cause,
            )
        } else {
            Log.i(TAG, "Finalize OK uri=" + uri + " size=" + (uri?.let { storage.sizeOf(it) }))
        }

        releaseWakeLock()
        recording = null
        closeOutput()
        unbindCamera()

        // A zero-length file is never a usable video, whatever the reported error was.
        val size = uri?.let { storage.sizeOf(it) }
        val emptyFile = size != null && size <= 0L
        val unusable = event.error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA || emptyFile
        if (unusable && uri != null) {
            storage.deleteQuietly(uri)
        }

        if (event.hasError()) {
            val message = describeFinalizeError(event.error)
            RecordingStateHolder.set(
                this,
                RecordingStatus(state = RecordingState.ERROR, errorMessage = message),
            )
            RecordingNotifications.showError(this, message)
        } else {
            RecordingStateHolder.set(
                this,
                RecordingStatus(state = RecordingState.IDLE, fileName = outputName),
            )
        }

        outputUri = null
        outputName = null
        shutdown()
    }

    // ------------------------------------------------------------------ foreground

    /**
     * Moves the service into the foreground. Returns false when Android refused, which is
     * the only case where the service must give up immediately.
     */
    private fun promoteToForeground(): Boolean {
        val status = RecordingStateHolder.current
        val notification = when (status.state) {
            RecordingState.RECORDING -> RecordingNotifications.recording(this, status.startedAtElapsedMs)
            RecordingState.STOPPING -> RecordingNotifications.stopping(this)
            else -> RecordingNotifications.preparing(this)
        }
        return try {
            ServiceCompat.startForeground(this, RecordingNotifications.NOTIFICATION_ID, notification, foregroundTypes())
            true
        } catch (t: Throwable) {
            // ForegroundServiceStartNotAllowedException (Android 12+) or a
            // SecurityException about a missing foreground-service-type permission.
            Log.e(TAG, "startForeground() was refused by the system", t)
            RecordingStateHolder.set(
                this,
                RecordingStatus(
                    state = RecordingState.ERROR,
                    errorMessage = getString(R.string.error_service_start),
                ),
            )
            RecordingNotifications.showError(this, getString(R.string.error_service_start))
            stopSelf()
            false
        }
    }

    /**
     * The microphone type is only declared when audio is actually going to be captured:
     * on Android 14+ declaring a type whose permission is missing throws.
     */
    private fun foregroundTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (settings.audioEnabled && hasPermission(Manifest.permission.RECORD_AUDIO)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return types
    }

    private fun shutdown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finishToIdle() {
        closeOutput()
        unbindCamera()
        RecordingStateHolder.set(this, RecordingStatus(RecordingState.IDLE))
        shutdown()
    }

    private fun fail(message: String) {
        Log.e(TAG, "Recording failed: " + message)
        mainHandler.removeCallbacks(startTimeout)
        releaseWakeLock()
        recording = null
        val uri = outputUri
        closeOutput()
        // Nothing was ever written to this file, so do not leave an empty stub behind.
        if (uri != null) storage.deleteQuietly(uri)
        outputUri = null
        outputName = null
        unbindCamera()
        RecordingStateHolder.set(
            this,
            RecordingStatus(state = RecordingState.ERROR, errorMessage = message),
        )
        RecordingNotifications.showError(this, message)
        shutdown()
    }

    // ------------------------------------------------------------------ resources

    private fun unbindCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (t: Throwable) {
            Log.w(TAG, "unbindAll() failed", t)
        }
        videoCapture = null
        cameraProvider = null
    }

    /** Closes our descriptor exactly once; CameraX holds its own duplicate. */
    private fun closeOutput() {
        storage.closeQuietly(outputPfd)
        outputPfd = null
    }

    /**
     * A PARTIAL_WAKE_LOCK held only while a recording is active.
     *
     * Why it is here: with the screen off, many devices suspend the application processor.
     * The camera and the encoder keep their own kernel wake locks, but the app-side plumbing
     * that feeds and drains them does not, and on a number of devices that is enough to
     * stall or truncate a long screen-off recording. This is the minimum that makes the
     * "lock the phone for five minutes and still get a complete file" requirement hold.
     *
     * It is PARTIAL only: it never turns the screen on, never keeps the screen on, and does
     * not stop the device from locking. It is acquired on the Start event and released on
     * Finalize, so it cannot outlive a recording. Set [USE_WAKE_LOCK] to false to test how
     * a given device behaves without it.
     */
    private fun acquireWakeLock() {
        if (!USE_WAKE_LOCK) return
        if (wakeLock?.isHeld == true) return
        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                // A hard cap, so a lost Finalize can never leak the lock indefinitely.
                acquire(MAX_WAKE_LOCK_MS)
            }
            Log.i(TAG, "Wake lock acquired")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not acquire wake lock; recording anyway", t)
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        try {
            if (lock.isHeld) {
                lock.release()
                Log.i(TAG, "Wake lock released")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not release wake lock", t)
        }
    }

    /**
     * Tracks the physical orientation of the device so a background recording is stored
     * the right way up. The display's own rotation is useless here: the screen may be off
     * or rotation-locked while recording.
     */
    private fun startOrientationTracking() {
        deviceRotation = currentDisplayRotation()
        val listener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when {
                    orientation >= 315 || orientation < 45 -> Surface.ROTATION_0
                    orientation < 135 -> Surface.ROTATION_270
                    orientation < 225 -> Surface.ROTATION_180
                    else -> Surface.ROTATION_90
                }
                if (rotation != deviceRotation) {
                    deviceRotation = rotation
                    // CameraX applies this to the next recording; an in-flight recording
                    // deliberately keeps the orientation it started with.
                    videoCapture?.targetRotation = rotation
                }
            }
        }
        if (listener.canDetectOrientation()) {
            listener.enable()
            orientationListener = listener
        } else {
            Log.w(TAG, "No orientation sensor; using the display rotation instead")
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int = try {
        val window = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        window.defaultDisplay.rotation
    } catch (t: Throwable) {
        Surface.ROTATION_0
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ errors

    private fun describeCameraError(t: Throwable): String = when {
        t is SecurityException -> getString(R.string.error_no_camera_permission)
        t.javaClass.simpleName == "CameraUnavailableException" -> getString(R.string.error_camera_busy)
        t is IllegalArgumentException -> getString(R.string.error_camera_unavailable)
        else -> getString(R.string.error_camerax)
    }

    private fun describeFinalizeError(error: Int): String = when (error) {
        VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> getString(R.string.error_low_space)
        VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> getString(R.string.error_size_limit)
        VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> getString(R.string.error_no_valid_data)
        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> getString(R.string.error_source_inactive)
        VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS -> getString(R.string.error_open_file)
        VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> getString(R.string.error_encoding)
        VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR -> getString(R.string.error_camerax)
        else -> getString(R.string.error_finalize_unknown)
    }

    companion object {
        private const val TAG = "RecordingService"

        const val ACTION_START = "com.example.videorecorder.action.START"
        const val ACTION_STOP = "com.example.videorecorder.action.STOP"
        const val ACTION_TOGGLE = "com.example.videorecorder.action.TOGGLE"

        /** See [acquireWakeLock]. Flip to false to measure a device without it. */
        private const val USE_WAKE_LOCK = true
        private const val WAKE_LOCK_TAG = "RecorderApp::Recording"
        private const val MAX_WAKE_LOCK_MS = 4L * 60L * 60L * 1000L

        /** How long the camera gets to produce its first frame before we give up. */
        private const val START_TIMEOUT_MS = 15_000L

        fun intent(context: Context, action: String): Intent =
            Intent(context, RecordingService::class.java).setAction(action)

        /** Starts the service from a context that is allowed to do so (the Activity). */
        fun send(context: Context, action: String) {
            try {
                ContextCompat.startForegroundService(context, intent(context, action))
            } catch (t: Throwable) {
                Log.e(TAG, "Could not start the recording service", t)
            }
        }
    }
}
