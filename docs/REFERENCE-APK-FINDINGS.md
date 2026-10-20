# REFERENCE-APK-FINDINGS.md — structural observations from the two reference APKs

Scope: **behavior and structure only** (PROMPT §3.1 / §3.8). Nothing here is code, strings or assets to copy.
Analysed files live in `Apk Files/` and were inspected read-only with the Android SDK 36.0.0 build-tools.
Evidence tags follow PROMPT §0: `[V]` verified by command output, `[I]` inferred, `[?]` unknown.

## 1. AniLab — `anilab-download-latest-version.apk`

| Item | Value | Evidence |
|---|---|---|
| Package | `com.anilab.android` 1.1.3 (versionCode 14) | `aapt dump badging` [V] |
| SDK | minSdk 23, targetSdk 35, compileSdk 35 | `aapt dump badging` [V] |
| Launcher | `com.anilab.android.ui.activity.MainActivity` | badging [V] |
| Other activities | `ui.player.PlayerActivity` | manifest xmltree [V] |
| Services / receivers | `service.AppMessagingService` (Firebase messaging), boot receiver | manifest xmltree [V] |
| Navigation graphs | `nav_graph_main` (Home, ReleaseCalendar, MyList, Profile fragments); `nav_main` (Splash → Welcome → …) | decoded `res/4V.xml`, `res/NV.xml` via `aapt dump xmltree` [V] |
| Bottom menu | `menu_home` items → titles `title_home`, `title_release_calendar`, `title_my_list`, `title_profile` | decoded `res/hG.xml` + resource table [V] |
| Fragments (app) | `fragment_home`, `fragment_movie_detail`, `fragment_release_calendar`, `fragment_my_list`, `fragment_profile`, `fragment_search`, `fragment_filter`, `fragment_filter_result`, `fragment_notification`, `fragment_welcome`, `fragment_splash`, `fragment_login*`, `fragment_register`, `fragment_mal_import/export/sync`, `fragment_player_settings`, `fragment_subtitle_setting`, `fragment_help_center`, `fragment_contact_us`, `fragment_security`, `fragment_edit_profile`, `fragment_category_detail`, `fragment_comment`, `fragment_update` | resource table layout names [V] |
| Item layouts (cards/rows) | `item_home`, `item_movie_home`, `item_movie_large`, `item_continue_watch_home`, `item_top_search`, `item_release_calendar`, `item_release_calendar_current`, `item_episode`, `item_select_episode`, `item_hls_server`, `item_subtitle`, `item_download`, `item_my_list`, `item_notification`, `item_filter`, `item_chip_choice`, `item_chip_tab`, `item_tabs_row`, `item_more_like`, `item_rating`, `item_comment`, `item_avatar` | resource table [V] |
| Bottom sheets | `bottom_sheet_download`, `bottom_sheet_my_list_action`, `bottom_sheet_select_player`, `bottom_sheet_select_subtitle`, `bottom_sheet_rating`, `bottom_sheet_action_comment`, `bottom_sheet_log_out` | resource table [V] |
| Player IDs | `playerView`, `exo_play_pause`, `exo_subtitle`, `exo_playback_speed`, `buttonServer`, `chipGroupServer`, `spinnerQuality`, `buttonSubtitle`, `buttonPlayerSettings`, `goToSelectPlayer`, `goToSelectSubtitle`, `textLabelServer`, `textLabelQuality`, `loadingQuality`, `toggleQuality` | resource ids [V] |
| Movie-detail IDs | `movieDetail`, `imageMovie`, `textMovieName`, `textMovieSize`, `textMovieEpisode`, `buttonPlay`, `buttonDownload`, `recyclerRelateMovies`, `textTitleRelateMovie`, `recyclerChooseServer`, `groupEpisode`, `recyclerEpisodes`, `buttonEpisodes`, `spinnerListEpisode`, `textLastEpisode` | resource ids [V] |
| Download IDs | `layoutDownloading`, `progressDownload`, `textDownloading`, `textTitleDownload`, `goToDownload` | resource ids [V] |
| Stack hints | `libmmkv.so`, `libdatastore_shared_counter.so`, `libfreak.so`; ExoPlayer-family player IDs | native libs + ids [V] |
| Ads | `notix_*` layouts (ad banner/interstitial/appopen) and AdMob/AD_ID permission | resource table + permissions [V] |

