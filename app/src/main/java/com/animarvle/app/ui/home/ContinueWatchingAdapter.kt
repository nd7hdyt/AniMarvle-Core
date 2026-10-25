package com.animarvle.app.ui.home

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.animarvle.app.R
import com.animarvle.app.databinding.ItemContinueWatchingBinding

class ContinueWatchingAdapter(
    private val onResume: (Media) -> Unit,
) : ListAdapter<Media, ContinueWatchingAdapter.Holder>(MediaDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemContinueWatchingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val b: ItemContinueWatchingBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: Media) {
            b.title.text = item.title
            val isMovie = item.mediaType?.equals("MOVIE", ignoreCase = true) == true || item.tags.contains("MOVIE")
            b.episodeLabel.text = if (isMovie) {
                item.timeLeft ?: "Movie"
            } else {
                val epNum = item.episodeNumber ?: 1
                val left = item.timeLeft
                if (left != null) "Ep $epNum • $left" else "Episode $epNum"
            }
            b.progressBar.progressTintList = ColorStateList.valueOf(Color.WHITE)
            b.progressBar.progress = ((item.progress ?: 0f) * 100).toInt().coerceIn(0, 100)

            // Always use the Anime/Movie vertical posterUrl
            val img = item.posterUrl.ifBlank { item.backdropUrl.orEmpty() }
            if (img.isNotBlank()) {
                b.poster.load(img) {
                    crossfade(true)
                    placeholder(R.drawable.bg_search_poster_placeholder)
                    error(R.drawable.bg_search_poster_placeholder)
                }
            } else {
                b.poster.setImageResource(R.drawable.bg_search_poster_placeholder)
            }
            b.root.setOnClickListener { onResume(item) }
        }
    }
}
