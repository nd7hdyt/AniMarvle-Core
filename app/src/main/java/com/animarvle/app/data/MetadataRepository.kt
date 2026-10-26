package com.animarvle.app.data

import com.animarvle.app.data.anilist.AniListApi
import com.animarvle.app.data.anilist.AniListMapper
import com.animarvle.app.data.anilist.Media
import com.animarvle.app.domain.EpisodeMetadata
import com.animarvle.app.domain.MediaUi
import com.animarvle.app.domain.ScheduleItem
import com.animarvle.app.data.source.builtin.AniZipHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

import com.animarvle.app.data.mal.MyAnimeListApi
import com.animarvle.app.data.simkl.SimklApi
import com.animarvle.app.data.tmdb.TmdbApi
import com.animarvle.app.data.tmdb.TmdbMapper

/**
 * Metadata repository (ARCH §3, §10). Provides AniList & MAL for Anime and
 * TMDB & Simkl for Movies & TV with automatic seamless fallback.
 */
class MetadataRepository(
    private val api: AniListApi = AniListApi(),
    val tmdb: TmdbApi = TmdbApi(),
    val mal: MyAnimeListApi = MyAnimeListApi(),
    val simkl: SimklApi = SimklApi(),
) {

    suspend fun trending(): List<MediaUi> = runCatching {
        val res = api.trending().map(AniListMapper::toMediaUi)
        if (res.isNotEmpty()) res else throw IllegalStateException("Empty AniList trending")
    }.getOrElse {
        runCatching {
            val malRes = mal.topAiring()
            if (malRes.isNotEmpty()) malRes else throw IllegalStateException("Empty MAL trending")
        }.getOrElse {
            simkl.popularAnime()
        }
    }

    suspend fun topAiring(): List<MediaUi> = runCatching {
        val res = api.topAiring().map(AniListMapper::toMediaUi)
        if (res.isNotEmpty()) res else throw IllegalStateException("Empty AniList topAiring")
    }.getOrElse {
        runCatching {
            val malRes = mal.topAiring()
            if (malRes.isNotEmpty()) malRes else throw IllegalStateException("Empty MAL topAiring")
        }.getOrElse {
            simkl.popularAnime()
        }
    }

    suspend fun airingNow(): List<MediaUi> = runCatching {
        val res = api.airingNow().map(AniListMapper::toMediaUi)
        if (res.isNotEmpty()) res else throw IllegalStateException("Empty AniList airingNow")
    }.getOrElse {
        runCatching {
            val malRes = mal.topAiring()
            if (malRes.isNotEmpty()) malRes else throw IllegalStateException("Empty MAL airingNow")
        }.getOrElse {
            simkl.popularAnime()
        }
    }

    suspend fun popularThisSeason(): List<MediaUi> {
        val (season, year) = Season.current()
        return runCatching {
            val res = api.popularSeason(season.apiValue, year).map(AniListMapper::toMediaUi)
            if (res.isNotEmpty()) res else throw IllegalStateException("Empty AniList seasonal")
        }.getOrElse {
            runCatching {
                val malRes = mal.seasonal(year, season.name)
                if (malRes.isNotEmpty()) malRes else throw IllegalStateException("Empty MAL seasonal")
            }.getOrElse {
                simkl.popularAnime()
            }
        }
    }

    suspend fun upcomingNextSeason(): List<MediaUi> {
        val (season, year) = Season.next()
        return runCatching {
            val res = api.popularSeason(season.apiValue, year).map(AniListMapper::toMediaUi)
            if (res.isNotEmpty()) res else throw IllegalStateException("Empty AniList upcoming")
        }.getOrElse {
            runCatching {
                val malRes = mal.seasonal(year, season.name)
                if (malRes.isNotEmpty()) malRes else throw IllegalStateException("Empty MAL upcoming")
            }.getOrElse {
                simkl.popularAnime()
            }
        }
    }

    suspend fun allTimePopular(): List<MediaUi> = runCatching {
        val res = api.allTimePopular().map(AniListMapper::toMediaUi)
        if (res.isNotEmpty()) res else throw IllegalStateException("Empty AniList allTimePopular")
    }.getOrElse {
        runCatching {
            val malRes = mal.allTimePopular()
            if (malRes.isNotEmpty()) malRes else throw IllegalStateException("Empty MAL allTimePopular")
        }.getOrElse {
            simkl.popularAnime()
        }
    }

    suspend fun topRated(): List<MediaUi> = runCatching {
        val res = api.topRated().map(AniListMapper::toMediaUi)
        if (res.isNotEmpty()) res else throw IllegalStateException("Empty AniList topRated")
    }.getOrElse {
        runCatching {
            val malRes = mal.topRated()
            if (malRes.isNotEmpty()) malRes else throw IllegalStateException("Empty MAL topRated")
        }.getOrElse {
            simkl.popularAnime()
        }
    }

    suspend fun mostFavorited(): List<MediaUi> = runCatching {
        val res = api.mostFavorited().map(AniListMapper::toMediaUi)
        if (res.isNotEmpty()) res else throw IllegalStateException("Empty AniList mostFavorited")
    }.getOrElse {
        runCatching {
            val malRes = mal.ranking("favorite")
            if (malRes.isNotEmpty()) malRes else throw IllegalStateException("Empty MAL favorite")
        }.getOrElse {
            simkl.popularAnime()
        }
    }

    suspend fun popularMovies(): List<MediaUi> = runCatching {
        val res = api.popularMovies().map(AniListMapper::toMediaUi)
        if (res.isNotEmpty()) res else throw IllegalStateException("Empty AniList popularMovies")
    }.getOrElse {
        runCatching {
            val malRes = mal.ranking("movie")
            if (malRes.isNotEmpty()) malRes else throw IllegalStateException("Empty MAL movie")
        }.getOrElse {
            simkl.popularAnime()
        }
    }

    suspend fun donghua(): List<MediaUi> = runCatching {
        api.donghua().map(AniListMapper::toMediaUi)
    }.getOrElse { emptyList() }

    // --- TMDB (Movies & TV) with Simkl Fallback ---
    suspend fun tmdbTrending(): List<MediaUi> = runCatching {
        val res = tmdb.trendingAll().map(TmdbMapper::toMediaUi)
        if (res.isNotEmpty()) res else throw IllegalStateException("Empty TMDB trendingAll")
    }.getOrElse {
        val tv = runCatching { simkl.popularTv(10) }.getOrDefault(emptyList())
        val movies = runCatching { simkl.popularMovies(10) }.getOrDefault(emptyList())
        (tv + movies).sortedByDescending { it.score ?: 0.0 }
    }

    suspend fun tmdbTrendingMovies(): List<MediaUi> = runCatching {
        val res = tmdb.trendingMovies().map(TmdbMapper::toMediaUi)
        if (res.isNotEmpty()) res else throw IllegalStateException("Empty TMDB trendingMovies")
    }.getOrElse {
        simkl.popularMovies()
    }

    suspend fun tmdbTrendingTv(): List<MediaUi> = runCatching {
        val res = tmdb.trendingTv().map(TmdbMapper::toMediaUi)
        if (res.isNotEmpty()) res else throw IllegalStateException("Empty TMDB trendingTv")
    }.getOrElse {
        simkl.popularTv()
    }

    suspend fun tmdbPopularMovies(): List<MediaUi> = runCatching {
        val res = tmdb.popularMovies().map(TmdbMapper::toMediaUi)
        if (res.isNotEmpty()) res else throw IllegalStateException("Empty TMDB popularMovies")
    }.getOrElse {
        simkl.popularMovies()
    }

    suspend fun tmdbPopularTv(): List<MediaUi> = runCatching {
        val res = tmdb.popularTv().map(TmdbMapper::toMediaUi)
        if (res.isNotEmpty()) res else throw IllegalStateException("Empty TMDB popularTv")
    }.getOrElse {
        simkl.popularTv()
    }

    suspend fun tmdbTopRated(): List<MediaUi> = runCatching {
        val res = tmdb.topRatedMovies().map(TmdbMapper::toMediaUi)
        if (res.isNotEmpty()) res else throw IllegalStateException("Empty TMDB topRated")
    }.getOrElse {
        simkl.popularMovies()
    }

    suspend fun tmdbNowPlaying(): List<MediaUi> = runCatching {
        val res = tmdb.nowPlayingMovies().map(TmdbMapper::toMediaUi)
        if (res.isNotEmpty()) res else throw IllegalStateException("Empty TMDB nowPlaying")
    }.getOrElse {
        simkl.popularMovies()
    }

    suspend fun tmdbCartoons(): List<MediaUi> = runCatching {
        tmdb.popularCartoons().map(TmdbMapper::toMediaUi)
    }.getOrElse { emptyList() }

    suspend fun tmdbKDramas(): List<MediaUi> = runCatching {
        tmdb.popularKDramas().map(TmdbMapper::toMediaUi)
    }.getOrElse { emptyList() }

    suspend fun tmdbCDramas(): List<MediaUi> = runCatching {
        tmdb.popularCDramas().map(TmdbMapper::toMediaUi)
    }.getOrElse { emptyList() }

    suspend fun discoverProviderCatalog(
        type: String, // "movie" or "tv"
        watchProviders: String? = null,
        watchRegion: String = "US",
        withGenres: String? = null,
        withOriginalLanguage: String? = null,
        sortBy: String = "popularity.desc",
        page: Int = 1
    ): List<MediaUi> = runCatching {
        tmdb.discover(
            type = type,
            page = page,
            watchProviders = watchProviders,
            watchRegion = watchRegion,
            withGenres = withGenres,
            withOriginalLanguage = withOriginalLanguage,
            sortBy = sortBy
        ).map(TmdbMapper::toMediaUi)
    }.getOrElse { emptyList() }

    suspend fun tmdbSearch(query: String): List<MediaUi> {
        val raw = runCatching {
            tmdb.searchMulti(query)
                .filter { it.mediaType != "person" && (!it.posterPath.isNullOrBlank() || !it.backdropPath.isNullOrBlank()) }
        }.getOrDefault(emptyList())

        if (raw.isNotEmpty()) {
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

        // Fallback to Simkl search
        return runCatching { simkl.searchMulti(query) }.getOrDefault(emptyList())
    }

    suspend fun tmdbSchedule(dateIso: String): Pair<Int, List<ScheduleItem>> {
        val (total, items) = tmdb.scheduleTv(dateIso)
        val epochSec = System.currentTimeMillis() / 1000
        return total to items.map { TmdbMapper.toScheduleItem(it, epochSec) }
    }

    suspend fun search(query: String): List<MediaUi> {
        val anilistResults = runCatching {
            api.search(query)
                .filter { it.coverImage?.extraLarge != null || it.coverImage?.large != null || it.bannerImage != null }
                .map(AniListMapper::toMediaUi)
        }.getOrDefault(emptyList())

        if (anilistResults.isNotEmpty()) return anilistResults

        val malResults = runCatching { mal.search(query) }.getOrDefault(emptyList())
        if (malResults.isNotEmpty()) return malResults

        return runCatching { simkl.searchAnime(query) }.getOrDefault(emptyList())
    }

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
            return null
        }

        val anilist = runCatching { api.details(id)?.let(AniListMapper::toMediaUi) }.getOrNull()
        if (anilist != null) {
            // For continuous long-running anime (One Piece, Naruto, Bleach, Detective Conan, etc.)
            // where AniList only has 1 single continuous entry, enrich seasons from TMDB TV!
            if (anilist.seasons.size <= 1 && (anilist.episodes == null || anilist.episodes!! > 24)) {
                val tmdbSeasons = runCatching {
                    val aniZip = AniZipHelper.getMappings(http, anilist.id.toInt())
                    val tmdbTvId = aniZip?.tmdbId ?: run {
                        val results = tmdb.searchMulti(anilist.title)
                        val jaMatch = results.firstOrNull { 
                            (it.mediaType == "tv" || it.numberOfEpisodes != null) && 
                            (it.originalLanguage.equals("ja", ignoreCase = true) || it.originalLanguage.isNullOrBlank()) 
                        }
                        jaMatch?.id ?: results.firstOrNull { it.mediaType == "tv" || it.numberOfEpisodes != null }?.id
                    }
                    if (tmdbTvId != null && tmdbTvId > 0) {
                        val tv = tmdb.tvDetails(tmdbTvId)
                        var runningEpCount = 1
                        tv?.seasons?.filter { (it.seasonNumber ?: 0) > 0 }?.map { s ->
                            val epCount = s.episodeCount ?: 0
                            val startEp = runningEpCount
                            runningEpCount += epCount
                            val sNum = s.seasonNumber ?: 1
                            val rawName = s.name.orEmpty().trim()
                            val cleanTitle = if (rawName.isNotBlank() && !rawName.equals("Season $sNum", ignoreCase = true)) {
                                rawName
                            } else {
                                "Season $sNum"
                            }
                            com.animarvle.app.domain.SeasonUi(
                                id = anilist.id,
                                title = cleanTitle,
                                posterUrl = s.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: anilist.coverUrl,
                                isCurrent = (sNum == 1),
                                seasonNumber = sNum,
                                episodeCount = epCount,
                                startEpisodeNumber = startEp,
                            )
                        }
                    } else null
                }.getOrNull()

                if (!tmdbSeasons.isNullOrEmpty() && tmdbSeasons.size > 1) {
                    return anilist.copy(seasons = tmdbSeasons)
                }
            }
            return anilist
        }

        val malMedia = runCatching { mal.details(id) }.getOrNull()
        if (malMedia != null) return malMedia

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
            return emptyList()
        }

        return runCatching { api.recommendations(id).map(AniListMapper::toMediaUi) }.getOrDefault(emptyList())
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

    private val episodeMetadataCache = ConcurrentHashMap<String, Map<Int, EpisodeMetadata>>()
    private val http: OkHttpClient = TmdbApi.defaultClient()

    suspend fun episodeMetadata(
        mediaId: Long,
        title: String,
        seasonNumber: Int = 1,
        isTmdb: Boolean = false,
        isAdult: Boolean = false,
        romaji: String? = null,
    ): Map<Int, EpisodeMetadata> = withContext(Dispatchers.IO) {
        val cacheKey = "${if (isTmdb) "tmdb" else "anilist"}:$mediaId:$title:$seasonNumber"
        episodeMetadataCache[cacheKey]?.let { return@withContext it }

        val result = if (isTmdb) {
            fetchTmdbEpisodeMetadata(mediaId, title, seasonNumber)
        } else {
            fetchAnimeEpisodeMetadata(mediaId, title, seasonNumber, isAdult = isAdult, romaji = romaji)
        }

        if (result.isNotEmpty()) {
            episodeMetadataCache[cacheKey] = result
        }
        result
    }

    private suspend fun fetchAnimeEpisodeMetadata(
        mediaId: Long,
        title: String,
        seasonNumber: Int,
        isAdult: Boolean = false,
        romaji: String? = null,
    ): Map<Int, EpisodeMetadata> {
        val adult = isAdult || isAdultTitle(title) || (romaji != null && isAdultTitle(romaji))

        if (adult) {
            val adultEps = fetchAdultEpisodeMetadata(title, romaji)
            if (adultEps.isNotEmpty()) return adultEps
            // For adult anime, do not allow Kitsu, AniZip, or TMDB to hijack with unrelated mainstream anime
            return emptyMap()
        }

        // 1. AniZip by AniList ID
        var anilistId = mediaId.toInt().takeIf { it > 0 }
        if (anilistId == null && title.isNotBlank()) {
            anilistId = runCatching { api.search(title).firstOrNull()?.id?.toInt() }.getOrNull()
        }

        if (anilistId != null && anilistId > 0) {
            val aniZip = runCatching { AniZipHelper.getMappings(http, anilistId) }.getOrNull()
            val titleMatches = if (title.isNotBlank() && aniZip != null && aniZip.titles.isNotEmpty()) {
                aniZip.titles.values.any { isTitleMatch(title, it) }
            } else true

            if (titleMatches) {
                val aniZipEpisodes = runCatching { AniZipHelper.getEpisodeMetadata(http, anilistId, seasonNumber) }.getOrDefault(emptyMap())
                if (aniZipEpisodes.isNotEmpty() && aniZipEpisodes.values.any { !it.thumbnailUrl.isNullOrBlank() }) {
                    return aniZipEpisodes
                }

                // Check if AniZip mapped to TMDB TV ID
                val tmdbId = aniZip?.tmdbId
                if (tmdbId != null && tmdbId > 0) {
                    val tmdbEpisodes = fetchTmdbEpisodeMetadata(tmdbId, title, seasonNumber)
                    if (tmdbEpisodes.isNotEmpty()) {
                        return tmdbEpisodes
                    }
                }
            }
        }

        // 2. Simkl episodes by title
        if (title.isNotBlank()) {
            val simklEpisodes = runCatching { simkl.fetchEpisodesByTitle(title, isTv = false, seasonNumber) }.getOrDefault(emptyMap())
            if (simklEpisodes.isNotEmpty() && simklEpisodes.values.any { !it.thumbnailUrl.isNullOrBlank() }) {
                return simklEpisodes
            }
        }

        // 3. Kitsu API by title (with strict title verification)
        val kitsuEpisodes = fetchKitsuEpisodes(title)
        if (kitsuEpisodes.isNotEmpty() && kitsuEpisodes.values.any { !it.thumbnailUrl.isNullOrBlank() }) {
            return kitsuEpisodes
        }

        // 4. Secondary Adult Fallback
        val adultEpisodes = fetchAdultEpisodeMetadata(title, romaji)
        if (adultEpisodes.isNotEmpty()) {
            return adultEpisodes
        }

        // 5. Fallback to TMDB TV
        val tmdbEpisodes = fetchTmdbEpisodeMetadata(0L, title, seasonNumber)
        if (tmdbEpisodes.isNotEmpty()) {
            return tmdbEpisodes
        }

        return emptyMap()
    }

    private suspend fun fetchTmdbEpisodeMetadata(
        mediaId: Long,
        title: String,
        seasonNumber: Int,
    ): Map<Int, EpisodeMetadata> {
        val tvId = mediaId.takeIf { it > 0 }

        if (tvId != null) {
            val episodes = runCatching { tmdb.seasonEpisodes(tvId, seasonNumber) }.getOrDefault(emptyList())
            if (episodes.isNotEmpty()) {
                return episodes.mapNotNull { ep ->
                    val epNum = ep.episodeNumber ?: return@mapNotNull null
                    epNum to EpisodeMetadata(
                        episodeNumber = epNum,
                        title = ep.name,
                        overview = ep.overview,
                        thumbnailUrl = ep.stillPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                    )
                }.toMap()
            }
        }

        // Search TMDB TV by title
        if (title.isNotBlank()) {
            val searchResults = runCatching { tmdb.searchMulti(title) }.getOrDefault(emptyList())
            val matchedTv = searchResults.firstOrNull {
                (it.mediaType == "tv" || it.numberOfEpisodes != null) &&
                isTitleMatch(title, it.name ?: it.title)
            }
            if (matchedTv != null) {
                val episodes = runCatching { tmdb.seasonEpisodes(matchedTv.id, seasonNumber) }.getOrDefault(emptyList())
                if (episodes.isNotEmpty()) {
                    return episodes.mapNotNull { ep ->
                        val epNum = ep.episodeNumber ?: return@mapNotNull null
                        epNum to EpisodeMetadata(
                            episodeNumber = epNum,
                            title = ep.name,
                            overview = ep.overview,
                            thumbnailUrl = ep.stillPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                        )
                    }.toMap()
                }
            }

            // Fallback to Simkl TV episodes by title
            val simklEpisodes = runCatching { simkl.fetchEpisodesByTitle(title, isTv = true, seasonNumber) }.getOrDefault(emptyMap())
            if (simklEpisodes.isNotEmpty()) {
                return simklEpisodes
            }
        }

        // Fallback to Kitsu
        return fetchKitsuEpisodes(title)
    }

    private suspend fun fetchKitsuEpisodes(title: String): Map<Int, EpisodeMetadata> {
        if (title.isBlank()) return emptyMap()
        return runCatching {
            val encoded = java.net.URLEncoder.encode(title, "UTF-8")
            val searchUrl = "https://kitsu.io/api/edge/anime?filter[text]=$encoded&page[limit]=5"
            val req = Request.Builder().url(searchUrl).header("User-Agent", "Animarvle/1.0").build()
            val text = http.newCall(req).execute().use { if (it.isSuccessful) it.body.string() else null } ?: return@runCatching emptyMap()
            val data = JSONObject(text).optJSONArray("data") ?: return@runCatching emptyMap()
            if (data.length() == 0) return@runCatching emptyMap()

            var matchedAnimeId: String? = null
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val attrs = item.optJSONObject("attributes") ?: continue
                val canTitle = attrs.optString("canonicalTitle")
                val titlesObj = attrs.optJSONObject("titles")
                val enTitle = titlesObj?.optString("en")
                val enJpTitle = titlesObj?.optString("en_jp")
                val jaJpTitle = titlesObj?.optString("ja_jp")
                if (isTitleMatch(title, canTitle) || isTitleMatch(title, enTitle) ||
                    isTitleMatch(title, enJpTitle) || isTitleMatch(title, jaJpTitle)
                ) {
                    matchedAnimeId = item.optString("id")
                    break
                }
            }
            if (matchedAnimeId.isNullOrBlank()) return@runCatching emptyMap()

            val resultMap = mutableMapOf<Int, EpisodeMetadata>()
            fetchKitsuEpisodePage(matchedAnimeId, 0, resultMap)
            if (resultMap.size >= 20) {
                fetchKitsuEpisodePage(matchedAnimeId, 20, resultMap)
            }
            resultMap
        }.getOrDefault(emptyMap())
    }

    private fun fetchKitsuEpisodePage(animeId: String, offset: Int, outMap: MutableMap<Int, EpisodeMetadata>) {
        runCatching {
            val epUrl = "https://kitsu.io/api/edge/episodes?filter[mediaId]=$animeId&page[limit]=20&page[offset]=$offset&sort=number"
            val req = Request.Builder().url(epUrl).header("User-Agent", "Animarvle/1.0").build()
            val text = http.newCall(req).execute().use { if (it.isSuccessful) it.body.string() else null } ?: return
            val epData = JSONObject(text).optJSONArray("data") ?: return
            for (i in 0 until epData.length()) {
                val item = epData.getJSONObject(i)
                val attrs = item.optJSONObject("attributes") ?: continue
                val epNum = attrs.optInt("number", 0).takeIf { it > 0 } ?: continue
                val canTitle = attrs.optString("canonicalTitle").takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                val syn = attrs.optString("synopsis").takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                val thumbObj = attrs.optJSONObject("thumbnail")
                val thumbUrl = thumbObj?.optString("original")?.takeIf { it.isNotBlank() }
                    ?: thumbObj?.optString("large")?.takeIf { it.isNotBlank() }
                    ?: thumbObj?.optString("medium")?.takeIf { it.isNotBlank() }
                    ?: thumbObj?.optString("small")?.takeIf { it.isNotBlank() }
                outMap[epNum] = EpisodeMetadata(
                    episodeNumber = epNum,
                    title = canTitle,
                    overview = syn,
                    thumbnailUrl = thumbUrl,
                )
            }
        }
    }

    private suspend fun fetchAdultEpisodeMetadata(
        title: String,
        romaji: String? = null,
    ): Map<Int, EpisodeMetadata> {
        if (title.isBlank()) return emptyMap()

        // 1. Serverless Cloudflare worker /api/episodes (instant <1s resolution)
        val workerRes = runCatching {
            val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
            val romajiParam = if (!romaji.isNullOrBlank()) "&romaji=" + java.net.URLEncoder.encode(romaji, "UTF-8") else ""
            val workerUrl = "${com.animarvle.app.data.source.builtin.AnimarvleStreamSource.DEFAULT_WORKER_URL}/api/episodes?title=$encodedTitle$romajiParam"
            val req = Request.Builder()
                .url(workerUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 AnimarvleApp")
                .build()
            val resp = http.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body.string()
                val json = JSONObject(body)
                if (json.optBoolean("success", false)) {
                    val arr = json.optJSONArray("episodes") ?: org.json.JSONArray()
                    val map = mutableMapOf<Int, EpisodeMetadata>()
                    for (i in 0 until arr.length()) {
                        val ep = arr.getJSONObject(i)
                        val epNum = ep.optInt("episode", i + 1)
                        val epTitle = ep.optString("title", "Episode $epNum")
                        val thumb = if (ep.has("thumbnail") && !ep.isNull("thumbnail")) ep.getString("thumbnail") else null
                        map[epNum] = EpisodeMetadata(
                            episodeNumber = epNum,
                            title = epTitle,
                            thumbnailUrl = thumb,
                            overview = null,
                        )
                    }
                    if (map.isNotEmpty()) return@runCatching map
                }
            }
            null
        }.getOrNull()

        if (!workerRes.isNullOrEmpty()) {
            return workerRes
        }

        // 2. Direct HentaiMama multi-query scraper fallback
        return runCatching {
            val cleanTitle = title.replace(Regex("\\s*\\((?:Dub|Sub|TV|Uncensored|Raw)\\)", RegexOption.IGNORE_CASE), "").trim()
            val alphaTitle = cleanTitle.replace(Regex("[^a-zA-Z0-9]"), " ").replace(Regex("\\s+"), " ").trim()
            val words = cleanTitle.split(Regex("\\s+"))
            val first3Words = if (words.size > 2) words.take(3).joinToString(" ") else ""

            val queryCandidates = linkedSetOf<String>()
            if (cleanTitle.isNotBlank()) queryCandidates.add(cleanTitle)
            if (alphaTitle.isNotBlank()) queryCandidates.add(alphaTitle)
            if (first3Words.isNotBlank()) queryCandidates.add(first3Words)
            if (!romaji.isNullOrBlank()) {
                val cleanRomaji = romaji.replace(Regex("\\s*\\((?:Dub|Sub|TV|Uncensored|Raw)\\)", RegexOption.IGNORE_CASE), "").trim()
                if (cleanRomaji.isNotBlank()) queryCandidates.add(cleanRomaji)
            }

            for (q in queryCandidates) {
                if (q.length < 2) continue
                val searchUrl = "https://hentaimama.io/?s=" + java.net.URLEncoder.encode(q, "UTF-8")
                val req = Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val resp = http.newCall(req).execute()
                if (!resp.isSuccessful) continue
                val html = resp.body.string()

                val tvRegex = Regex("https://hentaimama\\.io/tvshows/[^\"'\\s]+")
                val tvLinks = tvRegex.findAll(html).map { it.value }.toSet().toList()
                val showUrl = tvLinks.firstOrNull { l ->
                    isTitleMatch(cleanTitle, l.replace('-', ' '))
                } ?: (if (tvLinks.size == 1) tvLinks[0] else null)

                if (showUrl != null) {
                    val showReq = Request.Builder()
                        .url(showUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build()
                    val showResp = http.newCall(showReq).execute()
                    if (showResp.isSuccessful) {
                        val showHtml = showResp.body.string()
                        val itemRegex = Regex("<a class=\"dt-se-item\"[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)
                        val map = mutableMapOf<Int, EpisodeMetadata>()
                        itemRegex.findAll(showHtml).forEachIndexed { idx, match ->
                            val epUrl = match.groupValues[1]
                            val inner = match.groupValues[2]
                            val imgMatch = Regex("<img[^>]+src=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(inner)
                            val numMatch = Regex("<b class=\"dt-se-num\">[^<]*?(\\d+)</b>", RegexOption.IGNORE_CASE).find(inner)
                                ?: Regex("episode-(\\d+)", RegexOption.IGNORE_CASE).find(epUrl)
                                ?: Regex("alt=\"Episode\\s*(\\d+)\"", RegexOption.IGNORE_CASE).find(inner)
                            val titleMatch = Regex("<span class=\"dt-se-title\">([^<]+)</span>", RegexOption.IGNORE_CASE).find(inner)
                                ?: Regex("alt=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(inner)

                            val epNum = numMatch?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
                            val thumb = imgMatch?.groupValues?.get(1)
                            val rawTitle = titleMatch?.groupValues?.get(1)?.trim()
                            val epTitle = if (!rawTitle.isNullOrBlank() && !rawTitle.equals("null", ignoreCase = true)) rawTitle else "Episode $epNum"
                            map[epNum] = EpisodeMetadata(
                                episodeNumber = epNum,
                                title = epTitle,
                                thumbnailUrl = thumb,
                                overview = null,
                            )
                        }
                        if (map.isNotEmpty()) return@runCatching map
                    }
                }
            }
            emptyMap()
        }.getOrDefault(emptyMap())
    }

    companion object {
        fun localTimeZone(): TimeZone = TimeZone.getDefault()

        private val STOP_WORDS = setOf("the", "and", "for", "with", "from", "animation", "project", "season")

        val KNOWN_ADULT_KEYWORDS = setOf(
            "overflow", "euphoria", "mankitsu", "resort boin", "boku no pico",
            "eroge", "fella pure", "fela pure", "mitarashi", "kaihatsu zanmai",
            "araiya", "adam's sweet agony", "kimi omou koi", "what she fell on",
            "bikini warriors", "seven mortal sins", "tsunpure", "fault", "rance",
            "disciplina", "night shift nurses", "discipline", "bible black",
            "itadaki seieki", "kuroinu", "shoujo ramune", "ane wa yanmama",
            "dropout", "kyonyuu", "tamashii insert", "dokidoki little",
            "onichichi", "succubus stayed", "shoujo kyouiku", "harem camp"
        )

        fun isAdultTitle(title: String?): Boolean {
            if (title.isNullOrBlank()) return false
            val lower = title.trim().lowercase(Locale.ROOT)
            return KNOWN_ADULT_KEYWORDS.any { lower.contains(it) }
        }

        fun normalizeTitle(title: String?): String {
            if (title.isNullOrBlank()) return ""
            return title.lowercase(Locale.ROOT)
                .replace(Regex("\\s*\\((?:dub|sub|raw|tv|uncensored|the animation)\\)", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\[(?:dub|sub|raw|tv|uncensored|the animation)\\]", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("[^a-z0-9]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        fun isTitleMatch(query: String?, target: String?): Boolean {
            val q = normalizeTitle(query)
            val t = normalizeTitle(target)
            if (q.isBlank() || t.isBlank()) return false
            if (q == t) return true

            if (q.length >= 4 && t.contains(q)) return true
            if (t.length >= 4 && q.contains(t)) return true

            val qTokens = q.split(" ").filter { it.length > 2 && it !in STOP_WORDS }
            val tTokens = t.split(" ").filter { it.length > 2 && it !in STOP_WORDS }
            if (qTokens.isEmpty() || tTokens.isEmpty()) return false

            val common = qTokens.intersect(tTokens.toSet())
            if (common.isNotEmpty()) {
                if (qTokens.size == 1 && tTokens.contains(qTokens.first())) return true
                if (tTokens.size == 1 && qTokens.contains(tTokens.first())) return true
                if (common.size.toDouble() / minOf(qTokens.size, tTokens.size) >= 0.5) return true
            }
            return false
        }
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