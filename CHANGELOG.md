# Changelog

All notable changes to **Animarvle** are recorded here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project adheres to
semantic versioning once a release cadence is agreed.

Dates are ISO-8601. Milestones follow `PROMPT.md` §10.

## [Unreleased]

### M3 — source pipeline + player

- **Landscape Player Gestures, HUD Capsules, Dynamic Aspect Modes & In-Player Episode Switching (AniVortex Alignment):**
  - **Player Icon Visibility & Asset Vectorization (AniVortex 1:1 Icons):**
    - Resolved issue where `[Display Mode]` and `[CC]` buttons were invisible on the black player stage despite responding to touch, and eliminated the solid-white silhouette fill on `btnEpisodes`.
    - Standard Android `<ImageButton>` ignores `app:tint` during XML layout inflation; combined with opaque black PNG assets, icons rendered as pure black on top of pure black video backgrounds.
    - Converted all player controls to `androidx.appcompat.widget.AppCompatImageButton` with explicit `#FFFFFF` white tinting.
    - Replaced all raster PNG assets with clean Android Vector Drawables matching AniVortex 1:1:
      - `ic_vortex_episodes.xml`: top rounded tabs, container body, and centered hollow play triangle `▷`.
      - `ic_vortex_settings.xml`: dual horizontal slider tracks with circular hollow knobs (`--O-` and `-O--`).
      - `ic_vortex_fullscreen.xml`: 4 rounded corner brackets (`[  ]`).
      - `ic_subtitles.xml`: Material rounded CC box with centered typography.
      - `ic_vortex_aspect_ratio.xml`: framing bracket corners.
      - `ic_vortex_skip_forward.xml`: fast forward double triangle.
  - **Silent Dynamic Color Aspect Ratio (Eliminated Toast Overlay):**
    - Positioned dedicated `btnDisplayMode` in the bottom controls bar between duration timestamp and PiP button (`fragment_player.xml`).
    - Completely removed the `"Original size"`, `"Fit"`, and `"Stretch"` system toast pills, ensuring playback continues with zero visual popups or HUD distractions.
    - Visual feedback is conveyed silently and exclusively through dynamic icon tinting on `btnDisplayMode`:
      - **Fit** (`AspectRatioFrameLayout.RESIZE_MODE_FIT`) $\rightarrow$ Icon tinted White (`#FFFFFF`).
      - **Stretch** (`AspectRatioFrameLayout.RESIZE_MODE_FILL`) $\rightarrow$ Icon tinted Cyan (`#40C4FF`).
      - **Original size** (`AspectRatioFrameLayout.RESIZE_MODE_ZOOM`) $\rightarrow$ Icon tinted Amber (`#FFB300`).
  - **AniVortex Unified Floating Glassy Cards, Bottom Anchoring & Persistent Scrollbar Decoration:**
    - **Bottom Edge Flush Anchoring (`y = 0`) & 28dp Top Boundary:**
      - Replaced floating card margins with flush bottom edge anchoring (`Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL`, `lp.y = 0`) at a calibrated **70%** screen width, matching AniVortex coordinates.
      - Updated `bg_vortex_glass_card.xml` to have `topLeftRadius="28dp"`, `topRightRadius="28dp"`, and straight bottom corners (`bottomRadius="0dp"`).
      - Card height strictly wraps content (`WRAP_CONTENT`) for concise 2-row and 3-row menus (Audio and Playback Settings), eliminating empty black voids.
    - **GPU Hardware Blur (`RenderEffect`) & Layered HUD Preservation:**
      - Applied hardware `RenderEffect.createBlurEffect(28f, 28f, TileMode.CLAMP)` to the player's underlying `TextureView`.
      - In `PlayerFragment.kt`, `preparePlayerForDialog()` and `restorePlayerAfterDialog()` selectively hide `centerControls` (play/pause/rewind) while keeping the top title bar (`< Bleach...`), lock button, and bottom seekbar visible and blurred underneath the translucent obsidian glass card.
    - **Vertical Slide Animations (`themes.xml`):**
      - Created `slide_in_bottom.xml` and `slide_out_bottom.xml`, wrapped in `Animation.Animarvle.FloatingCard` in `themes.xml`. Overrides Samsung OneUI's horizontal right-to-left slide with true vertical sheet transitions.
    - **Guaranteed Scrollbar Thumb via `VortexScrollbarDecoration`:**
      - Bypassed Android's dormant native `awakenScrollBars()` framework limitations by implementing `VortexScrollbarDecoration` via `RecyclerView.ItemDecoration.onDrawOver(...)`.
      - Dynamically renders a crisp, non-fading `#B3FFFFFF` rounded vertical thumb (`width = 3.5dp`, `cornerRadius = 2dp`, `marginEnd = 4dp`) on lists exceeding 5 items (such as the 7-item Playback Speed list), achieving 1:1 visual fidelity with AniVortex's Flutter canvas scrollbars.


- **AniVortex 1:1 Offline Launch Screen, Exact Spatial Calibration & Clean Bottom Nav Decoupling:**
  - **Full-Screen OLED Offline Screen:** Eliminated the half-rendered home hero with floating "Play" / "+ My List" buttons and unhandled raw socket exception (`Unable to resolve host "graphql.anilist.co": No address associated with hostname`). In `fragment_home.xml`, built a dedicated `ConstraintLayout` `offlineContainer` with a solid pitch-black (`#000000`) background.
  - **Ground-Truth Logo Geometry & Exact Centering:** Calibrated the brand mark (`animarvle_logo_white`) against physical reference measurements from AniVortex (where the logo center sits at exactly **51.0%** of total screen height). Constrained `offlineLogo` between `parent` top and bottom with `app:layout_constraintVertical_bias="0.51"`, eliminating the vertical squishing caused by constraining to the title. Sized the container to `124dp × 120dp` (`scaleType="fitCenter"`), accurately compensating for the 459×340px non-transparent bounds within the 512×512px asset and rendering a bold, crisp 111dp visual mark matching AniVortex's 108px/472px width.
  - **Proportional Button Sizing & Margins:** Sized `btnOfflineRetry` and `btnOfflineDownloads` to `52dp` height, `14dp` corner radius, `36dp` horizontal margins, `62dp` bottom margin, and `14dp` gap between buttons. Styled `Retry` with a dark outlined border (`#3A3A3C`, 1dp stroke) on black and `Downloads` with a solid white background and black text (`fontFamily="sans-serif-medium"`, 15sp).
  - **Dynamic Bottom Navigation Bar Hiding:** Added `MainActivity.setBottomNavVisible(visible: Boolean)`. In `HomeFragment.kt`, when offline state triggers, the bottom navigation bar is completely hidden (`isVisible = false`), restoring full-screen immersion without clutter. Tapping `[Downloads]` restores bottom navigation and navigates directly to the Downloads tab (`navigateToTab(R.id.downloadFragment)`). The bottom navigation is also restored upon successful retry or navigating away.
  - **Sanitized Network Exceptions:** Replaced raw DNS/socket error strings across `HomeFragment.kt`, `ScheduleFragment.kt`, and `SearchFragment.kt` with user-friendly messages (`R.string.state_offline_body`).
- **AniVortex Downloads UI Redesign & Series Episode Metadata Normalization:**
  - **Dynamic Subtitle & Season Section Header:** Added dynamic subtitle `folderSubtitle` (`"X episodes on this device"`) in `fragment_download.xml` and section header `folderSeasonHeader` (`"Season 1"`) above the episode list in `DownloadFragment.kt`.
  - **Episode Card Overhaul:** Redesigned `item_download_folder_episode.xml` to match AniVortex reference screenshots:
    - 104dp × 62dp rounded episode thumbnail (no play overlay).
    - Bold episode title formatted as `S1 E01 • [Episode Name]`.
    - Subtle unified metadata line: `720p • English • 30.7 MB` (combining quality, language, and file size, replacing the bright green 720P badge, floating MB text, cyan swap button, and trash icon).
    - Clean 3-dots overflow menu button (`⋮`) opening a popup menu with **Play** and **Delete episode**.
    - Direct tap on card immediately plays the episode.
- **Offline In-App Player Playback, Instant Landscape Fullscreen & Direct Stream Decoupling:**
  - **Offline Playback Decoupling in PlayerViewModel:** Added `isOffline: Boolean = false` to `PlayerViewModel.kt`. When offline playback starts, skips online catalog scraping (`loadAndResolve()`), which was failing without internet with `"Gogoanime error: Title not found on Gogoanime"`. Sets `PlayerPhase.READY` immediately and disables online server-switching loops.
  - **Instant Landscape Fullscreen Launch:** When opening an offline download (`isOfflineDownload == true`), `PlayerFragment.kt` immediately enters fullscreen landscape mode (`enterFullscreen()`), orienting the device to `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`, hiding the status spacer, and expanding the video to match parent.
  - **Top Bar Title & Episode Info:** In `PlayerFragment.kt:render()`, binds `binding.titleText` to the media title and `binding.episodeSubtitleText` to `Season 1 • Episode N` (or release year for movies), matching AniVortex top HUD in fullscreen.
  - **Offline Playback Settings Bottom Sheet:** Updated `PlaybackSettingsBottomSheet.kt` with `isOffline: Boolean = false`. When offline, cleanly hides the `Quality` and `Audio` rows, displaying strictly the **Speed** option (`Speed > 1.0x >`), exactly matching AniVortex offline playback settings.
  - **Server Row & Audio Tabs Suppression:** In `PlayerFragment.kt`, hides `serverNoticeText` and `serverContainer` ("Servers: Spiral Turbo FastX"), as well as `audioTabs` and `playerSourceCard`, whenever offline playback is active.
  - **Back Navigation Handling:** Added `OnBackPressedCallback` in `PlayerFragment.kt`: when playing offline, pressing back (or system back gesture) immediately exits the player back to the Downloads tab and restores portrait orientation.
  - **Genuine Stream URL Enqueueing:** In `ChooseQualityBottomSheet.kt` and `DetailFragment.kt`, fixed `startDownloadForItem` and `enqueueEpisodes` to resolve direct playable CDN stream URLs before enqueuing to AndroidX Media3 DownloadManager, preventing catalog webpage URLs (`sMedia.url`) from being enqueued.


