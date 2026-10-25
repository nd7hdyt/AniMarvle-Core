package com.animarvle.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.animarvle.app.R
import com.animarvle.app.data.AppMediaMode
import com.animarvle.app.data.MediaTypeManager
import com.animarvle.app.data.MetadataRepository
import com.animarvle.app.ui.player.HistoryRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repo: MetadataRepository,
    private val historyRepo: HistoryRepository? = null,
    private val mediaTypeManager: MediaTypeManager? = null,
) : ViewModel() {

    private val _currentDomain = MutableStateFlow(
        if (mediaTypeManager?.currentMode?.value == AppMediaMode.MOVIES_TV) HomeDomain.MOVIE else HomeDomain.ANIME
    )
    val currentDomain: StateFlow<HomeDomain> = _currentDomain.asStateFlow()

    private val _feedState = MutableStateFlow<List<FeedRow>>(emptyList())
    val feedState: StateFlow<List<FeedRow>> = _feedState.asStateFlow()

    // Exposes loading state for VortexMorphLoadingView on initial load & pull-to-refresh
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var cachedAnimeRails: List<FeedRow>? = null
    private var cachedMovieRails: List<FeedRow>? = null
    private val resolvedPosterCache = mutableMapOf<String, String>()

    init {
        historyRepo?.let { hRepo ->
            viewModelScope.launch {
                hRepo.historyFlow.collect {
                    updateContinueWatchingRail()
                }
            }
        }
        loadFeedForDomain(_currentDomain.value, forceRefresh = false)
    }

    fun setDomain(domain: HomeDomain) {
        if (_currentDomain.value != domain) {
            _currentDomain.value = domain
            val desiredMode = if (domain == HomeDomain.ANIME) AppMediaMode.ANIME else AppMediaMode.MOVIES_TV
            if (mediaTypeManager?.currentMode?.value != desiredMode) {
                mediaTypeManager?.setMode(desiredMode)
            }

            val cached = if (domain == HomeDomain.ANIME) cachedAnimeRails else cachedMovieRails
            if (cached != null) {
                _feedState.value = cached
            } else {
                _feedState.value = emptyList()
            }
            loadFeedForDomain(domain, forceRefresh = false)
        }
    }

    fun refresh() {
        loadFeedForDomain(_currentDomain.value, forceRefresh = true)
    }

    private fun loadFeedForDomain(domain: HomeDomain, forceRefresh: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true
            runCatching {
                when (domain) {
                    HomeDomain.ANIME -> buildAnimeRails()
                    HomeDomain.MOVIE -> buildMovieRails()
                }
            }.onFailure { e ->
                android.util.Log.e("HomeViewModel", "Error loading feed for $domain", e)
            }
            _isLoading.value = false
        }
    }

    private suspend fun buildAnimeRails() = coroutineScope {
        val trendingDef = async { runCatching { repo.trending() }.getOrDefault(emptyList()) }
        val airingNowDef = async { runCatching { repo.airingNow() }.getOrDefault(emptyList()) }
        val popularSeasonDef = async { runCatching { repo.popularThisSeason() }.getOrDefault(emptyList()) }
        val topRatedDef = async { runCatching { repo.topRated() }.getOrDefault(emptyList()) }
        val donghuaDef = async { runCatching { repo.donghua() }.getOrDefault(emptyList()) }
        val allTimePopularDef = async { runCatching { repo.allTimePopular() }.getOrDefault(emptyList()) }
        val popularMoviesDef = async { runCatching { repo.popularMovies() }.getOrDefault(emptyList()) }

        val trending = trendingDef.await()
        val airingNow = airingNowDef.await()
        val popularSeason = popularSeasonDef.await()
        val topRated = topRatedDef.await()
        val donghua = donghuaDef.await()
        val allTimePopular = allTimePopularDef.await()
        val popularMovies = popularMoviesDef.await()

        // Cache known vertical cover posters by title so Continue Watching always uses the Anime's cover art
        (trending + airingNow + popularSeason + topRated + donghua + allTimePopular + popularMovies).forEach { mediaUi ->
            if (!mediaUi.coverUrl.isNullOrBlank()) {
                resolvedPosterCache[mediaUi.title.lowercase().trim()] = mediaUi.coverUrl
            }
        }

        val rails = mutableListOf<FeedRow>()

        // 1. Hero Carousel (Top 8 trending items)
        val heroPool = if (trending.isNotEmpty()) trending else airingNow
        val heroItems = heroPool.take(8).map { it.toMedia("ANIME") }
        if (heroItems.isNotEmpty()) {
            rails.add(FeedRow.HeroCarousel(heroItems))
        }

        // 2. Continue Watching (Anime only - uses vertical cover poster)
        val history = historyRepo?.historyFlow?.value.orEmpty()
            .filter { isAnimeHistory(it) }
            .distinctBy { it.title.trim().lowercase(java.util.Locale.ROOT).replace(Regex("""\s+season\s+\d+.*""", RegexOption.IGNORE_CASE), "") }
        if (history.isNotEmpty()) {
            val cwItems = history.map { h ->
                val bestCover = resolvedPosterCache[h.title.lowercase().trim()] ?: h.posterUrl.orEmpty()
                h.toMedia().copy(posterUrl = bestCover, backdropUrl = bestCover)
            }
            rails.add(FeedRow.ContinueWatching(items = cwItems))
        }

        // NOTE: Fix #6 — NO ProviderBubbles on Anime Home Screen!

        // 3. Curated Anime Rails
        if (airingNow.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Latest Anime", airingNow.map { it.toMedia("ANIME") }))
        }
        if (trending.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Trending Anime", trending.map { it.toMedia("ANIME") }))
        }
        if (popularSeason.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Popular This Season", popularSeason.map { it.toMedia("ANIME") }))
        }
        if (topRated.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Top Rated Anime", topRated.map { it.toMedia("ANIME") }))
        }
        if (donghua.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Donghua", donghua.map { it.toMedia("ANIME") }))
        }
        if (allTimePopular.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("All Time Popular", allTimePopular.map { it.toMedia("ANIME") }))
        }
        if (popularMovies.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Anime Movies", popularMovies.map { it.toMedia("MOVIE") }))
        }

        cachedAnimeRails = rails
        if (_currentDomain.value == HomeDomain.ANIME) {
            _feedState.value = rails
        }
    }

    private suspend fun buildMovieRails() = coroutineScope {
        val tmdbTrendingDef = async { runCatching { repo.tmdbTrending() }.getOrDefault(emptyList()) }
        val tmdbTrendingMoviesDef = async { runCatching { repo.tmdbTrendingMovies() }.getOrDefault(emptyList()) }
        val tmdbTrendingTvDef = async { runCatching { repo.tmdbTrendingTv() }.getOrDefault(emptyList()) }
        val tmdbTopRatedDef = async { runCatching { repo.tmdbTopRated() }.getOrDefault(emptyList()) }
        val tmdbNowPlayingDef = async { runCatching { repo.tmdbNowPlaying() }.getOrDefault(emptyList()) }
        val tmdbPopularMoviesDef = async { runCatching { repo.tmdbPopularMovies() }.getOrDefault(emptyList()) }
        val tmdbPopularTvDef = async { runCatching { repo.tmdbPopularTv() }.getOrDefault(emptyList()) }
        val tmdbCartoonsDef = async { runCatching { repo.tmdbCartoons() }.getOrDefault(emptyList()) }
        val tmdbKDramasDef = async { runCatching { repo.tmdbKDramas() }.getOrDefault(emptyList()) }
        val tmdbCDramasDef = async { runCatching { repo.tmdbCDramas() }.getOrDefault(emptyList()) }

        val trending = tmdbTrendingDef.await()
        val trendingMovies = tmdbTrendingMoviesDef.await()
        val trendingTv = tmdbTrendingTvDef.await()
        val topRated = tmdbTopRatedDef.await()
        val nowPlaying = tmdbNowPlayingDef.await()
        val popularMovies = tmdbPopularMoviesDef.await()
        val popularTv = tmdbPopularTvDef.await()
        val cartoons = tmdbCartoonsDef.await()
        val kdramas = tmdbKDramasDef.await()
        val cdramas = tmdbCDramasDef.await()

        (trending + trendingMovies + trendingTv + topRated + nowPlaying + popularMovies + popularTv).forEach { mediaUi ->
            if (!mediaUi.coverUrl.isNullOrBlank()) {
                resolvedPosterCache[mediaUi.title.lowercase().trim()] = mediaUi.coverUrl
            }
        }

        val rails = mutableListOf<FeedRow>()

        // 1. Hero Carousel
        val heroPool = if (trending.isNotEmpty()) trending else (trendingMovies + trendingTv)
        val heroItems = heroPool.take(8).map {
            val isMovie = it.format?.contains("MOVIE", ignoreCase = true) == true
            it.toMedia(if (isMovie) "MOVIE" else "TV")
        }
        if (heroItems.isNotEmpty()) {
            rails.add(FeedRow.HeroCarousel(heroItems))
        }

        // 2. Continue Watching (Movie/TV only - uses vertical cover poster)
        val history = historyRepo?.historyFlow?.value.orEmpty()
            .filter { !isAnimeHistory(it) }
        if (history.isNotEmpty()) {
            val cwItems = history.map { h ->
                val bestCover = resolvedPosterCache[h.title.lowercase().trim()] ?: h.posterUrl.orEmpty()
                h.toMedia().copy(posterUrl = bestCover, backdropUrl = bestCover)
            }
            rails.add(FeedRow.ContinueWatching(items = cwItems))
        }

        // 3. Provider Bubbles ONLY in Movie Hub (Fix #6)
        rails.add(FeedRow.ProviderBubbles(providers = getBrandedProviders()))

        // 4. Movie & TV Rails
        if (trendingMovies.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Trending Movies", trendingMovies.map { it.toMedia("MOVIE") }))
        }
        if (trendingTv.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Trending TV Shows", trendingTv.map { it.toMedia("TV") }))
        }
        if (topRated.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Top Rated on IMDb", topRated.map { it.toMedia("MOVIE") }))
        }
        if (nowPlaying.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Now Playing in Theaters", nowPlaying.map { it.toMedia("MOVIE") }))
        }
        if (popularMovies.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Popular Movies", popularMovies.map { it.toMedia("MOVIE") }))
        }
        if (popularTv.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Popular TV Shows", popularTv.map { it.toMedia("TV") }))
        }
        if (cartoons.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Cartoons & Animation", cartoons.map { it.toMedia("TV") }))
        }
        if (kdramas.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("K-Drama & Korean Shows", kdramas.map { it.toMedia("TV") }))
        }
        if (cdramas.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("C-Drama & Asian Series", cdramas.map { it.toMedia("TV") }))
        }

        cachedMovieRails = rails
        if (_currentDomain.value == HomeDomain.MOVIE) {
            _feedState.value = rails
        }
    }

    private fun updateContinueWatchingRail() {
        val currentRows = _feedState.value.toMutableList()
        if (currentRows.isEmpty()) return

        val isAnime = _currentDomain.value == HomeDomain.ANIME
        val history = historyRepo?.historyFlow?.value.orEmpty()
            .filter { if (isAnime) isAnimeHistory(it) else !isAnimeHistory(it) }
            .distinctBy { it.title.trim().lowercase(java.util.Locale.ROOT).replace(Regex("""\s+season\s+\d+.*""", RegexOption.IGNORE_CASE), "") }

        val existingIdx = currentRows.indexOfFirst { it is FeedRow.ContinueWatching }
        if (history.isNotEmpty()) {
            val cwItems = history.map { h ->
                val bestCover = resolvedPosterCache[h.title.lowercase().trim()] ?: h.posterUrl.orEmpty()
                h.toMedia().copy(posterUrl = bestCover, backdropUrl = bestCover)
            }
            val newRail = FeedRow.ContinueWatching(items = cwItems)
            if (existingIdx >= 0) {
                currentRows[existingIdx] = newRail
            } else {
                val insertPos = if (currentRows.isNotEmpty() && currentRows[0] is FeedRow.HeroCarousel) 1 else 0
                currentRows.add(insertPos, newRail)
            }
        } else if (existingIdx >= 0) {
            currentRows.removeAt(existingIdx)
        }

        _feedState.value = currentRows
        if (isAnime) cachedAnimeRails = currentRows else cachedMovieRails = currentRows
    }

    private fun isAnimeHistory(item: com.animarvle.app.ui.player.HistoryItem): Boolean {
        val sanitized = HistoryRepository.sanitizeMediaType(
            item.mediaType,
            item.title,
            item.mediaUrl,
            item.durationMs,
        )
        return sanitized.equals("ANIME", ignoreCase = true)
    }

    private fun getBrandedProviders(): List<ProviderLogo> = listOf(
        // Prime Video: Vibrant Amazon Prime Blue (#00A8E1) with crisp white P
        ProviderLogo("p2", "Prime Video", R.drawable.ic_provider_prime, "P", android.graphics.Color.parseColor("#00A8E1"), android.graphics.Color.parseColor("#FFFFFF")),
        // Netflix: Deep black with signature Netflix Red accent border & bold white/red N
        ProviderLogo("p1", "Netflix", R.drawable.ic_provider_netflix, "N", android.graphics.Color.parseColor("#0F0F14"), android.graphics.Color.parseColor("#E50914")),
        // Sony LIV: Deep purple gradient tone (#25103F) with cyan text
        ProviderLogo("p6", "Sony LIV", R.drawable.ic_provider_sonyliv, "LIV", android.graphics.Color.parseColor("#25103F"), android.graphics.Color.parseColor("#00E5FF")),
        // Crunchyroll: Vibrant Crunchyroll Orange (#F47521) with white CR
        ProviderLogo("p5", "Crunchyroll", R.drawable.ic_provider_crunchyroll, "CR", android.graphics.Color.parseColor("#F47521"), android.graphics.Color.parseColor("#FFFFFF")),
        // Hotstar: Deep Navy Blue (#0A192F) with gold star
        ProviderLogo("p7", "Hotstar", R.drawable.ic_provider_hotstar, "★", android.graphics.Color.parseColor("#0A192F"), android.graphics.Color.parseColor("#FFC800")),
        // Disney+: Rich Disney Blue (#0063E5) with white D+
        ProviderLogo("p3", "Disney+", R.drawable.ic_provider_disney, "D+", android.graphics.Color.parseColor("#0063E5"), android.graphics.Color.parseColor("#FFFFFF")),
        // Apple TV+: Sleek graphite dark gray (#2C2C2E) with white tv+
        ProviderLogo("p4", "Apple TV+", R.drawable.ic_provider_appletv, "tv+", android.graphics.Color.parseColor("#2C2C2E"), android.graphics.Color.parseColor("#FFFFFF")),
        // Max: Electric Warner Bros Blue (#002BE7) with white MAX
        ProviderLogo("p8", "Max", 0, "MAX", android.graphics.Color.parseColor("#002BE7"), android.graphics.Color.parseColor("#FFFFFF")),
    )

    class Factory(
        private val repo: MetadataRepository,
        private val historyRepo: HistoryRepository? = null,
        private val mediaTypeManager: MediaTypeManager? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(repo, historyRepo, mediaTypeManager) as T
    }
}