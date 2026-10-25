# Animarvle

[![Android CI](https://github.com/nd7hdyt/AniMarvle/actions/workflows/android.yml/badge.svg)](https://github.com/nd7hdyt/AniMarvle/actions/workflows/android.yml)
[![Latest Release](https://img.shields.io/github/v/release/nd7hdyt/AniMarvle?color=00D4B2&label=version)](https://github.com/nd7hdyt/AniMarvle/releases)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

An Android application for streaming Anime, Movies, and TV series, built with native Kotlin, AndroidX Media3 / ExoPlayer, Jetpack Architecture Components, Room, and a refined obsidian frosted-glass UI with pure OLED black theming.

---

## ✨ Features & Architecture Highlights

### 🌌 1. Pure OLED Black (#000000) & Obsidian Glass Design System
- **Pure `#000000` OLED Black Foundation**: Every background is locked to pitch black so poster art and typography pop with zero grey/navy wash.
- **Obsidian Glass Surfaces**:
  - Primary surfaces: `#0F0F14` / `#121217` / `#181818`
  - Floating dialogs and bottom sheets: Translucent obsidian frosted glass (`#B814151A`) with subtle 1dp specular borders (`#282830`).
- **Live Frosted-Glass Backdrop Blur (`VortexBlurHelper`)**:
  - Real-time stack blur capturing content behind the top header and bottom navigation bar.
  - Subtle glowing specular edge highlights (`topGlowBorder` and `bottomGlowBorder`) for true glassmorphism.

### 🎬 2. Dual Media Hubs
- **Anime Mode**:
  - Powered by AniList with curated vertical rails (Trending, Airing Now, Popular this Season, Top Rated, Donghua, All-Time Popular, Anime Movies).
  - Clean layout focused entirely on anime without live-action provider clutter.
  - Category headers leading directly to searchable catalog feeds.
- **Movies & TV Mode**:
  - Powered by TMDB (Trending Movies, Trending TV Series, TMDB Top Rated, Now Playing in Theaters, Popular Movies, Popular TV Shows, Animation & Family, K-Drama, C-Drama).
  - Interactive streaming provider bubbles (Netflix, Prime Video, Disney+, Apple TV+, Crunchyroll, Sony LIV, Hotstar, Max) leading to dedicated `ProviderCatalogFragment` with sorting and filtering.

### 🌟 3. Hero Carousel Banner & Reactive My List
- **500dp Immersive Banner**:
  - Top dark scrim ensuring logo, segmented capsule, and search icons remain razor-sharp over any artwork.
  - Deep 310dp bottom gradient scrim for high-contrast title and metadata readability.
- **Auto-Scroll Engine**:
  - 4.5-second automatic sliding with gesture-drag pausing and idle resume.
- **Action Controls & Reactive Indicator**:
  - Solid crisp white `▶ Play` button with dark icon/text.
  - Reactive `My List` button with real-time `itemsFlow` synchronization: dynamically displays filled accent bookmark with `"In List"` when saved, or outline bookmark with `"My List"` when unsaved.
  - Refined available-options bottom sheet (Pending, Watching, Completed, Remove from My List).
  - Clean pagination indicator (`01 ━━━ 08`).

### ⏱️ 4. Vertical Continue Watching Rail & Domain Isolation
- **2:3 Cover Poster Geometry**:
  - Compact vertical cards (118dp × 170dp) showcasing official series/movie cover art instead of stretched horizontal thumbnails.
  - Overlaid frosted play badge, bottom gradient scrim, clean "Ep X • Ym left" badge, and pure white progress bar.
- **Strict Media Domain Partitioning**:
  - Anime mode Continue Watching rail strictly displays genuine anime and donghua.
  - Live-action movies and TV series appear exclusively in the Movies & TV hub.
  - Dynamic history auto-sanitizer (`HistoryRepository`) cleans legacy entries on startup.

### 🔄 5. Multi-Screen 1:1 Real-UI Skeletons & Morph Loader
- **1:1 Structural Skeleton Loaders**:
  - Pre-media loading states on Details, Home, and Provider Catalog screens mirror the exact layout hierarchy of the loaded content (hero banner, wide cards, action capsules, and grids).
  - Smooth alpha pulse animation (1.0f ↔ 0.42f, 850ms reverse repeat) for fluid, zero-jank content transitions.
- **VortexMorphLoadingView**: Custom procedural polygon-morphing loading indicator for swipe-to-refresh.

### 🎭 6. Details Screen, Franchise Seasons & Cinematic Parallax
- **35% Parallax Backdrop**: Hero poster translates upward at ~35% of content scroll velocity (`parallaxFactor = 0.35f`), delivering rich visual depth without detaching from the top viewport edge.
- **Progressive Hardware Dimming**: Smooth dark overlay (`heroDimOverlay`) dynamically scales from 0% to 75% opacity as foreground content glides up into pure OLED black shadows.
- **Chronological Franchise Relations & One-Shot Filter**: Traverses prequel/sequel relation chains backward and forward to build strictly chronological season orders ($1, 2, 3\dots$), filtering out standalone 1-episode ONAs/OVAs/specials from hijacking main series.
- **Continuous Anime TMDB Saga Enrichment**: Continuous single-entry anime (e.g. *One Piece*, *Bleach*, *Naruto*, *Detective Conan*) are enriched with all canonical story sagas from TMDB TV (e.g. all 23 seasons for *One Piece*), calculating `startEpisodeNumber` offsets and arc titles.
- **In-Place Season Selection**: Switching seasons in the dropdown updates episodes in-place without page reload or mutating franchise relation chains.

### 🍿 7. Obsidian Glass Player & Smart Auto-Rotate
- **Smart Auto-Rotate Fullscreen**:
  - App-wide orientation is strictly locked to portrait across Home, Details, My List, Profile, and Search.
  - On the player screen, device auto-rotate dynamically expands the player into 100% immersive fullscreen landscape (completely hiding the title, server chips, and episode list below).
  - Returning the phone vertically seamlessly restores the portrait layout with title, server chips, and system bars.
- **Localized Glass Submenus**:
  - Floating 4-corner geometry (`26dp` rounded corners, `12dp` bottom float margin).
  - Single-window submenu swapping (Playback Settings -> Speed, Quality, Audio, Subtitles) without window recreation or flicker.
- **Advanced Playback Engine**:
  - AndroidX Media3 / ExoPlayer with HLS adaptive bitrate streaming, manual resolution selection (4K down to 144p), and multi-audio track selection.
  - Dual-mode episode layout (2-column 16:9 thumbnail cards vs. 6-column anime number tiles).
  - Custom subtitle engine with ASS/SSA styling, delay adjustments, and web subtitle support.
  - Full background & Picture-in-Picture (PiP) support, hardware resize modes (Fit, Fill, Zoom), and sleep timer.

### ⚡ 8. Stream Resolution Gateway (`AnimarvleStreamSource`)
- **Unified Resolution Engine**:
  - Native stream resolution gateway supporting multiple high-speed endpoints (Spiral, Turbo, FastX) out of the box.
  - Automatic fallback between audio profiles (SUB, DUB, RAW) ensuring seamless playback.

### 🔄 9. In-App Update Engine & Launch Reminders
- **Dual-Source Update Resolution**:
  - Automatically queries raw repository `version.json` with fallback to GitHub Releases API.
  - Compares semantic versioning and build codes against `BuildConfig.VERSION_CODE`.
- **Modern Obsidian Update Modal**:
  - 28dp rounded obsidian sheet with cyan squircle badge, version details, and release notes.
  - Streaming in-app APK download with real-time progress bar and automatic `FileProvider` package installation.
- **Settings & Profile Integration**:
  - Dedicated "Check for updates on launch" toggle switch.
  - Interactive "Check for updates" row with version badges and instant live check.

### 👤 10. User Account, Preferences & Profile System
- **Google Play Services Authentication (`play-services-auth`)**:
  - Account sync with high-resolution Google avatar loading via Coil circular crop.
  - Migration to Android Credential Manager (`androidx.credentials`) is queued on the technical roadmap.
- **OLED Obsidian Preferences Suite**:
  - **Player & Downloads**: Quality, audio language, download paths, and concurrency.
  - **Subtitle Settings**: Real-time typography styling, font size, text colors, outline thickness, and subtitle positioning.
  - **Adult Mode**: Live 18+ content toggle integrated into AniList GraphQL queries.
  - **Notifications**: System updates and new releases toggle with Android 13+ runtime permissions.
  - **Double Tap to Skip**: Material choice selector (`5s`, `10s`, `15s`, `20s`, `30s`, `45s`, `60s`, `90s`).
  - **Anime Skip Button**: Toggle for the in-player 90s intro/outro skip pill.
- **About & Legal OLED Suite**:
  - Self-contained in-app Legal Documents modal suite (**Privacy Policy**, **Terms of Service**, **Copyright & Takedown**) with clean in-app summaries and zero external web dependencies.
  - Prominent non-hosting legal disclaimer card and one-tap Copy Diagnostics utility.

---

## 🛠️ Tech Stack & Dependencies

- **Language**: Kotlin 2.0+
- **Architecture**: MVVM + Clean Architecture + Repository Pattern
- **Media Engine**: AndroidX Media3 / ExoPlayer 1.5+
- **UI Framework**: Material Design 3, ViewBinding, ConstraintLayout, Custom Views & ItemDecorations
- **Image Loading**: Coil 2.7+
- **Networking**: Retrofit 2, OkHttp 4.12+, Kotlinx Serialization, Coroutines & Flows
- **Local Persistence**: Room Database, AndroidX DataStore / SharedPreferences
- **Edge Gateway**: Cloudflare Workers (Node / V8 runtime)

---

## 🚀 Building & Running

### Prerequisites
- **Android Studio**: Latest release supporting AGP 9.4+ (or build directly using bundled `./gradlew`)
- **Gradle**: **9.8.2** (bundled via `./gradlew`) & **AGP 9.4.1**
- **JDK**: **17** or **21**
- **Android SDK Platforms**: `compileSdk = 37`, `targetSdk = 37`, `minSdk = 26` (Android 8.0+)

### Clone & Build
```bash
# Clone the repository
git clone https://github.com/nd7hdyt/AniMarvle.git
cd AniMarvle

# Verify Kotlin compilation
./gradlew compileDebugKotlin

# Build debug APK
./gradlew assembleDebug

# Install directly on a connected ADB device
./gradlew installDebug
```

---

## 📄 License & Attribution

This project is licensed under the terms of the GNU General Public License v3.0 ([LICENSE](LICENSE)).
Designed and developed for high-fidelity media streaming.
