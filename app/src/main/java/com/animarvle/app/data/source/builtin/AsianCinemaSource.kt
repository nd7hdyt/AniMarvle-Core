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
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Built-in native scraper for Asian 18+ Cinema:
 * 1. Korean 18+ Cinema & Erotic Dramas (Cat3Movie, KissKH)
 * 2. Japanese Adult (JAV, Censored / Uncensored / Pink Films / Roman Porno via JavMost & Jav.com.co)
 *
 * Scrapes and resolves direct high-speed streams:
 * - SoopLive / AfreecaTV Korean CDN (Cat3Movie Master HLS)
 * - Direct Progressive MP4 (Cat3Movie & cdn.mostplayer.com)
 * - VidHide Master HLS (Jav.com.co via acek-cdn.com)
 * - DooPlayer Master HLS (JavMost via mostplayer.com)
 */
class AsianCinemaSource(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build(),
) : MediaSource {

    override val id: Long = 40006L
    override val name: String = "Asian Cinema (18+)"
    override val language: String = "ja"
    override val supportedTypes: Set<MediaType> = setOf(MediaType.MOVIE, MediaType.ANIME)

    override val capabilities: SourceCapabilities = SourceCapabilities(
        sub = true,
        dub = false,
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
            .replace(Regex("""[:\-_#&]"""), " ")
            .trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        Log.i("AsianCinemaSource", "Searching Asian Cinema for: '$query' (clean: '$cleanQuery', type: $type)")

        // 1. Check if the query contains a JAV studio code (e.g. MIDE-500, ADN-165, SW-216, DSOD-122, JUFE-250)
        // Note: "JAV" is a genre tag and not a studio prefix; avoid treating JAV-\d+ as studio codes.
        val codeMatch = Regex("""\b([A-Za-z]{2,6})[-_]?(\d{2,5})\b""").find(query)
        if (codeMatch != null && !codeMatch.groupValues[1].equals("JAV", ignoreCase = true)) {
            val alphaPrefix = codeMatch.groupValues[1]
            val digits = codeMatch.groupValues[2]
            val fullCode = "$alphaPrefix-$digits"

            // Parallel search across JavMost JSON, Jav.com.co, and Cat3Movie
            val mostDef = async { searchJavMostJson(fullCode, type) }
            val javCoDef = async { searchJavComCo(fullCode, type) }
            val cat3Def = async { searchCat3Movie(fullCode, type) }

            val mostRes = mostDef.await()
            val javCoRes = javCoDef.await()
            val cat3Res = cat3Def.await()

            val combined = mostRes + javCoRes + cat3Res
            if (combined.isNotEmpty()) {
                Log.i("AsianCinemaSource", "Found ${combined.size} cards for studio code '$fullCode'")
                combined.forEach { com.animarvle.app.AnimarvleApp.registerAsianAdult(it.title) }
                return@withContext combined
            }

            // Fallback: search studio prefix on JavMost and filter by digits
            val prefixResults = searchJavMostJson(alphaPrefix, type)
            val filteredPrefix = prefixResults.filter { it.title.contains(digits, ignoreCase = true) }
            if (filteredPrefix.isNotEmpty()) {
                Log.i("AsianCinemaSource", "Found ${filteredPrefix.size} cards matching digits '$digits' under prefix '$alphaPrefix'")
                filteredPrefix.forEach { com.animarvle.app.AnimarvleApp.registerAsianAdult(it.title) }
                return@withContext filteredPrefix
            }
        }

        // 2. Parallel search for full cleaned title across Cat3Movie, JavMost, and Jav.com.co
        val cat3Def = async { searchCat3Movie(cleanQuery, type) }
        val mostTitleDef = async { searchJavMostJson(cleanQuery, type) }
        val javCoTitleDef = async { searchJavComCo(cleanQuery, type) }

        val cat3Res = cat3Def.await()
        val mostTitleRes = mostTitleDef.await()
        val javCoTitleRes = javCoTitleDef.await()

        if (cat3Res.isNotEmpty() || mostTitleRes.isNotEmpty() || javCoTitleRes.isNotEmpty()) {
            val combined = cat3Res + mostTitleRes + javCoTitleRes
            Log.i("AsianCinemaSource", "Found ${combined.size} cards for '$cleanQuery'")
            combined.forEach { com.animarvle.app.AnimarvleApp.registerAsianAdult(it.title) }
            return@withContext combined
        }

        // 3. If query had a subtitle delimiter (e.g. "Sweet Whip: ..."), search main title
        val mainTitle = query.split(Regex("""[:\-]""")).firstOrNull()?.trim()
        if (!mainTitle.isNullOrBlank() && mainTitle.length >= 3 && mainTitle != cleanQuery) {
            val mainCat3 = searchCat3Movie(mainTitle, type)
            val mainJavMost = searchJavMostJson(mainTitle, type)
            val mainJavCo = searchJavComCo(mainTitle, type)
            val mainCombined = mainCat3 + mainJavMost + mainJavCo
            if (mainCombined.isNotEmpty()) {
                Log.i("AsianCinemaSource", "Found ${mainCombined.size} cards for main title '$mainTitle'")
                mainCombined.forEach { com.animarvle.app.AnimarvleApp.registerAsianAdult(it.title) }
                return@withContext mainCombined
            }
        }

        // 4. Try first word or first two words (e.g. "Sweet Whip" -> "Sweet")
        val words = cleanQuery.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.size >= 2) {
            val firstWord = words[0]
            if (firstWord.length >= 4) {
                val subResults = searchCat3Movie(firstWord, type) + searchJavMostJson(firstWord, type) + searchJavComCo(firstWord, type)
                if (subResults.isNotEmpty()) {
                    Log.i("AsianCinemaSource", "Found ${subResults.size} cards for '$firstWord'")
                    subResults.forEach { com.animarvle.app.AnimarvleApp.registerAsianAdult(it.title) }
                    return@withContext subResults
                }
            }
        }

        Log.w("AsianCinemaSource", "No cards found on Asian Cinema for '$query'")
        emptyList()
    }

    // --- Cat3Movie Scraper ----------------------------------------------------------------------

    private fun cleanCat3Title(rawTitle: String): String {
        val clean = rawTitle.cleanHtml()
            .replace("&#8217;", "'")
            .replace("&hellip;", "...")
            .replace("&amp;", "&")
            .replace("&#8211;", "-")
            .trim()
        val parts = clean.split("|").map { it.trim() }.filter { it.isNotBlank() }
        val candidate = if (parts.size >= 2) {
            val firstUpper = parts[0].uppercase()
            if (firstUpper in setOf("VIVAMAX", "TBONX", "ATIKCHUCHU", "PCH", "HOTSHOT", "TBON")) {
                parts[1]
            } else {
                parts[0]
            }
        } else {
            parts.firstOrNull() ?: clean
        }
        return candidate
            .replace(Regex("""\s*-\s*Full (?:PHILIPINE|CHINA|KOREAN|JAPAN|THAI|VIVAMAX|TBONX).*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*Watch Movie Online.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*Free Sexy Movies.*""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun searchCat3Movie(query: String, type: MediaType): List<SourceMedia> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$CAT3_DOMAIN/?s=$encoded"
        val html = runCatching { fetchHtml(url, CAT3_DOMAIN) }.getOrNull() ?: return emptyList()

        val results = mutableListOf<SourceMedia>()
        val seen = mutableSetOf<String>()
        val queryWords = query.lowercase().split(" ").filter { it.length >= 3 }

        val articleRegex = Regex("""<article[^>]*>([\s\S]*?)</article\s*>""", RegexOption.IGNORE_CASE)
        val titleRegex = Regex("""<h2 class="title">\s*<a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        val backupLinkRegex = Regex("""<a\s+[^>]*href=["'](https?://(?:www\.)?cat3movie\.club/[^"']+)["'][^>]*title=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val imgRegex = Regex("""(?:data-src|data-original|data-bsrjs|src)=["'](https?://[^"']+\.(?:jpg|jpeg|png|webp)[^"']*)["']""", RegexOption.IGNORE_CASE)

        for (match in articleRegex.findAll(html)) {
            val block = match.groupValues[1]
            val tMatch = titleRegex.find(block)
            val (link, rawTitle) = if (tMatch != null) {
                tMatch.groupValues[1].trim() to tMatch.groupValues[2].trim()
            } else {
                val bMatch = backupLinkRegex.find(block) ?: continue
                bMatch.groupValues[1].trim() to bMatch.groupValues[2].trim()
            }

            if (!seen.add(link) || link.contains("/category/") || link.contains("/tag/")) continue

            val poster = imgRegex.find(block)?.groupValues?.get(1)?.trim()
            val textTitle = rawTitle.cleanHtml().replace("&#8217;", "'").replace("&hellip;", "...").replace("&amp;", "&").trim()
            val cleanTitle = cleanCat3Title(rawTitle)
            val lowerTitle = textTitle.lowercase()
            val lowerClean = cleanTitle.lowercase()
            if (queryWords.isNotEmpty() && !queryWords.any { lowerTitle.contains(it) || lowerClean.contains(it) }) continue

            val year = Regex("""(?:19|20)\d{2}""").find(textTitle)?.value?.toIntOrNull()
                ?: Regex("""(?:19|20)\d{2}""").find(cleanTitle)?.value?.toIntOrNull()

            results.add(
                SourceMedia(
                    url = link,
                    title = cleanTitle,
                    year = year,
                    episodeCount = 1,
                    poster = poster?.takeIf { it.isNotBlank() },
                    type = type,
                    extra = mapOf("mediaUrl" to link, "provider" to "cat3movie"),
                )
            )
        }
        return results
    }

    // --- JavMost Scraper ------------------------------------------------------------------------

    private fun searchJavMostJson(query: String, type: MediaType): List<SourceMedia> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$PRIMARY_DOMAIN/showlist2/$encoded/1/search/"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "$PRIMARY_DOMAIN/")
            .build()

        val jsonStr = runCatching {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull() ?: return emptyList()

        val results = mutableListOf<SourceMedia>()
        val seen = mutableSetOf<String>()
        runCatching {
            val root = JSONObject(jsonStr)
            val arr = root.optJSONArray("result") ?: return@runCatching
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val fullUrl = obj.optString("url")
                if (fullUrl.isBlank() || !seen.add(fullUrl)) continue
                val name = obj.optString("name").cleanHtml()
                val fullName = obj.optString("full_name").cleanHtml()
                if (name.matches(Regex("""^JAV-\d+$""", RegexOption.IGNORE_CASE)) || fullUrl.contains(Regex("""/JAV-\d+/?$""", RegexOption.IGNORE_CASE))) {
                    continue
                }
                val isUncensored = fullUrl.contains("uncensored", ignoreCase = true) ||
                    name.contains("uncensored", ignoreCase = true) ||
                    fullName.contains("uncensored", ignoreCase = true)
                val badge = if (isUncensored) "[Uncensored] " else ""
                val title = when {
                    name.isNotBlank() && fullName.isNotBlank() && !fullName.startsWith(name, ignoreCase = true) -> "$badge$name $fullName"
                    fullName.isNotBlank() -> "$badge$fullName"
                    else -> "$badge$name"
                }.trim()
                val cover = obj.optString("cover").takeIf { it.isNotBlank() }
                val year = Regex("""(?:19|20)\d{2}""").find(obj.optString("release"))?.value?.toIntOrNull()
                    ?: Regex("""(?:19|20)\d{2}""").find(title)?.value?.toIntOrNull()

                results.add(
                    SourceMedia(
                        url = fullUrl,
                        title = title,
                        year = year,
                        episodeCount = 1,
                        poster = cover,
                        type = type,
                        extra = mapOf("mediaUrl" to fullUrl, "provider" to "javmost"),
                    )
                )
            }
        }
        return results
    }

    // --- Jav.com.co Scraper ---------------------------------------------------------------------

    private fun searchJavComCo(query: String, type: MediaType): List<SourceMedia> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$JAVCOMCO_DOMAIN/?s=$encoded"
        val html = runCatching { fetchHtml(url, JAVCOMCO_DOMAIN) }.getOrNull() ?: return emptyList()
        val articleRegex = Regex("""<article[^>]*>([\s\S]*?)</article>""")
        val linkRegex = Regex("""href="([^"]+)"""")
        val titleRegex = Regex("""title="([^"]+)"""")
        val imgRegex = Regex("""(?:data-src|src)="([^"]+)"""")

        val results = mutableListOf<SourceMedia>()
        val seen = mutableSetOf<String>()
        val cleanQ = query.lowercase().replace("-", "").trim()

        for (match in articleRegex.findAll(html)) {
            val articleHtml = match.groupValues[1]
            val link = linkRegex.find(articleHtml)?.groupValues?.get(1)?.trim() ?: continue
            val rawTitle = titleRegex.find(articleHtml)?.groupValues?.get(1)?.cleanHtml().orEmpty()
            if (rawTitle.isBlank()) continue

            val cleanTitle = rawTitle.lowercase().replace("-", "")
            if (!cleanTitle.contains(cleanQ) && !cleanQ.split(" ").any { it.length >= 4 && cleanTitle.contains(it) }) {
                continue
            }

            if (!seen.add(link)) continue
            val cover = imgRegex.findAll(articleHtml).map { it.groupValues[1] }.firstOrNull { it.startsWith("http") }
            val year = Regex("""(?:19|20)\d{2}""").find(rawTitle)?.value?.toIntOrNull()

            results.add(
                SourceMedia(
                    url = link,
                    title = rawTitle,
                    year = year,
                    episodeCount = 1,
                    poster = cover,
                    type = type,
                    extra = mapOf("mediaUrl" to link, "provider" to "javcomco"),
                )
            )
        }
        return results
    }

    // --- Episodes -------------------------------------------------------------------------------

    override suspend fun episodes(media: SourceMedia): List<SourceEpisode> = withContext(Dispatchers.IO) {
        listOf(
            SourceEpisode(
                number = 1f,
                title = media.title,
                url = media.url,
                thumbnail = media.poster,
                extra = mapOf("mediaUrl" to media.url),
            )
        )
    }

    // --- Options --------------------------------------------------------------------------------

    override suspend fun options(episode: SourceEpisode): EpisodeOptions = withContext(Dispatchers.IO) {
        val pageUrl = episode.extra["mediaUrl"] ?: episode.url
        val isCat3Page = pageUrl.contains("cat3movie.club")

        val cat3Option = ServerOption(
            key = "server:cat3",
            label = "Cat3 Direct HD",
            hint = "Instant 800ms Direct CDN",
            audioLanguage = if (isCat3Page) "Korean / Japanese" else "Multi",
        )
        val vidhideOption = ServerOption(
            key = "server:vidhide",
            label = "VidHide / DooPlayer HD",
            hint = "Multi-Host Stream",
            audioLanguage = "Japanese",
        )

        // Always list Cat3 Direct as primary server option so it resolves first with instant 800ms start,
        // with VidHide / DooPlayer as secondary option.
        val serverList = listOf(cat3Option, vidhideOption)

        EpisodeOptions(
            byAudio = mapOf(
                Audio.SUB to serverList
            )
        )
    }

    // --- Resolve --------------------------------------------------------------------------------

    override suspend fun resolve(episode: SourceEpisode, choice: Choice): ResolvedMedia = withContext(Dispatchers.IO) {
        val episodeUrl = episode.extra["mediaUrl"] ?: episode.url
        val serverKey = choice.serverKey.lowercase()

        val codeMatch = Regex("""\b([A-Za-z]{2,6})[-_]?(\d{2,5})\b""").find(episodeUrl)
            ?: Regex("""\b([A-Za-z]{2,6})[-_]?(\d{2,5})\b""").find(episode.title.orEmpty())
        val code = if (codeMatch != null && !codeMatch.groupValues[1].equals("JAV", ignoreCase = true)) {
            "${codeMatch.groupValues[1]}-${codeMatch.groupValues[2]}"
        } else null

        val cleanKey = serverKey.removePrefix("server:")

        // 1. Explicit Cat3 Direct server requested
        if (cleanKey.contains("cat3") || cleanKey == "cat3_direct") {
            if (episodeUrl.contains("cat3movie.club")) {
                val cat3Media = resolveCat3(episodeUrl)
                if (cat3Media != null) return@withContext cat3Media
            } else {
                val cat3Media = resolveCat3ByTitleOrCode(episode.title.orEmpty(), code)
                if (cat3Media != null) return@withContext cat3Media
            }
            throw SourceException(SourceErrorKind.NOT_FOUND, "Stream not available on Cat3 Direct server")
        }

        // 2. Explicit VidHide server requested
        if (cleanKey.contains("vidhide") || cleanKey.contains("dooplayer")) {
            return@withContext resolveVidHidePipeline(episode, episodeUrl, code)
        }

        // 3. Default / unspecified server key
        if (episodeUrl.contains("cat3movie.club")) {
            val cat3Media = resolveCat3(episodeUrl)
            if (cat3Media != null) return@withContext cat3Media
            return@withContext resolveVidHidePipeline(episode, episodeUrl, code)
        }

        // Prioritize instant 800ms Cat3 Direct CDN for non-cat3 cards as well
        val cat3First = resolveCat3ByTitleOrCode(episode.title.orEmpty(), code)
        if (cat3First != null) {
            Log.i("AsianCinemaSource", "Auto-resolved Asian adult title via Cat3Movie high-speed CDN: ${cat3First.streams.firstOrNull()?.url}")
            return@withContext cat3First
        }

        return@withContext resolveVidHidePipeline(episode, episodeUrl, code)
    }

    private fun resolveCat3(pageUrl: String): ResolvedMedia? {
        val pageHtml = runCatching { fetchHtml(pageUrl, CAT3_DOMAIN) }.getOrNull() ?: return null
        val m3u8Url = Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(pageHtml)?.groupValues?.get(1)
            ?: Regex("""https?://[^\s"\'<>]+sooplive[^\s"\'<>]*\.m3u8[^\s"\'<>]*""").find(pageHtml)?.value
            ?: Regex("""https?://[^\s"\'<>]+\.m3u8[^\s"\'<>]*""").find(pageHtml)?.value

        if (m3u8Url != null) {
            Log.i("AsianCinemaSource", "Resolved Cat3Movie HLS stream: $m3u8Url")
            return ResolvedMedia(
                streams = listOf(
                    Stream(
                        url = m3u8Url,
                        kind = StreamKind.HLS,
                        qualityLabel = "1080p HD",
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "$CAT3_DOMAIN/",
                        ),
                    )
                ),
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$CAT3_DOMAIN/",
                ),
            )
        }

        val mp4Url = Regex("""(?:file\s*:\s*|src\s*=\s*)["'](https?://[^"']+\.mp4[^"']*)["']""").find(pageHtml)?.groupValues?.get(1)
            ?: Regex("""https?://[^\s"\'<>]+bigo\.sg[^\s"\'<>]*\.mp4[^\s"\'<>]*""").find(pageHtml)?.value
            ?: Regex("""https?://[^\s"\'<>]+\.mp4[^\s"\'<>]*""").find(pageHtml)?.value

        if (mp4Url != null) {
            Log.i("AsianCinemaSource", "Resolved Cat3Movie MP4 stream: $mp4Url")
            return ResolvedMedia(
                streams = listOf(
                    Stream(
                        url = mp4Url,
                        kind = StreamKind.PROGRESSIVE,
                        qualityLabel = "1080p HD",
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "$CAT3_DOMAIN/",
                        ),
                    )
                ),
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$CAT3_DOMAIN/",
                ),
            )
        }

        // Check if iframe embed
        val iframeUrl = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(pageHtml)?.groupValues?.get(1)
        if (iframeUrl != null) {
            val embedHtml = runCatching {
                val req = Request.Builder().url(iframeUrl).header("Referer", pageUrl).build()
                http.newCall(req).execute().use { it.body?.string().orEmpty() }
            }.getOrDefault("")
            val embedM3u8 = Regex("""https?://[^\s"\'<>]+\.m3u8[^\s"\'<>]*""").find(embedHtml)?.value
            if (embedM3u8 != null) {
                return ResolvedMedia(
                    streams = listOf(
                        Stream(
                            url = embedM3u8,
                            kind = StreamKind.HLS,
                            qualityLabel = "1080p HD",
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to iframeUrl,
                            ),
                        )
                    ),
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to iframeUrl,
                    ),
                )
            }
        }

        return null
    }

    private fun resolveCat3ByTitleOrCode(title: String, code: String?): ResolvedMedia? {
        if (!code.isNullOrBlank()) {
            val codeResults = searchCat3Movie(code, MediaType.MOVIE)
            for (card in codeResults) {
                val res = resolveCat3(card.url)
                if (res != null) return res
            }
        }
        val clean = cleanCat3Title(title)
        if (clean.isNotBlank()) {
            val titleResults = searchCat3Movie(clean, MediaType.MOVIE)
            for (card in titleResults) {
                val res = resolveCat3(card.url)
                if (res != null) return res
            }
            val words = clean.split(Regex("""\s+""")).filter { it.length >= 4 }
            if (words.size >= 2) {
                val shortQuery = words.take(2).joinToString(" ")
                val shortResults = searchCat3Movie(shortQuery, MediaType.MOVIE)
                for (card in shortResults) {
                    val res = resolveCat3(card.url)
                    if (res != null) return res
                }
            }
        }
        return null
    }

    private suspend fun resolveVidHidePipeline(
        episode: SourceEpisode,
        episodeUrl: String,
        code: String?,
    ): ResolvedMedia = withContext(Dispatchers.IO) {
        if (episodeUrl.contains("jav.com.co")) {
            return@withContext resolveJavComCo(episodeUrl)
        }

        if (episodeUrl.contains("javmenu.com")) {
            if (code != null) {
                val jm = resolveJavMenu(code)
                if (jm != null) return@withContext jm
            }
        }

        var javUrl = episodeUrl
        if (episodeUrl.contains("cat3movie.club")) {
            val candidateCards = if (code != null) searchJavMostJson(code, MediaType.MOVIE) else emptyList()
            val bestCard = candidateCards.firstOrNull()
                ?: searchJavMostJson(cleanCat3Title(episode.title.orEmpty()), MediaType.MOVIE).firstOrNull()
            if (bestCard != null) {
                javUrl = bestCard.url
            } else if (code != null) {
                val javCoCards = searchJavComCo(code, MediaType.MOVIE)
                val jcCard = javCoCards.firstOrNull()
                if (jcCard != null) {
                    return@withContext resolveJavComCo(jcCard.url)
                }
                val jm = resolveJavMenu(code)
                if (jm != null) return@withContext jm
            }
        }

        if (javUrl.contains("cat3movie.club")) {
            val cat3Media = resolveCat3(episodeUrl)
            if (cat3Media != null) return@withContext cat3Media
            throw SourceException(SourceErrorKind.NOT_FOUND, "Could not find VidHide mirror for this Cat3 title")
        }

        return@withContext resolveJavMost(javUrl, episode, code)
    }

    private suspend fun resolveJavMost(
        episodeUrl: String,
        episode: SourceEpisode,
        code: String?,
    ): ResolvedMedia = withContext(Dispatchers.IO) {
        val html = fetchHtml(episodeUrl, PRIMARY_DOMAIN)

        val y1 = Regex("""var\s+YWRzMQo\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            ?: throw SourceException(SourceErrorKind.SERVER_ERROR, "Missing y1 security token")
        val defaultY2 = Regex("""var\s+YWRzMg\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            ?: throw SourceException(SourceErrorKind.SERVER_ERROR, "Missing y2 security token")
        val defaultY4 = Regex("""var\s+YWRzNA\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            ?: throw SourceException(SourceErrorKind.SERVER_ERROR, "Missing y4 security token")
        val defaultY5 = Regex("""var\s+YWRzNQ\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            ?: throw SourceException(SourceErrorKind.SERVER_ERROR, "Missing y5 security token")
        val defaultY6 = Regex("""var\s+YWRzNg\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            ?: throw SourceException(SourceErrorKind.SERVER_ERROR, "Missing y6 security token")
        val endpoint = Regex("""url_source\s*=\s*YREdIr\s*\+\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            ?: throw SourceException(SourceErrorKind.SERVER_ERROR, "Missing endpoint parameter")

        data class JavCandidate(val group: String, val y4: String, val y5: String, val y6: String)
        val candidates = mutableListOf<JavCandidate>()

        val btnMatches = Regex("""select_part\s*\(\s*'1'\s*,\s*'([^']+)'\s*,\s*this\s*,\s*'parent'\s*,\s*'([^']+)'\s*,\s*'([^']+)'\s*,\s*'([^']+)'\s*\)""").findAll(html)
        for (m in btnMatches) {
            candidates.add(JavCandidate(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4]))
        }
        if (!candidates.any { it.group == defaultY2 }) {
            candidates.add(JavCandidate(defaultY2, defaultY4, defaultY5, defaultY6))
        }

        val sortedCandidates = candidates.sortedWith(compareByDescending { c ->
            when (c.group) {
                "54" -> 100 // MostPlayer
                "60" -> 80
                "46" -> 60
                else -> 10
            }
        })

        val domain = if (episodeUrl.contains("javmost.cx")) FALLBACK_DOMAIN else PRIMARY_DOMAIN

        for (candidate in sortedCandidates) {
            val resolved = runCatching {
                val postBody = FormBody.Builder()
                    .add("group", candidate.group)
                    .add("part", "1")
                    .add("code", candidate.y4)
                    .add("code2", candidate.y5)
                    .add("code3", candidate.y6)
                    .add("value", y1)
                    .add("sound", "av")
                    .build()

                val ajaxReq = Request.Builder()
                    .url("$domain/$endpoint")
                    .post(postBody)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", episodeUrl)
                    .header("Origin", domain)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .build()

                val ajaxJson = http.newCall(ajaxReq).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    JSONObject(resp.body?.string().orEmpty())
                } ?: return@runCatching null

                val embedUrl = ajaxJson.optJSONArray("data")?.optString(0)?.takeIf { it.isNotBlank() }
                    ?: return@runCatching null

                Log.i("AsianCinemaSource", "Candidate server ${candidate.group} embed: $embedUrl")

                if (embedUrl.contains("dooplayer.com") || embedUrl.contains("mostplayer.com")) {
                    val embedReq = Request.Builder()
                        .url(embedUrl)
                        .get()
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", episodeUrl)
                        .header("Origin", domain)
                        .header("Sec-Fetch-Dest", "iframe")
                        .header("Sec-Fetch-Mode", "navigate")
                        .header("Sec-Fetch-Site", "cross-site")
                        .build()

                    val embedHtml = http.newCall(embedReq).execute().use { resp ->
                        if (!resp.isSuccessful) return@runCatching null
                        resp.body?.string().orEmpty()
                    }

                    val token = extractMeta("x-embed-token", embedHtml) ?: return@runCatching null
                    val et = extractMeta("x-embed-et", embedHtml) ?: return@runCatching null
                    val sig = extractMeta("x-embed-sig", embedHtml) ?: return@runCatching null

                    val host = runCatching { URI(embedUrl).host }.getOrNull() ?: "www.mostplayer.com"
                    val streamApiUrl = "https://$host/api/stream/$token"
                    val jsonBody = JSONObject().put("ref", "https://$host").toString()
                        .toRequestBody("application/json".toMediaType())

                    val streamReq = Request.Builder()
                        .url(streamApiUrl)
                        .post(jsonBody)
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", embedUrl)
                        .header("Origin", "https://$host")
                        .header("Content-Type", "application/json")
                        .header("X-Embed-Auth", "1")
                        .header("X-Embed-ET", et)
                        .header("X-Embed-SIG", sig)
                        .build()

                    val streamJson = http.newCall(streamReq).execute().use { resp ->
                        if (!resp.isSuccessful) return@runCatching null
                        JSONObject(resp.body?.string().orEmpty())
                    } ?: return@runCatching null

                    val streamUrl = streamJson.optString("url").takeIf { it.isNotBlank() }
                        ?: return@runCatching null

                    if (streamUrl.contains("wowstream.cloud")) {
                        Log.w("AsianCinemaSource", "Rejecting dead wowstream.cloud stream URL: $streamUrl")
                        return@runCatching null
                    }

                    // Fast probe candidate stream URL to verify it is not 404/410 dead
                    val isDead = runCatching {
                        val pReq = Request.Builder()
                            .url(streamUrl)
                            .get()
                            .header("User-Agent", USER_AGENT)
                            .header("Referer", embedUrl)
                            .header("Origin", "https://$host")
                            .header("Range", "bytes=0-100")
                            .build()
                        val pClient = http.newBuilder()
                            .connectTimeout(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
                            .readTimeout(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
                            .build()
                        pClient.newCall(pReq).execute().use { resp ->
                            resp.code == 404 || resp.code == 410
                        }
                    }.getOrDefault(false)

                    if (isDead) {
                        Log.w("AsianCinemaSource", "Stream candidate on server ${candidate.group} returned 404/410 dead: $streamUrl")
                        return@runCatching null
                    }

                    Log.i("AsianCinemaSource", "Successfully resolved and verified stream on server ${candidate.group}: $streamUrl")
                    val isHls = streamUrl.contains(".m3u8")

                    ResolvedMedia(
                        streams = listOf(
                            Stream(
                                url = streamUrl,
                                kind = if (isHls) StreamKind.HLS else StreamKind.PROGRESSIVE,
                                qualityLabel = "1080p HD",
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to embedUrl,
                                    "Origin" to "https://$host",
                                ),
                            )
                        ),
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to embedUrl,
                            "Origin" to "https://$host",
                        ),
                    )
                } else null
            }.getOrNull()

            if (resolved != null) {
                return@withContext resolved
            }
        }

        // Fallback: Check Cat3Movie first, then JavMenu and jav.com.co
        if (code != null) {
            val cat3Fallback = runCatching { resolveCat3ByTitleOrCode(episode.title.orEmpty(), code) }.getOrNull()
            if (cat3Fallback != null) {
                Log.i("AsianCinemaSource", "JavMost servers failed, recovered via Cat3Movie high-speed CDN for $code")
                return@withContext cat3Fallback
            }

            Log.i("AsianCinemaSource", "JavMost servers failed or dead, falling back to JavMenu for $code")
            val jmResolved = runCatching { resolveJavMenu(code) }.getOrNull()
            if (jmResolved != null) return@withContext jmResolved

            Log.i("AsianCinemaSource", "JavMenu failed, falling back to Jav.com.co for $code")
            val fallbackCards = searchJavComCo(code, MediaType.MOVIE)
            for (card in fallbackCards) {
                val fbResolved = runCatching { resolveJavComCo(card.url) }.getOrNull()
                if (fbResolved != null) return@withContext fbResolved
            }
        } else {
            val cat3Fallback = runCatching { resolveCat3ByTitleOrCode(episode.title.orEmpty(), null) }.getOrNull()
            if (cat3Fallback != null) {
                Log.i("AsianCinemaSource", "JavMost servers failed, recovered via Cat3Movie high-speed CDN for '${episode.title}'")
                return@withContext cat3Fallback
            }
        }

        throw SourceException(SourceErrorKind.NOT_FOUND, "Could not resolve stream from any JavMost/VidHide server")
    }

    // --- Helpers --------------------------------------------------------------------------------

    private fun fetchHtml(url: String, referer: String): String {
        htmlCache[url]?.let { return it }
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .build()
        val body = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw SourceException(SourceErrorKind.NETWORK, "HTTP ${resp.code} for $url")
            resp.body?.string().orEmpty()
        }
        if (body.isNotBlank()) {
            htmlCache[url] = body
        }
        return body
    }

    private suspend fun resolveJavMenu(code: String): ResolvedMedia? = withContext(Dispatchers.IO) {
        val cleanCode = code.trim().uppercase()
        val url = "https://javmenu.com/en/$cleanCode"
        Log.i("AsianCinemaSource", "Attempting JavMenu resolution: $url")
        val html = runCatching {
            val req = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://javmenu.com/en/search/$cleanCode")
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string().orEmpty() else null
            }
        }.getOrNull() ?: return@withContext null

        val candidates = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(html)
            .map { it.value }
            .distinct()
            .toList()

        for (m3u8 in candidates) {
            val isWorking = runCatching {
                val probeReq = Request.Builder()
                    .url(m3u8)
                    .get()
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://javmenu.com/")
                    .build()
                val probeClient = http.newBuilder()
                    .connectTimeout(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
                probeClient.newCall(probeReq).execute().use { resp ->
                    resp.isSuccessful || resp.code == 416 || resp.code in 300..399
                }
            }.getOrDefault(false)

            if (isWorking) {
                Log.i("AsianCinemaSource", "Verified live JavMenu HLS stream: $m3u8")
                return@withContext ResolvedMedia(
                    streams = listOf(
                        Stream(
                            url = m3u8,
                            kind = StreamKind.HLS,
                            qualityLabel = "HD 720p/1080p",
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "https://javmenu.com/",
                            ),
                        )
                    ),
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "https://javmenu.com/",
                    ),
                )
            }
        }
        null
    }

    private suspend fun resolveJavComCo(pageUrl: String): ResolvedMedia = withContext(Dispatchers.IO) {
        val pageHtml = fetchHtml(pageUrl, JAVCOMCO_DOMAIN)
        val iframeUrl = Regex("""<iframe[^>]+src="([^"]+)"""").find(pageHtml)?.groupValues?.get(1)
            ?: throw SourceException(SourceErrorKind.NOT_FOUND, "No embed player found on jav.com.co")

        val iframeReq = Request.Builder()
            .url(iframeUrl)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Referer", pageUrl)
            .build()

        val iframeHtml = http.newCall(iframeReq).execute().use { resp ->
            if (!resp.isSuccessful) throw SourceException(SourceErrorKind.NETWORK, "VidHide player error: ${resp.code}")
            resp.body?.string().orEmpty()
        }

        val m3u8Url = unpackVidHide(iframeHtml, iframeUrl)
            ?: Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(iframeHtml)?.value
            ?: throw SourceException(SourceErrorKind.NOT_FOUND, "Could not resolve stream from VidHide player")

        // Fast probe VidHide m3u8 to verify server is not 404/410 dead
        val isVidHideDead = runCatching {
            val pReq = Request.Builder()
                .url(m3u8Url)
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Referer", iframeUrl)
                .build()
            val pClient = http.newBuilder()
                .connectTimeout(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
            pClient.newCall(pReq).execute().use { resp ->
                resp.code == 404 || resp.code == 410
            }
        }.getOrDefault(false)

        if (isVidHideDead) {
            throw SourceException(SourceErrorKind.NOT_FOUND, "VidHide stream removed or deleted (HTTP 404/410)")
        }

        Log.i("AsianCinemaSource", "Resolved and verified jav.com.co VidHide stream: $m3u8Url")

        ResolvedMedia(
            streams = listOf(
                Stream(
                    url = m3u8Url,
                    kind = StreamKind.HLS,
                    qualityLabel = "VidHide 1080p HD",
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to iframeUrl,
                    ),
                )
            ),
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to iframeUrl,
            ),
        )
    }

    private fun extractMeta(name: String, doc: String): String? {
        return Regex("""content="([^"]*)"[^>]*name=$name""").find(doc)?.groupValues?.get(1)
            ?: Regex("""name=$name[^>]*content="([^"]*)"""").find(doc)?.groupValues?.get(1)
            ?: Regex("""name=$name\s*content="?([^"\s>]+)""").find(doc)?.groupValues?.get(1)
            ?: Regex("""content="?([^"\s>]+)"?\s*name=$name""").find(doc)?.groupValues?.get(1)
    }

    private fun unpackVidHide(html: String, iframeUrl: String): String? {
        val match = Regex("""eval\(function\(p,a,c,k,e,d\)[\s\S]*?\}\('(.*)',(\d+),(\d+),'(.*)'\.split\('\|'\)""").find(html)
            ?: return null
        val p = match.groupValues[1]
        val a = match.groupValues[2].toIntOrNull() ?: return null
        val c = match.groupValues[3].toIntOrNull() ?: return null
        val k = match.groupValues[4].split('|')

        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val charMap = digits.take(a).mapIndexed { idx, ch -> ch to idx }.toMap()

        val decoded = Regex("""\b\w+\b""").replace(p) { m ->
            val word = m.value
            var v = 0
            var valid = true
            for (ch in word) {
                val digit = charMap[ch]
                if (digit == null) {
                    valid = false
                    break
                }
                v = v * a + digit
            }
            if (valid && v < k.size && k[v].isNotBlank()) k[v] else word
        }

        val iframeUri = runCatching { URI(iframeUrl) }.getOrNull()
        val iframeHost = if (iframeUri != null && !iframeUri.host.isNullOrBlank()) {
            "${iframeUri.scheme ?: "https"}://${iframeUri.host}"
        } else {
            "https://videohidefree.online"
        }

        // JWPlayer setup prioritizes: links.hls4 || links.hls3 || links.hls2
        val hls4 = Regex("""["']hls4["']\s*:\s*["']([^"']+)["']""").find(decoded)?.groupValues?.get(1)
        if (!hls4.isNullOrBlank()) {
            return if (hls4.startsWith("http", ignoreCase = true)) hls4 else "$iframeHost${if (hls4.startsWith("/")) "" else "/"}$hls4"
        }

        val hls3 = Regex("""["']hls3["']\s*:\s*["']([^"']+)["']""").find(decoded)?.groupValues?.get(1)
        if (!hls3.isNullOrBlank()) {
            return if (hls3.startsWith("http", ignoreCase = true)) hls3 else "$iframeHost${if (hls3.startsWith("/")) "" else "/"}$hls3"
        }

        val hls2 = Regex("""["']hls2["']\s*:\s*["']([^"']+)["']""").find(decoded)?.groupValues?.get(1)
        if (!hls2.isNullOrBlank()) {
            return if (hls2.startsWith("http", ignoreCase = true)) hls2 else "$iframeHost${if (hls2.startsWith("/")) "" else "/"}$hls2"
        }

        val fileMatch = Regex("""file\s*:\s*["']([^"']+)["']""").find(decoded)?.groupValues?.get(1)
        if (!fileMatch.isNullOrBlank()) {
            return if (fileMatch.startsWith("http", ignoreCase = true)) fileMatch else "$iframeHost${if (fileMatch.startsWith("/")) "" else "/"}$fileMatch"
        }

        return Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(decoded)?.value
    }
    private fun String.cleanHtml(): String {
        return this.replace(Regex("""<[^>]+>"""), "")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#8217;", "'")
            .replace("&#8211;", "-")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .trim()
    }

    companion object {
        private const val PRIMARY_DOMAIN = "https://www.javmost.ws"
        private const val FALLBACK_DOMAIN = "https://javmost.cx"
        private const val JAVCOMCO_DOMAIN = "https://jav.com.co"
        private const val CAT3_DOMAIN = "https://www.cat3movie.club"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
