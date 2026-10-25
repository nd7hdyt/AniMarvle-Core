package com.animarvle.app.data.source.builtin

import android.util.Log
import com.animarvle.sourceapi.Audio
import com.animarvle.sourceapi.Choice
import com.animarvle.sourceapi.EpisodeOptions
import com.animarvle.sourceapi.MediaSource
import com.animarvle.sourceapi.MediaType
import com.animarvle.sourceapi.ResolvedMedia
import com.animarvle.sourceapi.ServerOption
import com.animarvle.sourceapi.SourceCapabilities
import com.animarvle.sourceapi.SourceEpisode
import com.animarvle.sourceapi.SourceErrorKind
import com.animarvle.sourceapi.SourceException
import com.animarvle.sourceapi.SourceMedia
import com.animarvle.sourceapi.Stream
import com.animarvle.sourceapi.StreamKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import com.animarvle.app.ui.player.SpokenLanguage

/**
 * In-built native Kotlin scraper for 4K HDHub (Th3-Anime style).
 *
 * Scrapes 4K HDHub / HubCloud releases natively, providing:
 * - True resolution tiers: 4K (2160p), 1080p (FHD), 720p (HD), 480p (SD)
 * - Exact file sizes and codec tags (e.g. 65.95 GB REMUX, 6.6 GB BluRay x265)
 * - Self-contained redirect resolver (triple base64 + ROT13)
 * - Direct high-speed video streams (Cloudflare Workers, Pixeldrain 10Gbps, BuzzServer)
 * - No external APK or DexClassLoader needed.
 */
