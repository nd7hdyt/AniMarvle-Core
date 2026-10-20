package com.animarvle.app.ui.download

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.animarvle.app.R
import com.animarvle.app.databinding.ItemDownloadSeriesBinding

data class DownloadSeriesGroup(
    val mediaTitle: String,
    val posterUrl: String?,
    val episodeCount: Int,
    val downloadedCount: Int,
    val episodes: List<DownloadItem>,
)

class DownloadSeriesAdapter(
    private val onSeriesClick: (DownloadSeriesGroup) -> Unit,
) : ListAdapter<DownloadSeriesGroup, DownloadSeriesAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val b = ItemDownloadSeriesBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(b)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val b: ItemDownloadSeriesBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(group: DownloadSeriesGroup) {
            b.mediaTitle.text = group.mediaTitle

            val epWord = if (group.episodeCount == 1) "episode" else "episodes"
            b.episodeCountText.text = "${group.episodeCount} $epWord • ${group.downloadedCount} downloaded"

            if (!group.posterUrl.isNullOrBlank()) {
                b.poster.load(group.posterUrl)
            } else {
                b.poster.setImageResource(R.drawable.bg_chip_tonal)
            }

            b.root.setOnClickListener { onSeriesClick(group) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<DownloadSeriesGroup>() {
        override fun areItemsTheSame(old: DownloadSeriesGroup, new: DownloadSeriesGroup) =
            old.mediaTitle == new.mediaTitle

        override fun areContentsTheSame(old: DownloadSeriesGroup, new: DownloadSeriesGroup) =
            old == new
    }
}
