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
import androidx.media3.common.C
import androidx.media3.common.Tracks

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
    val seasonNumber: Int = 1,
    val availableSeasons: List<com.animarvle.app.domain.SeasonUi> = emptyList(),
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
    private val initialSeasonNumber: Int = 1,
    private val initialSeasons: List<com.animarvle.app.domain.SeasonUi> = emptyList(),
    private val defaultQuality: String = "auto",
    initialCustomQuery: String? = null,
    val isOffline: Boolean = false,
    private val metadata: com.animarvle.app.data.MetadataRepository? = null,
    private val isMovieParam: Boolean? = null,
) : ViewModel() {

    private var currentSeasonNumber: Int = initialSeasonNumber
    private val sourceMediaCache = mutableMapOf<Long, SourceMedia>()
    private var currentCustomQuery: String? = initialCustomQuery
    private var pendingResumeAtMs: Long = 0L

    private var selectedSourceId: Long? = initialSource?.id ?: sources.firstOrNull()?.id
    private var selectedHosterKey: String? = null
    private var isManualServerSelection: Boolean = false
    private val attemptedSourceIds = mutableSetOf<Long>()

    private val activeSource: MediaSource?
        get() = sources.firstOrNull { it.id == selectedSourceId }
            ?: (if (_state.value.audio == Audio.DUB && isAnime) {
                sources.firstOrNull { it.id != 40004L && (it.capabilities.dub || it.name.contains("dub", ignoreCase = true) || it.name.contains("zoro", ignoreCase = true) || it.name.contains("hianime", ignoreCase = true) || it.name.contains("gogo", ignoreCase = true)) }
            } else null)
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
                val detectedLang = SpokenLanguage.fromRawTag(h.audioLanguage)
                    ?: SpokenLanguage.fromRawTag(h.label)
                    ?: SpokenLanguage.fromRawTag(h.hint)
                list.add(ServerOption(key = k, label = cleanName, hint = h.hint, audioLanguage = detectedLang?.displayName ?: h.audioLanguage))
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
                val detectedSourceLang = SpokenLanguage.fromRawTag(src.language)
                    ?: SpokenLanguage.fromRawTag(src.name)
                list.add(ServerOption(key = k, label = cleanName, hint = src.name, audioLanguage = detectedSourceLang?.displayName ?: src.language.takeIf { it != "all" && it != "en" }))
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
        ?: (if (initialSeasons.isNotEmpty()) initialSeasons.sumOf { it.episodeCount ?: 0 }.takeIf { it > 0 } else null)
        ?: 1

    private val isMovieResolved: Boolean = isMovieParam ?: run {
        val hasMultipleEpisodes = totalEpisodes > 1
        val hasMultipleSeasons = initialSeasons.size > 1
        val hasSeasonWithEpisodes = initialSeasons.any { (it.episodeCount ?: 0) > 1 || (it.seasonNumber ?: 1) > 1 }
        val isDefinitelySeries = hasMultipleEpisodes || hasMultipleSeasons || hasSeasonWithEpisodes
        if (isDefinitelySeries) false else (metaQuery.type == MediaType.MOVIE)
    }

    private val isAnime: Boolean get() = !metaQuery.metaId.startsWith("tmdb:") && metaQuery.type == MediaType.ANIME
    private val isDonghua = AnimarvleApp.isDonghua(mediaTitle) || AnimarvleApp.isDonghua(metaQuery.titles.english)
    private val defaultOriginalLanguage = if (isDonghua) "Chinese" else if (isAnime) "Japanese" else "English"

    private val _state = MutableStateFlow(
        PlayerUiState(
            title = mediaTitle,
            phase = PlayerPhase.RESOLVING,
            audio = initialAudio,
            audioLanguage = if (initialAudio == Audio.DUB) "English" else defaultOriginalLanguage,
            audioTabs = DerivedUi.AudioTabs(subEnabled = true, dubEnabled = true, hidden = false),
            servers = initialServerInfo.first,
            selectedServer = initialServerInfo.second,
            sourceName = initialSource?.name ?: sources.firstOrNull()?.name.orEmpty(),
            episodes = (1..totalEpisodes).map {
                SourceEpisode(number = it.toFloat(), url = "", title = "Episode $it")
            },
            currentEpisodeIndex = (initialEpisodeNumber - 1).coerceIn(0, (totalEpisodes - 1).coerceAtLeast(0)),
            isMovie = isMovieResolved,
            movieYear = metaQuery.year,
            seasonNumber = initialSeasonNumber,
            availableSeasons = initialSeasons,
        ),
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _availableAudioOptions = MutableStateFlow<List<ResolvedAudioOption>>(emptyList())
    val availableAudioOptions: StateFlow<List<ResolvedAudioOption>> = _availableAudioOptions.asStateFlow()

    private val _selectedLanguageCode = MutableStateFlow(
        if (initialAudio == Audio.DUB) "en"
        else if (isDonghua) "zh"
        else if (isAnime) "ja"
        else "en"
    )
    val selectedLanguageCode: StateFlow<String> = _selectedLanguageCode.asStateFlow()

    private val _serversForSelectedLanguage = MutableStateFlow<List<ServerStreamItem>>(emptyList())
    val serversForSelectedLanguage: StateFlow<List<ServerStreamItem>> = _serversForSelectedLanguage.asStateFlow()

    private var allEpisodeServers: List<ServerStreamItem> = emptyList()
    private var lastReportedExoTracks: Tracks? = null

    // Channel, not SharedFlow: a fast source can resolve BEFORE the Fragment starts
    // collecting, and replay=0 would drop the Prepare command. A Channel buffers it.
    private val _commands = Channel<PlayerCommand>(Channel.BUFFERED)
    val commands: Flow<PlayerCommand> = _commands.receiveAsFlow()

    private var resolved: ResolvedMedia? = null
    private var job: Job? = null

    private fun getDefaultMediaLanguage(): SpokenLanguage {
        return SpokenLanguage.defaultMediaLanguage(_state.value.isMovie, isDonghua, isAnime)
    }

    init {
        initialSourceMedia?.let {
            val src = initialSource ?: sources.firstOrNull()
            if (src != null) sourceMediaCache[src.id] = it
        }
        val initialItems = initialServerInfo.first.map { s ->
            val detected = SpokenLanguage.fromRawTag(s.audioLanguage)
                ?: SpokenLanguage.fromRawTag(s.label)
                ?: SpokenLanguage.fromRawTag(s.hint)
                ?: getDefaultMediaLanguage()
            ServerStreamItem(
                serverKey = s.key,
                serverName = s.label,
                streamUrl = "",
                languageCode = detected.code,
                languageName = detected.displayName,
                format = "hls",
            )
        }
        setEpisodeServers(initialItems)
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
        if (metadata != null) {
            enrichEpisodesAsync()
            if (initialSeasons.isEmpty() && !isOffline) {
                fetchSeasonsAsync()
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

    /** Selects season by season number, clears episode state, and triggers re-resolution. */
    fun selectSeason(seasonNumber: Int, targetEpisode: Int = 1) {
        if (_state.value.seasonNumber == seasonNumber && _state.value.episodes.isNotEmpty()) return
        currentSeasonNumber = seasonNumber
        _state.update {
            it.copy(
                seasonNumber = seasonNumber,
                currentEpisodeIndex = (targetEpisode - 1).coerceAtLeast(0),
                episodes = emptyList(),
                phase = PlayerPhase.RESOLVING,
            )
        }
        loadAndResolve()
    }

    fun setEpisodeServers(servers: List<ServerStreamItem>, preferredLangCode: String? = null) {
        allEpisodeServers = servers.map { server ->
            val detected = SpokenLanguage.fromRawTag(server.languageCode)
                ?: SpokenLanguage.fromRawTag(server.languageName)
                ?: SpokenLanguage.fromRawTag(server.serverName)
                ?: getDefaultMediaLanguage()

            server.copy(
                languageCode = detected.code,
                languageName = detected.displayName
            )
        }

        val initialLang = preferredLangCode
            ?: _selectedLanguageCode.value.takeIf { code -> allEpisodeServers.any { it.languageCode == code } }
            ?: allEpisodeServers.firstOrNull()?.languageCode
            ?: getDefaultMediaLanguage().code

        _selectedLanguageCode.value = initialLang
        updateFilteredServers(initialLang)
        rebuildAudioOptions(lastReportedExoTracks)
    }

    /**
     * Aggregates:
     * 1. Embedded ExoPlayer C.TRACK_TYPE_AUDIO tracks (takes priority for instant switching)
     * 2. Server-level language groups (for languages hosted on separate URLs)
     */
    fun rebuildAudioOptions(exoTracks: Tracks?) {
        lastReportedExoTracks = exoTracks
        val mergedByLangCode = linkedMapOf<String, ResolvedAudioOption>()

        // 1. Inspect embedded ExoPlayer audio tracks first (HLS #EXT-X-MEDIA / MKV / MP4 multi-audio)
        if (exoTracks != null) {
            for (group in exoTracks.groups) {
                if (group.type == C.TRACK_TYPE_AUDIO && group.isSupported) {
                    for (i in 0 until group.length) {
                        if (!group.isTrackSupported(i)) continue
                        val format = group.getTrackFormat(i)
                        val isUnd = SpokenLanguage.isUntaggedOrUndetermined(format.language) && SpokenLanguage.isUntaggedOrUndetermined(format.label)

                        val detected = (if (!SpokenLanguage.isUntaggedOrUndetermined(format.label)) SpokenLanguage.fromRawTag(format.label) else null)
                            ?: (if (!SpokenLanguage.isUntaggedOrUndetermined(format.language)) SpokenLanguage.fromRawTag(format.language) else null)
                            ?: SpokenLanguage.inferLanguageForUntaggedTrack(
                                isMovie = _state.value.isMovie,
                                isDonghua = isDonghua,
                                isAnime = isAnime,
                                audioPreference = _state.value.audio,
                                activeMediaLanguage = _state.value.audioLanguage.takeIf { !SpokenLanguage.isUntaggedOrUndetermined(it) }
                            )

                        val rawCode = detected.code
                        val langName = detected.displayName

                        val mapKey = if (mergedByLangCode.containsKey(rawCode)) "${rawCode}_$i" else rawCode

                        mergedByLangCode[mapKey] = ResolvedAudioOption.EmbeddedTrack(
                            languageName = if (mergedByLangCode.containsKey(rawCode)) "$langName (Track ${i + 1})" else langName,
                            languageCode = rawCode,
                            trackGroup = group.mediaTrackGroup,
                            trackIndex = i
                        )

                        if (group.isTrackSelected(i)) {
                            _selectedLanguageCode.value = rawCode
                            _state.update { it.copy(audioLanguage = langName) }
                        }
                    }
                }
            }
        }

        // 2. Merge server-provided languages (e.g., Hindi/Tamil/Telugu/English on separate servers)
        val serversByLang = allEpisodeServers.groupBy { it.languageCode }
        for ((langCode, serverList) in serversByLang) {
            val alreadyEmbedded = mergedByLangCode.values.any { it.languageCode.equals(langCode, ignoreCase = true) }
            if (!alreadyEmbedded && serverList.isNotEmpty()) {
                val first = serverList.first()
                mergedByLangCode[langCode] = ResolvedAudioOption.ServerStream(
                    languageName = first.languageName,
                    languageCode = langCode,
                    defaultServerKey = first.serverKey,
                    serverUrls = serverList
                )
            }
        }

        // 3. For Anime: ensure both Japanese (Sub) and English (Dub) are accessible in the switcher
        if (isAnime) {
            val hasJapanese = mergedByLangCode.values.any { 
                it.languageCode == "ja" || it.languageCode == "zh" || 
                it.languageName.contains("Japanese", ignoreCase = true) || 
                it.languageName.contains("Chinese", ignoreCase = true) 
            }
            val hasEnglishDub = mergedByLangCode.values.any { 
                (it.languageCode == "en" || it.languageName.contains("English", ignoreCase = true)) &&
                (it is ResolvedAudioOption.EmbeddedTrack || (it is ResolvedAudioOption.ServerStream && it.audioCategory == Audio.DUB))
            }
            val currentAudio = _state.value.audio

            if (!hasJapanese) {
                mergedByLangCode["ja"] = ResolvedAudioOption.ServerStream(
                    languageName = if (isDonghua) "Chinese (Sub)" else "Japanese (Sub)",
                    languageCode = if (isDonghua) "zh" else "ja",
                    defaultServerKey = if (currentAudio == Audio.SUB) _state.value.selectedServer.orEmpty() else "",
                    serverUrls = emptyList(),
                    audioCategory = Audio.SUB,
                )
            }
            if (!hasEnglishDub) {
                mergedByLangCode["en"] = ResolvedAudioOption.ServerStream(
                    languageName = "English (Dub)",
                    languageCode = "en",
                    defaultServerKey = if (currentAudio == Audio.DUB) _state.value.selectedServer.orEmpty() else "",
                    serverUrls = emptyList(),
                    audioCategory = Audio.DUB,
                )
            }
        } else if (mergedByLangCode.isEmpty()) {
            val defaultLang = getDefaultMediaLanguage()
            mergedByLangCode[defaultLang.code] = ResolvedAudioOption.ServerStream(
                languageName = defaultLang.displayName,
                languageCode = defaultLang.code,
                defaultServerKey = _state.value.selectedServer.orEmpty(),
                serverUrls = emptyList(),
                audioCategory = Audio.SUB,
            )
        }

        _availableAudioOptions.value = mergedByLangCode.values.toList()
        updateFilteredServers(_selectedLanguageCode.value)
    }

    fun onLanguageSelected(langCode: String, langName: String? = null) {
        _selectedLanguageCode.value = langCode
        if (langName != null) {
            _state.update { it.copy(audioLanguage = langName) }
        }
        updateFilteredServers(langCode)
    }

    private fun updateFilteredServers(langCode: String) {
        val matching = allEpisodeServers.filter { it.languageCode.equals(langCode, ignoreCase = true) }
        _serversForSelectedLanguage.value = if (matching.isNotEmpty()) matching else allEpisodeServers
    }

    fun selectServerStreamLanguage(option: ResolvedAudioOption.ServerStream, resumeAtMs: Long = 0L) {
        _selectedLanguageCode.value = option.languageCode
        _state.update { it.copy(audioLanguage = option.languageName) }
        updateFilteredServers(option.languageCode)

        val targetServerItem = option.serverUrls.firstOrNull()
            ?: allEpisodeServers.firstOrNull { it.languageCode.equals(option.languageCode, ignoreCase = true) }

        val targetServerKey = targetServerItem?.serverKey?.takeIf { it.isNotBlank() } ?: option.defaultServerKey

        val targetAudio = option.audioCategory ?: run {
            if (isAnime) {
                if (option.languageCode == "ja" || option.languageCode == "zh") Audio.SUB else Audio.DUB
            } else {
                targetServerItem?.audioCategory ?: (if (option.languageCode == "ja" || option.languageCode == "zh") Audio.SUB else Audio.DUB)
            }
        }

        if (targetAudio != _state.value.audio) {
            _state.update { it.copy(audio = targetAudio) }
            selectedHosterKey = null
            selectedSourceId = null // Reset so matcher re-evaluates all sources for the new audio category!
            attemptedSourceIds.clear()
            sourceMediaCache.clear()
            isManualServerSelection = false
            loadAndResolve(resumeAt = resumeAtMs)
            return
        }

        if (targetServerKey.isNotBlank()) {
            selectServer(targetServerKey, resumeAtMs = resumeAtMs)
        } else {
            loadAndResolve(resumeAt = resumeAtMs)
        }
    }

    fun selectServer(key: String, resumeAtMs: Long = 0L) {
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
        loadAndResolve(resumeAt = resumeAtMs)
    }

    fun selectAudio(audio: Audio, resumeAtMs: Long = 0L) {
        if (audio == _state.value.audio && _state.value.phase == PlayerPhase.READY) return
        _state.update { it.copy(audio = audio) }
        selectedHosterKey = null
        selectedSourceId = null
        attemptedSourceIds.clear()
        sourceMediaCache.clear()
        loadAndResolve(resumeAt = resumeAtMs)
    }

    fun setAudioLanguageName(lang: String) {
        _state.update { it.copy(audioLanguage = lang) }
    }

    fun selectAudioLanguage(lang: String, resumeAtMs: Long = 0L) {
        val detected = SpokenLanguage.fromRawTag(lang)
        val langCode = detected?.code ?: lang.lowercase()
        val langName = detected?.displayName ?: lang

        // Check if already in availableAudioOptions as ServerStream
        val option = _availableAudioOptions.value.firstOrNull {
            it.languageCode.equals(langCode, ignoreCase = true) ||
            it.languageName.equals(langName, ignoreCase = true)
        }
        if (option is ResolvedAudioOption.ServerStream) {
            selectServerStreamLanguage(option, resumeAtMs = resumeAtMs)
            return
        }

        val current = _state.value

        if (isAnime) {
            val isOriginal = if (isDonghua) {
                langCode == "zh" || lang.equals("Chinese", ignoreCase = true) || lang.equals("Mandarin", ignoreCase = true) || lang.equals("Sub", ignoreCase = true) || lang.equals("Original", ignoreCase = true)
            } else {
                langCode == "ja" || lang.equals("Japanese", ignoreCase = true) || lang.equals("Sub", ignoreCase = true) || lang.equals("Original", ignoreCase = true)
            }
            val targetAudio = if (isOriginal) {
                Audio.SUB
            } else {
                Audio.DUB
            }
            if (targetAudio != current.audio || !current.audioLanguage.equals(langName, ignoreCase = true)) {
                _state.update { it.copy(audioLanguage = langName, audio = targetAudio) }
                _selectedLanguageCode.value = langCode
                selectedHosterKey = null
                selectedSourceId = null
                attemptedSourceIds.clear()
                sourceMediaCache.clear()
                loadAndResolve(resumeAt = resumeAtMs)
            }
            return
        }

        if (current.audioLanguage.equals(langName, ignoreCase = true) && current.phase == PlayerPhase.READY) return

        _state.update { it.copy(audioLanguage = langName) }
        _selectedLanguageCode.value = langCode

        // 1. Try to find a server matching this language
        val matchingServer = current.servers.firstOrNull { s ->
            val al = s.audioLanguage?.lowercase().orEmpty()
            val l = s.label.lowercase()
            val h = s.hint?.lowercase().orEmpty()
            val target = langName.lowercase()
            al.equals(target) || al.equals(langCode) || l.contains(target) || h.contains(target)
        }

        if (matchingServer != null && matchingServer.key != current.selectedServer) {
            selectServer(matchingServer.key, resumeAtMs = resumeAtMs)
            return
        }

        // 2. Try to find a source matching this language (e.g. moviebox, kisskh, etc.)
        val matchingSource = sources.firstOrNull { src ->
            val n = src.name.lowercase()
            val l = src.language.lowercase()
            val target = langName.lowercase()
            n.contains(target) || l.contains(target) || l.contains(langCode)
        }

        if (matchingSource != null && matchingSource.id != selectedSourceId) {
            selectSource(matchingSource.id, resumeAtMs = resumeAtMs)
            return
        }

        // 3. Fallback: switch audio category (SUB for Japanese/Chinese/Original, DUB for other dub languages)
        val targetAudio = if (langCode == "ja" || langCode == "zh" || lang.equals("Original", ignoreCase = true) || lang.equals("Sub", ignoreCase = true)) {
            Audio.SUB
        } else {
            Audio.DUB
        }
        if (targetAudio != current.audio) {
            selectAudio(targetAudio, resumeAtMs = resumeAtMs)
        }
    }

    val activeSourceId: Long? get() = selectedSourceId

    fun selectSource(sourceId: Long, resumeAtMs: Long = 0L) {
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
        loadAndResolve(resumeAt = resumeAtMs)
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
            val nextSource = if (!isAnime) {
                // For TMDB movies and series: strictly preserve the movie engine queue
                // (4K HDHub -> HexaWatch -> KissKH -> ...) and never divert to anime dub scrapers.
                unattempted.firstOrNull()
            } else if (_state.value.audio == Audio.DUB) {
                // When Dub is requested for anime, try dub-specialized anime sources first (excluding relay which has no dub)
                unattempted.firstOrNull { it.id != 40004L && (it.capabilities.dub || it.name.contains("dub", ignoreCase = true) || it.name.contains("wco", ignoreCase = true) || it.name.contains("zoro", ignoreCase = true) || it.name.contains("hianime", ignoreCase = true) || it.name.contains("gogo", ignoreCase = true)) }
                    ?: unattempted.firstOrNull()
            } else {
                unattempted.firstOrNull()
            }
            if (nextSource != null) {
                android.util.Log.i("PlayerViewModel", "Falling back from ${failedSource.name} to ${nextSource.name}...")
                selectedSourceId = nextSource.id
                selectedHosterKey = null
                loadAndResolve(resumeAt = pendingResumeAtMs)
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
    fun loadAndResolve(resumeAt: Long = 0L) {
        pendingResumeAtMs = resumeAt
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
            val enrichedExtra = targetMedia.extra.toMutableMap().apply {
                if (currentSeasonNumber > 1 || !containsKey("season")) {
                    put("season", currentSeasonNumber.toString())
                }
                metaQuery.titles.romaji?.takeIf { it.isNotBlank() }?.let { put("romaji", it) }
                if (metaQuery.synonyms.isNotEmpty()) {
                    put("synonyms", metaQuery.synonyms.joinToString("|"))
                }
                if (isAnime) {
                    put("mediaType", "anime")
                }
            }
            val mediaWithSeason = targetMedia.copy(extra = enrichedExtra)
            val fetchedEpisodesResult = withContext(Dispatchers.IO) {
                withTimeoutOrNull(25000L) {
                    runCatching { source.episodes(mediaWithSeason) }
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
            val curSeason = _state.value.availableSeasons.firstOrNull { it.seasonNumber == currentSeasonNumber }
                ?: initialSeasons.firstOrNull { it.seasonNumber == currentSeasonNumber }
            val startEp = curSeason?.startEpisodeNumber ?: 1
            val seasonEpCount = curSeason?.episodeCount
            val maxSourceEp = sortedFetched.lastOrNull()?.number?.toInt() ?: 1
            val isContinuousSource = startEp > 1 && maxSourceEp >= startEp

            val maxEp = if (seasonEpCount != null && seasonEpCount > 0) {
                seasonEpCount
            } else {
                maxOf(totalEpisodes, maxSourceEp)
            }
            val map = sortedFetched.associateBy { it.number.toInt() }

            val mergedEpisodes = if (_state.value.isMovie) {
                listOf(
                    (sortedFetched.firstOrNull() ?: SourceEpisode(url = targetMedia.url, number = 1f, title = "Movie")).let { first ->
                        first.copy(
                            number = 1f,
                            extra = if (first.extra.isEmpty()) fallbackExtra else first.extra,
                        )
                    }
                )
            } else if (isContinuousSource && seasonEpCount != null && seasonEpCount > 0) {
                // For continuous anime where source has absolute episode numbering (e.g. One Piece Ep 62..77)
                // map each episode in the season (1..seasonEpCount) to its corresponding source absolute episode
                (1..seasonEpCount).map { epInSeason ->
                    val absEp = (startEp - 1) + epInSeason
                    val src = map[absEp] ?: sortedFetched.getOrNull(absEp - 1)
                    SourceEpisode(
                        number = epInSeason.toFloat(),
                        url = src?.url ?: sortedFetched.getOrNull(absEp - 1)?.url ?: "",
                        title = src?.title?.takeIf { it.isNotBlank() } ?: "Episode $epInSeason",
                        extra = src?.extra ?: fallbackExtra,
                    )
                }
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
            if (metadata != null) {
                enrichEpisodesAsync()
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

            val serverStreamItems = mutableListOf<ServerStreamItem>()
            if (options != null && options.byAudio.isNotEmpty()) {
                options.byAudio.forEach { (audioCat, hList) ->
                    hList.forEach { h ->
                        val detected = if (isAnime && audioCat == Audio.SUB) {
                            if (isDonghua) SpokenLanguage.CHINESE else SpokenLanguage.JAPANESE
                        } else {
                            SpokenLanguage.fromRawTag(h.audioLanguage)
                                ?: SpokenLanguage.fromRawTag(h.label)
                                ?: SpokenLanguage.fromRawTag(h.hint)
                                ?: when (audioCat) {
                                    Audio.SUB -> if (isDonghua) SpokenLanguage.CHINESE else SpokenLanguage.JAPANESE
                                    Audio.DUB -> SpokenLanguage.ENGLISH
                                    else -> SpokenLanguage.ENGLISH
                                }
                        }
                        val k = "hoster:${source.id}:${h.key}"
                        serverStreamItems.add(
                            ServerStreamItem(
                                serverKey = k,
                                serverName = h.label,
                                streamUrl = "",
                                languageCode = detected.code,
                                languageName = detected.displayName,
                                format = "hls",
                                audioCategory = audioCat,
                            )
                        )
                    }
                }
            } else {
                updatedServers.forEach { s ->
                    val detected = SpokenLanguage.fromRawTag(s.audioLanguage)
                        ?: SpokenLanguage.fromRawTag(s.label)
                        ?: SpokenLanguage.fromRawTag(s.hint)
                        ?: getDefaultMediaLanguage()
                    serverStreamItems.add(
                        ServerStreamItem(
                            serverKey = s.key,
                            serverName = s.label,
                            streamUrl = "",
                            languageCode = detected.code,
                            languageName = detected.displayName,
                            format = "hls",
                        )
                    )
                }
            }
            if (serverStreamItems.isNotEmpty()) {
                setEpisodeServers(serverStreamItems)
            }

            resolveChain(source, currentEp, afterServer = null, resumeAt = resumeAt)
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
        val currentQualityId = _state.value.selectedQualityId
        val sanitizedCurrent = DerivedUi.sanitizeQualityLabel(currentQualityId.substringBefore('#'))
        val chosenQuality = if (currentQualityId.isNotBlank() && currentQualityId != DerivedUi.AUTO_ID) {
            qualityList?.firstOrNull {
                it.id == currentQualityId ||
                it.label.equals(currentQualityId, ignoreCase = true) ||
                DerivedUi.sanitizeQualityLabel(it.label).equals(sanitizedCurrent, ignoreCase = true)
            }?.id ?: currentQualityId
        } else {
            qualityList?.firstOrNull { it.label.equals(defaultQuality, ignoreCase = true) }?.id
                ?: DerivedUi.AUTO_ID
        }

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

    fun selectQuality(qualityId: String, resumeAtMs: Long = -1L) {
        if (qualityId == _state.value.selectedQualityId) return
        _state.update { it.copy(selectedQualityId = qualityId) }
        val media = resolved ?: return
        val streams = streamsForQuality(qualityId, media)
        val targetUrl = streams.firstOrNull()?.url
        val currentUrl = media.streams.firstOrNull()?.url
        val isHttpUrl = targetUrl != null && (targetUrl.startsWith("http://", ignoreCase = true) || targetUrl.startsWith("https://", ignoreCase = true))
        // Only re-prepare if switching to a physically distinct file URL that is an actual HTTP/HTTPS stream!
        if (streams.isNotEmpty() && isHttpUrl && targetUrl != currentUrl) {
            val startMs = if (resumeAtMs >= 0L) resumeAtMs else pendingResumeAtMs
            _commands.trySend(
                PlayerCommand.Prepare(
                    streams = streams,
                    startPositionMs = startMs,
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
        selectedHosterKey = null
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
        if (sheet != null) {
            _state.update { current ->
                val currentQualityId = current.selectedQualityId
                val updatedChosenId = if (currentQualityId.isNotBlank() && currentQualityId != DerivedUi.AUTO_ID) {
                    sheet.firstOrNull { it.id == currentQualityId || it.label.equals(currentQualityId, ignoreCase = true) }?.id
                        ?: currentQualityId
                } else {
                    current.selectedQualityId
                }
                current.copy(quality = sheet, selectedQualityId = updatedChosenId)
            }
        }
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
        val sanitized = DerivedUi.sanitizeQualityLabel(id.substringBefore('#'))
        val chosen = _state.value.quality?.firstOrNull { 
            it.id == id || DerivedUi.sanitizeQualityLabel(it.label).equals(sanitized, ignoreCase = true) 
        }?.stream ?: media.streams.firstOrNull { 
            DerivedUi.sanitizeQualityLabel(it.qualityLabel).equals(sanitized, ignoreCase = true) 
        } ?: return all
        return listOf(chosen)
    }

    private fun enrichEpisodesAsync() {
        val repo = metadata ?: return
        viewModelScope.launch {
            runCatching {
                val isTmdb = metaQuery.metaId.startsWith("tmdb:")
                val metaId = metaQuery.metaId.substringAfter(":").toLongOrNull() ?: 0L
                val isAdult = com.animarvle.app.data.MetadataRepository.isAdultTitle(mediaTitle) || metaQuery.metaId.contains("hentai", ignoreCase = true)
                val metaMap = repo.episodeMetadata(
                    mediaId = metaId,
                    title = mediaTitle,
                    seasonNumber = _state.value.seasonNumber,
                    isTmdb = isTmdb,
                    isAdult = isAdult,
                )
                if (metaMap.isNotEmpty()) {
                    _state.update { current ->
                        val enriched = current.episodes.map { ep ->
                            val num = ep.number.toInt()
                            val m = metaMap[num]
                            if (m != null) {
                                val currentThumb = ep.thumbnail.orEmpty()
                                val isExistingSnapshot = currentThumb.contains(".webp", ignoreCase = true) ||
                                    currentThumb.contains("hentaimama", ignoreCase = true) ||
                                    currentThumb.contains("gdvid", ignoreCase = true) ||
                                    currentThumb.contains("yesterdaymail", ignoreCase = true)
                                val finalThumb = if (isExistingSnapshot && currentThumb.isNotBlank()) {
                                    currentThumb
                                } else {
                                    m.thumbnailUrl?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) } ?: ep.thumbnail
                                }
                                val finalTitle = m.title?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) && !it.equals("Episode $num", ignoreCase = true) }
                                    ?: ep.title
                                ep.copy(
                                    title = finalTitle,
                                    thumbnail = finalThumb,
                                    extra = if (m.overview.isNullOrBlank() || m.overview.equals("null", ignoreCase = true)) ep.extra else (ep.extra + ("overview" to m.overview)),
                                )
                            } else {
                                ep
                            }
                        }
                        current.copy(episodes = enriched)
                    }
                }
            }
        }
    }

    private fun fetchSeasonsAsync() {
        val repo = metadata ?: return
        viewModelScope.launch {
            runCatching {
                val isTmdb = metaQuery.metaId.startsWith("tmdb:")
                val metaId = metaQuery.metaId.substringAfter(":").toLongOrNull() ?: 0L
                if (metaId > 0L) {
                    val targetMediaType = if (metaQuery.type == MediaType.MOVIE) "movie" else if (isTmdb) "tv" else "anime"
                    val details = repo.details(metaId, preferTmdb = isTmdb, mediaType = targetMediaType)
                    if (details != null && details.seasons.isNotEmpty()) {
                        val hasSeriesSeasons = details.seasons.size > 1 || details.seasons.any { s -> (s.episodeCount ?: 0) > 1 || (s.seasonNumber ?: 1) > 1 }
                        _state.update { current ->
                            current.copy(
                                availableSeasons = details.seasons,
                                isMovie = if (hasSeriesSeasons) false else current.isMovie,
                            )
                        }
                    }
                }
            }
        }
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
        private val initialSeasonNumber: Int = 1,
        private val initialSeasons: List<com.animarvle.app.domain.SeasonUi> = emptyList(),
        private val defaultQuality: String = "auto",
        private val initialCustomQuery: String? = null,
        private val isOffline: Boolean = false,
        private val metadata: com.animarvle.app.data.MetadataRepository? = null,
        private val isMovie: Boolean? = null,
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
                initialSeasonNumber,
                initialSeasons,
                defaultQuality,
                initialCustomQuery,
                isOffline,
                metadata,
                isMovie,
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