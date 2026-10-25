# ARCHITECTURE.md — Animarvel

Companion to `PROMPT.md` (read that first: rules, scope, screens, acceptance tests).
Tags: `[V]` verified from inspected APK/page · `[I]` inferred · `[D]` design decision · `[?]` open · `[A]` assumption from
memory — **verify before relying on it** (fetch docs/source, cite in `docs/VERSIONS.md` or code comments).

---

## 1. Principles

1. **The UI never knows a plugin by name.** Everything the user can do in Sub/Dub, server, quality, subtitles and
   skip behaves identically for any plugin because it is derived from data the plugin returns (§5–§7).
2. **Metadata and content are separate.** Browse/search/schedule/details come from a metadata provider; playback comes
   from sources matched to that metadata at play time. (Th3-Anime appears to work this way: it queries AniList and then
   reports "match not found for AniList" per provider `[V strings / I]`.)
3. **Fail soft, fall forward.** A failing server triggers the next one automatically; the user is told, not stranded.
4. **Pure-Kotlin core.** `:source-api`, `:domain` and the resolver have no Android dependency so they unit-test on the JVM.
5. **One source of truth per concern** (Room for library/history/downloads, DataStore for settings, in-memory + disk
   cache for network). UI observes `StateFlow`.

## 2. Module layout (Gradle, Kotlin DSL, version catalog) `[D]`

```
:app            Activity, Fragments (XML+ViewBinding), navigation, DI container, theming, workers
:core-ui        theme/tokens, shared views (PosterCard, Badge, Chip, EmptyState, SectionHeader)
:domain         models, repositories (interfaces), use cases, StreamResolver, SourceMatcher   (pure Kotlin)
:data           Room, DataStore, AniList client, repository impls, download manager glue
:source-api     the plugin contract (pure Kotlin/JVM, published as a library for plugin authors)
:source-host    plugin discovery/loading/registry, SourceProvider impls
:source-sample  FakeSource (tests/dev), LocalFilesSource, optional openly-licensed sample source
```
Dependency rule: `app → data, source-host, core-ui → domain → source-api`. `domain` must not depend on Android.

## 3. Layers and data flow

```
Fragment ──observes──> ViewModel (StateFlow<UiState>) ──calls──> UseCase ──> Repository
                                                                          ├─ MetadataRepository (AniList + cache)
                                                                          ├─ LibraryRepository / HistoryRepository (Room)
                                                                          ├─ SourceRegistry ──> MediaSource (plugin)
                                                                          └─ DownloadRepository (Room + Media3 downloader)
```
- UI state = sealed `Loading | Content | Empty | Error(retry)`; one-off events via `Channel`.
- Collect with `repeatOnLifecycle(STARTED)`. No business logic in Fragments.
- Manual DI: `AppContainer` built in `Application`, passed to ViewModel factories `[D]`.

## 4. Domain models (core, source-independent) `[D]`

```kotlin
enum class MediaType { ANIME, MOVIE }
enum class Audio { SUB, DUB, RAW }

data class MediaMeta(                 // from the metadata provider
  val metaId: String,                 // e.g. "anilist:1535"
  val type: MediaType,
  val titles: Titles,                 // english, romaji, native
  val synonyms: List<String>,
  val year: Int?, val episodeCount: Int?, val status: AiringStatus,
  val poster: String?, val banner: String?, val genres: List<String>,
  val synopsis: String?, val score: Double?, val ageRating: String?,
  val nextAiring: NextAiring?         // ANIME only: episode, airingAtEpochSec
  val runtimeMinutes: Int? = null,    // MOVIE
  val releaseDate: String? = null,    // MOVIE, ISO date; drives "Upcoming" state
  val certification: String? = null   // MOVIE age rating shown as the white badge
)
// metaId forms: "anilist:<id>" (ANIME) and "<provider>:movie:<id>" (MOVIE) [D]. A MOVIE has exactly one playable unit.
// TV series are NOT in v1; adding SERIES later = new MediaType + episode metadata from the same provider [D].
```
`LibraryEntry(metaId, status, addedAt, updatedAt)`, `HistoryEntry(metaId, episodeNumber, positionMs, durationMs, audio,
serverKey, qualityId, updatedAt)`, `DownloadEntry(...)` — see §11.

