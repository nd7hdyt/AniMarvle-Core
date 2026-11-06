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
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Built-in native scraper for Pinoy & Vivamax movies and adult cinema.
 * Directly scrapes and resolves streams from PinoyMoviePedia:
 * 1. Playmogo (Direct high-speed progressive MP4)
 * 2. MixDrop (Direct high-speed progressive MP4)
 * 3. StreamHLS (1080p direct HLS via /dl)
 * 4. ABStream (1080p direct HLS with Referer)
 * 5. VOE Server (HD HLS)
 */
class PinoyMoviePediaSource(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) : MediaSource {

    override val id: Long = 40005L
    override val name: String = "Pinoy Movie Pedia (Vivamax)"
    override val language: String = "tl"
    override val supportedTypes: Set<MediaType> = setOf(MediaType.MOVIE)

    override val capabilities: SourceCapabilities = SourceCapabilities(
        sub = true,
        dub = true,
        multiServer = true,
        qualities = true,
        skipTimes = false,
        downloadable = true,
    )

    private val htmlCache = ConcurrentHashMap<String, String>()

    // --- Search ---------------------------------------------------------------------------------

    override suspend fun search(query: String, type: MediaType, page: Int): List<SourceMedia> = withContext(Dispatchers.IO) {
        val cleanQuery = query
            .replace(Regex("""\s*\(\d{4}\)"""), "")
            .replace(Regex("""\s+season\s+\d+.*""", RegexOption.IGNORE_CASE), "")
            .trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        val results = searchUrl("https://pinoymoviepedia.ru/?s=" + URLEncoder.encode(cleanQuery, "UTF-8"), type)
        if (results.isNotEmpty()) {
            return@withContext results
        }

        // If specific multi-word query had 0 results, try with the primary title keyword
        val primaryWord = cleanQuery.split(" ").firstOrNull()?.trim().orEmpty()
        if (primaryWord.length >= 4 && primaryWord != cleanQuery) {
            return@withContext searchUrl("https://pinoymoviepedia.ru/?s=" + URLEncoder.encode(primaryWord, "UTF-8"), type)
        }

        emptyList()
    }

