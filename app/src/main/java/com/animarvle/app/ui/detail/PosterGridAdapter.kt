package com.animarvle.app.ui.detail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.animarvle.app.databinding.ItemPosterGridBinding
import com.animarvle.app.domain.MediaUi
import java.util.Locale

/** 3-column poster grid used by the "More like this" tab (PROMPT §7.6). */
class PosterGridAdapter(
    private val onOpen: (MediaUi) -> Unit,
) : RecyclerView.Adapter<PosterGridAdapter.VH>() {

    private val items = mutableListOf<MediaUi>()

    fun submit(list: List<MediaUi>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemPosterGridBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val binding: ItemPosterGridBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(media: MediaUi) {
            binding.title.text = media.title
            binding.poster.load(media.coverUrl)
            val score = media.score
            if (score != null) {
                binding.scoreBadge.visibility = View.VISIBLE
                binding.scoreBadge.text = String.format(Locale.US, "%.1f", score)
            } else {
                binding.scoreBadge.visibility = View.GONE
            }
            binding.root.setOnClickListener { onOpen(media) }
        }
    }
}