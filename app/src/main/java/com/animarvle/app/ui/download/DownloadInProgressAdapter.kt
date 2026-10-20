package com.animarvle.app.ui.download

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.animarvle.app.R
import com.animarvle.app.databinding.ItemDownloadInProgressBinding
import java.util.Locale

class DownloadInProgressAdapter(
    private val onItemClick: (DownloadItem) -> Unit,
    private val onPauseResumeClick: (DownloadItem) -> Unit,
    private val onCancelClick: (DownloadItem) -> Unit,
) : ListAdapter<DownloadItem, DownloadInProgressAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val b = ItemDownloadInProgressBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(b)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val b: ItemDownloadInProgressBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: DownloadItem) {
            b.mediaTitle.text = item.mediaTitle

            val qualityStr = item.quality.ifBlank { "1080p" }
            val langStr = item.language.ifBlank { "English" }

            val subtitle = when (item.status) {
                DownloadStatus.DOWNLOADING -> {
                    val speed = item.speedText.ifBlank { "Connecting..." }
                    "$qualityStr • $langStr • ${item.progressPercent}% • $speed"
                }
                DownloadStatus.PAUSED -> {
                    val dlMb = item.downloadedBytes.toDouble() / (1024.0 * 1024.0)
                    val totalMb = item.totalBytes.toDouble() / (1024.0 * 1024.0)
                    val bytesStr = if (totalMb > 0) {
                        String.format(Locale.US, "%.1f MB / %.1f MB", dlMb, totalMb)
                    } else {
                        String.format(Locale.US, "%.1f MB", dlMb)
                    }
                    "Paused • $qualityStr • $langStr • $bytesStr"
                }
                DownloadStatus.QUEUED -> {
                    "$qualityStr • $langStr • Queued • Starting secure transfer..."
                }
                DownloadStatus.FAILED -> {
                    "$qualityStr • $langStr • Failed • Tap to retry"
                }
                DownloadStatus.COMPLETED -> {
                    "$qualityStr • $langStr • Completed"
                }
            }
            b.progressSubtitle.text = subtitle

            val isIndeterminate = item.status == DownloadStatus.QUEUED || (item.progressPercent <= 0 && item.totalBytes <= 0)
            b.progressBar.isIndeterminate = isIndeterminate
            if (!isIndeterminate) {
                b.progressBar.progress = item.progressPercent
            }

            if (!item.posterUrl.isNullOrBlank()) {
                b.poster.load(item.posterUrl)
            } else {
                b.poster.setImageResource(R.drawable.bg_chip_tonal)
            }

            b.root.setOnClickListener { onItemClick(item) }

            b.btnMenu.setOnClickListener { v ->
                showMenu(v, item)
            }
        }

        private fun showMenu(view: View, item: DownloadItem) {
            val popup = PopupMenu(view.context, view)
            if (item.status == DownloadStatus.DOWNLOADING) {
                popup.menu.add(0, 1, 0, "Pause")
            } else if (item.status == DownloadStatus.PAUSED) {
                popup.menu.add(0, 2, 0, "Resume")
            }
            popup.menu.add(0, 3, 1, "Cancel Download")

            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1, 2 -> onPauseResumeClick(item)
                    3 -> onCancelClick(item)
                }
                true
            }
            popup.show()
        }
    }

    private object Diff : DiffUtil.ItemCallback<DownloadItem>() {
        override fun areItemsTheSame(old: DownloadItem, new: DownloadItem) = old.id == new.id
        override fun areContentsTheSame(old: DownloadItem, new: DownloadItem) = old == new
    }
}
