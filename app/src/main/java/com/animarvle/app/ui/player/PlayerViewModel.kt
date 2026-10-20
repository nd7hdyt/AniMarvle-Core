package com.animarvle.app.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.animarvle.app.AnimarvleApp
import com.animarvle.domain.DerivedUi
import com.animarvle.domain.MatchResult
import com.animarvle.domain.MetaQuery
import com.animarvle.domain.PlaybackSelection
import com.animarvle.domain.ResolveEvent
import com.animarvle.domain.SourceMatcher
import com.animarvle.domain.StreamResolver
import com.animarvle.sourceapi.Audio
import com.animarvle.sourceapi.MediaSource
import com.animarvle.sourceapi.MediaType
import com.animarvle.sourceapi.ResolvedMedia
import com.animarvle.sourceapi.ServerOption
import com.animarvle.sourceapi.SkipTimes
import com.animarvle.sourceapi.SourceEpisode
import com.animarvle.sourceapi.SourceMedia
import com.animarvle.sourceapi.Stream
import com.animarvle.sourceapi.StreamKind
import com.animarvle.sourceapi.SubtitleTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.animarvle.app.extensions.TachiyomiAnimeSource

enum class PlayerPhase { RESOLVING, READY, FAILED }

data class PlayerUiState(
    val title: String,
    val phase: PlayerPhase = PlayerPhase.RESOLVING,
    val audio: Audio = Audio.SUB,
    val audioLanguage: String = "English",
    val audioTabs: DerivedUi.AudioTabs = DerivedUi.AudioTabs(subEnabled = false, dubEnabled = false, hidden = true),
    val servers: List<ServerOption> = emptyList(),
    val selectedServer: String? = null,
    val quality: List<DerivedUi.QualityItem>? = null,
    val selectedQualityId: String = DerivedUi.AUTO_ID,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val selectedSubtitle: SubtitleTrack? = null,
    val skipTimes: SkipTimes? = null,
    val speed: Float = 1f,
    val episodes: List<SourceEpisode> = emptyList(),
    val currentEpisodeIndex: Int = 0,
    val isMovie: Boolean = false,
    val movieYear: Int? = null,
    val sourceName: String = "",
    val matchedTitle: String? = null,
    val message: String? = null,
    val error: String? = null,
    val tried: List<String> = emptyList(),
) {
    val hasQualitySheet: Boolean get() = quality != null && quality.size > 1
    val subtitlesAvailable: Boolean get() = subtitles.isNotEmpty()
    val currentEpisode: SourceEpisode? get() = episodes.getOrNull(currentEpisodeIndex)
    val currentEpisodeNumber: Int get() = currentEpisode?.number?.toInt() ?: 1
}

/** One-shot instructions for the ExoPlayer owned by the Fragment. */
sealed interface PlayerCommand {
    data class Prepare(
        val streams: List<Stream>,
        val startPositionMs: Long,
        val subtitles: List<SubtitleTrack>,
        val selectedSubtitle: SubtitleTrack?,
        val headers: Map<String, String> = emptyMap(),
    ) : PlayerCommand

    data class SetSpeed(val speed: Float) : PlayerCommand

    data class SetSubtitle(val track: SubtitleTrack?) : PlayerCommand
}

/**
 * Drives the resolve pipeline for one title (ARCH §7.3) and exposes everything the
 * player UI needs. The ExoPlayer instance lives in the Fragment; this ViewModel emits
 * [PlayerCommand]s and never touches Android media types.
 */
