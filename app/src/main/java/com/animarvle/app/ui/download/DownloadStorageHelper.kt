package com.animarvle.app.ui.download

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.datasource.cache.Cache
import com.animarvle.app.R
import com.animarvle.app.ui.profile.PlayerSettingsFragment
import java.io.File

/**
 * Helper utility managing storage permissions, custom folder resolution,
 * and exporting completed video downloads (.mkv, .mp4) to user-accessible storage.
 */
object DownloadStorageHelper {

    const val STORAGE_PERMISSION_REQUEST_CODE = 1001

    /**
     * Returns an array of missing runtime permissions required for storage access and notifications.
     */
    fun getMissingPermissions(context: Context): Array<String> {
        val missing = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }

        return missing.toTypedArray()
    }

    /**
     * Checks if the required storage permissions are currently granted.
     */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            val readGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                readGranted && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            } else {
                readGranted
            }
        }
    }

    /**
     * Checks if notification permission is granted.
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Resolves the target directory where video files (.mkv, .mp4) should sit.
     * Evaluates custom SAF folder setting (KEY_DOWNLOAD_DIR_URI) first, falling back to public Downloads/Animarvle.
     */
    fun getEffectiveDownloadDirectory(context: Context): File {
        val prefs = context.getSharedPreferences(PlayerSettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val uriStr = prefs.getString(PlayerSettingsFragment.KEY_DOWNLOAD_DIR_URI, null)

        if (!uriStr.isNullOrBlank()) {
            val resolved = resolveDirFromUri(context, uriStr)
            if (resolved != null) {
                if (!resolved.exists()) {
                    runCatching { resolved.mkdirs() }
                }
                if (resolved.exists() && resolved.canWrite()) {
                    return resolved
                }
            }
        }

        // Public Downloads/Animarvle directory
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Animarvle",
        )
        if (!publicDir.exists()) {
            runCatching { publicDir.mkdirs() }
        }
        if (publicDir.exists() && publicDir.canWrite()) {
            return publicDir
        }

        // Fallback to app external files or internal files
        val ext = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.getExternalFilesDir(null)
        val fallback = if (ext != null) File(ext, "downloads") else File(context.filesDir, "downloads")
        if (!fallback.exists()) {
            runCatching { fallback.mkdirs() }
        }
        return fallback
    }

    /**
     * Resolves a Storage Access Framework (SAF) tree URI into a physical File path on disk.
     */
    fun resolveDirFromUri(context: Context, uriString: String): File? {
        return runCatching {
            val uri = Uri.parse(uriString)
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":")
            val type = parts[0]
            val subPath = if (parts.size > 1) parts[1] else ""
            if ("primary".equals(type, ignoreCase = true)) {
                val base = Environment.getExternalStorageDirectory()
                if (subPath.isNotBlank()) File(base, subPath) else base
            } else {
                File("/storage/$type", subPath)
            }
        }.getOrNull()
    }

    /**
     * Formats a friendly human-readable label for the configured download folder.
     */
    fun getFriendlyFolderPath(context: Context, uriString: String?): String {
        if (uriString.isNullOrBlank()) {
            return context.getString(R.string.settings_download_folder_internal)
        }
        return runCatching {
            val uri = Uri.parse(uriString)
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":")
            val type = parts[0]
            val subPath = if (parts.size > 1) parts[1] else ""
            if ("primary".equals(type, ignoreCase = true)) {
                if (subPath.isNotBlank()) "Internal Storage > $subPath" else "Internal Storage"
            } else {
                if (subPath.isNotBlank()) "SD Card ($type) > $subPath" else "SD Card ($type)"
            }
        }.getOrDefault(Uri.decode(uriString))
    }

    /**
     * Exports completed video download bytes from Media3 SimpleCache into a clean, standalone
     * .mkv or .mp4 file in the user's download directory, and notifies Android's MediaScanner.
     */
    fun exportCompletedDownload(
        context: Context,
        cache: Cache,
        download: androidx.media3.exoplayer.offline.Download,
        item: DownloadItem,
    ): String? {
        return runCatching {
            val ext = if (item.streamUrl.contains(".mkv", ignoreCase = true) || item.quality.contains("MKV", ignoreCase = true)) {
                "mkv"
            } else {
                "mp4"
            }

            val safeTitle = item.mediaTitle.replace(Regex("[^a-zA-Z0-9.-]"), "_")
            val fileName = "${safeTitle}_EP${item.episodeNumber}_${item.quality}.$ext"
            val targetDir = getEffectiveDownloadDirectory(context)
            val targetFile = File(targetDir, fileName)

            // Keys to check in cache
            val keysToCheck = listOfNotNull(
                download.request.customCacheKey,
                download.request.id,
                download.request.uri.toString(),
            )

            var spans: Collection<androidx.media3.datasource.cache.CacheSpan>? = null
            for (k in keysToCheck) {
                val s = runCatching { cache.getCachedSpans(k) }.getOrNull()
                if (!s.isNullOrEmpty()) {
                    spans = s
                    break
                }
            }

            if (!spans.isNullOrEmpty()) {
                val sorted = spans.sortedBy { it.position }
                targetFile.outputStream().use { out ->
                    for (span in sorted) {
                        val spanFile = span.file
                        if (spanFile != null && spanFile.exists()) {
                            spanFile.inputStream().use { input ->
                                input.copyTo(out)
                            }
                        }
                    }
                }

                if (targetFile.exists() && targetFile.length() > 0) {
                    val mime = if (ext == "mkv") "video/x-matroska" else "video/mp4"
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(targetFile.absolutePath),
                        arrayOf(mime),
                        null,
                    )
                    return targetFile.absolutePath
                }
            }

            if (targetFile.exists() && targetFile.length() > 0) {
                return targetFile.absolutePath
            }

            // Fallback to existing path if already set
            item.filePath
        }.getOrNull()
    }
}

