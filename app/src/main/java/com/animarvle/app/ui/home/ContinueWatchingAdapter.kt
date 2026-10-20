package com.animarvle.app.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.animarvle.app.R
import com.animarvle.app.databinding.ItemContinueWatchingBinding
import com.animarvle.app.ui.player.HistoryItem

class ContinueWatchingAdapter(
    private val onResume: (HistoryItem) -> Unit,
) : RecyclerView.Adapter<ContinueWatchingAdapter.Holder>() {

    private var items = listOf<HistoryItem>()

    fun submit(newItems: List<HistoryItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemContinueWatchingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class Holder(private val b: ItemContinueWatchingBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: HistoryItem) {
            b.title.text = item.title
            b.episodeLabel.text = if (item.mediaType.equals("MOVIE", ignoreCase = true)) {
                "Movie"
            } else {
                b.root.context.getString(R.string.player_watching_episode, item.episodeNumber)
            }
            val accent = com.animarvle.app.ui.theme.AppTheme.accent(b.root.context)
            b.episodeLabel.setTextColor(accent)
            b.progressBar.progressTintList = android.content.res.ColorStateList.valueOf(accent)
            b.progressBar.progress = item.progressPercent
            if (!item.posterUrl.isNullOrBlank()) {
                b.poster.load(item.posterUrl)
            } else {
                b.poster.setImageResource(R.drawable.bg_chip_tonal)
            }
            b.root.setOnClickListener { onResume(item) }
        }
    }
}
