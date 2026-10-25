package com.animarvle.app.ui.provider

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.animarvle.app.data.MetadataRepository
import com.animarvle.app.domain.MediaUi
import com.animarvle.app.ui.home.Media
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CatalogSort {
    POPULAR, NEWEST, TOP_RATED
}

data class ProviderConfig(
    val isAnimeOnly: Boolean,
    val tmdbProviderId: String?,
    val region: String = "US"
)

class ProviderCatalogViewModel(
    private val repo: MetadataRepository,
    val providerName: String
) : ViewModel() {

    val config = getProviderConfig(providerName)

    private val _currentCategory = MutableStateFlow("MOVIE")
    val currentCategory: StateFlow<String> = _currentCategory.asStateFlow()

    private val _currentSort = MutableStateFlow(CatalogSort.POPULAR)
    val currentSort: StateFlow<CatalogSort> = _currentSort.asStateFlow()

    private val _catalogState = MutableStateFlow<List<Media>>(emptyList())
    val catalogState: StateFlow<List<Media>> = _catalogState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadCatalog()
    }

    fun setCategory(category: String) {
        if (_currentCategory.value != category) {
            _currentCategory.value = category
            loadCatalog()
        }
    }

    fun setSort(sort: CatalogSort) {
        if (_currentSort.value != sort) {
            _currentSort.value = sort
            loadCatalog()
        }
    }

    private fun loadCatalog() {
        viewModelScope.launch {
            _isLoading.value = true
            val category = _currentCategory.value
            val sort = _currentSort.value

            val tmdbSort = when (sort) {
                CatalogSort.POPULAR -> "popularity.desc"
                CatalogSort.NEWEST -> if (category == "MOVIE") "primary_release_date.desc" else "first_air_date.desc"
                CatalogSort.TOP_RATED -> "popularity.desc" // Will sort by score in memory for reliable ratings
            }

            val rawList: List<MediaUi> = runCatching {
                if (config.isAnimeOnly) {
                    // Crunchyroll / Anime-only provider:
                    when (category) {
                        "MOVIE" -> {
                            // Real Anime Movies (Doraemon, Perfect Blue, Demon Slayer, Your Name, etc.)
                            val animeMovies = repo.discoverProviderCatalog(
                                type = "movie",
                                withGenres = "16",
                                withOriginalLanguage = "ja",
                                sortBy = tmdbSort
                            )
                            if (animeMovies.isNotEmpty()) {
                                if (sort == CatalogSort.TOP_RATED) animeMovies.sortedByDescending { it.score ?: 0.0 }
                                else animeMovies
                            } else {
                                repo.popularMovies().filter { it.format?.contains("MOVIE", ignoreCase = true) == true }
                            }
                        }
                        "SERIES" -> {
                            // Real Anime Series (Doraemon, Bleach, Naruto, One Piece, etc.)
                            val animeSeries = repo.discoverProviderCatalog(
                                type = "tv",
                                withGenres = "16",
                                withOriginalLanguage = "ja",
                                sortBy = tmdbSort
                            )
                            if (animeSeries.isNotEmpty()) {
                                if (sort == CatalogSort.TOP_RATED) animeSeries.sortedByDescending { it.score ?: 0.0 }
                                else animeSeries
                            } else {
                                repo.allTimePopular()
                            }
                        }
                        else -> { // "ANIME"
                            when (sort) {
                                CatalogSort.POPULAR -> repo.allTimePopular()
                                CatalogSort.NEWEST -> repo.popularThisSeason().ifEmpty { repo.trending() }
                                CatalogSort.TOP_RATED -> repo.topRated().ifEmpty { repo.allTimePopular() }
                            }
                        }
                    }
                } else {
                    // Mainstream Providers: Netflix, Prime Video, Disney+, Apple TV+, Hotstar, Max, etc.
                    when (category) {
                        "MOVIE" -> {
                            val movies = repo.discoverProviderCatalog(
                                type = "movie",
                                watchProviders = config.tmdbProviderId,
                                watchRegion = config.region,
                                sortBy = tmdbSort
                            )
                            val list = if (movies.isNotEmpty()) movies else repo.tmdbPopularMovies()
                            if (sort == CatalogSort.TOP_RATED) list.sortedByDescending { it.score ?: 0.0 } else list
                        }
                        "SERIES" -> {
                            val series = repo.discoverProviderCatalog(
                                type = "tv",
                                watchProviders = config.tmdbProviderId,
                                watchRegion = config.region,
                                sortBy = tmdbSort
                            )
                            val list = if (series.isNotEmpty()) series else repo.tmdbPopularTv()
                            if (sort == CatalogSort.TOP_RATED) list.sortedByDescending { it.score ?: 0.0 } else list
                        }
                        else -> { // "ANIME"
                            val providerAnime = if (config.tmdbProviderId != null) {
                                repo.discoverProviderCatalog(
                                    type = "tv",
                                    watchProviders = config.tmdbProviderId,
                                    watchRegion = config.region,
                                    withGenres = "16",
                                    withOriginalLanguage = "ja",
                                    sortBy = tmdbSort
                                )
                            } else emptyList()

                            if (providerAnime.isNotEmpty()) {
                                if (sort == CatalogSort.TOP_RATED) providerAnime.sortedByDescending { it.score ?: 0.0 }
                                else providerAnime
                            } else {
                                when (sort) {
                                    CatalogSort.POPULAR -> repo.allTimePopular()
                                    CatalogSort.NEWEST -> repo.popularThisSeason().ifEmpty { repo.trending() }
                                    CatalogSort.TOP_RATED -> repo.topRated().ifEmpty { repo.allTimePopular() }
                                }
                            }
                        }
                    }
                }
            }.getOrDefault(emptyList())

            val mediaList = rawList.mapIndexed { index, item ->
                val isMovie = category == "MOVIE" || item.format?.contains("MOVIE", ignoreCase = true) == true
                val isSeries = category == "SERIES" || item.format?.contains("TV", ignoreCase = true) == true
                val type = if (isMovie) "MOVIE" else (if (isSeries) "TV" else "ANIME")

                val tags = mutableListOf<String>()
                if (isMovie) tags.add("MOVIE")
                else if (isSeries) tags.add("SERIES")
                else tags.add("ANIME")

                // Add MULTI badge for multi-audio/quality (alternating or high rated)
                if (index % 2 == 0 || (item.score ?: 0.0) >= 8.2) {
                    tags.add("MULTI")
                }
                // Add NEW badge for recent releases (year >= 2024 or top results in NEWEST)
                if ((item.year ?: 0) >= 2024 || (sort == CatalogSort.NEWEST && index < 4)) {
                    tags.add("NEW")
                }

                Media(
                    id = item.id.toString(),
                    title = item.title,
                    posterUrl = item.coverUrl.orEmpty(),
                    backdropUrl = item.bannerUrl ?: item.coverUrl,
                    year = item.year?.toString(),
                    score = item.score,
                    tags = tags,
                    mediaType = type,
                    originalId = item.id
                )
            }

            _catalogState.value = mediaList
            _isLoading.value = false
        }
    }

    private fun getProviderConfig(providerName: String): ProviderConfig {
        val clean = providerName.trim().lowercase()
        return when {
            clean.contains("crunchyroll") -> ProviderConfig(
                isAnimeOnly = true,
                tmdbProviderId = "283",
                region = "US"
            )
            clean.contains("netflix") -> ProviderConfig(
                isAnimeOnly = false,
                tmdbProviderId = "8",
                region = "US"
            )
            clean.contains("prime") -> ProviderConfig(
                isAnimeOnly = false,
                tmdbProviderId = "9|119",
                region = "US"
            )
            clean.contains("disney") -> ProviderConfig(
                isAnimeOnly = false,
                tmdbProviderId = "337",
                region = "US"
            )
            clean.contains("apple") -> ProviderConfig(
                isAnimeOnly = false,
                tmdbProviderId = "350",
                region = "US"
            )
            clean.contains("sony") -> ProviderConfig(
                isAnimeOnly = false,
                tmdbProviderId = "237",
                region = "IN"
            )
            clean.contains("hotstar") -> ProviderConfig(
                isAnimeOnly = false,
                tmdbProviderId = "122|337",
                region = "IN"
            )
            clean.contains("max") || clean.contains("hbo") -> ProviderConfig(
                isAnimeOnly = false,
                tmdbProviderId = "1899|384",
                region = "US"
            )
            clean.contains("hulu") -> ProviderConfig(
                isAnimeOnly = false,
                tmdbProviderId = "15",
                region = "US"
            )
            clean.contains("paramount") -> ProviderConfig(
                isAnimeOnly = false,
                tmdbProviderId = "531",
                region = "US"
            )
            else -> ProviderConfig(
                isAnimeOnly = false,
                tmdbProviderId = null,
                region = "US"
            )
        }
    }

    class Factory(
        private val repo: MetadataRepository,
        private val providerName: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ProviderCatalogViewModel(repo, providerName) as T
        }
    }
}
