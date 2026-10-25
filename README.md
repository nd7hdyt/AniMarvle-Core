# Animarvle (AniLab)

An Android application for streaming Anime, Movies, and TV series, built with native Kotlin, ExoPlayer, Jetpack architecture components, and an AniVortex-inspired obsidian frosted glass UI with pure OLED black theming.

---

## ✨ Features & Architecture Highlights

### 🌌 1. Pure OLED Black (#000000) & Obsidian Glass Design System
- **Pure `#000000` OLED Black Foundation**: Every background is locked to pitch black so poster art and typography pop with zero grey/navy wash.
- **Obsidian Glass Surfaces**:
  - Primary surfaces: `#0F0F14` / `#121217`
  - Floating dialogs and cards: Translucent obsidian frosted glass (`#B814151A`).
- **Live Frosted-Glass Backdrop Blur (`VortexBlurHelper`)**:
  - Real-time stack blur capturing content behind the top header and bottom navigation bar.
  - Subtle glowing specular edge highlights (`topGlowBorder` and `bottomGlowBorder`) for true glassmorphism.

### 🎬 2. Dual Media Hubs
- **Anime Mode**:
  - Powered by AniList with curated vertical rails (Trending, Airing Now, Popular this Season, Top Rated, Donghua, All-Time Popular, Anime Movies).
  - Clean layout without third-party streaming provider bubbles.
  - "More >" navigation directly to categorized search feeds.
- **Movies & TV Mode**:
  - Powered by TMDB (Trending Movies, Trending TV Series, Top Rated on IMDb, Now Playing in Theaters, Popular Movies, Popular TV Shows, Cartoons & Animation, K-Drama, C-Drama).
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
  - Redesigned available-options bottom sheet (Pending, Watching, Completed, Remove from My List).
  - AniVortex-style pagination bar (`01 ━━━ 08`).

### ⏱️ 4. Vertical Continue Watching
- **2:3 Cover Poster Geometry**:
  - Compact vertical cards (118dp × 170dp) showcasing official series/movie cover art instead of stretched horizontal thumbnails.
  - Overlaid frosted play badge, bottom gradient scrim, clean "Ep X • Ym left" badge, and pure white progress bar.
  - Reliable cover resolution cache and history fallback prioritizing `media.coverUrl`.

### 🔄 5. Pull-to-Reload with Vortex Morph Loader
- **VortexMorphLoadingView**: Custom procedural polygon-morphing loading indicator.
- Pull-down touch tracking on `feedRecyclerView` with dynamic reveal and smooth dismiss animations on data refresh.

### 🎭 6. Details Screen, Franchise Seasons & Cinematic Parallax Backdrop
- **35% Parallax Backdrop**: Hero poster translates upward at ~35% of content scroll velocity (`parallaxFactor = 0.35f`), delivering rich visual depth and atmospheric perspective without detaching from the top viewport edge.
- **Progressive Hardware Dimming**: Smooth dark overlay (`heroDimOverlay`) dynamically scales from 0% to 75% opacity as foreground content glides up into pure OLED black shadows.
- **Gradual Solid Black Toolbar**: 220dp extended linear gradient transition smoothly matures from a subtle status-bar pre-scrim into a solid `#000000` header with compact 34dp circular frosted action plates.
- **Chronological Franchise Relations & One-Shot Filter**: Traverses prequel/sequel relation chains backward and forward to build strictly chronological season orders ($1, 2, 3\dots$), filtering out standalone 1-episode ONAs/OVAs/specials (e.g. *MONSTERS*) from hijacking main series.
- **Continuous Anime TMDB Saga Enrichment**: Continuous single-entry anime (e.g. *One Piece*, *Bleach*, *Naruto*, *Detective Conan*) are enriched with all canonical story sagas from TMDB TV (e.g. all 23 seasons for *One Piece*), calculating `startEpisodeNumber` offsets and arc titles.
- **In-Place Season Selection**: Switching seasons in the dropdown updates episodes in-place without page reload or mutating franchise relation chains.

### 🍿 7. AniVortex Obsidian Glass Player & Smart Auto-Rotate
- **Smart Auto-Rotate Fullscreen (Sole Exception)**:
  - App-wide orientation is strictly locked to portrait across Home, Details, My List, Profile, and Search, preventing awkward stretching when device auto-rotate is enabled.
  - On the player screen, device auto-rotate dynamically expands the player into 100% immersive fullscreen landscape (completely hiding the title, server chips, and episode list below).
  - Returning the phone vertically seamlessly restores the portrait layout with title, server chips, and system bars.
  - Hardware accelerometer monitoring (`OrientationEventListener`) automatically unlocks manual fullscreen overrides upon physical reorientation.
