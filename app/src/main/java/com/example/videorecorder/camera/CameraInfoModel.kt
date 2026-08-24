package com.example.videorecorder.camera

import androidx.annotation.StringRes
import androidx.camera.core.CameraSelector
import androidx.camera.video.Quality
import com.example.videorecorder.R

/**
 * One camera the app can actually bind to, as reported by CameraX and enriched with
 * Camera2 characteristics.
 */
data class CameraInfoModel(
    /** Camera2 id, e.g. "0". Stable across reboots, so this is what gets persisted. */
    val cameraId: String,
    /** [CameraSelector.LENS_FACING_BACK] / [CameraSelector.LENS_FACING_FRONT] / EXTERNAL. */
    val lensFacing: Int,
    /** Human label, e.g. "Back Main" or "Back Ultrawide". */
    val displayName: String,
    val focalLengthMm: Float?,
    val sensorOrientation: Int?,
    val supportedQualities: List<VideoQualityOption>,
    /** Fixed frame rates the camera advertises (a subset of 30 / 60). May be empty. */
    val supportedFrameRates: List<Int>,
)

/**
 * The video qualities CameraX can express, with a stable persistence key, a label for
 * the UI, and a rank used to pick the nearest supported alternative.
 */
enum class VideoQualityOption(
    val key: String,
    val label: String,
    val rank: Int,
    val quality: Quality,
    /**
     * Reference video bitrate for this resolution, in bits per second. Used only as the
     * baseline that [CompressionOption] scales down; [CompressionOption.STANDARD] leaves
     * the encoder on the device's own default instead.
     */
    val baseBitRate: Int,
) {
    SD("SD", "480p", 1, Quality.SD, 3_000_000),
    HD("HD", "720p", 2, Quality.HD, 6_000_000),
    FHD("FHD", "1080p", 3, Quality.FHD, 12_000_000),
    UHD("UHD", "4K", 4, Quality.UHD, 40_000_000);

    companion object {
        fun fromKey(key: String?): VideoQualityOption =
            values().firstOrNull { it.key == key } ?: FHD

        fun fromQuality(quality: Quality): VideoQualityOption? =
            values().firstOrNull { it.quality == quality }

        /**
         * Picks [desired] when the camera supports it, otherwise the closest supported
         * quality. Ties resolve downwards, because falling back to a lower resolution is
         * always safe whereas a higher one may exceed the encoder's limits.
         */
        fun closest(desired: VideoQualityOption, supported: List<VideoQualityOption>): VideoQualityOption? {
            if (supported.isEmpty()) return null
            if (desired in supported) return desired
            return supported.minWithOrNull(
                compareBy({ kotlin.math.abs(it.rank - desired.rank) }, { it.rank })
            )
        }
    }
}

/**
 * How hard to squeeze the video.
 *
 * CameraX 1.3 records MP4/H.264 and exposes no way to pick a container or a codec, so the
 * only real lever on file size - short of dropping resolution or frame rate - is the
 * target encoding bitrate. [STANDARD] deliberately does not set one at all, leaving the
 * device's tuned default in place; the other two scale [VideoQualityOption.baseBitRate]
 * down by [factor].
 */
enum class CompressionOption(
    val key: String,
    @StringRes val labelRes: Int,
    /** null means "do not override the device default". */
    val factor: Float?,
) {
    STANDARD("STANDARD", R.string.compression_standard, null),
    LIGHT("LIGHT", R.string.compression_light, 0.60f),
    SMALL("SMALL", R.string.compression_small, 0.35f);

    /** Target bitrate for [quality], or null to leave the encoder default alone. */
    fun bitRateFor(quality: VideoQualityOption): Int? =
        factor?.let { (quality.baseBitRate * it).toInt().coerceAtLeast(MIN_BIT_RATE) }

    companion object {
        private const val MIN_BIT_RATE = 500_000

        fun fromKey(key: String?): CompressionOption =
            values().firstOrNull { it.key == key } ?: STANDARD
    }
}