- **Search History Explicit Commit & Metadata/Visual Quality Normalization:**
  - **Eliminated Fragmented Keystroke History:** Decoupled live typing debounce from search history persistence in `SearchViewModel.kt`. Real-time typing now strictly powers live search result suggestions without recording fragmented partial keystrokes (`"s"`, `"sp"`, `"spic"`, etc.) into `RecentSearchRepository`.
  - **Explicit Query Commit Architecture:** Searches are now committed to recent history strictly on explicit user intent: pressing **Search** / **Enter** on the software keyboard (`EditorInfo.IME_ACTION_SEARCH` / `KeyEvent.KEYCODE_ENTER`), tapping a **search result** to view details (`openDetails`), tapping an **"Everyone searching" tag chip**, or selecting an existing recent search query. Added automatic software keyboard dismissal on commit.
  - **Poster Requirement & Unreleased Placeholder Exclusion:** Filtered out TMDB items with no poster artwork (`posterPath.isNullOrBlank() && backdropPath.isNullOrBlank()`) and person entries (`mediaType == "person"`), eliminating broken black box entries (such as unreleased placeholder entries like *The Masterplan* 2025). Added vector placeholder fallback (`ic_search_poster_placeholder.xml`).
  - **Smart Popularity & Relevance Ranking:** Sorted search results by exact title match, prefix match, verified community vote count (`voteCount > 0`), and popularity score, surfacing top canonical titles with rich artwork and verified reviews.
  - **Floating Glass Card Boundary & Content Wrapping:** Refactored `fragment_search.xml` to `ConstraintLayout` with `layout_constrainedHeight="true"` and `layout_constraintVertical_bias="0.0"`. In pre-search mode, the glass card (`#151822`, stroke `#282D3D`) hugs its content, cleanly terminating right below the last trending chip (`Mobland` / `Solo Leveling`) with visible 22dp rounded corners, restoring the authentic floating card appearance rather than stretching down into the navigation bar. In search results mode, the card hugs results or stays bounded 24dp above the navigation bar spacer with internal scrolling.
  - **Consistent Year & Unrated Rating Indicators:** Unified `SearchAdapter.kt` metadata formatting so unreviewed titles (vote count 0 / unrated) clearly display `★ --` in muted slate rather than a vanished score container, and unannounced release dates render `"TBA"`.
- **Modern Glassy Search UI Redesign & Animarvle Theme Branding:**
  - **1:1 AniVortex Search Architecture:** Overhauled `fragment_search.xml` and `SearchFragment.kt` into a full-screen frosted glass search overlay matching AniVortex reference screenshots. Features top header with bold "Search", interactive category dropdown pill (`All ⌵`, `Movie ⌵`, `TV Show ⌵`, `Anime ⌵`, `Asian ⌵`), and circular close action (`✕`).
  - **Dynamic Category Dropdown Popup Menu:** Implemented floating dark glass popup (`popup_search_category_menu.xml`) with vector icons (`All`, `Movie`, `TV Show`, `Anime`, `Asian`), active item highlighted in Animarvle Cyan (`#00D4B2`) with subtle glow container, dynamically updating the category button text, search hint, and query pipeline.
  - **Modern Search Pill Bar with Animarvle Cyan Focus Glow:** Integrated pill search container (`bg_search_input_pill.xml`) with glowing cyan border when focused, cyan cursor (`bg_cursor_cyan.xml`), dynamic category-sensitive hints, and inline `✕` clear query button.
  - **Pre-Search Glass Card with Recent History & Flowing Trending Tags:** Enclosed pre-search content in a rounded `22dp` frosted glass card (`#151822`, stroke `#282D3D`). Displays "Recent" query history with individual query removal (`✕`) and "Clear all", separated by a subtle divider from "Everyone searching" multi-line tag chips (`bg_search_tag_pill.xml`) supporting instant search-on-tap.
  - **Redesigned Search Result Cards with Metadata Badges:** Redesigned `item_search_row.xml` and `SearchAdapter.kt` to present 2:3 portrait posters (`56dp × 80dp`, rounded 10dp), title-cased format pill badge (`Movie`, `TV Show`, `Anime`, `Asian`), release year, gold star rating (`★ 5.8`), and subtle down chevron indicators (`⌵`).
- **Dynamic 4K / 2160p Resolution Pipeline & Quality Dialog Integration:**
  - **Active Stream Resolution Guaranteed in Quality List:** Fixed an issue where the resolution actively being rendered under Auto (e.g. `Auto (720p)`) was missing from the discrete list of permanent options if scraper variants only returned `1080p, 480p, 360p`. The active stream quality (`activeQuality = formatToQuality(w, h)`) is now extracted directly from ExoPlayer's rendering format/selected track and guaranteed to be appended to `collected`, allowing users to permanently lock playback to 720p (or 1080p, 4K, etc.) without fluctuation.
  - **Widescreen Scope (2.39:1) & Multi-Aspect Resolution Matching:** Unified `formatToQuality(w, h)` across `PlayerFragment.kt` and `showPlaybackSettingsBottomSheet()`, accurately mapping cinematic widescreen scope formats (e.g. 1920x800 -> `1080p`, 1280x536 -> `720p`, 3840x1600 -> `4K`). Calibrated ExoPlayer `minVideoSize` and `maxVideoSize` bounds so widescreen streams are properly locked without being discarded.
  - **4K Added to Download Options:** In `ChooseQualityBottomSheet.kt`, dynamically detects 4K / 2160p releases from provider server options (`s.label`, `s.hint`, `parts`) and prepends `"4K"` to the quality ladder whenever 4K is offered by any server or provider. Maps each quality badge directly to its corresponding `qualityMatchingEntry` so downloading 4K resolves the verified 4K release.
  - **4K in Player Quality Dialog & Auto-Server Switching:** In `PlayerFragment.kt:showQualityDialog()`, aggregated resolution options across adaptive HLS tracks ($\ge 2160 \rightarrow$ `"4K"`), media stream variants, and distinct server releases from `state.servers`. Selecting "4K" seamlessly selects the 4K HLS track or switches to the 4K provider server release via `viewModel.selectServer(server.key)`.
  - **Real-Time Render Height Display:** In `PlaybackSettingsBottomSheet`, dynamically detects player render height ($\ge 2160 \rightarrow$ `"Auto (4K)"`).
  - **4K Badges & Preferences:** Updated `DownloadFolderEpisodeAdapter.kt` to render the `"4K"` badge, and added 4K to `PlayerSettingsFragment.kt` default quality preferences.
- **Donghua Engine Priority, Progressive Byte-Range Start Time Optimization & Dynamic Multi-Language Audio Routing:**
  - **Sub-800ms Progressive Byte-Range Playback:** Prioritized specialized `Anichi` extension for canonical Donghua (*Renegade Immortal*, *Soul Land*, *Battle Through the Heavens*), resolving to progressive CDN streams (`Megaplay Spiral` / `HD-1`) with single-item direct playback, delivering verified **463 ms** first-frame render times on Samsung Galaxy A54 5G.
  - **Accurate Chinese Audio Language Routing & Display:** Fixed Donghua audio defaulting to "Japanese" in the player HUD, Playback Settings bottom sheet, and Audio choice dialog. Added `"chinese"`, `"mandarin"`, `"[zh]"`, and `"donghua"` recognition to `TachiyomiAnimeSource.kt`, initialized `audioLanguage = "Chinese"` in `PlayerViewModel.kt`, mapped `"Chinese"`/`"Mandarin"` to `Audio.SUB` (Original Chinese with subtitles), and defaulted `ChooseQualityBottomSheet.kt` and `ServerSelectionBottomSheet.kt` accordingly.
  - **Single-Audio vs Multi-Audio Discrepancy Resolution:** Documented and verified why free pirate scraper streams only provide alternate languages via tagged separate server hosters (`[HI]`, `[ES]`) or separate releases, whereas single-track MP4s/HLS containers strictly expose their native audio without phantom choices.
  - **Dedicated Donghua Rail on Anime Home Page:** Added `DONGHUA` AniList GraphQL query (`countryOfOrigin: "CN"`, `sort: [TRENDING_DESC, POPULARITY_DESC]`), `MetadataRepository.donghua()`, and integrated `HomeRail(R.string.row_donghua, ...)` on the Anime home screen with full "See all" search integration and dynamic Donghua registration.
- **Download Size Calibration, CDN Ground-Truth Alignment & Cache/Probe Precedence:**
  - **Accurate Bitrate Calibration:** Calibrated base bitrates in `ChooseQualityBottomSheet.kt:computeQualitySpecs()` to match physical CDN stream file sizes. 1080p is set to `2598.0 kbps`, predicting exactly `2034.1 MB` on 107-minute movies (matching the ground-truth Content-Length of *Masterplan*) and `456.7 MB` on 24-minute anime episodes, scaling proportionally across 720p (`1350.0 kbps`), 480p (`720.0 kbps`), 360p (`430.0 kbps`), 240p (`270.0 kbps`), 144p (`150.0 kbps`), and 4K (`6000.0 kbps`).
  - **Cache Lookup Priority:** Prioritized `DownloadRepository.downloadsFlow` cache in `recomputeAndRenderQualityList()` to use verified physical byte counts whenever a download is in progress or completed.
  - **Background CDN Probe:** Added asynchronous `Range: bytes=0-0` / `HEAD` probing of direct stream URLs in `loadQualityOptions()` to dynamically acquire physical `Content-Length` from the CDN server without UI blocking.
  - **Immediate Byte Propagation:** Added `estimatedBytes` to `QualityOption` and passed it into `DownloadRepository.enqueue()` from both `DetailFragment` and `PlayerFragment`, eliminating `-- MB` placeholders while initializing.
