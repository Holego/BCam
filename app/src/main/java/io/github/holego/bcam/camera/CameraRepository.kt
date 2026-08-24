package io.github.holego.bcam.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Enumerates the cameras this device exposes and turns them into something the settings
 * screen can show.
 *
 * ## Limitation: physical (sub-)lenses
 * CameraX can only bind cameras that [ProcessCameraProvider.getAvailableCameraInfos]
 * reports. On many modern phones the OEM hides the ultra-wide / telephoto modules behind
 * a single logical camera; those sub-cameras are then only reachable through
 * CameraCharacteristics.getPhysicalCameraIds(), and CameraX has no supported way to bind
 * a physical sub-camera on its own.
 *
 * So: when the OEM publishes the extra lenses as separate camera ids (a lot of devices
 * do), they appear here and are individually selectable. When it does not, the list just
 * contains the logical back and front cameras. We deliberately do not try to force a
 * physical id through private APIs - that breaks across vendors and Android versions,
 * which is exactly the kind of unofficial workaround this app avoids.
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class CameraRepository(private val context: Context) {

    /** Everything CameraX will let us bind, labelled for humans. */
    suspend fun loadCameras(): List<CameraInfoModel> {
        val provider = awaitProvider()
        val infos = provider.availableCameraInfos
        Log.i(TAG, "CameraX reports " + infos.size + " bindable camera(s)")

        // Reading characteristics and camcorder profiles for every lens is enough work to
        // stutter the first frame of the settings screen, so keep it off the main thread.
        val labelled = withContext(Dispatchers.Default) {
            val raw = infos.mapNotNull { info ->
                try {
                    readCamera(info)
                } catch (t: Throwable) {
                    Log.w(TAG, "Skipping a camera that could not be inspected", t)
                    null
                }
            }
            labelAll(raw, infos)
        }
        labelled.forEach {
            Log.i(
                TAG,
                "camera id=" + it.cameraId + " facing=" + it.lensFacing + " name=" + it.displayName +
                    " qualities=" + it.supportedQualities.map { q -> q.label } +
                    " fps=" + it.supportedFrameRates
            )
        }
        return labelled
    }

    /**
     * Turns the persisted selection into a [CameraSelector], falling back to the default
     * back camera when the stored camera no longer exists (different device, OEM update,
     * camera disabled by policy).
     */
    fun resolveSelector(
        provider: ProcessCameraProvider,
        cameraId: String?,
        lensFacing: Int,
    ): CameraSelector {
        val available = provider.availableCameraInfos

        if (!cameraId.isNullOrBlank()) {
            val byId = selectorForCameraId(cameraId)
            if (matches(byId, available) == 1) {
                Log.i(TAG, "Using camera id=" + cameraId)
                return byId
            }
            Log.w(TAG, "Camera id=" + cameraId + " is gone; falling back to lens facing " + lensFacing)
        }

        val byFacing = selectorForFacing(lensFacing)
        if (matches(byFacing, available) >= 1) {
            Log.i(TAG, "Using default camera for lens facing " + lensFacing)
            return byFacing
        }

        Log.w(TAG, "No camera for lens facing " + lensFacing + "; falling back to default back camera")
        return CameraSelector.DEFAULT_BACK_CAMERA
    }

    /** Qualities the camera behind [selector] supports; empty when it cannot be determined. */
    fun supportedQualitiesFor(
        provider: ProcessCameraProvider,
        selector: CameraSelector,
    ): List<VideoQualityOption> {
        val info = runCatching { selector.filter(provider.availableCameraInfos).firstOrNull() }
            .getOrNull() ?: return emptyList()
        return readQualities(info)
    }

    // ---------------------------------------------------------------- internals

    private suspend fun awaitProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    continuation.resume(future.get())
                } catch (t: Throwable) {
                    continuation.resumeWithException(t)
                }
            }, ContextCompat.getMainExecutor(context))
        }

    private fun readCamera(info: CameraInfo): CameraInfoModel {
        val camera2 = Camera2CameraInfo.from(info)
        val id = camera2.cameraId
        // Camera2's LENS_FACING constants share their numeric values with CameraSelector's,
        // so this value can be stored and compared against CameraSelector.LENS_FACING_*.
        val facing = camera2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
            ?: CameraSelector.LENS_FACING_BACK
        val focal = camera2
            .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.minOrNull()
        val sensorOrientation =
            camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION)

        return CameraInfoModel(
            cameraId = id,
            lensFacing = facing,
            displayName = id, // replaced by labelAll()
            focalLengthMm = focal,
            sensorOrientation = sensorOrientation,
            supportedQualities = readQualities(info),
            supportedFrameRates = readFrameRates(camera2),
        )
    }

    private fun readQualities(info: CameraInfo): List<VideoQualityOption> = try {
        Recorder.getVideoCapabilities(info)
            .getSupportedQualities(DynamicRange.SDR)
            .mapNotNull { VideoQualityOption.fromQuality(it) }
            .sortedBy { it.rank }
    } catch (t: Throwable) {
        Log.w(TAG, "Could not read supported qualities", t)
        emptyList()
    }

    /**
     * Frame rates the camera advertises as an achievable auto-exposure target.
     *
     * Only 30 and 60 are offered: they are the two rates a user meaningfully chooses
     * between. See RecordingService.applyFrameRate for why applying this is best-effort.
     */
    private fun readFrameRates(camera2: Camera2CameraInfo): List<Int> = try {
        val ranges: Array<Range<Int>> = camera2.getCameraCharacteristic(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        ) ?: emptyArray()
        CANDIDATE_FRAME_RATES.filter { fps -> ranges.any { it.upper == fps } }
    } catch (t: Throwable) {
        Log.w(TAG, "Could not read auto-exposure frame-rate ranges", t)
        emptyList()
    }

    /** Assigns "Back Main" / "Back Ultrawide" / "Front" style names. */
    private fun labelAll(
        cameras: List<CameraInfoModel>,
        infos: List<CameraInfo>,
    ): List<CameraInfoModel> {
        // The "main" lens of each side is, by definition, whatever CameraX picks for the
        // DEFAULT_BACK/FRONT selector. Every other lens is described relative to it.
        val mainBackId = idOf(CameraSelector.DEFAULT_BACK_CAMERA, infos)
        val mainFrontId = idOf(CameraSelector.DEFAULT_FRONT_CAMERA, infos)

        val named = cameras.groupBy { it.lensFacing }.flatMap { entry ->
            val facing = entry.key
            val group = entry.value
            val side = when (facing) {
                CameraSelector.LENS_FACING_FRONT -> "Front"
                CameraSelector.LENS_FACING_BACK -> "Back"
                else -> "External"
            }
            if (group.size == 1) {
                listOf(group[0].copy(displayName = side))
            } else {
                val mainId =
                    if (facing == CameraSelector.LENS_FACING_FRONT) mainFrontId else mainBackId
                val mainFocal = group.firstOrNull { it.cameraId == mainId }?.focalLengthMm
                group.map { it.copy(displayName = describe(side, it, mainId, mainFocal)) }
            }
        }

        // Two lenses can end up with the same description; disambiguate with the camera id
        // so the user is never shown two identical rows.
        val counts = named.groupingBy { it.displayName }.eachCount()
        return named
            .map {
                if (counts.getValue(it.displayName) > 1) {
                    it.copy(displayName = it.displayName + " (id " + it.cameraId + ")")
                } else {
                    it
                }
            }
            .sortedWith(
                compareBy({ it.lensFacing != CameraSelector.LENS_FACING_BACK }, { it.cameraId })
            )
    }

    private fun describe(
        side: String,
        camera: CameraInfoModel,
        mainId: String?,
        mainFocal: Float?,
    ): String {
        if (camera.cameraId == mainId) return side + " Main"
        val focal = camera.focalLengthMm
        if (focal == null || mainFocal == null || mainFocal <= 0f) {
            return side + " (id " + camera.cameraId + ")"
        }
        val ratio = focal / mainFocal
        return when {
            ratio <= ULTRAWIDE_MAX_RATIO -> side + " Ultrawide"
            ratio >= TELEPHOTO_MIN_RATIO -> side + " Telephoto"
            else -> side + " " + String.format(Locale.US, "%.1f", focal) + "mm"
        }
    }

    private fun idOf(selector: CameraSelector, infos: List<CameraInfo>): String? = runCatching {
        selector.filter(infos).firstOrNull()?.let { Camera2CameraInfo.from(it).cameraId }
    }.getOrNull()

    private fun matches(selector: CameraSelector, infos: List<CameraInfo>): Int =
        runCatching { selector.filter(infos).size }.getOrDefault(0)

    private fun selectorForFacing(lensFacing: Int): CameraSelector =
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

    private fun selectorForCameraId(cameraId: String): CameraSelector =
        CameraSelector.Builder()
            .addCameraFilter { infos ->
                infos.filter {
                    runCatching { Camera2CameraInfo.from(it).cameraId }.getOrNull() == cameraId
                }
            }
            .build()

    companion object {
        const val TAG = "CameraManager"

        private val CANDIDATE_FRAME_RATES = listOf(30, 60)
        private const val ULTRAWIDE_MAX_RATIO = 0.7f
        private const val TELEPHOTO_MIN_RATIO = 1.4f
    }
}
