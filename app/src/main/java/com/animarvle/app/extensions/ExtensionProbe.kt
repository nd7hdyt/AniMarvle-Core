package com.animarvle.app.extensions

import android.content.Context
import android.util.Log
import com.animarvle.app.extensions.TachiyomiExtensionLoader.LoadResult
import com.animarvle.sourceapi.Audio
import com.animarvle.sourceapi.Choice
import com.animarvle.sourceapi.MediaType
import com.animarvle.sourceapi.SourceEpisode
import com.animarvle.sourceapi.SourceMedia
import dev.mihon.injekt.patchInjekt
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import tachiyomi.core.common.preference.AndroidPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingletonFactory

/**
 * On-device probe that proves real Aniyomi extensions load AND answer queries against the
 * vendored source-api. Extensions obtain their `NetworkHelper` from the host's Injekt registry
 * (verified from animepahe v14.45 dex: it calls the `uy.kohesive.injekt` scope then
 * `AnimeHttpSource.getNetwork()`), so a minimal module is registered before any query.
 *
 * Injekt here is `uy.kohesive.injekt:injekt-core` (the *real* DI the extension dex links
 * against) plus Mihon's `dev.mihon.injekt` patch for thread-safe registration.
 */
object ExtensionProbe {

    private const val TAG = "ExtensionProbe"

    @Volatile private var appContext: Context? = null

    enum class StageStatus {
        NOT_TESTED,
        RUNNING,
        SUCCESS,
        FAILURE,
    }

    data class StageReport(
        val status: StageStatus = StageStatus.NOT_TESTED,
        val summary: String = "Not tested",
        val durationMs: Long = 0L,
    )

    data class ExtensionTestPipelineResult(
        val search: StageReport = StageReport(),
        val episodes: StageReport = StageReport(),
        val servers: StageReport = StageReport(),
        val isRunning: Boolean = false,
    ) {
        val isSuccess: Boolean
            get() = search.status == StageStatus.SUCCESS &&
                    episodes.status == StageStatus.SUCCESS &&
                    servers.status == StageStatus.SUCCESS
    }

    data class ProbeResult(
        val success: Boolean,
        val durationMs: Long,
        val message: String,
        val details: String? = null,
        val pipelineResult: ExtensionTestPipelineResult? = null,
    )

