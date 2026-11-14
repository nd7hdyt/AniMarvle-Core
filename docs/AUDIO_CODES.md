# Audio, Stream Resolution & M3U8 Proxy Code Architecture

This document provides the complete source implementations for the three requested subsystems:
1. **The `/proxy/m3u8` and Movie Scraper Section** of `serverless-worker/worker.js` (including signed proxy tokens and HLS child playlist routing)
2. **Stream & Audio Track Resolution Pipeline** in `StreamResolver.kt` and `PlayerViewModel.kt` (including multi-track map key isolation, manual override protection, and Anime Sub/Dub pairing)
3. **Audio & Subtitle Track Selector Dialogs** in `PlayerFragment.kt`

---

## 1. Cloudflare Worker: `/proxy/m3u8` & Movie Scraper Engine

**File:** [`serverless-worker/worker.js`](file:///c:/Python/New%20folder/Animarvle-AniLab/serverless-worker/worker.js)

### 1.1 Pre-Hop Redirect Validation, Signed Proxy Token & SSRF Guard

```javascript
// Strict allowlist of verified streaming hosts and dedicated CDN subnets
const ALLOWED_STREAM_DOMAINS = [
  "hakunaymatata.com", "vidlink.pro", "anitaku.to", "anitaku.so", "anitaku.pe", "anitaku.bz",
  "animahd.org", "multiembed.mov", "2embed.cc", "videasy.net", "donghuastream.org",
  "hentaini.com", "hentaimama.io", "animesex.club", "pelisplushd.to", "pelisplus.lat",
  "pelisplus.icu", "asiancinema.me", "pinoymoviepedia.ru", "video-gen.xyz", "driveseed.org",
  "fastdl.workers.dev", "hubcloud.club", "hubcloud.lat", "gdtot.lat", "gdtot.pro",
  "b-cdn.net", "bunnycdn.com"
];

// Map spoken language names to ISO-639-1 codes for PlayerViewModel.rebuildAudioOptions()
const LANG_CODE_MAP = {
  english: "en",
  spanish: "es",
  hindi: "hi",
  german: "de",
  french: "fr",
  russian: "ru",
  portuguese: "pt",
  arabic: "ar",
  italian: "it",
  japanese: "ja",
  korean: "ko",
  chinese: "zh"
};

function isPrivateOrBlockedHost(urlString) {
  try {
    const u = new URL(urlString);
    if (u.protocol !== "http:" && u.protocol !== "https:") return true;
    const host = u.hostname.toLowerCase();
    return (
      host === "localhost" ||
      host === "127.0.0.1" ||
      host === "0.0.0.0" ||
      host === "::1" ||
      host === "metadata.google.internal" ||
      host === "instance-data" ||
      host.startsWith("127.") ||
      host.startsWith("10.") ||
      host.startsWith("192.168.") ||
      host.startsWith("169.254.") ||
      /^172\.(1[6-9]|2[0-9]|3[0-1])\./.test(host) ||
      host.startsWith("fc00:") ||
      host.startsWith("fd00:") ||
      host.startsWith("fe80:")
    );
  } catch (_) {
    return true;
  }
}

// Lightweight deterministic signature helper so CDN child playlists/segments authorized by Worker pass validation
function signProxyUrl(targetUrl, secret = "animarvle-edge-proxy-v1") {
  let hash = 2166136261;
  const input = `${targetUrl}|${secret}`;
  for (let i = 0; i < input.length; i++) {
    hash ^= input.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }
  return (hash >>> 0).toString(16);
}

function isAuthorizedProxyRequest(targetUrl, sig) {
  if (isPrivateOrBlockedHost(targetUrl)) return false;
  if (sig && sig === signProxyUrl(targetUrl)) return true;
  return isAllowedTargetUrl(targetUrl);
}

async function fetchWithManualRedirects(initialUrl, options = {}, timeoutMs = 8000, maxHops = 3, allowPublicHosts = false) {
  let curUrl = initialUrl;
  for (let hop = 0; hop <= maxHops; hop++) {
    if (isPrivateOrBlockedHost(curUrl)) {
      throw new Error(`Target URL ${curUrl} not allowed`);
    }
    if (!allowPublicHosts && !isAllowedTargetUrl(curUrl)) {
      throw new Error(`Target URL ${curUrl} not allowed`);
    }
    const res = await fetchWithTimeout(curUrl, { ...options, redirect: "manual" }, timeoutMs);
    if (res.status >= 301 && res.status <= 308) {
      const location = res.headers.get("Location");
      if (!location) return res;
      const resolved = new URL(location, curUrl).toString();
      if (isPrivateOrBlockedHost(resolved)) {
        throw new Error(`Redirect target ${resolved} not allowed`);
      }
      if (!allowPublicHosts && !isAllowedTargetUrl(resolved)) {
        throw new Error(`Redirect target ${resolved} not allowed`);
      }
      curUrl = resolved;
      continue;
    }
    return res;
  }
  throw new Error("Too many redirects");
}

function isAllowedTargetUrl(urlString) {
  try {
    const u = new URL(urlString);
    if (u.protocol !== "http:" && u.protocol !== "https:") return false;

    // 1. Strict blocking of private, loopback, link-local, cloud metadata IPs & hostnames
    if (isPrivateOrBlockedHost(urlString)) {
      return false;
    }

    const host = u.hostname.toLowerCase();

    // 2. Strict allowlist matching against authorized streaming hosts & CDNs
    const isDomainAllowed = ALLOWED_STREAM_DOMAINS.some(allowed =>
      host === allowed || host.endsWith("." + allowed)
    );

    return isDomainAllowed;
  } catch (_) {
    return false;
  }
}
```

### 1.2 M3U8 Playlist Proxy & Manifest Header Rewriter

```javascript
// Rewrite relative and absolute nested playlist, key, and chunk URLs through Worker proxy
function rewriteUriHelper(rawUri, targetOrigin, targetBasePath, workerOrigin, referer, forcePlaylist = false) {
  let abs = rawUri.trim();
  if (!abs.startsWith("http://") && !abs.startsWith("https://")) {
    abs = abs.startsWith("/") ? `${targetOrigin}${abs}` : `${targetBasePath}${abs}`;
  }
  const isPlaylist =
    forcePlaylist ||
    /\.(m3u8|m3u)(\?|$)/i.test(abs) ||
    abs.includes(".m3u8");
  const endpoint = isPlaylist ? "/proxy/m3u8" : "/proxy/ts";
  const sig = signProxyUrl(abs);
  return `${workerOrigin}${endpoint}?url=${encodeURIComponent(abs)}&referer=${encodeURIComponent(referer)}&sig=${sig}`;
}

async function handleM3u8Proxy(request, url) {
  const targetUrl = url.searchParams.get("url");
  const referer = url.searchParams.get("referer") || "https://google.com/";
  const sig = url.searchParams.get("sig") || "";

  if (!targetUrl || !isAuthorizedProxyRequest(targetUrl, sig)) {
    return jsonResponse({ error: "Missing or unauthorized url parameter" }, 400);
  }

  const upstreamHeaders = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Referer": referer,
    "Accept": "*/*"
  };

  try {
    const isSigned = Boolean(sig && sig === signProxyUrl(targetUrl));
    const response = await fetchWithManualRedirects(targetUrl, { headers: upstreamHeaders }, 6000, 3, isSigned);
    if (!response.ok) {
      return new Response(response.body, { status: response.status, headers: corsHeaders() });
    }

    const playlist = await response.text();
    const finalOrigin = new URL(targetUrl).origin;
    const finalBasePath = targetUrl.substring(0, targetUrl.lastIndexOf("/") + 1);

    const lines = playlist.split("\n");
    let expectVariantOnNextLine = false;

    const rewrittenLines = lines.map(line => {
      const trimmed = line.trim();
      if (!trimmed) return line;

      if (trimmed.startsWith("#")) {
        if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
          expectVariantOnNextLine = true;
        }
        // #EXT-X-MEDIA (TYPE=AUDIO / SUBTITLES) and #EXT-X-I-FRAME-STREAM-INF point to child playlists
        const isChildPlaylistTag =
          trimmed.startsWith("#EXT-X-MEDIA") ||
          trimmed.startsWith("#EXT-X-I-FRAME-STREAM-INF");

        return line.replace(/URI="([^"]+)"/gi, (match, uriVal) => {
          const proxied = rewriteUriHelper(
            uriVal,
            finalOrigin,
            finalBasePath,
            url.origin,
            referer,
            isChildPlaylistTag
          );
          return `URI="${proxied}"`;
        });
      }

      const forceM3u8 = expectVariantOnNextLine;
      expectVariantOnNextLine = false;
      return rewriteUriHelper(trimmed, finalOrigin, finalBasePath, url.origin, referer, forceM3u8);
    });

    const rewrittenPlaylist = rewrittenLines.join("\n");

    return new Response(rewrittenPlaylist, {
      status: 200,
      headers: {
        ...corsHeaders(),
        "Content-Type": "application/vnd.apple.mpegurl",
        "Cache-Control": "public, max-age=300"
      }
    });
  } catch (err) {
    return jsonResponse({ error: "M3U8 proxy failed", detail: err.message }, 502);
  }
}

async function handleSegmentProxy(request, url) {
  const targetUrl = url.searchParams.get("url");
  const referer = url.searchParams.get("referer") || "https://google.com/";
  const sig = url.searchParams.get("sig") || "";

  if (!targetUrl || !isAuthorizedProxyRequest(targetUrl, sig)) {
    return jsonResponse({ error: "Missing or unauthorized url parameter" }, 400);
  }

  const headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
    "Referer": referer,
    "Accept": "*/*"
  };

  const range = request.headers.get("Range");
  if (range) headers["Range"] = range;

  try {
    const isSigned = Boolean(sig && sig === signProxyUrl(targetUrl));
    const res = await fetchWithManualRedirects(targetUrl, { headers }, 8000, 3, isSigned);
    const responseHeaders = new Headers(res.headers);
    Object.entries(corsHeaders()).forEach(([k, v]) => responseHeaders.set(k, v));
    responseHeaders.set("Cache-Control", "public, max-age=86400"); // Segments are immutable

    return new Response(res.body, {
      status: res.status,
      statusText: res.statusText,
      headers: responseHeaders
    });
  } catch (err) {
    return jsonResponse({ error: "Segment proxy failed", detail: err.message }, 502);
  }
}
```

### 1.3 Movie Scraper Engine & Multi-Audio Stream Resolvers

```javascript
// Provider C: MultiEmbed Multi-Audio Movie Resolver (including Hindi & ISO languageCode)
async function resolveMovieBoxMultiAudio(tmdbId, imdbId, type, season, episode, workerOrigin) {
  if (!tmdbId && !imdbId) return [];
  const results = [];
  const id = tmdbId || imdbId;

  try {
    const targetUrl = type === "tv"
      ? `https://multiembed.mov/?video_id=${id}&s=${season}&e=${episode}`
      : `https://multiembed.mov/?video_id=${id}&tmdb=1`;

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 3500);

    const res = await fetch(targetUrl, {
      headers: {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Referer": "https://multiembed.mov/"
      },
      signal: controller.signal
    });
    clearTimeout(timeout);

    if (res.ok) {
      const html = await res.text();
      const m3u8Matches = html.match(/https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*/gi) || [];
      const langMatches = html.match(/(English|Spanish|Hindi|German|French|Russian|Portuguese|Arabic|Italian|Japanese)/gi) || [];

      for (let i = 0; i < m3u8Matches.length; i++) {
        const rawUrl = m3u8Matches[i];
        const rawLang = langMatches[i] || (i === 0 ? "English" : null);
        if (!rawLang) continue;

        const detectedLang = rawLang.charAt(0).toUpperCase() + rawLang.slice(1).toLowerCase();
        const langCode = LANG_CODE_MAP[detectedLang.toLowerCase()] || "en";
        const sig = signProxyUrl(rawUrl);
        const proxyUrl = `${workerOrigin}/proxy/m3u8?url=${encodeURIComponent(rawUrl)}&referer=${encodeURIComponent("https://multiembed.mov/")}&sig=${sig}`;
        results.push({
          server: `MultiAudio (${detectedLang})`,
          url: proxyUrl,
          rawUrl: rawUrl,
          kind: "hls",
          quality: "1080p",
          audio: langCode === "en" ? "SUB" : "DUB",
          audioLanguage: detectedLang,
          languageCode: langCode,
          headers: { "Referer": "https://multiembed.mov/" }
        });
      }
    }
  } catch (_) {}

  return results;
}

