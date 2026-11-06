package com.animarvle.app.ui.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.animarvle.app.databinding.ItemSearchGridCardBinding
import com.animarvle.app.domain.MediaUi
import java.util.Locale

/** Search 3-column compact grid card matching My List screen: portrait 2:2.95 poster · title · metadata · score. */
class SearchAdapter(
    private val onOpen: (MediaUi) -> Unit,
    private val onPlay: (MediaUi) -> Unit = onOpen,
) : RecyclerView.Adapter<SearchAdapter.VH>() {

    private val items = mutableListOf<MediaUi>()

    fun submit(list: List<MediaUi>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSearchGridCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val binding: ItemSearchGridCardBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(media: MediaUi) {
            binding.itemTitle.text = media.title

            // Format type (Movie, Series, Anime, Asian)
            val formatText = when {
                media.format.equals("MOVIE", ignoreCase = true) -> "Movie"
                media.genres.any { it.contains("Animation", ignoreCase = true) } || !media.isTmdb -> "Anime"
                media.format.equals("TV", ignoreCase = true) || media.format.equals("TV_SHORT", ignoreCase = true) -> "Series"
                media.genres.any { it.contains("Drama", ignoreCase = true) } -> "Asian"
                !media.format.isNullOrBlank() -> media.format.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
                else -> "Movie"
            }

            // Subtitle: Type • Year
            val y = media.year ?: media.startDateIso?.take(4)?.toIntOrNull()
            val subText = if (y != null && y > 0) "$formatText • $y" else formatText
            binding.itemSubtitle.text = subText

            // Rating score (★ 8.0)
            val scoreVal = media.score
            if (scoreVal != null && scoreVal > 0.0) {
                val formattedScore = if (scoreVal > 10.0) scoreVal / 10.0 else scoreVal
                binding.ratingContainer.isVisible = true
                binding.itemRating.text = String.format(Locale.US, "%.1f", formattedScore)
            } else {
                binding.ratingContainer.isVisible = false
            }

            // Portrait poster
            val artUrl = media.coverUrl?.takeIf { it.isNotBlank() } ?: media.bannerUrl?.takeIf { it.isNotBlank() }
            binding.posterImage.load(artUrl) {
                crossfade(true)
                placeholder(com.animarvle.app.R.drawable.ic_search_poster_placeholder)
                error(com.animarvle.app.R.drawable.ic_search_poster_placeholder)
            }

            binding.root.setOnClickListener { onOpen(media) }
        }
    }
}
