package com.animarvle.app.ui.detail

import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.animarvle.app.databinding.ItemDetailSeasonBinding
import com.animarvle.app.domain.SeasonUi
import com.animarvle.app.ui.theme.AppTheme

/**
 * Horizontal Seasons Rail adapter matching Th3-Anime reference screenshot.
 */
class DetailSeasonAdapter(
    private val onSeasonClick: (SeasonUi) -> Unit,
) : RecyclerView.Adapter<DetailSeasonAdapter.VH>() {

    private val items = mutableListOf<SeasonUi>()

    fun submit(list: List<SeasonUi>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemDetailSeasonBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val binding: ItemDetailSeasonBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(season: SeasonUi) {
            val ctx = binding.root.context
            val accent = AppTheme.accent(ctx)
            val strokeWidthPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                2f,
                ctx.resources.displayMetrics,
            ).toInt()

            binding.title.text = season.title
            binding.poster.load(season.posterUrl)

            if (season.isCurrent) {
                binding.card.strokeWidth = strokeWidthPx
                binding.card.strokeColor = accent
                binding.currentBadge.visibility = View.VISIBLE
                binding.currentBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
                binding.title.setTextColor(accent)
            } else {
                binding.card.strokeWidth = 0
                binding.currentBadge.visibility = View.GONE
                binding.title.setTextColor(Color.WHITE)
            }

            binding.root.setOnClickListener {
                if (!season.isCurrent) {
                    onSeasonClick(season)
                }
            }
        }
    }
}