// Provider D: 2Embed / Vidsrc Fallback Resolver
async function resolveFallbackHls(tmdbId, imdbId, type, season, episode, workerOrigin) {
  if (!tmdbId && !imdbId) return [];
  const results = [];
  const id = tmdbId || imdbId;

  try {
    const embedUrl = type === "tv"
      ? `https://www.2embed.cc/embedtv/${id}&s=${season}&e=${episode}`
      : `https://www.2embed.cc/embed/${id}`;

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 3500);

    const res = await fetch(embedUrl, {
      headers: { "User-Agent": "Mozilla/5.0", "Referer": "https://2embed.cc/" },
      signal: controller.signal
    });
    clearTimeout(timeout);

    if (res.ok) {
      const html = await res.text();
      const m3u8Match = html.match(/https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*/i);
      if (m3u8Match) {
        const rawUrl = m3u8Match[0];
        const sig = signProxyUrl(rawUrl);
        const proxyUrl = `${workerOrigin}/proxy/m3u8?url=${encodeURIComponent(rawUrl)}&referer=${encodeURIComponent("https://2embed.cc/")}&sig=${sig}`;
        results.push({
          server: "FastX",
          url: proxyUrl,
          rawUrl: rawUrl,
          kind: "hls",
          quality: "720p",
          headers: { "Referer": "https://2embed.cc/" }
        });
      }
    }
  } catch (_) {}

  return results;
}

