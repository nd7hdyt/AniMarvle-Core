package com.animarvle.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.animarvle.app.data.AppMediaMode
import com.animarvle.app.data.MediaTypeManager
import com.animarvle.app.data.MetadataRepository
import com.animarvle.app.domain.MediaUi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Categories matching AniVortex reference design */
enum class SearchType(val displayName: String, val hintText: String) {
    ALL("All", "Search all"),
    MOVIE("Movie", "Search movies"),
    TV_SHOW("TV Show", "Search TV shows"),
    ANIME("Anime", "Search anime"),
    ASIAN("Asian", "Search Asian dramas"),
}

val DEFAULT_TRENDING_TAGS = listOf(
    "The odyssey",
    "Spiderman",
    "Lioness",
    "Silo",
    "Runner",
    "Reacher",
    "Hope",
    "Mobland",
    "Solo Leveling",
    "Demon Slayer",
    "One Piece",
    "Squid Game",
    "Arcane",
    "Jujutsu Kaisen",
)

data class SearchState(
    val query: String = "",
    val type: SearchType = SearchType.ALL,
    val filterOptions: SearchFilterOptions = SearchFilterOptions(),
    val loading: Boolean = false,
    val results: List<MediaUi> = emptyList(),
    val recentQueries: List<String> = emptyList(),
    val trendingTags: List<String> = DEFAULT_TRENDING_TAGS,
    val error: String? = null,
    val searched: Boolean = false,
)

