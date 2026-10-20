package com.animarvle.app.ui.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.animarvle.app.AnimarvleApp
import com.animarvle.app.MainActivity
import com.animarvle.app.R
import java.util.Locale

/**
 * Foreground download service building rich AniVortex notifications (matching Screenshot 1 & 4).
 * Features:
 * - Title: Media title (e.g. "Masterplan")
 * - Subtitle: "Downloading • 1080p • English • 127.1 MB / 1686.4 MB" or "Paused • ..."
 * - Live horizontal progress bar
 * - [Pause] / [Resume] and [Cancel] interactive notification action buttons
 * - Silent updates via IMPORTANCE_LOW notification channel
 */
class AnimarvleDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_notification_channel_name,
    R.string.download_notification_channel_desc,
) {

    override fun getDownloadManager(): DownloadManager =
        (application as AnimarvleApp).container.downloadManager

    override fun getScheduler(): Scheduler? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.download_notification_channel_desc)
                setShowBadge(true)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): Notification {
        createNotificationChannel()

        val activeDownload = downloads.firstOrNull { it.state == Download.STATE_DOWNLOADING }
            ?: downloads.firstOrNull { it.state == Download.STATE_STOPPED }
            ?: downloads.firstOrNull { it.state == Download.STATE_QUEUED }
            ?: downloads.firstOrNull()

        val appContainer = (application as AnimarvleApp).container
        val allItems = appContainer.downloads.downloadsFlow.value
        val item = activeDownload?.let { dl -> allItems.firstOrNull { it.id == dl.request.id } }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("navigateTo", "downloadFragment")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(contentIntent)
            .setOngoing(activeDownload?.state == Download.STATE_DOWNLOADING)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (activeDownload == null) {
            builder.setContentTitle("Preparing downloads")
                .setContentText("Starting secure transfer...")
                .setProgress(100, 0, true)
            return builder.build()
        }

        val mediaTitle = item?.mediaTitle ?: "Downloading Media"
        val quality = item?.quality?.takeIf { it.isNotBlank() } ?: "1080p"
        val lang = item?.language?.takeIf { it.isNotBlank() } ?: "English"

        val downloadedBytes = activeDownload.bytesDownloaded
        val totalBytes = if (activeDownload.contentLength > 0) activeDownload.contentLength else item?.totalBytes ?: 0L

        val isPaused = activeDownload.state == Download.STATE_STOPPED
        val statusPrefix = if (isPaused) "Paused" else "Downloading"

        val subtitle = if (totalBytes > 0) {
            val dlMb = downloadedBytes.toDouble() / (1024.0 * 1024.0)
            val totalMb = totalBytes.toDouble() / (1024.0 * 1024.0)
            String.format(Locale.US, "%s • %s • %s • %.1f MB / %.1f MB", statusPrefix, quality, lang, dlMb, totalMb)
        } else if (downloadedBytes > 0) {
            val dlMb = downloadedBytes.toDouble() / (1024.0 * 1024.0)
            String.format(Locale.US, "%s • %s • %s • %.1f MB", statusPrefix, quality, lang, dlMb)
        } else {
            "$statusPrefix • $quality • $lang"
        }

        val pct = if (activeDownload.percentDownloaded >= 0f) {
            activeDownload.percentDownloaded.toInt().coerceIn(0, 100)
        } else {
            item?.progressPercent ?: 0
        }

        builder.setContentTitle(mediaTitle)
            .setContentText(subtitle)
            .setProgress(100, pct, pct <= 0 && totalBytes <= 0)

        // Action 1: Pause or Resume
        if (isPaused) {
            val resumeIntent = buildResumeDownloadsIntent(this, AnimarvleDownloadService::class.java, false)
            val resumePending = PendingIntent.getService(
                this,
                101,
                resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(R.drawable.ic_play_filled, "Resume", resumePending)
        } else {
            val pauseIntent = buildPauseDownloadsIntent(this, AnimarvleDownloadService::class.java, false)
            val pausePending = PendingIntent.getService(
                this,
                102,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(R.drawable.ic_pause, "Pause", pausePending)
        }

        // Action 2: Cancel
        val cancelIntent = buildRemoveDownloadIntent(this, AnimarvleDownloadService::class.java, activeDownload.request.id, false)
        val cancelPending = PendingIntent.getService(
            this,
            103,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.addAction(R.drawable.ic_close, "Cancel", cancelPending)

        return builder.build()
    }

    companion object {
        const val FOREGROUND_NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "animarvle_downloads"
    }
}
