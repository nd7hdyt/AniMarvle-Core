# VERSIONS.md — pinned dependency versions and their sources

All versions below were fetched from primary sources on **2026-10-10** (this session). No version was
pinned from memory. Re-verify before changing anything.

## Toolchain

| Component | Version | Source (fetched 2026-10-10) | Status |
|---|---|---|---|
| Android Gradle Plugin | 9.4.1 | `https://dl.google.com/android/maven2/com/android/tools/build/gradle/maven-metadata.xml` — latest stable entries listed `9.4.1` (9.5.0-* are alpha) | verified |
| Gradle | 9.8.0 | Wrapper `distributionUrl` = `https://services.gradle.org/distributions/gradle-9.8.0-bin.zip`; AGP 9.4 docs require **minimum Gradle 9.6.0** (see below) | verified |
| AGP 9.4 compatibility | max API level **37**, min Gradle 9.6.0, SDK Build-Tools 36.0.0, JDK 17 | `https://developer.android.com/build/releases/gradle-plugin` (page: "Android Gradle plugin 9.4.0 (September 2026)", quoted Compatibility section) | verified |
| JDK | 17.0.20.1 | `java -version` on this machine | verified |
| compileSdk / targetSdk | 37 | Installed SDK platform `android-37.0` (also `android-36`); ≤ AGP 9.4 max (37). The pre-existing `Animarvle-Android` project uses `compileSdk = 37 / targetSdk = 37` and builds in this environment | verified |
| minSdk | 26 | Owner decision (PROMPT §9 Q1 answered 2026-10-10) | decision |
| SDK Build-Tools | 36.0.0 installed (35.0.0 also present) | `%LOCALAPPDATA%\Android\Sdk\build-tools` listing | verified |
| Kotlin | Applied through AGP 9.4.1's built-in Kotlin support — **no** `org.jetbrains.kotlin.android` plugin in the build (mirrors `Animarvle-Android\app\build.gradle.kts`, which compiles `.kt` this way and passed `compileDebugKotlin` this session) | local primary source: `Animarvle-Android\build.gradle.kts`, `Animarvle-Android\app\build.gradle.kts` + successful build log | verified behavior / exact Kotlin version bundled with AGP = **unknown** (report after first build output) |

## Android libraries (Google Maven `maven-metadata.xml`, fetched 2026-10-10)

| Artifact | Version | Notes |
|---|---|---|
| `androidx.core:core-ktx` | 1.19.1 | `<latest>` and `<release>` both 1.19.1 |
| `androidx.appcompat:appcompat` | 1.8.0 | `<latest>` = `<release>` = 1.8.0 |
| `com.google.android.material:material` | 1.14.0 | provides `Theme.Material3.*` |
| `androidx.fragment:fragment-ktx` | 1.9.1 | `<latest>` = `<release>` = 1.9.1 |
| `androidx.activity:activity-ktx` | *not pinned* | `<release>` is `1.14.0-alpha03` — alpha only in metadata; not used in M0 |
| `androidx.navigation:navigation-fragment-ktx` | 2.10.2 | resolved by `libs.versions.toml`, compiles `compileDebugKotlin` OK (build-m2d.log) |
| `androidx.navigation:navigation-ui-ktx` | 2.10.2 | same |
| `androidx.recyclerview:recyclerview` | 1.4.0 | used by rails + the 3-column recommendations grid |
| `androidx.swiperefreshlayout:swiperefreshlayout` | 1.2.0 | Home pull-to-refresh |
| `androidx.lifecycle:lifecycle-runtime-ktx`, `lifecycle-viewmodel-ktx` | 2.11.0 | `repeatOnLifecycle` + `ViewModelProvider` |
| `com.squareup.okhttp3:okhttp` | 5.5.0 | AniList GraphQL transport |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.11.0 | plugin `org.jetbrains.kotlin.plugin.serialization` 2.4.21 |
| `io.coil-kt:coil` | 2.7.0 | poster/banner image loading |

Later milestones (Media3, Room, DataStore, WorkManager) will be added here with their fetched sources
**before** being pinned (PROMPT §4.6).

## AniList GraphQL API (verified against the live endpoint 2026-10-11)

Endpoint: `https://graphql.anilist.co` (POST, `Content-Type: application/json`). No API key needed for
public metadata.

| Fact | Value | How verified |
|---|---|---|
| `averageScore` scale | **0–100**, not 0–10 | Live query: Death Note = 84, Code Geass = 85. Mapper divides by 10 (`AniListMapper.toMediaUi`) |
| `description(asHtml: false)` | Still contains `<br>` / `<i>` tags | Live response for Death Note contained raw tags; mapper strips them (`AniListMapper.clean`) |
| `AiringSort` | `TIME_DESC` is valid; **`AIRING_AT_DESC` does not exist** | Schema introspection + live `Page { airingSchedules }` call |
| Variable definitions | **Must be parenthesised**: `query ($id: Int) { ... }` | Unparenthesised form returned `Syntax Error: Expected {, found $` |
| `recommendations(sort: RATING_DESC, perPage: N)` | Returns `nodes[].mediaRecommendation` | Live query on Death Note (id 1535) returned 6 related titles |
| `relations` | `edges[].relationType` + `node` | Live query returned `ADAPTATION`/`SUMMARY`/`CHARACTER` edges |
| `nextAiringEpisode` | `episode`, `airingAt`, `timeUntilAiring` | Rendered on-device as the countdown chip (ONE PIECE: "Ep 1181 · 84d 5h (3 Jan)") |

### Queries shipped in `data/anilist/AniListApi.kt`
`trending`, `topAiring`, `popularMovies`, `search`, `details` (+ nested `recommendations`/`relations`),
`schedule` — all exercised against the live API; see `docs/device-shots/m2d-*.png` for the rendered result.

## M3 additions (verified 2026-10-11)

| Library | Version | Source | Notes |
|---|---|---|---|
| androidx.media3 (exoplayer, exoplayer-hls, ui, session, datasource-okhttp) | **1.11.1** | Google Maven (latest stable) | Player core for PROMPT §7.7 |
| kotlinx-coroutines-core | **1.11.0** | Maven Central | Channel/Flow; drives the resolver pipeline |
| junit | **4.13.2** | Maven Central | `:domain` resolver acceptance tests |
| com.google.android.gms:play-services-auth | **21.3.0** | Google Maven | Native Google Play Services OAuth 2.0 Client |

Module layout note: `:source-api`, `:domain` and `:source-sample` are registered with the
`android-library` plugin (see `settings.gradle.kts`), reusing AGP 9.4.1's built-in Kotlin support
instead of applying `org.jetbrains.kotlin.android`.

Acceptance test result: `domain/build/test-results/testDebugUnitTest/TEST-com.animarvle.domain.ResolverAcceptanceTest.xml`
-> `tests="12" failures="0" errors="0"`.