@OptIn(FlowPreview::class)
class SearchViewModel(
    private val repo: MetadataRepository,
    private val recentRepo: RecentSearchRepository? = null,
    private val mediaTypeManager: MediaTypeManager? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private val queryFlow = MutableStateFlow("")
    private var currentPage = 1
    private var isLoadingMore = false
    private var canLoadMore = true

    init {
        // "Top Searches" before typing = trending titles / active filters
        loadTop()
        recentRepo?.let { rr ->
            viewModelScope.launch {
                rr.recentSearches.collect { recents ->
                    _state.value = _state.value.copy(recentQueries = recents)
                }
            }
        }
        viewModelScope.launch {
            queryFlow
                .debounce(DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { q -> if (q.isBlank()) loadTop() else search(q) }
        }
    }

    fun onQueryChanged(q: String) {
        _state.value = _state.value.copy(query = q)
        queryFlow.value = q
    }

    fun onTypeChanged(type: SearchType) {
        _state.value = _state.value.copy(type = type)
        val q = _state.value.query
        if (q.isBlank()) loadTop() else search(q)
    }

    fun retry() {
        val q = _state.value.query
        if (q.isBlank()) loadTop() else search(q)
    }

    fun onFiltersApplied(filters: SearchFilterOptions) {
        _state.value = _state.value.copy(filterOptions = filters)
        val q = _state.value.query
        if (q.isBlank()) loadTop() else search(q)
    }

    private fun loadTop() {
        currentPage = 1
        canLoadMore = true
        isLoadingMore = false
        _state.value = _state.value.copy(loading = true, error = null, searched = false)
        viewModelScope.launch {
            val isMovieTv = mediaTypeManager?.currentMode?.value == AppMediaMode.MOVIES_TV ||
                _state.value.type == SearchType.MOVIE ||
                _state.value.type == SearchType.TV_SHOW
            val filters = _state.value.filterOptions

            runCatching {
                fetchBatch(page = 1, pages = 3, q = "", filters = filters, isMovieTv = isMovieTv)
            }
                .onSuccess { rawItems ->
                    val filtered = filter(rawItems)
                    currentPage = 4
                    val dynamicTags = if (filtered.isNotEmpty()) {
                        (filtered.take(6).map { it.title } + DEFAULT_TRENDING_TAGS).distinct().take(14)
                    } else {
                        DEFAULT_TRENDING_TAGS
                    }
                    _state.value = _state.value.copy(
                        loading = false,
                        results = filtered,
                        trendingTags = dynamicTags,
                        searched = false,
                    )
                }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it.message) }
        }
    }

    private fun search(q: String) {
        currentPage = 1
        canLoadMore = true
        isLoadingMore = false
        _state.value = _state.value.copy(loading = true, error = null, searched = true)
        viewModelScope.launch {
            val isMovieTv = mediaTypeManager?.currentMode?.value == AppMediaMode.MOVIES_TV
            val filters = _state.value.filterOptions

            runCatching {
                fetchBatch(page = 1, pages = 4, q = q, filters = filters, isMovieTv = isMovieTv)
            }
                .onSuccess { rawItems ->
                    currentPage = 5
                    _state.value = _state.value.copy(
                        loading = false,
                        results = filter(rawItems),
                        searched = true,
                    )
                }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it.message, searched = true) }
        }
    }

    fun loadMore() {
        if (_state.value.loading || isLoadingMore || !canLoadMore) return
        val currentResults = _state.value.results
        if (currentResults.isEmpty()) return

        isLoadingMore = true
        val fetchPage = currentPage
        viewModelScope.launch {
            val isMovieTv = mediaTypeManager?.currentMode?.value == AppMediaMode.MOVIES_TV ||
                _state.value.type == SearchType.MOVIE ||
                _state.value.type == SearchType.TV_SHOW
            val q = _state.value.query.trim()
            val filters = _state.value.filterOptions

            val newBatch = runCatching {
                fetchBatch(page = fetchPage, pages = 3, q = q, filters = filters, isMovieTv = isMovieTv)
            }.getOrDefault(emptyList())

            if (newBatch.isEmpty()) {
                canLoadMore = false
            } else {
                val filtered = filter(newBatch).filter { newItem ->
                    !currentResults.any { it.id == newItem.id }
                }
                currentPage = fetchPage + 3
                if (filtered.isEmpty()) {
                    if (newBatch.size < 10) {
                        canLoadMore = false
                    }
                } else {
                    _state.value = _state.value.copy(
                        results = currentResults + filtered
                    )
                }
            }
            isLoadingMore = false
        }
    }

    private suspend fun fetchBatch(
        page: Int,
        pages: Int,
        q: String,
        filters: SearchFilterOptions,
        isMovieTv: Boolean,
    ): List<MediaUi> {
        val isAdult18Plus = filters.adultContent.equals("Adult (18+)", ignoreCase = true) ||
            filters.genres.any { it.equals("Adult (18+)", ignoreCase = true) }
        val isAdultWestern = filters.adultContent.equals("Adult", ignoreCase = true) ||
            filters.genres.any { it.equals("Adult", ignoreCase = true) }
        val isHentai = filters.adultContent.equals("Hentai", ignoreCase = true) ||
            filters.genres.any { it.equals("Hentai", ignoreCase = true) }
        val isEcchi = filters.adultContent.equals("Ecchi", ignoreCase = true) ||
            filters.genres.any { it.equals("Ecchi", ignoreCase = true) }

        // Adult options take precedence
        if (isAdult18Plus) {
            return repo.searchAsianCinema(q, startPage = page, pages = pages)
        }
        if (isAdultWestern) {
            return repo.tmdbAdultRealMovies(startPage = page, pages = pages)
        }
        if (isHentai) {
            return repo.anilistDiscoverFiltered(genre = "Hentai", startPage = page, pages = pages.coerceAtMost(2))
        }
        if (isEcchi) {
            return repo.anilistDiscoverFiltered(genre = "Ecchi", startPage = page, pages = pages.coerceAtMost(2))
        }

        val hasQuery = q.isNotBlank()
        val isAdultQuery = hasQuery && (
            Regex("""\b[a-zA-Z]{2,6}[-_]?\d{2,5}\b""").containsMatchIn(q) ||
            listOf("jav", "uncensored", "pink film", "roman porno", "stepmother", "mother-in-law", "korean wife", "sweet whip", "young mother", "cat3", "mov18plus", "vivamax", "pinoy", "tagalog", "alindog", "sukdulan", "dutdot", "angeli", "raval").any { q.contains(it, ignoreCase = true) }
        )

        if (isAdultQuery) {
            return repo.searchAsianCinema(q, startPage = page, pages = pages)
        }

        // When user typed a search query
        if (hasQuery) {
            return when (_state.value.type) {
                SearchType.ALL -> {
                    val primary = runCatching {
                        if (isMovieTv) repo.tmdbSearch(q, startPage = page, pages = pages)
                        else repo.search(q, page = page)
                    }.getOrDefault(emptyList())
                    val secondary = runCatching {
                        if (isMovieTv) repo.search(q, page = page)
                        else repo.tmdbSearch(q, startPage = page, pages = pages)
                    }.getOrDefault(emptyList())
                    (primary + secondary).distinctBy { "${it.id}:${it.format}" }
                }
                SearchType.MOVIE -> repo.tmdbSearch(q, startPage = page, pages = pages).filter { it.format.equals("MOVIE", ignoreCase = true) }
                SearchType.TV_SHOW -> repo.tmdbSearch(q, startPage = page, pages = pages).filter { !it.format.equals("MOVIE", ignoreCase = true) }
                SearchType.ANIME -> repo.search(q, page = page)
                SearchType.ASIAN -> {
                    val tmdbRes = repo.tmdbSearch(q, startPage = page, pages = pages).filter {
                        it.genres.any { g -> g.contains("Drama", ignoreCase = true) }
                    }
                    if (tmdbRes.isNotEmpty()) tmdbRes else repo.search(q, page = page)
                }
            }
        }

        // When in Filter Catalog discovery mode (query is empty)
        val tmdbGenreIds = filters.genres.mapNotNull { SearchFilterData.MOVIE_GENRES[it] }.filter { it > 0 }
        val genreParam = if (tmdbGenreIds.isNotEmpty()) tmdbGenreIds.joinToString(",") else null
        val directorParam = filters.director?.let { SearchFilterData.DIRECTORS[it]?.toString() }
        val actorParam = filters.maleActor?.let { SearchFilterData.MALE_ACTORS[it]?.toString() }
            ?: filters.femaleActress?.let { SearchFilterData.FEMALE_ACTRESSES[it]?.toString() }
        val studioParam = filters.studio?.let { SearchFilterData.STUDIOS[it]?.toString() }

        val parsedYear = filters.year.toIntOrNull()
        val yearParam = if (filters.year != "Any" && filters.year.isNotBlank()) filters.year else null
        val minRatingVal = if (filters.minRating > 0.0) filters.minRating else null

        val sortMappingTmdb = when (filters.sort) {
            "Highest rated" -> "vote_average.desc"
            "Most popular" -> "popularity.desc"
            "Newest" -> "primary_release_date.desc"
            "Oldest" -> "primary_release_date.asc"
            "A-Z" -> "title.asc"
            else -> "popularity.desc"
        }

        val sortMappingAniList = when (filters.sort) {
            "Highest rated" -> "SCORE_DESC"
            "Most popular" -> "POPULARITY_DESC"
            "Newest" -> "START_DATE_DESC"
            "Oldest" -> "START_DATE"
            "A-Z" -> "TITLE_ENGLISH"
            "Trending" -> "TRENDING_DESC"
            "Most episodes" -> "EPISODES_DESC"
            else -> "POPULARITY_DESC"
        }

        val hasCustomFilter = genreParam != null || directorParam != null || actorParam != null ||
            studioParam != null || yearParam != null || minRatingVal != null ||
            filters.sort != "Trending" || filters.format != "Any" || filters.category != "All"

        if (isMovieTv || filters.category.equals("Movies", ignoreCase = true) ||
            filters.category.equals("TV Series", ignoreCase = true) || filters.category.equals("TV", ignoreCase = true)
        ) {
            val tmdbType = when {
                _state.value.type == SearchType.TV_SHOW -> "tv"
                _state.value.type == SearchType.MOVIE -> "movie"
                filters.format.equals("Movie", ignoreCase = true) || filters.category.equals("Movies", ignoreCase = true) -> "movie"
                filters.format.equals("TV Series", ignoreCase = true) || filters.category.equals("TV", ignoreCase = true) || filters.category.equals("TV Series", ignoreCase = true) -> "tv"
                else -> "movie"
            }

            if (hasCustomFilter) {
                if (_state.value.type == SearchType.ALL && filters.format == "Any") {
                    val movies = repo.tmdbDiscoverFiltered(
                        type = "movie",
                        startPage = page,
                        pages = pages,
                        withGenres = genreParam,
                        withCrew = directorParam,
                        withCast = actorParam,
                        withCompanies = studioParam,
                        voteAverageGte = minRatingVal,
                        primaryReleaseYear = yearParam,
                        sortBy = sortMappingTmdb,
                    )
                    val tv = repo.tmdbDiscoverFiltered(
                        type = "tv",
                        startPage = page,
                        pages = pages,
                        withGenres = genreParam,
                        withCrew = directorParam,
                        withCast = actorParam,
                        withCompanies = studioParam,
                        voteAverageGte = minRatingVal,
                        primaryReleaseYear = yearParam,
                        sortBy = sortMappingTmdb,
                    )
                    return (movies + tv).distinctBy { "${it.id}:${it.format}" }
                } else {
                    return repo.tmdbDiscoverFiltered(
                        type = tmdbType,
                        startPage = page,
                        pages = pages,
                        withGenres = genreParam,
                        withCrew = directorParam,
                        withCast = actorParam,
                        withCompanies = studioParam,
                        voteAverageGte = minRatingVal,
                        primaryReleaseYear = yearParam,
                        sortBy = sortMappingTmdb,
                    )
                }
            } else {
                return when (_state.value.type) {
                    SearchType.ALL -> repo.tmdbTrending(startPage = page, pages = pages)
                    SearchType.MOVIE -> repo.tmdbTrendingMovies(startPage = page, pages = pages)
                    SearchType.TV_SHOW -> repo.tmdbTrendingTv(startPage = page, pages = pages)
                    SearchType.ANIME -> repo.anilistDiscoverFiltered(sortBy = "TRENDING_DESC", startPage = page, pages = 2)
                    SearchType.ASIAN -> repo.tmdbKDramas(startPage = page, pages = pages)
                }
            }
        } else {
            // Anime mode
            val animeGenre = filters.genres.firstOrNull { SearchFilterData.ANIME_GENRES.contains(it) }
            val anilistFormat = when (filters.format) {
                "Movie" -> "MOVIE"
                "TV Series", "Anime TV" -> "TV"
                "OVA / ONA" -> "OVA"
                "Special" -> "SPECIAL"
                else -> null
            }
            if (hasCustomFilter) {
                return repo.anilistDiscoverFiltered(
                    genre = animeGenre,
                    format = anilistFormat,
                    year = parsedYear,
                    sortBy = sortMappingAniList,
                    startPage = page,
                    pages = 2,
                )
            } else {
                return when (_state.value.type) {
                    SearchType.ALL -> repo.trending()
                    SearchType.MOVIE -> repo.anilistDiscoverFiltered(format = "MOVIE", sortBy = "POPULARITY_DESC", startPage = page, pages = 2)
                    SearchType.TV_SHOW -> repo.anilistDiscoverFiltered(format = "TV", sortBy = "POPULARITY_DESC", startPage = page, pages = 2)
                    SearchType.ANIME -> repo.trending()
                    SearchType.ASIAN -> repo.tmdbKDramas(startPage = page, pages = pages)
                }
            }
        }
    }

    /** Explicitly commits a completed search query to recent search history. */
    fun commitSearchQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        recentRepo?.let { rr ->
            viewModelScope.launch { rr.addSearchQuery(trimmed) }
        }
    }

    fun removeRecentQuery(query: String) {
        recentRepo?.let { rr ->
            viewModelScope.launch { rr.removeSearchQuery(query) }
        }
    }

    fun clearRecentQueries() {
        recentRepo?.let { rr ->
            viewModelScope.launch { rr.clearAll() }
        }
    }

    private fun filter(items: List<MediaUi>): List<MediaUi> {
        // Exclude items without any visual artwork so users never see blank cards
        val visualItems = items.filter { !it.coverUrl.isNullOrBlank() || !it.bannerUrl.isNullOrBlank() }

        val typeFiltered = when (_state.value.type) {
            SearchType.ALL -> visualItems
            SearchType.MOVIE -> visualItems.filter { it.format.equals("MOVIE", ignoreCase = true) }
            SearchType.TV_SHOW -> visualItems.filter { !it.format.equals("MOVIE", ignoreCase = true) }
            SearchType.ANIME -> visualItems.filter { !it.format.equals("MOVIE", ignoreCase = true) }
            SearchType.ASIAN -> visualItems.filter { it.genres.any { g -> g.contains("Drama", ignoreCase = true) } }
        }

        val filters = _state.value.filterOptions
        var res = typeFiltered

        // 1. Category Filter
        if (filters.category.equals("Anime", ignoreCase = true)) {
            res = res.filter { !it.format.equals("MOVIE", ignoreCase = true) || it.genres.any { g -> g.contains("Animation", ignoreCase = true) } }
        } else if (filters.category.equals("Movies", ignoreCase = true)) {
            res = res.filter { it.format.equals("MOVIE", ignoreCase = true) }
        } else if (filters.category.equals("TV", ignoreCase = true) || filters.category.equals("TV Series", ignoreCase = true)) {
            res = res.filter { !it.format.equals("MOVIE", ignoreCase = true) }
        } else if (filters.category.equals("Cartoons", ignoreCase = true)) {
            res = res.filter { it.genres.any { g -> g.contains("Animation", ignoreCase = true) } }
        } else if (filters.category.equals("K-Drama", ignoreCase = true) || filters.category.equals("C-Drama", ignoreCase = true)) {
            res = res.filter { it.genres.any { g -> g.contains("Drama", ignoreCase = true) } }
        }

        // 2. Format & Type Filter
        val activeFormat = if (filters.format != "Any" && filters.format.isNotBlank()) filters.format else filters.type
        if (activeFormat != "Any" && activeFormat != "All" && activeFormat.isNotBlank()) {
            when (activeFormat) {
                "Movie" -> res = res.filter { it.format.equals("MOVIE", ignoreCase = true) }
                "TV Series" -> res = res.filter { !it.format.equals("MOVIE", ignoreCase = true) }
                "Anime TV" -> res = res.filter { it.format.equals("TV", ignoreCase = true) || it.format.equals("TV_SHORT", ignoreCase = true) }
                "OVA / ONA" -> res = res.filter { it.format.equals("OVA", ignoreCase = true) || it.format.equals("ONA", ignoreCase = true) }
                "Special" -> res = res.filter { it.format.equals("SPECIAL", ignoreCase = true) }
                else -> res = res.filter { it.format.equals(activeFormat, ignoreCase = true) }
            }
        }

        // 3. Year & Decades Filter
        if (filters.year != "Any" && filters.year.isNotBlank()) {
            val yStr = filters.year
            val exactYear = yStr.toIntOrNull()
            if (exactYear != null) {
                res = res.filter { it.year == exactYear || it.startDateIso?.startsWith(yStr) == true }
            } else if (yStr.endsWith("s")) {
                val start = yStr.removeSuffix("s").toIntOrNull()
                if (start != null) {
                    val end = start + 9
                    res = res.filter {
                        val itemYear = it.year ?: it.startDateIso?.take(4)?.toIntOrNull()
                        itemYear != null && itemYear in start..end
                    }
                }
            } else if (yStr.contains("-")) {
                val parts = yStr.split("-")
                val start = parts.getOrNull(0)?.trim()?.toIntOrNull()
                val end = parts.getOrNull(1)?.trim()?.toIntOrNull()
                if (start != null && end != null) {
                    res = res.filter {
                        val itemYear = it.year ?: it.startDateIso?.take(4)?.toIntOrNull()
                        itemYear != null && itemYear in start..end
                    }
                }
            } else if (yStr.equals("Earlier", ignoreCase = true)) {
                res = res.filter {
                    val itemYear = it.year ?: it.startDateIso?.take(4)?.toIntOrNull()
                    itemYear != null && itemYear < 1980
                }
            }
        }

        // 4. Min Rating Filter
        if (filters.minRating > 0.0) {
            res = res.filter { (it.score ?: 0.0) >= filters.minRating }
        }

        // 5. Genres Filter
        if (filters.genres.isNotEmpty()) {
            res = res.filter { item ->
                val itemGenres = item.genres.map { it.lowercase() }
                filters.genres.any { sel ->
                    val s = sel.lowercase()
                    when (s) {
                        "adult (18+)" -> itemGenres.any { it == "adult (18+)" || it == "korean 18+" || it == "jav" || it == "asian cinema" } || item.id >= 800000000L
                        "adult" -> itemGenres.any { it == "adult" } || (item.isTmdb && item.genres.any { it.contains("erotic", ignoreCase = true) || it.contains("romance", ignoreCase = true) || it.contains("thriller", ignoreCase = true) })
                        "hentai" -> itemGenres.any { it == "hentai" }
                        "ecchi" -> itemGenres.any { it == "ecchi" }
                        else -> itemGenres.any { it.contains(s) || s.contains(it) }
                    }
                }
            }
        }

        // 6. Adult Content Filter
        when (filters.adultContent) {
            "Adult (18+)" -> {
                res = res.filter {
                    it.id >= 800000000L || it.genres.any { g ->
                        g.contains("adult (18+)", ignoreCase = true) || g.contains("korean 18+", ignoreCase = true) || g.contains("jav", ignoreCase = true)
                    }
                }
            }
            "Adult" -> {
                res = res.filter {
                    it.isTmdb || it.genres.any { g -> g.equals("Adult", ignoreCase = true) }
                }
            }
            "Hentai" -> {
                res = res.filter { it.genres.any { g -> g.contains("Hentai", ignoreCase = true) } }
            }
            "Ecchi" -> {
                res = res.filter { it.genres.any { g -> g.contains("Ecchi", ignoreCase = true) } }
            }
            "Family Friendly (Safe)" -> {
                res = res.filter { item ->
                    item.id < 800000000L && item.genres.none {
                        it.contains("adult", ignoreCase = true) || it.contains("erotic", ignoreCase = true) || it.contains("hentai", ignoreCase = true)
                    }
                }
            }
        }

        // 7. Sort Filter
        when (filters.sort) {
            "Highest rated" -> res = res.sortedByDescending { it.score ?: 0.0 }
            "Newest" -> res = res.sortedByDescending { it.year ?: it.startDateIso?.take(4)?.toIntOrNull() ?: 0 }
            "Oldest" -> res = res.sortedBy { it.year ?: it.startDateIso?.take(4)?.toIntOrNull() ?: 9999 }
            "A-Z" -> res = res.sortedBy { it.title.lowercase() }
        }

        return res
    }

    class Factory(
        private val repo: MetadataRepository,
        private val recentRepo: RecentSearchRepository? = null,
        private val mediaTypeManager: MediaTypeManager? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SearchViewModel(repo, recentRepo, mediaTypeManager) as T
    }

    companion object {
        const val DEBOUNCE_MS = 350L
    }
}
