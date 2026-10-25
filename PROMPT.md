# PROMPT.md — Animarvel (AniLab-style UI) · Agent Contract

You are building **Animarvel**: a native Android anime + movie client with the **AniLab UI/stack** and a
**source-plugin system**. This file is the contract. `ARCHITECTURE.md` is the technical plan.
Read both fully before writing code. If something here conflicts with your habits, this file wins.
If something is missing, **stop and ask** — do not invent (see §9).

---

## 0. Verification rules (non-negotiable)

- Never state a fact about APIs, versions, file contents, or runtime behavior you haven't verified this
  session (fetch docs/POM/source, grep the repo, run the command). Label memory-based claims **"assumption"**
  until checked.
- Primary sources beat summaries: upstream source > official docs > blogs > memory.
- Before claiming something works: build it, run it, or test it. "Should work" is not a result. Report the
  command's actual output.
- When evidence contradicts your plan, say so explicitly and change course — don't rationalize.
- Distinguish **verified / inferred / unknown** in every status update.
- If blocked on something unverifiable, stop and ask instead of guessing.
- Read a file before editing it; cite `path:line` when referencing code.

**Evidence tags used in this repo's docs:** `[V]` verified from an APK/screenshot/page this project
inspected · `[I]` inferred · `[D]` design decision made for Animarvel (not observed anywhere) · `[?]` open,
must be asked, not guessed.

---

## 1. Goal and scope

**In scope:** **anime + movies**; browse, search, details, play, download, library ("My List"), watch history,
airing schedule, settings, and an installable **source-plugin** system.

**"Movies" means general feature films, not anime movies** `[V user statement]`. They are handled the way
Zangetsu groups them (anime, movies, series in one streaming mode `[V Zangetsu README]`) but **simplified the AniLab way**
`[D]`: no separate movies app section or screens. A movie is a title with exactly **one episode**, shown by the
same Home/Search/Details/Player/My List/Download screens, switched with a type chip (All · Anime · Movies). Anime
films stay under *Anime* (format "Movie"). TV series are **not** in v1 (the type model leaves room, §ARCH 4).

**Out of scope (do not build):** manga, light novels, ads, points/rewards/"cosmetic shop"/badges, comments
backend, in-app self-updater, analytics/Firebase, any bundled streaming source, any scraper for third-party
sites inside this repo.

**Reference inputs the user listed** (Mihon, LNReader, Kotatsu, DropSauce, Seanime-Marketplace, AnymeX,
ReDantotsu, Zangetsu, Synthetiq-Modules, Synthetiq player, AniLab2, miru): **only the pages of Mihon, AnymeX and Zangetsu
(README only; its code pages block automated access) were inspected** `[V]`. The rest are **not analyzed** `[?]`.
What Zangetsu does for movies *in code* (metadata provider, source format) is **unverified**. Do not claim what any of them do. If you need one, fetch
its source first and cite it.

## 2. Evidence base (what the design comes from)

| Input | What was verified |
|---|---|
| AniLab APK (`com.anilab.android`) | Native Android, Kotlin 2.0.21, Gradle 8.11.1, AGP 8.10.1, Java 17 target; Navigation-fragment 2.8.9, Room 2.7.1, DataStore 1.1.3, WorkManager 2.7.1, Material 1.12.0, AppCompat 1.7.1, MMKV; ExoPlayer-family player strings; Firebase + Cast + AdMob present; code is R8-obfuscated; no Compose version files `[V]`. UI is View/Fragment based `[I]`. |
| Th3-Anime APK (`com.th3c0der.anime`) | React Native + Expo SDK 54, Hermes bytecode v96 `[V]`. **UI reference only. Its stack is NOT used.** |
| Screenshots | 3 AniLab (home, mockup, details) + 7 Th3-Anime (details, home, my list, schedule, download, profile, player) `[V]`. |
| Th3 behavior (from bundle strings, logic not decompiled) | per-provider stream resolvers, sequential server fallback, dub-availability check, HLS quality parsing, HLS→MP4 download, AniList GraphQL metadata; its "movies" are AniList `format: MOVIE` anime films, with "top movies"/"top TV series"/"top upcoming" rows `[V strings / I behavior]`. |
| 4 more Th3 screenshots | Search, Sort & Filter, Notifications (announcement item), Player quality dialog `[V]` |
| AniLab resource strings | "Choose quality", "Choose server", "Choose subtitle", "Skip intro/outro", "Subtitle delay", "Select Player", download states incl. waiting for Wi-Fi/network and paused; a drawable named `background_gradient_movie_detail` (its details screen is called "movie detail"; no separate movie UI found) `[V]` |
| Zangetsu (Flutter/Dart, GPL-3.0) | README: three modes Streaming/Manga/Novel; streaming mode covers anime, movies and series; own JS sources; Android can load CloudStream/Aniyomi/Mihon extensions; sync + trackers `[V README]`. Movie code path `[?]`. |

