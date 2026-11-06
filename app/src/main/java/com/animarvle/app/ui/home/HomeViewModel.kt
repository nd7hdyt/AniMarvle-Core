package com.animarvle.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.animarvle.app.R
import com.animarvle.app.data.AppMediaMode
import com.animarvle.app.data.MediaTypeManager
import com.animarvle.app.data.MetadataRepository
import com.animarvle.app.domain.MediaUi
import com.animarvle.app.ui.player.HistoryRepository
import com.animarvle.app.ui.search.SearchFilterData
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
        val history = historyRepo?.historyFlow?.value.orEmpty()
            .filter { isAnimeHistory(it) }
            .distinctBy { it.title.trim().lowercase(java.util.Locale.ROOT).replace(Regex("""\s+season\s+\d+.*""", RegexOption.IGNORE_CASE), "") }
        val isTasteUnlocked = history.size >= 10
        val watchedIds = history.mapNotNull { it.mediaUrl.toLongOrNull() }

        val trendingDef = async { runCatching { repo.trending() }.getOrDefault(emptyList()) }
        val popularSeasonDef = async { runCatching { repo.popularThisSeason() }.getOrDefault(emptyList()) }
        val topRatedDef = async { runCatching { repo.topRated() }.getOrDefault(emptyList()) }
        val donghuaDef = async { runCatching { repo.donghua() }.getOrDefault(emptyList()) }
        val popularMoviesDef = async { runCatching { repo.popularMovies() }.getOrDefault(emptyList()) }
        val adventureGenreDef = async { runCatching { repo.anilistDiscoverFiltered(genre = "Adventure") }.getOrDefault(emptyList()) }
        val mappaStudioDef = async { runCatching { repo.studioAnime("MAPPA") }.getOrDefault(emptyList()) }
        val tasteDef = if (isTasteUnlocked && watchedIds.isNotEmpty()) {
            async { runCatching { repo.tasteRecommendations(watchedIds, isAnime = true) }.getOrDefault(emptyList()) }
        } else null

        val trending = trendingDef.await()
        val popularSeason = popularSeasonDef.await()
        val topRated = topRatedDef.await()
        val donghua = donghuaDef.await()
        val popularMovies = popularMoviesDef.await()
        val adventureItems = adventureGenreDef.await()
        val mappaItems = mappaStudioDef.await()
        val tasteItems = tasteDef?.await().orEmpty()

        (trending + popularSeason + topRated + donghua + popularMovies + adventureItems + mappaItems + tasteItems).forEach { mediaUi ->
            if (!mediaUi.coverUrl.isNullOrBlank()) {
                resolvedPosterCache[mediaUi.title.lowercase().trim()] = mediaUi.coverUrl
            }
        }

        val rails = mutableListOf<FeedRow>()

        // 1. Hero Carousel (Top 8 trending anime)
        val heroPool = if (trending.isNotEmpty()) trending else popularSeason
        val heroItems = heroPool.take(8).map { it.toMedia("ANIME") }
        if (heroItems.isNotEmpty()) {
            rails.add(FeedRow.HeroCarousel(heroItems))
        }

        // 2. Continue Watching (Anime only)
        if (history.isNotEmpty()) {
            val cwItems = history.map { h ->
                val bestCover = resolvedPosterCache[h.title.lowercase().trim()] ?: h.posterUrl.orEmpty()
                h.toMedia().copy(posterUrl = bestCover, backdropUrl = bestCover)
            }
            rails.add(FeedRow.ContinueWatching(items = cwItems))
        }

        // 3. Your Taste (ONLY unlocked after >= 10 watches)
        if (isTasteUnlocked && tasteItems.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Your Taste", tasteItems.map { it.toMedia("ANIME") }))
        }

        // 4. Trending Anime
        if (trending.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Trending Anime", trending.map { it.toMedia("ANIME") }))
        }

        // 5. Anime Genre › [ Adventure ⌃ ] (Dynamic filter rail)
        rails.add(
            FeedRow.FilterableRail(
                railId = "anime_genre",
                title = "Anime Genre",
                filterType = "genre",
                selectedOption = "Adventure",
                availableOptions = listOf(
                    "Adventure", "Action", "Comedy", "Drama", "Fantasy",
                    "Isekai", "Mecha", "Mystery", "Psychological", "Romance",
                    "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller"
                ),
                items = adventureItems.map { it.toMedia("ANIME") }
            )
        )

        // 6. Popular This Season
        if (popularSeason.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Popular This Season", popularSeason.map { it.toMedia("ANIME") }))
        }

        // 7. Animation Studios › [ MAPPA ⌃ ] (Dynamic filter rail)
        rails.add(
            FeedRow.FilterableRail(
                railId = "anime_studio",
                title = "Animation Studios",
                filterType = "studio",
                selectedOption = "MAPPA",
                availableOptions = listOf(
                    "MAPPA", "Ufotable", "Madhouse", "Bones", "Wit Studio",
                    "Kyoto Animation", "CloverWorks", "Toei Animation", "A-1 Pictures", "CoMix Wave Films"
                ),
                items = mappaItems.map { it.toMedia("ANIME") }
            )
        )

        // 8. Top Rated Anime
        if (topRated.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Top Rated Anime", topRated.map { it.toMedia("ANIME") }))
        }

        // 9. Donghua
        if (donghua.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Donghua", donghua.map { it.toMedia("ANIME") }))
        }

        // 10. Anime Movies
        if (popularMovies.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Anime Movies", popularMovies.map { it.toMedia("MOVIE") }))
        }

        cachedAnimeRails = rails
        if (_currentDomain.value == HomeDomain.ANIME) {
            _feedState.value = rails
        }
    }

    private suspend fun buildMovieRails() = coroutineScope {
        val history = historyRepo?.historyFlow?.value.orEmpty()
            .filter { !isAnimeHistory(it) }
            .distinctBy { it.title.trim().lowercase(java.util.Locale.ROOT) }
        val isTasteUnlocked = history.size >= 10
        val watchedIds = history.mapNotNull { it.mediaUrl.toLongOrNull() }

        val tmdbTrendingDef = async { runCatching { repo.tmdbTrending() }.getOrDefault(emptyList()) }
        val tmdbTrendingMoviesDef = async { runCatching { repo.tmdbTrendingMovies() }.getOrDefault(emptyList()) }
        val tmdbTrendingTvDef = async { runCatching { repo.tmdbTrendingTv() }.getOrDefault(emptyList()) }
        val tmdbTopRatedDef = async { runCatching { repo.tmdbTopRated() }.getOrDefault(emptyList()) }
        val horrorGenreDef = async { runCatching { repo.tmdbDiscoverFiltered("movie", pages = 2, withGenres = "27") }.getOrDefault(emptyList()) }
        val nolanDef = async { runCatching { repo.tmdbDiscoverFiltered("movie", pages = 2, withCrew = "525") }.getOrDefault(emptyList()) }
        val pughDef = async { runCatching { repo.tmdbDiscoverFiltered("movie", pages = 2, withCast = "1373737") }.getOrDefault(emptyList()) }
        val chalametDef = async { runCatching { repo.tmdbDiscoverFiltered("movie", pages = 2, withCast = "1190668") }.getOrDefault(emptyList()) }
        val indianWebSeriesDef = async { runCatching { repo.tmdbDiscoverFiltered("tv", pages = 2, withOriginalLanguage = "hi") }.getOrDefault(emptyList()) }
        val bollywoodPicksDef = async { runCatching { repo.tmdbDiscoverFiltered("movie", pages = 2, withOriginalLanguage = "hi") }.getOrDefault(emptyList()) }
        val cartoonsDef = async { runCatching { repo.tmdbCartoons() }.getOrDefault(emptyList()) }
        val asianSchoolDef = async {
            runCatching {
                val res = repo.tmdbDiscoverFiltered("tv", pages = 2, withOriginalLanguage = "ko", withKeywords = "10683|210024|246535")
                if (res.isNotEmpty()) res else repo.tmdbDiscoverFiltered("tv", pages = 2, withOriginalLanguage = "ko", withGenres = "18,35")
            }.getOrDefault(emptyList())
        }
        val tasteDef = if (isTasteUnlocked && watchedIds.isNotEmpty()) {
            async { runCatching { repo.tasteRecommendations(watchedIds, isAnime = false) }.getOrDefault(emptyList()) }
        } else null

        val trending = tmdbTrendingDef.await()
        val trendingMovies = tmdbTrendingMoviesDef.await()
        val trendingTv = tmdbTrendingTvDef.await()
        val topRated = tmdbTopRatedDef.await()
        val horrorItems = horrorGenreDef.await()
        val nolanItems = nolanDef.await()
        val pughItems = pughDef.await()
        val chalametItems = chalametDef.await()
        val indianWebSeries = indianWebSeriesDef.await()
        val bollywoodPicks = bollywoodPicksDef.await()
        val cartoons = cartoonsDef.await()
        val asianSchoolItems = asianSchoolDef.await()
        val tasteItems = tasteDef?.await().orEmpty()

        (trending + trendingMovies + trendingTv + topRated + horrorItems + nolanItems + pughItems + chalametItems + indianWebSeries + bollywoodPicks + cartoons + asianSchoolItems + tasteItems).forEach { mediaUi ->
            if (!mediaUi.coverUrl.isNullOrBlank()) {
                resolvedPosterCache[mediaUi.title.lowercase().trim()] = mediaUi.coverUrl
            }
        }

        val rails = mutableListOf<FeedRow>()

        // 1. Hero Carousel (Top 8 trending movies/tv)
        val heroPool = if (trending.isNotEmpty()) trending else (trendingMovies + trendingTv)
        val heroItems = heroPool.take(8).map {
            val isMovie = it.format?.contains("MOVIE", ignoreCase = true) == true
            it.toMedia(if (isMovie) "MOVIE" else "TV")
        }
        if (heroItems.isNotEmpty()) {
            rails.add(FeedRow.HeroCarousel(heroItems))
        }

        // 2. Continue Watching (Movie/TV only)
        if (history.isNotEmpty()) {
            val cwItems = history.map { h ->
                val bestCover = resolvedPosterCache[h.title.lowercase().trim()] ?: h.posterUrl.orEmpty()
                h.toMedia().copy(posterUrl = bestCover, backdropUrl = bestCover)
            }
            rails.add(FeedRow.ContinueWatching(items = cwItems))
        }

        // 3. Provider Brand Bubbles (Netflix, Disney+, Prime, etc.)
        rails.add(FeedRow.ProviderBubbles(providers = getBrandedProviders()))

        // 4. Your Taste (ONLY unlocked after >= 10 watches)
        if (isTasteUnlocked && tasteItems.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Your Taste", tasteItems.map { it.toMedia("MOVIE") }))
        }

        // 5. Trending Movies / TV
        val combinedTrending = (trendingMovies + trendingTv).take(20).ifEmpty { trending.take(20) }
        if (combinedTrending.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Trending Movies / TV", combinedTrending.map {
                val isMovie = it.format?.contains("MOVIE", ignoreCase = true) == true
                it.toMedia(if (isMovie) "MOVIE" else "TV")
            }))
        }

        // 6. Movie Genre › [ Horror ⌃ ] (Dynamic filter rail)
        rails.add(
            FeedRow.FilterableRail(
                railId = "movie_genre",
                title = "Movie Genre",
                filterType = "genre",
                selectedOption = "Horror",
                availableOptions = listOf(
                    "Horror", "Action", "Adventure", "Comedy", "Crime & Gangster",
                    "Drama", "Fantasy", "Mystery & Thriller", "Romance", "Sci-Fi", "War", "Western"
                ),
                items = horrorItems.map { it.toMedia("MOVIE") }
            )
        )

        // 7. Directors › [ Christopher Nolan ⌃ ] (Dynamic filter rail)
        rails.add(
            FeedRow.FilterableRail(
                railId = "movie_director",
                title = "Directors",
                filterType = "director",
                selectedOption = "Christopher Nolan",
                availableOptions = listOf(
                    "Christopher Nolan", "Quentin Tarantino", "Martin Scorsese", "Steven Spielberg",
                    "Stanley Kubrick", "David Fincher", "Denis Villeneuve", "James Cameron", "Ridley Scott", "Guillermo del Toro"
                ),
                items = nolanItems.map { it.toMedia("MOVIE") }
            )
        )

        // 8. Top Rated on IMDb
        if (topRated.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Top Rated on IMDb", topRated.map { it.toMedia("MOVIE") }))
        }

        // 9. Female Actress › [ Florence Pugh ⌃ ] (Dynamic filter rail)
        rails.add(
            FeedRow.FilterableRail(
                railId = "movie_actress",
                title = "Female Actress",
                filterType = "female_actress",
                selectedOption = "Florence Pugh",
                availableOptions = listOf(
                    "Florence Pugh", "Scarlett Johansson", "Margot Robbie", "Zendaya",
                    "Emma Stone", "Jennifer Lawrence", "Anya Taylor-Joy", "Sydney Sweeney", "Jenna Ortega", "Ana de Armas"
                ),
                items = pughItems.map { it.toMedia("MOVIE") }
            )
        )

        // 10. Male Actor › [ Timothée Chalamet ⌃ ] (Dynamic filter rail)
        rails.add(
            FeedRow.FilterableRail(
                railId = "movie_actor",
                title = "Male Actor",
                filterType = "male_actor",
                selectedOption = "Timothée Chalamet",
                availableOptions = listOf(
                    "Timothée Chalamet", "Leonardo DiCaprio", "Cillian Murphy", "Brad Pitt",
                    "Christian Bale", "Keanu Reeves", "Tom Hanks", "Robert Downey Jr.", "Ryan Gosling", "Pedro Pascal"
                ),
                items = chalametItems.map { it.toMedia("MOVIE") }
            )
        )

        // 11. Series › [ All-Time Best ⌃ ] (Dynamic filter rail)
        rails.add(
            FeedRow.FilterableRail(
                railId = "movie_series",
                title = "Series",
                filterType = "series",
                selectedOption = "All-Time Best",
                availableOptions = listOf(
                    "All-Time Best", "Trending Now", "Crime & Mystery",
                    "Sci-Fi & Fantasy", "Mini-Series", "Binge-Worthy Comedy"
                ),
                items = topRated.take(20).map { it.toMedia("TV") }
            )
        )

        // 12. Indian Web Series
        if (indianWebSeries.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Indian Web Series", indianWebSeries.map { it.toMedia("TV") }))
        }

        // 13. Bollywood Picks
        if (bollywoodPicks.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Bollywood Picks", bollywoodPicks.map { it.toMedia("MOVIE") }))
        }

        // 14. Cartoons & Animation
        if (cartoons.isNotEmpty()) {
            rails.add(FeedRow.StandardRail("Cartoons & Animation", cartoons.map { it.toMedia("TV") }))
        }

        // 15. [K-Drama & Korean shows] [ C-drama & Asian series ] (pills) › [ School & Youth ⌃ ] (Dynamic filter rail)
        rails.add(
            FeedRow.FilterableRail(
                railId = "asian_drama",
                title = "Asian Dramas",
                filterType = "asian",
                selectedOption = "School & Youth",
                availableOptions = listOf(
                    "School & Youth", "All Popular", "Romance",
                    "Mystery & Thriller", "Historical / Period", "Fantasy & Supernatural"
                ),
                pillOptions = listOf("K-Drama & Korean Shows", "C-Drama & Asian Series"),
                activePillIndex = 0,
                items = asianSchoolItems.map { it.toMedia("TV") }
            )
        )

        cachedMovieRails = rails
        if (_currentDomain.value == HomeDomain.MOVIE) {
            _feedState.value = rails
        }
    }

    /**
     * Handles in-place dynamic filter option selection for any FilterableRail.
     */
    fun onFilterRailOptionChanged(railId: String, newOption: String) {
        val currentRows = _feedState.value.toMutableList()
        val index = currentRows.indexOfFirst { it is FeedRow.FilterableRail && it.railId == railId }
        if (index == -1) return
        val currentRail = currentRows[index] as FeedRow.FilterableRail
        if (currentRail.selectedOption == newOption && !currentRail.isLoading) return

        // Set loading state immediately for quick feedback
        currentRows[index] = currentRail.copy(selectedOption = newOption, isLoading = true)
        _feedState.value = currentRows

        viewModelScope.launch {
            val newItems = runCatching {
                fetchFilterRailItems(railId, newOption, currentRail.activePillIndex)
            }.getOrDefault(emptyList())

            val updatedRows = _feedState.value.toMutableList()
            val targetIdx = updatedRows.indexOfFirst { it is FeedRow.FilterableRail && it.railId == railId }
            if (targetIdx != -1) {
                val existing = updatedRows[targetIdx] as FeedRow.FilterableRail
                updatedRows[targetIdx] = existing.copy(
                    selectedOption = newOption,
                    items = newItems,
                    isLoading = false
                )
                _feedState.value = updatedRows
                if (_currentDomain.value == HomeDomain.ANIME) {
                    cachedAnimeRails = updatedRows
                } else {
                    cachedMovieRails = updatedRows
                }
            }
        }
    }

    /**
     * Handles dual-pill toggle for pill-enabled FilterableRails (e.g. K-Drama vs C-Drama).
     */
    fun onFilterRailPillChanged(railId: String, newPillIndex: Int) {
        val currentRows = _feedState.value.toMutableList()
        val index = currentRows.indexOfFirst { it is FeedRow.FilterableRail && it.railId == railId }
        if (index == -1) return
        val currentRail = currentRows[index] as FeedRow.FilterableRail
        if (currentRail.activePillIndex == newPillIndex && !currentRail.isLoading) return

        currentRows[index] = currentRail.copy(activePillIndex = newPillIndex, isLoading = true)
        _feedState.value = currentRows

        viewModelScope.launch {
            val newItems = runCatching {
                fetchFilterRailItems(railId, currentRail.selectedOption, newPillIndex)
            }.getOrDefault(emptyList())

            val updatedRows = _feedState.value.toMutableList()
            val targetIdx = updatedRows.indexOfFirst { it is FeedRow.FilterableRail && it.railId == railId }
            if (targetIdx != -1) {
                val existing = updatedRows[targetIdx] as FeedRow.FilterableRail
                updatedRows[targetIdx] = existing.copy(
                    activePillIndex = newPillIndex,
                    items = newItems,
                    isLoading = false
                )
                _feedState.value = updatedRows
                if (_currentDomain.value == HomeDomain.ANIME) {
                    cachedAnimeRails = updatedRows
                } else {
                    cachedMovieRails = updatedRows
                }
            }
        }
    }

    private suspend fun fetchFilterRailItems(railId: String, option: String, pillIndex: Int): List<Media> {
        return when (railId) {
            "anime_genre" -> {
                repo.anilistDiscoverFiltered(genre = option).map { it.toMedia("ANIME") }
            }
            "anime_studio" -> {
                repo.studioAnime(studioName = option).map { it.toMedia("ANIME") }
            }
            "movie_genre" -> {
                val genreId = SearchFilterData.MOVIE_GENRES[option]
                repo.tmdbDiscoverFiltered("movie", pages = 2, withGenres = genreId?.toString()).map { it.toMedia("MOVIE") }
            }
            "movie_director" -> {
                val crewId = SearchFilterData.DIRECTORS[option]
                repo.tmdbDiscoverFiltered("movie", pages = 2, withCrew = crewId?.toString()).map { it.toMedia("MOVIE") }
            }
            "movie_actress" -> {
                val castId = SearchFilterData.FEMALE_ACTRESSES[option]
                repo.tmdbDiscoverFiltered("movie", pages = 2, withCast = castId?.toString()).map { it.toMedia("MOVIE") }
            }
            "movie_actor" -> {
                val castId = SearchFilterData.MALE_ACTORS[option]
                repo.tmdbDiscoverFiltered("movie", pages = 2, withCast = castId?.toString()).map { it.toMedia("MOVIE") }
            }
            "movie_series" -> {
                when (option) {
                    "All-Time Best" -> repo.tmdbTopRated().map { it.toMedia("TV") }
                    "Trending Now" -> repo.tmdbTrendingTv().map { it.toMedia("TV") }
                    "Crime & Mystery" -> repo.tmdbDiscoverFiltered("tv", pages = 2, withGenres = "80,9648").map { it.toMedia("TV") }
                    "Sci-Fi & Fantasy" -> repo.tmdbDiscoverFiltered("tv", pages = 2, withGenres = "10765").map { it.toMedia("TV") }
                    "Mini-Series" -> repo.tmdbDiscoverFiltered("tv", pages = 2, withKeywords = "210024|222243").map { it.toMedia("TV") }
                    "Binge-Worthy Comedy" -> repo.tmdbDiscoverFiltered("tv", pages = 2, withGenres = "35").map { it.toMedia("TV") }
                    else -> repo.tmdbPopularTv().map { it.toMedia("TV") }
                }
            }
            "asian_drama" -> {
                val lang = if (pillIndex == 0) "ko" else "zh"
                val list = when (option) {
                    "School & Youth" -> {
                        val res = repo.tmdbDiscoverFiltered("tv", pages = 2, withOriginalLanguage = lang, withKeywords = "10683|210024|246535")
                        if (res.isNotEmpty()) res else repo.tmdbDiscoverFiltered("tv", pages = 2, withOriginalLanguage = lang, withGenres = "18,35")
                    }
                    "All Popular" -> repo.tmdbDiscoverFiltered("tv", pages = 2, withOriginalLanguage = lang, sortBy = "popularity.desc")
                    "Romance" -> repo.tmdbDiscoverFiltered("tv", pages = 2, withOriginalLanguage = lang, withGenres = "10749,18")
                    "Mystery & Thriller" -> repo.tmdbDiscoverFiltered("tv", pages = 2, withOriginalLanguage = lang, withGenres = "9648,80")
                    "Historical / Period" -> {
                        val res = repo.tmdbDiscoverFiltered("tv", pages = 2, withOriginalLanguage = lang, withKeywords = "156096|207317")
                        if (res.isNotEmpty()) res else repo.tmdbDiscoverFiltered("tv", pages = 2, withOriginalLanguage = lang, withGenres = "18")
                    }
                    "Fantasy & Supernatural" -> repo.tmdbDiscoverFiltered("tv", pages = 2, withOriginalLanguage = lang, withGenres = "10765")
                    else -> repo.tmdbDiscoverFiltered("tv", pages = 2, withOriginalLanguage = lang, sortBy = "popularity.desc")
                }
                list.map { it.toMedia("TV") }
            }
            else -> emptyList()
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
            rawMediaType = item.mediaType,
            title = item.title,
            mediaUrl = item.mediaUrl,
            durationMs = item.durationMs,
            posterUrl = item.posterUrl,
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
