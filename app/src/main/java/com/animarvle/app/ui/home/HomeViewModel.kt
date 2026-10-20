package com.animarvle.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.animarvle.app.R
import com.animarvle.app.data.MetadataRepository
import com.animarvle.app.domain.MediaUi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

import com.animarvle.app.data.AppMediaMode
import com.animarvle.app.data.MediaTypeManager

data class HomeRail(val titleRes: Int, val items: List<MediaUi>)

sealed interface HomeState {
    data object Loading : HomeState
    data class Content(
        val trending: List<MediaUi>,
        val rails: List<HomeRail>,
        val continueWatching: List<com.animarvle.app.ui.player.HistoryItem> = emptyList(),
        val mode: AppMediaMode = AppMediaMode.ANIME,
    ) : HomeState
    data class Error(val message: String?) : HomeState
}

class HomeViewModel(
    private val repo: MetadataRepository,
    private val historyRepo: com.animarvle.app.ui.player.HistoryRepository? = null,
    private val mediaTypeManager: MediaTypeManager? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<HomeState>(HomeState.Loading)
    val state: StateFlow<HomeState> = _state.asStateFlow()

    val currentMode: AppMediaMode
        get() = mediaTypeManager?.currentMode?.value ?: AppMediaMode.ANIME

    init {
        load()
    }

    fun toggleMode(): AppMediaMode {
        val next = mediaTypeManager?.toggleMode() ?: AppMediaMode.ANIME
        load()
        return next
    }

    fun load() {
        _state.value = HomeState.Loading
        val mode = currentMode
        viewModelScope.launch {
            runCatching {
                coroutineScope {
                    if (mode == AppMediaMode.ANIME) {
                        val trendingDeferred = async { repo.trending() }
                        val airingNowDeferred = async { runCatching { repo.airingNow() }.getOrDefault(emptyList()) }
                        val donghuaDeferred = async { runCatching { repo.donghua() }.getOrDefault(emptyList()) }
                        val popularThisSeasonDeferred = async { runCatching { repo.popularThisSeason() }.getOrDefault(emptyList()) }
                        val upcomingNextSeasonDeferred = async { runCatching { repo.upcomingNextSeason() }.getOrDefault(emptyList()) }
                        val allTimePopularDeferred = async { runCatching { repo.allTimePopular() }.getOrDefault(emptyList()) }
                        val topRatedDeferred = async { runCatching { repo.topRated() }.getOrDefault(emptyList()) }
                        val mostFavoritedDeferred = async { runCatching { repo.mostFavorited() }.getOrDefault(emptyList()) }
                        val popularMoviesDeferred = async { runCatching { repo.popularMovies() }.getOrDefault(emptyList()) }

                        val trending = trendingDeferred.await()
                        val rails = listOf(
                            HomeRail(R.string.row_trending, trending),
                            HomeRail(R.string.row_airing_now, airingNowDeferred.await()),
                            HomeRail(R.string.row_donghua, donghuaDeferred.await()),
                            HomeRail(R.string.row_popular_this_season, popularThisSeasonDeferred.await()),
                            HomeRail(R.string.row_upcoming_next_season, upcomingNextSeasonDeferred.await()),
                            HomeRail(R.string.row_all_time_popular, allTimePopularDeferred.await()),
                            HomeRail(R.string.row_top_rated, topRatedDeferred.await()),
                            HomeRail(R.string.row_most_favorited, mostFavoritedDeferred.await()),
                            HomeRail(R.string.row_popular_movies, popularMoviesDeferred.await()),
                        )
                        trending to rails
                    } else {
                        val trendingDeferred = async { repo.tmdbTrending() }
                        val trendingMoviesDeferred = async { runCatching { repo.tmdbTrendingMovies() }.getOrDefault(emptyList()) }
                        val trendingTvDeferred = async { runCatching { repo.tmdbTrendingTv() }.getOrDefault(emptyList()) }
                        val cartoonsDeferred = async { runCatching { repo.tmdbCartoons() }.getOrDefault(emptyList()) }
                        val kdramasDeferred = async { runCatching { repo.tmdbKDramas() }.getOrDefault(emptyList()) }
                        val cdramasDeferred = async { runCatching { repo.tmdbCDramas() }.getOrDefault(emptyList()) }
                        val popularMoviesDeferred = async { runCatching { repo.tmdbPopularMovies() }.getOrDefault(emptyList()) }
                        val popularTvDeferred = async { runCatching { repo.tmdbPopularTv() }.getOrDefault(emptyList()) }
                        val topRatedDeferred = async { runCatching { repo.tmdbTopRated() }.getOrDefault(emptyList()) }
                        val nowPlayingDeferred = async { runCatching { repo.tmdbNowPlaying() }.getOrDefault(emptyList()) }

                        val trending = trendingDeferred.await()
                        val rails = listOf(
                            HomeRail(R.string.row_trending_movies, trendingMoviesDeferred.await()),
                            HomeRail(R.string.row_trending_tv, trendingTvDeferred.await()),
                            HomeRail(R.string.row_cartoons, cartoonsDeferred.await()),
                            HomeRail(R.string.row_kdrama, kdramasDeferred.await()),
                            HomeRail(R.string.row_cdrama, cdramasDeferred.await()),
                            HomeRail(R.string.row_popular_movies, popularMoviesDeferred.await()),
                            HomeRail(R.string.row_popular_tv, popularTvDeferred.await()),
                            HomeRail(R.string.row_top_rated, topRatedDeferred.await()),
                            HomeRail(R.string.row_now_playing, nowPlayingDeferred.await()),
                        )
                        trending to rails
                    }
                }
            }.onSuccess { (trending, rails) ->
                val allHistory = historyRepo?.historyFlow?.value.orEmpty()
                val history = if (mode == AppMediaMode.ANIME) {
                    allHistory.filter { it.mediaType.equals("ANIME", ignoreCase = true) }
                } else {
                    allHistory.filter { it.mediaType.equals("MOVIE", ignoreCase = true) || it.mediaType.equals("TV", ignoreCase = true) }
                }
                _state.value = HomeState.Content(trending, rails, history, mode)
            }.onFailure {
                _state.value = HomeState.Error(it.message)
            }
        }
    }

    class Factory(
        private val repo: MetadataRepository,
        private val historyRepo: com.animarvle.app.ui.player.HistoryRepository? = null,
        private val mediaTypeManager: MediaTypeManager? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(repo, historyRepo, mediaTypeManager) as T
    }
}