class PlayerViewModel(
    private val sources: List<MediaSource>,
    private val matcher: SourceMatcher,
    private val resolver: StreamResolver,
    private val metaQuery: MetaQuery,
    private val initialSource: MediaSource? = null,
    private val initialSourceMedia: SourceMedia? = null,
    private val mediaTitle: String,
    initialAudio: Audio,
    private val initialEpisodeNumber: Int = 1,
    private val defaultQuality: String = "auto",
    initialCustomQuery: String? = null,
    val isOffline: Boolean = false,
) : ViewModel() {

    private val sourceMediaCache = mutableMapOf<Long, SourceMedia>()
    private var currentCustomQuery: String? = initialCustomQuery

    private var selectedSourceId: Long? = initialSource?.id ?: sources.firstOrNull()?.id
    private var selectedHosterKey: String? = null
    private var isManualServerSelection: Boolean = false
    private val attemptedSourceIds = mutableSetOf<Long>()

    private val activeSource: MediaSource?
        get() = sources.firstOrNull { it.id == selectedSourceId }
            ?: initialSource
            ?: sources.firstOrNull()

    private fun buildServerOptions(activeSrc: MediaSource?, activeHosters: List<ServerOption>): Pair<List<ServerOption>, String?> {
        val list = mutableListOf<ServerOption>()
        var selectedKey: String? = null

        if (activeHosters.isNotEmpty()) {
            activeHosters.take(3).forEachIndexed { index, h ->
                val srcId = activeSrc?.id ?: 0L
                val k = "hoster:$srcId:${h.key}"
                val cleanName = when {
                    h.label.contains("spiral", ignoreCase = true) -> "Spiral"
                    h.label.contains("turbo", ignoreCase = true) -> "Turbo"
                    h.label.contains("fastx", ignoreCase = true) || h.label.contains("fast", ignoreCase = true) -> "FastX"
                    index == 0 -> "Spiral"
                    index == 1 -> "Turbo"
                    else -> "FastX"
                }
                list.add(ServerOption(key = k, label = cleanName, hint = h.hint, audioLanguage = h.audioLanguage))
                if (selectedHosterKey == h.key || (selectedHosterKey == null && selectedKey == null)) {
                    selectedKey = k
                }
            }
        } else {
            // Provide clean unified servers mapped to top candidate sources
            val topSources = sources.take(3)
            topSources.forEachIndexed { index, src ->
                val k = "source:${src.id}"
                val cleanName = when (index) {
                    0 -> "Spiral"
                    1 -> "Turbo"
                    else -> "FastX"
                }
                list.add(ServerOption(key = k, label = cleanName, hint = src.name, audioLanguage = src.language.takeIf { it != "all" && it != "en" }))
                if (src.id == activeSrc?.id && selectedKey == null) {
                    selectedKey = k
                }
            }
        }

        if (selectedKey == null && list.isNotEmpty()) {
            selectedKey = list.first().key
        }

        return list to selectedKey
    }

    private val initialServerInfo = buildServerOptions(activeSource, emptyList())

    private val totalEpisodes: Int = metaQuery.episodeCount?.takeIf { it > 0 }
        ?: initialSourceMedia?.episodeCount?.takeIf { it > 0 }
        ?: 1

    private val isDonghua = AnimarvleApp.isDonghua(mediaTitle) || AnimarvleApp.isDonghua(metaQuery.titles.english)
    private val defaultOriginalLanguage = if (isDonghua) "Chinese" else "Japanese"

    private val _state = MutableStateFlow(
        PlayerUiState(
            title = mediaTitle,
            phase = PlayerPhase.RESOLVING,
            audio = initialAudio,
            audioLanguage = if (metaQuery.type == MediaType.MOVIE) "English" else if (initialAudio == Audio.DUB) "English" else defaultOriginalLanguage,
            audioTabs = DerivedUi.AudioTabs(subEnabled = true, dubEnabled = true, hidden = false),
            servers = initialServerInfo.first,
            selectedServer = initialServerInfo.second,
            sourceName = initialSource?.name ?: sources.firstOrNull()?.name.orEmpty(),
            episodes = (1..totalEpisodes).map {
                SourceEpisode(number = it.toFloat(), url = "", title = "Episode $it")
            },
            currentEpisodeIndex = (initialEpisodeNumber - 1).coerceIn(0, (totalEpisodes - 1).coerceAtLeast(0)),
            isMovie = metaQuery.type == MediaType.MOVIE,
            movieYear = metaQuery.year,
        ),
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    // Channel, not SharedFlow: a fast source can resolve BEFORE the Fragment starts
    // collecting, and replay=0 would drop the Prepare command. A Channel buffers it.
    private val _commands = Channel<PlayerCommand>(Channel.BUFFERED)
    val commands: Flow<PlayerCommand> = _commands.receiveAsFlow()

    private var resolved: ResolvedMedia? = null
    private var job: Job? = null

    init {
        initialSourceMedia?.let {
            val src = initialSource ?: sources.firstOrNull()
            if (src != null) sourceMediaCache[src.id] = it
        }
        if (!isOffline) {
            loadAndResolve()
        } else {
            _state.update {
                it.copy(
                    phase = PlayerPhase.READY,
                    sourceName = "Offline Download",
                )
            }
        }
    }

    fun setOfflineEpisodes(episodes: List<SourceEpisode>, currentIndex: Int) {
        _state.update {
            it.copy(
                episodes = episodes,
                currentEpisodeIndex = currentIndex.coerceIn(0, (episodes.size - 1).coerceAtLeast(0)),
                phase = PlayerPhase.READY,
            )
        }
    }

    fun playNextEpisode() {
        selectEpisode(_state.value.currentEpisodeIndex + 1)
    }

    fun playPreviousEpisode() {
        selectEpisode(_state.value.currentEpisodeIndex - 1)
    }

    /** Switches to another episode from the grid and re-resolves (PROMPT §7.7). */
    fun selectEpisode(index: Int) {
        if (index !in _state.value.episodes.indices) return
        if (index == _state.value.currentEpisodeIndex && _state.value.phase == PlayerPhase.READY) return
        _state.update { it.copy(currentEpisodeIndex = index) }
        if (!isOffline) {
            attemptedSourceIds.clear()
            loadAndResolve()
        } else {
            val ep = _state.value.episodes.getOrNull(index) ?: return
            if (ep.url.isNotBlank()) {
                val kind = if (ep.url.contains(".mp4", ignoreCase = true)) StreamKind.PROGRESSIVE else StreamKind.HLS
                _commands.trySend(
                    PlayerCommand.Prepare(
                        streams = listOf(Stream(ep.url, kind)),
                        startPositionMs = 0L,
                        subtitles = emptyList(),
                        selectedSubtitle = null,
                        headers = emptyMap(),
                    ),
                )
            }
        }
    }

    /** Selects episode by its episode number (1, 2, ...), mapping to correct list index. */
    fun selectEpisodeByNumber(epNumber: Int) {
        val index = _state.value.episodes.indexOfFirst { it.number.toInt() == epNumber }
        if (index >= 0) {
            selectEpisode(index)
        }
    }

    fun selectServer(key: String) {
        if (isOffline) return
        if (key == _state.value.selectedServer && _state.value.phase == PlayerPhase.READY) return
        isManualServerSelection = true

        val targetSourceId: Long?
        val targetHosterKey: String?

        when {
            key.startsWith("hoster:") -> {
                val rest = key.removePrefix("hoster:")
                val colonIdx = rest.indexOf(':')
                if (colonIdx != -1) {
                    targetSourceId = rest.substring(0, colonIdx).toLongOrNull()
                    targetHosterKey = rest.substring(colonIdx + 1)
                } else {
                    targetSourceId = rest.toLongOrNull()
                    targetHosterKey = null
                }
            }
            key.startsWith("source:") -> {
                val rest = key.removePrefix("source:")
                val colonIdx = rest.indexOf(':')
                if (colonIdx != -1) {
                    targetSourceId = rest.substring(0, colonIdx).toLongOrNull()
                    targetHosterKey = rest.substring(colonIdx + 1).takeIf { it.isNotBlank() }
                } else {
                    targetSourceId = rest.toLongOrNull()
                    targetHosterKey = null
                }
            }
            else -> {
                val matched = sources.firstOrNull { it.id.toString() == key }
                if (matched != null) {
                    targetSourceId = matched.id
                    targetHosterKey = null
                } else {
                    targetSourceId = selectedSourceId
                    targetHosterKey = key
                }
            }
        }

        if (targetSourceId != null) {
            selectedSourceId = targetSourceId
        }
        selectedHosterKey = targetHosterKey

        val currentSrc = activeSource
        _state.update {
            it.copy(
                selectedServer = key,
                sourceName = currentSrc?.name ?: it.sourceName,
            )
        }
        loadAndResolve()
    }

    fun selectAudio(audio: Audio) {
        if (audio == _state.value.audio && _state.value.phase == PlayerPhase.READY) return
        _state.update { it.copy(audio = audio) }
        selectedHosterKey = null
        // If switching between Sub and Dub, clear cache for the active source to allow
        // re-matching the specific (Dub) or (Sub) variant from the catalog
        selectedSourceId?.let { sourceMediaCache.remove(it) }
        attemptedSourceIds.clear()
        loadAndResolve()
    }

    fun setAudioLanguageName(lang: String) {
        _state.update { it.copy(audioLanguage = lang) }
    }

    fun selectAudioLanguage(lang: String) {
        val current = _state.value
        val isAnime = !current.isMovie && metaQuery.type != MediaType.MOVIE

        if (isAnime) {
            val isOriginal = if (isDonghua) {
                lang.equals("Chinese", ignoreCase = true) || lang.equals("Mandarin", ignoreCase = true) || lang.equals("Sub", ignoreCase = true) || lang.equals("Original", ignoreCase = true)
            } else {
                lang.equals("Japanese", ignoreCase = true) || lang.equals("Sub", ignoreCase = true) || lang.equals("Original", ignoreCase = true)
            }
            val targetAudio = if (isOriginal) {
                Audio.SUB
            } else {
                Audio.DUB
            }
            if (targetAudio != current.audio || !current.audioLanguage.equals(lang, ignoreCase = true)) {
                _state.update { it.copy(audioLanguage = lang, audio = targetAudio) }
                selectedHosterKey = null
                selectedSourceId?.let { sourceMediaCache.remove(it) }
                attemptedSourceIds.clear()
                loadAndResolve()
            }
            return
        }

        if (current.audioLanguage.equals(lang, ignoreCase = true) && current.phase == PlayerPhase.READY) return

        _state.update { it.copy(audioLanguage = lang) }

        // 1. Try to find a server matching this language
        val matchingServer = current.servers.firstOrNull { s ->
            val al = s.audioLanguage?.lowercase().orEmpty()
            val l = s.label.lowercase()
            val h = s.hint?.lowercase().orEmpty()
            val target = lang.lowercase()
            al.equals(target) || l.contains(target) || h.contains(target)
        }

        if (matchingServer != null && matchingServer.key != current.selectedServer) {
            selectServer(matchingServer.key)
            return
        }

        // 2. Try to find a source matching this language (e.g. moviebox, kisskh, etc.)
        val matchingSource = sources.firstOrNull { src ->
            val n = src.name.lowercase()
            val l = src.language.lowercase()
            val target = lang.lowercase()
            n.contains(target) || l.contains(target)
        }

        if (matchingSource != null && matchingSource.id != selectedSourceId) {
            selectSource(matchingSource.id)
            return
        }

        // 3. Fallback: switch audio category (SUB for Japanese/Chinese/Original, DUB for other dub languages)
        val targetAudio = if (lang.equals("Japanese", ignoreCase = true) || lang.equals("Chinese", ignoreCase = true) || lang.equals("Mandarin", ignoreCase = true) || lang.equals("Original", ignoreCase = true) || lang.equals("Sub", ignoreCase = true)) {
            Audio.SUB
        } else {
            Audio.DUB
        }
        if (targetAudio != current.audio) {
            selectAudio(targetAudio)
        }
    }

    val activeSourceId: Long? get() = selectedSourceId

    fun selectSource(sourceId: Long) {
        if (sourceId == selectedSourceId && _state.value.phase == PlayerPhase.READY) return
        selectedSourceId = sourceId
        selectedHosterKey = null
        isManualServerSelection = true
        attemptedSourceIds.clear()
        val currentSrc = activeSource
        _state.update {
            it.copy(
                sourceName = currentSrc?.name ?: it.sourceName,
                matchedTitle = null,
            )
        }
        loadAndResolve()
    }

    fun updateCustomQuery(customQuery: String?) {
        val trimmed = customQuery?.trim()?.takeIf { it.isNotBlank() }
        currentCustomQuery = trimmed
        // Invalidate cache for the active source to force re-matching with the custom title
        selectedSourceId?.let { sourceMediaCache.remove(it) }
        attemptedSourceIds.clear()
        loadAndResolve()
    }

    fun retry() {
        if (isOffline) return
        attemptedSourceIds.clear()
        isManualServerSelection = false
        loadAndResolve()
    }

    private fun cleanErrorMessage(sourceName: String, t: Throwable): String {
        val raw = t.message.orEmpty()
        return when {
            raw.contains("cancel", ignoreCase = true) ->
                "Loading stream..."
            raw.contains("AA_CRYPTO_MISSING", ignoreCase = true) ->
                "$sourceName is temporarily unavailable (encryption key expired). Try another server below."
            raw.contains("Unexpected JSON token", ignoreCase = true) ->
                "$sourceName returned an unexpected response. Try another server below."
            raw.contains("403", ignoreCase = true) || raw.contains("Cloudflare", ignoreCase = true) ->
                "$sourceName is blocked by Cloudflare. Try another server below."
            raw.contains("timeout", ignoreCase = true) || raw.contains("timed out", ignoreCase = true) ->
                "$sourceName timed out. Try another server below."
            raw.isNotBlank() && raw.length <= 100 ->
                "$sourceName error: $raw"
            else ->
                "All servers for this episode are currently unavailable."
        }
    }

    private fun handleSourceFailure(failedSource: MediaSource, error: Throwable) {
        if (error is CancellationException) return
        val cleanMsg = cleanErrorMessage(failedSource.name, error)
        android.util.Log.w("PlayerViewModel", "${failedSource.name} failed: ${error.message}")

        if (!isManualServerSelection) {
            val unattempted = sources.filter { !attemptedSourceIds.contains(it.id) }
            val nextSource = if (_state.value.isMovie || metaQuery.type == MediaType.MOVIE) {
                // For movies and TMDB series: strictly preserve the movie engine queue
                // (4K HDHub -> HexaWatch -> KissKH -> ...) and never divert to anime dub scrapers.
                unattempted.firstOrNull()
            } else if (_state.value.audio == Audio.DUB) {
                // When Dub is requested for anime, try dub-specialized anime sources first
                unattempted.firstOrNull { it.capabilities.dub || it.name.contains("dub", ignoreCase = true) || it.name.contains("wco", ignoreCase = true) }
                    ?: unattempted.firstOrNull()
            } else {
                unattempted.firstOrNull()
            }
            if (nextSource != null) {
                android.util.Log.i("PlayerViewModel", "Falling back from ${failedSource.name} to ${nextSource.name}...")
                selectedSourceId = nextSource.id
                selectedHosterKey = null
                loadAndResolve()
                return
            }
        }

        val serverInfo = buildServerOptions(failedSource, emptyList())
        _state.update {
            it.copy(
                phase = PlayerPhase.FAILED,
                error = cleanMsg,
                servers = serverInfo.first,
                selectedServer = serverInfo.second,
            )
        }
    }

    /** Loads the episode's options (audio tabs + servers), then runs the resolver. */
    fun loadAndResolve() {
        job?.cancel()
        job = viewModelScope.launch {
            if (sources.isEmpty()) {
                _state.update {
                    it.copy(
                        phase = PlayerPhase.FAILED,
                        error = "No extensions installed. Please install an extension.",
                    )
                }
                return@launch
            }

            val source = activeSource ?: run {
                _state.update {
                    it.copy(
                        phase = PlayerPhase.FAILED,
                        error = "All servers for this episode are currently unavailable.",
                        servers = buildServerOptions(null, emptyList()).first,
                    )
                }
                return@launch
            }

            attemptedSourceIds.add(source.id)

            val currentServerInfo = buildServerOptions(source, emptyList())
            _state.update {
                it.copy(
                    phase = PlayerPhase.RESOLVING,
                    sourceName = source.name,
                    servers = currentServerInfo.first,
                    selectedServer = currentServerInfo.second,
                    error = null,
                    message = null,
                )
            }

            // 1. Get SourceMedia for this source (with 15s timeout)
            val baseQuery = metaQuery.copy(requestedAudio = _state.value.audio)
            val queryToUse = currentCustomQuery?.let { customQ ->
                baseQuery.copy(
                    titles = baseQuery.titles.copy(english = customQ),
                    synonyms = listOf(customQ) + baseQuery.synonyms,
                )
            } ?: baseQuery

            val targetMedia: SourceMedia? = sourceMediaCache[source.id] ?: run {
                val match = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(15000L) {
                        matcher.match(queryToUse, listOf(source))
                    }
                }
                val matched = (match as? MatchResult.Matched)?.media
                if (matched != null) {
                    android.util.Log.i("PlayerViewModel", "Matched '${matched.title}' on ${source.name}")
                    sourceMediaCache[source.id] = matched
                }
                matched
            }

            if (!isActive) return@launch

            if (targetMedia == null) {
                _state.update { it.copy(matchedTitle = null) }
                handleSourceFailure(source, Exception("Title not found on ${source.name}"))
                return@launch
            }

            _state.update { it.copy(matchedTitle = targetMedia.title) }

            // 2. Fetch episodes list from source (with 25s timeout)
            val fetchedEpisodesResult = withContext(Dispatchers.IO) {
                withTimeoutOrNull(25000L) {
                    runCatching { source.episodes(targetMedia) }
                }
            }
            if (!isActive) return@launch
            val err = fetchedEpisodesResult?.exceptionOrNull()
            if (err is CancellationException) return@launch
            val fetchedEpisodes = fetchedEpisodesResult?.getOrNull().orEmpty()
            if (fetchedEpisodes.isEmpty()) {
                val failureErr = err ?: Exception("No playable episodes found on ${source.name}")
                android.util.Log.w("PlayerViewModel", "Episode fetch failed on ${source.name}: ${failureErr.message}")
                handleSourceFailure(source, failureErr)
                return@launch
            }

            val fallbackExtra = mapOf(
                TachiyomiAnimeSource.KEY_ANIME_URL to targetMedia.url,
                "animeTitle" to targetMedia.title,
            )

            // Movies or single episode titles:
            val sortedFetched = fetchedEpisodes.sortedBy { it.number }
            val maxEp = maxOf(totalEpisodes, sortedFetched.lastOrNull()?.number?.toInt() ?: 1)
            val map = sortedFetched.associateBy { it.number.toInt() }

            val mergedEpisodes = if (_state.value.isMovie || fetchedEpisodes.size == 1) {
                listOf(
                    (sortedFetched.firstOrNull() ?: SourceEpisode(url = targetMedia.url, number = 1f, title = "Movie")).let { first ->
                        first.copy(
                            number = 1f,
                            extra = if (first.extra.isEmpty()) fallbackExtra else first.extra,
                        )
                    }
                )
            } else {
                // Ensure episodes are ALWAYS in strictly ascending order by number (1, 2, 3, ... maxEp)
                // so the episode list is never in jumbles regardless of what order the extension uses!
                (1..maxEp).map { epNum ->
                    map[epNum] ?: SourceEpisode(
                        number = epNum.toFloat(),
                        url = sortedFetched.getOrNull(epNum - 1)?.url ?: "",
                        title = "Episode $epNum",
                        extra = fallbackExtra,
                    )
                }
            }

            val targetEpNumber = _state.value.currentEpisodeNumber
            val newIndex = mergedEpisodes.indexOfFirst { it.number.toInt() == targetEpNumber }.takeIf { it >= 0 }
                ?: (targetEpNumber - 1).coerceIn(0, (mergedEpisodes.size - 1).coerceAtLeast(0))

            _state.update {
                it.copy(
                    episodes = mergedEpisodes,
                    currentEpisodeIndex = newIndex,
                )
            }

            val epList = _state.value.episodes
            val currentEpNumber = _state.value.currentEpisodeNumber
            val currentEp = epList.firstOrNull { it.number.toInt() == currentEpNumber && it.url.isNotBlank() }
                ?: epList.firstOrNull { it.number.toInt() == currentEpNumber }
                ?: epList.getOrNull(_state.value.currentEpisodeIndex)?.takeIf { it.url.isNotBlank() }
                ?: epList.firstOrNull { it.url.isNotBlank() }
                ?: epList.firstOrNull()
                ?: SourceEpisode(
                    number = currentEpNumber.toFloat(),
                    url = targetMedia.url,
                    title = "Episode $currentEpNumber",
                    extra = fallbackExtra,
                )

            // 3. Query episode options (with 25s timeout)
            val options = withContext(Dispatchers.IO) {
                withTimeoutOrNull(25000L) {
                    runCatching { resolver.options(source, currentEp) }.getOrNull()
                }
            }
            if (!isActive) return@launch

            val hosters = options?.byAudio?.get(_state.value.audio).orEmpty()
            val (updatedServers, updatedSelected) = buildServerOptions(source, hosters)
            val tabs = if (options != null) DerivedUi.audioTabs(options) else null

            _state.update {
                it.copy(
                    audioTabs = if (tabs != null && !tabs.hidden) tabs else it.audioTabs,
                    servers = updatedServers,
                    selectedServer = updatedSelected,
                )
            }

            resolveChain(source, currentEp, afterServer = null, resumeAt = 0L)
        }
    }

    private suspend fun resolveChain(source: MediaSource, episode: SourceEpisode, afterServer: String?, resumeAt: Long) {
        val rawServerKey = selectedHosterKey ?: _state.value.selectedServer
        val cleanServerKey = when {
            rawServerKey == null -> null
            rawServerKey.startsWith("hoster:") -> {
                val rest = rawServerKey.removePrefix("hoster:")
                val colonIdx = rest.indexOf(':')
                if (colonIdx != -1) rest.substring(colonIdx + 1) else rest
            }
            rawServerKey.startsWith("source:") -> {
                val rest = rawServerKey.removePrefix("source:")
                val colonIdx = rest.indexOf(':')
                if (colonIdx != -1) rest.substring(colonIdx + 1).takeIf { it.isNotBlank() } else null
            }
            else -> rawServerKey
        }
        val selection = PlaybackSelection(
            audio = _state.value.audio,
            serverKey = cleanServerKey,
        )
        try {
            resolver.resolve(
                source = source,
                episode = episode,
                requested = selection,
                startAfterServer = afterServer,
                resumedAtMs = resumeAt,
            ).collect { event ->
                when (event) {
                    is ResolveEvent.Resolved -> onResolved(event)
                    is ResolveEvent.Fallback -> _state.update {
                        it.copy(
                            message = "Switched to ${event.to ?: "another server"}",
                        )
                    }

                    is ResolveEvent.AudioUnavailable -> handleSourceFailure(source, Exception(event.message))
                    is ResolveEvent.AllServersFailed -> handleSourceFailure(source, Exception(event.cause ?: "Stream unavailable"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleSourceFailure(source, e)
        }
    }

    private fun onResolved(event: ResolveEvent.Resolved) {
        val media = event.media
        resolved = media
        val subs = media.subtitles
        val selectedSub = _state.value.selectedSubtitle?.takeIf { track -> subs.any { it.url == track.url } }
            ?: DerivedUi.pickSubtitle(subs, DEFAULT_SUBTITLE_LANGUAGE)
        val qualityList = DerivedUi.qualitySheet(media)
        val chosenQuality = qualityList?.firstOrNull { it.label.equals(defaultQuality, ignoreCase = true) }?.id
            ?: DerivedUi.AUTO_ID

        _state.update {
            it.copy(
                phase = PlayerPhase.READY,
                subtitles = subs,
                selectedSubtitle = selectedSub,
                skipTimes = media.skipTimes,
                quality = qualityList,
                selectedQualityId = chosenQuality,
                error = null,
            )
        }
        emitPrepare(event.resumedAtMs)
    }

    fun selectQuality(qualityId: String) {
        if (qualityId == _state.value.selectedQualityId) return
        _state.update { it.copy(selectedQualityId = qualityId) }
        val media = resolved ?: return
        val streams = streamsForQuality(qualityId, media)
        val targetUrl = streams.firstOrNull()?.url
        val currentUrl = media.streams.firstOrNull()?.url
        // Only re-prepare if switching to a physically distinct file URL
        if (streams.isNotEmpty() && targetUrl != null && targetUrl != currentUrl) {
            _commands.trySend(
                PlayerCommand.Prepare(
                    streams = streams,
                    startPositionMs = -1L,
                    subtitles = media.subtitles,
                    selectedSubtitle = _state.value.selectedSubtitle,
                    headers = media.headers,
                ),
            )
        }
    }

    fun selectSubtitle(track: SubtitleTrack?) {
        _state.update { it.copy(selectedSubtitle = track) }
        _commands.trySend(PlayerCommand.SetSubtitle(track))
    }

    fun setSpeed(speed: Float) {
        _state.update { it.copy(speed = speed) }
        _commands.trySend(PlayerCommand.SetSpeed(speed))
    }

    /** Mid-playback failure: continue the chain after the server that just died (ARCH §7.3). */
    fun onPlaybackFailed(positionMs: Long) {
        val deadServer = _state.value.selectedServer
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(phase = PlayerPhase.RESOLVING, error = null) }
            val src = activeSource ?: return@launch
            val ep = _state.value.currentEpisode ?: return@launch
            resolveChain(source = src, episode = ep, afterServer = deadServer, resumeAt = positionMs)
        }
    }

    /** The player reports the video levels it found in an adaptive master (ARCH §6). */
    fun onPlayerQualityLevels(levels: List<Stream>) {
        val media = resolved ?: return
        val sheet = DerivedUi.qualitySheet(media, levels)
        if (sheet != null) _state.update { it.copy(quality = sheet) }
    }

    private fun emitPrepare(positionMs: Long) {
        val media = resolved ?: return
        val streams = streamsForQuality(_state.value.selectedQualityId, media)
        if (streams.isEmpty()) return
        _commands.trySend(
            PlayerCommand.Prepare(
                streams = streams,
                startPositionMs = positionMs,
                subtitles = media.subtitles,
                selectedSubtitle = _state.value.selectedSubtitle,
                headers = media.headers,
            ),
        )
    }

    private fun streamsForQuality(id: String, media: ResolvedMedia): List<Stream> {
        val all = media.streams.sortedByDescending { it.height ?: 0 }
        if (id == DerivedUi.AUTO_ID) return all
        val chosen = _state.value.quality?.firstOrNull { it.id == id }?.stream ?: return all
        return listOf(chosen)
    }

    class Factory(
        private val sources: List<MediaSource>,
        private val matcher: SourceMatcher,
        private val resolver: StreamResolver,
        private val metaQuery: MetaQuery,
        private val initialSource: MediaSource? = null,
        private val initialSourceMedia: SourceMedia? = null,
        private val mediaTitle: String,
        private val audio: Audio,
        private val initialEpisodeNumber: Int = 1,
        private val defaultQuality: String = "auto",
        private val initialCustomQuery: String? = null,
        private val isOffline: Boolean = false,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlayerViewModel(
                sources,
                matcher,
                resolver,
                metaQuery,
                initialSource,
                initialSourceMedia,
                mediaTitle,
                audio,
                initialEpisodeNumber,
                defaultQuality,
                initialCustomQuery,
                isOffline,
            ) as T
    }

    companion object {
        const val DEFAULT_SUBTITLE_LANGUAGE = "en"

        fun audioFrom(raw: String?): Audio =
            runCatching { Audio.valueOf((raw ?: "SUB").uppercase()) }.getOrDefault(Audio.SUB)

        fun typeFrom(raw: String?): MediaType =
            runCatching { MediaType.valueOf((raw ?: "ANIME").uppercase()) }.getOrDefault(MediaType.ANIME)
    }
}