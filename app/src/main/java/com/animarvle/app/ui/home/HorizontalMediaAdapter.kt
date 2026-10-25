package com.animarvle.app.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.animarvle.app.R

class HorizontalMediaAdapter(
    private val onItemClick: (Media) -> Unit
) : ListAdapter<Media, HorizontalMediaAdapter.MediaViewHolder>(MediaDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        // Reusing the exact card layout from the My List architecture
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_my_list_card, parent, false)
        // Override width to fixed size for horizontal scrolling (e.g., 120dp wide)
        view.layoutParams = ViewGroup.LayoutParams(
            (120 * parent.context.resources.displayMetrics.density).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return MediaViewHolder(view)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MediaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val posterImage: ImageView = view.findViewById(R.id.posterImage)
        private val tvTitle: TextView = view.findViewById(R.id.itemTitle)
        private val tvSubtitle: TextView = view.findViewById(R.id.itemSubtitle)
        private val tvRating: TextView = view.findViewById(R.id.itemRating)
        private val ratingContainer: View = view.findViewById(R.id.ratingContainer)
        private val btnMarkComplete: View? = view.findViewById(R.id.btnMarkComplete)
        private val btnRemoveItem: View? = view.findViewById(R.id.btnRemoveItem)

        fun bind(media: Media) {
            btnMarkComplete?.visibility = View.GONE
            btnRemoveItem?.visibility = View.GONE
            tvTitle.text = media.title
            
            // Movie vs Series vs Anime classification
            val typeLabel = when {
                media.mediaType?.equals("MOVIE", ignoreCase = true) == true || media.tags.contains("MOVIE") -> "Movie"
                media.mediaType?.equals("TV", ignoreCase = true) == true || media.mediaType?.equals("SERIES", ignoreCase = true) == true || media.tags.contains("SERIES") -> "Series"
                else -> "Anime"
            }
            tvSubtitle.text = if (media.year != null) "$typeLabel • ${media.year}" else typeLabel

            if (media.score != null && media.score > 0) {
                ratingContainer.visibility = View.VISIBLE
                tvRating.text = String.format(java.util.Locale.US, "%.1f", media.score)
            } else {
                ratingContainer.visibility = View.GONE
            }

            if (media.posterUrl.isNotBlank()) {
                posterImage.load(media.posterUrl)
            }

            itemView.setOnClickListener { onItemClick(media) }
        }
    }
}
