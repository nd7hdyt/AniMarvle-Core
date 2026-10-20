package com.animarvle.app.ui.extensions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animarvle.app.extensions.ExtensionDiscovery
import com.animarvle.app.extensions.ExtensionPackage
import com.animarvle.app.extensions.ExtensionProbe
import com.animarvle.app.extensions.MediaCategory
import com.animarvle.sourceapi.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ExtensionTestType {
    PING,
    BASIC,
    FULL,
}

data class ExtensionTestingUiState(
    val extensionType: MediaCategory = MediaCategory.ANIME,
    val testType: ExtensionTestType = ExtensionTestType.FULL,
    val searchQuery: String = "Chainsaw Man",
    val items: List<TestingExtensionUiModel> = emptyList(),
    val isTestingRunning: Boolean = false,
)

class ExtensionTestingViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ExtensionTestingUiState())
    val uiState: StateFlow<ExtensionTestingUiState> = _uiState.asStateFlow()

    private var allDiscovered: List<ExtensionPackage> = emptyList()
    private val selectedPackages = mutableSetOf<String>()
    private val testResults = mutableMapOf<String, ExtensionProbe.ProbeResult>()
    private val pipelineResults = mutableMapOf<String, ExtensionProbe.ExtensionTestPipelineResult>()

    init {
        loadExtensions()
    }

    fun loadExtensions() {
        viewModelScope.launch {
            allDiscovered = withContext(Dispatchers.IO) {
                ExtensionDiscovery.discover(getApplication())
            }
            // By default, select all discovered
            selectedPackages.addAll(allDiscovered.map { it.packageName })
            updateFilteredList()
        }
    }

    fun setExtensionType(type: MediaCategory) {
        if (_uiState.value.extensionType == type) return
        _uiState.value = _uiState.value.copy(extensionType = type)
        updateFilteredList()
    }

    fun setTestType(type: ExtensionTestType) {
        if (_uiState.value.testType == type) return
        _uiState.value = _uiState.value.copy(testType = type)
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun toggleSelection(pkgName: String) {
        if (selectedPackages.contains(pkgName)) {
            selectedPackages.remove(pkgName)
        } else {
            selectedPackages.add(pkgName)
        }
        updateFilteredList()
    }

    fun selectAll() {
        val currentVisible = _uiState.value.items.map { it.pkg.packageName }
        if (selectedPackages.containsAll(currentVisible)) {
            selectedPackages.removeAll(currentVisible.toSet())
        } else {
            selectedPackages.addAll(currentVisible)
        }
        updateFilteredList()
    }

    fun runTests() {
        val state = _uiState.value
        if (state.isTestingRunning) return

        val targets = state.items.filter { it.isSelected }
        if (targets.isEmpty()) return

        val mediaType = if (state.extensionType == MediaCategory.ANIME) MediaType.ANIME else MediaType.MOVIE

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTestingRunning = true)

            for (target in targets) {
                val pkg = target.pkg
                updateItemRunning(pkg.packageName, isRunning = true)

                if (state.testType == ExtensionTestType.PING) {
                    val pingRes = withContext(Dispatchers.IO) {
                        ExtensionProbe.probePing(pkg)
                    }
                    testResults[pkg.packageName] = pingRes
                    updateItemFinished(pkg.packageName, pingRes, null)
                } else if (state.testType == ExtensionTestType.BASIC) {
                    val basicRes = withContext(Dispatchers.IO) {
                        ExtensionProbe.probeBasic(pkg, state.searchQuery)
                    }
                    testResults[pkg.packageName] = basicRes
                    updateItemFinished(pkg.packageName, basicRes, null)
                } else {
                    // Full / 3-Stage Pipeline (Search, Episodes, Servers)
                    val pipeRes = withContext(Dispatchers.IO) {
                        ExtensionProbe.probePipelineStages(pkg, state.searchQuery, mediaType) { intermediate ->
                            viewModelScope.launch(Dispatchers.Main) {
                                updateItemPipeline(pkg.packageName, intermediate)
                            }
                        }
                    }
                    pipelineResults[pkg.packageName] = pipeRes
                    val probeRes = ExtensionProbe.ProbeResult(
                        success = pipeRes.isSuccess,
                        durationMs = pipeRes.search.durationMs + pipeRes.episodes.durationMs + pipeRes.servers.durationMs,
                        message = if (pipeRes.isSuccess) "All stages passed" else "Stage failed",
                        pipelineResult = pipeRes
                    )
                    testResults[pkg.packageName] = probeRes
                    updateItemFinished(pkg.packageName, probeRes, pipeRes)
                }
            }

            _uiState.value = _uiState.value.copy(isTestingRunning = false)
        }
    }

    fun runSingleTest(pkg: ExtensionPackage, customQuery: String? = null, onStageUpdate: ((ExtensionProbe.ExtensionTestPipelineResult) -> Unit)? = null) {
        val query = customQuery ?: _uiState.value.searchQuery
        val mediaType = if (_uiState.value.extensionType == MediaCategory.ANIME) MediaType.ANIME else MediaType.MOVIE

        viewModelScope.launch {
            updateItemRunning(pkg.packageName, isRunning = true)
            val pipeRes = withContext(Dispatchers.IO) {
                ExtensionProbe.probePipelineStages(pkg, query, mediaType) { intermediate ->
                    viewModelScope.launch(Dispatchers.Main) {
                        updateItemPipeline(pkg.packageName, intermediate)
                        onStageUpdate?.invoke(intermediate)
                    }
                }
            }
            pipelineResults[pkg.packageName] = pipeRes
            val probeRes = ExtensionProbe.ProbeResult(
                success = pipeRes.isSuccess,
                durationMs = pipeRes.search.durationMs + pipeRes.episodes.durationMs + pipeRes.servers.durationMs,
                message = if (pipeRes.isSuccess) "All stages passed" else "Stage failed",
                pipelineResult = pipeRes
            )
            testResults[pkg.packageName] = probeRes
            updateItemFinished(pkg.packageName, probeRes, pipeRes)
            onStageUpdate?.invoke(pipeRes)
        }
    }

    private fun updateItemRunning(pkgName: String, isRunning: Boolean) {
        val updated = _uiState.value.items.map { item ->
            if (item.pkg.packageName == pkgName) {
                item.copy(isRunning = isRunning)
            } else {
                item
            }
        }
        _uiState.value = _uiState.value.copy(items = updated)
    }

    private fun updateItemPipeline(
        pkgName: String,
        pipeline: ExtensionProbe.ExtensionTestPipelineResult,
    ) {
        val updated = _uiState.value.items.map { item ->
            if (item.pkg.packageName == pkgName) {
                item.copy(
                    isRunning = pipeline.isRunning,
                    pipelineResult = pipeline
                )
            } else {
                item
            }
        }
        _uiState.value = _uiState.value.copy(items = updated)
    }

    private fun updateItemFinished(
        pkgName: String,
        result: ExtensionProbe.ProbeResult,
        pipeline: ExtensionProbe.ExtensionTestPipelineResult?,
    ) {
        val updated = _uiState.value.items.map { item ->
            if (item.pkg.packageName == pkgName) {
                item.copy(
                    isRunning = false,
                    result = result,
                    pipelineResult = pipeline
                )
            } else {
                item
            }
        }
        _uiState.value = _uiState.value.copy(items = updated)
    }

    private fun updateFilteredList() {
        val state = _uiState.value
        val filtered = allDiscovered.filter { pkg ->
            when (state.extensionType) {
                MediaCategory.ANIME -> pkg.isAnime && !pkg.packageName.contains("movie", true)
                MediaCategory.MOVIES -> !pkg.isAnime || pkg.packageName.contains("movie", true)
            }
        }

        val models = filtered.map { pkg ->
            TestingExtensionUiModel(
                pkg = pkg,
                isSelected = selectedPackages.contains(pkg.packageName),
                isRunning = false,
                result = testResults[pkg.packageName],
                pipelineResult = pipelineResults[pkg.packageName],
            )
        }

        _uiState.value = state.copy(items = models)
    }
}