Th3-Anime's UI is "almost the same, different theme" as AniLab (user statement `[V]`, consistent with the
screenshots). Where the two differ, §6 states which one Animarvel follows.

## 3. Hard rules: what you must NOT do

1. **Copy nothing from either app**: no logos, illustrations, icon sets, strings verbatim, names ("Anilab",
   "Th3-Anime"), package ids, Cast receiver ids, API keys, Firebase configs, or decompiled code. Observed copy
   in §6 is *reference only — reword it*. Draw your own logo/illustrations.
2. **Package id** is `com.animarvel.app` `[D]`. Never `com.anilab.*` / `com.th3c0der.*`.
3. **No hardcoded content hosts** in the repo (no streaming sites, no scraper rules). Sources are plugins (§7).
4. The repo ships exactly these sources: a **Fake/Test source** (fixtures), a **Local Files source**, and
   optionally a **sample source with openly licensed test streams**. You must find such streams yourself,
   verify license + URL by fetching them, and cite the license in the code. Do not invent URLs.
5. No ads SDKs, no analytics, no Firebase `[D]`. Network only to: metadata API (AniList, §ARCH 10), user-installed
   plugins' own hosts, image CDNs returned by those.
6. Do not pin a dependency version from memory. Check Maven Central / Google Maven / release notes, record the
   source in `docs/VERSIONS.md`, then pin.
7. Do not add screens/features not listed here without asking.
8. **Zangetsu is GPL-3.0** `[V README]`. Read it for *behavior* only. Do not copy or translate its code, assets or
   strings (that would force GPL terms onto Animarvel). Same for every reference repo whose license you haven't checked.
9. **Brand name is one token.** User-facing name lives only in `strings.xml` (`app_name`) and `Brand.kt`; the user has
   written both "Animarvel" and "Animarvle" `[?]` (§9 Q8). Until answered, use `Animarvel` and keep it trivially renamable
   (the applicationId follows the final spelling).

## 4. Tech baseline

Language **Kotlin**; UI **Views + Fragments** (XML, ViewBinding) + Jetpack Navigation + Material 3-style
theming via Material Components `[I from APK; D to follow]`. Not Compose. Room, DataStore, WorkManager.
Player: **Media3** (ExoPlayer successor) `[D]` — AniLab shows ExoPlayer-family strings `[I]`; verify current Media3
docs before use. Coil for images, OkHttp + kotlinx.serialization for network `[D]`. Manual DI (`AppContainer`) `[D]`.
`minSdk`/`targetSdk`: **ask** `[?]` (AniLab's were not read; Th3 targets 35 `[V]`).
Build with Gradle Kotlin DSL + version catalog. Use AniLab's toolchain as the starting point (Kotlin 2.0.21,
AGP 8.10.1, Gradle 8.11.1, JDK 17) **only after verifying compatibility with each other and your SDK**.

## 5. Design tokens

Two themes share one token set. `[V]` = measured from screenshots (JPEG, ±few levels); treat as starting values.

