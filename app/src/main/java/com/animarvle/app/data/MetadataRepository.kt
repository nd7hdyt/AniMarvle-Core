package com.animarvle.app.data

import com.animarvle.app.data.anilist.AniListApi
import com.animarvle.app.data.anilist.AniListMapper
import com.animarvle.app.data.anilist.Media
import com.animarvle.app.domain.MediaUi
import com.animarvle.app.domain.ScheduleItem
import java.util.Calendar
import java.util.TimeZone

import com.animarvle.app.data.tmdb.TmdbApi
import com.animarvle.app.data.tmdb.TmdbMapper

/**
 * Metadata repository (ARCH §3, §10). Provides both AniList for Anime and
 * TMDB for Movies & TV.
 */
class MetadataRepository(
    private val api: AniListApi = AniListApi(),
    val tmdb: TmdbApi = TmdbApi(),
) {

    suspend fun trending(): List<MediaUi> = api.trending().map(AniListMapper::toMediaUi)

    suspend fun topAiring(): List<MediaUi> = api.topAiring().map(AniListMapper::toMediaUi)

    suspend fun airingNow(): List<MediaUi> = api.airingNow().map(AniListMapper::toMediaUi)

    suspend fun popularThisSeason(): List<MediaUi> {
        val (season, year) = Season.current()
        return api.popularSeason(season.apiValue, year).map(AniListMapper::toMediaUi)
    }

    suspend fun upcomingNextSeason(): List<MediaUi> {
        val (season, year) = Season.next()
        return api.popularSeason(season.apiValue, year).map(AniListMapper::toMediaUi)
    }

    suspend fun allTimePopular(): List<MediaUi> = api.allTimePopular().map(AniListMapper::toMediaUi)

    suspend fun topRated(): List<MediaUi> = api.topRated().map(AniListMapper::toMediaUi)

    suspend fun mostFavorited(): List<MediaUi> = api.mostFavorited().map(AniListMapper::toMediaUi)

    suspend fun popularMovies(): List<MediaUi> = api.popularMovies().map(AniListMapper::toMediaUi)

    suspend fun donghua(): List<MediaUi> = api.donghua().map(AniListMapper::toMediaUi)

    // --- TMDB (Movies & TV) ---
    suspend fun tmdbTrending(): List<MediaUi> = tmdb.trendingAll().map(TmdbMapper::toMediaUi)

    suspend fun tmdbTrendingMovies(): List<MediaUi> = tmdb.trendingMovies().map(TmdbMapper::toMediaUi)

    suspend fun tmdbTrendingTv(): List<MediaUi> = tmdb.trendingTv().map(TmdbMapper::toMediaUi)

    suspend fun tmdbPopularMovies(): List<MediaUi> = tmdb.popularMovies().map(TmdbMapper::toMediaUi)

    suspend fun tmdbPopularTv(): List<MediaUi> = tmdb.popularTv().map(TmdbMapper::toMediaUi)

    suspend fun tmdbTopRated(): List<MediaUi> = tmdb.topRatedMovies().map(TmdbMapper::toMediaUi)

    suspend fun tmdbNowPlaying(): List<MediaUi> = tmdb.nowPlayingMovies().map(TmdbMapper::toMediaUi)

    suspend fun tmdbCartoons(): List<MediaUi> = tmdb.popularCartoons().map(TmdbMapper::toMediaUi)

    suspend fun tmdbKDramas(): List<MediaUi> = tmdb.popularKDramas().map(TmdbMapper::toMediaUi)

    suspend fun tmdbCDramas(): List<MediaUi> = tmdb.popularCDramas().map(TmdbMapper::toMediaUi)

    suspend fun tmdbSearch(query: String): List<MediaUi> {
        val raw = tmdb.searchMulti(query)
            .filter { it.mediaType != "person" && (!it.posterPath.isNullOrBlank() || !it.backdropPath.isNullOrBlank()) }
        val qClean = query.trim()
        val sorted = raw.sortedWith(
            compareByDescending<com.animarvle.app.data.tmdb.TmdbItem> {
                it.title?.equals(qClean, ignoreCase = true) == true || it.name?.equals(qClean, ignoreCase = true) == true
            }
                .thenByDescending {
                    it.title?.startsWith(qClean, ignoreCase = true) == true || it.name?.startsWith(qClean, ignoreCase = true) == true
                }
                .thenByDescending { (it.voteCount ?: 0) > 0 }
                .thenByDescending { it.popularity ?: 0.0 }
        )
        return sorted.map(TmdbMapper::toMediaUi)
    }

    suspend fun tmdbSchedule(dateIso: String): Pair<Int, List<ScheduleItem>> {
        val (total, items) = tmdb.scheduleTv(dateIso)
        val epochSec = System.currentTimeMillis() / 1000
        return total to items.map { TmdbMapper.toScheduleItem(it, epochSec) }
    }

    suspend fun search(query: String): List<MediaUi> =
        api.search(query)
            .filter { it.coverImage?.extraLarge != null || it.coverImage?.large != null || it.bannerImage != null }
            .map(AniListMapper::toMediaUi)

    suspend fun details(id: Long, preferTmdb: Boolean = false, mediaType: String? = null): MediaUi? {
        val isTv = mediaType?.equals("tv", ignoreCase = true) == true || mediaType?.equals("series", ignoreCase = true) == true
        val isMovie = mediaType?.equals("movie", ignoreCase = true) == true

        if (preferTmdb) {
            if (isTv) {
                val tmdbTv = runCatching { tmdb.tvDetails(id)?.let(TmdbMapper::toMediaUi) }.getOrNull()
                if (tmdbTv != null) return tmdbTv
                val tmdbMovie = runCatching { tmdb.movieDetails(id)?.let(TmdbMapper::toMediaUi) }.getOrNull()
                if (tmdbMovie != null) return tmdbMovie
            } else if (isMovie) {
                val tmdbMovie = runCatching { tmdb.movieDetails(id)?.let(TmdbMapper::toMediaUi) }.getOrNull()
                if (tmdbMovie != null) return tmdbMovie
                val tmdbTv = runCatching { tmdb.tvDetails(id)?.let(TmdbMapper::toMediaUi) }.getOrNull()
                if (tmdbTv != null) return tmdbTv
            } else {
                val rawMovie = runCatching { tmdb.movieDetails(id) }.getOrNull()
                val rawTv = runCatching { tmdb.tvDetails(id) }.getOrNull()
                if (rawTv != null && rawMovie != null) {
                    val tvPop = rawTv.popularity ?: 0.0
                    val moviePop = rawMovie.popularity ?: 0.0
                    return if (tvPop >= moviePop) TmdbMapper.toMediaUi(rawTv) else TmdbMapper.toMediaUi(rawMovie)
                }
                if (rawMovie != null) return TmdbMapper.toMediaUi(rawMovie)
                if (rawTv != null) return TmdbMapper.toMediaUi(rawTv)
            }
            return runCatching { api.details(id)?.let(AniListMapper::toMediaUi) }.getOrNull()
        }

        val anilist = runCatching { api.details(id)?.let(AniListMapper::toMediaUi) }.getOrNull()
        if (anilist != null) return anilist

        if (isTv) {
            val tmdbTv = runCatching { tmdb.tvDetails(id)?.let(TmdbMapper::toMediaUi) }.getOrNull()
            if (tmdbTv != null) return tmdbTv
            val tmdbMovie = runCatching { tmdb.movieDetails(id)?.let(TmdbMapper::toMediaUi) }.getOrNull()
            if (tmdbMovie != null) return tmdbMovie
        } else if (isMovie) {
            val tmdbMovie = runCatching { tmdb.movieDetails(id)?.let(TmdbMapper::toMediaUi) }.getOrNull()
            if (tmdbMovie != null) return tmdbMovie
            val tmdbTv = runCatching { tmdb.tvDetails(id)?.let(TmdbMapper::toMediaUi) }.getOrNull()
            if (tmdbTv != null) return tmdbTv
        } else {
            val rawMovie = runCatching { tmdb.movieDetails(id) }.getOrNull()
            val rawTv = runCatching { tmdb.tvDetails(id) }.getOrNull()
            if (rawTv != null && rawMovie != null) {
                val tvPop = rawTv.popularity ?: 0.0
                val moviePop = rawMovie.popularity ?: 0.0
                return if (tvPop >= moviePop) TmdbMapper.toMediaUi(rawTv) else TmdbMapper.toMediaUi(rawMovie)
            }
            if (rawMovie != null) return TmdbMapper.toMediaUi(rawMovie)
            if (rawTv != null) return TmdbMapper.toMediaUi(rawTv)
        }
        return null
    }

    /** "More like this" (PROMPT §7.6). AniList or TMDB recommendations, rated best-first. */
    suspend fun recommendations(id: Long, preferTmdb: Boolean = false, mediaType: String? = null): List<MediaUi> {
        val isTv = mediaType?.equals("tv", ignoreCase = true) == true || mediaType?.equals("series", ignoreCase = true) == true

        if (preferTmdb) {
            if (isTv) {
                val tmdbTv = runCatching { tmdb.tvRecommendations(id).map(TmdbMapper::toMediaUi) }.getOrDefault(emptyList())
                if (tmdbTv.isNotEmpty()) return tmdbTv
                val tmdbMovie = runCatching { tmdb.movieRecommendations(id).map(TmdbMapper::toMediaUi) }.getOrDefault(emptyList())
                if (tmdbMovie.isNotEmpty()) return tmdbMovie
            } else {
                val tmdbMovie = runCatching { tmdb.movieRecommendations(id).map(TmdbMapper::toMediaUi) }.getOrDefault(emptyList())
                if (tmdbMovie.isNotEmpty()) return tmdbMovie
                val tmdbTv = runCatching { tmdb.tvRecommendations(id).map(TmdbMapper::toMediaUi) }.getOrDefault(emptyList())
                if (tmdbTv.isNotEmpty()) return tmdbTv
            }
            return runCatching { api.recommendations(id).map(AniListMapper::toMediaUi) }.getOrDefault(emptyList())
        }

        val anilist = runCatching { api.recommendations(id).map(AniListMapper::toMediaUi) }.getOrDefault(emptyList())
        if (anilist.isNotEmpty()) return anilist

        if (isTv) {
            val tmdbTv = runCatching { tmdb.tvRecommendations(id).map(TmdbMapper::toMediaUi) }.getOrDefault(emptyList())
            if (tmdbTv.isNotEmpty()) return tmdbTv
            return runCatching { tmdb.movieRecommendations(id).map(TmdbMapper::toMediaUi) }.getOrDefault(emptyList())
        } else {
            val tmdbMovie = runCatching { tmdb.movieRecommendations(id).map(TmdbMapper::toMediaUi) }.getOrDefault(emptyList())
            if (tmdbMovie.isNotEmpty()) return tmdbMovie
            return runCatching { tmdb.tvRecommendations(id).map(TmdbMapper::toMediaUi) }.getOrDefault(emptyList())
        }
    }

    /** Episodes airing between local midnight today and the end of the given day offset. */
    suspend fun scheduleFromToday(dayOffset: Int = 0): List<ScheduleItem> {
        val start = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        return api.schedule(start.timeInMillis / 1000, end.timeInMillis / 1000)
            .mapNotNull(AniListMapper::toScheduleItem)
            .sortedBy { it.airingAtEpochSec }
    }

    companion object {
        fun localTimeZone(): TimeZone = TimeZone.getDefault()
    }
}

enum class Season(val apiValue: String) {
    WINTER("WINTER"),
    SPRING("SPRING"),
    SUMMER("SUMMER"),
    FALL("FALL");

    companion object {
        fun current(calendar: Calendar = Calendar.getInstance()): Pair<Season, Int> {
            val month = calendar.get(Calendar.MONTH)
            val year = calendar.get(Calendar.YEAR)
            val season = when (month) {
                Calendar.JANUARY, Calendar.FEBRUARY, Calendar.MARCH -> WINTER
                Calendar.APRIL, Calendar.MAY, Calendar.JUNE -> SPRING
                Calendar.JULY, Calendar.AUGUST, Calendar.SEPTEMBER -> SUMMER
                else -> FALL
            }
            return season to year
        }

        fun next(calendar: Calendar = Calendar.getInstance()): Pair<Season, Int> {
            val (currentSeason, year) = current(calendar)
            return when (currentSeason) {
                WINTER -> SPRING to year
                SPRING -> SUMMER to year
                SUMMER -> FALL to year
                FALL -> WINTER to (year + 1)
            }
        }
    }
}