### Movie handling in AniLab (what the APK shows)
- One detail layout (`fragment_movie_detail`) is reused for titles; movie-specific IDs (`textMovieSize`,
  `textMovieEpisode`) sit beside the episode picker (`recyclerChooseEpisode`, `groupEpisode`).
- Movies appear in the same home carousels as anime (`item_movie_home`, `item_movie_large`,
  `recyclerHozMovies`, `recyclerMovies`). No separate movies navigation destination exists in
  `nav_graph_main` [V]. This matches PROMPT D10.
- **Gap:** I have not confirmed whether the detail layout hides episode UI when a title has one
  episode. That is runtime behavior and needs the app running on a device [?].

### Player behavior evidence (structure only)
- Server chooser is its own list (`item_hls_server`, `recyclerChooseServer`, `chipGroupServer`), so
  servers are chips/list, not hidden in a menu. Matches PROMPT §7.7.
- Quality is a spinner plus a toggle (`spinnerQuality`, `toggleQuality`, `textLabelQuality`).
- Subtitle picker is a separate sheet (`bottom_sheet_select_subtitle`, `fragment_subtitle_setting`).
- Sub/Dub availability is **not** visible in any AniLab resource name I searched (`dub`, `audio`).
  [?] Unknown whether AniLab does Sub/Dub in-app; behavior needs runtime check.

## 2. Th3-Anime — `th3-anime-arm64-v8a-release-10.apk`

| Item | Value | Evidence |
|---|---|---|
| Package | `com.th3c0der.anime` 0.0.10 (versionCode 10002) | `aapt dump badging` [V] |
| SDK | minSdk 24, targetSdk 35, compileSdk 35 | badging [V] |
| Stack | **React Native + Expo** (Hermes bytecode in `assets/index.android.bundle`, `libhermes.so`, `libreactnative.so`, `libexpo-*.so`) | file listing [V] |
| Routes | expo-router file names: `top-airing`, `new-episodes`, `top-hits`, `most-favorite`, `top-movies`, `top-tv`, `top-upcoming`, `random-discover`, `calendar`, `mylist`, `download`, `watch-history`, `notification`, `comments`, `search`, `player`, `player-settings`, `subtitle-settings`, `mal-import-export`, `profile`, `edit-profile`, `security`, `welcome`, `let-you-in`, `login`, `register`, `oauthredirect`, `modal`, `details/[...id]` | Hermes bundle strings [V] (route names, not verified as the full tree) |
| Metadata hosts seen in bundle | `graphql.anilist.co` (AniList), `api.jikan.moe` (MyAnimeList data), `api.ani.zip`, `arm.haglund.dev` (ID mapping), `anidb.app`, `myanimelist.net` | strings [V] |
| Playback hosts seen in bundle | `megaplay.buzz`, `anikototv.to`, `hianimes.re`, `allanime.day` (third-party streaming sites) | strings [V]. **Recorded only as observed reference. Must not appear in the Animarvle repo (PROMPT §3.3).** |
| Auth | Firebase + Google OAuth (`accounts.google.com`, `oauth2.googleapis.com`, `firebase.google.com`) | strings [V]. Not in Animarvle v1 (no backend). |
| Features referenced in strings | "Top airing", "Top movies", "Top TV", "Top upcoming", "Most favorite", "Random discover", "Watch history", "MAL import/export (XML)", "Subtitle settings", "Player settings", "Prefers Dub audio if available (falls back to sub)", "Download", "M3U8 → MP4", "Custom folder" | strings [V] |
| Permissions of note | `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`, `READ_MEDIA_IMAGES`, `RECORD_AUDIO`, `SYSTEM_ALERT_WINDOW`, `WRITE_SETTINGS`, many launcher-badge permissions | manifest [V]. Animarvle should request only what it uses (PROMPT §5). |

### Th3-Anime movie handling (what the strings show)
- Separate routes exist for **top movies** and **top TV** (`top-movies.tsx`, `top-tv.tsx`) next to
  `top-airing`. Movies are a home-section concept, not a separate app area. [V strings]
- The bundle has a "Top movies" loader and error text. Its metadata is AniList format filters, so
  Th3 movies are anime films [I]. This does **not** match the general-films scope in PROMPT D10. Animarvle
  will use TMDB for general films (PROMPT D11), which Th3 does not do.

