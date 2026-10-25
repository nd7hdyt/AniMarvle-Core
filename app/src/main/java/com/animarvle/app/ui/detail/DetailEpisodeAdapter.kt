package com.animarvle.app.ui.detail

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.animarvle.app.R
import com.animarvle.app.databinding.ItemDetailEpisodeBinding
import com.animarvle.app.ui.player.HistoryItem

data class DetailEpisodeUi(
    val episodeNumber: Int,
    val title: String,
    val thumbnailUrl: String?,
    val history: HistoryItem?,
    val durationMinutes: Int? = null,
)

class DetailEpisodeAdapter(
    private val onPlayEpisode: (Int) -> Unit,
) : RecyclerView.Adapter<DetailEpisodeAdapter.ViewHolder>() {

    private var items: List<DetailEpisodeUi> = emptyList()

    fun submitList(list: List<DetailEpisodeUi>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDetailEpisodeBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.epTitle.text = item.title

        item.thumbnailUrl?.let {
            holder.binding.epThumbnail.load(it) {
                crossfade(true)
            }
        }

        if (item.durationMinutes != null && item.durationMinutes > 0) {
            holder.binding.epDuration.isVisible = true
            holder.binding.epDuration.text = "${item.durationMinutes}m"
        } else {
            holder.binding.epDuration.isVisible = false
        }

        val history = item.history
        if (history != null && history.progressPercent > 0) {
            holder.binding.watchedDimOverlay.isVisible = true
            holder.binding.watchProgressBar.isVisible = true
            holder.binding.watchProgressBar.progress = history.progressPercent
        } else {
            holder.binding.watchedDimOverlay.isVisible = false
            holder.binding.watchProgressBar.isVisible = false
        }

        holder.itemView.setOnClickListener {
            onPlayEpisode(item.episodeNumber)
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(val binding: ItemDetailEpisodeBinding) : RecyclerView.ViewHolder(binding.root)
}
