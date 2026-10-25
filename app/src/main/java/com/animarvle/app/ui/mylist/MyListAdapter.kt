package com.animarvle.app.ui.mylist

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.animarvle.app.R
import com.animarvle.app.databinding.ItemMyListCompactBinding
import com.animarvle.app.databinding.ItemMyListCoverOnlyBinding
import com.animarvle.app.databinding.ItemMyListGridBinding
import com.animarvle.app.databinding.ItemMyListLinearBinding
import java.util.Locale

class MyListAdapter(
    private val onItemClick: (MyListItem) -> Unit,
    private val onItemLongClick: (MyListItem) -> Unit,
    private val onMarkCompleteClick: (MyListItem) -> Unit = {},
    private val onRemoveClick: (MyListItem) -> Unit = {},
) : ListAdapter<MyListItem, MyListAdapter.BaseViewHolder>(DiffCallback) {

    private var currentDisplayMode = DisplayMode.COMFORTABLE_GRID

    fun setDisplayMode(mode: DisplayMode) {
        if (currentDisplayMode != mode) {
            currentDisplayMode = mode
            notifyDataSetChanged()
        }
    }

    override fun getItemViewType(position: Int): Int = currentDisplayMode.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            DisplayMode.COVER_ONLY_GRID.ordinal -> {
                val binding = ItemMyListCoverOnlyBinding.inflate(inflater, parent, false)
                CoverOnlyViewHolder(binding)
            }
            DisplayMode.LIST.ordinal -> {
                val binding = ItemMyListLinearBinding.inflate(inflater, parent, false)
                LinearViewHolder(binding)
            }
            else -> {
                // Both COMFORTABLE_GRID and COMPACT_GRID use the clean 1:1 reference card layout
                val binding = ItemMyListGridBinding.inflate(inflater, parent, false)
                GridViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    abstract class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        abstract fun bind(item: MyListItem)
    }

    inner class GridViewHolder(
        private val binding: ItemMyListGridBinding,
    ) : BaseViewHolder(binding.root) {

        override fun bind(item: MyListItem) {
            binding.itemTitle.text = item.title

            val isAnime = item.posterUrl?.contains("anilist", ignoreCase = true) == true ||
                item.format?.contains("ANIME", ignoreCase = true) == true ||
                item.format in listOf("ONA", "OVA", "TV_SHORT", "SPECIAL")

            val typeLabel = when {
                !isAnime && (item.format?.equals("MOVIE", ignoreCase = true) == true || item.format?.contains("Movie", ignoreCase = true) == true) -> "Movie"
                !isAnime && (item.format?.equals("TV", ignoreCase = true) == true || item.format?.contains("SERIES", ignoreCase = true) == true || item.format?.contains("Series", ignoreCase = true) == true) -> "Series"
                else -> "Anime"
            }

            val inferredYear = item.year ?: run {
                val match = Regex("""\b(19\d\d|20\d\d)\b""").find(item.title)
                match?.value?.toIntOrNull()
            }

            binding.itemSubtitle.text = if (inferredYear != null && inferredYear > 0) {
                "$typeLabel • $inferredYear"
            } else {
                typeLabel
            }

            if (item.score != null && item.score > 0.0) {
                binding.ratingContainer.isVisible = true
                binding.itemRating.text = String.format(Locale.US, "%.1f", item.score)
            } else {
                binding.ratingContainer.isVisible = false
            }

            binding.posterImage.load(item.posterUrl) {
                crossfade(true)
                placeholder(R.drawable.bg_badge_dark)
                error(R.drawable.bg_badge_dark)
            }

            // Quick actions:
            // Checkmark button (✓): visible in Pending to mark Completed
            if (item.status == MyListStatus.COMPLETED) {
                binding.btnMarkComplete.isVisible = false
            } else {
                binding.btnMarkComplete.isVisible = true
                binding.iconMarkComplete.setColorFilter(Color.parseColor("#C5E1A5"))
            }

            binding.btnMarkComplete.setOnClickListener {
                onMarkCompleteClick(item)
            }

            binding.btnRemoveItem.setOnClickListener {
                onRemoveClick(item)
            }

            binding.root.setOnClickListener { onItemClick(item) }
            binding.root.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
        }
    }

    inner class CompactViewHolder(
        private val binding: ItemMyListCompactBinding,
    ) : BaseViewHolder(binding.root) {

        override fun bind(item: MyListItem) {
            binding.title.text = item.title
            binding.poster.load(item.posterUrl) {
                crossfade(true)
                placeholder(R.drawable.bg_badge_dark)
                error(R.drawable.bg_badge_dark)
            }

            val statusRes = when (item.status) {
                MyListStatus.WATCHING -> R.string.my_list_status_watching
                MyListStatus.PLAN_TO_WATCH -> R.string.my_list_status_plan_to_watch
                MyListStatus.COMPLETED -> R.string.my_list_status_completed
                MyListStatus.ON_HOLD -> R.string.my_list_status_on_hold
                MyListStatus.DROPPED -> R.string.my_list_status_dropped
            }
            binding.statusBadge.setText(statusRes)

            if (item.score != null && item.score > 0) {
                binding.scoreBadge.isVisible = true
                binding.scoreBadge.text = String.format(Locale.US, "%.1f", item.score)
            } else {
                binding.scoreBadge.isVisible = false
            }

            val totalEps = item.totalEpisodes
            if (totalEps != null && totalEps > 0) {
                binding.progressBar.isVisible = item.progress > 0
                binding.progressBar.max = totalEps
                binding.progressBar.progress = item.progress.coerceIn(0, totalEps)
                binding.progressText.isVisible = true
                binding.progressText.text = "Episodes ${item.progress} / $totalEps"
            } else {
                binding.progressBar.isVisible = false
                binding.progressText.isVisible = false
            }

            binding.root.setOnClickListener { onItemClick(item) }
            binding.root.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
        }
    }

    inner class CoverOnlyViewHolder(
        private val binding: ItemMyListCoverOnlyBinding,
    ) : BaseViewHolder(binding.root) {

        override fun bind(item: MyListItem) {
            binding.title.text = item.title
            binding.poster.load(item.posterUrl) {
                crossfade(true)
                placeholder(R.drawable.bg_badge_dark)
                error(R.drawable.bg_badge_dark)
            }

            val statusRes = when (item.status) {
                MyListStatus.WATCHING -> R.string.my_list_status_watching
                MyListStatus.PLAN_TO_WATCH -> R.string.my_list_status_plan_to_watch
                MyListStatus.COMPLETED -> R.string.my_list_status_completed
                MyListStatus.ON_HOLD -> R.string.my_list_status_on_hold
                MyListStatus.DROPPED -> R.string.my_list_status_dropped
            }
            binding.statusBadge.setText(statusRes)

            if (item.score != null && item.score > 0) {
                binding.scoreBadge.isVisible = true
                binding.scoreBadge.text = String.format(Locale.US, "%.1f", item.score)
            } else {
                binding.scoreBadge.isVisible = false
            }

            binding.root.setOnClickListener { onItemClick(item) }
            binding.root.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
        }
    }

    inner class LinearViewHolder(
        private val binding: ItemMyListLinearBinding,
    ) : BaseViewHolder(binding.root) {

        override fun bind(item: MyListItem) {
            binding.title.text = item.title
            binding.poster.load(item.posterUrl) {
                crossfade(true)
                placeholder(R.drawable.bg_badge_dark)
                error(R.drawable.bg_badge_dark)
            }

            val statusRes = when (item.status) {
                MyListStatus.WATCHING -> R.string.my_list_status_watching
                MyListStatus.PLAN_TO_WATCH -> R.string.my_list_status_plan_to_watch
                MyListStatus.COMPLETED -> R.string.my_list_status_completed
                MyListStatus.ON_HOLD -> R.string.my_list_status_on_hold
                MyListStatus.DROPPED -> R.string.my_list_status_dropped
            }
            binding.statusBadge.setText(statusRes)

            if (item.score != null && item.score > 0) {
                binding.scoreBadge.isVisible = true
                binding.scoreBadge.text = String.format(Locale.US, "%.1f", item.score)
            } else {
                binding.scoreBadge.isVisible = false
            }

            val totalEps = item.totalEpisodes
            if (totalEps != null && totalEps > 0) {
                binding.progressBar.isVisible = item.progress > 0
                binding.progressBar.max = totalEps
                binding.progressBar.progress = item.progress.coerceIn(0, totalEps)
                binding.progressText.isVisible = true
                binding.progressText.text = "Episodes ${item.progress} / $totalEps"
            } else {
                binding.progressBar.isVisible = false
                binding.progressText.isVisible = false
            }

            binding.root.setOnClickListener { onItemClick(item) }
            binding.root.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<MyListItem>() {
        override fun areItemsTheSame(oldItem: MyListItem, newItem: MyListItem): Boolean =
            oldItem.mediaId == newItem.mediaId

        override fun areContentsTheSame(oldItem: MyListItem, newItem: MyListItem): Boolean =
            oldItem == newItem
    }
}
