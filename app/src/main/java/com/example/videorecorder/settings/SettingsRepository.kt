package com.example.videorecorder.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.camera.core.CameraSelector

/**
 * Persisted user configuration.
 *
 * SharedPreferences rather than DataStore: every read here happens on paths that are
 * already synchronous (service start-up, widget rendering), and the whole payload is a
 * handful of scalars. DataStore would add a dependency and force those paths to become
 * suspending for no practical gain.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Tree URI of the SAF directory chosen by the user, or null if not configured yet. */
    var folderUri: Uri?
        get() = prefs.getString(KEY_FOLDER_URI, null)?.let(Uri::parse)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_FOLDER_URI) else putString(KEY_FOLDER_URI, value.toString())
        }.apply()

    /** Camera2 id of the selected camera, or null to use the default for [lensFacing]. */
    var cameraId: String?
        get() = prefs.getString(KEY_CAMERA_ID, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_CAMERA_ID) else putString(KEY_CAMERA_ID, value)
        }.apply()

    /** [CameraSelector.LENS_FACING_BACK] or [CameraSelector.LENS_FACING_FRONT]. */
    var lensFacing: Int
        get() = prefs.getInt(KEY_LENS_FACING, CameraSelector.LENS_FACING_BACK)
        set(value) = prefs.edit().putInt(KEY_LENS_FACING, value).apply()

    /** Key of the selected [androidx.camera.video.Quality]; see VideoQuality.kt. */
    var qualityKey: String
        get() = prefs.getString(KEY_QUALITY, DEFAULT_QUALITY_KEY) ?: DEFAULT_QUALITY_KEY
        set(value) = prefs.edit().putString(KEY_QUALITY, value).apply()

    /** Key of the selected [com.example.videorecorder.camera.CompressionOption]. */
    var compressionKey: String
        get() = prefs.getString(KEY_COMPRESSION, DEFAULT_COMPRESSION_KEY) ?: DEFAULT_COMPRESSION_KEY
        set(value) = prefs.edit().putString(KEY_COMPRESSION, value).apply()

    /** Target frames per second, or [FPS_AUTO] to let the device decide. */
    var frameRate: Int
        get() = prefs.getInt(KEY_FPS, FPS_AUTO)
        set(value) = prefs.edit().putInt(KEY_FPS, value).apply()

    var audioEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUDIO, true)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO, value).apply()

    fun clearFolder() {
        folderUri = null
    }

    companion object {
        const val FPS_AUTO = 0
        const val DEFAULT_QUALITY_KEY = "FHD"
        const val DEFAULT_COMPRESSION_KEY = "STANDARD"

        private const val PREFS_NAME = "background_camera_settings"
        private const val KEY_COMPRESSION = "compression"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_CAMERA_ID = "camera_id"
        private const val KEY_LENS_FACING = "lens_facing"
        private const val KEY_QUALITY = "quality"
        private const val KEY_FPS = "frame_rate"
        private const val KEY_AUDIO = "audio_enabled"
    }
}
