package com.animarvle.app.ui.mylist

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
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

class MyListAdapter(
    private val onItemClick: (MyListItem) -> Unit,
    private val onItemLongClick: (MyListItem) -> Unit,
) : ListAdapter<MyListItem, MyListAdapter.ViewHolder>(DiffCallback) {

    private var currentDisplayMode = DisplayMode.COMFORTABLE_GRID

    fun setDisplayMode(mode: DisplayMode) {
        if (currentDisplayMode != mode) {
            currentDisplayMode = mode
            notifyDataSetChanged()
        }
    }

    override fun getItemViewType(position: Int): Int = currentDisplayMode.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            DisplayMode.COMPACT_GRID.ordinal -> {
                val binding = ItemMyListCompactBinding.inflate(inflater, parent, false)
                ViewHolder(
                    root = binding.root,
                    poster = binding.poster,
                    title = binding.title,
                    statusBadge = binding.statusBadge,
                    scoreBadge = binding.scoreBadge,
                    progressBar = binding.progressBar,
                    progressText = binding.progressText,
                )
            }
            DisplayMode.COVER_ONLY_GRID.ordinal -> {
                val binding = ItemMyListCoverOnlyBinding.inflate(inflater, parent, false)
                ViewHolder(
                    root = binding.root,
                    poster = binding.poster,
                    title = binding.title,
                    statusBadge = binding.statusBadge,
                    scoreBadge = binding.scoreBadge,
                    progressBar = binding.progressBar,
                    progressText = binding.progressText,
                )
            }
            DisplayMode.LIST.ordinal -> {
                val binding = ItemMyListLinearBinding.inflate(inflater, parent, false)
                ViewHolder(
                    root = binding.root,
                    poster = binding.poster,
                    title = binding.title,
                    statusBadge = binding.statusBadge,
                    scoreBadge = binding.scoreBadge,
                    progressBar = binding.progressBar,
                    progressText = binding.progressText,
                )
            }
            else -> {
                val binding = ItemMyListGridBinding.inflate(inflater, parent, false)
                ViewHolder(
                    root = binding.root,
                    poster = binding.poster,
                    title = binding.title,
                    statusBadge = binding.statusBadge,
                    scoreBadge = binding.scoreBadge,
                    progressBar = binding.progressBar,
                    progressText = binding.progressText,
                )
            }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val root: View,
        private val poster: ImageView,
        private val title: TextView,
        private val statusBadge: TextView,
        private val scoreBadge: TextView?,
        private val progressBar: ProgressBar?,
        private val progressText: TextView?,
    ) : RecyclerView.ViewHolder(root) {

        fun bind(item: MyListItem) {
            title.text = item.title

            poster.load(item.posterUrl) {
                crossfade(true)
                placeholder(R.drawable.bg_badge_dark)
                error(R.drawable.bg_badge_dark)
            }

            // Status Badge
            val statusRes = when (item.status) {
                MyListStatus.WATCHING -> R.string.my_list_status_watching
                MyListStatus.PLAN_TO_WATCH -> R.string.my_list_status_plan_to_watch
                MyListStatus.COMPLETED -> R.string.my_list_status_completed
                MyListStatus.ON_HOLD -> R.string.my_list_status_on_hold
                MyListStatus.DROPPED -> R.string.my_list_status_dropped
            }
            statusBadge.setText(statusRes)

            // Score badge
            if (scoreBadge != null) {
                if (item.score != null && item.score > 0) {
                    scoreBadge.visibility = View.VISIBLE
                    scoreBadge.text = String.format("%.1f", item.score)
                } else {
                    scoreBadge.visibility = View.GONE
                }
            }

            // Watched / total progress bar & label
            val totalEps = item.totalEpisodes
            if (progressBar != null) {
                if (totalEps != null && totalEps > 0) {
                    progressBar.isVisible = item.progress > 0
                    progressBar.max = totalEps
                    progressBar.progress = item.progress.coerceIn(0, totalEps)
                } else {
                    progressBar.isVisible = false
                }
            }

            if (progressText != null) {
                if (totalEps != null && totalEps > 0) {
                    progressText.isVisible = true
                    progressText.text = "Episodes ${item.progress} / $totalEps"
                } else if (item.progress > 0) {
                    progressText.isVisible = true
                    progressText.text = "Episodes ${item.progress}"
                } else {
                    progressText.isVisible = false
                }
            }

            root.setOnClickListener { onItemClick(item) }
            root.setOnLongClickListener {
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
