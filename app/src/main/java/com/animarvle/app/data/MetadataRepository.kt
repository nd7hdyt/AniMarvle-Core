package com.animarvle.app.data

import com.animarvle.app.data.anilist.AniListApi
import com.animarvle.app.data.anilist.AniListMapper
import com.animarvle.app.data.anilist.Media
import com.animarvle.app.domain.EpisodeMetadata
import com.animarvle.app.domain.MediaUi
import com.animarvle.app.domain.ScheduleItem
import com.animarvle.app.data.source.builtin.AniZipHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    val customMediaCache = ConcurrentHashMap<Long, MediaUi>()

    fun cacheCustomMedia(media: MediaUi) {
        customMediaCache[media.id] = media
    }

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
    suspend fun tmdbTrending(pages: Int = 3, startPage: Int = 1): List<MediaUi> = runCatching {
        coroutineScope {
            (startPage until (startPage + pages)).map { p ->
                async { tmdb.trendingAll(page = p).map(TmdbMapper::toMediaUi) }
            }.awaitAll().flatten().distinctBy { "${it.id}:${it.format}" }
        }.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("Empty TMDB trendingAll")
    }.getOrElse {
        val tv = runCatching { simkl.popularTv(20) }.getOrDefault(emptyList())
        val movies = runCatching { simkl.popularMovies(20) }.getOrDefault(emptyList())
        (tv + movies).sortedByDescending { it.score ?: 0.0 }
    }

    suspend fun tmdbTrendingMovies(pages: Int = 3, startPage: Int = 1): List<MediaUi> = runCatching {
        coroutineScope {
            (startPage until (startPage + pages)).map { p ->
                async { tmdb.trendingMovies(page = p).map(TmdbMapper::toMediaUi) }
            }.awaitAll().flatten().distinctBy { it.id }
        }.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("Empty TMDB trendingMovies")
    }.getOrElse {
        simkl.popularMovies()
    }

    suspend fun tmdbTrendingTv(pages: Int = 3, startPage: Int = 1): List<MediaUi> = runCatching {
        coroutineScope {
            (startPage until (startPage + pages)).map { p ->
                async { tmdb.trendingTv(page = p).map(TmdbMapper::toMediaUi) }
            }.awaitAll().flatten().distinctBy { it.id }
        }.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("Empty TMDB trendingTv")
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
        val res = tmdb.popularCartoons().map(TmdbMapper::toMediaUi)
        if (res.isNotEmpty()) res else throw IllegalStateException("Empty cartoons")
    }.getOrElse {
        runCatching { simkl.popularTv(10) }.getOrDefault(emptyList())
    }

    suspend fun tmdbKDramas(pages: Int = 3, startPage: Int = 1): List<MediaUi> = runCatching {
        val res = coroutineScope {
            (startPage until (startPage + pages)).map { p ->
                async { tmdb.popularKDramas(page = p).map(TmdbMapper::toMediaUi) }
            }.awaitAll().flatten().distinctBy { it.id }
        }
        if (res.isNotEmpty()) res else throw IllegalStateException("Empty kdramas")
    }.getOrElse {
        runCatching { simkl.popularTv(20) }.getOrDefault(emptyList())
    }

    suspend fun tmdbCDramas(): List<MediaUi> = runCatching {
        val res = tmdb.popularCDramas().map(TmdbMapper::toMediaUi)
        if (res.isNotEmpty()) res else throw IllegalStateException("Empty cdramas")
    }.getOrElse {
        runCatching { simkl.popularTv(10) }.getOrDefault(emptyList())
    }

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

    suspend fun tmdbSearch(query: String, pages: Int = 3, startPage: Int = 1): List<MediaUi> {
        val raw = runCatching {
            coroutineScope {
                (startPage until (startPage + pages)).map { p ->
                    async {
                        tmdb.searchMulti(query, page = p)
                            .filter { it.mediaType != "person" && (!it.posterPath.isNullOrBlank() || !it.backdropPath.isNullOrBlank()) }
                    }
                }.awaitAll().flatten().distinctBy { "${it.id}:${it.mediaType}" }
            }
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

    suspend fun tmdbSchedule(dateIso: String): Pair<Int, List<ScheduleItem>> = runCatching {
        val (total, items) = tmdb.scheduleTv(dateIso)
        val epochSec = System.currentTimeMillis() / 1000
        total to items.map { TmdbMapper.toScheduleItem(it, epochSec) }
    }.getOrElse {
        val airing = runCatching { simkl.scheduleTv(dateIso) }.getOrDefault(emptyList())
        airing.size to airing
    }

    suspend fun search(query: String, page: Int = 1): List<MediaUi> {
        val anilistResults = runCatching {
            api.search(query, page = page, includeAdult = false)
                .filter { it.coverImage?.extraLarge != null || it.coverImage?.large != null || it.bannerImage != null }
                .map(AniListMapper::toMediaUi)
        }.getOrDefault(emptyList())

        if (anilistResults.isNotEmpty()) return anilistResults

        val anilistAdultResults = runCatching {
            api.search(query, page = page, includeAdult = true)
                .filter { it.coverImage?.extraLarge != null || it.coverImage?.large != null || it.bannerImage != null }
                .map(AniListMapper::toMediaUi)
        }.getOrDefault(emptyList())

        if (anilistAdultResults.isNotEmpty()) return anilistAdultResults

        if (page > 1) return emptyList()

        val malResults = runCatching { mal.search(query) }.getOrDefault(emptyList())
        if (malResults.isNotEmpty()) return malResults

        return runCatching { simkl.searchAnime(query) }.getOrDefault(emptyList())
    }

    suspend fun details(id: Long, preferTmdb: Boolean = false, mediaType: String? = null): MediaUi? {
        customMediaCache[id]?.let { return it }
        val isTv = mediaType?.equals("tv", ignoreCase = true) == true || mediaType?.equals("series", ignoreCase = true) == true
        val isMovie = mediaType?.equals("movie", ignoreCase = true) == true

        if (preferTmdb) {
            val tmdbResult = runCatching {
                if (isTv) {
                    val tv = tmdb.tvDetails(id)?.let(TmdbMapper::toMediaUi)
                    if (tv != null) return@runCatching tv
                    tmdb.movieDetails(id)?.let(TmdbMapper::toMediaUi)
                } else if (isMovie) {
                    val movie = tmdb.movieDetails(id)?.let(TmdbMapper::toMediaUi)
                    if (movie != null) return@runCatching movie
                    tmdb.tvDetails(id)?.let(TmdbMapper::toMediaUi)
                } else {
                    val rawMovie = tmdb.movieDetails(id)
                    val rawTv = tmdb.tvDetails(id)
                    if (rawTv != null && rawMovie != null) {
                        val tvPop = rawTv.popularity ?: 0.0
                        val moviePop = rawMovie.popularity ?: 0.0
                        if (tvPop >= moviePop) TmdbMapper.toMediaUi(rawTv) else TmdbMapper.toMediaUi(rawMovie)
                    } else if (rawMovie != null) {
                        TmdbMapper.toMediaUi(rawMovie)
                    } else if (rawTv != null) {
                        TmdbMapper.toMediaUi(rawTv)
                    } else null
                }
            }.getOrNull()

            if (tmdbResult != null) return tmdbResult

            // Fallback to Simkl details if TMDB fails or is unavailable
            val simklResult = runCatching { simkl.fetchDetails(id, isTv = isTv) }.getOrNull()
            if (simklResult != null) return simklResult

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

        val simklMedia = runCatching { simkl.fetchDetails(id, isTv = false) }.getOrNull()
        if (simklMedia != null) return simklMedia

        return null
    }

    /** "More like this" (PROMPT §7.6). AniList or TMDB recommendations, rated best-first. */
    suspend fun recommendations(id: Long, preferTmdb: Boolean = false, mediaType: String? = null): List<MediaUi> {
        // 1. Custom / Asian cinema recommendations
        if (id >= 800000000L || customMediaCache.containsKey(id)) {
            val cached = customMediaCache[id]
            val title = cached?.title.orEmpty()
            val recs = mutableListOf<MediaUi>()

            if (title.isNotBlank()) {
                val codeMatch = Regex("""\b([A-Za-z]{2,6})[-_]?(\d{2,5})\b""").find(title)
                if (codeMatch != null) {
                    val prefix = codeMatch.groupValues[1]
                    val studioRecs = runCatching { searchAsianCinema(prefix) }.getOrDefault(emptyList()).filter { it.id != id }
                    recs.addAll(studioRecs.take(12))
                } else {
                    val cleanT = title.replace(Regex("""\[[^\]]+\]"""), "").split("|").firstOrNull()?.trim() ?: title
                    val words = cleanT.split(Regex("""[\s\-_:]+""")).filter { it.length >= 3 }
                    val query = if (words.size >= 2) "${words[0]} ${words[1]}" else words.firstOrNull() ?: cleanT
                    val related = runCatching { searchAsianCinema(query) }.getOrDefault(emptyList()).filter { it.id != id }
                    recs.addAll(related.take(12))
                }
            }

            if (recs.size < 6) {
                val cachedOthers = customMediaCache.values.filter { it.id != id && !recs.any { r -> r.id == it.id } }
                recs.addAll(cachedOthers.take(12 - recs.size))
            }

            if (recs.isNotEmpty()) return recs
        }

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
            // Fallback to Simkl popular items as recommendations
            val simklRecs = if (isTv) runCatching { simkl.popularTv(10) }.getOrDefault(emptyList()) else runCatching { simkl.popularMovies(10) }.getOrDefault(emptyList())
            return simklRecs
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
            // Fallback directly to Simkl by mediaId
            val simklEpisodesById = runCatching { simkl.fetchEpisodes(tvId, isTv = true, seasonNumber) }.getOrDefault(emptyMap())
            if (simklEpisodesById.isNotEmpty()) {
                return simklEpisodesById
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
            "itadaki", "seieki", "itadaki seieki", "vampire vixen",
            "kuroinu", "shoujo ramune", "ane wa yanmama",
            "dropout", "kyonyuu", "tamashii insert", "dokidoki little",
            "onichichi", "succubus stayed", "shoujo kyouiku", "harem camp",
            "hentai"
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


    suspend fun tmdbDiscoverFiltered(
        type: String, // "movie" or "tv"
        pages: Int = 3,
        startPage: Int = 1,
        withGenres: String? = null,
        withOriginalLanguage: String? = null,
        sortBy: String = "popularity.desc",
        withCast: String? = null,
        withCrew: String? = null,
        withCompanies: String? = null,
        withKeywords: String? = null,
        voteAverageGte: Double? = null,
        primaryReleaseYear: String? = null,
        includeAdult: Boolean = false,
    ): List<MediaUi> = runCatching {
        coroutineScope {
            (startPage until (startPage + pages)).map { p ->
                async {
                    tmdb.discover(
                        type = type,
                        page = p,
                        withGenres = withGenres,
                        withOriginalLanguage = withOriginalLanguage,
                        sortBy = sortBy,
                        withCast = withCast,
                        withCrew = withCrew,
                        withCompanies = withCompanies,
                        withKeywords = withKeywords,
                        voteAverageGte = voteAverageGte,
                        primaryReleaseYear = primaryReleaseYear,
                        includeAdult = includeAdult,
                    ).map(TmdbMapper::toMediaUi)
                }
            }.awaitAll().flatten().distinctBy { it.id }
        }
    }.getOrDefault(emptyList())

    suspend fun tmdbAdultRealMovies(pages: Int = 3, startPage: Int = 1): List<MediaUi> = runCatching {
        tmdbDiscoverFiltered(
            type = "movie",
            pages = pages,
            startPage = startPage,
            withKeywords = "207767|256466|298666",
            includeAdult = true,
        ).map { media ->
            if (!media.genres.contains("Adult")) {
                media.copy(genres = media.genres + "Adult")
            } else {
                media
            }
        }
    }.getOrDefault(emptyList())

    suspend fun anilistDiscoverFiltered(
        genre: String? = null,
        tag: String? = null,
        format: String? = null,
        year: Int? = null,
        sortBy: String = "POPULARITY_DESC",
        pages: Int = 2,
        startPage: Int = 1,
    ): List<MediaUi> = runCatching {
        val isHentai = genre.equals("Hentai", ignoreCase = true) || tag.equals("Hentai", ignoreCase = true)
        coroutineScope {
            (startPage until (startPage + pages)).map { p ->
                async {
                    api.discoverAnime(
                        page = p,
                        perPage = 50,
                        genre = genre,
                        format = format,
                        year = year,
                        sort = sortBy,
                        isAdultOnly = isHentai,
                        includeAdult = isHentai,
                    ).map { aniMedia ->
                        val ui = AniListMapper.toMediaUi(aniMedia)
                        if (isHentai && !ui.genres.any { it.equals("Hentai", ignoreCase = true) }) {
                            ui.copy(genres = ui.genres + "Hentai")
                        } else {
                            ui
                        }
                    }
                }
            }.awaitAll().flatten().distinctBy { it.id }
        }
    }.getOrDefault(emptyList())

    suspend fun studioAnime(studioName: String): List<MediaUi> = runCatching {
        api.studioAnime(studioName).map(AniListMapper::toMediaUi)
    }.getOrDefault(emptyList())

    suspend fun tasteRecommendations(watchedIds: List<Long>, isAnime: Boolean = true): List<MediaUi> = runCatching {
        if (watchedIds.isEmpty()) return@runCatching emptyList()
        val seed = watchedIds.takeLast(3)
        coroutineScope {
            seed.map { id ->
                async { recommendations(id, preferTmdb = !isAnime) }
            }.awaitAll().flatten().distinctBy { it.id }
        }
    }.getOrDefault(emptyList())

    private fun cleanCat3Title(raw: String): String {
        val t = raw.replace(Regex("<[^>]+>"), "")
            .replace("&#8217;", "'")
            .replace("&hellip;", "...")
            .replace("&amp;", "&")
            .replace("&#8211;", "-")
            .trim()
        val parts = t.split("|").map { it.trim() }.filter { it.isNotBlank() }
        val candidate = if (parts.size >= 2) {
            val first = parts[0].uppercase(Locale.ROOT)
            if (first in setOf("VIVAMAX", "TBONX", "ATIKCHUCHU", "PCH", "HOTSHOT", "TBON")) {
                parts[1]
            } else {
                parts[0]
            }
        } else {
            parts.firstOrNull() ?: t
        }
        return candidate
            .replace(Regex("""\s*-\s*Full (?:PHILIPINE|CHINA|KOREAN|JAPAN|THAI|VIVAMAX|TBONX).*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*Watch Movie Online.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*Free Sexy Movies.*""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun scrapeCat3Articles(
        url: String,
        defaultGenre: String,
        score: Double,
        seen: MutableSet<String>,
    ): List<MediaUi> {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", "https://www.cat3movie.club/")
            .build()
        val html = runCatching {
            httpClient.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return emptyList()

        val list = mutableListOf<MediaUi>()
        val articleRegex = Regex("""<article[^>]*>([\s\S]*?)</article\s*>""", RegexOption.IGNORE_CASE)
        val titleRegex = Regex("""<h2 class="title">\s*<a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        val backupLinkRegex = Regex("""<a\s+[^>]*href=["'](https?://(?:www\.)?cat3movie\.club/[^"']+)["'][^>]*title=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val imgRegex = Regex("""(?:data-src|data-original|data-bsrjs|src)=["'](https?://[^"']+\.(?:jpg|jpeg|png|webp)[^"']*)["']""", RegexOption.IGNORE_CASE)

        for (match in articleRegex.findAll(html)) {
            val block = match.groupValues[1]
            val tMatch = titleRegex.find(block)
            val (link, rawTitle) = if (tMatch != null) {
                tMatch.groupValues[1].trim() to tMatch.groupValues[2].trim()
            } else {
                val bMatch = backupLinkRegex.find(block) ?: continue
                bMatch.groupValues[1].trim() to bMatch.groupValues[2].trim()
            }

            if (!seen.add(link) || link.contains("/category/") || link.contains("/tag/")) continue

            val poster = imgRegex.find(block)?.groupValues?.get(1)?.trim()
            val cleanTitle = cleanCat3Title(rawTitle)
            if (cleanTitle.isBlank()) continue

            val year = Regex("""\b(?:19|20)\d{2}\b""").find(rawTitle)?.value?.toIntOrNull()
                ?: Regex("""\b(?:19|20)\d{2}\b""").find(cleanTitle)?.value?.toIntOrNull()
            val id = (link.hashCode().toLong() and 0x7FFFFFFF) + 800000000L

            val isVivamax = defaultGenre.equals("Vivamax", ignoreCase = true) || rawTitle.contains("VIVAMAX", ignoreCase = true)
            val genres = if (isVivamax) {
                listOf("Adult (18+)", "Vivamax", "Pinoy", "Asian Cinema")
            } else {
                listOf("Adult (18+)", defaultGenre, "Erotic", "Asian Cinema")
            }

            val media = MediaUi(
                id = id,
                title = cleanTitle,
                romajiTitle = null,
                synopsis = "Cat3Movie $defaultGenre: $cleanTitle",
                coverUrl = poster?.takeIf { it.isNotBlank() },
                bannerUrl = poster?.takeIf { it.isNotBlank() },
                genres = genres,
                score = score,
                year = year,
                startDateIso = year?.let { "$it-01-01" },
                format = "MOVIE",
                episodes = 1,
                durationMinutes = 105,
                status = com.animarvle.app.domain.AiringStatus.FINISHED,
                nextAiringEpisode = null,
                nextAiringAtEpochSec = null,
                isTmdb = false,
            )
            customMediaCache[id] = media
            if (isVivamax) {
                com.animarvle.app.AnimarvleApp.registerPinoy(cleanTitle, rawTitle)
            } else {
                com.animarvle.app.AnimarvleApp.registerAsianAdult(cleanTitle, rawTitle)
            }
            list.add(media)
        }
        return list
    }

    private fun scrapePinoyArticles(
        url: String,
        seen: MutableSet<String>,
    ): List<MediaUi> {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", "https://pinoymoviepedia.ru/")
            .build()
        val html = runCatching {
            httpClient.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return emptyList()

        val list = mutableListOf<MediaUi>()
        val articleRegex = Regex("""<article[^>]*>([\s\S]*?)</article>""", RegexOption.IGNORE_CASE)
        val linkRegex = Regex("""href=["'](https?://pinoymoviepedia\.ru/movies/[^"']+)["']""", RegexOption.IGNORE_CASE)
        val titleRegex = Regex("""(?:class="title"[^>]*>\s*<[ah\d]+[^>]*>|<h3>\s*<a[^>]*>)\s*([\s\S]*?)</[ah\d]+>""", RegexOption.IGNORE_CASE)
        val altRegex = Regex("""alt=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val imgRegex = Regex("""(?:data-src|src)=["'](https?://[^"']+\.(?:jpg|jpeg|png|webp)[^"']*)["']""", RegexOption.IGNORE_CASE)
        val yearRegex = Regex("""(?:class=["']year["']>|<span>)(\d{4})<""", RegexOption.IGNORE_CASE)

        for (match in articleRegex.findAll(html)) {
            val block = match.groupValues[1]
            val tMatch = titleRegex.find(block)
            val (link, rawTitle) = if (tMatch != null) {
                val l = linkRegex.find(block)?.groupValues?.get(1)?.trim() ?: continue
                l to tMatch.groupValues[1].trim()
            } else {
                val l = linkRegex.find(block)?.groupValues?.get(1)?.trim() ?: continue
                val a = altRegex.find(block)?.groupValues?.get(1)?.trim() ?: ""
                l to a
            }

            if (link.isBlank() || rawTitle.isBlank() || !seen.add(link)) continue

            val poster = imgRegex.find(block)?.groupValues?.get(1)?.trim()
            val cleanTitle = rawTitle.replace(Regex("<[^>]+>"), "")
                .replace(Regex("""\s*\(\d{4}\)"""), "")
                .replace("&#8217;", "'")
                .replace("&hellip;", "...")
                .replace("&amp;", "&")
                .trim()
            if (cleanTitle.isBlank()) continue

            val year = Regex("""\b(?:19|20)\d{2}\b""").find(rawTitle)?.value?.toIntOrNull()
                ?: Regex("""\b(?:19|20)\d{2}\b""").find(link)?.value?.toIntOrNull()
                ?: yearRegex.find(block)?.groupValues?.get(1)?.toIntOrNull()
            val id = (link.hashCode().toLong() and 0x7FFFFFFF) + 800000000L

            val media = MediaUi(
                id = id,
                title = cleanTitle,
                romajiTitle = null,
                synopsis = "Pinoy / Vivamax Adult (18+) Cinema: $cleanTitle",
                coverUrl = poster?.takeIf { it.isNotBlank() },
                bannerUrl = poster?.takeIf { it.isNotBlank() },
                genres = listOf("Adult (18+)", "Vivamax", "Pinoy", "Asian Cinema"),
                score = 8.6,
                year = year,
                startDateIso = year?.let { "$it-01-01" },
                format = "MOVIE",
                episodes = 1,
                durationMinutes = 95,
                status = com.animarvle.app.domain.AiringStatus.FINISHED,
                nextAiringEpisode = null,
                nextAiringAtEpochSec = null,
                isTmdb = false,
            )
            customMediaCache[id] = media
            com.animarvle.app.AnimarvleApp.registerPinoy(cleanTitle, rawTitle)
            list.add(media)
        }
        return list
    }

    suspend fun searchAsianCinema(query: String = "", pages: Int = 3, startPage: Int = 1): List<MediaUi> = withContext(Dispatchers.IO) {
        val q = query.trim()
        val results = mutableListOf<MediaUi>()
        val seen = mutableSetOf<String>()

        if (q.isBlank()) {
            // === DISCOVERY MODE ===
            // Front-load Vivamax & Pinoy erotic cinema, Korean 18+ cinema, and Asian Cat 3 cinema.
            // NO fake JAV queries. Zero dummy "NOW PRINTING" placeholders!

            val vivamaxList = mutableListOf<MediaUi>()
            val koreanList = mutableListOf<MediaUi>()
            val cat3List = mutableListOf<MediaUi>()

            coroutineScope {
                // 1. PinoyMoviePedia (Pinay Sexy / Vivamax Movies)
                val pinoyDef = async {
                    val list = mutableListOf<MediaUi>()
                    for (pageIdx in startPage until (startPage + pages)) {
                        val url = if (pageIdx == 1) "https://pinoymoviepedia.ru/genre/pinay-sexy-movi/"
                            else "https://pinoymoviepedia.ru/genre/pinay-sexy-movi/page/$pageIdx/"
                        list.addAll(scrapePinoyArticles(url, seen))
                    }
                    list
                }

                // 2. Cat3Movie Philippine Category (Vivamax / T-Bonx / Pinoy Erotic)
                val cat3PhilDef = async {
                    val list = mutableListOf<MediaUi>()
                    for (pageIdx in startPage until (startPage + pages)) {
                        val url = if (pageIdx == 1) "https://www.cat3movie.club/category/philipine/"
                            else "https://www.cat3movie.club/category/philipine/page/$pageIdx/"
                        list.addAll(scrapeCat3Articles(url, defaultGenre = "Vivamax", score = 8.5, seen = seen))
                    }
                    list
                }

                // 3. Cat3Movie Korean Category (Korean 18+ Cinema)
                val cat3KoreanDef = async {
                    val list = mutableListOf<MediaUi>()
                    for (pageIdx in startPage until (startPage + pages)) {
                        val url = if (pageIdx == 1) "https://www.cat3movie.club/category/korean/"
                            else "https://www.cat3movie.club/category/korean/page/$pageIdx/"
                        list.addAll(scrapeCat3Articles(url, defaultGenre = "Korean 18+", score = 8.2, seen = seen))
                    }
                    list
                }

                // 4. Cat3Movie China Category (Hong Kong / Chinese Category 3 Cinema)
                val cat3ChinaDef = async {
                    val list = mutableListOf<MediaUi>()
                    for (pageIdx in startPage until (startPage + pages)) {
                        val url = if (pageIdx == 1) "https://www.cat3movie.club/category/china/"
                            else "https://www.cat3movie.club/category/china/page/$pageIdx/"
                        list.addAll(scrapeCat3Articles(url, defaultGenre = "Category 3", score = 8.0, seen = seen))
                    }
                    list
                }

                vivamaxList.addAll(pinoyDef.await())
                vivamaxList.addAll(cat3PhilDef.await())
                koreanList.addAll(cat3KoreanDef.await())
                cat3List.addAll(cat3ChinaDef.await())
            }

            // Interleave Vivamax, Korean 18+, and Cat 3 for a rich, balanced catalog
            var vIdx = 0
            var kIdx = 0
            var cIdx = 0
            while (vIdx < vivamaxList.size || kIdx < koreanList.size || cIdx < cat3List.size) {
                // Add 1 Vivamax
                if (vIdx < vivamaxList.size) results.add(vivamaxList[vIdx++])
                // Add 1 Korean 18+
                if (kIdx < koreanList.size) results.add(koreanList[kIdx++])
                // Add 1 Vivamax
                if (vIdx < vivamaxList.size) results.add(vivamaxList[vIdx++])
                // Add 1 Cat 3
                if (cIdx < cat3List.size) results.add(cat3List[cIdx++])
            }
            return@withContext results
        }

        // === SEARCH MODE ===
        val isJavCode = Regex("""\b[A-Za-z]{2,6}[-_]?\d{2,5}\b""").containsMatchIn(q)
        val isExplicitJavQuery = isJavCode || listOf("jav", "uncensored", "fc2", "dori", "nanx", "ipx", "ssis", "mide", "jul", "sone", "stars").any { q.contains(it, ignoreCase = true) }
        val isPinoySearch = listOf("vivamax", "pinoy", "tagalog", "alindog", "sukdulan", "dutdot", "angeli", "raval", "tbonx").any { q.contains(it, ignoreCase = true) }

        if (isExplicitJavQuery) {
            // 1. JavMost instant JSON search
            for (pageIdx in startPage until (startPage + pages)) {
                runCatching {
                    val encoded = java.net.URLEncoder.encode(q, "UTF-8")
                    val url = "https://www.javmost.ws/showlist2/$encoded/$pageIdx/search/"
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Referer", "https://www.javmost.ws/")
                        .build()
                    val body = httpClient.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
                    if (body != null) {
                        val root = JSONObject(body)
                        val arr = root.optJSONArray("result")
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                val fullUrl = obj.optString("url")
                                if (fullUrl.isBlank() || !seen.add(fullUrl)) continue
                                val cover = obj.optString("cover").takeIf { it.isNotBlank() }
                                if (cover == null || cover.contains("now-printing", ignoreCase = true) || cover.contains("now_printing", ignoreCase = true)) {
                                    continue
                                }
                                val name = obj.optString("name").replace(Regex("<[^>]+>"), "").trim()
                                val fullName = obj.optString("full_name").replace(Regex("<[^>]+>"), "").trim()
                                val isUncensored = fullUrl.contains("uncensored", ignoreCase = true) ||
                                    name.contains("uncensored", ignoreCase = true) ||
                                    fullName.contains("uncensored", ignoreCase = true)
                                val badge = if (isUncensored) "[Uncensored] " else ""
                                val title = when {
                                    name.isNotBlank() && fullName.isNotBlank() && !fullName.startsWith(name, ignoreCase = true) -> "$badge$name $fullName"
                                    fullName.isNotBlank() -> "$badge$fullName"
                                    else -> "$badge$name"
                                }.trim()
                                val year = Regex("""\b(?:19|20)\d{2}\b""").find(obj.optString("release"))?.value?.toIntOrNull()
                                    ?: Regex("""\b(?:19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
                                val id = (fullUrl.hashCode().toLong() and 0x7FFFFFFF) + 800000000L

                                val media = MediaUi(
                                    id = id,
                                    title = title,
                                    romajiTitle = name.ifBlank { null },
                                    synopsis = "Asian Cinema (18+) / JAV Stream: $name",
                                    coverUrl = cover,
                                    bannerUrl = cover,
                                    genres = listOf("Adult (18+)", "JAV", "Asian Cinema"),
                                    score = 8.5,
                                    year = year,
                                    startDateIso = year?.let { "$it-01-01" },
                                    format = "MOVIE",
                                    episodes = 1,
                                    durationMinutes = 120,
                                    status = com.animarvle.app.domain.AiringStatus.FINISHED,
                                    nextAiringEpisode = null,
                                    nextAiringAtEpochSec = null,
                                    isTmdb = false,
                                )
                                customMediaCache[id] = media
                                com.animarvle.app.AnimarvleApp.registerAsianAdult(title, name, fullName)
                                results.add(media)
                            }
                        }
                    }
                }
            }

            // 2. Jav.com.co search
            runCatching {
                val encoded = java.net.URLEncoder.encode(q, "UTF-8")
                val url = "https://jav.com.co/?s=$encoded"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://jav.com.co/")
                    .build()
                val html = httpClient.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
                if (html != null) {
                    val articleRegex = Regex("""<article[^>]*>([\s\S]*?)</article\s*>""", RegexOption.IGNORE_CASE)
                    val titleRegex = Regex("""<a[^>]*href=["'](https://jav\.com\.co/[^"']+)["'][^>]*title=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    val imgRegex = Regex("""(?:data-src|src)=["'](https?://[^"']+\.(?:jpg|jpeg|png|webp)[^"']*)["']""", RegexOption.IGNORE_CASE)

                    for (match in articleRegex.findAll(html)) {
                        val block = match.groupValues[1]
                        val tMatch = titleRegex.find(block) ?: continue
                        val link = tMatch.groupValues[1].trim()
                        val rawTitle = tMatch.groupValues[2].trim()

                        if (!seen.add(link) || link.contains("/category/") || link.contains("/tag/")) continue

                        val poster = imgRegex.find(block)?.groupValues?.get(1)?.trim()
                        val cleanText = rawTitle.replace(Regex("<[^>]+>"), "")
                            .replace("&#8217;", "'")
                            .replace("&hellip;", "...")
                            .replace("&amp;", "&")
                            .trim()
                        val id = (link.hashCode().toLong() and 0x7FFFFFFF) + 800000000L

                        val isUncensored = link.contains("uncensored", ignoreCase = true) || cleanText.contains("uncensored", ignoreCase = true)
                        val badge = if (isUncensored) "[Uncensored] " else ""

                        val media = MediaUi(
                            id = id,
                            title = "$badge$cleanText",
                            romajiTitle = null,
                            synopsis = "Jav.com.co HD Stream: $cleanText",
                            coverUrl = poster?.takeIf { it.isNotBlank() },
                            bannerUrl = poster?.takeIf { it.isNotBlank() },
                            genres = listOf("Adult (18+)", "JAV", "Asian Cinema"),
                            score = 8.2,
                            year = Regex("""\b(?:19|20)\d{2}\b""").find(cleanText)?.value?.toIntOrNull(),
                            startDateIso = null,
                            format = "MOVIE",
                            episodes = 1,
                            durationMinutes = 120,
                            status = com.animarvle.app.domain.AiringStatus.FINISHED,
                            nextAiringEpisode = null,
                            nextAiringAtEpochSec = null,
                            isTmdb = false,
                        )
                        customMediaCache[id] = media
                        com.animarvle.app.AnimarvleApp.registerAsianAdult(cleanText)
                        results.add(media)
                    }
                }
            }

            // 3. Cat3Movie search fallback
            runCatching {
                val encoded = java.net.URLEncoder.encode(q, "UTF-8")
                val url = "https://www.cat3movie.club/?s=$encoded"
                results.addAll(scrapeCat3Articles(url, defaultGenre = "Asian Cinema", score = 8.0, seen = seen))
            }
        } else {
            // General / Vivamax / Korean movie search
            coroutineScope {
                val pinoyDef = async {
                    val encoded = java.net.URLEncoder.encode(q, "UTF-8")
                    val url = "https://pinoymoviepedia.ru/?s=$encoded"
                    scrapePinoyArticles(url, seen)
                }

                val cat3Def = async {
                    val encoded = java.net.URLEncoder.encode(q, "UTF-8")
                    val url = "https://www.cat3movie.club/?s=$encoded"
                    scrapeCat3Articles(url, defaultGenre = "Adult Cinema", score = 8.4, seen = seen)
                }

                val javDef = async {
                    if (isPinoySearch) return@async emptyList<MediaUi>()
                    val list = mutableListOf<MediaUi>()
                    runCatching {
                        val encoded = java.net.URLEncoder.encode(q, "UTF-8")
                        val url = "https://www.javmost.ws/showlist2/$encoded/1/search/"
                        val req = Request.Builder()
                            .url(url)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                            .header("X-Requested-With", "XMLHttpRequest")
                            .header("Referer", "https://www.javmost.ws/")
                            .build()
                        val body = httpClient.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
                        if (body != null) {
                            val root = JSONObject(body)
                            val arr = root.optJSONArray("result")
                            if (arr != null) {
                                for (i in 0 until arr.length()) {
                                    val obj = arr.getJSONObject(i)
                                    val fullUrl = obj.optString("url")
                                    if (fullUrl.isBlank() || !seen.add(fullUrl)) continue
                                    val cover = obj.optString("cover").takeIf { it.isNotBlank() }
                                    if (cover == null || cover.contains("now-printing", ignoreCase = true) || cover.contains("now_printing", ignoreCase = true)) {
                                        continue
                                    }
                                    val name = obj.optString("name").replace(Regex("<[^>]+>"), "").trim()
                                    val fullName = obj.optString("full_name").replace(Regex("<[^>]+>"), "").trim()
                                    val title = when {
                                        name.isNotBlank() && fullName.isNotBlank() && !fullName.startsWith(name, ignoreCase = true) -> "$name $fullName"
                                        fullName.isNotBlank() -> fullName
                                        else -> name
                                    }.trim()
                                    val year = Regex("""\b(?:19|20)\d{2}\b""").find(obj.optString("release"))?.value?.toIntOrNull()
                                        ?: Regex("""\b(?:19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
                                    val id = (fullUrl.hashCode().toLong() and 0x7FFFFFFF) + 800000000L

                                    val media = MediaUi(
                                        id = id,
                                        title = title,
                                        romajiTitle = name.ifBlank { null },
                                        synopsis = "Asian Cinema (18+): $title",
                                        coverUrl = cover,
                                        bannerUrl = cover,
                                        genres = listOf("Adult (18+)", "Asian Cinema"),
                                        score = 8.0,
                                        year = year,
                                        startDateIso = year?.let { "$it-01-01" },
                                        format = "MOVIE",
                                        episodes = 1,
                                        durationMinutes = 110,
                                        status = com.animarvle.app.domain.AiringStatus.FINISHED,
                                        nextAiringEpisode = null,
                                        nextAiringAtEpochSec = null,
                                        isTmdb = false,
                                    )
                                    customMediaCache[id] = media
                                    list.add(media)
                                }
                            }
                        }
                    }
                    list
                }

                val pinoyRes = pinoyDef.await()
                val cat3Res = cat3Def.await()
                val javRes = javDef.await()

                // Movie results (Vivamax, Pinoy, Cat3) first!
                results.addAll(pinoyRes)
                results.addAll(cat3Res)
                results.addAll(javRes)
            }
        }

        results
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