## 5. The source-plugin contract (`:source-api`) `[D]`

Pure Kotlin. Versioned: `const val SOURCE_API_VERSION = 1`. Plugins declare the API version they target; host rejects
unknown majors.

```kotlin
interface MediaSource {
  val id: Long                         // stable, unique per plugin
  val name: String
  val language: String                 // BCP-47, e.g. "en"
  val supportedTypes: Set<MediaType>
  val capabilities: SourceCapabilities // STATIC hints for the Sources screen only (never trusted for behavior)

  // 1) Find the title in this source (used by SourceMatcher with metadata titles/synonyms/year)
  suspend fun search(query: String, type: MediaType, page: Int = 1): List<SourceMedia>

  // 2) Episodes (a movie is one episode, number = 1)
  suspend fun episodes(media: SourceMedia): List<SourceEpisode>

  // 3) THE DYNAMIC TRUTH: what can be played for this episode right now
  suspend fun options(episode: SourceEpisode): EpisodeOptions

  // 4) Resolve one concrete choice to playable streams
  suspend fun resolve(episode: SourceEpisode, choice: Choice): ResolvedMedia
}

data class SourceMedia(val url: String, val title: String, val year: Int?, val episodeCount: Int?,
                       val poster: String?, val type: MediaType, val extra: Map<String, String> = emptyMap())
data class SourceEpisode(val url: String, val number: Float, val title: String?, val thumbnail: String?,
                         val isFiller: Boolean = false, val extra: Map<String, String> = emptyMap())

/** audio -> ordered servers actually available for THIS episode */
data class EpisodeOptions(val byAudio: Map<Audio, List<ServerOption>>)
data class ServerOption(val key: String, val label: String, val hint: String? = null,
                        val audioLanguage: String? = null)  // label shown in chip; language e.g. "ja","en" (movies/dubs)
data class Choice(val audio: Audio, val serverKey: String)

data class ResolvedMedia(
  val streams: List<Stream>,           // >=1; may be a single HLS master or several direct files
  val subtitles: List<SubtitleTrack> = emptyList(),
  val skipTimes: SkipTimes? = null,    // opening/ending ranges in ms
  val headers: Map<String, String> = emptyMap() // referer/UA applied to playback AND download
)
data class Stream(val url: String, val kind: StreamKind /*HLS|DASH|PROGRESSIVE*/,
                  val qualityLabel: String? = null, val height: Int? = null, val bitrate: Int? = null,
                  val headers: Map<String, String> = emptyMap())
data class SubtitleTrack(val url: String, val language: String, val label: String, val format: SubFormat /*VTT|SRT|ASS*/)
data class SkipTimes(val opening: LongRange? = null, val ending: LongRange? = null)

data class SourceCapabilities(val sub: Boolean, val dub: Boolean, val multiServer: Boolean,
                              val qualities: Boolean, val skipTimes: Boolean, val downloadable: Boolean)
```
Contract rules for plugin authors (also enforced by the host):
- `options()` and `resolve()` must be idempotent and cancellable; host applies timeouts (default 15 s, `[D]` configurable).
- Never return a stream the player cannot start; return an error (throw `SourceException(kind, message)`) instead.
- `ServerOption.key` is stable across calls (it is persisted as the user's preferred server).
- Plugins must not show UI. All HTTP goes through host-provided `SourceHttp` when running in-process so timeouts/UA/logging
  are uniform `[D]` (see §13 for the honest limits of this).

## 6. Capability → UI behavior (this is the parity contract)

The host derives UI **only** from `options()` / `resolve()` results — not from `capabilities` (static hints).

| Plugin returns | Host UI behavior (all screens that use the Player) |
|---|---|
| `byAudio` has `SUB` and `DUB` | **Sub \| Dub tabs** both enabled. |
| `byAudio` has only one audio | Other tab **disabled** with the "not available from this source" hint; never auto-switch audio silently. |
| `byAudio` has only `RAW` (typical for movies) | Sub\|Dub tabs **hidden** (not disabled); subtitles button carries the language choice. |
| `MediaType.MOVIE` | Player omits the episode list/search; Details omits Episodes; everything else (servers, quality, subtitles, skip, download) behaves identically. |
| `byAudio[audio]` has ≥2 servers | **Server chips** in order: per-title last-used → user priority (Settings) → plugin order. |
| exactly 1 server | One chip, still shown (consistent layout). |
| Selected server throws/times out | **Auto-fallback** to next server (§7); toast/snackbar "Trying next server…"; chip selection updates. |
| All servers fail | "All servers unavailable" state + Retry + "switch Sub/Dub" hint. |
| `streams` has one HLS/DASH master | **Quality sheet** = *Auto* + levels read from the player's available video tracks `[A: Media3 exposes tracks via Player.currentTracks; verify]`. |
| `streams` has several PROGRESSIVE/HLS variants with `height`/`qualityLabel` | Quality sheet lists them (sorted high→low) + *Auto* = host default rule (§7.4). |
| Single stream with no quality info | Quality item hidden (no empty menu). |
| `subtitles` non-empty | Subtitle button enabled; list + "Off"; honor default-language setting; format support per player `[A verify VTT/SRT/ASS]`. |
| `skipTimes` present | **Skip Intro / Skip Outro** buttons during the ranges; "auto-skip" setting honored. |
| `headers` present | Applied to playback **and** downloads. |
| Source `capabilities.downloadable = false` | Download actions disabled for that source's streams (hint only; real failure also handled). |

Result: installing a new plugin immediately gives the same Sub/Dub / Server / Quality / Subtitle / Skip behavior with
zero UI changes.

## 7. Resolution pipeline (`:domain`)

### 7.1 SourceMatcher (metadata → source entry)
1. Sources are evaluated in strict priority tiers:
   - **Tier 0 (Built-in Native Kotlin Engines):** `FourKHDHubSource` (Movie priority 300), `AllAnimeSource` (Anime priority 300), `AniKotoSource` (Anime priority 290). These run self-contained in pure Kotlin with no external APK requirements.
   - **Tier 1 (User-Installed Extension APKs):** Aniyomi, Tachiyomi, and Cloudstream bridge extensions.
   - **Tier 2 (Fallback / Fixture Sources):** `DemoSources.fake()`.
2. For each **enabled** source supporting `meta.type`: query `search()` with `titles.romaji`,
   `titles.english`, then top `synonyms` (stop at first confident result) `[D]`.
3. Score candidates: normalized-title similarity (case/punct/season-suffix stripped) across all title variants,
   `+` year match, `+` episode-count match (±1), `+` type match. Accept ≥ threshold `[D: tune with fixtures]`.
3b. **Movies:** query by `title` then `title + year`; the **year** is the strongest signal (±1 allowed for regional release
   dates), then title similarity; ignore episode count; reject candidates whose `type` isn't MOVIE `[D]`. Remakes with
   identical titles must be separated by year (test #12 in PROMPT §10).
4. Cache mapping `(metaId, sourceId) → SourceMedia.url` in Room; invalidate on manual re-match or repeated failure.
5. Zero matches in every source ⇒ Details shows **"No source found"** with *Install a source* and *Pick manually*
   (manual search in a chosen source) — handled state, not an error `[D]`.

### 7.2 Episode list
`episodes(sourceMedia)` from the chosen source; merged with metadata (titles/thumbnails/filler when present). If the
source returns fewer episodes than metadata, show source count (truth for playback) `[D]`. Cached (TTL 6 h `[D]`).

### 7.3 Audio + server selection (`StreamResolver`)
```
state: PlaybackSelection(audio, serverKey?, qualityId?)  persisted per metaId
resolve(episode, requested):
  opts = source.options(episode)               // cached per episode, TTL short
  if requested.audio !in opts.byAudio -> return AudioUnavailable(message)   // no silent switch
  order = ordered(opts.byAudio[audio], lastUsed, userPriority)
  if requested.serverKey != null && user picked manually: order = [that server] + (offer fallback after failure)
  for server in order:
     try withTimeout(T): r = source.resolve(episode, Choice(audio, server.key))
         check r.streams.isNotEmpty()
         emit Resolved(server, r); remember lastUsed; return
     catch (Cancel) rethrow
     catch (e): log(sourceId, server, e); emit Fallback(server -> next)
  return AllServersFailed
```
- **Manual server tap** resolves only that server first; on failure the UI offers "Try other servers" (auto order resumes).
- **Mid-playback failure** (player error): same loop starting after the failed server, then seek to last position `[D]`.
- **Dub availability cache**: `options()` results cached per (sourceId, episodeUrl) with TTL `[D 6 h]`; Dub tab
  state also precomputed for the *first* episode on Details to avoid a flicker `[D]`. (Th3 keeps dub/episode caches
  `[V strings]`.)

### 7.4 Quality rules
- Default = user setting: **Auto** | max 1080p | 720p | 480p | **Data-saver** (caps resolution/bitrate on mobile data)
  `[D]` (Th3 has "Adaptive based on network" and a data-saver string `[V strings]`).
- Auto with HLS/DASH master ⇒ adaptive selection by the player; with a flat stream list ⇒ pick highest ≤ cap.
- Manual change: persist `qualityId` for the session (and as default only if user toggles "remember") `[D]`; keep
  position; for adaptive streams apply a track-selection override `[A verify API]`, for flat lists re-prepare
  at the saved position (± 1 s tolerance).

## 8. Player (`:app`, Media3) `[D]`/`[A]`

- Media3 ExoPlayer + HLS (+DASH) modules. Custom control layout in XML matching AniVortex design geometry (lock, speed, ±10 s, fullscreen, PiP, skip 90s, subtitle selector, settings).
- **Unified Dual-Layer Multi-Audio Architecture (`AudioModels.kt`, `PlayerViewModel.kt`, `PlayerFragment.kt`):**
  - **Dynamic Language Aggregator:** Unifies in-stream ExoPlayer audio tracks (`C.TRACK_TYPE_AUDIO`) and server-level language options into a single reactive hierarchy (`ResolvedAudioOption.EmbeddedTrack` vs. `ResolvedAudioOption.ServerStream`), replacing the hardcoded `listOf(defaultOriginalLang, "English")` fallback.
  - **Instant In-Stream Audio Switching:** Automatically detects embedded HLS `#EXT-X-MEDIA:TYPE=AUDIO`, MKV, and MP4 audio tracks via `onTracksChanged`. Instantaneously applies `TrackSelectionOverride` on `C.TRACK_TYPE_AUDIO` without stream reloading or buffering interruption.
  - **Position-Preserving Server-Stream Switching:** For audio languages hosted on separate URLs/servers (e.g., Hindi, Tamil, Telugu, English dub servers), switches server streams dynamically while saving and restoring exact playback timestamp (`savedPositionMs`), resuming playback at the exact second.
  - **ISO 639-1 / 639-2 Normalization (`SpokenLanguage`):** Maps language tags (`hi`/`hin` $\rightarrow$ Hindi, `ta`/`tam` $\rightarrow$ Tamil, `te`/`tel` $\rightarrow$ Telugu, `ur`/`urd` $\rightarrow$ Urdu, `bn`/`ben` $\rightarrow$ Bengali, `ja`/`jpn` $\rightarrow$ Japanese, `en`/`eng` $\rightarrow$ English, `es`/`spa` $\rightarrow$ Spanish, `ko`/`kor` $\rightarrow$ Korean, `zh`/`zho` $\rightarrow$ Chinese, etc.) to user-friendly display labels and clean UI selection states.
- **AniVortex Glass Dialog Architecture & Real-Time Live Blur (`VortexBlurHelper`):**
  - Samples live video frames from hardware `TextureView` via downscaled bitmap (8x), composites visible player HUD elements (SeekBar, timestamps, skip pill), applies multi-pass Gaussian stack blur, warm-obsidian translucent tint (`#B8161618`), and clips to 26dp rounded corners (`BitmapDrawable`).
  - Bypasses OEM system-level restrictions (Samsung OneUI, Xiaomi HyperOS, battery saver modes) where `window.setBackgroundBlurRadius` and `FLAG_BLUR_BEHIND` are globally disabled.
  - Universal across all player dialogs: `ChoiceDialog` (Quality, Speed, Audio, Settings) and `SubtitlesBottomSheet` (`[CC]` subtitles and timing offset popup).
- **Cross-Side Gestures & Control Isolation:** Swiping vertically on the Right half triggers Volume on the Left; swiping on the Left half triggers Brightness on the Right. Triggering either gesture immediately hides `controlsOverlay` to avoid collision with transport controls and the Lock button.
- **Orientation-Aware UI (Portrait vs. Landscape):**
  - Portrait (`!isFullscreen`): 230dp stage height, clean vertical rounded pill SeekBar thumb (`5dp × 18dp`, 3dp radius), hides `btnEpisodes`, `btnLock`, `btnDisplayMode`.
  - Landscape (`isFullscreen`): restores `btnEpisodes`, `btnLock`, `btnDisplayMode`, expands transport button spacing, and dynamically switches SeekBar thumb to a cross-pill (`LayerDrawable`).
- **Progress Fill Math & Feedback:** Active white track terminates cleanly at the 12dp circle thumb center (`thumbOffset + thumbSize / 2f`). Aspect ratio highlights in light-blue (`#38BDF8`), and timestamps format as `%d:%02d`.
- **Material 3 Expressive Morphing Loading Indicator (VortexMorphLoadingView):** Replaces traditional circular spinners with an authentic pure-white morphing rounded polygon that smoothly transitions across 5 geometric phases (Circle <-> Rounded Pentagon <-> 8-Point Soft Star Burst <-> Rounded Diamond <-> Soft Pill) while rotating at 1.5x speed. Sits in an isolated 56dp/62dp center slot (playPauseContainer), completely hiding btnPlayPause (View.INVISIBLE) during buffering/seeking to preserve exact layout geometry between Previous and Next controls.
- **Automatic Matroska / EBML Container Sniffing:** Direct media streams sniff container format automatically, enabling seamless playback of high-definition .mkv multi-audio streams from worker relays.
- **Dual-Mode Episode Layout Architecture (`EpisodeGridAdapter.kt`, `PlayerFragment.kt`):**
  - **Mode A (Thumbnail Grid, Default):** 2-column 16:9 aspect ratio cards with full-bleed screenshots, bottom gradient scrim (`bg_player_scrim_bottom.xml`), bold "Episode : N" label, overview/title subtitle, and 2dp active white highlight border. Backed by non-collapsing `ConstraintLayout` root with `H,16:9` ratio and automatic poster fallback for titles lacking per-episode screenshot assets.
  - **Mode B (Number Tiles):** 6-column anime-style number tiles `[ 1 ] [ 2 ] [ 3 ]...` with active white fill.
  - **Persistent Mode Switching (`btnToggleViewMode`):** Seamlessly toggles grid span count (2 vs 6) and updates icon state (`ic_grid_view` vs. `ic_tile_numbers`), saving selection in `player_episode_view_mode`.
- **Season Dropdown Capsule & Multi-Season Series Preservation:**
  - Option 1 Season Dropdown Capsule (`bg_season_dropdown_pill.xml`) displaying `[ 🗂 Season X ▾ ]` opening `SeasonSelectionBottomSheet`.
  - Distinguishes multi-season series from single movies in catalog resolution, ensuring multi-season TV titles load complete seasons and episode lists instead of collapsing into movie mode.
- Run the player in a `MediaSessionService` for background audio/PiP/notifications when enabled.
- `DataSource.Factory` attaches `ResolvedMedia.headers`/`Stream.headers` per request.
- Progress saved every ~10 s and on pause/stop to `HistoryEntry`; "watched" at ≥90 %.
- Autoplay next: on end, resolve next episode with the **same audio/server/quality selection**, falling back per §7.3.
- Cast, external-player intent, sleep timer: phase 2 (Cast requires your own receiver app id — never reuse another app's) `[D]`.

## 9. Downloads (AndroidX Media3 DownloadManager Architecture) `[V]`

- **Engine:** AndroidX Media3 `DownloadManager` paired with `SimpleCache` and `StandaloneDatabaseProvider` in local app storage.
- **Service:** `Media3DownloadService` extending `androidx.media3.exoplayer.offline.DownloadService`, configured with foreground notifications, download channels, pause/resume, and cancellation.
- **Tracker:** `OfflineDownloadTracker` bridges Media3 download events (`STATE_QUEUED`, `STATE_DOWNLOADING`, `STATE_COMPLETED`, `STATE_FAILED`) into reactive StateFlows, coordinating with `DownloadFragment` and `PlayerFragment`.
- **Persistence & Headers:** Custom `HttpDataSource.Factory` injects source-specific referer and user-agent headers per segment request, ensuring signed or protected HLS streams download correctly.
- **Offline Playback:** When a download completes, `PlayerFragment` automatically plays from `SimpleCache` without contacting remote networks.
- **Storage:** Dynamic card metrics computed from `StatFs` on internal/external storage.

## 10. Metadata, schedule, notifications

**Provider:** AniList GraphQL, host `graphql.anilist.co` `[V: seen in Th3 bundle strings]`.
Fields/args observed in Th3's queries `[V strings]`: `Media(type: ANIME, sort: SCORE_DESC|POPULARITY_DESC|TRENDING_DESC|
FAVOURITES_DESC, format: TV, isAdult: false)`, `title{english romaji native}`, `status format episodes seasonYear
startDate{year} synonyms`, `nextAiringEpisode{episode airingAt timeUntilAiring}`, and `idMal`.
- **Before use**: read AniList's current API docs/terms/rate limits and verify every field `[A]`. Always `isAdult:false` `[D]`.
- **Movies = general feature films** `[V user statement]`, served by a second provider behind the same interface:
  `TmdbProvider` `[D]` (TMDB is a community film/TV database with a public API and a key `[A: from secondary sources;
  read the official docs/terms/attribution first and cite them in `docs/VERSIONS.md`]`). Mapping `[D]`: popular/top-rated/
  upcoming lists → Home movie rows; search + genre/year/sort → Search & Filter; details → runtime, certification,
  genres, similar titles ("More like this"); `include_adult=false` `[A]`.
  **Key policy `[D]`:** no key is shipped; the user pastes their own in Profile › Metadata (PROMPT §7.14); stored in
  `DataStore` (encrypted preferences `[A]` if available). Until set, movie rows are replaced by the setup empty state.
  **Attribution:** the About screen must carry the provider's required attribution text/logo `[A verify]`.
- `CompositeMetadataRepository` routes by `MediaType` (ANIME → AniList, MOVIE → TMDB); the UI only sees `MediaMeta`.
  Zangetsu groups anime and movies in one "streaming" mode `[V README]` but its movie metadata path is **unverified**
  `[?]`; do not assume it uses the same provider, and do not copy its (GPL-3.0) code.
- Anime films (AniList `format: MOVIE`, seen in Th3's queries `[V]`) stay `ANIME`; they appear under *Anime* with a
  "Movie" format filter (PROMPT §7.3b).
- Interface `MetadataProvider` (`trending`, `topAiring`, `topRated`, `search`, `details`, `schedule(dayRange)`,
  `related`) so a second provider can be added without touching UI `[D]`.
- Cache: OkHttp/disk cache + Room for "Continue/Library" metadata snapshots; show cached on offline.
- **Schedule**: query airing entries for a day range; convert `airingAt` (epoch seconds `[A]`) to device-local time.
- **New-episode notifications**: WorkManager periodic job (interval ≥ platform minimum `[A]`, default 1 h `[D]`) compares
  `nextAiringEpisode` of library titles with `last_notified_episode`; posts a notification (channel) and writes the bell list.
  `POST_NOTIFICATIONS` runtime permission requested contextually `[D]`.

## 11. Persistence

**Room** (single DB, migration tests required):
- `library(metaId PK, status, addedAt, updatedAt)`
- `meta_cache(metaId PK, json, fetchedAt)`
- `history(metaId, episodeNumber, positionMs, durationMs, audio, serverKey, qualityId, updatedAt, PK(metaId, episodeNumber))`
- `source_match(metaId, sourceId, sourceMediaUrl, confidence, updatedAt, PK(metaId, sourceId))`
- `episode_cache(sourceId, mediaUrl, json, fetchedAt)`
- `downloads(id PK, metaId, episodeNumber, sourceId, audio, serverKey, qualityId, state, progress, bytes, path, createdAt)`
- `search_history(query PK, updatedAt)`, `notifications(id PK, kind /*NEW_EPISODE|ANNOUNCEMENT*/, metaId?, episodeNumber?, title, body, createdAt, read)`
- Movies reuse every table: `episodeNumber = 1`, `durationMs` = runtime; `library.status` for movies uses Plan to Watch / Completed (Watching = has progress).

**DataStore (Preferences):** theme, default audio, default quality, server priority list, autoplay next, auto-skip,
on-hold speed, external player, PiP/background, subtitle style + default language, download folder URI,
Wi-Fi-only, concurrency, onboarding-seen. (AniLab's APK also bundles MMKV `[V]`; not required here `[D]`.)

## 12. Extension (plugin) loading — `[?]` verify first

- The loader sits behind `interface SourceProvider { fun sources(): Flow<List<MediaSource>> }` so the rest of the app
  never depends on the mechanism. Built-in providers: `FakeSource`, `LocalFilesSource`, sample.
- **Proposed mechanism `[D]`, pending verification `[A]`:** plugins are separate APKs (their own package) that the host
  discovers via `PackageManager` using a feature flag / metadata contract, loads with a class loader, and instantiates
  a `MediaSource` entry class — the model Mihon's `source-api` suggests. **Before coding:** open
  `github.com/mihonapp/mihon` (Apache-2.0 `[V]`), read its extension loader and `source-api`, and cite `path:line` in
  `docs/EXTENSIONS.md`. If you adapt code, preserve Apache-2.0 notices. Do not assume other reference repos' licenses
  (AnymeX is MIT `[V]`; the others are unchecked `[?]`).
- Android 11+ package-visibility (`<queries>`) and install rules `[A]`; `REQUEST_INSTALL_PACKAGES` only if in-app
  install is built (Th3 requests it for its updater `[V]`; AniLab does not `[V]`). Default: user installs plugin APKs via
  the system installer, app detects them.
- Compatibility: reject plugins whose `SOURCE_API_VERSION` major differs; show "needs update" in Sources screen.
- Plugin repositories (index of available plugins by URL) are a convenience, off by default, user-added only `[D]`.

## 13. Security & compliance

- **Honest limit:** an in-process plugin runs with the app's privileges; it is **not sandboxed**. Mitigations `[D]`:
  explicit consent dialog on first install; show plugin package + signature fingerprint; optional allowlist; never pass
  secrets/tokens to plugins; log which plugin made which request in a debug panel.
- `network_security_config`: block cleartext by default; per-plugin override only with a visible warning `[D]`.
- No analytics/ads/Firebase. No secrets or third-party keys from the inspected APKs may be reused (the Th3 bundle
  contains Firebase config — do not copy it).
- The app bundles no content and no scrapers; plugins are third-party and user-installed. This reduces but does not
  eliminate legal risk for what a user installs; the Sources screen must say so (PROMPT §7.13).

## 14. Navigation `[D]`

Single `MainActivity` + NavHost (Navigation component `[V: AniLab uses navigation-fragment 2.8.9]`).
Bottom nav hosts 5 top-level destinations: Home, Schedule, MyList, Download, Profile (each keeps its back stack).
Other destinations: Welcome, Search, Notifications, SeeAll, Details, Player (`sensorLandscape` handled in-fragment),
Settings/PlayerDownloads, Settings/Subtitles, Sources. Player is entered from Home/Details/Library/Downloads with
`(metaId, episodeNumber, optional offline downloadId)`.

## 15. Build & test

- Toolchain starting point from AniLab `[V]`: Kotlin 2.0.21, Gradle 8.11.1, AGP 8.10.1, Java 17 — verify mutual
  compatibility before pinning; all other versions: look up, cite in `docs/VERSIONS.md`.
- Environment note: the planning sandbox had **no Gradle/Android SDK/network `[V]`**. Your environment may. **Run
  `./gradlew assembleDebug` and the unit tests and paste real output before claiming anything works.**
- **Tests:** JVM unit tests for `SourceMatcher`, `StreamResolver` (fake clock, `FakeSource` scripted to fail/succeed),
  mapper/parsing tests with saved AniList fixtures (no network in tests), Room migration tests, ViewModel tests
  (Turbine `[A]`). Instrumented/UI tests for: tab shell, Sub/Dub disabled state, server chip fallback, quality sheet.
- **FakeSource scenarios** (shipped in `:source-sample`): `subOnly`, `subDub`, `flakyServerA`, `allServersFail`,
  `hlsMaster3Levels`, `directQualities`, `withSubtitles`, `withSkipTimes`, `slowResolve`.

## 16. Decision log (change = ask the owner)

| # | Decision | Why |
|---|---|---|
| D1 | Native Kotlin + Views/Fragments | AniLab APK is native Kotlin, no Compose files |
| D2 | Metadata-driven catalog + source matching | Matches Th3's observed model; keeps UI source-agnostic |
| D3 | Capability-by-data UI (§6) | Guarantees plugin parity with no per-plugin UI |
| D4 | 5-tab nav with labels | AniLab mockup + Th3 both show it; AniLab phone build had 4 |
| D5 | No ads/analytics/Firebase/points/updater | Out of scope; privacy |
| D6 | Media3 for playback/offline | AniLab shows ExoPlayer-family strings `[I]`; successor library |
| D7 | Manual DI | No codegen, simplest for an automated agent |
| D8 | Two themes (red/green) via tokens | User: "almost the same with different theme" |
| D9 | Package `com.animarvel.app` (final after brand spelling Q8) | Must not reuse reference packages |
| D10 | MOVIE = general films, one episode, same screens (type chip, no movie section) | Owner: "like Zangetsu but simplified like AniLab"; AniLab has no separate movie UI `[V]` |
| D11 | TMDB via user-supplied key, behind `MetadataProvider` | Avoids shipping a key/terms risk; swappable `[A]` |
| D12 | No code reuse from GPL-3.0 references (Zangetsu) | Licence contamination |
| D13 | Announcements feed optional, default off | Needs owner-hosted JSON; no backend in v1 |
| D14 | Cloudflare Serverless Edge Relay (`worker.js`) | Instant 800ms byte-range streaming; eliminates 4-6MB/s data waste from monolithic 40GB/3.5GB files |
| D15 | Minimal Glassy Player UI | Sub/Dub tab row removed (moved to settings), authentic `[CC]` icon, 2dp timeline bar, anime-exclusive `▶▶ Skip 90s`. **Implemented (2026-10-20):** `fragment_player.xml` `controlsOverlay` rebuilt to reference geometry; new vector assets (`ic_vortex_cc`, `ic_vortex_aspect_ratio`, `ic_vortex_playback_speed`, `ic_tune`); soft 18dp rounded HUD capsule (`bg_vortex_hud_capsule.xml`); cross-side swipe gestures with immediate control isolation; centered 12dp thumb progress math; dynamic vertical-to-cross SeekBar thumb switching; `%d:%02d` time formatting; `VortexBlurHelper` live TextureView frame blur + OEM bypass wired into `ChoiceDialog` and `SubtitlesBottomSheet`. |
| D16 | 3-Server Chip Normalization (`Spiral`, `Turbo`, `FastX`) | Clean normalized 3-server presentation; eliminates messy scraper labels from HUD |
| D17 | Dual-Layer Edge + Extension Architecture | Tier 0 zero-extension streaming for friends/new users + Tier 1 extension fallback for unindexed anime (e.g. One Piece) |
| D18 | Clean Multi-Quality & Multi-Audio Engine | Titlecase language names, Japanese Sub vs English Dub anime switcher, sanitized 1080p-360p quality ladder, coroutine cancellation guard |
| D19 | Resilient Multi-Tier Metadata Fallback (AniList, MAL, Simkl, TMDB) | Stored credentials in .env and BuildConfig; resilient fallback chain ensures anime and movie rails/search remain 100% operational during upstream outages or rate-limits. |

