package com.animarvle.app.ui.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animarvle.app.extensions.ExtensionRepo
import com.animarvle.app.extensions.ExtensionRepoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ExtensionReposViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ExtensionRepoRepository(app)

    private val _repos = MutableStateFlow<List<ExtensionRepo>>(emptyList())
    val repos: StateFlow<List<ExtensionRepo>> = _repos.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _repos.value = repo.getAll()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load repos"
            } finally {
                _loading.value = false
            }
        }
    }

    fun addRepo(baseUrl: String) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val meta = repo.fetchRepoMeta(baseUrl)
                if (meta == null) {
                    _error.value = "Could not fetch repo metadata from $baseUrl"
                    return@launch
                }
                val extensionRepo = ExtensionRepo(
                    baseUrl = baseUrl.trimEnd('/'),
                    name = meta.name ?: baseUrl,
                    shortName = meta.shortName,
                    website = meta.website ?: "",
                    signingKeyFingerprint = meta.signingKeyFingerprint ?: "",
                )
                val result = repo.add(extensionRepo)
                if (result.isFailure) {
                    _error.value = result.exceptionOrNull()?.message ?: "Failed to add repo"
                } else {
                    _repos.value = repo.getAll()
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to add repo"
            } finally {
                _loading.value = false
            }
        }
    }

    fun deleteRepo(baseUrl: String) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                repo.remove(baseUrl)
                _repos.value = repo.getAll()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to delete repo"
            } finally {
                _loading.value = false
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _repos.value = repo.refresh()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to refresh repos"
            } finally {
                _loading.value = false
            }
        }
    }
}
