package com.animarvle.app

import android.app.Application
import com.animarvle.app.data.MetadataRepository
import com.animarvle.app.extensions.ExtensionDiscovery
import com.animarvle.app.extensions.ExtensionPackage
import com.animarvle.app.extensions.ExtensionProbe
import com.animarvle.app.extensions.TachiyomiExtensionLoader
import com.animarvle.domain.MatchResult
import com.animarvle.domain.MetaQuery
import com.animarvle.domain.SourceMatcher
import com.animarvle.domain.StreamResolver
import com.animarvle.sourceapi.Audio
import com.animarvle.sourceapi.Choice
import com.animarvle.sourceapi.MediaSource
import com.animarvle.sourceapi.MediaType
import com.animarvle.sourceapi.Stream
import com.animarvle.sourceapi.StreamKind
import com.animarvle.sourceapi.Titles
import com.animarvle.sourcesample.FakeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.scheduler.Requirements
import java.io.File
import java.util.concurrent.Executors

/**
 * Manual DI container (ARCH §3: built in Application, handed to ViewModel factories).
 * No DI framework in v1 (DECISIONS D7).
 *
 * M3 wires the first source (`FakeSource`) plus the shared resolver and matcher so the
 * Player can run the real resolve pipeline. M5 replaces this list with loaded extensions.
 */
class AnimarvleApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Register the Injekt bindings extensions expect BEFORE the container is constructed:
        // AppContainer.http builds a Cloudflare-capable client that needs this context, and it is
        // lazy, but ordering it here keeps the dependency explicit.
        ExtensionProbe.registerInjekt(this)
        container = AppContainer(this)
        // Extension host: the Aniyomi source-api classes are now on our classpath (:aniyomi-api),
        // so real extension dex can bind to them. Probe each installed package:
        // load + getPopularAnime(1) proves ABI + runtime support.
        val packages = container.extensionPackages
        android.util.Log.i(
            "Animarvle",
            "Extensions: ${packages.size} found; API classes present=" +
                TachiyomiExtensionLoader.apiClassesPresent(),
        )
    }

    companion object {
        private val registeredDonghuaIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        private val registeredDonghuaTitles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        fun registerDonghua(id: Long?, vararg titles: String?) {
            if (id != null) registeredDonghuaIds.add(id)
            titles.filterNotNull().filter { it.isNotBlank() }.forEach {
                registeredDonghuaTitles.add(it.trim().lowercase())
            }
        }

        fun isDonghua(title: String?, mediaId: Long? = null): Boolean {
            if (mediaId != null && registeredDonghuaIds.contains(mediaId)) return true
            if (title.isNullOrBlank()) return false
            val t = title.lowercase().trim()
            if (registeredDonghuaTitles.contains(t)) return true
            val donghuaList = listOf(
                "renegade immortal", "xian ni",
                "fights break sphere", "battle through the heavens", "doupo",
                "soul land", "douluo dalu", "douluo",
                "perfect world", "wanmei shijie",
                "swallowed star", "tunshi xingkong",
                "a will eternal", "yi nian yong heng",
                "mortal's journey", "a record of a mortal", "fanren xiu xian",
                "shrouding the heavens", "zhe tian",
                "throne of seal", "shen yin wangzuo",
                "stellar transformation", "xingchen bian",
                "martial universe", "wu dong qian kun",
                "the great ruler", "da zhu zai",
                "immortal king", "xian wang",
                "apotheosis", "bai lian cheng shen",
                "lord xue ying", "snow eagle lord", "xue ying ling zhu",
                "against the gods", "ni tian xie shen",
                "tales of demons and gods", "yao shen ji",
                "keyboard immortal",
                "peerless martial", "jueshi wu shen",
                "martial master", "wu shen zhu zai",
                "jade dynasty", "zhu xian",
                "demonic cultivation", "mo dao zu shi",
                "heaven official", "tian guan ci fu",
                "scissor seven", "cike wu liuqi",
                "link click", "shiguang dailiren",
                "five elements", "wu shan wu xing",
                "white cat legend", "immortality",
                "legend of immortals", "everlasting immortal",
                "sword immortal", "world of immortals",
                "aliens among immortals", "soul hunter",
                "carp reborn", "yuan zun", "dragon prince yuan",
                "law of the devil", "wan jie", "ten thousand worlds",
                "tomb of fallen gods", "shen mu",
                "island of siliang", "juan si liang",
                "bu liang ren", "painting rivers and lakes",
                "ling cage", "incorporeal", "blade of the guardians",
                "biao ren", "spicy girl", "luo xiaohei", "legend of hei",
                "fox spirit matchmaker", "huyao xiao hongniang",
                "quanzhi fashi", "full-time magister",
                "the king's avatar", "quanzhi gaoshou",
                "lord of mysteries", "lord of the mysteries",
                "to be hero", "dawang raoming", "spare me, great lord",
                "wan sheng jie", "all saints street", "false memory",
                "rakshasa street", "hitori no shita", "the outcast",
                "god of martial arts", "spirit sword mountain",
                "silver gravekeeper", "cinderella chef", "psychic princess",
                "no doubt in us", "liang bu yi",
                "donghua",
            )
            return donghuaList.any { t.contains(it) }
        }
    }
}