| Token | AniLab (red) | Th3 (green) |
|---|---|---|
| `accent` | ≈ `#D8202C` `[V approx]` | `#05C149`–`#06C149` (`[V]` pixel of Apply button `#05C149`; Th3 config `#06C149`) |
| `bg` | ≈ `#101218`–`#181820` `[V approx]` | `#0D1017` `[V pixel, new screenshots]` (Th3 splash `#101218` `[V config]`) |
| `surface` (cards/inputs) | ≈ `#1E2129` `[I]` | same `[I]` |
| `surfaceVariant` (chips) | ≈ `#262A34` `[I]` | same |
| `onBg` | white | white |
| `onBgMuted` | ≈ `#A1A1A1` `[I]` | same |
| `accentContainer` (tonal btn bg) | accent @ ~15–20% on bg `[V]` visually | `#0D221D` `[V pixel]` (Reset button, filter button) |
| `accentAlt` (shuffle/random) | n/a `[?]` | `#FD9704` icon on `#261D14` tile `[V pixel]` |
| `info` (announcement chip) | n/a `[?]` | blue text (`#2196F3` appears in the Th3 bundle `[V]`) on `#101D2E` `[V pixel]` |

- Ship **both** themes, switchable in settings (default: red) `[D]`. Dark only in v1 `[V]` (all screenshots dark).
- Font: system sans (Roboto-like) in AniLab phone screenshots `[V]`; mockup uses a rounded geometric face `[V]`
  but that looks like a design mock `[I]`. Use system font `[D]`.
- Radii: posters/cards ≈ 14–16dp; pills fully rounded; bottom sheet top ≈ 24dp `[V approx]`.
- Poster ratio ≈ 5:7; episode card ≈ 7:5; schedule thumb ≈ 7:5 `[V approx]`.
- Define tokens once (`colors.xml` + theme overlays). **No hex literals in layouts.**

## 6. Global components

| Component | Spec |
|---|---|
| Primary button | Filled accent, fully rounded, leading circled-play icon, bold label (e.g. Play) `[V]` |
| Secondary button | Outlined (white or accent stroke), fully rounded, leading icon (e.g. + My List, Download) `[V]` |
| Tonal button | `accentContainer` bg, accent text, leading `+` (Schedule "+ My List") `[V]` |
| Age badge | White chip, dark bold text (e.g. PG-13), top-left on poster `[V AniLab]` |
| Quality badge | Accent chip, white bold "HD", top-right on poster (AniLab) / top-left (Th3) `[V]`; Animarvel: top-right `[D]`; shown only if the source reports ≥720p `[D]` |
| Section header | Bold title left, accent "See all" right `[V]` |
| Chip (filter) | Selected: accent fill, dark/white text. Unselected: `surfaceVariant` pill with subtle outline `[V]` |
| Search field | Outlined rounded pill, leading search icon, muted hint `[V]` |
| Empty state | Centered illustration, accent bold title, muted two-line subtitle `[V]` |
| Bottom nav | **5 tabs with labels: Home · Schedule · My List · Download · Profile** `[D]`. AniLab's phone build shows 4 icon-only tabs (no Download) and its mockup + Th3 show 5 labeled tabs `[V]`; Animarvel follows the 5-tab version. Active = accent icon+label; inactive = muted. |
| Toolbar (tab screens) | App logo (own) left, screen title, search icon right (Home also bell) `[V]` |
| Long titles | Single line; on details, marquee-scroll when overflowing `[I]` (both apps show mid-scroll clipped titles) |
| Icon-tile button | ≈ 48dp rounded-square tile, tonal container + coloured icon (Search: shuffle = `accentAlt`, filter = `accent`) `[V]` |
| Outlined option chip | Accent 1.5dp stroke + accent text; **selected** = accent fill + white text; wraps to multiple lines `[V Sort & Filter]` |
| Centered dialog | Rounded (~20dp) card on dimmed backdrop, bold title, rows separated by hairline dividers, accent text "Close" bottom-right; selected row = accent text + check at right `[V Quality dialog]` |
| Info chip | Uppercase label, `info` text on `info` container, small radius (e.g. ANNOUNCEMENT) `[V]` |

## 7. Screens (build exactly these)

Each screen lists: **Layout → States → Actions → Data**. `[V]` visible in a screenshot; `[D]` Animarvel decision;
`[?]` not observed — build the stated minimal version and flag it in your report.

### 7.1 Welcome / onboarding (first launch only) `[V mockup, may not exist in the real app]`
- Layout: diagonal-sliced poster collage; headline "Welcome to Animarvel." (brand word in accent); short tagline;
  pager dots (active = elongated accent); two bottom buttons.
