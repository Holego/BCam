package io.github.holego.bcam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.holego.bcam.camera.CameraInfoModel
import io.github.holego.bcam.camera.CameraRepository
import io.github.holego.bcam.camera.CompressionOption
import io.github.holego.bcam.camera.VideoQualityOption
import io.github.holego.bcam.databinding.ActivityMainBinding
import io.github.holego.bcam.recording.RecordingService
import io.github.holego.bcam.recording.RecordingState
import io.github.holego.bcam.recording.RecordingStateHolder
import io.github.holego.bcam.recording.RecordingStatus
import io.github.holego.bcam.recording.formatDuration
import io.github.holego.bcam.settings.SettingsRepository
import io.github.holego.bcam.storage.VideoStorageManager
import io.github.holego.bcam.widget.RecordingWidgetProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Setup and manual start/stop.
 *
 * This screen only configures and commands; it never touches the camera. Closing it, or
 * having Android destroy it, has no effect on a running recording.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsRepository
    private lateinit var storage: VideoStorageManager
    private lateinit var cameraRepository: CameraRepository

    private var cameras: List<CameraInfoModel> = emptyList()

    /**
     * Cached because the status line re-renders once a second: checking the folder hits
     * the content provider over binder, which has no business running on the main thread
     * at 1 Hz. Refreshed by [refreshRows], i.e. on every start and after every change.
     */
    private var folderUsable = false

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        storage.persistFolderPermission(uri)
        settings.folderUri = uri
        Log.i(TAG, "Folder selected: " + uri)
        // The widget's behaviour depends on whether setup is complete.
        RecordingWidgetProvider.refresh(this)
        refreshRows()
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(TAG, "CAMERA permission granted=" + granted)
        RecordingWidgetProvider.refresh(this)
        if (granted) loadCameras()
        refreshRows()
    }

    private val audioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(TAG, "RECORD_AUDIO permission granted=" + granted)
        if (!granted) {
            toast(getString(R.string.audio_permission_denied))
        }
        refreshRows()
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(TAG, "POST_NOTIFICATIONS granted=" + granted)
        // Recording works either way; without it the ongoing notification is just hidden.
        beginRecording()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsRepository(this)
        storage = VideoStorageManager(this, settings)
        cameraRepository = CameraRepository(this)

        binding.rowFolder.setOnClickListener { folderPicker.launch(null) }
        binding.rowCamera.setOnClickListener { showCameraDialog() }
        binding.rowQuality.setOnClickListener { showQualityDialog() }
        binding.rowCompression.setOnClickListener { showCompressionDialog() }
        binding.rowFps.setOnClickListener { showFrameRateDialog() }
        binding.rowAudio.setOnClickListener { binding.audioSwitch.toggle() }
        binding.audioSwitch.setOnCheckedChangeListener { _, checked -> onAudioToggled(checked) }
        binding.primaryButton.setOnClickListener { onPrimaryClick() }
        binding.rowLanguage.setOnClickListener { showLanguageDialog() }
        binding.rowAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        handleSetupHint(intent)
        loadCameras()
        observeRecordingState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSetupHint(intent)
    }

    override fun onStart() {
        super.onStart()
        // Permissions and the folder can change while the app is in the background.
        refreshRows()
    }

    /** The widget sends the user here when the app has not been fully configured yet. */
    private fun handleSetupHint(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SETUP_REQUIRED, false) == true) {
            toast(getString(R.string.finish_setup_first))
        }
    }

    // ------------------------------------------------------------------ state

    private fun observeRecordingState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    RecordingStateHolder.status.collect { render(it) }
                }
                launch {
                    // Keeps the elapsed-time readout moving while the screen is visible.
                    while (isActive) {
                        render(RecordingStateHolder.current)
                        delay(1000L)
                    }
                }
            }
        }
    }

    private fun render(status: RecordingStatus) {
        binding.statusText.text = statusTextFor(status)

        val busy = status.state == RecordingState.STARTING || status.state == RecordingState.STOPPING
        binding.primaryButton.isEnabled = !busy
        binding.primaryButton.setText(
            if (status.state == RecordingState.RECORDING) R.string.button_stop else R.string.button_start
        )
        // Settings must not change under a running recording.
        val configurable = status.state == RecordingState.IDLE || status.state == RecordingState.ERROR
        listOf(
            binding.rowFolder,
            binding.rowCamera,
            binding.rowQuality,
            binding.rowCompression,
            binding.rowFps,
            binding.rowAudio,
        )
            .forEach { it.isEnabled = configurable }
        binding.audioSwitch.isEnabled = configurable
    }

    private fun statusTextFor(status: RecordingStatus): String = when (status.state) {
        RecordingState.RECORDING ->
            getString(R.string.status_recording, formatDuration(status.elapsedMs()))
        RecordingState.STARTING -> getString(R.string.status_starting)
        RecordingState.STOPPING -> getString(R.string.status_stopping)
        RecordingState.ERROR -> status.errorMessage ?: getString(R.string.error_camerax)
        RecordingState.IDLE -> when {
            !hasPermission(Manifest.permission.CAMERA) -> getString(R.string.status_need_camera)
            !folderUsable -> getString(R.string.status_need_folder)
            else -> getString(R.string.status_idle)
        }
    }

    private fun refreshRows() {
        folderUsable = storage.hasUsableFolder()
        binding.folderValue.text =
            storage.folderDisplayName() ?: getString(R.string.value_not_selected)

        val selected = selectedCamera()
        binding.cameraValue.text = selected?.displayName ?: getString(R.string.value_default_camera)

        binding.qualityValue.text = VideoQualityOption.fromKey(settings.qualityKey).label
        binding.compressionValue.text =
            getString(CompressionOption.fromKey(settings.compressionKey).labelRes)

        val rates = selected?.supportedFrameRates.orEmpty()
        // The row only exists when the camera actually advertises a choice.
        binding.rowFps.visibility = if (rates.isEmpty()) View.GONE else View.VISIBLE
        binding.fpsValue.text = frameRateLabel(settings.frameRate)

        binding.audioSwitch.setOnCheckedChangeListener(null)
        binding.audioSwitch.isChecked = settings.audioEnabled
        binding.audioSwitch.setOnCheckedChangeListener { _, checked -> onAudioToggled(checked) }
        binding.audioHint.visibility =
            if (settings.audioEnabled && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
                View.VISIBLE
            } else {
                View.GONE
            }

        binding.languageValue.text = languageLabel(currentLanguageTag())

        render(RecordingStateHolder.current)
    }

    // ------------------------------------------------------------------ actions

    private fun onPrimaryClick() {
        when (RecordingStateHolder.current.state) {
            RecordingState.RECORDING -> {
                Log.i(TAG, "Stop requested from the app")
                RecordingService.send(this, RecordingService.ACTION_STOP)
            }
            RecordingState.STARTING, RecordingState.STOPPING -> Unit // button is disabled
            RecordingState.IDLE, RecordingState.ERROR -> startRecordingWithChecks()
        }
    }

    private fun startRecordingWithChecks() {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        if (!storage.hasUsableFolder()) {
            val error = storage.folderError()
            if (error != null) toast(getString(error.messageRes))
            folderPicker.launch(null)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            // Asked here rather than on first launch: this is the only moment it matters.
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        beginRecording()
    }

    private fun beginRecording() {
        Log.i(TAG, "Start requested from the app")
        RecordingService.send(this, RecordingService.ACTION_START)
    }

    private fun onAudioToggled(checked: Boolean) {
        settings.audioEnabled = checked
        Log.i(TAG, "Audio setting = " + checked)
        if (checked && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            refreshRows()
        }
    }

    // ------------------------------------------------------------------ dialogs

    private fun showCameraDialog() {
        if (cameras.isEmpty()) {
            toast(getString(R.string.error_camera_unavailable))
            return
        }
        val labels = cameras.map { it.displayName }.toTypedArray()
        val checked = cameras.indexOfFirst { it.cameraId == settings.cameraId }
        AlertDialog.Builder(this)
            .setTitle(R.string.row_camera)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val camera = cameras[which]
                settings.cameraId = camera.cameraId
                settings.lensFacing = camera.lensFacing
                Log.i(TAG, "Camera selected: " + camera.displayName + " id=" + camera.cameraId)
                alignQualityWith(camera)
                alignFrameRateWith(camera)
                dialog.dismiss()
                refreshRows()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showQualityDialog() {
        val options = qualityOptions()
        if (options.isEmpty()) {
            toast(getString(R.string.error_no_qualities))
            return
        }
        val labels = options.map { it.label }.toTypedArray()
        val current = VideoQualityOption.fromKey(settings.qualityKey)
        AlertDialog.Builder(this)
            .setTitle(R.string.row_quality)
            .setSingleChoiceItems(labels, options.indexOf(current)) { dialog, which ->
                settings.qualityKey = options[which].key
                Log.i(TAG, "Quality selected: " + options[which].label)
                dialog.dismiss()
                refreshRows()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Per-app language. AppCompat applies this on Android 13+ through the platform API and
     * emulates it below that, persisting the choice itself via the
     * AppLocalesMetadataHolderService declared in the manifest. Setting it recreates the
     * Activity; a running recording is untouched because it lives in the service.
     */
    private fun showLanguageDialog() {
        val tags = listOf("", "en", "ru")
        val labels = tags.map { languageLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.row_language)
            .setSingleChoiceItems(labels, tags.indexOf(currentLanguageTag())) { dialog, which ->
                val tag = tags[which]
                Log.i(TAG, "Language selected: " + (tag.ifEmpty { "system" }))
                dialog.dismiss()
                AppCompatDelegate.setApplicationLocales(
                    if (tag.isEmpty()) {
                        LocaleListCompat.getEmptyLocaleList()
                    } else {
                        LocaleListCompat.forLanguageTags(tag)
                    }
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Language tag currently forced by the user, or "" when following the system. */
    private fun currentLanguageTag(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        val tag = if (locales.isEmpty) "" else locales[0]?.language.orEmpty()
        return if (tag == "en" || tag == "ru") tag else ""
    }

    private fun languageLabel(tag: String): String = when (tag) {
        "en" -> getString(R.string.language_en)
        "ru" -> getString(R.string.language_ru)
        else -> getString(R.string.language_system)
    }

    private fun showCompressionDialog() {
        val options = CompressionOption.values()
        val labels = options.map { getString(it.labelRes) }.toTypedArray()
        val current = CompressionOption.fromKey(settings.compressionKey)
        AlertDialog.Builder(this)
            .setTitle(R.string.row_compression)
            .setSingleChoiceItems(labels, options.indexOf(current)) { dialog, which ->
                settings.compressionKey = options[which].key
                Log.i(TAG, "Compression selected: " + options[which].key)
                dialog.dismiss()
                refreshRows()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showFrameRateDialog() {
        val rates = listOf(SettingsRepository.FPS_AUTO) + selectedCamera()?.supportedFrameRates.orEmpty()
        val labels = rates.map { frameRateLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.row_fps)
            .setSingleChoiceItems(labels, rates.indexOf(settings.frameRate)) { dialog, which ->
                settings.frameRate = rates[which]
                Log.i(TAG, "Frame rate selected: " + rates[which])
                dialog.dismiss()
                refreshRows()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ helpers

    private fun loadCameras() {
        lifecycleScope.launch {
            cameras = try {
                cameraRepository.loadCameras()
            } catch (t: Throwable) {
                Log.e(TAG, "Could not enumerate cameras", t)
                emptyList()
            }
            // A stored camera can disappear (different device, OEM update).
            if (cameras.isNotEmpty() && cameras.none { it.cameraId == settings.cameraId }) {
                val fallback = cameras.first()
                Log.w(TAG, "Stored camera is gone; falling back to " + fallback.displayName)
                settings.cameraId = fallback.cameraId
                settings.lensFacing = fallback.lensFacing
                alignQualityWith(fallback)
                alignFrameRateWith(fallback)
            }
            refreshRows()
        }
    }

    private fun selectedCamera(): CameraInfoModel? =
        cameras.firstOrNull { it.cameraId == settings.cameraId } ?: cameras.firstOrNull()

    private fun qualityOptions(): List<VideoQualityOption> {
        val supported = selectedCamera()?.supportedQualities.orEmpty()
        // Before the camera list has loaded, offer everything rather than an empty dialog.
        return supported.ifEmpty { VideoQualityOption.values().toList() }
    }

    /** Keeps the stored quality valid for the camera that is now selected. */
    private fun alignQualityWith(camera: CameraInfoModel) {
        val supported = camera.supportedQualities
        if (supported.isEmpty()) return
        val desired = VideoQualityOption.fromKey(settings.qualityKey)
        val chosen = VideoQualityOption.closest(desired, supported) ?: return
        if (chosen != desired) {
            Log.w(TAG, "Quality " + desired.label + " unsupported here; using " + chosen.label)
            settings.qualityKey = chosen.key
        }
    }

    private fun alignFrameRateWith(camera: CameraInfoModel) {
        if (settings.frameRate != SettingsRepository.FPS_AUTO &&
            settings.frameRate !in camera.supportedFrameRates
        ) {
            Log.w(TAG, "Frame rate " + settings.frameRate + " unsupported here; using Auto")
            settings.frameRate = SettingsRepository.FPS_AUTO
        }
    }

    private fun frameRateLabel(fps: Int): String =
        if (fps == SettingsRepository.FPS_AUTO) getString(R.string.fps_auto) else getString(R.string.fps_value, fps)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "RecorderApp"
        const val EXTRA_SETUP_REQUIRED = "extra_setup_required"
    }
}
