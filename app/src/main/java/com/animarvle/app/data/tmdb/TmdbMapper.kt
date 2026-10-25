package com.animarvle.app.data.tmdb

import com.animarvle.app.domain.AiringStatus
import com.animarvle.app.domain.MediaUi
import com.animarvle.app.domain.ScheduleItem

object TmdbMapper {

    private const val IMG_W500 = "https://image.tmdb.org/t/p/w500"
    private const val IMG_ORIGINAL = "https://image.tmdb.org/t/p/original"

    private val GENRE_MAP = mapOf(
        28 to "Action",
        12 to "Adventure",
        16 to "Animation",
        35 to "Comedy",
        80 to "Crime",
        99 to "Documentary",
        18 to "Drama",
        10751 to "Family",
        14 to "Fantasy",
        36 to "History",
        27 to "Horror",
        10402 to "Music",
        9648 to "Mystery",
        10749 to "Romance",
        878 to "Sci-Fi",
        10770 to "TV Movie",
        53 to "Thriller",
        10752 to "War",
        37 to "Western",
        10759 to "Action & Adventure",
        10762 to "Kids",
        10763 to "News",
        10764 to "Reality",
        10765 to "Sci-Fi & Fantasy",
        10766 to "Soap",
        10767 to "Talk",
        10768 to "War & Politics",
    )

    fun toMediaUi(item: TmdbItem): MediaUi {
        val displayTitle = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: "Unknown"
        val dateStr = item.releaseDate ?: item.firstAirDate
        val parsedYear = dateStr?.take(4)?.toIntOrNull()
        val format = if (item.name != null || item.mediaType == "tv") "TV" else "MOVIE"

        val genreNames = if (item.genres.isNotEmpty()) {
            item.genres.map { it.name }
        } else {
            item.genreIds.mapNotNull { GENRE_MAP[it] }
        }

        val totalEpisodes = item.numberOfEpisodes ?: if (format == "MOVIE") 1 else null

        return MediaUi(
            id = item.id,
            title = displayTitle,
            romajiTitle = item.originalTitle ?: item.originalName,
            nativeTitle = null,
            synonyms = emptyList(),
            synopsis = item.overview,
            coverUrl = item.posterPath?.let { "$IMG_W500$it" },
            bannerUrl = item.backdropPath?.let { "$IMG_ORIGINAL$it" } ?: item.posterPath?.let { "$IMG_W500$it" },
            genres = genreNames,
            score = item.voteAverage?.let { (it * 10).toInt() / 10.0 },
            year = parsedYear,
            startDateIso = dateStr,
            format = format,
            episodes = totalEpisodes,
            durationMinutes = item.runtime,
            status = AiringStatus.RELEASING,
            nextAiringEpisode = null,
            nextAiringAtEpochSec = null,
            seasons = item.seasons.filter { (it.seasonNumber ?: 0) > 0 }.map { s ->
                com.animarvle.app.domain.SeasonUi(
                    id = item.id,
                    title = s.name ?: "Season ${s.seasonNumber}",
                    posterUrl = s.posterPath?.let { "$IMG_W500$it" } ?: item.posterPath?.let { "$IMG_W500$it" },
                    isCurrent = (s.seasonNumber == 1),
                    seasonNumber = s.seasonNumber,
                    episodeCount = s.episodeCount,
                )
            },
            isTmdb = true,
        )
    }

    fun toScheduleItem(item: TmdbItem, epochSec: Long): ScheduleItem {
        val displayTitle = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: "Unknown"
        val format = if (item.name != null || item.mediaType == "tv") "Series" else "Movie"

        return ScheduleItem(
            mediaId = item.id,
            title = displayTitle,
            coverUrl = item.backdropPath?.let { "$IMG_W500$it" } ?: item.posterPath?.let { "$IMG_W500$it" },
            episode = null,
            airingAtEpochSec = epochSec,
            format = format,
            totalEpisodes = null,
        )
    }
}