- Actions: **Get started** → Home. **Sign in** → Profile sign-in (hidden until a tracker login exists, §7.11) `[D]`.
- Show once (DataStore flag). Posters: from AniList trending cache; fallback to solid gradient `[D]`.

### 7.2 Home `[V]`
- **Hero** (full-bleed, gradient fade to `bg`): top row logo (left), search + bell (right). Bottom-left: title
  (bold, white, **1 line, ellipsized**, per AniLab; Th3 wraps to 2 — not followed), genres line (muted, 1 line),
  buttons **Play** (primary) + **My List** (secondary, toggles to "In list").
  Hero = single featured item = first of Trending `[D]`; carousel `[?]`.
- **Row: Continue watching** (shown only if history exists): cards ≈ poster ratio; top-left ⓘ (→ Details), top-right ✕
  (remove entry; confirm via snackbar undo `[D]`), bottom-left "Episode N" label, bottom progress bar (accent
  portion on muted track). Tap card → Player resume at saved position.
- **Row: Top Airing** + "See all": posters with age badge (top-left) + quality badge (top-right) `[V]`, title under
  poster (1 line, ellipsized) `[V Th3]`.
- **Further rows** `[I from Th3 strings + AniLab mockup]`: *Top Hits* (ranked numerals 1,2,3 overlaid bottom-left
  `[V mockup]`), *New Episode Releases*, *Recently Added*. Ship Top Airing, Top Hits, New Episodes in v1 `[D]`.
- **Content-type chips** above the first row: **All · Anime · Movies** `[D]`. *All* = anime rows then movie rows;
  *Anime* = Top Airing, Top Hits, New Episodes, Recently Added; *Movies* = Popular, Top Rated, Upcoming, + 2–3 genre rows
  `[D]`. If no movie metadata provider is configured (ARCH §10), the *Movies* chip shows the "Set up movies" empty state
  (§7.14) instead of rows. Movie posters show the same age + quality badges; no episode numbers.
- States: skeleton posters while loading; pull-to-refresh; per-row retry on error; offline → cached data + banner.
- Data: metadata provider (ARCH §10), history (Room).

### 7.3 Search `[V Th3 screenshot]`
- **Top row**: back arrow · search field (outlined pill, leading magnifier, hint "Search…", IME action = search) ·
  **Shuffle** icon-tile (`accentAlt`) = open a random title (respecting the active type chip) · **Filter** icon-tile
  (`accent`) → §7.3b.
- **Before typing**: heading **"Top Searches"** (bold) over a vertical list. Each row: landscape thumbnail (≈ 7:5, rounded),
  title (bold, ≤2 lines), release/air date `YYYY-MM-DD` (muted), trailing circled-play outline icon (accent) →
  Details (row) / Player (play icon, resumes or Ep 1 / the movie) `[D]`. **Recent searches** (clearable) appear above
  Top Searches when present `[I: Th3 strings]`.
- **While typing**: same row layout, debounced 350 ms `[D]`; empty result state; error state with retry.
- A **type chip row** (All · Anime · Movies) sits under the field `[D]`; the Filter tile adds the groups in §7.3b.
- Data: Top Searches = metadata provider's "popular/trending" list (no backend search analytics) `[D]`.

### 7.3b Sort & Filter `[V Th3 screenshot]`
- Full screen: back arrow + centered title "Sort & Filter", hairline under toolbar. Scrollable body of **groups**, each a
  bold heading + wrapping **outlined option chips** (single-select, first = "All"/"Default"):
  - **Type** *(anime formats)*: All · Movie · TV · OVA · ONA · Special · Music `[V]`.
  - **Status**: All · Finished Airing · Currently Airing · Not yet aired `[V]`.
  - **Season**: All · Spring · Summer · Fall · Winter `[V]`.
  - **Sort**: Default · Recently Added · Recently Updated · Score `[V]` (more options may exist below the fold `[?]`).
- Fixed bottom bar with hairline above: **Reset** (tonal) and **Apply** (primary, wider) `[V]`.
- **Animarvel adaptation `[D]`:** a **Category** group (All · Anime · Movies) comes first. When *Movies* is selected the
  anime-only groups (Type/Status/Season) are hidden and replaced by **Genre** (multi-select), **Year** (from–to), and
  **Sort** = Popularity · Rating · Release date · Title. "Movie" in the anime *Type* group means an anime film.