    /** Registers the minimal Injekt bindings Aniyomi extension dex expects. Call once at startup. */
    fun registerInjekt(context: Context) {
        appContext = context.applicationContext

        // Mihon's patch makes the default Injekt registrar thread-safe (extensions run on IO).
        patchInjekt()

        val preferences = NetworkPreferences(AndroidPreferenceStore(appContext!!))
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            isLenient = true
            coerceInputValues = true
        }
        // Bindings go straight on the shared `Injekt` scope (no module class needed): extension
        // dex calls `Injekt.get<NetworkHelper>()` (via `AnimeHttpSource.network`).
        Injekt.addSingletonFactory { NetworkHelper(appContext!!, preferences) }
        Injekt.addSingletonFactory { json }
        // Keiyoushi-built extensions resolve `android.app.Application` / `android.content.Context`
        // through the same scope (verified on-device: AnimeOnsen's parser threw
        // "No registered instance or factory for type class android.app.Application").
        Injekt.addSingletonFactory { context.applicationContext as android.app.Application }
        Injekt.addSingletonFactory { context.applicationContext }
        Log.i(TAG, "Injekt bindings registered (NetworkHelper, Json, Application, Context)")
    }

    suspend fun probePing(pkg: ExtensionPackage): ProbeResult {
        val start = System.currentTimeMillis()
        val ctx = appContext ?: return ProbeResult(false, 0, "Injekt not initialized")
        val result = TachiyomiExtensionLoader.load(ctx, pkg)
        val elapsed = System.currentTimeMillis() - start
        return when (result) {
            is LoadResult.Failure -> ProbeResult(false, elapsed, "Failed to load: ${result.reason}")
            is LoadResult.Success -> {
                val src = result.instance
                val name = (src as? eu.kanade.tachiyomi.animesource.AnimeSource)?.name ?: pkg.label
                ProbeResult(true, elapsed, "Connected: $name (${elapsed}ms)")
            }
        }
    }

    suspend fun probeBasic(pkg: ExtensionPackage, query: String): ProbeResult {
        val start = System.currentTimeMillis()
        val ctx = appContext ?: return ProbeResult(false, 0, "Injekt not initialized")
        val loaded = TachiyomiExtensionLoader.load(ctx, pkg)
        if (loaded !is LoadResult.Success) {
            val elapsed = System.currentTimeMillis() - start
            val reason = (loaded as? LoadResult.Failure)?.reason ?: "unknown"
            return ProbeResult(false, elapsed, "Load error: $reason")
        }
        val ani = loaded.instance as? eu.kanade.tachiyomi.animesource.AnimeSource
            ?: return ProbeResult(false, System.currentTimeMillis() - start, "Not an AnimeSource")
        val src = TachiyomiAnimeSource(ani)

        return withContext(Dispatchers.IO) {
            try {
                val results = if (query.isNotBlank()) {
                    src.search(query.trim(), MediaType.ANIME, 1)
                } else {
                    emptyList()
                }
                val elapsed = System.currentTimeMillis() - start
                if (results.isNotEmpty()) {
                    val first = results.first().title
                    ProbeResult(true, elapsed, "Found ${results.size} matches (${elapsed}ms)", "First: $first")
                } else {
                    val popularVerdict = probePopular(pkg)
                    val totalElapsed = System.currentTimeMillis() - start
                    if (popularVerdict.startsWith("OK")) {
                        ProbeResult(true, totalElapsed, "Catalogue accessible (${totalElapsed}ms)", popularVerdict)
                    } else {
                        ProbeResult(false, totalElapsed, "No results for '$query' (${totalElapsed}ms)")
                    }
                }
            } catch (e: Throwable) {
                val elapsed = System.currentTimeMillis() - start
                ProbeResult(false, elapsed, "Query error: ${e.message?.take(80) ?: e.javaClass.simpleName}")
            }
        }
    }

    suspend fun probeFull(
        pkg: ExtensionPackage,
        query: String,
        mediaType: MediaType = MediaType.ANIME,
        onStageUpdate: ((ExtensionTestPipelineResult) -> Unit)? = null
    ): ProbeResult {
        val start = System.currentTimeMillis()
        val pipeline = probePipelineStages(pkg, query, mediaType, onStageUpdate)
        val elapsed = System.currentTimeMillis() - start
        val success = pipeline.isSuccess
        val summary = if (success) {
            "All stages passed (${elapsed}ms)"
        } else {
            if (pipeline.search.status == StageStatus.FAILURE) {
                pipeline.search.summary
            } else if (pipeline.episodes.status == StageStatus.FAILURE) {
                pipeline.episodes.summary
            } else {
                pipeline.servers.summary
            }
        }
        return ProbeResult(success, elapsed, summary, details = "Search, Episodes, and Servers tested", pipelineResult = pipeline)
    }

    suspend fun probePipelineStages(
        pkg: ExtensionPackage,
        query: String,
        mediaType: MediaType = MediaType.ANIME,
        onStageUpdate: ((ExtensionTestPipelineResult) -> Unit)? = null
    ): ExtensionTestPipelineResult {
        var result = ExtensionTestPipelineResult(
            search = StageReport(status = StageStatus.RUNNING, summary = "Searching..."),
            episodes = StageReport(status = StageStatus.NOT_TESTED, summary = "Not tested"),
            servers = StageReport(status = StageStatus.NOT_TESTED, summary = "Not tested"),
            isRunning = true
        )
        onStageUpdate?.invoke(result)

        val ctx = appContext ?: run {
            val failure = ExtensionTestPipelineResult(
                search = StageReport(StageStatus.FAILURE, "Injekt not initialized"),
                episodes = StageReport(StageStatus.NOT_TESTED, "Not tested"),
                servers = StageReport(StageStatus.NOT_TESTED, "Not tested"),
                isRunning = false
            )
            onStageUpdate?.invoke(failure)
            return failure
        }

        // 1. Load extension
        val loaded = TachiyomiExtensionLoader.load(ctx, pkg)
        if (loaded !is TachiyomiExtensionLoader.LoadResult.Success) {
            val reason = (loaded as? TachiyomiExtensionLoader.LoadResult.Failure)?.reason ?: "Failed to load extension"
            result = result.copy(
                search = StageReport(StageStatus.FAILURE, "Load Error: $reason"),
                isRunning = false
            )
            onStageUpdate?.invoke(result)
            return result
        }

        val ani = loaded.instance as? eu.kanade.tachiyomi.animesource.AnimeSource
        if (ani == null) {
            result = result.copy(
                search = StageReport(StageStatus.FAILURE, "Error: Not an AnimeSource"),
                isRunning = false
            )
            onStageUpdate?.invoke(result)
            return result
        }

        val src = TachiyomiAnimeSource(ani)

        // STAGE 1: SEARCH
        val startSearch = System.currentTimeMillis()
        val mediaList: List<SourceMedia>
        try {
            val q = query.ifBlank { "chainsaw man" }
            mediaList = withContext(Dispatchers.IO) {
                src.search(q, mediaType, 1)
            }
            val searchMs = System.currentTimeMillis() - startSearch
            if (mediaList.isEmpty()) {
                result = result.copy(
                    search = StageReport(StageStatus.FAILURE, "No results found (${searchMs}ms)", durationMs = searchMs),
                    isRunning = false
                )
                onStageUpdate?.invoke(result)
                return result
            }
            result = result.copy(
                search = StageReport(StageStatus.SUCCESS, "${mediaList.size} results in ${searchMs}ms", durationMs = searchMs),
                episodes = StageReport(StageStatus.RUNNING, "Fetching episodes...")
            )
            onStageUpdate?.invoke(result)
        } catch (e: Throwable) {
            val searchMs = System.currentTimeMillis() - startSearch
            val errorMsg = e.message?.take(100) ?: e.javaClass.simpleName
            result = result.copy(
                search = StageReport(StageStatus.FAILURE, "Error: $errorMsg", durationMs = searchMs),
                isRunning = false
            )
            onStageUpdate?.invoke(result)
            return result
        }

        // STAGE 2: EPISODES
        val firstMedia = mediaList.first()
        val startEpisodes = System.currentTimeMillis()
        val epList: List<SourceEpisode>
        try {
            epList = withContext(Dispatchers.IO) {
                src.episodes(firstMedia)
            }
            val epMs = System.currentTimeMillis() - startEpisodes
            if (epList.isEmpty()) {
                result = result.copy(
                    episodes = StageReport(StageStatus.FAILURE, "No episodes found for '${firstMedia.title}' (${epMs}ms)", durationMs = epMs),
                    isRunning = false
                )
                onStageUpdate?.invoke(result)
                return result
            }
            result = result.copy(
                episodes = StageReport(StageStatus.SUCCESS, "${epList.size} Episodes in ${epMs}ms", durationMs = epMs),
                servers = StageReport(StageStatus.RUNNING, "Resolving servers...")
            )
            onStageUpdate?.invoke(result)
        } catch (e: Throwable) {
            val epMs = System.currentTimeMillis() - startEpisodes
            val errorMsg = e.message?.take(100) ?: e.javaClass.simpleName
            result = result.copy(
                episodes = StageReport(StageStatus.FAILURE, "Error: $errorMsg", durationMs = epMs),
                isRunning = false
            )
            onStageUpdate?.invoke(result)
            return result
        }

        // STAGE 3: SERVERS
        val firstEp = epList.first()
        val startServers = System.currentTimeMillis()
        try {
            val opts = withContext(Dispatchers.IO) {
                src.options(firstEp)
            }
            val serverList = opts.byAudio.values.flatten()
            val servMs = System.currentTimeMillis() - startServers
            if (serverList.isEmpty()) {
                result = result.copy(
                    servers = StageReport(StageStatus.FAILURE, "No servers found (${servMs}ms)", durationMs = servMs),
                    isRunning = false
                )
            } else {
                result = result.copy(
                    servers = StageReport(StageStatus.SUCCESS, "${serverList.size} Servers in ${servMs}ms", durationMs = servMs),
                    isRunning = false
                )
            }
            onStageUpdate?.invoke(result)
            return result
        } catch (e: Throwable) {
            val servMs = System.currentTimeMillis() - startServers
            val errorMsg = e.message?.take(100) ?: e.javaClass.simpleName
            result = result.copy(
                servers = StageReport(StageStatus.FAILURE, "Error: $errorMsg", durationMs = servMs),
                isRunning = false
            )
            onStageUpdate?.invoke(result)
            return result
        }
    }


    /**
     * Loads [pkg] and asks it for page 1 of its popular catalogue. Returns a short, loggable
     * verdict so the host can prove ABI + runtime compatibility without a dedicated UI yet.
     */
    suspend fun probePopular(pkg: ExtensionPackage): String {
        val ctx = appContext ?: return "PROBE SKIPPED (Injekt not registered)"
        val result = TachiyomiExtensionLoader.load(ctx, pkg)
        return when (result) {
            is LoadResult.Failure -> "LOAD FAIL: ${result.reason}"
            is LoadResult.Success -> runCatching {
                val source = result.instance as AnimeCatalogueSource
                val page = withContext(Dispatchers.IO) { source.getPopularAnime(1) }
                val animes = page.animes
                val sample = animes.firstOrNull()?.let { "${it.title}" }
                "OK: loaded ${pkg.packageName}, ${animes.size} popular results" +
                    (sample?.let { " (first: $it)" } ?: "")
            }.getOrElse { e ->
                "QUERY FAIL: ${e.javaClass.simpleName}: ${e.message?.take(150)}"
                    .also { Log.w(TAG, "probe failed", e) }
            }
        }
    }

    /**
     * Exercises the whole adapter path against a real extension:
     * search -> episodes -> options -> resolve. This is what actually proves the loaded source is
     * usable by the rest of the app, rather than merely instantiable.
     */
    suspend fun probePipeline(pkg: ExtensionPackage, query: String = "one piece"): String {
        val ctx = appContext ?: return "SKIPPED (Injekt not registered)"
        val loaded = TachiyomiExtensionLoader.load(ctx, pkg)
        if (loaded !is TachiyomiExtensionLoader.LoadResult.Success) {
            val reason = (loaded as? LoadResult.Failure)?.reason ?: "unknown"
            return "LOAD FAIL: $reason"
        }
        val ani = loaded.instance as? eu.kanade.tachiyomi.animesource.AnimeSource
            ?: return "NOT AN ANIME SOURCE"
        val src = TachiyomiAnimeSource(ani)
        return runCatching {
            val media = src.search(query, MediaType.MOVIE, 1)
            if (media.isEmpty()) return "NO SEARCH RESULTS for '$query'"
            val firstMedia = media.first()
            val eps = src.episodes(firstMedia)
            if (eps.isEmpty()) return "NO EPISODES for '${firstMedia.title}'"
            val firstEp = eps.first()
            val opts = src.options(firstEp)
            val audio = opts.byAudio.keys.firstOrNull() ?: Audio.SUB
            val server = opts.byAudio[audio]?.firstOrNull()
                ?: return "NO SERVERS for '${firstMedia.title}' (audioKeys=${opts.byAudio.keys})"
            val resolved = src.resolve(firstEp, Choice(audio, server.key))
            "OK ${src.name}/${src.language}: search=${media.size} eps=${eps.size} " +
                "servers=${opts.byAudio[audio]?.size} streams=${resolved.streams.size} " +
                "first='${firstMedia.title}' streamUrl=${resolved.streams.firstOrNull()?.url?.take(80)}"
        }.getOrElse { e ->
            "PIPELINE FAIL: ${e.javaClass.simpleName}: ${e.message?.take(150)}"
                .also { Log.w(TAG, "pipeline failed", e) }
        }
    }

    /**
     * OkHttp client for ExoPlayer's HTTP datasource.
     *
     * Shares the process-wide [android.webkit.CookieManager] via an [AndroidCookieJar], so any
     * `cf_clearance` solved by the extension-side [NetworkHelper] (or by this client's own
     * [CloudflareInterceptor]) is replayed on playback requests. Without the interceptor here,
     * a Cloudflare-fronted CDN returns 403 for the manifest even though resolve succeeded.
     *
     * The UA provider mirrors Mihon: it uses the WebView's inferred (Chrome-like) UA so the
     * challenge, when one is issued, is solved with the same identity the client replays.
     */
    fun playbackClient(): OkHttpClient {
        val ctx = requireNotNull(appContext) { "registerInjekt() must run first" }
        val cookieJar = eu.kanade.tachiyomi.network.AndroidCookieJar()
        return OkHttpClient.Builder()
            .connectionPool(okhttp3.ConnectionPool(32, 5, java.util.concurrent.TimeUnit.MINUTES))
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .cookieJar(cookieJar)
            .addInterceptor(
                eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor(
                    context = ctx,
                    cookieManager = cookieJar,
                    defaultUserAgentProvider = {
                        runCatching { eu.kanade.tachiyomi.util.system.WebViewUtil.getInferredUserAgent(ctx) }
                            .getOrDefault("Mozilla/5.0")
                    },
                ),
            )
            .build()
    }

    /**
     * Replicates PlayerFragment's construction *without* the in-memory warm caches: a brand-new
     * adapter built from the media payload carried across navigation (title + `extra` only).
     * This is the true cold path a user hits when Details -> Play hands over a match result.
     */
    suspend fun probeColdPlayerPath(
        pkg: ExtensionPackage,
        title: String,
        animeUrl: String,
    ): String {
        val ctx = appContext ?: return "SKIPPED (Injekt not registered)"
        val loaded = TachiyomiExtensionLoader.load(ctx, pkg)
        if (loaded !is TachiyomiExtensionLoader.LoadResult.Success) return "LOAD FAIL"
        val ani = loaded.instance as? eu.kanade.tachiyomi.animesource.AnimeSource ?: return "NOT A SOURCE"
        val src = TachiyomiAnimeSource(ani)
        val payload = com.animarvle.sourceapi.SourceMedia(
            url = animeUrl,
            title = title,
            year = null,
            episodeCount = null,
            poster = null,
            type = MediaType.ANIME,
            extra = mapOf(TachiyomiAnimeSource.KEY_ANIME_URL to animeUrl),
        )
        return runCatching {
            val eps = src.episodes(payload)
            val opts = src.options(eps.first())
            val audio = opts.byAudio.keys.first()
            val resolved = src.resolve(eps.first(), Choice(audio, opts.byAudio.getValue(audio).first().key))
            val base = "COLD OK: eps=${eps.size} streams=${resolved.streams.size} url=${resolved.streams.first().url.take(60)} hdrs=${resolved.headers.keys}"
            base + " | " + probePlayback(resolved.streams.first().url, resolved.headers)
        }.getOrElse { e ->
            "COLD FAIL: ${e.javaClass.simpleName}: ${e.message?.take(150)}"
        }
    }

    /**
     * Drives a real ExoPlayer against a resolved stream exactly like PlayerFragment.buildPlayer:
     * same OkHttp datasource + DefaultMediaSourceFactory. Returns once the player reaches
     * STATE_READY (manifest parsed) or on error/timeout. This is the proof that a stream the
     * extension hands over can actually be played, not just resolved.
     */
    private suspend fun probePlayback(url: String, headers: Map<String, String>): String {
        val ctx = appContext ?: return "PLAY SKIPPED (no context)"
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val ok = playbackClient()
                val okDs = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(ok)
                if (headers.isNotEmpty()) okDs.setDefaultRequestProperties(headers)
                val player = androidx.media3.exoplayer.ExoPlayer.Builder(ctx)
                    .setMediaSourceFactory(
                        androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                            androidx.media3.datasource.DefaultDataSource.Factory(ctx, okDs),
                        ),
                    )
                    .build()
                lateinit var timeout: java.lang.Runnable
                lateinit var timeoutListener: androidx.media3.common.Player.Listener
                timeoutListener = object : androidx.media3.common.Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == androidx.media3.common.Player.STATE_READY) {
                            android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(timeout)
                            player.removeListener(this)
                            val dur = player.duration
                            player.release()
                            cont.resumeWith(Result.success("PLAY READY: duration=${dur}ms"))
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(timeout)
                        player.removeListener(this)
                        player.release()
                        cont.resumeWith(Result.success("PLAY ERROR: ${error.errorCodeName}: ${error.message?.take(120)}"))
                    }
                }
                timeout = java.lang.Runnable {
                    player.removeListener(timeoutListener)
                    player.release()
                    cont.resumeWith(Result.success("PLAY TIMEOUT (no READY in 25s)"))
                }
                player.addListener(timeoutListener)
                player.setMediaItem(androidx.media3.common.MediaItem.Builder().setUri(url).build())
                player.prepare()
                player.playWhenReady = false
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(timeout, 25_000)
            }
        }
    }
}