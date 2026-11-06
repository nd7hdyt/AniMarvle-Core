package com.animarvle.app.ui.detail

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.animarvle.app.AnimarvleApp
import com.animarvle.app.R
import com.animarvle.app.data.source.builtin.AnimarvleStreamSource
import com.animarvle.app.databinding.DialogChooseQualityBinding
import com.animarvle.app.databinding.ItemDownloadQualityBinding
import com.animarvle.domain.MatchResult
import com.animarvle.domain.MetaQuery
import com.animarvle.sourceapi.Audio
import com.animarvle.sourceapi.Choice
import com.animarvle.sourceapi.MediaSource
import com.animarvle.sourceapi.MediaType
import com.animarvle.sourceapi.SourceEpisode
import com.animarvle.sourceapi.Titles
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class QualityOption(
    val id: String,
    val label: String,
    val qualityBadge: String,
    val formatBadge: String = "MP4",
    val specsSubtitle: String? = null,
    val subtitleCount: Int = 0,
    val serverName: String = "",
    val audio: Audio = Audio.SUB,
    val serverKey: String = "",
    var streamUrl: String = "",
    val language: String = "English",
    val sizeText: String = "",
    val estimatedBytes: Long = 0L,
    val headers: Map<String, String> = emptyMap(),
)

data class ServerEntry(
    val serverName: String,
    val serverKey: String,
    val audioLanguage: String,
    val audio: Audio,
    val streamUrl: String,
    val rawQuality: String,
    val kind: String,
    val qualityMap: Map<String, String>,
    val headers: Map<String, String> = emptyMap(),
)

class ChooseQualityBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogChooseQualityBinding? = null
    private val binding get() = _binding!!

    private var mediaId: Long = 0L
    private var mediaTitle: String = ""
    private var posterUrl: String? = null
    private var epNumbers: List<Int> = listOf(1)
    private var mediaType: MediaType = MediaType.ANIME
    private var mediaYear: Int? = null
    private var durationMinutes: Int = 95
    private val isDonghua: Boolean get() = AnimarvleApp.isDonghua(mediaTitle)

    private var activeSource: MediaSource? = null
    private var activeEpisode: SourceEpisode? = null

    private var serverEntries: List<ServerEntry> = emptyList()
    private var availableServers: List<String> = emptyList()
    private var selectedServer: String = ""

    private var availableLanguages: List<String> = emptyList()
    private var selectedLanguage: String = ""

    private var qualityLadder: List<QualityOption> = emptyList()
    private val probedSizes = ConcurrentHashMap<String, Long>()
    private var isProbingSizes: Boolean = false
    private var onConfirmListener: ((QualityOption, List<Int>) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                val displayMetrics = resources.displayMetrics
                val targetHeight = (displayMetrics.heightPixels * 0.73f).toInt()
                sheet.layoutParams.height = targetHeight
                behavior.maxHeight = targetHeight
                behavior.peekHeight = targetHeight
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isFitToContents = true
                sheet.setBackgroundColor(Color.TRANSPARENT)
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogChooseQualityBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mediaId = arguments?.getLong(ARG_MEDIA_ID) ?: 0L
        mediaTitle = arguments?.getString(ARG_MEDIA_TITLE).orEmpty()
        posterUrl = arguments?.getString(ARG_POSTER_URL)
        epNumbers = arguments?.getIntegerArrayList(ARG_EPISODES) ?: arrayListOf(1)
        val typeStr = arguments?.getString(ARG_MEDIA_TYPE)
        mediaType = if (isDonghua) MediaType.ANIME else if (typeStr == MediaType.MOVIE.name) MediaType.MOVIE else MediaType.ANIME
        val y = arguments?.getInt(ARG_MEDIA_YEAR, -1) ?: -1
        mediaYear = if (y > 0) y else null

        val durArg = arguments?.getInt(ARG_DURATION_MINUTES, -1) ?: -1
        durationMinutes = when {
            durArg > 0 -> durArg
            mediaType == MediaType.MOVIE -> 95
            else -> 24
        }

        binding.btnClose.setOnClickListener { dismiss() }

        binding.mediaTitleText.text = mediaTitle
        if (!posterUrl.isNullOrBlank()) {
            binding.mediaPoster.load(posterUrl) {
                crossfade(true)
            }
        }
        if (epNumbers.size > 1) {
            binding.mediaSubtitleText.isVisible = true
            binding.mediaSubtitleText.text = "${epNumbers.size} episodes selected for download"
        } else {
            binding.mediaSubtitleText.isVisible = false
        }

        binding.qualityRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.qualityRecyclerView.isNestedScrollingEnabled = false

        loadQualityOptions()
    }

    private fun loadQualityOptions() {
        binding.loadingContainer.isVisible = true
        binding.contentContainer.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    withTimeoutOrNull(30_000L) {
                        fetchStreamOptions()
                    }
                }.getOrNull()
            }

            if (result != null && result.entries.isNotEmpty()) {
                serverEntries = result.entries
                availableServers = result.servers
                selectedServer = availableServers.firstOrNull { it.equals("Turbo", ignoreCase = true) }
                    ?: availableServers.firstOrNull().orEmpty()

                updateAvailableLanguagesForServer()
            } else {
                serverEntries = emptyList()
                availableServers = emptyList()
                availableLanguages = emptyList()
            }

            binding.loadingContainer.isVisible = false
            binding.contentContainer.isVisible = true

            if (serverEntries.isEmpty()) {
                android.widget.Toast.makeText(
                    requireContext(),
                    "No downloadable streams found for this title",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }

            isProbingSizes = true
            renderServerPills()
            renderLanguagePills()
            recomputeAndRenderQualityList()

            // Probe real Content-Length for every discovered URL in parallel using CDN headers
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val app = (activity?.application as? AnimarvleApp)?.container ?: return@launch
                val urlsToProbe = linkedMapOf<String, Map<String, String>>()

                for (entry in serverEntries) {
                    if (isValidHttpUrl(entry.streamUrl)) {
                        urlsToProbe[entry.streamUrl] = AnimarvleStreamSource.buildHeadersForUrl(entry.streamUrl, entry.streamUrl)
                    }
                    for ((_, qUrl) in entry.qualityMap) {
                        if (isValidHttpUrl(qUrl)) {
                            urlsToProbe[qUrl] = AnimarvleStreamSource.buildHeadersForUrl(qUrl, entry.streamUrl)
                        }
                    }
                }

                coroutineScope {
                    urlsToProbe.entries.map { (url, headers) ->
                        async {
                            val cached = AnimarvleStreamSource.sizesCache[url] ?: probedSizes[url]
                            if (cached != null && cached > 0L) {
                                probedSizes[url] = cached
                                return@async
                            }
                            val bytes = probeExactStreamSize(app.http, url, headers)
                            if (bytes != null && bytes > 0L) {
                                probedSizes[url] = bytes
                                AnimarvleStreamSource.sizesCache[url] = bytes
                                withContext(Dispatchers.Main) {
                                    if (isAdded && !isDetached && _binding != null) {
                                        recomputeAndRenderQualityList()
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }

                withContext(Dispatchers.Main) {
                    isProbingSizes = false
                    if (isAdded && !isDetached && _binding != null) {
                        recomputeAndRenderQualityList()
                    }
                }
            }
        }
    }

    private fun updateAvailableLanguagesForServer() {
        val langsForServer = serverEntries
            .filter { it.serverName.equals(selectedServer, ignoreCase = true) }
            .map { it.audioLanguage }
            .distinct()

        availableLanguages = langsForServer.ifEmpty {
            serverEntries.map { it.audioLanguage }.distinct()
        }

        if (selectedLanguage.isBlank() || availableLanguages.none { it.equals(selectedLanguage, ignoreCase = true) }) {
            selectedLanguage = availableLanguages.firstOrNull { it.equals("English", ignoreCase = true) }
                ?: availableLanguages.firstOrNull().orEmpty()
        }
    }

    /**
     * Probes the actual byte size of a video stream from the CDN using 3 strategies:
     * 1. Range: bytes=0-0 -> parses Content-Range: bytes 0-0/TOTAL_BYTES
     * 2. HTTP HEAD -> parses Content-Length
     * 3. Normal GET (aborted immediately after reading headers) -> parses Content-Length
     */
    private fun probeExactStreamSize(
        http: okhttp3.OkHttpClient,
        url: String,
        headers: Map<String, String>,
    ): Long? {
        val lower = url.lowercase(Locale.US)
        if (lower.contains(".m3u8") || lower.contains("/proxy/m3u8") || lower.contains(".mpd")) {
            return null
        }

        // Strategy 1: Range: bytes=0-0
        runCatching {
            val reqBuilder = okhttp3.Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .header("Accept-Encoding", "identity")
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }

            http.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful || resp.code == 206) {
                    val cr = resp.header("Content-Range")
                    if (cr != null && cr.contains("/")) {
                        val total = cr.substringAfterLast("/").trim().toLongOrNull()
                        if (total != null && total > 100_000L) return total
                    }
                    val cl = resp.header("Content-Length")?.toLongOrNull()
                    if (cl != null && cl > 100_000L) return cl
                }
            }
        }

        // Strategy 2: HTTP HEAD
        runCatching {
            val headBuilder = okhttp3.Request.Builder()
                .url(url)
                .head()
                .header("Accept-Encoding", "identity")
            headers.forEach { (k, v) -> headBuilder.header(k, v) }

            http.newCall(headBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    val cl = resp.header("Content-Length")?.toLongOrNull()
                    if (cl != null && cl > 100_000L) return cl
                }
            }
        }

        // Strategy 3: Standard GET (reads only response headers, then closes body stream immediately)
        runCatching {
            val getBuilder = okhttp3.Request.Builder()
                .url(url)
                .get()
                .header("Accept-Encoding", "identity")
            headers.forEach { (k, v) -> getBuilder.header(k, v) }

            http.newCall(getBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    val cl = resp.header("Content-Length")?.toLongOrNull()
                    if (cl != null && cl > 100_000L) return cl
                }
            }
        }

        return null
    }

    private data class StreamFetchResult(
        val servers: List<String>,
        val languages: List<String>,
        val entries: List<ServerEntry>,
    )

    private suspend fun fetchStreamOptions(): StreamFetchResult = coroutineScope {
        val app = (requireActivity().application as AnimarvleApp).container
        val meta = MetaQuery(
            metaId = "anilist:$mediaId",
            type = mediaType,
            titles = Titles(english = mediaTitle),
            synonyms = emptyList(),
            year = mediaYear,
            episodeCount = epNumbers.maxOrNull() ?: 1,
        )

        val sources = app.sourcesFor(mediaType, mediaTitle)
        val match = app.matcher.match(meta, sources)
        if (match !is MatchResult.Matched) {
            return@coroutineScope StreamFetchResult(emptyList(), emptyList(), emptyList())
        }

        val sMedia = match.media
        val source = sources.firstOrNull { it.id == match.sourceId }
            ?: return@coroutineScope StreamFetchResult(emptyList(), emptyList(), emptyList())

        val episodes = runCatching { source.episodes(sMedia) }.getOrNull().orEmpty()
        val targetEpNumber = epNumbers.firstOrNull() ?: 1
        val firstEp = episodes.firstOrNull { it.number.toInt() == targetEpNumber } ?: episodes.firstOrNull()
            ?: return@coroutineScope StreamFetchResult(emptyList(), emptyList(), emptyList())

        activeSource = source
        activeEpisode = firstEp

        val epOptions = runCatching { source.options(firstEp) }.getOrNull()
        val subServers = epOptions?.byAudio?.get(Audio.SUB).orEmpty()
        val dubServers = epOptions?.byAudio?.get(Audio.DUB).orEmpty()
        val rawServers = epOptions?.byAudio?.get(Audio.RAW).orEmpty()
        val allServerOptions = (subServers + dubServers + rawServers).distinctBy { it.key }

        val resolvedEntriesLists = allServerOptions.map { s ->
            async(Dispatchers.IO) {
                // Match AnimarvleStreamSource.resolve() key format: "turbo:i|quality|kindStr|streamUrl"
                val parts = s.key.split("|", limit = 4)
                val part1 = parts.getOrNull(1).orEmpty()
                val part2 = parts.getOrNull(2).orEmpty()
                val part3 = parts.getOrNull(3).orEmpty()

                val (kindFromKey, urlFromKey) = when {
                    isValidHttpUrl(part3) -> part2 to part3
                    isValidHttpUrl(part2) -> part3 to part2
                    else -> {
                        val anyUrl = parts.firstOrNull { isValidHttpUrl(it) }.orEmpty()
                        "" to anyUrl
                    }
                }

                val serverName = when {
                    s.label.contains("spiral", ignoreCase = true) -> "Spiral"
                    s.label.contains("turbo", ignoreCase = true) -> "Turbo"
                    s.label.contains("fast", ignoreCase = true) -> "FastX"
                    else -> s.label.substringBefore(" -").substringBefore(" (").trim().ifBlank { "Turbo" }
                }

                val lang = s.audioLanguage?.takeIf { it.isNotBlank() } ?: "English"
                val isSub = subServers.any { it.key == s.key }
                val audio = if (lang.equals("Japanese", ignoreCase = true) || lang.equals("Chinese", ignoreCase = true) || isSub) {
                    Audio.SUB
                } else {
                    Audio.DUB
                }

                // Resolve through source.resolve() to get full stream list & headers
                val resolved = runCatching {
                    withTimeoutOrNull(12_000L) {
                        source.resolve(firstEp, Choice(audio, s.key))
                    }
                }.getOrNull()

                val qualityMap = linkedMapOf<String, String>()
                var primaryUrl = urlFromKey
                var primaryKind = kindFromKey
                var mergedHeaders = if (urlFromKey.isNotBlank()) {
                    AnimarvleStreamSource.buildHeadersForUrl(urlFromKey, urlFromKey)
                } else {
                    emptyMap()
                }

                if (resolved != null && resolved.streams.isNotEmpty()) {
                    for (stream in resolved.streams) {
                        if (!isValidHttpUrl(stream.url)) continue
                        if (primaryUrl.isBlank()) primaryUrl = stream.url
                        if (mergedHeaders.isEmpty() && stream.headers.isNotEmpty()) {
                            mergedHeaders = stream.headers
                        }
                        val badge = normalizeQualityBadge(stream.qualityLabel ?: stream.height?.let { "${it}p" } ?: part1)
                        if (badge.isNotBlank() && !qualityMap.containsKey(badge)) {
                            qualityMap[badge] = stream.url
                        }
                    }
                }

                // Also check AnimarvleStreamSource.qualitiesCache directly
                if (primaryUrl.isNotBlank()) {
                    val cachedMap = AnimarvleStreamSource.qualitiesCache[primaryUrl].orEmpty()
                    for ((qKey, qUrl) in cachedMap) {
                        if (!isValidHttpUrl(qUrl)) continue
                        val badge = normalizeQualityBadge(qKey).ifBlank { qKey.uppercase(Locale.US) }
                        if (badge.isNotBlank() && !qualityMap.containsKey(badge)) {
                            qualityMap[badge] = qUrl
                        }
                    }
                }

                // If it's an HLS master playlist with only 1 entry, parse #EXT-X-STREAM-INF variants
                if (primaryUrl.isNotBlank() &&
                    (primaryUrl.contains(".m3u8", ignoreCase = true) || primaryKind.equals("hls", ignoreCase = true)) &&
                    qualityMap.size <= 1
                ) {
                    val hlsVariants = parseHlsMasterQualities(app.http, primaryUrl, mergedHeaders)
                    for ((qBadge, qUrl) in hlsVariants) {
                        if (!qualityMap.containsKey(qBadge)) {
                            qualityMap[qBadge] = qUrl
                        }
                    }
                }

                val rawQuality = normalizeQualityBadge(part1.ifBlank { s.hint ?: s.label }).ifBlank { "1080p" }
                if (primaryUrl.isNotBlank() && qualityMap.isEmpty()) {
                    qualityMap[rawQuality] = primaryUrl
                }

                if (primaryUrl.isBlank() && qualityMap.isEmpty()) {
                    return@async emptyList<ServerEntry>()
                }

                listOf(
                    ServerEntry(
                        serverName = serverName,
                        serverKey = s.key,
                        audioLanguage = lang,
                        audio = audio,
                        streamUrl = primaryUrl,
                        rawQuality = rawQuality,
                        kind = primaryKind.ifBlank { inferFormat(primaryUrl) },
                        qualityMap = qualityMap,
                        headers = mergedHeaders,
                    )
                )
            }
        }.awaitAll()

        val entries = resolvedEntriesLists.flatten()
        val serverNames = entries.map { it.serverName }.distinct()
        val languages = entries.map { it.audioLanguage }.distinct()

        StreamFetchResult(
            servers = serverNames,
            languages = languages,
            entries = entries,
        )
    }

    /**
     * Parses an HLS master playlist (.m3u8) to extract real resolution variants if present.
     */
    private fun parseHlsMasterQualities(
        http: okhttp3.OkHttpClient,
        masterUrl: String,
        headers: Map<String, String>,
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        runCatching {
            val reqBuilder = okhttp3.Request.Builder().url(masterUrl)
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            http.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return emptyMap()
                val body = resp.body?.string().orEmpty()
                if (!body.contains("#EXT-X-STREAM-INF")) return emptyMap()

                val lines = body.lines()
                var pendingQuality: String? = null
                var pendingBandwidth: Long? = null
                val baseUri = URI(masterUrl)

                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                        val resMatch = Regex("RESOLUTION=\\d+x(\\d+)", RegexOption.IGNORE_CASE).find(trimmed)
                        val bwMatch = Regex("AVERAGE-BANDWIDTH=(\\d+)|BANDWIDTH=(\\d+)", RegexOption.IGNORE_CASE).find(trimmed)
                        pendingBandwidth = bwMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toLongOrNull()

                        val height = resMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                        pendingQuality = when {
                            height != null && height >= 2000 -> "4K"
                            height != null && height >= 1400 -> "1440p"
                            height != null && height >= 1000 -> "1080p"
                            height != null && height >= 700 -> "720p"
                            height != null && height >= 460 -> "480p"
                            height != null && height >= 340 -> "360p"
                            height != null && height >= 220 -> "240p"
                            height != null && height > 0 -> "144p"
                            else -> null
                        }
                    } else if (pendingQuality != null && trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                        val resolvedVariantUrl = runCatching { baseUri.resolve(trimmed).toString() }.getOrDefault(trimmed)
                        result[pendingQuality] = resolvedVariantUrl
                        // If HLS manifest explicitly states BANDWIDTH (bits/sec), compute size from manifest bandwidth
                        if (pendingBandwidth != null && pendingBandwidth > 0L && durationMinutes > 0) {
                            val bytes = (pendingBandwidth / 8L) * (durationMinutes * 60L)
                            if (bytes > 0L) {
                                probedSizes[resolvedVariantUrl] = bytes
                            }
                        }
                        pendingQuality = null
                        pendingBandwidth = null
                    }
                }
            }
        }
        return result
    }

    private fun normalizeQualityBadge(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val s = raw.lowercase(Locale.US)
        return when {
            s.contains("2160") || s.contains("4k") || s.contains("uhd") -> "4K"
            s.contains("1440") || s.contains("2k") -> "1440p"
            s.contains("1080") || s.contains("fhd") -> "1080p"
            s.contains("720") || s.contains("hd") -> "720p"
            s.contains("480") || s.contains("sd") -> "480p"
            s.contains("360") -> "360p"
            s.contains("240") -> "240p"
            s.contains("144") -> "144p"
            else -> ""
        }
    }

    private fun qualitySortRank(badge: String): Int = when (badge.uppercase(Locale.US)) {
        "4K", "2160P" -> 700
        "1440P", "2K" -> 600
        "1080P" -> 500
        "720P" -> 400
        "480P" -> 300
        "360P" -> 200
        "240P" -> 100
        "144P" -> 50
        else -> 10
    }

    private fun inferFormat(url: String, kindHint: String? = null): String {
        val u = url.lowercase(Locale.US)
        val k = kindHint?.lowercase(Locale.US).orEmpty()
        return when {
            u.contains(".mkv") || k.contains("mkv") -> "MKV"
            u.contains(".m3u8") || u.contains("/proxy/m3u8") || k.contains("hls") -> "HLS"
            u.contains(".mpd") || k.contains("dash") -> "DASH"
            else -> "MP4"
        }
    }

    private fun renderServerPills() {
        binding.serverPillsContainer.removeAllViews()
        for (server in availableServers) {
            val isSelected = server.equals(selectedServer, ignoreCase = true)
            val pill = createPill(server, isSelected) {
                if (!server.equals(selectedServer, ignoreCase = true)) {
                    selectedServer = server
                    updateAvailableLanguagesForServer()
                    renderServerPills()
                    renderLanguagePills()
                    recomputeAndRenderQualityList()
                }
            }
            binding.serverPillsContainer.addView(pill)
        }
    }

    private fun renderLanguagePills() {
        binding.languagePillsContainer.removeAllViews()
        for (lang in availableLanguages) {
            val isSelected = lang.equals(selectedLanguage, ignoreCase = true)
            val pill = createPill(lang, isSelected) {
                if (!lang.equals(selectedLanguage, ignoreCase = true)) {
                    selectedLanguage = lang
                    renderLanguagePills()
                    recomputeAndRenderQualityList()
                }
            }
            binding.languagePillsContainer.addView(pill)
        }
    }

    private fun createPill(text: String, isSelected: Boolean, onClick: () -> Unit): View {
        val density = resources.displayMetrics.density
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setPadding(
                (18 * density).toInt(),
                (8 * density).toInt(),
                (18 * density).toInt(),
                (8 * density).toInt(),
            )
            if (isSelected) {
                setBackgroundResource(R.drawable.bg_server_pill_active)
                setTextColor(Color.BLACK)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            } else {
                setBackgroundResource(R.drawable.bg_server_pill_inactive)
                setTextColor(Color.parseColor("#9E9E9E"))
                typeface = android.graphics.Typeface.DEFAULT
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (36 * density).toInt(),
            ).apply {
                marginEnd = (8 * density).toInt()
            }
            layoutParams = params
            setOnClickListener { onClick() }
        }
    }

    /**
     * Builds the quality list strictly from real, verified streams for the selected server & language.
     * Never invents missing resolutions or reuses another resolution's URL/size.
     */
    private fun recomputeAndRenderQualityList() {
        val count = epNumbers.size.coerceAtLeast(1)

        val matchingEntries = serverEntries.filter {
            it.serverName.equals(selectedServer, ignoreCase = true) &&
            it.audioLanguage.equals(selectedLanguage, ignoreCase = true)
        }.ifEmpty {
            serverEntries.filter { it.serverName.equals(selectedServer, ignoreCase = true) }
        }

        data class ResolvedQualityTarget(
            val badge: String,
            val url: String,
            val entry: ServerEntry,
        )

        val discoveredQualities = linkedMapOf<String, ResolvedQualityTarget>()

        for (entry in matchingEntries) {
            for ((qKey, qUrl) in entry.qualityMap) {
                if (!isValidHttpUrl(qUrl)) continue
                val badge = normalizeQualityBadge(qKey).ifBlank { qKey.uppercase(Locale.US) }
                if (!discoveredQualities.containsKey(badge)) {
                    discoveredQualities[badge] = ResolvedQualityTarget(badge, qUrl, entry)
                }
            }
            if (isValidHttpUrl(entry.streamUrl)) {
                val badge = normalizeQualityBadge(entry.rawQuality).ifBlank { "1080p" }
                if (!discoveredQualities.containsKey(badge)) {
                    discoveredQualities[badge] = ResolvedQualityTarget(badge, entry.streamUrl, entry)
                }
            }
        }

        // Deduplicate by URL so two quality cards NEVER point to the same underlying file
        val uniqueUrlTargets = discoveredQualities.values
            .sortedByDescending { qualitySortRank(it.badge) }
            .distinctBy { it.url }

        qualityLadder = uniqueUrlTargets.map { target ->
            val qBadge = target.badge
            val directUrl = target.url
            val entry = target.entry
            val format = inferFormat(directUrl, entry.kind)

            // 1. Check if DownloadRepository already has a verified size for this exact streamUrl
            val cachedDownloadBytes = runCatching {
                (requireActivity().application as? AnimarvleApp)?.container?.downloads?.downloadsFlow?.value?.firstOrNull {
                    it.streamUrl == directUrl && it.totalBytes > 0L
                }?.totalBytes
            }.getOrNull()

            // 2. Check probed CDN Content-Length for this exact URL
            val singleEpBytes = cachedDownloadBytes
                ?: probedSizes[directUrl]
                ?: AnimarvleStreamSource.sizesCache[directUrl]

            val totalVerifiedBytes = if (singleEpBytes != null && singleEpBytes > 0L) {
                singleEpBytes * count
            } else {
                0L
            }

            val sizeLabel = when {
                totalVerifiedBytes > 0L -> {
                    val mb = totalVerifiedBytes.toDouble() / (1024.0 * 1024.0)
                    if (mb >= 1024.0) {
                        String.format(Locale.US, "%.2f GB (%.1f MB)", mb / 1024.0, mb)
                    } else {
                        String.format(Locale.US, "%.1f MB", mb)
                    }
                }
                format == "HLS" || format == "DASH" -> "Adaptive Stream"
                isProbingSizes -> "Checking size..."
                else -> "Direct Stream"
            }

            // Build updated serverKey with the specific quality's URL so batch/detail downloaders use the exact chosen resolution
            val specificServerKey = "turbo:0|$qBadge|${format.lowercase(Locale.US)}|$directUrl"

            QualityOption(
                id = "${selectedServer}_${selectedLanguage}_$qBadge",
                label = qBadge,
                qualityBadge = qBadge,
                formatBadge = format,
                specsSubtitle = "$format • $sizeLabel",
                serverName = selectedServer,
                audio = entry.audio,
                serverKey = specificServerKey,
                streamUrl = directUrl,
                language = selectedLanguage,
                sizeText = sizeLabel,
                estimatedBytes = totalVerifiedBytes,
                headers = AnimarvleStreamSource.buildHeadersForUrl(directUrl, entry.streamUrl),
            )
        }

        renderQualityList()
    }

    private fun renderQualityList() {
        val adapter = DownloadQualityAdapter(
            items = qualityLadder,
            onDownloadClick = { item, holder ->
                startDownloadForItem(item, holder)
            },
        )
        binding.qualityRecyclerView.adapter = adapter
    }

    private fun startDownloadForItem(item: QualityOption, holder: DownloadQualityAdapter.Holder) {
        val missingPermissions = com.animarvle.app.ui.download.DownloadStorageHelper.getMissingPermissions(requireContext())
        if (missingPermissions.isNotEmpty()) {
            androidx.core.app.ActivityCompat.requestPermissions(
                requireActivity(),
                missingPermissions,
                2002,
            )
        }

        if (isValidHttpUrl(item.streamUrl)) {
            onConfirmListener?.invoke(item, epNumbers)
            dismiss()
            return
        }

        android.widget.Toast.makeText(
            requireContext(),
            "Stream URL unavailable for ${item.qualityBadge}",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    private fun isValidHttpUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
    }

    fun setOnConfirmListener(listener: (QualityOption, List<Int>) -> Unit) {
        onConfirmListener = listener
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class DownloadQualityAdapter(
        private val items: List<QualityOption>,
        private val onDownloadClick: (QualityOption, Holder) -> Unit,
    ) : RecyclerView.Adapter<DownloadQualityAdapter.Holder>() {

        inner class Holder(val b: ItemDownloadQualityBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val b = ItemDownloadQualityBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(b)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]

            holder.b.qualityText.text = item.qualityBadge
            holder.b.specsText.text = "${item.formatBadge} • ${item.sizeText}"

            holder.b.btnDownloadPill.setOnClickListener {
                onDownloadClick(item, holder)
            }

            holder.b.qualityCardRoot.setOnClickListener {
                onDownloadClick(item, holder)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    companion object {
        const val TAG = "ChooseQualityBottomSheet"
        private const val ARG_MEDIA_ID = "media_id"
        private const val ARG_MEDIA_TITLE = "media_title"
        private const val ARG_POSTER_URL = "poster_url"
        private const val ARG_EPISODES = "episodes"
        private const val ARG_MEDIA_TYPE = "media_type"
        private const val ARG_MEDIA_YEAR = "media_year"
        private const val ARG_DURATION_MINUTES = "duration_minutes"

        fun newInstance(
            mediaId: Long,
            mediaTitle: String,
            posterUrl: String?,
            episodes: List<Int>,
            mediaType: MediaType = MediaType.ANIME,
            year: Int? = null,
            durationMinutes: Int? = null,
        ): ChooseQualityBottomSheet {
            return ChooseQualityBottomSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_MEDIA_ID, mediaId)
                    putString(ARG_MEDIA_TITLE, mediaTitle)
                    putString(ARG_POSTER_URL, posterUrl)
                    putIntegerArrayList(ARG_EPISODES, ArrayList(episodes))
                    putString(ARG_MEDIA_TYPE, mediaType.name)
                    if (year != null) putInt(ARG_MEDIA_YEAR, year)
                    if (durationMinutes != null && durationMinutes > 0) putInt(ARG_DURATION_MINUTES, durationMinutes)
                }
            }
        }
    }
}