class FourKHDHubSource(
    private val http: OkHttpClient = OkHttpClient.Builder().build(),
) : MediaSource {

    override val id: Long = 40001L
    override val name: String = "4K HDHub"
    override val language: String = "en"
    override val supportedTypes: Set<MediaType> = setOf(MediaType.MOVIE, MediaType.ANIME)

    override val capabilities: SourceCapabilities = SourceCapabilities(
        sub = true,
        dub = true,
        multiServer = true,
        qualities = true,
        skipTimes = false,
        downloadable = true,
    )

    @Volatile
    private var cachedMainDomain: String = DEFAULT_MAIN

    private val htmlCache = ConcurrentHashMap<String, String>()

    // --- Search -----------------------------------------------------------------------------

    override suspend fun search(query: String, type: MediaType, page: Int): List<SourceMedia> = withContext(Dispatchers.IO) {
        val domain = getDomain()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = if (page > 1) "$domain/page/$page/?s=$encodedQuery" else "$domain/?s=$encodedQuery"
        val html = fetch(url, "$domain/")

        val cardRegex = Regex("""<a[^>]*href="([^"]+)"[^>]*class="[^"]*\bmovie-card\b[^"]*"[^>]*>([\s\S]*?)<\/a>|<a[^>]*class="[^"]*\bmovie-card\b[^"]*"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>|<a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>""")
        val titleRegex = Regex("""<h3[^>]*>([\s\S]*?)<\/h3>""")
        val imgRegex = Regex("""<img[^>]+(?:data-src|src)="([^"]+)"""")

        val results = mutableListOf<SourceMedia>()
        val seenUrls = mutableSetOf<String>()

        cardRegex.findAll(html).forEach { match ->
            val href = match.groupValues[1].ifEmpty { match.groupValues[3] }.ifEmpty { match.groupValues[5] }
            val inner = match.groupValues[2].ifEmpty { match.groupValues[4] }.ifEmpty { match.groupValues[6] }
            val titleMatch = titleRegex.find(inner) ?: return@forEach
            val cleanTitle = cleanHtml(titleMatch.groupValues[1])
            if (cleanTitle.isBlank()) return@forEach

            val absUrl = toAbsoluteUrl(href, domain)
            if (seenUrls.contains(absUrl)) return@forEach
            seenUrls.add(absUrl)

            val imgMatch = imgRegex.find(inner)
            val posterUrl = imgMatch?.groupValues?.get(1)?.let { toAbsoluteUrl(it, domain) }
            val year = parseYear(cleanTitle)

            results.add(
                SourceMedia(
                    url = absUrl,
                    title = cleanTitle,
                    year = year,
                    episodeCount = 1,
                    poster = posterUrl,
                    type = type,
                    extra = mapOf("sourceId" to id.toString()),
                ),
            )
        }
        results
    }

    // --- Episodes ---------------------------------------------------------------------------

    override suspend fun episodes(media: SourceMedia): List<SourceEpisode> = withContext(Dispatchers.IO) {
        val domain = getDomain()
        val html = fetch(media.url, "$domain/")
        htmlCache[media.url] = html

        // Check if TV series with multiple episodes
        val isSeries = html.contains("season-item", ignoreCase = true) ||
            html.contains("episode-download-item", ignoreCase = true)

        if (!isSeries) {
            listOf(
                SourceEpisode(
                    url = media.url,
                    number = 1f,
                    title = media.title,
                    thumbnail = media.poster,
                    extra = mapOf(
                        KEY_MEDIA_URL to media.url,
                        "movieTitle" to media.title,
                    ),
                ),
            )
        } else {
            // Parse series episodes if present (S01 E01 format)
            val epRegex = Regex("""(?:S(\d+)\s*E(\d+)|Episode[\s-]*(\d+))""", RegexOption.IGNORE_CASE)
            val epMatches = epRegex.findAll(html).toList()
            if (epMatches.isNotEmpty()) {
                val list = mutableListOf<SourceEpisode>()
                val seenEps = mutableSetOf<Int>()
                epMatches.forEach { m ->
                    val epNum = m.groupValues[2].toIntOrNull() ?: m.groupValues[3].toIntOrNull() ?: 1
                    if (!seenEps.contains(epNum)) {
                        seenEps.add(epNum)
                        list.add(
                            SourceEpisode(
                                url = media.url,
                                number = epNum.toFloat(),
                                title = "Episode $epNum",
                                extra = mapOf(
                                    KEY_MEDIA_URL to media.url,
                                    "targetEpisode" to epNum.toString(),
                                ),
                            ),
                        )
                    }
                }
                list.sortedBy { it.number }
            } else {
                listOf(
                    SourceEpisode(
                        url = media.url,
                        number = 1f,
                        title = media.title,
                        thumbnail = media.poster,
                        extra = mapOf(KEY_MEDIA_URL to media.url),
                    ),
                )
            }
        }
    }

    // --- Options (Releases with 4K, 1080p, 720p, sizes) -------------------------------------

    override suspend fun options(episode: SourceEpisode): EpisodeOptions = withContext(Dispatchers.IO) {
        val pageUrl = episode.extra[KEY_MEDIA_URL] ?: episode.url
        val domain = getDomain()
        val html = htmlCache[pageUrl] ?: fetch(pageUrl, "$domain/")

        // Split by download items
        val chunks = html.split("class=\"download-item")
        val scoredOptions = mutableListOf<Pair<ServerOption, Int>>()
        val fallbackBloated = mutableListOf<Pair<ServerOption, Int>>()
        val seenLinks = mutableSetOf<String>()

        chunks.drop(1).forEachIndexed { idx, chunk ->
            // Extract raw text for resolution, tags, and size
            val rawText = cleanHtml(chunk.take(1200))
            val resLabel = extractResolution(rawText)
            val size = extractSize(rawText)
            val sizeMB = parseSizeInMB(size)
            val tags = extractTags(rawText)

            // Look for HubCloud download link (greenmotors / hubcloud / id=)
            val linkRegex = Regex("""<a[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>""")
            val linksWithText = linkRegex.findAll(chunk.take(6000)).map {
                it.groupValues[1] to cleanHtml(it.groupValues[2])
            }.toList()

            // Prefer Download HubCloud link (public direct stream) over HubDrive (login required)
            val hubCloudLink = linksWithText.firstOrNull { (l, t) ->
                t.contains("HubCloud", ignoreCase = true) ||
                    (l.contains("greenmotors", ignoreCase = true) && !t.contains("HubDrive", ignoreCase = true))
            }?.first ?: linksWithText.firstOrNull { (l, _) ->
                l.contains("greenmotors", ignoreCase = true) || l.contains("hubcloud", ignoreCase = true)
            }?.first ?: linksWithText.firstOrNull { (l, _) -> l.contains("id=", ignoreCase = true) }?.first

            if (hubCloudLink != null && !seenLinks.contains(hubCloudLink)) {
                seenLinks.add(hubCloudLink)
                val displayLabel = buildString {
                    append(resLabel)
                    if (size.isNotBlank()) append(" · ").append(size)
                    if (tags.isNotBlank()) append(" (").append(tags).append(")")
                }
                val hint = buildString {
                    if (size.isNotBlank()) append(size)
                    if (tags.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(tags)
                    }
                }
                val detected = SpokenLanguage.fromRawTag(tags) ?: SpokenLanguage.fromRawTag(rawText)
                val opt = ServerOption(
                    key = "4khd:$idx|$resLabel|$hubCloudLink",
                    label = displayLabel,
                    hint = hint,
                    audioLanguage = detected?.displayName,
                )
                val score = calculateReleaseScore(resLabel, sizeMB, rawText)
                if (shouldSkipRelease(sizeMB, rawText)) {
                    fallbackBloated.add(opt to score)
                } else {
                    scoredOptions.add(opt to score)
                }
            }
        }

        // Rank by highest sweet-spot score first; fallback to bloated if no filtered options found
        val optionsList = when {
            scoredOptions.isNotEmpty() -> scoredOptions.sortedByDescending { it.second }.map { it.first }
            fallbackBloated.isNotEmpty() -> fallbackBloated.sortedByDescending { it.second }.map { it.first }
            else -> emptyList()
        }.toMutableList()

        if (optionsList.isEmpty()) {
            // Fallback: search all anchor tags in the page for any hub links
            val allLinks = Regex("""href="([^"]+)"""").findAll(html)
                .map { it.groupValues[1] }
                .filter { it.contains("greenmotors", ignoreCase = true) || it.contains("hubcloud", ignoreCase = true) }
                .distinct()
                .toList()

            allLinks.forEachIndexed { idx, l ->
                optionsList.add(
                    ServerOption(
                        key = "4khd:$idx|HD|$l",
                        label = "1080p · Server ${idx + 1}",
                        hint = "Direct HubCloud",
                        audioLanguage = "English",
                    ),
                )
            }
        }

        EpisodeOptions(
            byAudio = buildMap {
                if (optionsList.isNotEmpty()) {
                    put(Audio.SUB, optionsList)
                    put(Audio.DUB, optionsList)
                }
            },
        )
    }

    // --- Resolve (Direct Streaming URL) -----------------------------------------------------

    override suspend fun resolve(episode: SourceEpisode, choice: Choice): ResolvedMedia = withContext(Dispatchers.IO) {
        val parts = choice.serverKey.split("|")
        val rawLink = parts.getOrNull(2)
            ?: throw SourceException(SourceErrorKind.NOT_FOUND, "Invalid server key: ${choice.serverKey}")
        val resLabel = parts.getOrNull(1) ?: "1080p"

        Log.i(TAG, "Resolving 4K HDHub link: $rawLink ($resLabel)")

        // 1. Resolve redirect if link has id=
        val hubDriveOrCloud = if (rawLink.contains("id=", ignoreCase = true)) {
            resolveRedirect(rawLink)
        } else {
            rawLink
        }

        if (hubDriveOrCloud.isBlank()) {
            throw SourceException(SourceErrorKind.NOT_FOUND, "Could not resolve redirect link")
        }

        Log.i(TAG, "Redirect resolved to: $hubDriveOrCloud")

        // 2. Fetch HubCloud page to find hubcloud.php generator URL
        val doc = fetch(hubDriveOrCloud, rawLink)
        val phpRegex = Regex("""href="([^"]*hubcloud\.php[^"]*)"""")
        val directBtnRegex = Regex("""<a[^>]*href="([^"]+)"[^>]*class="[^"]*\bbtn\b[^"]*"[^>]*>([\s\S]*?)<\/a>|<a[^>]*class="[^"]*\bbtn\b[^"]*"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>""")

        var phpUrl = phpRegex.find(doc)?.groupValues?.get(1)

        if (phpUrl == null) {
            // Check if any button links to generate
            directBtnRegex.findAll(doc).forEach { m ->
                val link = m.groupValues[1].ifEmpty { m.groupValues[3] }
                if (link.contains("hubcloud.php", ignoreCase = true)) {
                    phpUrl = link
                }
            }
        }

        val targetUrl = phpUrl ?: hubDriveOrCloud
        Log.i(TAG, "Fetching server page: $targetUrl")
        val serverPageHtml = fetch(targetUrl, hubDriveOrCloud)

        // 3. Extract direct server links from serverPageHtml
        val serverButtons = directBtnRegex.findAll(serverPageHtml).mapNotNull { m ->
            val link = m.groupValues[1].ifEmpty { m.groupValues[3] }
            val label = cleanHtml(m.groupValues[2].ifEmpty { m.groupValues[4] }).lowercase()
            if (link.isNotBlank()) link to label else null
        }.toList()

        Log.i(TAG, "Found ${serverButtons.size} server buttons")

        // Priority 1: Direct Cloudflare Workers / GPDL / FastX 10Gbps
        val directWorker = serverButtons.firstOrNull { (link, label) ->
            link.contains("gpdl", ignoreCase = true) ||
                link.contains(".workers.dev", ignoreCase = true) ||
                link.endsWith(".mkv", ignoreCase = true) ||
                link.endsWith(".mp4", ignoreCase = true) ||
                label.contains("fastx", ignoreCase = true) ||
                label.contains("fast server", ignoreCase = true) ||
                label.contains("cf worker", ignoreCase = true) ||
                label.contains("10gbps", ignoreCase = true)
        }

        // Priority 2: Pixeldrain
        val pixeldrain = serverButtons.firstOrNull { (_, label) ->
            label.contains("pixel", ignoreCase = true)
        }

        // Priority 3: BuzzServer
        val buzzServer = serverButtons.firstOrNull { (_, label) ->
            label.contains("buzz", ignoreCase = true)
        }

        var resolvedStreamUrl = ""

        if (directWorker != null) {
            val resolvedWorker = resolveDirectWorker(directWorker.first, targetUrl)
            if (resolvedWorker.isNotBlank() && (resolvedWorker.startsWith("http://") || resolvedWorker.startsWith("https://"))) {
                resolvedStreamUrl = resolvedWorker
            }
        }

        if (resolvedStreamUrl.isBlank() && pixeldrain != null) {
            val resolvedPixel = resolvePixeldrain(pixeldrain.first)
            if (resolvedPixel.isNotBlank()) {
                resolvedStreamUrl = resolvedPixel
            }
        }

        if (resolvedStreamUrl.isBlank() && buzzServer != null) {
            val resolvedBuzz = resolveBuzzServer(buzzServer.first)
            if (resolvedBuzz.isNotBlank()) {
                resolvedStreamUrl = resolvedBuzz
            }
        }

        if (resolvedStreamUrl.isBlank()) {
            val fallbackBtn = serverButtons.firstOrNull()?.first
            resolvedStreamUrl = fallbackBtn ?: throw SourceException(SourceErrorKind.NOT_FOUND, "No playable server found for $resLabel")
        }

        Log.i(TAG, "4K HDHub final stream URL: $resolvedStreamUrl")

        ResolvedMedia(
            streams = listOf(
                Stream(
                    url = resolvedStreamUrl,
                    kind = if (resolvedStreamUrl.contains(".m3u8", ignoreCase = true)) StreamKind.HLS else StreamKind.PROGRESSIVE,
                    qualityLabel = resLabel,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "https://cryptonewz.online/",
                    ),
                ),
            ),
        )
    }

    // --- Helpers & Resolvers ----------------------------------------------------------------

    private suspend fun resolveDirectWorker(url: String, referer: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer)
                .build()
            http.newCall(req).execute().use { response ->
                val finalUrl = response.request.url.toString()
                val linkParam = response.request.url.queryParameter("link")
                if (!linkParam.isNullOrBlank()) {
                    return@withContext linkParam
                }
                if (finalUrl.contains(".mkv", ignoreCase = true) ||
                    finalUrl.contains(".mp4", ignoreCase = true) ||
                    finalUrl.contains("googleusercontent.com", ignoreCase = true)) {
                    return@withContext finalUrl
                }
                val body = response.body?.string().orEmpty()
                val btnMatch = Regex("""id=["']downloadBtn["'][^>]*href=["']([^"']+)["']""").find(body)
                    ?: Regex("""href=["'](https?://video-downloads\.googleusercontent\.com[^"']+)["']""").find(body)
                    ?: Regex("""href=["'](https?://[^"']+\.(?:mkv|mp4)[^"']*)["']""").find(body)
                btnMatch?.groupValues?.get(1) ?: finalUrl
            }
        }.getOrDefault(url)
    }

    private suspend fun resolveRedirect(url: String): String = runCatching {
        val html = fetch(url)
        val sRegex = Regex("""s\('o','([A-Za-z0-9+/=]+)'""")
        val ckRegex = Regex("""ck\('_wp_http_\d+','([^']+)'""")

        val sMatches = sRegex.findAll(html).map { it.groupValues[1] }
        val ckMatches = ckRegex.findAll(html).map { it.groupValues[1] }
        val combined = (sMatches + ckMatches).joinToString("")

        if (combined.isBlank()) return ""

        // Triple Base64 + ROT13 decode
        val step1 = b64Decode(combined)
        val step2 = b64Decode(step1)
        val step3 = rot13(step2)
        val decodedJson = b64Decode(step3)

        val json = JSONObject(decodedJson)
        val o = json.optString("o")
        if (o.isNotBlank()) {
            b64Decode(o).trim()
        } else {
            val blogUrl = json.optString("blog_url")
            val data = json.optString("data")
            if (blogUrl.isNotBlank() && data.isNotBlank()) {
                val reData = b64Decode(data)
                cleanHtml(fetch("$blogUrl?re=$reData"))
            } else ""
        }
    }.getOrDefault("")

    private fun resolvePixeldrain(link: String): String {
        val fileId = link.trimEnd('/').substringAfterLast('/')
        return "https://pixeldrain.com/api/file/$fileId?download"
    }

    private suspend fun resolveBuzzServer(link: String): String = runCatching {
        val req = Request.Builder()
            .url("$link/download")
            .header("User-Agent", USER_AGENT)
            .header("Referer", link)
            .build()
        http.newCall(req).execute().use { resp ->
            resp.header("hx-redirect") ?: resp.header("HX-Redirect") ?: link
        }
    }.getOrDefault(link)

    private suspend fun getDomain(): String {
        val cached = cachedMainDomain
        if (cached != DEFAULT_MAIN) return cached
        return runCatching {
            val jsonStr = fetch(DOMAINS_URL)
            val json = JSONObject(jsonStr)
            val main = json.optString("4khdhub", DEFAULT_MAIN).trimEnd('/')
            cachedMainDomain = main
            main
        }.getOrDefault(DEFAULT_MAIN)
    }

    private suspend fun fetch(url: String, referer: String? = null): String = withContext(Dispatchers.IO) {
        runCatching {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
            if (referer != null) {
                reqBuilder.header("Referer", referer)
            }
            http.newCall(reqBuilder.build()).execute().use { response ->
                response.body?.string().orEmpty()
            }
        }.getOrDefault("")
    }

    private fun extractResolution(text: String): String = when {
        text.contains("2160p", ignoreCase = true) || text.contains("4k", ignoreCase = true) -> "4K"
        text.contains("1080p", ignoreCase = true) -> "1080p"
        text.contains("720p", ignoreCase = true) -> "720p"
        text.contains("480p", ignoreCase = true) -> "480p"
        else -> "HD"
    }

    private fun extractSize(text: String): String {
        val match = Regex("""(\d+(?:\.\d+)?\s*(?:GB|MB))""", RegexOption.IGNORE_CASE).find(text)
        return match?.groupValues?.get(1)?.trim().orEmpty()
    }

    private fun parseSizeInMB(sizeText: String): Double {
        val match = Regex("""([\d.]+)\s*(GB|MB)""", RegexOption.IGNORE_CASE).find(sizeText) ?: return 0.0
        val value = match.groupValues[1].toDoubleOrNull() ?: return 0.0
        val unit = match.groupValues[2].uppercase()
        return if (unit == "GB") value * 1024.0 else value
    }

    private fun shouldSkipRelease(sizeMB: Double, rawText: String): Boolean {
        val upper = rawText.uppercase()
        // Skip untouched disc REMUX copies (> 5 GB or explicit REMUX with size > 5 GB)
        if (upper.contains("REMUX") && sizeMB > 5000.0) return true
        // Hard cap: Skip releases over 10 GB (10240 MB) for direct mobile streaming/storage
        if (sizeMB > 10240.0) return true
        return false
    }

    private fun calculateReleaseScore(resLabel: String, sizeMB: Double, rawText: String): Int {
        var score = 0
        // 1. Resolution base: 1080p is prime for mobile AMOLED/LCD screens
        when (resLabel) {
            "1080p" -> score += 60
            "720p" -> score += 40
            "4K" -> score += 15
            "480p" -> score += 10
            else -> score += 20
        }

        // 2. Codec & efficiency (AniVortex style x265 HEVC)
        val upper = rawText.uppercase()
        if (upper.contains("X265") || upper.contains("HEVC") || upper.contains("H265") || upper.contains("10BIT")) {
            score += 45
        } else if (upper.contains("WEB-DL") || upper.contains("WEBRIP")) {
            score += 25
        }

        // 3. Multi-Audio preference
        if (upper.contains("HINDI") || upper.contains("MULTI") || upper.contains("DUAL") || upper.contains("DUB")) {
            score += 30
        }

        // 4. Size sweet spot (MB) - targeting ~1.5 GB to 4.5 GB for 1080p
        when {
            sizeMB in 1500.0..4500.0 -> score += 50
            sizeMB in 700.0..1500.0 -> score += 35
            sizeMB in 4500.0..8000.0 -> score += 15
            sizeMB > 8000.0 -> score -= 30
            sizeMB > 0.0 && sizeMB < 700.0 -> score += 10
        }

        return score
    }

    private fun extractTags(text: String): String {
        val tags = mutableListOf<String>()
        val upper = text.uppercase()
        if (upper.contains("REMUX")) tags.add("REMUX")
        if (upper.contains("DV") || upper.contains("DOLBY VISION")) tags.add("DV")
        if (upper.contains("HDR10+")) tags.add("HDR10+")
        else if (upper.contains("HDR")) tags.add("HDR")
        if (upper.contains("BLURAY")) tags.add("BluRay")
        else if (upper.contains("WEB-DL")) tags.add("WEB-DL")
        if (upper.contains("H265") || upper.contains("HEVC") || upper.contains("X265") || upper.contains("10BIT")) tags.add("x265")
        else if (upper.contains("H264") || upper.contains("X264") || upper.contains("AVC")) tags.add("x264")
        if (upper.contains("HINDI") || upper.contains("DUAL") || upper.contains("MULTI")) tags.add("Multi-Audio")
        if (upper.contains("DDP5.1")) tags.add("DDP 5.1")
        if (upper.contains("ATMOS")) tags.add("Atmos")
        return tags.joinToString(" ")
    }

    private fun parseYear(text: String): Int? {
        val match = Regex("""\b(19\d{2}|20\d{2})\b""").find(text)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun cleanHtml(html: String): String =
        html.replace(Regex("""<[^>]*>"""), " ").replace(Regex("""\s+"""), " ").trim()

    private fun toAbsoluteUrl(href: String, domain: String): String = when {
        href.startsWith("http://") || href.startsWith("https://") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> "$domain$href"
        else -> "$domain/$href"
    }

    private fun rot13(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            when (c) {
                in 'a'..'z' -> sb.append((((c - 'a') + 13) % 26 + 'a'.code).toChar())
                in 'A'..'Z' -> sb.append((((c - 'A') + 13) % 26 + 'A'.code).toChar())
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun b64Decode(raw: String): String = runCatching {
        var s = raw.trim()
        val pad = s.length % 4
        if (pad != 0) s += "=".repeat(4 - pad)
        val bytes = Base64.getDecoder().decode(s)
        String(bytes, Charsets.UTF_8)
    }.getOrDefault("")

    companion object {
        private const val TAG = "FourKHDHubSource"
        private const val DEFAULT_MAIN = "https://4khdhub.one"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        private const val DOMAINS_URL =
            "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"
        private const val KEY_MEDIA_URL = "sMediaUrl"
    }
}