- Apply returns to Search/See-all with the filters shown as removable chips; Reset clears all.

### 7.4 Notifications (bell) `[V Th3 screenshot]`
- **Toolbar**: back arrow · centered title "Notifications" · accent text **Clear All** (right).
- **Item row**: portrait thumbnail (≈ 2:3, rounded; Th3 shows a grey "300 x 450" placeholder, so image is optional →
  use a local drawable, never a placeholder URL), title (bold; may start with an emoji), relative date at far right
  ("Today"), body (muted, 2 lines, ellipsized), **info chip** (e.g. ANNOUNCEMENT).
- **Two item kinds** `[D]`: `NEW_EPISODE` (title/poster of a My List item; chip "NEW EPISODE"; tap → Details/Play;
  for movies "NOW AVAILABLE"/release reminder) and `ANNOUNCEMENT` (text from an **optional** remote JSON feed;
  Zangetsu keeps an `announcements.json` in its repo `[V filename]`, Th3 has an announcement service `[V strings]`).
  Announcement feed URL = **owner decision `[?]`**; default **off** (list shows only NEW_EPISODE items).
- Empty state; unread dot; swipe to dismiss; Clear All confirms with undo snackbar `[D]`.
- Backed by a WorkManager periodic check (ARCH §10). Notification permission requested on first library add.

### 7.5 See all `[?]`
- `[D]` Toolbar title = row name; 3-column poster grid, infinite scroll (paged), same badges.

### 7.6 Details `[V]`
- **Banner** (top ≈ 38% height): back (translucent circle) left; **Cast** icon right (disabled until Cast phase).
  Content sheet overlaps with rounded top corners `[V Th3]`.
- **Title row**: title (marquee when long) · bookmark icon (toggle library) · share icon (system share `[D]`).
- **Meta row**: ★ score (accent) · dot · year · outlined badges (age rating, quality) `[V]`.
- **Buttons row**: **Play** (primary, resumes if history; label becomes "Resume Ep N") · **Download** (secondary).
- **Genres**: "Genre: a, b, c" 1 line, ellipsized `[V]`. **Synopsis**: collapsed ~3 lines with "… View More" accent
  toggle `[V]` (AniLab also shows a quote line; not required).
- **Episodes**: header "Episodes" · **air-countdown chip** when the show is airing, e.g. "Ep 9 · 9d 4h (19 Oct)"
  `[V Th3]` from `nextAiringEpisode` · **Search episode** pill `[V]`. Below: horizontal episode cards: thumbnail
  (fallback = poster/banner — Th3 does this `[V]`), centered play circle, bottom-left "Ep N: Title", optional
  **Filler** tag top-left `[V AniLab]` (only if metadata provides it `[?]`), watched dim + progress bar `[D]`.
  Tap → Player at that episode.
- **Seasons** section below episodes `[V Th3 label cut off; content unseen]`: `[D]` horizontal chips of related
  seasons/sequels from metadata relations.
- **Tabs**: **More like this** (poster grid) · **Comments (N)** `[V both apps]`. Comments need a backend we don't
  have → render the tab with an empty/"coming later" state and no count `[D]`.
- **Movie variant** `[D]` (same screen, fewer parts — the AniLab way; its details screen is internally "movie detail"
  `[V resource name]`):
  - Meta row: ★ score · year · **runtime** (e.g. 2h 08m) · certification badge · quality badge.
  - Buttons: **Play** ("Resume 1h 12m" if in history) · **Download**. Bookmark/share in the title row as usual.
  - **No** Episodes header/countdown chip/Seasons/episode search. Genres + synopsis + "View More" stay.
  - Tabs: **More like this** (similar movies) · **Comments** (placeholder). **No cast/crew/trailer section** in v1 `[D]`.
  - Release states: *Upcoming* → Play/Download replaced by **"Remind me"** (adds to My List + release notification).
- Data: metadata details + episode list resolved via a matched source (ARCH §7). If no installed source matches →
  Play shows the **"Install a source"** bottom sheet linking to §7.13.