    private fun searchUrl(url: String, type: MediaType): List<SourceMedia> {
        val html = runCatching { fetchHtml(url, "https://pinoymoviepedia.ru/") }.getOrNull() ?: return emptyList()
        val articleRegex = Regex("""<article[^>]*>([\s\S]*?)</article>""")
        val linkRegex = Regex("""href=["']([^"']+)["']""")
        val titleRegex = Regex("""(?:class="title"[^>]*>\s*<[ah\d]+[^>]*>|<h3>\s*<a[^>]*>)\s*([\s\S]*?)</[ah\d]+>""", RegexOption.IGNORE_CASE)
        val altTitleRegex = Regex("""alt=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val yearRegex = Regex("""(?:class=["']year["']>|<span>)(\d{4})<""", RegexOption.IGNORE_CASE)
        val imgRegex = Regex("""(?:data-src|src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        val results = mutableListOf<SourceMedia>()
        val seenLinks = mutableSetOf<String>()

        articleRegex.findAll(html).forEach { match ->
            val content = match.groupValues[1]
            val linkMatch = linkRegex.find(content) ?: return@forEach
            val link = linkMatch.groupValues[1]
            if (seenLinks.contains(link)) return@forEach
            seenLinks.add(link)

            val rawTitle = titleRegex.find(content)?.groupValues?.get(1)?.trim()
                ?: altTitleRegex.find(content)?.groupValues?.get(1)?.trim()
                ?: link.trimEnd('/').substringAfterLast('/').replace('-', ' ')

            val cleanTitle = rawTitle
                .replace(Regex("""\s*\(\d{4}\)"""), "")
                .replace(Regex("<[^>]+>"), "")
                .replace("&#8217;", "'")
                .replace("&hellip;", "...")
                .replace("&amp;", "&")
                .trim()
            val img = imgRegex.find(content)?.groupValues?.get(1)
            val year = yearRegex.find(content)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""\b(?:19|20)\d{2}\b""").find(rawTitle)?.value?.toIntOrNull()
                ?: Regex("""\b(?:19|20)\d{2}\b""").find(link)?.value?.toIntOrNull()

            results.add(
                SourceMedia(
                    url = link,
                    title = cleanTitle,
                    year = year,
                    episodeCount = 1,
                    poster = img,
                    type = type,
                    extra = mapOf("mediaUrl" to link),
                )
            )
        }
        Log.i(TAG, "Search '$url' found ${results.size} items: ${results.map { it.title }}")
        return results
    }

    // --- Episodes -------------------------------------------------------------------------------

    override suspend fun episodes(media: SourceMedia): List<SourceEpisode> = withContext(Dispatchers.IO) {
        listOf(
            SourceEpisode(
                url = media.url,
                number = 1f,
                title = media.title,
                thumbnail = media.poster,
                extra = mapOf("mediaUrl" to media.url),
            )
        )
    }

    // --- Options --------------------------------------------------------------------------------

    override suspend fun options(episode: SourceEpisode): EpisodeOptions = withContext(Dispatchers.IO) {
        val pageUrl = episode.extra["mediaUrl"] ?: episode.url
        val html = htmlCache[pageUrl] ?: fetchHtml(pageUrl, "https://pinoymoviepedia.ru/").also { htmlCache[pageUrl] = it }

        val boxRegex = Regex("""<div[^>]*id=['"]source-player-[^'"]*['"][^>]*>([\s\S]*?)</div>\s*</div>""")
        val iframeRegex = Regex("""<iframe[^>]*src=[\'"]([^\'"]+)[\'"]""")

        data class DetectedServer(
            val option: ServerOption,
            val priority: Int,
        )

        val detectedList = mutableListOf<DetectedServer>()
        val seenUrls = mutableSetOf<String>()

        var serverIndex = 1
        boxRegex.findAll(html).forEach { boxMatch ->
            val boxContent = boxMatch.groupValues[1]
            val iframeSrc = iframeRegex.find(boxContent)?.groupValues?.get(1) ?: return@forEach
            if (iframeSrc.isBlank() || seenUrls.contains(iframeSrc) || iframeSrc.contains("ad.a-ads.com") || iframeSrc.contains("trailer")) return@forEach
            seenUrls.add(iframeSrc)

            val host = runCatching { URI(iframeSrc).host.lowercase() }.getOrDefault("server")
            val (label, hint, priority) = when {
                host.contains("playmogo") || host.contains("dood") || host.contains("ds2play") -> {
                    Triple("Playmogo (Direct MP4)", "Ultra Fast MP4", 1000)
                }
                host.contains("mixdrop") || host.contains("miiixdrop") -> {
                    Triple("MixDrop (Direct MP4)", "Direct 1080p MP4", 900)
                }
                host.contains("streamhls") -> {
                    Triple("StreamHLS (1080p Direct HLS)", "Direct HLS", 800)
                }
                host.contains("abstream") -> {
                    Triple("ABStream (1080p HLS)", "Fast HLS", 700)
                }
                host.contains("voe") || host.contains("shanelocal") -> {
                    Triple("VOE Server (HD)", "Fast Server", 500)
                }
                else -> {
                    Triple("Server $serverIndex ($host)", "Web Stream", 300)
                }
            }

            detectedList.add(
                DetectedServer(
                    option = ServerOption(
                        key = "pinoy:$serverIndex|$iframeSrc",
                        label = label,
                        hint = hint,
                        audioLanguage = "Filipino",
                    ),
                    priority = priority,
                )
            )
            serverIndex++
        }

        if (detectedList.isEmpty()) {
            throw SourceException(SourceErrorKind.NOT_FOUND, "No streaming servers found for this movie on PinoyMoviePedia")
        }

        // Sort so ultra fast direct MP4s (Playmogo, MixDrop) win first, followed by clean HLS!
        val sortedOptions = detectedList.sortedByDescending { it.priority }.map { it.option }

        EpisodeOptions(
            byAudio = mapOf(
                Audio.SUB to sortedOptions,
                Audio.DUB to sortedOptions,
            )
        )
    }

    // --- Resolve --------------------------------------------------------------------------------

    override suspend fun resolve(episode: SourceEpisode, choice: Choice): ResolvedMedia = withContext(Dispatchers.IO) {
        val embedUrl = choice.serverKey.substringAfter("|")
        if (embedUrl.isBlank()) {
            throw SourceException(SourceErrorKind.NOT_FOUND, "Invalid server key: ${choice.serverKey}")
        }

        Log.i(TAG, "Resolving PinoyMoviePedia embed: $embedUrl")

        // 1. Playmogo / DoodStream (Direct High-Speed MP4 via /pass_md5 API)
        if (embedUrl.contains("playmogo.com") || embedUrl.contains("dood") || embedUrl.contains("ds2play")) {
            val html = fetchHtml(embedUrl, "https://pinoymoviepedia.ru/")
            val passMatch = Regex("""/pass_md5/([^\'\"]+)""").find(html)
            val tokenMatch = Regex("""token=([a-zA-Z0-9]+)""").find(html)

            if (passMatch != null) {
                val domain = embedUrl.substringBefore("/e/").substringBefore("/d/")
                val passUrl = "$domain/pass_md5/${passMatch.groupValues[1]}"
                val prefix = fetchHtml(passUrl, embedUrl).trim()

                if (prefix.isNotBlank() && !prefix.startsWith("<")) {
                    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
                    val rand = (1..10).map { chars.random() }.joinToString("")
                    val token = tokenMatch?.groupValues?.get(1) ?: "token"
                    val finalMp4 = "$prefix$rand?token=$token&expiry=${System.currentTimeMillis()}"

                    Log.i(TAG, "Resolved Playmogo direct MP4: $finalMp4")
                    val headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to embedUrl,
                    )
                    return@withContext ResolvedMedia(
                        streams = listOf(
                            Stream(
                                url = finalMp4,
                                kind = StreamKind.PROGRESSIVE,
                                qualityLabel = "720p",
                                height = 720,
                                headers = headers,
                            )
                        ),
                        headers = headers,
                    )
                }
            }
        }

        // 2. MixDrop (Direct High-Speed 1080p MP4 via packed script unpacking)
        if (embedUrl.contains("mixdrop") || embedUrl.contains("miiixdrop")) {
            val html = fetchHtml(embedUrl, "https://pinoymoviepedia.ru/")
            val unpacked = unpackDeanEdwards(html)
            val wurlMatch = Regex("""(?:wurl|vurl)\s*=\s*[\'"]([^\'"]+)[\'"]""").find(unpacked)
                ?: Regex("""//[^\s\'"]+\.(?:mp4|m3u8)[^\s\'"]*""").find(unpacked)

            if (wurlMatch != null) {
                var streamUrl = wurlMatch.groupValues.getOrNull(1) ?: wurlMatch.value
                if (streamUrl.startsWith("//")) {
                    streamUrl = "https:$streamUrl"
                }
                Log.i(TAG, "Resolved MixDrop direct MP4: $streamUrl")
                val headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to embedUrl,
                )
                return@withContext ResolvedMedia(
                    streams = listOf(
                        Stream(
                            url = streamUrl,
                            kind = StreamKind.PROGRESSIVE,
                            qualityLabel = "1080p",
                            height = 1080,
                            headers = headers,
                        )
                    ),
                    headers = headers,
                )
            }
        }

        // 3. StreamHLS (Direct Master HLS via POST /dl)
        if (embedUrl.contains("streamhls.to")) {
            val fileCode = embedUrl.substringAfterLast("/e/")
                .substringAfterLast("/")
                .substringBefore(".html")
                .substringBefore("?")
                .trim()

            val postBody = FormBody.Builder()
                .add("op", "embed")
                .add("file_code", fileCode)
                .add("auto", "1")
                .add("referer", "https://pinoymoviepedia.ru/")
                .build()

            val req = Request.Builder()
                .url("https://streamhls.to/dl")
                .post(postBody)
                .header("User-Agent", USER_AGENT)
                .header("Referer", embedUrl)
                .build()

            val dlHtml = runCatching { http.newCall(req).execute().use { it.body?.string().orEmpty() } }.getOrDefault("")
            val m3u8Match = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(dlHtml)
                ?: Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(fetchHtml(embedUrl, "https://pinoymoviepedia.ru/"))

            if (m3u8Match != null) {
                val m3u8Url = m3u8Match.value
                Log.i(TAG, "Resolved StreamHLS m3u8: $m3u8Url")
                val headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://streamhls.to/",
                )
                return@withContext ResolvedMedia(
                    streams = listOf(
                        Stream(
                            url = m3u8Url,
                            kind = StreamKind.HLS,
                            qualityLabel = "1080p",
                            height = 1080,
                            headers = headers,
                        )
                    ),
                    headers = headers,
                )
            }
        }

