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

    init {
        // "Top Searches" before typing = trending titles
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

    private fun loadTop() {
        _state.value = _state.value.copy(loading = true, error = null, searched = false)
        viewModelScope.launch {
            val isMovieTv = mediaTypeManager?.currentMode?.value == AppMediaMode.MOVIES_TV
            runCatching {
                when (_state.value.type) {
                    SearchType.ALL -> if (isMovieTv) repo.tmdbTrending() else repo.trending()
                    SearchType.MOVIE -> repo.tmdbTrendingMovies()
                    SearchType.TV_SHOW -> repo.tmdbTrendingTv()
                    SearchType.ANIME -> repo.trending()
                    SearchType.ASIAN -> repo.tmdbKDramas()
                }
            }
                .onSuccess { items ->
                    val filtered = filter(items)
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
        _state.value = _state.value.copy(loading = true, error = null, searched = true)
        viewModelScope.launch {
            val isMovieTv = mediaTypeManager?.currentMode?.value == AppMediaMode.MOVIES_TV
            runCatching {
                when (_state.value.type) {
                    SearchType.ALL -> {
                        if (isMovieTv) repo.tmdbSearch(q) else repo.search(q)
                    }
                    SearchType.MOVIE -> repo.tmdbSearch(q).filter { it.format.equals("MOVIE", ignoreCase = true) }
                    SearchType.TV_SHOW -> repo.tmdbSearch(q).filter { !it.format.equals("MOVIE", ignoreCase = true) }
                    SearchType.ANIME -> repo.search(q)
                    SearchType.ASIAN -> {
                        val tmdbRes = repo.tmdbSearch(q).filter {
                            it.genres.any { g -> g.contains("Drama", ignoreCase = true) }
                        }
                        if (tmdbRes.isNotEmpty()) tmdbRes else repo.search(q)
                    }
                }
            }
                .onSuccess { _state.value = _state.value.copy(loading = false, results = filter(it), searched = true) }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it.message, searched = true) }
        }
    }

    /** Explicitly commits a completed search query to recent search history (Enter/Search key or result click). */
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

    fun onFiltersApplied(filters: SearchFilterOptions) {
        _state.value = _state.value.copy(filterOptions = filters)
        val q = _state.value.query
        if (q.isBlank()) loadTop() else search(q)
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

        if (filters.category.equals("Anime", ignoreCase = true)) {
            res = res.filter { !it.format.equals("MOVIE", ignoreCase = true) }
        } else if (filters.category.equals("Movies", ignoreCase = true)) {
            res = res.filter { it.format.equals("MOVIE", ignoreCase = true) }
        } else if (filters.category.equals("TV", ignoreCase = true) || filters.category.equals("TV Series", ignoreCase = true)) {
            res = res.filter { !it.format.equals("MOVIE", ignoreCase = true) }
        } else if (filters.category.equals("Cartoons", ignoreCase = true)) {
            res = res.filter { it.genres.any { g -> g.contains("Animation", ignoreCase = true) } }
        } else if (filters.category.equals("K-Drama", ignoreCase = true) || filters.category.equals("C-Drama", ignoreCase = true)) {
            res = res.filter { it.genres.any { g -> g.contains("Drama", ignoreCase = true) } }
        }

        if (filters.type != "All") {
            res = res.filter { it.format.equals(filters.type, ignoreCase = true) }
        }

        if (filters.sort == "Score") {
            res = res.sortedByDescending { it.score ?: 0.0 }
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