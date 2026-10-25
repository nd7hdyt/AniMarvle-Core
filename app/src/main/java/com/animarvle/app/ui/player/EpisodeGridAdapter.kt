package com.animarvle.app.ui.player

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.animarvle.app.R
import com.animarvle.app.databinding.ItemEpisodeBinding
import com.animarvle.app.databinding.ItemPlayerEpisodeThumbnailBinding
import com.animarvle.sourceapi.SourceEpisode

enum class EpisodeViewMode {
    THUMBNAIL,
    NUMBER
}

/**
 * Dual-mode Episode Adapter for Player screen:
 * - Mode A: 2-column 16:9 Thumbnail cards with active white border highlight.
 * - Mode B: 6-column anime-style number tiles [ 1 ] [ 2 ] [ 3 ]...
 */
class EpisodeGridAdapter(
    private val onPick: (Int) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_NUMBER = 1
        private const val TYPE_THUMBNAIL = 2
    }

    private var items: List<SourceEpisode> = emptyList()
    private var currentNumber: Int? = null
    var fallbackPoster: String? = null
    var viewMode: EpisodeViewMode = EpisodeViewMode.THUMBNAIL
        private set

    fun setViewMode(mode: EpisodeViewMode) {
        if (viewMode != mode) {
            viewMode = mode
            notifyDataSetChanged()
        }
    }

    fun submit(episodes: List<SourceEpisode>, current: Int?, fallback: String? = null) {
        items = episodes
        currentNumber = current
        if (fallback != null) fallbackPoster = fallback
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (viewMode == EpisodeViewMode.THUMBNAIL) TYPE_THUMBNAIL else TYPE_NUMBER
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_THUMBNAIL) {
            ThumbnailHolder(ItemPlayerEpisodeThumbnailBinding.inflate(inflater, parent, false))
        } else {
            NumberHolder(ItemEpisodeBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val ep = items[position]
        val epNum = ep.number.toInt()
        val isSelected = epNum == currentNumber

        if (holder is ThumbnailHolder) {
            holder.bind(ep, isSelected, fallbackPoster, onPick)
        } else if (holder is NumberHolder) {
            holder.bind(epNum, isSelected, onPick)
        }
    }

    class NumberHolder(private val binding: ItemEpisodeBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(epNum: Int, isSelected: Boolean, onPick: (Int) -> Unit) {
            binding.epLabel.text = epNum.toString()
            binding.epLabel.setBackgroundResource(
                if (isSelected) R.drawable.bg_ep_tile_selected else R.drawable.bg_ep_tile
            )
            binding.epLabel.setTextColor(
                if (isSelected) Color.BLACK else Color.parseColor("#E0E0E0")
            )
            binding.epLabel.setOnClickListener { onPick(epNum) }
        }
    }

    class ThumbnailHolder(private val binding: ItemPlayerEpisodeThumbnailBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(ep: SourceEpisode, isSelected: Boolean, fallbackPoster: String?, onPick: (Int) -> Unit) {
            val epNum = ep.number.toInt()
            binding.epNumberText.text = "Episode : $epNum"

            val title = ep.title?.trim().orEmpty()
            val overview = ep.extra["overview"]?.trim().orEmpty()
            val isTitleValid = title.isNotBlank() &&
                    !title.equals("null", ignoreCase = true) &&
                    !title.equals("episode $epNum", ignoreCase = true) &&
                    title != "$epNum"
            val isOverviewValid = overview.isNotBlank() && !overview.equals("null", ignoreCase = true)

            val displaySubtitle = when {
                isTitleValid -> title
                isOverviewValid -> overview
                else -> null
            }

            if (!displaySubtitle.isNullOrBlank()) {
                binding.epTitleText.text = displaySubtitle
                binding.epTitleText.isVisible = true
            } else {
                binding.epTitleText.isVisible = false
            }

            // Active card border: 2dp solid white #FFFFFF, inactive 0dp / transparent
            val strokeDp = if (isSelected) dp(2) else 0
            binding.cardRoot.strokeWidth = strokeDp
            binding.cardRoot.strokeColor = if (isSelected) Color.WHITE else Color.TRANSPARENT

            // Thumbnail loading: use episode screenshot if available, otherwise show poster
            val thumbUrl = ep.thumbnail?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                ?: fallbackPoster?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

            if (!thumbUrl.isNullOrBlank()) {
                binding.epThumbnail.load(thumbUrl) {
                    crossfade(true)
                    placeholder(R.drawable.bg_thumbnail_placeholder)
                    error(R.drawable.bg_thumbnail_placeholder)
                }
            } else {
                binding.epThumbnail.setImageResource(R.drawable.bg_thumbnail_placeholder)
            }

            binding.cardRoot.setOnClickListener { onPick(epNum) }
        }

        private fun dp(value: Int): Int {
            return (value * binding.root.resources.displayMetrics.density).toInt()
        }
    }
}