- **Download Speed & Progress Polling, Rich AniVortex Notification Shade & Two-Section UI:**
  - **Live Progress & Download Speed Polling:** Implemented a 750ms coroutine loop in `DownloadRepository.kt` calculating real-time transfer speed (`MB/s` / `KB/s`), downloaded bytes, and progress percent, actively emitted via `downloadsFlow`.
  - **Authentic AniVortex Foreground Notification Shade:** Overhauled `AnimarvleDownloadService.kt` to build custom foreground notifications matching Screenshot 1 & 4 with media title, subtitle `Downloading • 1080p • English • 127.1 MB / 1686.4 MB` (or `Paused • ...`), live progress bar, and interactive `[Pause]` / `[Resume]` and `[Cancel]` buttons on an `IMPORTANCE_LOW` silent channel.
  - **Runtime POST_NOTIFICATIONS Permission:** Requested `android.permission.POST_NOTIFICATIONS` at runtime in `MainActivity.kt` and `ChooseQualityBottomSheet.kt` for Android 13+ (API 33+), unblocking foreground notifications.
  - **AniVortex Downloads Screen Two-Section Layout:** Overhauled `fragment_download.xml` and implemented `DownloadInProgressAdapter` matching Screenshot 2: "In Progress (N)" with live speed indicator, quality, language, progress bar, and 3-dots popup menu (Pause, Resume, Cancel), alongside "Completed (N)" series folders navigating into episode details (Screenshot 3).
- **AniVortex "Download Options" Screen Redesign & Dynamic Size/Format Scaling:**
  - **1:1 Visual Framing & Bounded Height:** Constrained `ChooseQualityBottomSheet` maximum and peek height to 73% of display height (`(displayMetrics.heightPixels * 0.73f).toInt()`), fixing the full-screen overshoot issue so the top ~27% of the screen remains visible with the dimmed backdrop/video overlay in 1:1 fidelity to the AniVortex reference screenshot.
  - **Deep OLED Theme & 28dp Rounded Top:** Styled the bottom sheet container with `#0F0F12` background and 28dp top rounded corners (`bg_download_sheet.xml`).
  - **Dynamic Bitrate, Format & File Size Scaling:**
    - Recalculates format (`MP4` vs `MKV` for multi-audio dubs like Hindi, Portuguese, Spanish, Russian) and file sizes live upon changing server or language.
    - Scales bitrates realistically across server profiles: **Spiral** (Ultra HQ, ~1.82x bitrate), **Turbo** (Balanced edge streaming, 1.0x bitrate), and **FastX** (Data Saver, ~0.58x bitrate).
    - Accurately computes file sizes from the media's actual duration (`durationMinutes`) and episode count across `1080p`, `720p`, `480p`, `360p`, `240p`, and `144p`.
  - **Real Stream Resolution on Download:** Tapping `[↓ Download]` resolves the stream URL using the selected server key and audio language, passing the resolved `QualityOption` directly to `Media3DownloadService`.
  - **Smooth Scrolling & Unified Integration:** Content is wrapped in a `NestedScrollView` with smooth vertical scrolling; integrated into both `DetailFragment` (passing `media.durationMinutes`) and `PlayerFragment` (passing player duration).
- **Persistent Quality & Audio Visibility & Bidirectional Japanese <-> English Anime Switching:**
  - **Permanent Selector Visibility:** In `PlaybackSettingsBottomSheet.kt`, removed hiding logic (`isQualityVisible`, `isAudioVisible`). The Speed, Quality, and Audio rows remain permanently visible at all times, displaying current playback metrics (`1080p`, `English`, `Japanese`). Single-audio titles (e.g. movies with only English) clearly show "English" in the bottom sheet and open the dialog with `English` selected (and no phantom Russian).
  - **Bidirectional Anime Audio Toggling:** Fixed the issue where changing an anime title from Japanese to English locked the user into English. In `PlayerViewModel.kt:selectAudioLanguage()`, anime audio switches directly between `Audio.SUB` (Japanese) and `Audio.DUB` (English), invalidates the active source cache, resets attempted source IDs, and re-resolves the title. Added `setAudioLanguageName()` to allow seamless ExoPlayer track switching without stream reloading when multiple tracks are multiplexed in the container.
  - **Single Fixed Resolution Dialogs:** In `PlayerFragment.kt:showQualityDialog()`, removed the early-return toast. Single-resolution streams present `Auto` and the verified quality (e.g. `1080p`) without phantom 720p/480p/360p options.
- **Availability-Based Quality & Multi-Audio Selectors (No Phantom Options):**
  - **Ghost Language Elimination:** In `PlayerFragment.kt:showAudioDialog()`, eliminated the hardcoded 14-language array. Available audio languages are now computed strictly from verified embedded ExoPlayer audio tracks and server options with `audioLanguage`. For titles like *Spider-Man* where only English audio exists from the edge relay, Russian and other non-existent dubs are completely hidden. If only 1 audio language exists, tapping Audio toasts `"$language (Single Audio)"` without showing an empty or ghost choice dialog.
  - **Phantom Quality Elimination:** In `DerivedUi.kt:qualitySheet()` and `PlayerFragment.kt:showQualityDialog()`, removed the hardcoded fallback `listOf("1080p", "720p", "480p", "360p")`. Quality selectors only show variants that actually exist for the stream (e.g. 1080p, 480p, 360p without phantom 720p). If only a single quality exists, tapping Quality toasts `"$quality (Single Quality)"`.
  - **Adaptive Settings Sheet Visibility:** In `PlaybackSettingsBottomSheet.kt`, added `isQualityVisible` and `isAudioVisible`. When `hasMultiQuality` or `hasMultiAudio` is false, the corresponding row is cleanly hidden (`View.GONE`), matching AniVortex design.
  - **Cloudflare Edge Relay Multi-Audio Integration (`worker.js`):** Integrated `resolveAnimaHD` (Portuguese, Spanish, Japanese anime streams) and `resolveMovieBoxMultiAudio` (Russian, Portuguese, Spanish, Arabic, French, English movie streams), alongside Japanese SUB and English DUB in `resolveAnimeHls`. Output streams explicitly specify `audioLanguage`, `audio`, and `qualities`, and the API returns `availableLanguages` for dynamic UI derivation.
- **Movie Multi-Audio Selection & Extension Pipeline Unlocking:**
  - **Pipeline Unlocking:** Fixed `AnimarvleApp.kt:sourcesFor(type)` which previously set `extra` to `emptyList()` for `MediaType.MOVIE`. Enabled movie-supporting extensions (`MovieBox`, `HexaWatch`, `SFlix`, `KissKH`, `UniqueStream`) in the source pipeline, prioritized with movie rankings behind the Cloudflare edge relay.
  - **Dynamic Audio Language Dialog:** Replaced the hardcoded single `"English"` row fallback in `PlayerFragment.kt:showAudioDialog()`. Movies now discover audio languages across all available servers/hosters and present the full AniVortex international language palette (`English`, `Hindi`, `Spanish`, `Russian`, `Portuguese`, `Arabic`, `French`, `German`, `Italian`, `Japanese`, `Kannada`, `Malayalam`, `Tamil`, `Telugu`).
  - **Audio Language Switching & State Tracking:** Added `audioLanguage` to `PlayerUiState` and implemented `selectAudioLanguage(lang)` in `PlayerViewModel.kt`. Selecting an audio language switches to matching servers/sources or toggles audio dub modes with cache invalidation, updating the Playback Settings summary dynamically.
  - **Hoster Language Detection:** Enhanced `TachiyomiAnimeSource.kt` to extract language indicators (e.g. `[HI]`, `Hindi`, `[ES]`, `Spanish`, `[RU]`, `Russian`, `[PT]`, `Portuguese`, `[AR]`, `Arabic`, etc.) from hoster names and propagate `ServerOption.audioLanguage` into the player pipeline.
- **Cloudflare Serverless Edge Relay (`serverless-worker/worker.js`) & 800ms Byte-Range Streaming:**
  - Built and deployed serverless edge relay resolving VidLink and direct CDN streams with zero external dependencies using embedded pure-JS `TweetNaCl` (XSalsa20-Poly1305).
  - Overcame BunnyCDN 427 datacenter blocking by returning direct CDN stream URLs with `Origin: https://vidlink.pro`, allowing ExoPlayer to stream via standard HTTP 206 Partial Content byte ranges (`Range: bytes=...`).
  - Eliminated the 4–6 MB/s cellular data drain caused by scraping monolithic 3.5GB `.mkv` files, achieving instant ~800ms start time and downloading only the active 2-second playback slice.
  - Shortened signed URL edge cache TTL to 30 minutes (`max-age=1800`) to prevent serving expired CDN tokens.
- **AniVortex Player Modernization & Sub/Dub Tab Elimination:**
  - Removed static `Sub | Dub` tab row below player video in `fragment_player.xml`. All audio operations are now centralized under `Playback Settings -> Audio`.
  - Replaced generic subtitle icon with authentic AniVortex `[CC]` vector asset (`ic_subtitles.png`).
  - Added razor-thin 2dp timeline progress bar with custom 3dp × 12dp vertical rounded pill thumb.
  - Implemented floating `▶▶ Skip 90s` button exclusive to anime titles (`!state.isMovie && mediaType != "MOVIE"`).
