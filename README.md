# Animarvle (AniLab)

An Android application for streaming Anime, Movies, and TV series, built with native Kotlin, ExoPlayer, Jetpack architecture components, and an AniVortex-inspired obsidian frosted glass UI.

---

## ✨ Features

- **Dual Media Mode**:
  - **Anime Mode**: Powered by AniList with 8 parallel rails (Trending, Airing Now, Popular this Season, Upcoming, All-Time Popular, Top Rated, Most Favorited, Popular Movies).
  - **Movies & TV Mode**: Powered by TMDB (Trending Movies, Trending TV Series, Popular Movies, Popular TV Series, Top Rated, Now Playing).
- **AniVortex Obsidian Glass Player**:
  - Localized frosted glass blur behind player cards (`FLAG_BLUR_BEHIND` on Android 12+) while keeping external video sharp and player HUD visible.
  - Floating 4-corner geometry (`26dp` rounded corners, `12dp` bottom float margin).
  - Exact 3.5-row height clamping (`MaxHeightRecyclerView`) so the 4th item peeks at the bottom.
  - Persistent `#B3FFFFFF` vertical scrollbar thumb on frame 1 with touch-scrubbing support (`VortexScrollbarDecoration`).
  - Single-window submenu swapping (Playback Settings -> Speed, Quality, Audio) without window re-creation.
- **Advanced Playback Engine**:
  - ExoPlayer with HLS adaptive bitrate streaming, manual resolution selection (4K down to 144p), and multi-audio track selection.
  - Custom subtitle engine with ASS/SSA styling, delay adjustments, and web subtitle support.
  - Full background & Picture-in-Picture (PiP) support, hardware resize modes (Fit, Fill, Zoom), and sleep timer.
- **Extension & Source Ecosystem**:
  - Compatible with Tachiyomi / Aniyomi extension packages and custom scrapers.
  - In-app Extension Manager with repository feeds and automatic updates.
  - High-performance Cloudflare serverless worker proxy for bypassing CORS, anti-bot protections, and stream decryptions.
- **Offline Downloads**:
  - Background downloading engine with quality badges and resume support.

---

## 🛠️ Tech Stack

- **Language**: Kotlin 1.9+
- **Architecture**: MVVM + Clean Architecture + Repository Pattern
- **Media Engine**: AndroidX Media3 / ExoPlayer 1.3+
- **UI Framework**: Material Design 3, ViewBinding, ConstraintLayout, Custom ItemDecorations & Views
- **Networking**: Retrofit 2, OkHttp 5, Kotlin Coroutines & Flows
- **Local Persistence**: Room Database, AndroidX DataStore / SharedPreferences
- **Backend / Edge Proxy**: Cloudflare Workers (Node / V8 runtime)

---

## 🚀 Building & Running

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 34+

### Clone & Build
```bash
git clone https://gitlab.com/nd7hdyt/Animarvle.git
cd Animarvle

# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

---

## 📄 License & Attribution

Designed and developed for high-fidelity media streaming. Built with inspiration from AniLab, AniVortex, and the Tachiyomi/Aniyomi open-source ecosystem.
