package com.animarvle.app.ui.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.animarvle.app.databinding.ItemSearchRowBinding
import com.animarvle.app.domain.MediaUi
import java.util.Locale

/** Search row: portrait 2:3 thumb · title · type badge · year · score · chevron. */
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
        VH(ItemSearchRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val binding: ItemSearchRowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(media: MediaUi) {
            binding.title.text = media.title

            // Format type badge (Movie, TV Show, Anime, Asian)
            val formatText = when {
                media.format.equals("MOVIE", ignoreCase = true) -> "Movie"
                media.format.equals("TV", ignoreCase = true) || media.format.equals("TV_SHORT", ignoreCase = true) -> "TV Show"
                media.genres.any { it.contains("Animation", ignoreCase = true) } -> "Anime"
                media.genres.any { it.contains("Drama", ignoreCase = true) } -> "Asian"
                !media.format.isNullOrBlank() -> media.format.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
                else -> "Movie"
            }
            binding.typeBadge.text = formatText
            binding.typeBadge.isVisible = formatText.isNotBlank()

            // Year
            val y = media.year ?: media.startDateIso?.take(4)?.toIntOrNull()
            if (y != null && y > 0) {
                binding.year.isVisible = true
                binding.year.text = y.toString()
            } else {
                binding.year.isVisible = true
                binding.year.text = "TBA"
            }

            // Rating score
            val scoreVal = media.score
            binding.scoreContainer.isVisible = true
            if (scoreVal != null && scoreVal > 0.0) {
                val formattedScore = if (scoreVal > 10.0) scoreVal / 10.0 else scoreVal
                binding.score.text = String.format(Locale.US, "%.1f", formattedScore)
                binding.score.setTextColor(0xFFE1E4EC.toInt())
            } else {
                binding.score.text = "--"
                binding.score.setTextColor(0xFF7E8494.toInt())
            }

            // Portrait poster
            val artUrl = media.coverUrl?.takeIf { it.isNotBlank() } ?: media.bannerUrl?.takeIf { it.isNotBlank() }
            binding.thumbImage.load(artUrl) {
                crossfade(true)
                placeholder(com.animarvle.app.R.drawable.ic_search_poster_placeholder)
                error(com.animarvle.app.R.drawable.ic_search_poster_placeholder)
            }

            binding.root.setOnClickListener { onOpen(media) }
        }
    }
}