### 7.7 Player `[V Th3 portrait layout]`
Portrait screen: player on top, details below.
- **Top bar** (over video). Two variants were seen in Th3 `[V]`: *loading* = back · title · speed · subtitles · ⋮;
  *playing* = back · title · cast · **quality pill ("AUTO", accent text on tonal container)** · speed · subtitles
  (accent outline when a track is on) · ✕. **Animarvel `[D]`:** back · title · cast (hidden until the Cast phase) ·
  **quality pill** · speed · subtitles · **⋮ overflow** (Server, Audio/Sub language, Subtitle delay, Select player/
  external player, Sleep timer). No ✕ (back already exits).
- **Quality dialog** `[V Th3]`: tap the pill → **centered dialog** titled "Quality": rows **AUTO** (accent, check when
  selected) then levels high→low (**1080P · 720P · 480P**, uppercase "P") then **Close**. Levels come from the stream
  (ARCH §6), never hardcoded. AniLab has matching strings "Choose quality / Choose server / Choose subtitle"
  `[V]`, so **Server** and **Subtitle** choosers use the same centered-dialog component `[I]`.
- **AniLab extras `[V strings]`:** Skip intro / Skip outro buttons; Subtitle delay; "Select Player" (external player).
- **Center**: accent spinner while buffering. **Seekbar**: `mm:ss` current · white thumb · muted track · `mm:ss` total.
- **Bottom controls**: Lock · Volume · −10s · Play/Pause (large) · +10s · Download episode · Fullscreen.
- **Below the player**:
  - "You are watching **Episode N**" (label accent, number white).
  - **Sub | Dub** tabs (accent underline on selected). A tab with no availability is disabled (§ARCH 6).
  - **Server chips** (selected = accent fill). One chip per available server for the chosen audio.
  - **Season Selector (TV Series)**: Option 1 obsidian tonal capsule pill `[ 🗂 Season X ▾ ]` opening `SeasonSelectionBottomSheet`. Omitted for Movies.
  - **Action buttons row**: `[ + Add to List ]` · `[ 📺 Cast ]` · `[ 📥 Download ]`.
  - **List of episodes header**: "Episodes" title + "EPS: 1–100 ▾" range filter pill (when season > 1 episode) + episode search capsule + view mode toggle button (`[ ▦ ]` / `[ ▤ ]`).
  - **Dual-mode episode grid**: Mode A (default) 2-column 16:9 thumbnail cards with full-bleed stills, gradient scrim, episode title, and active white highlight border; Mode B 6-column anime-style number tiles with active white fill. Toggleable and persistent.
  - **Movies** `[D]`: the whole "List of episodes" and season selector block is omitted; the line reads "You are watching **<Title>**
    (<year>)"; if the source gives only original audio (no Sub/Dub), the Sub|Dub tabs are **hidden** (not disabled) and
    only subtitles apply.
  - **Comments card** at the bottom with arrow `[V]` → same placeholder as Details `[D]`.
- **Fullscreen**: sensor-landscape, immersive; same controls overlay; gestures `[D]`: double-tap seek ±10 s,
  vertical swipe volume/brightness, **long-press = hold speed** (setting "On-Hold Speed" exists in Th3 `[V string]`).
- **Required behaviors** (all driven by the source plugin — see ARCH §6/§7): Sub/Dub switch, **Server switch with
  automatic fallback**, **Quality change (Auto + levels)**, subtitle track selection, skip intro/outro, autoplay next,
  resume position, PiP, background audio (setting), lock mode, speed (0.5–2×) `[D]`.
- Observed copy (reference, reword): "Try a different server or switch between Sub/Dub.", "Trying next server…",
  "All servers for this episode are currently unavailable." `[V Th3 strings]`.

### 7.8 Schedule `[V Th3]`
- Toolbar: logo · "Schedule" · search.
- **Day strip**: horizontally scrollable rounded tall pills (weekday over date); selected = accent fill; initial =
  today, with ~3 days before/after visible `[V]`.
- **Timeline list** for the selected day: left rail with accent dash + time label (`HH:mm`, **device local time**
  `[D]`) and a vertical line; each item: thumbnail (rounded), title (≤2 lines), "Episodes N" muted,
  **+ My List** tonal button (toggles to "In list"). Tap item → Details.
