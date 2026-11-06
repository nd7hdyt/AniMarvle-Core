package com.animarvle.app.ui.home

import androidx.recyclerview.widget.DiffUtil

enum class HomeDomain {
    ANIME, MOVIE
}

/**
 * Core media object used across all rails.
 */
data class Media(
    val id: String,
    val title: String,
    val posterUrl: String,
    val backdropUrl: String? = null,
    val year: String? = null,
    val score: Double? = null,
    val tags: List<String> = emptyList(), // e.g., ["MOVIE", "MULTI", "HD", "HIN"]
    val progress: Float? = null,          // For Continue Watching (0.0f to 1.0f)
    val timeLeft: String? = null,         // e.g., "24m left"
    val mediaType: String? = null,        // "ANIME", "MOVIE", "TV"
    val originalId: Long? = null,
    val episodeNumber: Int? = null,
    val seasonNumber: Int? = null,
    val mediaUrl: String? = null,
    val audio: String? = null,
)

/**
 * Streaming provider brand object (Netflix, Prime, Crunchyroll, etc.)
 */
data class ProviderLogo(
    val id: String,
    val name: String,
    val iconResId: Int = 0,
    val initial: String = name.take(1).uppercase(),
    val brandBgColor: Int = 0,
    val brandTextColor: Int = 0xFFFFFFFF.toInt(),
)

/**
 * The sealed class architecture powering the Multi-ViewHolder Feed Adapter.
 */
sealed class FeedRow {
    
    // 1. Auto-Scrolling Hero Banner
    data class HeroCarousel(
        val items: List<Media>
    ) : FeedRow()
    
    // 2. Continue Watching (requires progress bars)
    data class ContinueWatching(
        val title: String = "Continue Watching",
        val items: List<Media>
    ) : FeedRow()
    
    // 3. Branded Circular Logos
    data class ProviderBubbles(
        val providers: List<ProviderLogo>
    ) : FeedRow()
    
    // 4. Standard Horizontal Posters (Trending, Top Rated, Genre rails)
    data class StandardRail(
        val title: String,
        val items: List<Media>
    ) : FeedRow()

    // 5. Dynamic Filter Rail with Dropdown Option Selector & Optional Dual Pills
    data class FilterableRail(
        val railId: String,
        val title: String,
        val filterType: String,
        val selectedOption: String,
        val availableOptions: List<String>,
        val items: List<Media>,
        val pillOptions: List<String> = emptyList(),
        val activePillIndex: Int = 0,
        val isLoading: Boolean = false,
    ) : FeedRow()
}

class MediaDiffCallback : DiffUtil.ItemCallback<Media>() {
    override fun areItemsTheSame(oldItem: Media, newItem: Media): Boolean = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: Media, newItem: Media): Boolean = oldItem == newItem
}

fun com.animarvle.app.domain.MediaUi.toMedia(explicitType: String? = null): Media {
    val isMovie = format?.contains("MOVIE", ignoreCase = true) == true ||
        format?.contains("FILM", ignoreCase = true) == true
    val isTv = format?.contains("TV", ignoreCase = true) == true ||
        explicitType?.equals("TV", ignoreCase = true) == true ||
        explicitType?.equals("SERIES", ignoreCase = true) == true

    val type = explicitType ?: if (isMovie) "MOVIE" else (if (isTv) "TV" else "ANIME")
    val tagList = mutableListOf<String>()
    if (isMovie) tagList.add("MOVIE")
    else if (type == "TV" || isTv) tagList.add("SERIES")
    else tagList.add("ANIME")

    format?.let {
        val clean = it.replace('_', ' ').uppercase()
        if (!tagList.contains(clean)) tagList.add(clean)
    }
    return Media(
        id = id.toString(),
        title = title,
        posterUrl = coverUrl.orEmpty(),
        backdropUrl = bannerUrl ?: coverUrl,
        year = year?.toString(),
        score = score,
        tags = tagList,
        mediaType = type,
        originalId = id,
    )
}

fun com.animarvle.app.ui.player.HistoryItem.toMedia(): Media {
    val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
    val mins = remainingMs / 60000
    val timeLeftStr = if (mins >= 60) "${mins / 60}h ${mins % 60}m left" else if (mins > 0) "${mins}m left" else null
    val prog = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    return Media(
        id = "${mediaUrl}_$episodeNumber",
        title = title,
        posterUrl = posterUrl.orEmpty(),
        backdropUrl = posterUrl,
        year = null,
        score = null,
        tags = listOf(mediaType.uppercase()),
        progress = prog,
        timeLeft = timeLeftStr,
        mediaType = mediaType,
        originalId = mediaUrl.toLongOrNull(),
        episodeNumber = episodeNumber,
        seasonNumber = seasonNumber,
        mediaUrl = mediaUrl,
        audio = audio,
    )
}

