package com.example.videorecorder.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.util.Log
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import com.example.videorecorder.R
import com.example.videorecorder.settings.SettingsRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "RecorderApp"

/** Why an output file could not be produced. Each case carries a user-facing message. */
enum class StorageError(@StringRes val messageRes: Int) {
    NO_FOLDER(R.string.error_no_folder),
    PERMISSION_LOST(R.string.error_folder_permission_lost),
    FOLDER_MISSING(R.string.error_folder_missing),
    CREATE_FAILED(R.string.error_create_file),
    OPEN_FAILED(R.string.error_open_file),
    LOW_SPACE(R.string.error_low_space),
}

sealed interface StorageResult {
    /**
     * [pfd] is owned by the caller and must be closed once recording has finalized -
     * not before, and exactly once.
     */
    data class Success(
        val documentUri: Uri,
        val displayName: String,
        val pfd: ParcelFileDescriptor,
    ) : StorageResult

    data class Failure(val error: StorageError) : StorageResult
}

/**
 * Creates the .mp4 files inside the Storage Access Framework directory the user picked.
 *
 * Everything here is defensive on purpose: the tree URI is user-supplied, survives
 * reboots, and can stop being valid at any time (folder deleted, SD card pulled,
 * permission revoked from system settings). No path may throw into the recorder.
 */
class VideoStorageManager(
    private val context: Context,
    private val settings: SettingsRepository,
) {

    /** True when a folder is configured, still granted, and writable. */
    fun hasUsableFolder(): Boolean = folderError() == null

    /** The [StorageError] blocking recording, or null when the folder is fine. */
    fun folderError(): StorageError? {
        val treeUri = settings.folderUri ?: return StorageError.NO_FOLDER
        if (!hasPersistedPermission(treeUri)) return StorageError.PERMISSION_LOST
        val dir = documentDir(treeUri) ?: return StorageError.FOLDER_MISSING
        return if (dir.exists() && dir.isDirectory && dir.canWrite()) null else StorageError.FOLDER_MISSING
    }

    /**
     * A short, readable form of the chosen folder, e.g. "Movies/BackgroundCamera".
     * Falls back to the folder's display name when the document id is not a path.
     */
    fun folderDisplayName(): String? {
        val treeUri = settings.folderUri ?: return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        if (docId != null) {
            val path = docId.substringAfter(':', "")
            if (path.isNotBlank()) return path
        }
        return runCatching { documentDir(treeUri)?.name }.getOrNull() ?: treeUri.toString()
    }

    /** Persists read/write access so the folder still works after a reboot. */
    fun persistFolderPermission(treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
            Log.i(TAG, "Persisted folder permission for " + treeUri)
        } catch (t: Throwable) {
            // Some providers refuse to persist; the folder then only works for this session.
            Log.w(TAG, "Could not persist folder permission for " + treeUri, t)
        }
    }

    /**
     * Creates a new timestamped .mp4 and opens it for writing.
     *
     * The descriptor is opened "rw" because the MP4 muxer has to seek back and patch the
     * header when the recording finalizes; a write-only descriptor produces a file that
     * no player can open.
     */
    fun createOutput(): StorageResult {
        folderError()?.let { return StorageResult.Failure(it) }

        val treeUri = settings.folderUri ?: return StorageResult.Failure(StorageError.NO_FOLDER)
        val dir = documentDir(treeUri) ?: return StorageResult.Failure(StorageError.FOLDER_MISSING)

        val fileName = buildFileName()
        val document = try {
            dir.createFile(MIME_TYPE, fileName)
        } catch (t: Throwable) {
            Log.e(TAG, "createFile failed for " + fileName, t)
            null
        } ?: return StorageResult.Failure(StorageError.CREATE_FAILED)

        val pfd = try {
            context.contentResolver.openFileDescriptor(document.uri, "rw")
        } catch (t: Throwable) {
            Log.e(TAG, "openFileDescriptor failed for " + document.uri, t)
            null
        }
        if (pfd == null) {
            deleteQuietly(document.uri)
            return StorageResult.Failure(StorageError.OPEN_FAILED)
        }

        val free = freeBytesFor(pfd)
        if (free != null && free < MIN_FREE_BYTES) {
            Log.e(TAG, "Refusing to record: only " + free + " bytes free")
            closeQuietly(pfd)
            deleteQuietly(document.uri)
            return StorageResult.Failure(StorageError.LOW_SPACE)
        }

        val actualName = runCatching { document.name }.getOrNull() ?: fileName
        Log.i(TAG, "Output file created: " + document.uri + " name=" + actualName + " free=" + free)
        return StorageResult.Success(document.uri, actualName, pfd)
    }

    /** Removes a file that ended up with no usable content. Never throws. */
    fun deleteQuietly(uri: Uri) {
        try {
            val deleted = DocumentsContract.deleteDocument(context.contentResolver, uri)
            Log.i(TAG, "Deleted unusable output " + uri + " ok=" + deleted)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not delete " + uri, t)
        }
    }

    /** Size of a written document, or null when it cannot be determined. */
    fun sizeOf(uri: Uri): Long? = try {
        DocumentFile.fromSingleUri(context, uri)?.length()
    } catch (t: Throwable) {
        Log.w(TAG, "Could not stat " + uri, t)
        null
    }

    fun closeQuietly(pfd: ParcelFileDescriptor?) {
        if (pfd == null) return
        try {
            pfd.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Closing file descriptor failed", t)
        }
    }

    // ---------------------------------------------------------------- internals

    private fun buildFileName(): String =
        "VID_" + SimpleDateFormat(FILE_NAME_PATTERN, Locale.US).format(Date()) + ".mp4"

    private fun documentDir(treeUri: Uri): DocumentFile? =
        runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()

    private fun hasPersistedPermission(treeUri: Uri): Boolean = try {
        context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Could not read persisted permissions", t)
        false
    }

    /**
     * Free space on the volume that actually holds the new file. Asking the descriptor
     * itself is the only reliable way with SAF, since the tree URI may point at an SD
     * card or a provider that is not the primary volume.
     */
    private fun freeBytesFor(pfd: ParcelFileDescriptor): Long? = try {
        val stat = Os.fstatvfs(pfd.fileDescriptor)
        stat.f_bavail * stat.f_frsize
    } catch (t: Throwable) {
        // Cloud-backed and virtual providers have no filesystem to stat; skip the check
        // rather than blocking a recording that would have worked.
        Log.w(TAG, "Free-space check unavailable for this provider", t)
        null
    }

    companion object {
        private const val MIME_TYPE = "video/mp4"
        private const val FILE_NAME_PATTERN = "yyyy-MM-dd_HH-mm-ss"

        /** Refuse to start a recording that would immediately run out of room. */
        private const val MIN_FREE_BYTES = 200L * 1024L * 1024L
    }
}
