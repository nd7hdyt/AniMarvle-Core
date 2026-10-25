package com.animarvle.app.ui.provider

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.animarvle.app.R
import com.animarvle.app.ui.home.Media
import com.animarvle.app.ui.home.MediaDiffCallback
import java.util.Locale

class ProviderCatalogAdapter(
    private val onItemClick: (Media) -> Unit
) : ListAdapter<Media, ProviderCatalogAdapter.CatalogViewHolder>(MediaDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CatalogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_provider_catalog_card, parent, false)
        return CatalogViewHolder(view)
    }

    override fun onBindViewHolder(holder: CatalogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CatalogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val posterImage: ImageView = view.findViewById(R.id.posterImage)
        private val badgeMulti: TextView = view.findViewById(R.id.badgeMulti)
        private val badgeNew: TextView = view.findViewById(R.id.badgeNew)
        private val tvTitle: TextView = view.findViewById(R.id.itemTitle)
        private val tvSubtitle: TextView = view.findViewById(R.id.itemSubtitle)
        private val tvRating: TextView = view.findViewById(R.id.itemRating)
        private val ratingContainer: View = view.findViewById(R.id.ratingContainer)

        fun bind(media: Media) {
            tvTitle.text = media.title

            val typeLabel = when {
                media.mediaType.equals("MOVIE", ignoreCase = true) || media.tags.contains("MOVIE") -> "Movie"
                media.mediaType.equals("TV", ignoreCase = true) || media.mediaType.equals("SERIES", ignoreCase = true) || media.tags.contains("SERIES") -> "Series"
                else -> "Anime"
            }
            tvSubtitle.text = if (!media.year.isNullOrBlank()) "$typeLabel • ${media.year}" else typeLabel

            badgeMulti.isVisible = media.tags.contains("MULTI")
            badgeNew.isVisible = media.tags.contains("NEW")

            if (media.score != null && media.score > 0) {
                ratingContainer.isVisible = true
                tvRating.text = String.format(Locale.US, "%.1f", media.score)
            } else {
                ratingContainer.isVisible = false
            }

            if (media.posterUrl.isNotBlank()) {
                posterImage.load(media.posterUrl) {
                    crossfade(true)
                }
            } else {
                posterImage.setImageDrawable(null)
            }

            itemView.setOnClickListener { onItemClick(media) }
        }
    }
}