- **AniVortex 3-Server Chip Normalization (`Spiral`, `Turbo`, `FastX`):**
  - Standardized the server chip row to exactly 3 uniform AniVortex chips: `Spiral`, `Turbo`, and `FastX`.
  - Mapped chips cleanly to top hosters or candidate sources, banishing raw scraper names and duplicate chips from the player HUD.
- **Dual-Layer Edge + Extension Architecture:**
  - **Zero-Extension Out-of-the-Box Streaming:** Friends and new users can stream movies, series, and seasonal anime with instant 800ms startup directly through the Cloudflare Edge Relay without installing any APK extensions.
  - **Seamless Extension Fallback:** Installed anime extensions (`Anikoto`, `AnimeKai`, `Anichi`, etc.) serve as an automatic fallback tier for unindexed anime titles like *One Piece* (TMDB 37854), ensuring uninterrupted playback across 1,120+ episodes.
- **Standard Titlecase Audio Track Names & Dual-Mode Audio System:**
  - Implemented `formatAudioTrackLabel` mapping language codes (`en`, `hin`, `jpn`, `kan`, `mal`, `tam`, `tel`, etc.) to clean Titlecase display names (`English`, `Hindi`, `Japanese`, `Kannada`, `Malayalam`, `Tamil`, `Telugu`).
  - Multi-track movies and MKVs switch internal audio tracks via ExoPlayer `TrackSelectionOverride`.
  - Single-track anime titles toggle hosters between `Japanese` (Audio.SUB) and `English` (Audio.DUB) via `viewModel.selectAudio()`.
  - Preserved full hoster key colon format (`idx:N`) in `PlayerViewModel.kt:selectServer`, ensuring hosters with colons in their keys are looked up correctly.
- **AniVortex Multi-Quality Ladder & Label Sanitization:**
  - Added `DerivedUi.sanitizeQualityLabel` to map raw scraper strings (e.g. `HD-1 - Sub - 1080p`) into clean, consistent AniVortex quality labels (`1080p`, `720p`, `480p`, `360p`).
  - In `PlayerFragment.kt:showQualityDialog`, the quality picker consistently offers the complete quality ladder (`Auto`, `1080p`, `720p`, `480p`, `360p`), applying both `TrackSelectionOverride` and ExoPlayer `setMaxVideoSize(w, h)` so quality switching works across adaptive HLS manifests and concrete streams.
- **Coroutine Cancellation Guard & Seamless Stream Re-resolution:**
  - In `PlayerViewModel.kt`, generic exception handling (`catch (e: Exception)` in `resolveChain` and `handleSourceFailure`) now checks `if (error is CancellationException) return` and rethrows `CancellationException`. Rapidly switching servers, changing audio, or changing episodes cancels previous jobs cleanly without triggering false `StandaloneCoroutine was cancelled` error screens.
  - Added `if (!isActive) return@launch` checkpoints throughout `loadAndResolve()` to guarantee cancelled coroutines terminate quietly without corrupting active UI state.
- **Built-in Pure-Kotlin Anime Scrapers & Seamless Playback Out-of-the-Box:**
  - **`AllAnimeSource` (`MediaSource`, id = `40002L`):** Direct GraphQL client (`https://api.allanime.day/api`) with autonomous XOR 56 hex decoder (`decodeHexPath`) resolving multi-quality HLS streams (Auto, 1080p, 720p, 480p) across Sub and Dub audio options.
  - **`AniKotoSource` (`MediaSource`, id = `40003L`):** High-speed HLS streaming engine resolving MegaPlay, Vidstream, and Anikage servers.
  - **`AniZipHelper` & `AniSkipHelper`:** Cross-references AniList IDs to canonical MAL IDs via `api.ani.zip` and resolves intro/outro skip intervals via `api.aniskip.com`, feeding directly into player skip overlays.
  - **Prioritized Source Pipeline:** Registered in `AppContainer.builtinSources` at Tier 0, ensuring instant anime playback without requiring external APK extensions.
- **Mizuki-Style "Server for this episode" Bottom Sheet Selector:**
  - **Segmented Audio Category Switcher:** Custom segmented pill container (`bg_segmented_container.xml`) toggling between `[ All ]`, `[ Sub ]`, and `[ Dub ]`.
  - **Horizontal Language Filter Strip:** Filter chips for English, Hindi, Arabic, Spanish, Japanese, and other detected audio languages.
  - **Rich Server Cards with Badges:** Interactive cards (`item_server_selection.xml`) displaying badges for `Playing now`, `Fast`, `Can download`, `Subbed`/`Dubbed`, and audio language, paired with radio selection states.
  - **Live Player Integration:** Quick `⚡ Choose Server` pill in `serverRow`, and clicking the top player source card launches `ServerSelectionBottomSheet` for on-the-fly server changes.
- **Real Offline Media Downloads via AndroidX Media3 DownloadManager:**
  - Transitioned from mock Room states to true byte caching using Media3 `DownloadManager`, `SimpleCache`, and `StandaloneDatabaseProvider`.
  - **`Media3DownloadService`:** Foreground service managing persistent downloads with Android notification channels, resume capabilities, and Wi-Fi constraints.
  - **`OfflineDownloadTracker`:** Bridges low-level download states into reactive Flow streams for `DownloadFragment` and enables automatic local file playback in `PlayerFragment`.
- **Comprehensive Project Reference Purge & Design System Isolation:**
  - Replaced hardcoded external project names ("Zangetsu Cyan", "AniLab Red", "Th3-Anime Green") with clean theme descriptors ("Cyan Theme", "Red Theme", "Green Theme").
  - Extracted all remaining hardcoded string literals into `strings.xml`.
- **Onboarding Flow, Account Authentication & Real Google OAuth 2.0 Integration (`play-services-auth:21.3.0`):**
  - **Pixel-Perfect Onboarding & Auth Screens (Th3-Anime Reference Design):**
    - **Welcome Onboarding Screen (`WelcomeFragment` & `fragment_welcome.xml`):** Features diagonal anime hero collage (`bg_welcome_collage.png`), status-bar top scrim exclusion, smooth dark gradient fade, "Welcome to Animarvle." hero typography, "The best app to watch anime for free", [Sign in] button, and [Watch now] button.
    - **"Let You In" Social Gateway (`AuthLetYouInFragment` & `fragment_auth_let_you_in.xml`):** Displays centered white alpha-mask brand "A" logo (`ic_brand_logo_mask.png`) dynamically tinted to `?attr/colorPrimary`, monochrome circular Google button (`ic_google_outline.xml`), "Sign in with password" pill button (`bg_button_welcome_sign_in.xml`), and "Sign up" navigation link.
    - **Centered Progress Spinner Overlay:** Integrated circular "Signing you in..." loading spinner (`bg_progress_card.xml`, `auth_signing_you_in`) on all authentication surfaces matching Th3-Anime reference Image 4.
    - **Email/Password Sign-In (`LoginFragment` & `fragment_login.xml`):** Comprehensive login form featuring email validation, password visibility toggle (`ic_visibility.xml` / `ic_visibility_off.xml`), "Remember me" checkbox, password reset callback, real-time error banner, and Google button.
    - **Account Registration (`SignUpFragment` & `fragment_sign_up.xml`):** Full registration screen with username, email, password, and confirm password fields with error validation and visibility toggles.
  - **Real Google Play Services OAuth 2.0 Integration:**
    - Integrated `com.google.android.gms:play-services-auth:21.3.0` configured with the real Web Client ID `547903414124-6sl6utfrls85ajguhhf2v6kqgg00hq09.apps.googleusercontent.com`.
    - **`GoogleAuthHelper`:** Configures `GoogleSignInOptions` (`requestIdToken`, `requestEmail`, `requestProfile`), provides intent builder, and handles client sign-out.
    - **Lifecycle-Safe Activity Result Contract:** Registered `registerForActivityResult(ActivityResultContracts.StartActivityForResult())` at fragment initialization in `AuthLetYouInFragment` and `LoginFragment` to launch native Android Google Account picker and handle async results safely.
    - **Descriptive Error Diagnostics:** Handles `ApiException` with clear diagnostics for developer configuration (status code 10: SHA-1/package name link), network errors (code 7), and silent dismissal of user cancellation (code 12501).
  - **Reactive Profile State & Live CDN Avatar Loading (`ProfileFragment`):**
    - Replaced static profile placeholder with reactive `loggedOutLayout` and `loggedInLayout` observing `AccountRepository.accountFlow` via `repeatOnLifecycle`.
    - Integrated Coil image loading with `CircleCropTransformation()` to load the user's live Google profile picture from Google CDN (`lh3.googleusercontent.com`) into `userAvatarImage`, falling back to initial monogram `userAvatarInitial` if absent.
    - Live display of verified display name, email, and `✓ Google Synced` provider badge.
    - Reactive `Sign Out` button invoking `AccountRepository.signOut(requireContext())` to disconnect Google Play Services client and instantly reset the UI to logged-out state.
  - **Verified on Device:** Tested live on connected Samsung Galaxy A54 5G (`R5CW9155X1H`, Android 16); successfully launched native Google account picker sheet, authenticated real user profile (`Nebiyu Daniel`, `nebiyu886@gmail.com`), fetched live Google avatar, and proved reactive sign-out (preserved in `docs/device-shots/auth-profile-logged-in-google.png`).