        // 4. ABStream (Direct Master HLS with strict Referer)
        if (embedUrl.contains("abstream.to")) {
            val html = fetchHtml(embedUrl, "https://pinoymoviepedia.ru/")
            val m3u8Match = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(html)
            if (m3u8Match != null) {
                val m3u8Url = m3u8Match.value
                Log.i(TAG, "Resolved ABStream m3u8: $m3u8Url")
                val headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://abstream.to/",
                )
                return@withContext ResolvedMedia(
                    streams = listOf(
                        Stream(
                            url = m3u8Url,
                            kind = StreamKind.HLS,
                            qualityLabel = "1080p",
                            height = 1080,
                            headers = headers,
                        )
                    ),
                    headers = headers,
                )
            }
        }

        // 5. VOE (Redirects to shanelocal / HLS stream)
        if (embedUrl.contains("voe.sx")) {
            val voeHtml = fetchHtml(embedUrl)
            val redir = Regex("""window\.location\.href\s*=\s*[\'"]([^\'"]+)[\'"]""").find(voeHtml)?.groupValues?.get(1)
            val targetUrl = redir ?: embedUrl
            val targetHtml = fetchHtml(targetUrl)

            val hlsMatch = Regex("""['"]hls['"]\s*:\s*['"]([^'"]+)['"]""").find(targetHtml)
                ?: Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(targetHtml)

            if (hlsMatch != null) {
                val hlsUrl = hlsMatch.groupValues.getOrNull(1) ?: hlsMatch.value
                Log.i(TAG, "Resolved VOE m3u8: $hlsUrl")
                val headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to targetUrl,
                )
                return@withContext ResolvedMedia(
                    streams = listOf(
                        Stream(
                            url = hlsUrl,
                            kind = StreamKind.HLS,
                            qualityLabel = "1080p",
                            height = 1080,
                            headers = headers,
                        )
                    ),
                    headers = headers,
                )
            }
        }

        // Fallback: If embed is an m3u8 or video file directly
        if (embedUrl.contains(".m3u8")) {
            val headers = mapOf("User-Agent" to USER_AGENT)
            return@withContext ResolvedMedia(
                streams = listOf(
                    Stream(
                        url = embedUrl,
                        kind = StreamKind.HLS,
                        qualityLabel = "HD",
                        headers = headers,
                    )
                ),
                headers = headers,
            )
        }

        throw SourceException(SourceErrorKind.SERVER_ERROR, "Could not extract direct video from server: $embedUrl")
    }

    private fun unpackDeanEdwards(packedJs: String): String {
        val m = Regex("""\}\s*\(\s*'([\s\S]+?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([\s\S]+?)'\.split\('\|'\)""").find(packedJs) ?: return packedJs
        val p = m.groupValues[1]
        val a = m.groupValues[2].toIntOrNull() ?: 62
        val c = m.groupValues[3].toIntOrNull() ?: 0
        val k = m.groupValues[4].split('|')

        fun encode(d: Int): String {
            val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            return if (d < a) {
                if (d < digits.length) digits[d].toString() else ""
            } else {
                encode(d / a) + (if (d % a < digits.length) digits[d % a].toString() else "")
            }
        }

        val dict = mutableMapOf<String, String>()
        for (i in 0 until c) {
            val key = encode(i)
            val v = k.getOrNull(i)
            dict[key] = if (!v.isNullOrBlank()) v else key
        }

        return Regex("""\b\w+\b""").replace(p) { match ->
            dict[match.value] ?: match.value
        }
    }

    private fun fetchHtml(url: String, referer: String? = null): String {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
        if (!referer.isNullOrBlank()) {
            reqBuilder.header("Referer", referer)
        }
        val res = http.newCall(reqBuilder.build()).execute()
        return res.use { it.body?.string().orEmpty() }
    }

    companion object {
        private const val TAG = "PinoyMoviePediaSource"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
