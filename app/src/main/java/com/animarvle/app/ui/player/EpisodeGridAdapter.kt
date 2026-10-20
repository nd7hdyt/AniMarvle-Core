package com.animarvle.app.ui.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.animarvle.app.databinding.ItemEpisodeBinding

/** Numbered episode grid (PROMPT §7.7): ~6 per row, current = accent fill. */
class EpisodeGridAdapter(
    private val onPick: (Int) -> Unit,
) : RecyclerView.Adapter<EpisodeGridAdapter.Holder>() {

    private var items: List<Int> = emptyList()
    private var current: Int? = null

    fun submit(episodes: List<Int>, currentNumber: Int?) {
        items = episodes
        current = currentNumber
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemEpisodeBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val episode = items[position]
        holder.binding.epLabel.text = episode.toString()
        val selected = episode == current
        holder.binding.epLabel.setBackgroundResource(
            if (selected) com.animarvle.app.R.drawable.bg_ep_tile_selected
            else com.animarvle.app.R.drawable.bg_ep_tile,
        )
        holder.binding.epLabel.setTextColor(
            if (selected) android.graphics.Color.WHITE
            else android.graphics.Color.parseColor("#E0E0E0"),
        )
        holder.binding.epLabel.setOnClickListener { onPick(episode) }
    }
}