- **Localized Glass Submenus**:
  - Floating 4-corner geometry (`26dp` rounded corners, `12dp` bottom float margin).
  - Exact 3.5-row height clamping (`MaxHeightRecyclerView`) so the 4th item peeks at the bottom.
  - Persistent `#B3FFFFFF` vertical scrollbar thumb on frame 1 with touch-scrubbing support (`VortexScrollbarDecoration`).
  - Single-window submenu swapping (Playback Settings -> Speed, Quality, Audio, Subtitles) without window recreation or flicker.
- **Advanced Playback Engine**:
  - ExoPlayer with HLS adaptive bitrate streaming, manual resolution selection (4K down to 144p), and multi-audio track selection.
  - Custom subtitle engine with ASS/SSA styling, delay adjustments, and web subtitle support.
  - Full background & Picture-in-Picture (PiP) support, hardware resize modes (Fit, Fill, Zoom), and sleep timer.

### ⚡ 8. Cloudflare Serverless Edge Streaming Architecture
- **Unified Edge Relay (`AnimarvleStreamSource`)**:
  - Deprecated legacy extension managers and external source installs in favor of a fast, native Cloudflare Edge Worker relay.
  - Zero-configuration streaming: automatically handles stream resolution, anti-bot bypass, decryption, and multi-server fallback (Spiral, Turbo, FastX) out of the box.

### 📥 9. Offline Downloads
- Background downloading engine with batch episode selection, quality badges, and resume support.

### 👤 10. User Account, Preferences & Profile System
- **Google Play Services OAuth 2.0 Integration**:
  - Direct integration via `play-services-auth:21.3.0` with secure token resolution and robust empty-client error handling.
  - Automatic profile sync with high-resolution Google avatar loading via Coil circular crop.
- **Redesigned Profile Surface & Account Management**:
  - **Before Login**: High-impact "Make Animarvle yours" card with sync messaging and device watermark illustration.
  - **After Login**: Clean centered profile header with 88dp circular avatar, bold username, muted email, and quick-access `✎ Edit profile` action.
  - **Bottom Log Out Placement**: Prominent, dedicated red card button (`#FF2E55`) positioned at the bottom of the profile screen before the footer, preventing accidental sign-outs.
  - **Complete Edit Profile Flow**: Real-time display name editing with live preview, and a 39-slot avatar grid selection system.
- **OLED Obsidian Preferences Suite**:
  - **Player & Downloads**: Dedicated hub for playback quality, audio language, download paths, and concurrency.
  - **Subtitle Settings**: Real-time typography styling, font size, text colors, outline thickness, and subtitle positioning.
  - **Adult Mode**: Live 18+ content toggle integrated into AniList GraphQL queries (`isAdult: false` strict filter when disabled; adult titles included when enabled).
  - **Notifications**: System updates and new releases toggle with Android 13+ runtime permission coordination.
  - **Double Tap to Skip**: Material choice selector with expanded durations (`5s`, `10s`, `15s`, `20s`, `30s`, `45s`, `60s`, `90s`).
  - **Anime Skip Button**: Toggle for the in-player 90s intro/outro skip pill.
- **Default Audio Preference (SUB/DUB)**:
  - Instant high-contrast button toggle between Japanese (SUB) and English (DUB) in Player Settings.
  - Automatically propagated from episode details into playback sessions.
- **About & Legal OLED Suite**:
  - Pure OLED Black (`#000000`) theme and obsidian `#181818` card surfaces matching the app foundation.
  - Official community channels (Telegram, Discord, GitHub).
  - Self-contained in-app Legal Documents modal suite (**Privacy Policy**, **Terms of Service**, **Copyright & Takedown**) with clean in-app summaries and zero external web dependencies.
  - Prominent non-hosting legal disclaimer card and one-tap Copy Diagnostics utility.

---

## 🛠️ Tech Stack

- **Language**: Kotlin 1.9+
- **Architecture**: MVVM + Clean Architecture + Repository Pattern
- **Media Engine**: AndroidX Media3 / ExoPlayer 1.3+
- **UI Framework**: Material Design 3, ViewBinding, ConstraintLayout, Custom Views & ItemDecorations
- **Image Loading**: Coil
- **Networking**: Retrofit 2, OkHttp 5, Kotlin Coroutines & Flows
- **Local Persistence**: Room Database, AndroidX DataStore / SharedPreferences
- **Edge / Proxy**: Cloudflare Workers (Node / V8 runtime)

---

## 🚀 Building & Running

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 34+ / Android 16 (API 36) compatible device or emulator

### Clone & Build
```bash
git clone https://gitlab.com/nd7hdyt/Animarvle.git
cd Animarvle

# Verify Kotlin compilation
./gradlew compileDebugKotlin

# Build debug APK
./gradlew assembleDebug

# Install directly on connected device
./gradlew installDebug
```

---

## 📄 License & Attribution

Designed and developed for high-fidelity media streaming. Built with inspiration from AniLab, AniVortex, and the Tachiyomi/Aniyomi open-source ecosystem.