### Sub/Dub and server handling (what the strings show)
- Strings include "Prefers Dub audio if available (falls back to sub)", "Try a different server or switch
  between Sub/Dub", "Trying next server...", "All servers for this episode are currently unavailable",
  "Failed to fetch streaming options from the server", "Failed to fetch sub tracks for dub combination".
  [V strings] → this is the observed model for PROMPT §6/§7.3 (sub/dub availability, server fallback).
- Strings show the app fetches streaming options per episode from the source and falls back through servers.
  The exact resolver logic is not visible in strings. Decompiling the Hermes bytecode is needed to confirm it [?].

## 3. What to build from this (PROMPT-aligned, clean-room)

1. Keep the **5 labelled tabs**: AniLab's 4-tab build vs. 5-tab mockup is consistent with PROMPT D4.
2. Keep one **movie detail layout** with the episode block hidden for single-episode titles (AniLab's
   `groupEpisode` / `textMovieEpisode` pattern) [I].
3. Build **Top Airing / Top Movies / Top TV / New Episodes / Top Hits** rows as separate data queries,
   matching the route names both apps use [V].
4. Server and quality choosers as **list sheets** (AniLab `item_hls_server` / Th3 "Try a different server").
5. Do **not** reuse any host, route file name, string, or ID from these APKs in Animarvle code. Names above
   are for design reference only (PROMPT §3.1).

## 4. Device-verified screens (Th3-Anime on a Galaxy A54, Android 16, 1080x2340)

Captured live with adb on 2026-10-10 and **looked at directly**. Files in `docs/device-shots/`.
Tags: `[V-seen]` = seen in the screenshot, `[V-text]` = from the uiautomator dump of the same screen.

