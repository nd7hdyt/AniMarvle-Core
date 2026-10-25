package com.animarvle.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.animarvle.app.data.MetadataRepository
import com.animarvle.app.domain.MediaUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DetailState {
    data object Loading : DetailState
    data class Content(
        val media: MediaUi,
        val recommendations: List<MediaUi>,
    ) : DetailState

    data class Error(val message: String?) : DetailState
}

class DetailViewModel(
    private val repo: MetadataRepository,
    private var mediaId: Long,
    private val preferTmdb: Boolean = false,
    private val mediaType: String? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<DetailState>(DetailState.Loading)
    val state: StateFlow<DetailState> = _state.asStateFlow()

    init {
        load()
    }

    fun load(newMediaId: Long? = null) {
        val targetId = newMediaId ?: mediaId
        if (targetId <= 0) {
            _state.value = DetailState.Error("Missing media id")
            return
        }
        mediaId = targetId
        _state.value = DetailState.Loading
        viewModelScope.launch {
            runCatching {
                val media = repo.details(mediaId, preferTmdb, mediaType) ?: error("Title not found")
                val resolvedType = mediaType ?: if (media.format.equals("MOVIE", ignoreCase = true)) "movie" else "tv"
                val recs = runCatching { repo.recommendations(mediaId, preferTmdb, resolvedType) }.getOrDefault(emptyList())
                media to recs
            }.onSuccess { (media, recs) ->
                _state.value = DetailState.Content(media, recs)
            }.onFailure {
                _state.value = DetailState.Error(it.message)
            }
        }
    }

    class Factory(
        private val repo: MetadataRepository,
        private val mediaId: Long,
        private val preferTmdb: Boolean = false,
        private val mediaType: String? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DetailViewModel(repo, mediaId, preferTmdb, mediaType) as T
    }
}