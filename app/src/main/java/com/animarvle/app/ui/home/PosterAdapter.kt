package com.animarvle.app.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.animarvle.app.databinding.ItemPosterBinding
import com.animarvle.app.domain.MediaUi
import java.util.Locale

/**
 * Poster card (PROMPT §6): poster with an optional score badge, one-line title.
 * The badge is hidden when the score is unknown rather than showing a placeholder.
 */
class PosterAdapter(
    private val onOpen: (MediaUi) -> Unit,
) : RecyclerView.Adapter<PosterAdapter.VH>() {

    private val items = mutableListOf<MediaUi>()
    private var savedIds = emptySet<Long>()

    fun submit(list: List<MediaUi>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setSavedIds(ids: Set<Long>) {
        savedIds = ids
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPosterBinding.inflate(
            LayoutInflater.from(parent.context), parent, false,
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val binding: ItemPosterBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(media: MediaUi) {
            binding.title.text = media.title
            binding.poster.load(media.coverUrl)
            binding.scoreBadge.visibility = android.view.View.VISIBLE
            binding.scoreBadge.text = "HD"
            val score = media.score
            if (score != null) {
                binding.ratingBadge.visibility = android.view.View.VISIBLE
                binding.ratingBadge.text = String.format(Locale.US, "★ %.1f", score)
            } else {
                binding.ratingBadge.visibility = android.view.View.GONE
            }
            val accent = com.animarvle.app.ui.theme.AppTheme.accent(binding.root.context)
            binding.scoreBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
            binding.inLibraryIcon.imageTintList = android.content.res.ColorStateList.valueOf(accent)
            binding.inLibraryIcon.visibility = if (media.id in savedIds) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
            binding.root.setOnClickListener { onOpen(media) }
        }
    }
}