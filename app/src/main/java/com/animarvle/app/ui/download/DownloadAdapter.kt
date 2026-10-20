package com.animarvle.app.ui.download

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.animarvle.app.R
import com.animarvle.app.databinding.ItemDownloadEpisodeBinding

class DownloadAdapter(
    private val onItemClick: (DownloadItem) -> Unit,
    private val onItemLongClick: (DownloadItem) -> Unit,
    private val onActionClick: (DownloadItem) -> Unit,
) : ListAdapter<DownloadItem, DownloadAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val b = ItemDownloadEpisodeBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(b)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val b: ItemDownloadEpisodeBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: DownloadItem) {
            val ctx = b.root.context
            b.mediaTitle.text = item.mediaTitle
            b.episodeTitle.text = item.episodeTitle
            b.sizeText.text = item.displaySize

            if (!item.posterUrl.isNullOrBlank()) {
                b.poster.load(item.posterUrl)
            } else {
                b.poster.setImageResource(R.drawable.bg_chip_tonal)
            }

            when (item.status) {
                DownloadStatus.DOWNLOADING -> {
                    b.statusBadge.text = ctx.getString(R.string.download_state_downloading, item.progressPercent)
                    b.downloadProgress.isVisible = true
                    b.downloadProgress.progress = item.progressPercent
                    b.actionButton.setImageResource(R.drawable.ic_pause)
                }
                DownloadStatus.QUEUED -> {
                    b.statusBadge.setText(R.string.download_state_queued)
                    b.downloadProgress.isVisible = true
                    b.downloadProgress.progress = 0
                    b.actionButton.setImageResource(R.drawable.ic_pause)
                }
                DownloadStatus.PAUSED -> {
                    b.statusBadge.setText(R.string.download_state_paused)
                    b.downloadProgress.isVisible = true
                    b.downloadProgress.progress = item.progressPercent
                    b.actionButton.setImageResource(R.drawable.ic_play_filled)
                }
                DownloadStatus.COMPLETED -> {
                    b.statusBadge.setText(R.string.download_state_completed)
                    b.downloadProgress.isVisible = false
                    b.actionButton.setImageResource(R.drawable.ic_play_filled)
                }
                DownloadStatus.FAILED -> {
                    b.statusBadge.setText(R.string.download_state_failed)
                    b.downloadProgress.isVisible = false
                    b.actionButton.setImageResource(R.drawable.ic_refresh)
                }
            }

            b.root.setOnClickListener { onItemClick(item) }
            b.root.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
            b.actionButton.setOnClickListener { onActionClick(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<DownloadItem>() {
        override fun areItemsTheSame(old: DownloadItem, new: DownloadItem) = old.id == new.id
        override fun areContentsTheSame(old: DownloadItem, new: DownloadItem) = old == new
    }
}
