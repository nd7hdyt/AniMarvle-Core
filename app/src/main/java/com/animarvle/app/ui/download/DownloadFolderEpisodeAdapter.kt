package com.animarvle.app.ui.download

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.animarvle.app.R
import com.animarvle.app.databinding.ItemDownloadFolderEpisodeBinding

class DownloadFolderEpisodeAdapter(
    private val onItemClick: (DownloadItem) -> Unit,
    private val onDeleteClick: (DownloadItem) -> Unit,
    private val onSwitchClick: (DownloadItem) -> Unit,
) : ListAdapter<DownloadItem, DownloadFolderEpisodeAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val b = ItemDownloadFolderEpisodeBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(b)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val b: ItemDownloadFolderEpisodeBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: DownloadItem) {
            val epNum = item.episodeNumber
            val rawTitle = item.episodeTitle.trim()
            val cleanEpTitle = when {
                rawTitle.startsWith("Episode ", ignoreCase = true) -> rawTitle.removePrefix("Episode ").trim()
                rawTitle.startsWith("Ep ", ignoreCase = true) -> rawTitle.removePrefix("Ep ").trim()
                rawTitle.startsWith("Ep", ignoreCase = true) -> rawTitle.removePrefix("Ep").trim()
                else -> rawTitle
            }
            val displayTitle = if (cleanEpTitle.isNotBlank() && cleanEpTitle != "$epNum") {
                String.format(java.util.Locale.US, "S1 E%02d • %s", epNum, cleanEpTitle)
            } else {
                String.format(java.util.Locale.US, "S1 E%02d • %s", epNum, item.mediaTitle)
            }
            b.episodeTitle.text = displayTitle

            val q = item.quality.lowercase().ifBlank { "720p" }
            val lang = item.language.ifBlank { "English" }
            val sz = item.displaySize
            b.episodeInfo.text = "$q • $lang • $sz"

            if (!item.posterUrl.isNullOrBlank()) {
                b.poster.load(item.posterUrl)
            } else {
                b.poster.setImageResource(R.drawable.bg_chip_tonal)
            }

            when (item.status) {
                DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED, DownloadStatus.PAUSED -> {
                    b.downloadProgress.isVisible = true
                    b.downloadProgress.progress = item.progressPercent
                }
                DownloadStatus.COMPLETED, DownloadStatus.FAILED -> {
                    b.downloadProgress.isVisible = false
                }
            }

            b.root.setOnClickListener { onItemClick(item) }

            b.moreButton.setOnClickListener { v ->
                val popup = androidx.appcompat.widget.PopupMenu(v.context, v)
                popup.menu.add(0, 1, 0, "Play")
                popup.menu.add(0, 2, 1, "Delete episode")
                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        1 -> {
                            onItemClick(item)
                            true
                        }
                        2 -> {
                            onDeleteClick(item)
                            true
                        }
                        else -> false
                    }
                }
                popup.show()
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<DownloadItem>() {
        override fun areItemsTheSame(old: DownloadItem, new: DownloadItem) = old.id == new.id
        override fun areContentsTheSame(old: DownloadItem, new: DownloadItem) = old == new
    }
}