- Data: airing schedule from metadata (`airingAt` epoch → local) (ARCH §10). States: skeleton, empty day, error+retry.

### 7.9 My List `[V Th3]`
- Toolbar: logo · "My List" · search (filters within list).
- **Status chips** (horizontally scrollable): All · Watching · Plan to Watch · Completed (+ On Hold · Dropped `[D]`;
  Th3 chip row is cut off after "Com…" `[V]`).
- **Empty state**: two-clipboard style illustration (own art), accent title, muted subtitle `[V]`.
- **Populated** `[?]` `[D]`: 2-column poster grid, status badge, "watched/total" progress; long-press → change status /
  remove. Sort: recently updated / title / score `[D]`.
- Optional import/export (MAL/AniList XML) — Th3 has it `[V strings]` — **phase 2**.

### 7.10 Download `[V Th3]`
- Toolbar: logo · "Download" · search.
- **Storage card**: leading tile icon, "Device Storage" + small accent badge (INTERNAL / SD), "X GB available of Y GB",
  accent progress bar (used), "NN% Used" left / "NN% Free" right `[V]`. Computed from `StatFs` `[D]`.
- **Empty state**: illustration + accent title + muted subtitle `[V]`.
- **Populated** `[?]` `[D]`: grouped by title; per episode: thumb, title, size, state (queued/downloading with
  progress/paused/failed/done), tap → play offline, long-press/select → delete (confirm). Top-right: pause-all.
- Download location: app storage by default; custom folder via SAF (setting) `[V Th3 string "Choose Custom Folder (SAF)"]`.

### 7.11 Profile `[V Th3]`
- Toolbar: logo · "Profile".
- **Account card**: "My profile" + "Sign in to sync your list" + **Continue** (primary). `[D]` Animarvel v1 has **no
  cloud backend**: Continue opens **tracker login (AniList)** if implemented, else the card is hidden until then.
  Ask before building tracker login `[?]`.
- **Rows** (icon · title · chevron): **Player & Downloads** · **Subtitle settings** · **Sources** `[D new]` · **Metadata** `[D new]` (movie provider key,
  §7.14) · **About** (version, licences, required metadata-provider attribution).
  *Excluded from Th3:* "Watch ad / Support" card, points.
- Footer: "Animarvel vX.Y.Z (build)" instead of "Created by".

### 7.12 Settings sub-screens `[V strings; layouts unseen → D]`
- **Player & Downloads**: default server order · default audio (Sub/Dub) · default quality (Auto / 1080p / 720p /
  480p / Data-saver) · autoplay next · auto-skip intro/outro · on-hold speed · external player (MX/VLC/mpv/system
  chooser) · background play · PiP · download folder (SAF) · download on Wi-Fi only · concurrent downloads (1–3) ·
  clear watch history · theme (red/green).
- **Subtitle settings**: default subtitle language · text size · text color · background/edge style · position ·
  "save subtitles with download".

### 7.13 Sources (plugin manager) `[D new]`
- List of installed plugins: name, language, version, capability icons (Sub, Dub, multi-server, quality, skip-times,
  downloadable), enable toggle, uninstall, **order** (drag) which sets default server priority.
- **Add**: from a repository URL (list of available plugins) and/or install a local package — mechanism per ARCH §12.
- First-use **consent dialog**: sources are third-party code and content providers, Animarvel hosts and bundles no
  content, the user is responsible for what they install `[D]`.
- Empty state explains what a source is. No source names or URLs are shipped.

### 7.14 "Set up movies" (metadata) `[D]`
- Movies need a general-film metadata provider (TMDB `[A]`, ARCH §10). Until it is configured: the *Movies* chip, Search
  type chip and Filter category show a calm empty state — illustration, "Movies need a free metadata key", button
  **Add key** → Profile › Metadata (text field, paste, test-connection, link to the provider's key page, attribution
  note). Key stored on-device only. *Owner may switch to a shipped key `[?]` (§9 Q2).*

## 8. Source-plugin behavior requirement (the key feature)

