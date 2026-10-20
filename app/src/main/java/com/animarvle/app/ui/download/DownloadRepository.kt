package com.animarvle.app.ui.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class DownloadRepository(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val downloadDirectory: File,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val settingsPrefs = context.getSharedPreferences(
        com.animarvle.app.ui.profile.PlayerSettingsFragment.PREFS_NAME,
        Context.MODE_PRIVATE,
    )
    private val _downloadsFlow = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloadsFlow: StateFlow<List<DownloadItem>> = _downloadsFlow.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressTrackingJob: Job? = null

    fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun isWifiOnlyEnabled(): Boolean {
        return settingsPrefs.getBoolean(
            com.animarvle.app.ui.profile.PlayerSettingsFragment.KEY_WIFI_ONLY,
            true,
        )
    }

    fun canDownloadNow(): Boolean {
        if (!isWifiOnlyEnabled()) return true
        return isWifiConnected()
    }

    fun maxConcurrentDownloads(): Int {
        return settingsPrefs.getInt(
            com.animarvle.app.ui.profile.PlayerSettingsFragment.KEY_CONCURRENT_DOWNLOADS,
            1,
        )
    }

    init {
        load()
        downloadManager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(
                manager: DownloadManager,
                download: Download,
                finalException: Exception?,
            ) {
                handleDownloadChanged(download)
                checkAndManageTracking()
            }

            override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
                handleDownloadRemoved(download.request.id)
                checkAndManageTracking()
            }

            override fun onInitialized(manager: DownloadManager) {
                syncWithDownloadManager()
                checkAndManageTracking()
            }
        })
        if (downloadManager.isInitialized) {
            syncWithDownloadManager()
            checkAndManageTracking()
        }
    }

    private fun checkAndManageTracking() {
        val hasActive = downloadManager.currentDownloads.any {
            it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED || it.state == Download.STATE_RESTARTING
        }
        if (hasActive) {
            startProgressTracking()
        }
    }

    private fun startProgressTracking() {
        if (progressTrackingJob?.isActive == true) return
        progressTrackingJob = scope.launch(Dispatchers.IO) {
            var lastSampleTime = System.currentTimeMillis()
            val lastBytesMap = mutableMapOf<String, Long>()

            while (isActive) {
                delay(750L) // Poll every 750ms for live progress & speed calculation
                val activeDownloads = downloadManager.currentDownloads
                if (activeDownloads.isEmpty()) {
                    break
                }

                val now = System.currentTimeMillis()
                val deltaSec = (now - lastSampleTime).coerceAtLeast(1L) / 1000.0
                lastSampleTime = now

                val current = _downloadsFlow.value.toMutableList()
                var hasChanges = false
                var anyActive = false

                for (dl in activeDownloads) {
                    if (dl.state == Download.STATE_DOWNLOADING || dl.state == Download.STATE_QUEUED) {
                        anyActive = true
                    }
                    val idx = current.indexOfFirst { it.id == dl.request.id }
                    if (idx >= 0) {
                        val old = current[idx]
                        val bytes = dl.bytesDownloaded
                        val prevBytes = lastBytesMap[dl.request.id] ?: bytes
                        lastBytesMap[dl.request.id] = bytes

                        val deltaBytes = (bytes - prevBytes).coerceAtLeast(0L)
                        val speedBps = if (deltaSec > 0) (deltaBytes / deltaSec).toLong() else 0L
                        val speedStr = if (dl.state == Download.STATE_DOWNLOADING) formatSpeed(speedBps) else ""

                        val pct = if (dl.percentDownloaded >= 0f) dl.percentDownloaded.toInt().coerceIn(0, 100) else old.progressPercent
                        val total = if (dl.contentLength > 0) dl.contentLength else old.totalBytes

                        val status = when (dl.state) {
                            Download.STATE_QUEUED -> DownloadStatus.QUEUED
                            Download.STATE_DOWNLOADING -> DownloadStatus.DOWNLOADING
                            Download.STATE_COMPLETED -> DownloadStatus.COMPLETED
                            Download.STATE_FAILED -> DownloadStatus.FAILED
                            Download.STATE_STOPPED -> DownloadStatus.PAUSED
                            Download.STATE_RESTARTING -> DownloadStatus.DOWNLOADING
                            else -> old.status
                        }

                        if (bytes != old.downloadedBytes || pct != old.progressPercent || status != old.status || speedStr != old.speedText) {
                            current[idx] = old.copy(
                                status = status,
                                progressPercent = if (status == DownloadStatus.COMPLETED) 100 else pct,
                                downloadedBytes = bytes,
                                totalBytes = total,
                                speedText = speedStr,
                            )
                            hasChanges = true
                        }
                    }
                }

                if (hasChanges) {
                    withContext(Dispatchers.Main) {
                        _downloadsFlow.value = current
                    }
                    save(current)
                }

                if (!anyActive) {
                    break
                }
            }
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec.toDouble() / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format(Locale.US, "%.1f KB/s", bytesPerSec.toDouble() / 1024.0)
            bytesPerSec > 0 -> "$bytesPerSec B/s"
            else -> "0 KB/s"
        }
    }

    private fun handleDownloadChanged(download: Download) {
        val current = _downloadsFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == download.request.id }
        if (index >= 0) {
            val old = current[index]
            val status = when (download.state) {
                Download.STATE_QUEUED -> DownloadStatus.QUEUED
                Download.STATE_DOWNLOADING -> DownloadStatus.DOWNLOADING
                Download.STATE_COMPLETED -> DownloadStatus.COMPLETED
                Download.STATE_FAILED -> DownloadStatus.FAILED
                Download.STATE_STOPPED -> DownloadStatus.PAUSED
                Download.STATE_RESTARTING -> DownloadStatus.DOWNLOADING
                else -> old.status
            }
            val pct = if (download.percentDownloaded >= 0f) download.percentDownloaded.toInt().coerceIn(0, 100) else old.progressPercent
            val downloadedBytes = if (download.bytesDownloaded > 0) download.bytesDownloaded else old.downloadedBytes
            val totalBytes = if (download.contentLength > 0) download.contentLength else (if (status == DownloadStatus.COMPLETED) downloadedBytes else old.totalBytes)
            val filePath = if (status == DownloadStatus.COMPLETED) File(downloadDirectory, old.id).path else old.filePath

            current[index] = old.copy(
                status = status,
                progressPercent = if (status == DownloadStatus.COMPLETED) 100 else pct,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                filePath = filePath,
                speedText = if (status == DownloadStatus.DOWNLOADING) old.speedText else "",
            )
            save(current)
        }
    }

    private fun handleDownloadRemoved(id: String) {
        val current = _downloadsFlow.value.toMutableList()
        if (current.removeAll { it.id == id }) {
            save(current)
        }
    }

    private fun syncWithDownloadManager() {
        val downloads = downloadManager.currentDownloads
        if (downloads.isEmpty()) return
        val current = _downloadsFlow.value.toMutableList()
        var changed = false
        downloads.forEach { dl ->
            val idx = current.indexOfFirst { it.id == dl.request.id }
            if (idx >= 0) {
                val old = current[idx]
                val newStatus = when (dl.state) {
                    Download.STATE_QUEUED -> DownloadStatus.QUEUED
                    Download.STATE_DOWNLOADING -> DownloadStatus.DOWNLOADING
                    Download.STATE_COMPLETED -> DownloadStatus.COMPLETED
                    Download.STATE_FAILED -> DownloadStatus.FAILED
                    Download.STATE_STOPPED -> DownloadStatus.PAUSED
                    Download.STATE_RESTARTING -> DownloadStatus.DOWNLOADING
                    else -> old.status
                }
                val pct = if (dl.percentDownloaded >= 0f) dl.percentDownloaded.toInt().coerceIn(0, 100) else old.progressPercent
                if (old.status != newStatus || pct != old.progressPercent) {
                    current[idx] = old.copy(
                        status = newStatus,
                        progressPercent = if (newStatus == DownloadStatus.COMPLETED) 100 else pct,
                        downloadedBytes = dl.bytesDownloaded,
                        totalBytes = if (dl.contentLength > 0) dl.contentLength else old.totalBytes,
                    )
                    changed = true
                }
            }
        }
        if (changed) {
            save(current)
        }
    }

    private fun load() {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            val list = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val statusStr = o.optString("status", DownloadStatus.QUEUED.name)
                val status = runCatching { DownloadStatus.valueOf(statusStr) }.getOrDefault(DownloadStatus.QUEUED)
                DownloadItem(
                    id = o.getString("id"),
                    mediaId = if (o.has("mediaId") && !o.isNull("mediaId")) o.getLong("mediaId") else null,
                    mediaTitle = o.getString("mediaTitle"),
                    episodeTitle = o.getString("episodeTitle"),
                    episodeNumber = o.optInt("episodeNumber", 1),
                    posterUrl = if (o.has("posterUrl") && !o.isNull("posterUrl")) o.getString("posterUrl") else null,
                    streamUrl = o.getString("streamUrl"),
                    status = status,
                    progressPercent = o.optInt("progressPercent", 0),
                    downloadedBytes = o.optLong("downloadedBytes", 0L),
                    totalBytes = o.optLong("totalBytes", 0L),
                    filePath = if (o.has("filePath") && !o.isNull("filePath")) o.getString("filePath") else null,
                    addedAtMs = o.optLong("addedAtMs", System.currentTimeMillis()),
                    quality = o.optString("quality", "AUTO"),
                    serverName = if (o.has("serverName") && !o.isNull("serverName")) o.getString("serverName") else null,
                    language = o.optString("language", "English"),
                    speedText = o.optString("speedText", ""),
                )
            }
            _downloadsFlow.value = list
        }
    }

    suspend fun enqueue(
        id: String,
        mediaId: Long?,
        mediaTitle: String,
        episodeTitle: String,
        episodeNumber: Int,
        posterUrl: String?,
        streamUrl: String,
        totalBytes: Long = 0L,
        quality: String = "AUTO",
        serverName: String? = null,
        language: String = "English",
    ) = withContext(Dispatchers.IO) {
        val current = _downloadsFlow.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.id == id }
        val newItem = DownloadItem(
            id = id,
            mediaId = mediaId,
            mediaTitle = mediaTitle,
            episodeTitle = episodeTitle,
            episodeNumber = episodeNumber,
            posterUrl = posterUrl,
            streamUrl = streamUrl,
            status = DownloadStatus.QUEUED,
            progressPercent = 0,
            downloadedBytes = 0L,
            totalBytes = totalBytes,
            quality = quality,
            serverName = serverName,
            language = language,
            speedText = "",
        )
        if (existingIndex >= 0) {
            current[existingIndex] = newItem
        } else {
            current.add(0, newItem)
        }
        save(current)

        val uri = Uri.parse(streamUrl)
        val mimeType = when {
            streamUrl.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            streamUrl.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
            streamUrl.contains(".mp4", ignoreCase = true) -> MimeTypes.VIDEO_MP4
            else -> null
        }
        val requestBuilder = DownloadRequest.Builder(id, uri)
        if (mimeType != null) {
            requestBuilder.setMimeType(mimeType)
        }
        val request = requestBuilder.build()

        DownloadService.sendAddDownload(
            context,
            AnimarvleDownloadService::class.java,
            request,
            false,
        )

        withContext(Dispatchers.Main) {
            startProgressTracking()
        }
    }

    suspend fun updateStatus(id: String, status: DownloadStatus, progress: Int = -1) = withContext(Dispatchers.IO) {
        val current = _downloadsFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            val old = current[index]
            val newProgress = if (progress >= 0) progress else old.progressPercent
            current[index] = old.copy(status = status, progressPercent = newProgress)
            save(current)
        }
    }

    suspend fun pauseDownload(id: String) = withContext(Dispatchers.IO) {
        DownloadService.sendSetStopReason(
            context,
            AnimarvleDownloadService::class.java,
            id,
            Download.STOP_REASON_NONE + 1,
            false,
        )
        val current = _downloadsFlow.value.map { item ->
            if (item.id == id) item.copy(status = DownloadStatus.PAUSED, speedText = "") else item
        }
        save(current)
    }

    suspend fun resumeDownload(id: String) = withContext(Dispatchers.IO) {
        DownloadService.sendSetStopReason(
            context,
            AnimarvleDownloadService::class.java,
            id,
            Download.STOP_REASON_NONE,
            false,
        )
        val current = _downloadsFlow.value.map { item ->
            if (item.id == id) item.copy(status = DownloadStatus.DOWNLOADING) else item
        }
        save(current)
        withContext(Dispatchers.Main) {
            startProgressTracking()
        }
    }

    suspend fun pauseAll() = withContext(Dispatchers.IO) {
        DownloadService.sendPauseDownloads(
            context,
            AnimarvleDownloadService::class.java,
            false,
        )
        val current = _downloadsFlow.value.map { item ->
            if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.QUEUED) {
                item.copy(status = DownloadStatus.PAUSED, speedText = "")
            } else {
                item
            }
        }
        save(current)
    }

    suspend fun resumeAll() = withContext(Dispatchers.IO) {
        DownloadService.sendResumeDownloads(
            context,
            AnimarvleDownloadService::class.java,
            false,
        )
        val current = _downloadsFlow.value.map { item ->
            if (item.status == DownloadStatus.PAUSED) {
                item.copy(status = DownloadStatus.QUEUED)
            } else {
                item
            }
        }
        save(current)
        withContext(Dispatchers.Main) {
            startProgressTracking()
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        DownloadService.sendRemoveDownload(
            context,
            AnimarvleDownloadService::class.java,
            id,
            false,
        )
        val current = _downloadsFlow.value.toMutableList()
        if (current.removeAll { it.id == id }) {
            save(current)
        }
    }

    suspend fun removeAllCompleted() = withContext(Dispatchers.IO) {
        val completed = _downloadsFlow.value.filter { it.status == DownloadStatus.COMPLETED }
        completed.forEach { item ->
            DownloadService.sendRemoveDownload(
                context,
                AnimarvleDownloadService::class.java,
                item.id,
                false,
            )
        }
        val current = _downloadsFlow.value.toMutableList()
        if (current.removeAll { it.status == DownloadStatus.COMPLETED }) {
            save(current)
        }
    }

    suspend fun removeByMediaTitle(mediaTitle: String) = withContext(Dispatchers.IO) {
        val itemsToRemove = _downloadsFlow.value.filter { it.mediaTitle.equals(mediaTitle, ignoreCase = true) }
        itemsToRemove.forEach { item ->
            DownloadService.sendRemoveDownload(
                context,
                AnimarvleDownloadService::class.java,
                item.id,
                false,
            )
        }
        val current = _downloadsFlow.value.toMutableList()
        if (current.removeAll { it.mediaTitle.equals(mediaTitle, ignoreCase = true) }) {
            save(current)
        }
    }

    private fun save(items: List<DownloadItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("mediaId", item.mediaId ?: JSONObject.NULL)
                    put("mediaTitle", item.mediaTitle)
                    put("episodeTitle", item.episodeTitle)
                    put("episodeNumber", item.episodeNumber)
                    put("posterUrl", item.posterUrl ?: JSONObject.NULL)
                    put("streamUrl", item.streamUrl)
                    put("status", item.status.name)
                    put("progressPercent", item.progressPercent)
                    put("downloadedBytes", item.downloadedBytes)
                    put("totalBytes", item.totalBytes)
                    put("filePath", item.filePath ?: JSONObject.NULL)
                    put("addedAtMs", item.addedAtMs)
                    put("quality", item.quality)
                    put("serverName", item.serverName ?: JSONObject.NULL)
                    put("language", item.language)
                    put("speedText", item.speedText)
                },
            )
        }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
        _downloadsFlow.value = items
    }

    companion object {
        private const val PREFS_NAME = "animarvel_downloads"
        private const val KEY_ITEMS = "download_items"
    }
}
