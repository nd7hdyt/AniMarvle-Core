package com.animarvle.app.ui.download

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
}

data class DownloadItem(
    val id: String, // unique id, e.g. "mediaId_ep1" or stream URL hash
    val mediaId: Long? = null,
    val mediaTitle: String,
    val episodeTitle: String,
    val episodeNumber: Int,
    val posterUrl: String? = null,
    val streamUrl: String,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progressPercent: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val filePath: String? = null,
    val addedAtMs: Long = System.currentTimeMillis(),
    val quality: String = "AUTO",
    val serverName: String? = null,
    val language: String = "English",
    val speedText: String = "",
) {
    val displaySize: String
        get() = if (totalBytes > 0) {
            val mb = totalBytes.toDouble() / (1024.0 * 1024.0)
            String.format(java.util.Locale.US, "%.1f MB", mb)
        } else {
            "-- MB"
        }
}