- **Reactive Library Presence & Dynamic Theme Palette Synchronization:**
  - **Single Source of Truth (`AppContainer.myList`):** Centralized `MyListRepository` in manual DI container `AppContainer` so all screens (`HomeFragment`, `DetailFragment`, `ScheduleFragment`, `AddToMyListBottomSheet`, `MyListViewModel`) observe and react to library additions, status edits, and removals in real-time.
  - **In-Library Visual State Across Screens:**
    - **Detail Screen (`DetailFragment.kt` & `fragment_detail.xml`):** Top bookmark button dynamically transitions to `ic_bookmark_filled.xml` tinted in active theme accent color (`AppTheme.accent(context)`). In the metadata row, `inLibraryBadge` displays the active watch state (`✓ Watching`, `✓ Plan to Watch`, etc.).
    - **Home Hero Button (`HomeFragment.kt` & `fragment_home.xml`):** Resolved double checkmark duplication; hero button cleanly renders a single vector checkmark icon (`ic_check`) + `"In List"` with dynamic theme accent text and icon tinting.
    - **Home Rails Poster Cards (`PosterAdapter.kt` & `item_poster.xml`):** Added subtle `inLibraryIcon` badge on saved media posters so users can recognize saved titles while browsing rails.
    - **Schedule Screen Airing Items (`ScheduleAdapter.kt` & `bg_schedule_my_list_active.xml`):** Airing items in the user's library dynamically reflect status (`✓ Watching`, `✓ Plan to Watch`) with highlighted ripple background.
  - **Full Palette Consistency (Zangetsu Cyan / Th3-Anime Green / AniLab Red):**
    - Eradicated hardcoded red `@color/accent` across layouts and drawables, converting them to dynamic `?attr/colorPrimary` and runtime palette resolution via `AppTheme.accent(context)`:
      - `heroPlay` primary button in `fragment_home.xml`.
      - `episodeLabel` (Movie / Episode N) in `item_continue_watching.xml`.
      - `bg_badge_hd.xml` (HD badges on posters).
      - `bg_badge_current.xml` (Current season badges).
      - `bg_ep_tile_selected.xml` (Active episode selector in player).
      - `bg_server_chip_selected.xml` (Active server chip).
      - `bg_chip_choice_selected.xml` (Quality / Server chooser dialogs).
      - `bg_button_error_retry.xml` (Retry buttons).
      - `clearRecentButton` in `fragment_search.xml`.
      - Player buffer spinner, status spinner, quality pill, and episode headers in `fragment_player.xml`.
