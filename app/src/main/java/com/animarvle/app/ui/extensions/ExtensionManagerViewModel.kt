package com.animarvle.app.ui.extensions

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animarvle.app.extensions.AvailableExtension
import com.animarvle.app.extensions.ExtensionDiscovery
import com.animarvle.app.extensions.ExtensionEngine
import com.animarvle.app.extensions.ExtensionFeedRepository
import com.animarvle.app.extensions.ExtensionInstaller
import com.animarvle.app.extensions.ExtensionPackage
import com.animarvle.app.extensions.ExtensionRepoRepository
import com.animarvle.app.extensions.MediaCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ExtensionManagerUiState(
    val isLoading: Boolean = false,
    val mediaCategory: MediaCategory = MediaCategory.ANIME,
    val isShowingInstalled: Boolean = true,
    val selectedEngine: ExtensionEngine = ExtensionEngine.ALL,
    val searchQuery: String = "",
    val selectedLanguage: String = "ALL",
    val items: List<ExtensionItemUiModel> = emptyList(),
    val pendingUpdateItems: List<ExtensionItemUiModel> = emptyList(),
    val downloadingPkgs: Set<String> = emptySet(),
    val message: String? = null,
)

class ExtensionManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val repoRepository = ExtensionRepoRepository(application)
    private val feedRepository = ExtensionFeedRepository(repoRepository)
    val installer = ExtensionInstaller(application)

    private val _uiState = MutableStateFlow(ExtensionManagerUiState(isLoading = true, isShowingInstalled = true))
    val uiState: StateFlow<ExtensionManagerUiState> = _uiState.asStateFlow()

    private val _eventFlow = MutableSharedFlow<ExtensionEvent>()
    val eventFlow: SharedFlow<ExtensionEvent> = _eventFlow.asSharedFlow()

    sealed class ExtensionEvent {
        data class ShowToast(val text: String) : ExtensionEvent()
        object RequestInstallPermission : ExtensionEvent()
    }

    private var cachedAvailable: List<AvailableExtension> = emptyList()
    private var cachedInstalled: List<ExtensionPackage> = emptyList()

    init {
        com.animarvle.app.extensions.ExtensionInstallReceiver.onPackageInstalled = {
            loadExtensions()
        }
        loadExtensions()
    }

    override fun onCleared() {
        super.onCleared()
        com.animarvle.app.extensions.ExtensionInstallReceiver.onPackageInstalled = null
    }

    fun loadExtensions() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            // 1. Scan installed immediately
            cachedInstalled = withContext(Dispatchers.IO) {
                ExtensionDiscovery.discover(getApplication())
            }
            applyFilters()

            // 2. Fetch from repos safely
            try {
                cachedAvailable = feedRepository.fetchAllAvailable()
            } catch (e: Throwable) {
                android.util.Log.e("ExtensionManagerVM", "Failed to fetch extension repos", e)
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
                applyFilters()
            }
        }
    }

    fun setMediaCategory(category: MediaCategory) {
        if (_uiState.value.mediaCategory == category) return
        _uiState.value = _uiState.value.copy(mediaCategory = category)
        applyFilters()
    }

    fun setShowInstalled(showInstalled: Boolean) {
        if (_uiState.value.isShowingInstalled == showInstalled) return
        _uiState.value = _uiState.value.copy(isShowingInstalled = showInstalled)
        applyFilters()
    }

    fun setEngineFilter(engine: ExtensionEngine) {
        if (_uiState.value.selectedEngine == engine) return
        _uiState.value = _uiState.value.copy(selectedEngine = engine)
        applyFilters()
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        applyFilters()
    }

    fun setLanguageFilter(lang: String) {
        _uiState.value = _uiState.value.copy(selectedLanguage = lang)
        applyFilters()
    }

    fun getAvailableLanguages(): List<String> {
        val langs = mutableSetOf("ALL")
        cachedAvailable.forEach { if (it.lang.isNotBlank()) langs.add(it.lang.uppercase()) }
        cachedInstalled.forEach { langs.add("ENGLISH") }
        return langs.toList().sorted()
    }

    fun updateAll() {
        val updates = _uiState.value.pendingUpdateItems
        if (updates.isEmpty()) {
            viewModelScope.launch {
                _eventFlow.emit(ExtensionEvent.ShowToast("No updates pending"))
            }
            return
        }
        updates.forEach { model ->
            installExtension(model.item)
        }
    }

    fun installExtension(ext: AvailableExtension) {
        if (ext.apkUrl.isNullOrBlank()) {
            viewModelScope.launch {
                _eventFlow.emit(ExtensionEvent.ShowToast("Direct script extensions are registered: ${ext.name}"))
            }
            return
        }

        viewModelScope.launch {
            val downloading = _uiState.value.downloadingPkgs + ext.pkg
            _uiState.value = _uiState.value.copy(downloadingPkgs = downloading)
            applyFilters()

            when (val res = installer.downloadAndInstallApk(ext.apkUrl, ext.pkg)) {
                is ExtensionInstaller.InstallResult.PermissionRequired -> {
                    _eventFlow.emit(ExtensionEvent.RequestInstallPermission)
                }
                is ExtensionInstaller.InstallResult.Error -> {
                    _eventFlow.emit(ExtensionEvent.ShowToast("Error: ${res.message}"))
                }
                is ExtensionInstaller.InstallResult.SuccessLaunchingInstaller -> {
                    _eventFlow.emit(ExtensionEvent.ShowToast("Installing ${ext.name}..."))
                }
            }

            val updatedDownloading = _uiState.value.downloadingPkgs - ext.pkg
            _uiState.value = _uiState.value.copy(downloadingPkgs = updatedDownloading)
            applyFilters()
        }
    }

    fun uninstallExtension(ext: AvailableExtension) {
        installer.requestUninstall(ext.pkg)
    }

    fun openAppSettings(pkg: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$pkg")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            getApplication<Application>().startActivity(intent)
        } catch (e: Exception) {
            viewModelScope.launch {
                _eventFlow.emit(ExtensionEvent.ShowToast("Unable to open settings for $pkg"))
            }
        }
    }

    private fun isNewerVersion(available: String, installed: String): Boolean {
        val cleanAvail = available.removePrefix("v").removePrefix("V").trim()
        val cleanInst = installed.removePrefix("v").removePrefix("V").trim()
        if (cleanAvail.isBlank() || cleanInst.isBlank()) return false
        if (cleanAvail == cleanInst) return false
        val pAvail = cleanAvail.split('.').mapNotNull { it.toIntOrNull() }
        val pInst = cleanInst.split('.').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(pAvail.size, pInst.size)) {
            val a = pAvail.getOrElse(i) { 0 }
            val b = pInst.getOrElse(i) { 0 }
            if (a > b) return true
            if (a < b) return false
        }
        return cleanAvail != cleanInst
    }

    private fun applyFilters() {
        val state = _uiState.value
        val installedPkgNames = cachedInstalled.map { it.packageName }.toSet()
        val availableByPkg = cachedAvailable.associateBy { it.pkg }

        // Find pending updates across installed packages
        val pendingUpdatesList = mutableListOf<ExtensionItemUiModel>()
        if (state.isShowingInstalled) {
            for (pkg in cachedInstalled) {
                val avail = availableByPkg[pkg.packageName]
                if (avail != null && !avail.apkUrl.isNullOrBlank()) {
                    val hasUpdate = (avail.versionCode > 0 && avail.versionCode > pkg.versionCode) ||
                        (avail.versionName.isNotBlank() && isNewerVersion(avail.versionName, pkg.versionName))
                    if (hasUpdate) {
                        val updateModel = AvailableExtension(
                            id = pkg.packageName,
                            name = pkg.label,
                            pkg = pkg.packageName,
                            versionName = pkg.versionName,
                            versionCode = avail.versionCode,
                            lang = avail.lang.ifBlank { "ENGLISH" },
                            isNsfw = pkg.isNsfw || avail.isNsfw,
                            iconUrl = avail.iconUrl,
                            apkUrl = avail.apkUrl,
                            engine = avail.engine,
                            isMovieOrAnime = pkg.isAnime,
                        )
                        pendingUpdatesList.add(
                            ExtensionItemUiModel(
                                item = updateModel,
                                isInstalled = true,
                                isDownloading = state.downloadingPkgs.contains(pkg.packageName),
                                hasUpdate = true,
                                updateVersion = avail.versionName,
                            )
                        )
                    }
                }
            }
        }

        // Build list based on Installed vs Available toggle
        val candidates: List<AvailableExtension> = if (state.isShowingInstalled) {
            cachedInstalled.map { pkg ->
                val avail = availableByPkg[pkg.packageName]
                AvailableExtension(
                    id = pkg.packageName,
                    name = pkg.label,
                    pkg = pkg.packageName,
                    versionName = pkg.versionName,
                    versionCode = pkg.versionCode,
                    lang = avail?.lang ?: "ENGLISH",
                    isNsfw = pkg.isNsfw,
                    iconUrl = avail?.iconUrl,
                    apkUrl = avail?.apkUrl,
                    engine = avail?.engine ?: ExtensionEngine.ANIYOMI,
                    isMovieOrAnime = pkg.isAnime,
                )
            }
        } else {
            // For available list, include extensions from repos that are not already installed
            cachedAvailable.filter { !installedPkgNames.contains(it.pkg) }
        }

        val filtered = candidates.filter { item ->
            // Category filter: Anime vs Movies
            val matchCategory = when (state.mediaCategory) {
                MediaCategory.ANIME -> item.isMovieOrAnime && !item.name.contains("movie", true) && !item.pkg.contains("movie", true)
                MediaCategory.MOVIES -> item.name.contains("movie", true) || !item.isMovieOrAnime || item.pkg.contains("movie", true)
            }

            // Engine filter
            val matchEngine = when (state.selectedEngine) {
                ExtensionEngine.ALL -> true
                ExtensionEngine.ANIYOMI -> item.engine == ExtensionEngine.ANIYOMI || item.pkg.contains("aniyomi", true)
                ExtensionEngine.MANGAYOMI -> item.engine == ExtensionEngine.MANGAYOMI || item.pkg.contains("mangayomi", true)
                ExtensionEngine.CLOUDSTREAM -> item.engine == ExtensionEngine.CLOUDSTREAM || item.pkg.contains("cloudstream", true)
                ExtensionEngine.SORA -> item.engine == ExtensionEngine.SORA || item.pkg.contains("sora", true)
            }

            // Language filter
            val matchLanguage = if (state.selectedLanguage == "ALL") {
                true
            } else {
                item.lang.equals(state.selectedLanguage, ignoreCase = true)
            }

            // Search query
            val matchSearch = if (state.searchQuery.isBlank()) {
                true
            } else {
                item.name.contains(state.searchQuery, ignoreCase = true) ||
                    item.lang.contains(state.searchQuery, ignoreCase = true) ||
                    item.pkg.contains(state.searchQuery, ignoreCase = true)
            }

            matchCategory && matchEngine && matchLanguage && matchSearch
        }

        val uiModels = filtered.map { item ->
            val isInst = installedPkgNames.contains(item.pkg)
            val isDown = state.downloadingPkgs.contains(item.pkg)
            val avail = availableByPkg[item.pkg]
            val hasUp = isInst && avail != null && (
                (avail.versionCode > 0 && avail.versionCode > item.versionCode) ||
                (avail.versionName.isNotBlank() && isNewerVersion(avail.versionName, item.versionName))
            )
            ExtensionItemUiModel(
                item = item,
                isInstalled = isInst,
                isDownloading = isDown,
                hasUpdate = hasUp,
                updateVersion = avail?.versionName.orEmpty(),
            )
        }

        _uiState.value = state.copy(
            items = uiModels,
            pendingUpdateItems = pendingUpdatesList,
        )
    }
}