// Provider E: Videasy Multi-CDN HLS Stream Resolver
async function resolveVideasyMultiAudio(type, tmdbId, season, episode, workerOrigin) {
  if (!tmdbId) return [];
  const results = [];
  const isTv = type === "tv" || type === "series";

  try {
    const embedUrl = isTv
      ? `https://player.videasy.net/tv/${tmdbId}/${season}/${episode}`
      : `https://player.videasy.net/movie/${tmdbId}`;

    const res = await fetchWithTimeout(embedUrl, {
      headers: {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Referer": "https://player.videasy.net/"
      }
    }, 3500);

    if (res.ok) {
      const html = await res.text();
      const sourcesMatch = html.match(/https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*/gi) || [];

      for (let i = 0; i < Math.min(sourcesMatch.length, 2); i++) {
        const m3u8Url = sourcesMatch[i];
        if (!m3u8Url.includes(".m3u8")) continue;
        const sig = signProxyUrl(m3u8Url);
        const proxyUrl = `${workerOrigin}/proxy/m3u8?url=${encodeURIComponent(m3u8Url)}&referer=${encodeURIComponent("https://player.videasy.net/")}&sig=${sig}`;
        results.push({
          server: `Videasy (CDN ${i + 1})`,
          url: proxyUrl,
          rawUrl: m3u8Url,
          kind: "hls",
          quality: "1080p",
          audio: "SUB",
          audioLanguage: "English",
          languageCode: "en",
          headers: { "Referer": "https://player.videasy.net/" }
        });
      }
    }
  } catch (_) {}

  return results;
}
```

---

## 2. Stream & Audio Resolution Architecture

### 2.1 `StreamResolver.kt` (Server Selection & Fallback Engine)

**File:** [`domain/src/main/java/com/animarvle/domain/StreamResolver.kt`](file:///c:/Python/New%20folder/Animarvle-AniLab/domain/src/main/java/com/animarvle/domain/StreamResolver.kt)

```kotlin
class StreamResolver(
    private val defaultTimeoutMs: Long = 10_000L,
) {
    /**
     * Resolves an episode by asking the source for its options, ordering the servers
     * according to priority/history, and attempting them in sequence.
     */
    fun resolve(
        source: MediaSource,
        episode: SourceEpisode,
        requested: Choice,
        userPriority: List<String> = emptyList(),
        lastUsedServer: String? = null,
        startAfterServer: String? = null,
        timeoutMs: Long = defaultTimeoutMs,
        failedServers: Set<String> = emptySet(),
        resumedAtMs: Long = 0L,
    ): Flow<ResolveEvent> = flow {
        val options = withTimeout(timeoutMs) { source.options(episode) }
        val servers = options.byAudio[requested.audio]

        // When the requested audio category is not available, emit AudioUnavailable
        // so PlayerViewModel advances to other backup sources rather than playing wrong audio
        if (servers.isNullOrEmpty()) {
            emit(
                ResolveEvent.AudioUnavailable(
                    requested = requested.audio,
                    available = options.byAudio.keys,
                    message = "${requested.audio} is not available from this source",
                ),
            )
            return@flow
        }

        val order = orderServers(servers, lastUsedServer, userPriority, startAfterServer, requested.serverKey, failedServers)
        if (order.isEmpty()) {
            emit(ResolveEvent.AllServersFailed(servers.map { it.key }, "All servers failed"))
            return@flow
        }
        var previous: String? = null
        var lastCause: String? = null

        for (server in order) {
            val prev = previous
            if (prev != null) {
                emit(ResolveEvent.Fallback(prev, server.key, lastCause ?: "server failed"))
            }
            try {
                val media = withTimeout(timeoutMs) {
                    source.resolve(episode, Choice(requested.audio, server.key))
                }
                if (media.streams.isEmpty()) {
                    throw EmptyStreamException(server.key)
                }
                emit(ResolveEvent.Resolved(server.key, media, resumedAtMs))
                return@flow
            } catch (t: TimeoutCancellationException) {
                lastCause = "timed out after ${timeoutMs}ms"
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                lastCause = e.message ?: e::class.java.simpleName
            }
            previous = server.key
        }

        emit(ResolveEvent.AllServersFailed(order.map { it.key }, lastCause))
    }

    /** Per-title last-used -> user priority -> plugin order; manual pick forced to the front. */
    internal fun orderServers(
        servers: List<ServerOption>,
        lastUsedServer: String?,
        userPriority: List<String>,
        startAfterServer: String?,
        manualPick: String?,
        failedServers: Set<String> = emptySet(),
    ): List<ServerOption> {
        val availableServers = if (failedServers.isNotEmpty()) {
            servers.filterNot { it.key in failedServers }
        } else {
            servers
        }
        if (availableServers.isEmpty()) return emptyList()

        val baseOrder = if (startAfterServer != null) {
            val index = availableServers.indexOfFirst { it.key == startAfterServer }
            if (index >= 0) {
                availableServers.drop(index + 1) + availableServers.take(index)
            } else {
                availableServers
            }
        } else {
            availableServers
        }

        val prioritized = baseOrder.sortedWith(
            compareByDescending<ServerOption> { it.key == manualPick }
                .thenByDescending { it.key == lastUsedServer }
                .thenBy {
                    val idx = userPriority.indexOf(it.key)
                    if (idx >= 0) idx else Int.MAX_VALUE
                },
        )
        return prioritized
    }
}
```

### 2.2 `PlayerViewModel.kt` (Dynamic Audio & Multi-Language Switching)

**File:** [`app/src/main/java/com/animarvle/app/ui/player/PlayerViewModel.kt`](file:///c:/Python/New%20folder/Animarvle-AniLab/app/src/main/java/com/animarvle/app/ui/player/PlayerViewModel.kt)

```kotlin
// Rebuilds audio options combining ExoPlayer embedded tracks + remote multi-language servers
fun rebuildAudioOptions(exoTracks: Tracks?) {
    lastReportedExoTracks = exoTracks
    val mergedByLangCode = linkedMapOf<String, ResolvedAudioOption>()

    // 1. Inspect embedded ExoPlayer audio tracks (e.g. MKV or HLS multi-audio manifests)
    if (exoTracks != null) {
        for (group in exoTracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO && group.isSupported) {
                for (i in 0 until group.length) {
                    if (!group.isTrackSupported(i)) continue
                    val format = group.getTrackFormat(i)

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
                    val hasDuplicate = mergedByLangCode.containsKey(rawCode)
                    val mapKey = if (hasDuplicate) "${rawCode}_$i" else rawCode
                    val displayLabel = if (hasDuplicate) {
                        val extraLabel = format.label?.takeIf { it.isNotBlank() } ?: "Track ${i + 1}"
                        "$langName ($extraLabel)"
                    } else {
                        langName
                    }

                    mergedByLangCode[mapKey] = ResolvedAudioOption.EmbeddedTrack(
                        languageName = displayLabel,
                        languageCode = mapKey,
                        trackGroup = group.mediaTrackGroup,
                        trackIndex = i
                    )

                    // Only sync _selectedLanguageCode from ExoPlayer if user hasn't explicitly chosen one yet
                    if (group.isTrackSelected(i) && _selectedLanguageCode.value.isBlank()) {
                        _selectedLanguageCode.value = mapKey
                        _state.update { it.copy(audioLanguage = displayLabel) }
                    }
                }
            }
        }
    }

    // 2. Merge server-provided multi-audio feeds (e.g. Hindi, Spanish, German, English Dub, Japanese Sub)
    val serversByLang = allEpisodeServers.groupBy {
        it.languageCode.ifBlank {
            SpokenLanguage.fromRawTag(it.languageName)?.code ?: "en"
        }.lowercase()
    }
    for ((langCode, serverList) in serversByLang) {
        val alreadyEmbedded = mergedByLangCode.values.any {
            it.languageCode.equals(langCode, ignoreCase = true) ||
                it.languageCode.startsWith("${langCode}_", ignoreCase = true)
        }
        if (!alreadyEmbedded && serverList.isNotEmpty()) {
            val first = serverList.first()
            mergedByLangCode[langCode] = ResolvedAudioOption.ServerStream(
                languageName = first.languageName,
                languageCode = langCode,
                defaultServerKey = first.serverKey,
                serverUrls = serverList,
                audioCategory = first.audioCategory,
            )
        }
    }

    // 3. For Anime: ensure both Japanese/Chinese (Sub) and English (Dub) are accessible in the switcher
    if (isAnime && !_state.value.isMovie) {
        val origCode = if (isDonghua) "zh" else "ja"
        val hasOrig = mergedByLangCode.values.any {
            it.languageCode == origCode || it.languageCode.startsWith("${origCode}_") ||
                it.languageName.contains(if (isDonghua) "Chinese" else "Japanese", ignoreCase = true)
        }
        val hasEnglishDub = mergedByLangCode.values.any {
            (it.languageCode == "en" || it.languageCode.startsWith("en_") || it.languageName.contains("English", ignoreCase = true)) &&
                (it is ResolvedAudioOption.EmbeddedTrack || (it is ResolvedAudioOption.ServerStream && it.audioCategory == Audio.DUB))
        }
        val currentAudio = _state.value.audio

        if (!hasOrig) {
            mergedByLangCode[origCode] = ResolvedAudioOption.ServerStream(
                languageName = if (isDonghua) "Chinese (Sub)" else "Japanese (Sub)",
                languageCode = origCode,
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
    val baseCode = langCode.substringBefore("_")
    val matching = allEpisodeServers.filter {
        it.languageCode.equals(langCode, ignoreCase = true) ||
            it.languageCode.equals(baseCode, ignoreCase = true)
    }
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
        selectedSourceId = null
        attemptedSourceIds.clear()
        sourceMediaCache.clear()
        isManualServerSelection = false
        loadAndResolve(resumeAt = resumeAtMs)
        return
    }

    if (option.serverUrls.isEmpty() && targetServerKey.isBlank()) {
        loadAndResolve(resumeAt = resumeAtMs)
        return
    }

    if (targetServerKey.isNotBlank()) {
        selectServer(targetServerKey, resumeAtMs = resumeAtMs)
    } else {
        loadAndResolve(resumeAt = resumeAtMs)
    }
}
```

---

## 3. Player UI: Audio & Subtitle Track Selector Dialogs

**File:** [`app/src/main/java/com/animarvle/app/ui/player/PlayerFragment.kt`](file:///c:/Python/New%20folder/Animarvle-AniLab/app/src/main/java/com/animarvle/app/ui/player/PlayerFragment.kt)

### 3.1 Audio Track Selection & ExoPlayer Overrides

```kotlin
// Displays Audio selector dialog with embedded tracks and alternate server streams
private fun showAudioDialog() {
    val (rows, handler) = getAudioRowsAndHandler()
    preparePlayerForDialog()
    ChoiceDialog.show(
        context = requireContext(),
        title = "Audio",
        rows = rows,
        onDismiss = { restorePlayerAfterDialog() },
    ) { index ->
        handler(index)
    }
}

private fun getAudioRowsAndHandler(): Pair<List<ChoiceRow>, (Int) -> Unit> {
    val options = viewModel.availableAudioOptions.value
    val activeLangCode = viewModel.selectedLanguageCode.value
    val state = viewModel.state.value

    if (options.isEmpty()) {
        val current = state.audioLanguage.ifBlank { "English" }
        return listOf(ChoiceRow(current, true)) to { toast(current) }
    }

    val hasBothTypes = options.any { it is ResolvedAudioOption.EmbeddedTrack } &&
            options.any { it is ResolvedAudioOption.ServerStream }

    val labels = options.map { option ->
        when (option) {
            is ResolvedAudioOption.EmbeddedTrack -> option.languageName
            is ResolvedAudioOption.ServerStream -> {
                if (hasBothTypes) "${option.languageName} (Switch Server)" else option.languageName
            }
        }
    }

    val selectedIndex = options.indexOfFirst { opt ->
        opt.languageCode.equals(activeLangCode, ignoreCase = true) ||
        opt.languageName.equals(state.audioLanguage, ignoreCase = true)
    }.coerceAtLeast(0)

    val rows = labels.mapIndexed { idx, label ->
        ChoiceRow(label, idx == selectedIndex)
    }

    return rows to { which ->
        val chosen = options.getOrNull(which) ?: return@to
        applyAudioOption(chosen)
    }
}

// Applies TrackSelectionOverride for embedded streams, or switches stream source seamlessly
private fun applyAudioOption(option: ResolvedAudioOption) {
    val p = player ?: return
    when (option) {
        is ResolvedAudioOption.EmbeddedTrack -> {
            val override = TrackSelectionOverride(
                option.trackGroup,
                listOf(option.trackIndex)
            )
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .addOverride(override)
                .setPreferredAudioLanguage(option.languageCode.substringBefore("_"))
                .build()

            viewModel.onLanguageSelected(option.languageCode, option.languageName)
            toast("Audio: ${option.languageName}")
        }

        is ResolvedAudioOption.ServerStream -> {
            val savedPositionMs = p.currentPosition
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setPreferredAudioLanguage(option.languageCode)
                .build()
            toast("Loading ${option.languageName}...")
            viewModel.selectServerStreamLanguage(option, resumeAtMs = savedPositionMs)
        }
    }
}
```

### 3.2 Subtitles Track Selector & Timing Calibration

```kotlin
// Displays SubtitlesBottomSheet with live delay calibration and subtitle selection
private fun showSubtitleDialog() {
    val state = viewModel.state.value
    preparePlayerForDialog()
    SubtitlesBottomSheet(
        context = requireContext(),
        subtitles = state.subtitles,
        selectedSubtitle = state.selectedSubtitle,
        currentDelayMs = subtitleDelayOffsetMs,
        onSelectSubtitle = { track ->
            viewModel.selectSubtitle(track)
        },
        onDelayChange = { newDelayMs ->
            subtitleDelayOffsetMs = newDelayMs
            toast(getString(R.string.player_subtitle_delay_applied, newDelayMs))
        },
        onOpenSettings = {
            player?.pause()
            findNavController().navigate(R.id.subtitleSettingsFragment)
        },
        onDismiss = {
            restorePlayerAfterDialog()
        },
    ).show()
}

// Media3 Player Listener capturing track changes and syncing preferred audio
override fun onTracksChanged(tracks: Tracks) {
    reportQualityLevels(tracks)
    val targetLangCode = viewModel.selectedLanguageCode.value.substringBefore("_")
    if (targetLangCode.isNotBlank()) {
        player?.let { p ->
            val hasExplicitAudioOverride = p.trackSelectionParameters.overrides.keys.any { group ->
                group.type == C.TRACK_TYPE_AUDIO
            }
            if (!hasExplicitAudioOverride &&
                p.trackSelectionParameters.preferredAudioLanguages.firstOrNull() != targetLangCode
            ) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage(targetLangCode)
                    .build()
            }
        }
    }
    viewModel.rebuildAudioOptions(tracks)
}
```
