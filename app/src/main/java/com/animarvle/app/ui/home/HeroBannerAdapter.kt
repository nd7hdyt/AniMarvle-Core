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

class HeroBannerAdapter(
    private val onMediaClick: (Media) -> Unit = {},
    private val onPlayClick: (Media) -> Unit,
    private val onMyListClick: (Media) -> Unit
) : ListAdapter<Media, HeroBannerAdapter.HeroViewHolder>(MediaDiffCallback()) {

    private var savedMediaIds: Set<Long> = emptySet()

    fun setSavedIds(ids: Set<Long>) {
        if (savedMediaIds != ids) {
            savedMediaIds = ids
            notifyItemRangeChanged(0, itemCount)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeroViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_hero_banner_page, parent, false)
        return HeroViewHolder(view)
    }

    override fun onBindViewHolder(holder: HeroViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class HeroViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val heroBackdrop: ImageView = view.findViewById(R.id.heroBackdrop)
        private val tvPretitle: TextView = view.findViewById(R.id.heroPretitle)
        private val tvTitle: TextView = view.findViewById(R.id.heroTitle)
        private val tvTags: TextView = view.findViewById(R.id.heroTags)
        private val tvRating: TextView = view.findViewById(R.id.heroRating)
        private val btnPlay: View = view.findViewById(R.id.btnPlay)
        private val btnMyList: View = view.findViewById(R.id.btnMyList)
        private val ivMyListIcon: ImageView = view.findViewById(R.id.ivMyListIcon)
        private val tvMyListLabel: TextView = view.findViewById(R.id.tvMyListLabel)

        fun bind(media: Media) {
            tvTitle.text = media.title
            
            val typeLabel = when {
                media.mediaType?.equals("MOVIE", ignoreCase = true) == true || media.tags.contains("MOVIE") -> "MOVIE"
                media.mediaType?.equals("TV", ignoreCase = true) == true || media.mediaType?.equals("SERIES", ignoreCase = true) == true || media.tags.contains("SERIES") -> "SERIES"
                else -> "ANIME"
            }
            tvPretitle.text = typeLabel

            // Format "2024 • "
            tvTags.text = if (media.year != null) "${media.year} • " else ""
            
            tvRating.text = if (media.score != null && media.score > 0) String.format(java.util.Locale.US, "%.1f", media.score) else "N/A"
            
            val imageSource = media.backdropUrl ?: media.posterUrl
            if (imageSource.isNotBlank()) {
                heroBackdrop.load(imageSource)
            }

            val mediaId = media.originalId ?: media.id.toLongOrNull() ?: -1L
            val isSaved = savedMediaIds.contains(mediaId)
            val accentColor = com.animarvle.app.ui.theme.AppTheme.accent(itemView.context)

            if (isSaved) {
                ivMyListIcon.setImageResource(R.drawable.ic_bookmark_filled)
                ivMyListIcon.imageTintList = android.content.res.ColorStateList.valueOf(accentColor)
                tvMyListLabel.text = "In List"
                tvMyListLabel.setTextColor(accentColor)
            } else {
                ivMyListIcon.setImageResource(R.drawable.ic_bookmark_outline)
                ivMyListIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                tvMyListLabel.text = "My List"
                tvMyListLabel.setTextColor(android.graphics.Color.parseColor("#CCFFFFFF"))
            }

            itemView.setOnClickListener { onMediaClick(media) }
            btnPlay.setOnClickListener { onPlayClick(media) }
            btnMyList.setOnClickListener { onMyListClick(media) }
        }
    }
}