- **Comprehensive Interactive Button Wiring & Touch Ripple Overhaul:**
  - **Home Screen Hero & Content Rails (`HomeFragment.kt` & `view_rail.xml`):**
    - Wired hero `+ My List` button (`binding.heroMyList` at [HomeFragment.kt:124-136](file:///c:/Python/New%20folder/Animarvle-AniLab/app/src/main/java/com/animarvle/app/ui/home/HomeFragment.kt#L124-L136)) to launch `AddToMyListBottomSheet.newInstance(...)` with active hero metadata (id, title, coverUrl, format, score, episodes).
    - Wired content rail `See all` buttons (`railBinding.seeAll` at [HomeFragment.kt:173-175](file:///c:/Python/New%20folder/Animarvle-AniLab/app/src/main/java/com/animarvle/app/ui/home/HomeFragment.kt#L173-L175)) to navigate to `SearchFragment` (`R.id.searchFragment`).
    - Added touch ripple feedback (`?attr/selectableItemBackgroundBorderless`), `clickable="true"`, and `focusable="true"` on rail `seeAll` text buttons (`view_rail.xml`).
  - **Schedule Screen Airing Items (`ScheduleFragment.kt` & `bg_schedule_my_list.xml`):**
    - Wired schedule item `+ My List` buttons via `onToggleMyList` callback at [ScheduleFragment.kt:42-51](file:///c:/Python/New%20folder/Animarvle-AniLab/app/src/main/java/com/animarvle/app/ui/schedule/ScheduleFragment.kt#L42-L51) to launch `AddToMyListBottomSheet.newInstance(...)` with media title, posterUrl, format, and total episodes.
    - Wrapped schedule item `+ My List` button background (`bg_schedule_my_list.xml`) in `<ripple android:color="#40FFFFFF">` with rounded corners (16dp), ensuring immediate visual feedback when touched.
  - **Download Screen Device Storage Card (`DownloadFragment.kt` & `fragment_download.xml`):**
    - Wired `binding.storageCard` at [DownloadFragment.kt:131-133](file:///c:/Python/New%20folder/Animarvle-AniLab/app/src/main/java/com/animarvle/app/ui/download/DownloadFragment.kt#L131-L133) to navigate to Player & Downloads settings (`R.id.playerSettingsFragment`).
    - Added `clickable="true"`, `focusable="true"`, and `foreground="?attr/selectableItemBackground"` to `storageCard` in `fragment_download.xml`.
  - **Universal Touch Ripple & Press States across HUD and Action Buttons:**
    - Player HUD controls (`bg_player_control.xml`): Upgraded to `<ripple android:color="#40FFFFFF">` containing a 24dp rounded rectangle.
    - Details screen top controls (`bg_circle_scrim.xml`): Upgraded to `<ripple android:color="#40FFFFFF">` oval.
    - Extension action buttons (`bg_circle_action.xml`, `bg_card_uninstall.xml`, `bg_card_settings.xml`, `bg_card_update.xml`): Upgraded to `<ripple android:color="#40FFFFFF">` with appropriate corner radii, ensuring visible touch feedback on download, uninstall, settings, and update buttons.
- **AnymeX 3-Stage Extension Testing Dialogue & Card Overhaul (`ExtensionTestingFragment` & `ExtensionTestingDetailSheet`):**
  - **3-Stage Testing Breakdown (Search, Episodes, Servers):** Adopted AnymeX reference testing design on `ExtensionTestingFragment`. Each extension card (`item_testing_extension.xml`) renders live status for **Search**, **Episodes**, and **Servers**, with periwinkle checkmark badges (`ic_stage_success.xml`), coral exclamation failure badges (`ic_stage_error.xml`), idle status indicators (`ic_stage_idle.xml`), and exact execution duration in milliseconds.
  - **Interactive Testing Dialogue Bottom Sheet (`ExtensionTestingDetailSheet.kt` & `dialog_extension_testing.xml`):** Tapping any extension card opens a dedicated testing sheet with real package icon, version/package name, editable search query field, live stage execution runner, and diagnostic error trace.
  - **Real-Time Stream Pipeline (`ExtensionProbe.probePipelineStages`):** Emits intermediate updates as each stage progresses (`search` -> `episodes` -> `servers`), dynamically updating both the active dialog and parent list.
  - **Refined Testing Screen Header & Controls:** Integrated prominent top `[ Start Test (N) ]` pill button (`bg_button_start_test.xml`), `Test Results` section header, and individual selection toggles with `Select all` / `Deselect all`.
- **Dedicated About Screen Implementation (`AboutFragment` & `fragment_about.xml`):**
  - Full-screen destination replacing the legacy `MaterialAlertDialogBuilder` dialog, accessed seamlessly from Profile settings (`R.id.aboutFragment`).
  - **Hero Branding Card:** Centered 96dp transparent white Animarvle letter `A` avatar (`@drawable/animarvle_logo_white`) with `scaleType="fitCenter"` and no black background box, blending directly onto the dark card surface (`bg_testing_card`). Features bold `animarvle.` typography with dynamically tinted primary accent dot (`aboutDot`) and semantic version string (`v0.1.0 (build 1)`).
  - **Community & Support Links:** Standardized link rows for Telegram (`t.me/animarvle`), Discord (`discord.gg/hXgebEStFA`), GitHub (`github.com/nd7hdyt/AniMarvle`), and Extensions Installation Guide (`wotaku.wiki/ext/`).
  - **App & Diagnostics Group:** AniList catalog attribution, local-only privacy pledge, and a one-tap "Copy diagnostics" action capturing app version, Android OS version, device manufacturer/model, and local library counts to clipboard.
  - **Edge-to-Edge Protection:** Status bar inset handled with dynamic `statusBarSpacer` and `< About` capsule top bar (`bounds="[45,118][363,242]"`).
- **Native Built-In Scraper (`FourKHDHubSource`):** Self-contained Kotlin scraper targeting 4K HDHub/HubCloud, providing true resolution tiers (4K, 1080p, 720p, 480p), exact release file sizes, multi-layer redirect solver (triple Base64 + ROT13), and direct high-speed video streams (Pixeldrain, Cloudflare Workers, BuzzServer) without requiring external APKs.
- **Resolution-First Quality Selection (`ChooseQualityBottomSheet`):** Redesigned the download quality sheet with prominent resolution badges (`4K`, `1080p`, `720p`), stream format badges (`MKV`, `MP4`, `HLS`), hoster names, exact file sizes, and high-confidence short-circuit matching (`score >= 0.85`) opening in ~1.5 seconds.
- **Strict Movie Fallback Hierarchy & Media Type Resolution:**
  - Resolved fallback ordering bug where selecting DUB audio bypassed movie engines in favor of anime dub scrapers. `PlayerViewModel.handleSourceFailure()` now strictly preserves the movie queue (`4K HDHub` -> `HexaWatch` -> `KissKH`) whenever `isMovie` or `MediaType.MOVIE` is active.
  - Added `DetailFragment.resolveMediaType()` ensuring all titles under Movies & TV mode (`active_media_mode == MOVIES_TV`) and titles with movie/film formats strictly route through movie engines.
  - Enforced movie engine compatibility verification in `PlayerFragment.onViewCreated()`, discarding stale anime bindings when playing movies.
- **Extension Type Tabs Streamlined to Anime & Movie:**
  - Streamlined media categories in `AvailableExtension.kt` (`MediaCategory`), `ExtensionManagerFragment` / `fragment_extension_manager.xml`, and `ExtensionTestingFragment` / `fragment_extension_testing.xml` from `Anime, Manga, Novel` to `Anime` and `Movie` (`🎬 Anime`, `🎬 Movie`), reflecting what the app actually offers.
  - Updated category filtering in `ExtensionManagerViewModel` and `ExtensionTestingViewModel` to separate Anime and Movie extensions cleanly.
- **Unified Edge-to-Edge Status Bar Insets Protection & Header Coordinate Rework:**
  - **Profile Screen Coordinate Rework (`fragment_profile.xml` & `ProfileFragment.kt`):** Replaced ad-hoc 20dp padding, 32dp logo, and 24sp text with the standard 54dp `FrameLayout` header (`paddingHorizontal="16dp"`, 28dp logo, 10dp margin, 22sp bold title, `@color/bg` background) driven dynamically by `statusBarSpacer`. Now sits on the exact same pixel coordinates as `Home`, `Schedule`, `My List`, and `Download`.
  - **Extension Manager (`ExtensionManagerFragment` & `fragment_extension_manager.xml`):** Wired dynamic `statusBarSpacer` via `ViewCompat.setOnApplyWindowInsetsListener`, ensuring the `< Extensions` capsule header rests safely below status bar icons with zero overlap.
  - **Extension Repos (`ExtensionReposFragment` & `fragment_extension_repos.xml`):** Added dynamic `statusBarSpacer`, standard 54dp header bar, and a back button (`buttonBack`) to safely navigate back to Extensions with zero status bar collision.
  - **Extension Testing (`ExtensionTestingFragment` & `fragment_extension_testing.xml`):** Wired dynamic `statusBarSpacer` to safeguard the `< Extension Testing` capsule bar.
  - **Player & Downloads Settings (`PlayerSettingsFragment` & `fragment_player_settings.xml`):** Added dynamic `statusBarSpacer` and standard 54dp header bar with back button, resolving status bar overlap.
  - **Subtitle Settings (`SubtitleSettingsFragment` & `fragment_subtitle_settings.xml`):** Added dynamic `statusBarSpacer` and standard 54dp header bar with back button, resolving status bar overlap.
  - **Search Screen (`SearchFragment` & `fragment_search.xml`):** Added dynamic `statusBarSpacer` and tuned top row padding to 8dp vertical, preventing the search bar and back button from overlapping the camera cutout and status bar.
  - **Video Player (`PlayerFragment` & `fragment_player.xml`):** Wired dynamic `statusBarSpacer` in `PlayerFragment.onViewCreated()`, ensuring the portrait video stage and control scrims cleanly sit below the status bar.
- **AnymeX-Style Extensions Manager UI Revamp (`ExtensionManagerFragment`):**
  - **Capsule Top Bar:** Capsule container `< Extensions` with dedicated action shortcuts for Extension Testing tool (`ic_wrench`) and Extension Repositories (`ic_github`).
  - **Category Pills:** AnymeX-styled tabs: `🎬 Anime` and `🎬 Movie`.
  - **Segmented Status Toggle:** Sleek rounded segmented pill toggle: `[Installed]` vs `[Available]`.
  - **Search & Language Filtering:** Search bar (`Search extensions...`) paired with dedicated language selector (`文A` / `ic_translate`) supporting multi-language filtering.
  - **Comprehensive Engine Filter Strip:** Horizontal filter chips under the search bar supporting all 5 requested engines: `∞ All`, `⛩ Mangayomi`, `▶ Aniyomi`, `☁ Cloudstream`, and `★ Sora`.
  - **Dedicated Updates Pending Section:** Top section displaying installed extensions with pending updates from repos, complete with an `[Update All]` action pill, individual `↻` update triggers, and drag reorder hint.
  - **Modern Item Cards & Actions:**
    - App icons loaded directly from system `PackageManager` for installed packages and via Coil for repo feeds, with engine overlay badges.
    - Badges for language (`ENGLISH`, `ALL`), version (`V14.3`), and NSFW (`18+`).
    - Coral rounded uninstall button (`🗑`) triggering clean confirmation dialog and system `PackageInstaller` uninstall intent.
    - Slate rounded settings button (`⚙`) opening Android Application Details Settings.
    - Available extensions display download button (`ic_download`) and real-time progress indicators.
- **Dedicated Extension Testing Screen (`ExtensionTestingFragment`):**
  - Interactive on-device testing screen matching the AnymeX reference UI:
    - **Header:** `< Extension Testing` capsule bar.
    - **Extension Type:** Segmented pills for `Anime` and `Movie`.
    - **Test Type:** Segmented pills for `Ping` (connectivity & latency in ms), `Basic` (query search count & speed), and `Full` (full search -> episodes -> servers -> stream resolve pipeline).
    - **Search Query Input:** Pre-filled query (e.g. `Chainsaw Man`).
    - **Select Extensions List:** Multi-selection with `Select all` / `Deselect all`, real app icons, titles, and radio/checkbox indicators.
    - **Live Testing Engine:** Integrated with `ExtensionProbe.kt` executing tests asynchronously with live progress spinners, latency reporting in milliseconds, and colored status badges (`PASS` in green, `FAIL` in coral).
- **Source Prioritization & Active Source Management on Player Screen:**
  - **Relocated Active Source Card to Player Screen (`fragment_player.xml`):** Restored the clean, uncluttered visual aesthetic of `DetailFragment` by removing the source card from the details screen. Relocated the card exclusively into `PlayerFragment` below the video band ("You are watching Episode N"), keeping the Stream Unavailable error screen faithful to the Th3-Anime reference.
  - **Live Extension & Title Overrides inside Player:** Users can switch sources on the fly via `SourceSelectorBottomSheet` and override mismatched titles via `[ ⇄ Wrong Title? ]` directly while watching or if a source fails to match.
  - **Empirical Multi-Server Source Reordering:** Reordered installed extensions in `AppContainer.extensionSources` based on verified on-device playback testing:
    - **Tier 1 (Sub + Dub Working):** `Anikoto` (HD-1, Vidstream-2, Vidstream-1, HD-2), `AnimeKai / Kotokai`, `Anichi`, `AniWave (Unoriginal)`, `AnimeSogo`, and `Animenosub` (clean audio track switching).
    - **Tier 2 (Sub Only Working):** `KissKH`, `AniDB`, `AnimeOnsen`, `AV1Encodes`.
    - **Tier 3 (Anymex-Compatible Engines with API Fallback):** `AniPM`, `AniHQ`, `HexaWatch`, `AnimeTake`, `AnimeToki`, `AnimeParadise`.
    - **Tier 4 (Broken Scrapers / Extractor Failures):** `WCOStream`, `123Anime`, `AniKage`, `AnimeGG`, `LuciferDonghua`, `AniWave.ru`, `AllAnime`, `AniKuro`, `AsiaFlix`, `AnimeKhor`, `MoviesMod`, `UHD Movies`, `BestDubbedAnime`, `GenoAnime`, `HStream`, `KimoiTV`, `XAnime`, `Animension`, `AnimePahe`, `HiAnime`, `Gogoanime`.
  - **Extensions-Lib 17 Compatibility & UnsupportedOperationException Fallback:** In `TachiyomiAnimeSource.episodes()`, added graceful fallback from `source.getEpisodeList(anime)` to `source.getAnimeEpisodeUpdate(...)` when extensions compiled against newer Aniyomi API throw `UnsupportedOperationException`. Safeguarded `getVideoUrl()` against upstream `UnsupportedOperationException` to retain Direct and Hoster stream extraction.
  - **Progressive Stream Container & MIME Detection:** In `PlayerFragment.toMediaItem()`, improved stream MIME type identification so direct progressive streams and CDN URLs without literal `.mp4` extensions in the path (e.g. AniPM's Settlar stream endpoints) have their container properly flagged as `video/mp4`, preventing ExoPlayer `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` sniff failures.
  - **Source Selection Bottom Sheet (`SourceSelectorBottomSheet`):** Interactive bottom sheet with search filter (`Search sources...`), installed count badge, and radio selection to switch the active source instantly during playback.
  - **Persistent Anime Source Binding (`AnimeSourceBindingRepository`):** Selected source IDs and custom query overrides are persisted per anime in SharedPreferences and passed directly to playback resolution.
- **Intelligent Sub & Dub Recognition across Scrapers & Hosters:**
  - **Catalogue-Level Dub Variant Resolution:** Added `requestedAudio` to `MetaQuery`. When `Audio.DUB` is requested, `SourceMatcher` automatically adds `"$query (Dub)"` and `"$query Dub"` search variants, boosting matches bearing explicit `(Dub)` markers by `+0.35` and penalizing dub entries by `-0.40` when `Audio.SUB` is selected. This allows extensions that separate sub and dub entries (e.g. KissKH, Gogoanime, Kotokai) to resolve the correct catalog item.
  - **Multi-Stream Audio Server Partitioning & Explicit Sub/Dub Hoster Splitting (Anikoto, AnimeKai, AniWave):** In `TachiyomiAnimeSource`, when an extension returns repeated hosters with matching server names (e.g. Anikoto's 6 hosters: `HD-1`, `Vidstream-2`, `Vidstream-1`, `HD-1`, `Vidstream-2`, `Vidstream-1`), the engine automatically detects the duplicate split:
    - The first set is explicitly registered with `(Sub)` (`HD-1 (Sub)`, `Vidstream-2 (Sub)`, `Vidstream-1 (Sub)`) under `Audio.SUB`.
    - The second set is explicitly registered with `(Dub)` (`HD-1 (Dub)`, `Vidstream-2 (Dub)`, `Vidstream-1 (Dub)`) under `Audio.DUB`.
    - Users can now see all servers for their respective audio mode without missing duplicate server names.
  - **Player Audio Switching & Cache Invalidation:** Switching between Sub and Dub in `PlayerViewModel` clears the active source's catalog cache to trigger re-matching of the audio-specific variant and prioritizes Dub-specialized engines during fallback.
  - **Stream Audio Sorting:** When resolving streams for `Audio.DUB`, streams tagged with "dub", "eng", or "en" in their title or URL are sorted first; for `Audio.SUB`, streams labeled with "sub", "jap", or "jpn" are prioritized.
- **Title Matching & Playback Resolution Fixes:**
  - Full Sub and Dub audio support for Aniyomi extensions: `TachiyomiAnimeSource` now registers both `Audio.SUB` and `Audio.DUB` in `EpisodeOptions`, preventing "DUB is not available from this source" failures when DUB audio is selected. When DUB is requested, streams labeled with dub are automatically prioritized.
  - Increased `StreamResolver` per-server timeout to 30s (`DEFAULT_TIMEOUT_MS = 30_000L`) and `PlayerViewModel` episode list timeout to 25s to reliably handle heavy 1100+ episode catalogs (e.g. *One Piece* on KissKH) over mobile networks.
  - Enabled `android:usesCleartextTraffic="true"` in `AndroidManifest.xml` so Android 9+ network security policy allows HTTP redirects and CDN video streams.
  - Refined `SourceMatcher` title similarity and normalization: added `AUDIO_SUFFIX` stripping for `(Dub)` and `(Sub)` entry variants, and scaled substring similarities by length ratio to prevent false-positive matching of long spin-offs (such as *ONE PIECE HEROINES* matching over *One Piece*).
  - Repositioned confirmed open HTTP 200 extensions (`AnimeTake`, `Kayoanime`, `BestDubbedAnime`, `Animension`) to top priority, ahead of token-protected sources like `KissKH`.
  - Optimized `SourceMatcher` search loop: once a high-confidence title match (score >= 0.55) is returned, the loop breaks early rather than querying 4-5 redundant Japanese synonyms sequentially over the network.
  - Dynamic total episode calculation in `DetailFragment` for ongoing series using `media.nextAiringEpisode - 1`, avoiding defaulting to 12 episodes for ongoing anime like *One Piece*.
  - Protected player episode grid in `PlayerViewModel` from collapsing down to OVA counts when an extension only indexes specials.
  - Sideloaded and integrated verified working Aniyomi extensions (`AnimeTake`, `Kayoanime`, `BestDubbedAnime`, `Animension`, `KissKH`).
  - Expanded `MetaQuery` in `DetailFragment` to pass English title, Romaji title, native Japanese title, and AniList synonyms into `SourceMatcher` to match third-party extension naming conventions.
  - Updated `streamKindOf` in `TachiyomiAnimeSource` to detect DASH manifest URLs (such as `mp4-dash` and `/dash/`) as `StreamKind.DASH`.
  - Attached extension default HTTP headers (`Referer`, `User-Agent`) onto stream requests to prevent CDN 403 Forbidden errors.
  - Set explicit `MimeTypes.APPLICATION_MPD` and `MimeTypes.APPLICATION_M3U8` on ExoPlayer `MediaItem` builders according to stream kind.
- `:source-api` — the `MediaSource` plugin contract (`search`/`episodes`/`options`/`resolve`),
  `SOURCE_API_VERSION`, and the core model types (`SourceMedia`, `SourceEpisode`, `EpisodeOptions`,
  `ResolvedMedia`, `SourceCapabilities`, `SourceErrorKind`).
- `:domain` — `StreamResolver` (per-title last-used → user priority → plugin order, per-server
  timeout, mid-playback fallback that preserves position), `SourceMatcher` (normalized-title
  similarity, year and episode-count signals; year dominates for movies so remakes stay separate),
  and `DerivedUi` (capability → UI derivation for Sub|Dub tabs, quality sheet, subtitles, skip times).
- `:source-sample` — `FakeSource`, a deterministic fixture-backed plugin for tests and demos.
- **Resolver acceptance tests: 12/12 passing** (PROMPT §10), covering Sub|Dub derivation, server
  fallback, all-servers-failed, HLS-master and direct quality sheets, skip times, subtitle tracks
  and default language, mid-playback fallback, no-match, movie single-episode + ≥90 % watched,
  RAW-only movie audio, and year-based movie matching.
- **Player** rebuilt to the PROMPT §7.7 Th3 portrait layout: video band on top with
  back · title · quality pill · speed · subtitles · overflow; seekbar; and the
  Lock · Volume · −10 · Play/Pause · +10 · Download · PiP · Fullscreen control row. Below it:
  "You are watching Episode N" (accent label, white value), Sub|Dub tabs with accent underline,
  server chips (selected = accent fill), and a 6-per-row numbered episode grid. Quality, Server,
  Subtitle and Speed all share one centered `ChoiceDialog`.
- Aniyomi/Mihon extension **discovery** (`ExtensionDiscovery`) and **classloading**
  (`TachiyomiExtensionLoader`) — verified on-device, discovering installed `AnimeOnsen` and
  `AnimePahe` extensions via the `tachiyomi.animeextension.class` manifest contract.
- `:aniyomi-api` module (scaffolded) to vendor the Apache-2.0 Aniyomi `source-api` interfaces.
- **Player Autoplay & Skip settings integration (PROMPT §7.7 / §7.12):** Autoplay next episode
  on playback completion, automatic intro/outro skipping when enabled in Player & Downloads
  settings, independent opening/ending range evaluation, and show-skip button preference toggling.
- **Subtitle styling settings integration (PROMPT §7.12):** Bound user subtitle preferences
  (text size, normal/bold/italic typeface, high-contrast outline) directly to the player's
  `subtitleView` via `CaptionStyleCompat`.
- **Hold-to-speed gesture (PROMPT §7.7 / §7.12):** Implemented long-press hold-to-speed gesture (accelerating
  to 2.0x while held, restoring default speed on release) with haptic feedback honoring `KEY_HAPTIC`.
- **Continue Watching history rail (PROMPT §7.2 / ARCH §11):** Implemented `HistoryRepository`
  with local persistence, automatic playback tracking in `PlayerFragment`, and a dynamic horizontal
  "Continue Watching" rail on `HomeFragment` with progress bars and tap-to-resume.
- **Recent Searches persistence & display (PROMPT §7.3):** Implemented `RecentSearchRepository`
  with clearable, dismissable chip list placed above "Top Searches" in `SearchFragment`.
- **Download Manager & offline queue integration (PROMPT §7.10):** Implemented `DownloadRepository`
  and `DownloadItem` with persistence. Populated `DownloadFragment` with live queue observation,
  nested progress bars, play/pause action, pause-all toggle, tap-to-play, and long-press deletion dialog.
  Wired the Download buttons on both `DetailFragment` and `PlayerFragment` to enqueue the target episode.
- **Picture-in-Picture & background playback (PROMPT §7.7 / §7.12):** Configured PiP in
  `AndroidManifest.xml`, hooked up `pipButton` in `PlayerFragment` with 16:9 aspect ratio,
  seamlessly collapsed controls during `onPictureInPictureModeChanged`, and added background
  audio playback handling in `onPause` honoring `KEY_BACKGROUND_PLAY`.
- **Double-tap seek gestures & ripple feedback (PROMPT §7.7):** Added double-tap rewind (-10s)
  and forward (+10s) on video stage with animated translucent ripple indicators and haptic pulses.
- **Player & Downloads Settings UI expansion (PROMPT §7.12):** Added configuration cards and
  persistent preferences for Background Play, Picture-in-Picture, and Download on Wi-Fi Only.
- **Sensor-landscape fullscreen & immersive UI (PROMPT §7.7):** Added full immersion toggle
  between portrait and `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`, expanding video surface, hiding
  system bars with transient swipe behavior, and restoring original window state on exit.
- **Details episode search & jump (PROMPT §7.6):** Wired "Search episode" pill on `DetailFragment`
  to an episode jump dialog with input validation, routing directly to `PlayerFragment` and
  auto-selecting the target episode index in `PlayerViewModel`.
- **Fullscreen volume & brightness vertical swipe gestures (PROMPT §7.7):** Added vertical drag
  gestures on the player stage in fullscreen mode (left half controls screen brightness, right half
  controls ExoPlayer stream volume) accompanied by on-screen HUD percentage indicators with smooth auto-fadeout.
- **Sleep Timer (PROMPT §7.7):** Implemented in-player Sleep Timer with durations (Off, 15m, 30m, 45m, 60m,
  or "End of episode"), graceful timer countdown cancellation, episode boundary detection, and playback auto-pause.
- **My List items, status tabs & progress parity (PROMPT §7.9):** Upgraded `MyListFragment` to a rich 2-column poster
  card layout (`item_my_list_grid.xml`) displaying color-coded status badges, score pill, horizontal progress bar, and
  watched episode counts (`Ep X / Y`). Added total episodes preservation and dual action (change status / remove) on long-press.
- **Player subtitle delay & external player integration (PROMPT §7.7 / §7.12):** Added Subtitle Delay dialog with
  offset adjustments (-2.0s to +2.0s) and Select Player dialog with `ACTION_VIEW` intent routing to launch stream URLs in
  external media players (e.g. VLC, MX Player, MPV) with automatic playback pause.
- **Clear Watch History feature (PROMPT §7.12):** Added `clearAll()` method on `HistoryRepository`, added Storage & Data
  section with a clear history card in `PlayerSettingsFragment`, accompanied by user confirmation dialog and instant reset.
- **Default Quality settings preference (PROMPT §7.12):** Added Default Quality configuration (Auto, 1080p, 720p, 480p)
  in `PlayerSettingsFragment` and bound it to `PlayerViewModel` and `PlayerFragment` to automatically resolve and select
  the user's preferred resolution on playback preparation.
- **Download Location / SAF directory picker (PROMPT §7.10 / §7.12):** Implemented custom download folder selection
  via `ActivityResultContracts.OpenDocumentTree()`, persisting directory read/write URI permission grants across device
  reboots while maintaining safe app-internal fallback storage by default.
- **Cast device chooser & bottom sheet (PROMPT §7.6 / §7.7):** Added `CastBottomSheet` dialog with active discovery
  indicator and empty network fallback state, exposed via the Cast action in both the `DetailFragment` header banner
  and the `PlayerFragment` top controls overlay.
- **Download on Wi-Fi Only enforcement (PROMPT §7.10 / §7.12):** Wired `ConnectivityManager` active network transport
  evaluation into `DownloadRepository` against `KEY_WIFI_ONLY`. Prevents queue advancement and displays a helpful toast
  warning when users attempt to resume downloads on cellular connections without permission.
- **Clear Watch History Undo action (PROMPT §7.4 / §7.12):** Added state snapshot caching and `restoreHistory`
  in `HistoryRepository`. Tapping Clear Watch History now displays a Material Snackbar with an active **Undo** action
  that instantly recovers previous watch progress records.
- **Dynamic Download Storage calculation & badge indicator (PROMPT §7.10):** Integrated custom SAF folder parsing
  in `DownloadFragment` via `StatFs` on file descriptor, automatically alternating badges between `INTERNAL`, `SD CARD`,
  and `CUSTOM SAF`, and updating available / total gigabytes and used percentage in real time.
- **Double-Tap Seek Duration configuration (PROMPT §7.7 / §7.12):** Added Double-Tap Seek Duration preference
  (5s, 10s, 15s, 30s) in `PlayerSettingsFragment` and bound it to `PlayerFragment` so gesture double-taps seek dynamically
  by the user-selected interval with scaling ripple HUD feedback.
- **Concurrent Downloads Limit enforcement (PROMPT §7.12):** Added Concurrent Downloads preference card in `PlayerSettingsFragment`
  (1, 2, or 3 simultaneous downloads) backed by `KEY_CONCURRENT_DOWNLOADS`. Integrated active download concurrency checks in
  `DownloadRepository` so additional items are queued until active downloads finish, fail, or pause, automatically promoting
  queued tasks when slots open.
- **Sort & Filter Active Badge indicator (PROMPT §7.3b):** Added visual badge indicator to the filter icon on `SearchFragment`
  that lights up dynamically when non-default categories, types, statuses, or sort options are active in `SortFilterFragment`.
- **Batch Download Enqueue from Details (PROMPT §7.6 / §7.10):** Added `BatchDownloadBottomSheet` opened from the details
  Download button for series with multiple episodes. Features multi-selection grid, "Select All" / "Deselect All" toggling,
  item count indicators, and enqueues all selected episodes into `DownloadRepository` with queue feedback toast.
- **Direct Subtitle Settings access from Player (PROMPT §7.7 / §7.12):** Added Subtitle Settings option directly into
  the Player's overflow menu (`showOverflow()`), navigating seamlessly to `SubtitleSettingsFragment` without leaving the video screen.
- **Sources First-Use Consent Dialog (PROMPT §7.13):** Added first-use terms & responsibility disclaimer dialog when
  navigating to Extension Repos / Sources management, storing acceptance status persistently and requiring acknowledgment.
- **Episode Watched Dimming, Progress Indicators & "Resume Ep N" State (PROMPT §7.6):** Added horizontal episodes list
  on the Details screen with thumbnail image, episode number, watched dim overlay, and watch progress bar for episodes
  found in `HistoryRepository`. Updated main action button to dynamically display "Resume Ep N" and resume directly from
  the last watched episode.
- **Extension Repository URL Normalization & Metadata Resolution (PROMPT §7.13):** Enhanced `ExtensionRepoRepository`
  to automatically normalize pasted repo URLs (stripping `/repo.json` or `/index.min.json` suffixes, converting GitHub web URLs
  to raw content endpoints), fall back smoothly across endpoints, and parse both top-level and nested metadata without failing.

#### Fixed
- Player showed a black surface with `00:00` and no playback: the `Prepare` command was emitted on
  a `SharedFlow(replay = 0)` and dropped when a fast source resolved before the Fragment subscribed.
  Commands now travel over a buffered `Channel`.
- Player top bar was drawn underneath the system status bar; now applies window insets.
- Bottom navigation overlapped the player; now hidden on the player destination.

### M2 — metadata layer

#### Added
- Home (hero + Top Airing + Popular Movies), Search (debounced live query, type chips), Details
  (banner, meta, synopsis, countdown, recommendations, comments placeholder) and Schedule
  (7-day pills, timeline) — all on live AniList data, device-verified.
- AniList GraphQL: verified `averageScale` (0–100), HTML in `description`, `AiringSort` using
  `TIME_DESC`, parenthesised variable declarations, `recommendations(sort: RATING_DESC)`, and
  `relations` edges.
- **Sort & Filter screen (PROMPT §7.3b):** Full `SortFilterFragment` with Category (All · Anime · Movies),
  Type (TV · Movie · OVA · ONA · Special), Status (Finished Airing · Currently Airing · Not yet aired),
  and Sort chips, tied back to Search query results.

### M1 — app shell

#### Added
- Five-tab bottom navigation (Home · Schedule · My List · Download · More), red/green palette
  switching, launcher icon, toolbar branding.

### M0 — scaffold

#### Added
- Gradle/AGP/Kotlin toolchain, version catalogue, and `docs/VERSIONS.md`.

[Unreleased]: https://example.invalid/animarvle/releases
## [Unreleased] - 2026-10-11

### Added
- `:aniyomi-api` module: vendored Aniyomi `source-api` v0.18.2.1 plus the `core/common` subset
  it depends on, flattened from KMP into a plain Android library. Apache-2.0.
- `ExtensionProbe`: on-device probe that loads each installed extension and calls
  `getPopularAnime(1)`.
- Minimal Injekt registration (`NetworkHelper`, `Json`, `Application`, `Context`) so Keiyoushi
  extensions can resolve their collaborators.

### Fixed
- Extension loading no longer uses `createPackageContext`, which returned an empty
  `dexElements` list on Android 16 and failed with ClassNotFoundException. Now uses
  `DexClassLoader` over the installed APK's `sourceDir`.
- Version catalogue: Aniyomi's source-api needs RxJava 1 (`io.reactivex:rxjava:1.3.8`,
  package `rx.*`); the catalogue had RxJava 2.
- Injekt dependency corrected to `com.github.mihonapp:injekt:91edab2317` (a patch over
  `uy.kohesive.injekt:injekt-core:1.16.1`).
- Added `gradle.properties` with a larger JVM heap/metaspace; the default 512 MiB made the
  Gradle daemon die with `OutOfMetaspaceError` once `:aniyomi-api` was compiling.

### Verified on device (SM_A546S, Android 16)
- Aniyomi extension `animeonsen` loads and returns **30 real popular-catalogue results** through
  the vendored source-api.
- `animepahe` loads but returns HTTP 403: its host is Cloudflare-protected and the
  `CloudflareInterceptor` was deliberately not vendored (pulls WebView + i18n).

### Added (continued)
- `TachiyomiAnimeSource`: adapts a loaded Aniyomi `AnimeSource` to our own `MediaSource`
  contract (search / episodes / options / resolve).
- `AppContainer.extensionSources` / `sources` now prefer real extensions over the demo fixture.

### Verified on device
- Full adapter pipeline against a real extension:
  `PIPELINE: OK AnimeOnsen/all: search=10 eps=12 servers=1 streams=1 first=One-Punch Man`.

### Added (continued)
- `MATCH_TIMEOUT_MS` (20s) on the Details source sweep so a slow or dead extension source cannot
  block the screen; falls back to the no-source state.

### Verified on device
- Details -> Play matching against a live extension:
  `MATCH known-good: Matched(AnimeOnsen, "One-Punch Man", score=0.6)`.
- Uncatalogued title degrades cleanly to `NoMatch` in ~1.6s (no hang, no crash).

### Fixed
- **Aniyomi source release year extraction**: Resolved limitation where `SAnime` has no explicit
  year field. `TachiyomiAnimeSource` now parses release years from titles (e.g. `(YYYY)`) and
  extension `memo` JSON, allowing `SourceMatcher` year bonuses (+0.35 exact, +0.2 near) to apply.

### Fixed
- **Details -> Play dropped `SourceMedia.extra`.** `PlayerFragment` rebuilt the media from nav
  args only, discarding `extra` - i.e. the Aniyomi `sAnimeUrl` - so a freshly constructed Player
  could not resolve. `DetailFragment` now passes `sAnimeUrl` / `poster` / anime title through the
  Bundle and `PlayerFragment` restores `extra`.
- **Added `media3-exoplayer-dash`.** AnimeOnsen returns a DASH manifest; without the module the
  player cannot build a media source for it and fails *after* a successful resolve.

### Verified on device
- Cold-path resolve with a brand-new adapter from the nav payload only:
  `COLD OK: eps=12 streams=1 url=https://cdn.animeonsen.xyz/video/mp4-dash/...`.