The user requirement: **once any plugin is installed, the app must exhibit the same discovery/playback behavior as
both reference apps — Sub/Dub, quality change, server change — with no per-plugin UI code.** The UI is derived only
from what a plugin *declares and returns* (ARCH §5–§7). Acceptance tests are in §10.

## 9. Open questions — ASK, do not guess

1. `minSdk`/`targetSdk` and ABI targets.
2. **Movie metadata**: general films are confirmed `[V user]`. Provider and key policy: **TMDB with a user-supplied key
   (default `[D]`)** vs a project-owned key/proxy. Read TMDB's current API docs, terms and attribution rules first
   `[A]` and report. Dedup of anime films that appear in both providers: **none in v1** `[D]`.
3. Tracker login (AniList/MAL) in v1 or later?
4. Extension distribution mechanism (ARCH §12) — confirm after you verify how Mihon does it.
5. Does AniLab's real home screen use a hero carousel? Quality-picker UI? Search/notification screens? (no screenshots).
6. Download format: Media3 offline (HLS) vs HLS→MP4 export (Th3 does MP4) — ARCH §9.
7. App logo and brand colors final? (Placeholders until answered.)
8. **Spelling of the app name**: "Animarvel" or "Animarvle" (both used by the owner)? Drives `app_name` and `applicationId`.
9. Zangetsu's actual movie handling (metadata source, extension format) is unverified — ask the owner for screenshots or
   a specific file to read; its code pages block automated fetching.
10. Announcement feed URL for §7.4 (default off).

## 10. Milestones and acceptance (report each with real command output)

| # | Deliverable | Acceptance (must show output) |
|---|---|---|
| M0 | Gradle project, `docs/VERSIONS.md` with cited sources | `./gradlew assembleDebug` succeeds; APK path printed |
| M1 | Theme tokens (2 themes), 5-tab shell, empty-state screens, all §6 components | Screenshot of each tab on emulator/device or Robolectric/Paparazzi-style test output |
| M2 | Metadata layer (AniList) + Home/Search/Details/Schedule on real data | Unit tests for parsing against saved fixtures; manual run notes |
| M3 | `:source-api`, `FakeSource`, `StreamResolver`, Player screen | **Resolver unit tests** (below) pass; player plays a verified test stream |
| M4 | Library, history, downloads | Room migration test; download completes offline play test |
| M5 | Settings, Sources screen, extension loader | Install/uninstall a sample plugin APK; UI updates without restart |
| M6 | Polish, a11y labels, edge cases, README | Full test run output |

**Resolver acceptance tests (M3, with `FakeSource`):**
1. Source declares SUB+DUB → both tabs enabled; declares SUB only → Dub tab disabled + message.
2. Server A fails, B succeeds → user sees B playing; error logged; chip B selected ("next server" path).
3. All servers fail → "all servers unavailable" state with Retry.
4. HLS master with 360/720/1080 → quality sheet shows Auto + 3 levels; switching keeps position within ±1 s.
5. Direct (non-HLS) multi-quality list → same sheet, switching re-prepares at saved position.
6. Source returns skip-times → Skip Intro/Outro buttons appear; auto-skip honors the setting.
7. Source returns subtitle tracks → selectable; default language honored.
8. Mid-playback server failure → auto-fallback to next server resuming at last position.
9. Metadata item not found in a source → "no match" handled; user can pick a match manually.
10. **Movie**: `MediaType.MOVIE` with one `SourceEpisode(number=1)` → Details shows no episode UI; Play resolves via the same
    §7.3 pipeline; resume position restores; progress ≥ 90 % marks watched.
11. **Movie, RAW-only audio**: Sub|Dub tabs hidden; subtitle chooser works; quality + server fallback still work.
12. **Matcher, movies**: two titles with the same name and different years → the year-matching candidate wins.
13. Quality dialog lists exactly the levels the stream exposes (+ AUTO); selecting a level keeps position within ±1 s.

## 11. Reporting format (every status update)

```
VERIFIED:   <what you ran/fetched + actual output or citation>
INFERRED:   <what you concluded and from which evidence>
UNKNOWN:    <what you could not check>
CHANGED:    <anything that contradicted this plan and what you did>
NEXT:       <one step>
```