/** Mirrors DetailFragment.playCurrent's match call for on-device verification. */
private suspend fun matchTitle(
    container: AppContainer,
    title: String,
    romaji: String?,
    year: Int?,
): MatchResult = runCatching {
    withTimeoutOrNull(20_000) {
        container.matcher.match(
            MetaQuery(
                metaId = "anilist:$title",
                type = MediaType.ANIME,
                titles = Titles(english = title, romaji = romaji),
                year = year,
                episodeCount = null,
            ),
            container.sources,
        )
    }
}.getOrNull() ?: MatchResult.NoMatch

class AppContainer(
    private val appContext: android.content.Context,
) {
    val metadata: MetadataRepository by lazy {
        val prefs = appContext.getSharedPreferences(
            com.animarvle.app.ui.profile.PlayerSettingsFragment.PREFS_NAME,
            android.content.Context.MODE_PRIVATE,
        )
        MetadataRepository(
            api = com.animarvle.app.data.anilist.AniListApi(
                isAdultProvider = {
                    prefs.getBoolean(
                        com.animarvle.app.ui.profile.PlayerSettingsFragment.KEY_ADULT_MODE,
                        false,
                    )
                }
            )
        )
    }
    val sourceBindings: com.animarvle.app.data.binding.AnimeSourceBindingRepository by lazy {
        com.animarvle.app.data.binding.AnimeSourceBindingRepository(appContext)
    }

    /**
     * ExoPlayer's HTTP client. Built by [ExtensionProbe.playbackClient] so it shares the
     * process-wide CookieManager (and therefore any `cf_clearance` solved for Cloudflare-fronted
     * CDNs) and carries its own CloudflareInterceptor for the manifest/segment requests.
     */
    val http: OkHttpClient by lazy { com.animarvle.app.extensions.ExtensionProbe.playbackClient() }

    /**
     * Extension packages installed on the device (Aniyomi/Mihon). Discovery is cheap and read-only;
     * see docs/DECISIONS.md "Source strategy" for the GPL-3.0 licence posture.
     */
    val extensionPackages: List<ExtensionPackage>
        get() = ExtensionDiscovery.discover(appContext).also { ExtensionDiscovery.log(it) }

    /**
     * Sources backed by user-installed Aniyomi/Mihon extensions, adapted to our own contract.
     * Loading is lazy + cheap: a [DexClassLoader] per package, no network until the source is used.
     */
    val extensionSources: List<com.animarvle.app.extensions.TachiyomiAnimeSource>
        get() = extensionPackages.flatMap { pkg ->
            val loaded = TachiyomiExtensionLoader.load(appContext, pkg)
            if (loaded is TachiyomiExtensionLoader.LoadResult.Success) {
                when (val inst = loaded.instance) {
                    is eu.kanade.tachiyomi.animesource.AnimeSource -> listOf(com.animarvle.app.extensions.TachiyomiAnimeSource(inst))
                    is eu.kanade.tachiyomi.animesource.AnimeSourceFactory -> inst.createSources().map { com.animarvle.app.extensions.TachiyomiAnimeSource(it) }
                    else -> emptyList()
                }
            } else {
                emptyList()
            }
        }.sortedWith(compareByDescending { src ->
            val n = src.name.lowercase()
            when {
                // Tier 1: Verified Working Engines with Full Sub + Dub Support
                // Anikoto (HD-1, Vidstream-2, Vidstream-1, HD-2), AnimeKai/Kotokai, Anichi, AniWave (Unoriginal), AnimeSogo
                n.contains("anikoto") -> 150
                n.contains("animekai") || n.contains("kotokai") -> 145
                n.contains("anichi") -> 140
                n.contains("aniwave") && !n.contains(".ru") -> 135
                n.contains("animesogo") -> 130
                // Animenosub: Verified clean language switching between Dub and Sub
                n.contains("animenosub") -> 125

                // Tier 2: Verified Working Sub-Only Engines
                // KissKH, AniDB, AnimeOnsen, AV1Encodes
                n.contains("kisskh") -> 110
                n.contains("anidb") -> 105
                n.contains("animeonsen") -> 100
                n.contains("av1encodes") -> 95

                // Tier 3: Works in Anymex with compatibility fallback
                // AniPM, AniHQ, HexaWatch, AnimeTake, AnimeToki, AnimeParadise
                n.contains("anipm") -> 70
                n.contains("anihq") -> 68
                n.contains("hexawatch") -> 66
                n.contains("animetake") -> 64
                n.contains("animetoki") -> 62
                n.contains("paradise") -> 60

                // Tier 4: Known Broken / Dead / Extractor Failures (both in Anymex and standalone)
                // WCOStream, 123Anime, AniKage, AnimeGG, LuciferDonghua, AniWave.ru, AllAnime, AniKuro, AsiaFlix,
                // AnimeKhor, MoviesMod, UHD Movies, BestDubbedAnime, GenoAnime, HStream, KimoiTV, XAnime,
                // Animension, AnimePahe, HiAnime, Gogoanime
                n.contains("wcostream") || n.contains("wcoanime") || n.contains("wcofun") -> 20
                n.contains("123anime") || n.contains("onetwothree") -> 20
                n.contains("anikage") || n.contains("animegg") -> 20
                n.contains("lucifer") || n.contains("aniwaves.ru") -> 20
                n.contains("allanime") || n.contains("anikuro") || n.contains("asiaflix") -> 20
                n.contains("bestdubbed") || n.contains("genoanime") || n.contains("uhdmovies") -> 20
                n.contains("animekhor") || n.contains("hstream") || n.contains("kimoitv") || n.contains("xanime") -> 20
                n.contains("anime4up") || n.contains("moviesmod") || n.contains("rule34") -> 15
                n.contains("kayoanime") || n.contains("streamcloud") || n.contains("animension") -> 15
                n.contains("animepahe") || n.contains("hianime") || n.contains("zoro") -> 10
                n.contains("gogoanime") || n.contains("kickass") -> 5
                src.language == "en" || src.language == "all" -> 40
                else -> 10
            }
        })

    /**
     * Built-in native scrapers (Mizuki & Th3-Anime style) that do not require external APKs.
     */
    val builtinSources: List<MediaSource> by lazy {
        listOf(
            com.animarvle.app.data.source.builtin.AnimarvleStreamSource(http, appContext),
            // Kept in codebase for future reference; disabled in active build per AniVortex standard:
            // com.animarvle.app.data.source.builtin.AllAnimeSource(http),
            // com.animarvle.app.data.source.builtin.AniKotoSource(http),
            // com.animarvle.app.data.source.builtin.FourKHDHubSource(http),
        )
    }

    /**
     * Pure Cloudflare Edge Relay (AniVortex architecture).
     * Eliminates 4-6 MB/s monolithic file downloads and extension clutter.
     */
    fun sourcesFor(type: MediaType, title: String? = null): List<MediaSource> {
        val isDonghuaTitle = AnimarvleApp.isDonghua(title)
        val relay = builtinSources.first()
        val extra = extensionSources.filter { type in it.supportedTypes }
        val sortedExtra = when {
            isDonghuaTitle -> {
                extra.sortedWith(compareByDescending { src ->
                    val n = src.name.lowercase()
                    when {
                        n.contains("anichi") -> 300
                        n.contains("kisskh") -> 250
                        n.contains("anikoto") -> 150
                        n.contains("kotokai") || n.contains("animekai") -> 145
                        n.contains("aniwave") -> 140
                        n.contains("animesogo") -> 130
                        else -> 50
                    }
                })
            }
            type == MediaType.MOVIE -> {
                extra.sortedWith(compareByDescending { src ->
                    val n = src.name.lowercase()
                    when {
                        n.contains("moviebox") -> 200
                        n.contains("hexawatch") -> 180
                        n.contains("sflix") -> 170
                        n.contains("kisskh") -> 160
                        n.contains("uniquestream") -> 150
                        n.contains("movie") -> 140
                        else -> 50
                    }
                })
            }
            else -> {
                extra
            }
        }

        return if (isDonghuaTitle) {
            // For Donghua, dedicated scrapers (Anichi, KissKH) win immediately.
            // Builtin Cloudflare relay does not host Chinese donghua, so put it after Anichi/KissKH
            // to eliminate the initial 3-5s timeout and buffering!
            sortedExtra + listOf(relay)
        } else {
            listOf(relay) + sortedExtra
        }
    }

    /**
     * The source list the UI offers. Real extensions win; the fixture demo source is only a
     * fallback so the app still demos on a device with no extensions installed.
     */
    val sources: List<MediaSource>
        get() = sourcesFor(MediaType.ANIME)

    val resolver: StreamResolver by lazy { StreamResolver() }
    val matcher: SourceMatcher by lazy { SourceMatcher() }
    val history: com.animarvle.app.ui.player.HistoryRepository by lazy {
        com.animarvle.app.ui.player.HistoryRepository(appContext)
    }
    val recentSearches: com.animarvle.app.ui.search.RecentSearchRepository by lazy {
        com.animarvle.app.ui.search.RecentSearchRepository(appContext)
    }
    val databaseProvider: StandaloneDatabaseProvider by lazy {
        StandaloneDatabaseProvider(appContext)
    }

    val downloadDirectory: File by lazy {
        val ext = appContext.getExternalFilesDir(null)
        if (ext != null) File(ext, "downloads").apply { mkdirs() }
        else File(appContext.filesDir, "downloads").apply { mkdirs() }
    }

    val downloadCache: SimpleCache by lazy {
        SimpleCache(downloadDirectory, NoOpCacheEvictor(), databaseProvider)
    }

    val downloadDataSourceFactory: DefaultDataSource.Factory by lazy {
        val okHttpFactory = OkHttpDataSource.Factory(http)
        DefaultDataSource.Factory(appContext, okHttpFactory)
    }

    val cacheDataSourceFactory: CacheDataSource.Factory by lazy {
        CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(downloadDataSourceFactory)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    val downloadNotificationHelper: DownloadNotificationHelper by lazy {
        DownloadNotificationHelper(appContext, com.animarvle.app.ui.download.AnimarvleDownloadService.CHANNEL_ID)
    }

    val downloadManager: DownloadManager by lazy {
        val executor = Executors.newFixedThreadPool(4)
        val isWifiOnly = appContext.getSharedPreferences(
            com.animarvle.app.ui.profile.PlayerSettingsFragment.PREFS_NAME,
            android.content.Context.MODE_PRIVATE,
        ).getBoolean(com.animarvle.app.ui.profile.PlayerSettingsFragment.KEY_WIFI_ONLY, true)

        val requirements = Requirements(
            if (isWifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK,
        )

        DownloadManager(
            appContext,
            databaseProvider,
            downloadCache,
            downloadDataSourceFactory,
            executor,
        ).apply {
            this.requirements = requirements
            maxParallelDownloads = appContext.getSharedPreferences(
                com.animarvle.app.ui.profile.PlayerSettingsFragment.PREFS_NAME,
                android.content.Context.MODE_PRIVATE,
            ).getInt(com.animarvle.app.ui.profile.PlayerSettingsFragment.KEY_CONCURRENT_DOWNLOADS, 1)
        }
    }

    fun updateDownloadRequirements(isWifiOnly: Boolean) {
        downloadManager.requirements = Requirements(
            if (isWifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK,
        )
    }

    fun updateMaxConcurrentDownloads(max: Int) {
        downloadManager.maxParallelDownloads = max.coerceAtLeast(1)
    }

    val downloads: com.animarvle.app.ui.download.DownloadRepository by lazy {
        com.animarvle.app.ui.download.DownloadRepository(appContext, downloadManager, downloadDirectory)
    }
    val myList: com.animarvle.app.ui.mylist.MyListRepository by lazy {
        com.animarvle.app.ui.mylist.MyListRepository(appContext)
    }
    val account: com.animarvle.app.data.account.AccountRepository by lazy {
        com.animarvle.app.data.account.AccountRepository(appContext)
    }
}

/**
 * Demo source used to exercise the M3 player on-device. It is a [FakeSource] whose
 * resolution is pointed at a public, non-anime sample stream so the Player has real
 * media to play. No streaming-service host appears here (PROMPT §3.3).
 */
object DemoSources {

    /** Public HLS test stream (Mux's canonical multi-bitrate sample). Not an anime host. */
    private const val DEMO_HLS = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

    fun fake(): FakeSource = FakeSource().apply {
        // No quality label: the sheet is driven by the levels the player reports (ARCH §6).
        streamOverride = listOf(Stream(DEMO_HLS, StreamKind.HLS))
        responseHeaders = emptyMap()
        subtitleTracks = emptyList()
    }
}