| Screen | File | Verified content |
|---|---|---|
| Home | `th3-home.png` | Hero art (0–1350px) fading to black · logo + "Th3-Anime" title · search + bell icons · bold title · genre line · **Play** (filled accent) + **My List** (white outline pill) · **Continue Watching** row with "See all" and a card carrying ⓘ (top-left), ✕ (top-right), "Episode 1" label and a progress bar · 5-tab bar: Home, Schedule, My List, Download, Profile `[V-seen][V-text]` |
| Schedule | `th3-schedule.png` | Logo + "Schedule" + search · horizontal day strip of tall rounded pills (weekday over date), selected day = accent fill, ~3 days either side · timeline list: accent dash + `HH:mm` + vertical rail, thumbnail, title, "Episodes N", **+ My List** tonal button `[V-seen]` |
| My List | `th3-mylist.png` | Status chips: All (accent fill) · Watching · Plan to Watch · Com… (scrolls) · empty state: two-clipboard illustration, accent title, muted two-line subtitle `[V-seen]` |
| Download | `th3-download.png` | Storage card: tile icon, "Device Storage" + "INTERNAL" badge, "16.6 GB Available of 107.2 GB", accent progress bar, "84% Used" / "16% Free" · empty state illustration + accent title + muted subtitle `[V-seen]` |
| Profile | `th3-profile.png` | Account card ("My profile" / "Sign in to synchronize your anime" / filled **Continue** button) · **Support/ad card — excluded from Animarvle** · rows with icon + title + chevron: Player & Downloads, Subtitle settings, Support · footer "Created By …" → Animarvle uses a version footer instead `[V-seen]` |
| Search | `th3-search.png` | Back arrow · outlined search pill "Search..." · **shuffle** icon-tile (amber/brown tile, `accentAlt`) · **filter** icon-tile (green) · "Recent Searches" + "Clear All" · query chips · "Top Searches" rows: thumbnail, bold title, `YYYY-MM-DD`, accent circled-play at right `[V-seen][V-text]` |
| Sort & Filter | `th3-sortfilter.png` | Groups **Type** (All/Movie/TV/OVA/ONA/Special/Music), **Status** (All/Finished Airing/Currently Airing/Not yet aired), **Season** (All/Spring/Summer/Fall/Winter), **Sort** (Default/Recently Added/Recently Updated/Score/**Name A-Z/Name Z-A/Most Popular/Most Favorite**) · selected chip = accent fill, unselected = outlined accent text · bottom bar: **Reset** (tonal) + **Apply** (filled, wider) `[V-seen][V-text]` |
| Details | `th3-details3.png` | Banner ~38% with back arrow + **cast icon (greyed)** · content sheet with rounded top corners · title + bookmark + share icons · meta row: ★ score · dot · year · outlined **PG-13** / **HD** chips · **Play** (filled) + **Download** (outlined accent) · "Genre: …" · synopsis 3 lines + "... View More" · (source attribution line) · **Episodes** header + countdown chip "Ep 3: 6d 18h (17 Oct)" + "Search episode" pill · episode cards using the **banner as thumbnail**, centered play triangle, "Ep 1: Episode 1" · **Seasons** section `[V-seen][V-text]` |
| Details (loading) | `th3-details.png` | Skeleton placeholder set: banner block, title bar + 2 icon circles, meta chips, 2 pill buttons, 3 synopsis lines, section header + "See all", 3 poster cards `[V-seen]` |
| Player | `th3-player5.png` | Top bar: back · ellipsized title · speed gauge · subtitles · **⋮ overflow** · seekbar `00:00`/white thumb/`00:00` with a **buffering spinner** above · bottom controls: Lock, Volume, −10s, Play/Pause, +10s, Download, Fullscreen · "You are watching **Episode N**" (accent label, white number) · **Sub | Dub tabs** with accent underline · **server chips** (Koto, Neko, GG — selected = accent fill) · "List of episodes" + "No. of Ep" pill + "EPS: N" counter · numbered grid (current = accent fill, others = dark tile) · Comments card with arrow `[V-seen][V-text]` |
| Player subtitles | `th3-player-overflow.png` | Bottom-sheet dialog "Subtitles" with "Off" in accent + check mark; same component reused for track selection `[V-seen][V-text]` |

### What this resolves in PROMPT.md

- **§7.3b `[?]`** "more options may exist below the fold" → **resolved**: Sort also has Name A-Z, Name Z-A,
  Most Popular, Most Favorite `[V-text]`.
- **§7.6** banner **cast icon is present but greyed out** (matches "disabled until Cast phase") `[V-seen]`.
- **§7.6** episode thumbnails fall back to the **banner** when the episode has no art `[V-seen]`.
- **§7.7** the layout is **identical in structure to PROMPT §7.7**, including the episode grid, server chips
  and the comments card `[V-seen]`.
- **§7.4** notifications captured: matches §7.4 including the "300 x 450" placeholder and the blue
  ANNOUNCEMENT info chip `[V-seen]`.
- **Quality pill ("AUTO") not observed** in any player capture — the stream never loaded on this network
  (duration stayed `00:00`), so the quality UI is still `[?]` `[V-seen absence]`.

### More screens captured (second pass, same session)

| Screen | File | Verified content |
|---|---|---|
| Notifications | `th3-notifications3.png` | Back arrow · centered "Notifications" · accent **Clear All** · item row: placeholder thumb with the literal text "300 x 450", bold title starting with an emoji, "Today" at far right, muted 2-line body, **info chip "ANNOUNCEMENT"** in blue on a dark-blue container `[V-seen][V-text]` |
| Player & Downloads | `th3-player-settings.png` | Uppercase accent section headers ("PLAYBACK", "HOLD-TO-SPEED") · rounded surface cards, each title + muted description + control: **toggles** (Autoplay Next Episode, Auto Skip Intro/Outro, Show Skip Buttons, Haptic Feedback) and a **segmented pair** (Default Audio: SUB \| DUB, SUB selected) `[V-seen][V-text]` |
| Subtitle settings | `th3-subtitle-settings.png` | Live **preview card** on a light backdrop with the sample line rendered in the chosen style · Default Subtitle Language dropdown · Text Size **slider** · Text Style segmented (Normal/Bold/Italic) · **Text Color** swatch grid with the selected swatch ringed in accent · Background Color swatch row `[V-seen][V-text]` |
| Search results | `th3-search-results.png` | Same row layout as Top Searches; each result carries an **HD** badge `[V-seen][V-text]` |
| **Film detail** | `th3-movie-detail4.png` | *Suzume* (2022, ★8.1, PG-13, HD). Same banner + bookmark/share + Play/Download as a series. **No airing countdown chip. No Seasons section.** The **Episodes header, "Search episode" pill and a single "Ep 1: Episode 1" card remain visible.** Tabs: "More like this" · "Comments (0)" `[V-seen][V-text]` |

## 6. `com.xo.anilab` (AniLab2) is a different design — not the UI reference

Launched on the device and captured (`xo-anilab-home.png`) `[V-seen]`:

- Hero is an **informational spotlight card**: centred title, then chips `TV` · `PG-13` · `8.2`, then a line
  `Winter 1996 · Currently Airing · 24m · ★ 8.2`, then genres, then **three** actions: a small "Share" icon,
  a large white pill "ⓘ Details", and "My List" with a **heart** icon.
- Rows are **"Spotlight"** and **"Trending"** poster grids (no Continue Watching, no Top Airing).
- Bottom nav: **Home · Genres · Latest · Search · My List** (5 tabs, different destinations).
- It renders with an **opaque dimmed collage background**, not a full-bleed fading hero.

**Decision:** Animarvle follows `com.anilab.android` (AniLab) + Th3-Anime layout, as the owner specified.
`com.xo.anilab` is recorded only so the difference is on record.

## 4b. Live playback verified (stream actually loaded)

The Suzume stream resolved on the second attempt and played to ~1h15m of 1h59m. Captured:

| What | File | Verified content |
|---|---|---|
| Playing controls | `th3-movie-controls2.png` | Back (translucent circle) · center play/pause · seekbar with **two-tone segments** (played / buffered amber) + white thumb + `hh:mm:ss / hh:mm:ss` · bottom row: **Volume · −10s · +10s · CC · Settings(gear) · PiP · Fullscreen**. The *series* screen captured earlier showed a different set (Lock, Volume, −10, Play/Pause, +10, **Download**, Fullscreen) — the control row is not identical between states `[V-seen]` |
| Movie player state | `th3-movie-player2.png`, `th3-quality-dialog3.png` | Resolves to **"You are watching Episode 1"**, **EPS: 1**, and a single numbered tile `1` — Th3 keeps the full episode-grid block for a film `[V-seen]` |
| Player settings panel | `th3-player-settings-sheet.png` | Gear opens a **full-width panel over the video**, top tab row: **CC** (active, underlined) · **speed** · **volume**, plus ✕ close. CC tab lists tracks: `• Off` (bullet = selected), Chinese (- Simplified), Chinese (- Simplified) 2, English `[V-seen][V-text]` |
| Speed tab | `th3-player-speed-tab.png` | `0.25x · 0.5x · 0.75x · • 1x · 1.25x` … scrollable, selected marked with a bullet `[V-seen][V-text]` |
| Control bounds | `th3-player-settings-sheet.xml` | Play `[413,359][540,488]` · Volume `[30,606][132,731]` · −10s `[405,606][531,731]` · +10s `[528,606][655,731]` · **CC `[652,606][753,731]`** · **Settings `[750,606][852,731]`** · **PiP `[846,606][950,731]`** · Fullscreen `[947,606][1049,731]` `[V-text]` |

### Movie-variant decision (owner)

Owner chose **Th3's behaviour**: keep a one-item Episodes block. So for `MediaType.MOVIE` Animarvle keeps the
Episodes header / episode search / single "Ep 1" card, and drops only the airing countdown chip and Seasons.
Recorded in `docs/DECISIONS.md` as Q11.

### Still missing: the quality UI

Both the CC button and the gear were opened. **No quality/resolution UI exists in Th3's playing controls** —
no "AUTO" pill, no quality dialog `[V-seen absence]`. The gear's three tabs are CC / speed / volume only.
This **contradicts PROMPT §7.7**, which specifies a quality pill plus a quality dialog ("AUTO · 1080P · 720P ·
480P · Close"). Possible reason: this server returned a single stream, so no quality control was offered
`[I]`. Animarvle keeps the PROMPT behaviour (ARCH §6/§7.4), which is stricter than either reference app.

| **Stream-failure state** | `th3-current.png` | Full-screen error: circular icon on a dark-red container (crossed-out signal glyph) · bold **"Stream Unavailable"** · body *"All servers for this episode are currently unavailable. Try a different server or switch between Sub/Dub."* · uppercase muted label **"TRY ANOTHER SERVER"** · server chips **Koto · Neko (selected) · GG · MEGA** · audio chips **🇯🇵 Sub (selected) / 🎙 Dub** (flag icon for Sub, mic icon for Dub) · **Retry** (filled accent + refresh glyph) and **Go Back** (surface) buttons `[V-seen]` |

### What the failure state verifies

ARCH §6 "All servers fail → 'All servers unavailable' state + Retry + 'switch Sub/Dub' hint" is implemented
**exactly** as specified `[V-seen]`. Three details worth carrying into Animarvle:

1. The failure screen **replaces the video area but keeps the picker visible** — the user can change server
   and Sub/Dub inline instead of going back `[V-seen]`.
2. Audio chips carry **icons** (a language flag for Sub, a microphone for Dub), which the earlier player
   capture did not show `[V-seen]`.
3. The server list **changed between captures** (Koto/Neko/GG → Koto/Neko/GG/**MEGA**) while the app was
   open, so Th3 re-queries servers rather than caching the list for the session `[V-seen]`. Relevant to
   ARCH §7.3's "cached per episode" rule — Animarvle should re-query on failure.

### The quality pill — VERIFIED (supersedes the "missing" note above)

`th3-quality-picker.png` shows the pill in the **top bar**, between the back arrow and the speed gauge:
**`AUTO`** in accent green on a dark tonal container `[V-seen]`. It appeared only on this stream — the earlier
captures had no pill, so the control is **conditional on the stream exposing quality information** `[I]`.
This source exposes only one rendition, so **only AUTO is offered**; the other captures had no quality
button at all. PROMPT §7.7's "AUTO · 1080P · 720P · 480P" is therefore the correct design for sources that
report levels (ARCH §6: "levels come from the stream, never hardcoded") — Animarvle hides the item entirely
when there is nothing to choose.

That capture also adds two things not seen before:

- **Episode range selector**: the counter row reads **"EPS: 1-100" with a chevron** — a range picker over the
  episode set, not just a plain count `[V-seen]`.
- **Server attribution watermark** burned into the video ("Presented by StreamAnimeTV") `[V-seen]`.
- This stream is 23:37 long and the server chips differ (Koto/Neko/**GG** selected, MEGA absent) — the
  available servers vary per title/audio `[V-seen]`.

| Add to My List sheet | `th3-current2.png` | Modal sheet with a grabber handle, title "Add to My List" over a hairline, then **five status chips in a wrapping 2-column grid**, each with an icon: ▶ Watching (play-circle), 📅 Plan to Watch (calendar), ✓ Completed (green check), ⏸ On-Hold (orange pause), ✗ Dropped (red X). Chips are dark surface pills; the active status is shown by the accent-coloured icon `[V-seen][V-text]` |
| In-list state | `th3-mylist-added3.png` | After picking a status the sheet closes and the **bookmark icon turns filled accent**; the title also **marquee-scrolls** when too long ("Hunter x Hunter (2011)" sliding) `[V-seen]` |
| Seasons section | `th3-mylist-added3.png` | Season poster carousel with a **"Current" badge** (accent pill, top-right) on the active season `[V-seen]` |

This resolves PROMPT §7.9's `[D]` marks on **On Hold** and **Dropped**: both exist in the reference app with
their own icons and colours `[V-seen]`.

### Not captured

**Share** and **Download** on Details would not open under synthetic taps. Likely cause `[I]`: the episode
list for that title never loaded (the Episodes section stayed empty for several minutes), so Download has no
episodes to act on. Not confirmed — needs a retry on a title whose episodes are loaded.

### Download sheet — VERIFIED

`th3-download-action.png`. Grabber handle · centred title **"Download"** · control row: "**Episodes**"
label, **Deselect All** (filled accent pill), a **server dropdown** showing the current server (`Koto ⌄`,
outlined accent), and a **quality dropdown** (`QUALITY ⌄`, outlined accent) · **Search…** field · episode grid
where each card carries a **green check badge (top-right) when selected** · bottom row **Cancel** (tonal) +
**Download** (filled accent) `[V-seen]`.

Two things this settles:

1. **Where quality is actually chosen in Th3: the download sheet, not the player.** The player's quality pill
   showed only `AUTO`; the download sheet is where a specific level would be picked `[V-seen]`.
2. The server picker is **per-download**, so a download can pin a different server than playback
   `[V-seen]` — relevant to ARCH §9 ("resolve at enqueue time with the same §7.3 pipeline").

Not yet captured: the expanded QUALITY option list (tap did not open it under synthetic input).

### Share — VERIFIED

`th3-share-action.png`. Th3 uses the **stock Android share sheet** (`ACTION_SEND`, `text/plain`), not a custom
UI:

- Header **"Sharing text"** over a hairline
- Preview row: white circle with a lines glyph, the payload text, and a copy icon at the right
- App grid below: Instagram, Facebook, OpenAI, Xender, Telegram, Quick Share, Chrome, File Manager, …

**The payload is plain text only** — *"Check out Hunter x Hunter: Phantom Rouge on Th3-Anime!…"*. There is no
URL, no deep link and no image `[V-seen]`. So the reference app shares a title string and the brand name.

For Animarvle this confirms PROMPT §7.6's "system share `[D]`" and gives us something to improve on: include a
**watch link** when a source has been matched (ARCH §7 gives us the stream), instead of an unresolvable title
string. Suggested shape: `<Title> (<year>) — watched on Animarvle` + URL when available, plain text otherwise.

## 7. Open items

- [?] Th3 resolver logic (server order, dub-fallback rules): needs Hermes bytecode decompilation (e.g. a
  hermes-dec tool). Not attempted yet.
- [?] Full Th3 route list: only route file names seen in strings, not a confirmed navigation tree.
- [?] AniLab (`com.anilab.android`) runtime screens: the app was not launchable during this session.
- [?] AniLab2 (`com.xo.anilab`) was launched and its home screen captured (`xo-anilab-home.png`), but it is a
  different design (see §6) and was not used as the UI reference.
- [?] The **quality pill / quality dialog** is still unverified: Th3 showed no quality UI at all, and no
  source so far has offered multiple renditions. Animarvle keeps the PROMPT §7.7 behaviour regardless.

## 8. Mizuki — `Mizuki-phone-universal-release.apk`

Inspected on 2026-10-16.

| Item | Value | Evidence |
|---|---|---|
| Package | `org.mizuki.app` | manifest / DEX inspection [V] |
| Scraper Engine | **Built-in Pure-Kotlin scrapers** (no external extension APKs required) | DEX decompilation [V] |
| Primary Scrapers | `AllAnimeProvider` (`https://api.allanime.day/api`), `AniKotoProvider`, `GogoProvider` | DEX decompilation [V] |
| Link Decryption | XOR 56 hex decoder (`decodeHexPath`): `(hexByte.toInt(16) xor 0x38).toChar()` | DEX bytecode disasm [V] |
| Skip Integration | AniSkip API (`https://api.aniskip.com/v2/skip-times/`) for OP/ED intervals | DEX strings / network [V] |
| Mapping | AniZip API (`https://api.ani.zip/mappings?anilist_id=`) for AniList ↔ MAL cross-referencing | DEX strings [V] |
| Server Selection UI | "Server for this episode" bottom sheet dialog with segmented tabs `[ All ]` `[ Sub ]` `[ Dub ]`, language filter chips (English, Hindi, Arabic, Spanish, Japanese), capability badges (`Playing now`, `Fast`, `Can download`, `Subbed`/`Dubbed`, language badge) | Reference screenshots & DEX layouts [V] |

### Architectural Adoption in Animarvle
- Created pure Kotlin `AllAnimeSource` and `AniKotoSource` implementations of `MediaSource` at Tier 0 priority.
- Adopted the Mizuki-style "Server for this episode" sheet (`ServerSelectionBottomSheet`) with real-time category and language filtering.

---

## 9. AniVortex — `anivortex_5.0.2.apk`

Inspected on 2026-10-16.

| Item | Value | Evidence |
|---|---|---|
| Package | `app.anivortex.mobile` 5.0.2 | manifest XML string pool [V] |
| Framework | **Flutter** (Dart AOT snapshot in `lib/arm64-v8a/libapp.so`) | APK zip listing [V] |
| Scraper Strategy | **Zero client-side host scrapers or torrents**; thin client model | libapp.so string audit [V] |
| Backend Endpoint | Bootstraps via `https://anivortex-bootstrap.pages.dev/config.json` -> `https://api.anivortex.in` | libapp.so & HTTP probe [V] |
| Request Security | HMAC signed requests: `X-AniVortex-Timestamp`, `X-AniVortex-Request-ID`, `X-AniVortex-Signature`, Play Integrity check | libapp.so strings [V] |
| The "Turbo" Server | Branded private CDN caching proxy cluster (`cdn_host`) | libapp.so strings [V] |
| File Sizes (~2–3 GB) | Pre-encoded x264/x265 releases with muxed Indian regional AAC dubs (English, Hindi, Kannada, Malayalam, Tamil, Telugu) sourced from release syndication groups (HubCloud / GDFlix / FastX / VegaMovies), explaining why sizes are ~2.5 GB MP4 rather than 60 GB torrent remuxes | libapp.so & UI screenshots [V] |
| Player & Downloader | `uz.shs.better_player_plus` + custom resumable download engine (`resumable_download_engine.dart`) with HTTP byte range resume | libapp.so & classes.dex [V] |
| Player Dialog Architecture | **Obsidian Frosted Glass Card Bottom Sheet**: Anchored flush to the bottom edge (`y = 0`), 70% width, 28dp top-left/top-right corners, 0dp bottom corners. Underlying video is blurred with GPU shaders while top title HUD and bottom seekbar remain visible underneath. | Reference screenshots (`media_1792451509307.jpg`..`344.jpg`) [V-seen] |
| Custom Canvas Scrollbar | **Persistent Custom Scrollbar**: Rendered via Flutter Canvas (`thumbVisibility: true`) at `x = 886..890` (width ~3.5dp, radius 2dp, color `#B3FFFFFF`) inset 4dp from the right card border on scrollable lists (> 5 items like Playback Speed), rather than native Android `awakenScrollBars()` which fades or fails when views are stationary. | Reference screenshots & pixel analysis [V] |

---

## 10. AniWorld-Downloader — Video Provider Extraction Reference

Inspected from upstream `src/aniworld/extractors/provider/` on 2026-10-16.

| Provider | Target Host | Decryption & Extraction Algorithm |
|---|---|---|
| **VOE** (`voe.py`) | `voe.sx` / mirrors | 5-step cipher: ROT13 alphabet shift -> strip junk tokens (`@$`, `^^`, `~@`, `%?`, `*~`, `!!`, `#&`) -> Base64 decode -> ord shift -3 -> reverse string -> Base64 decode -> JSON parse for `source`. |
| **Filemoon** (`filemoon.py`) | `filemoon.sx`, `bysezejataos.com` | Modern Byse REST API (`/api/videos/{code}`) with AES-256-GCM encrypted `playback` payload (32-byte key from base64url chunks). Fallback to Dean Edwards packed JS `eval(p,a,c,k,e,d)`. |
| **Gupload** (`gupload.py`) | `gupload.xyz` | Concatenated key parts array + repeating XOR byte decryption (`cipher[i] ^ key[i % len]`). |
| **Hanime.tv** (`hanime_tv.py`) | `hanime.tv` | Page scrape + AES-256-GCM decrypted manifest token using fixed key `5d65...ebe3` and AAD `"htv-insecure-v1"`. |
| **Doodstream** (`doodstream.py`) | `dood.li`, `dood.to` | Extracts `pass_md5` URL and token -> fetches base video URL -> appends 10-char random salt and expiry timestamp. Requires `Referer: https://dood.li/`. |
| **Megakino** (`megakino.py`) | `watch.gxplayer.xyz` | Regex extraction of `uid`, `md5`, `id` -> master URL: `https://watch.gxplayer.xyz/m3u8/{uid}/{md5}/master.txt?s=1&id={id}&cache=1`. |
| **Vidara** (`vidara.py`) | `vidara.to`, `vidara.so` | POST `/api/stream` with JSON `{"filecode": code, "device": "web"}`. |
| **Vidmoly** (`vidmoly.py`) | `vidmoly.biz` | Concatenates `<script>` blocks and extracts `file: '...m3u8'`. Requires `Referer: https://vidmoly.biz`. |
| **Vidoza** (`vidoza.py`) | `vidoza.net` | Searches HTML for `sourcesCode:` and extracts `src: "..."`. |
| **MoflixClick** (`moflixclick.py`) | `moflix-stream.click` | Unpacks Dean Edwards JS, extracts `links`, probes `hls4` -> `hls3` -> `hls2` for `#EXTM3U`. |

