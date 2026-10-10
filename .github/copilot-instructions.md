# Copilot instructions for `aino-android`

## What this repo is
Native Android client for the AINO workforce platform (attendance, tasks, chat, calls/meetings, calendar, notes, org, manager approvals). Package/applicationId `app.aino.mobile`. It is a port of the private web app `aino-platform` (`client/src/...`), so many comments refer to web parity and to tickets such as `A-1xx`/`MIG-xxxx`. It has one Gradle module (`:app`) with about 265 main and 100 unit-test Kotlin files.

**Stack:** Kotlin 2.2.10, AGP 9.4.1, Gradle 9.6.0 wrapper (Kotlin DSL), Jetpack Compose + Material 3, Navigation-Compose, ViewModel/Coroutines, OkHttp, kotlinx-serialization, Room (KSP, schemas in `app/schemas/`, DB version 2 with AutoMigration), WorkManager, DataStore, Firebase Messaging, WebRTC, CameraX, ML Kit, Media3, Coil 3. minSdk 26, compile/target SDK 35. Bytecode is JVM 17; **build with JDK 21** (CI uses Temurin 21). Dependency versions live in `gradle/libs.versions.toml`.

## Build and validate (all of these were run and pass)
Prerequisites: JDK 21, Android SDK Platform 35, `ANDROID_HOME` or an untracked `local.properties` with `sdk.dir=...`. Use `./gradlew` (or `gradlew.bat` on Windows). No `google-services.json` is needed. Without it, the Firebase plugin is skipped and the build still works. Do not commit `google-services.json`, keystores or `local.properties`.

**Always run the CI command before you finish:**
```
./gradlew --no-daemon testPlayDebugUnitTest lintPlayDebug assemblePlayDebug testDirectDebugUnitTest assembleDirectDebug
```
- Time: about 5 minutes when compile and test are already cached. `lintAnalyzeDebug*` takes most of that. A cold run with dependency downloads takes longer, and the CI job timeout is 20 minutes. Use a long command timeout of 10 minutes or more. Do not cancel early.
- There are two product flavors: `play` (Google Play: Play In-App Updates and Crashlytics, no `REQUEST_INSTALL_PACKAGES`) and `direct` (sideloaded pilots: the R2 APK self-updater). Flavor-only code lives in `app/src/play/` and `app/src/direct/`; everything else is in `main`.
- After a source change, `./gradlew testPlayDebugUnitTest` (incremental compile plus all unit tests) takes about 2 minutes.
- To run one test class: `./gradlew testPlayDebugUnitTest --tests "app.aino.mobile.core.common.LenientNumbersTest"` (about 30 seconds).
- Unit tests use JUnit 4, Robolectric, Turbine, MockWebServer and Compose UI test (`unitTests.isIncludeAndroidResources = true`). Instrumentation tests (`connectedDebugAndroidTest`, `app/src/androidTest/`) need a device or emulator and are **not** run in CI.
- Lint uses AGP defaults. There is no `lint.xml` or baseline, so any lint *error* fails the build. Reports are written to `app/build/reports/lint-results-playDebug.html`.
- There is no ktlint or detekt. Follow the existing style (`kotlin.code.style=official`, 4-space indent, trailing commas).
- If Gradle reports `PKIX path building failed` (corporate TLS), the fix belongs in `~/.gradle/gradle.properties`, **never** in the repo's `gradle.properties`, because it breaks CI on Linux.

**Always also run the Node guardrails (Node 22 in CI; each takes under 1 second, no `npm install` needed):**
```
node scripts/check-independence.mjs
node scripts/check-module-boundaries.mjs
node scripts/check-source-provenance.mjs
node scripts/check-realtime-parity.mjs
node scripts/check-endpoint-parity.mjs
```
What each one enforces:
- **independence**: no tracked file may contain the legacy product or repo name, the placeholder endpoint domain, or the retired Expo R2 channel paths. The exact regexes are in `RULES` in the script. The R2 channel is `android/`. Markdown and docs files are scanned too, so never write these literals anywhere, including in comments and docs.
- **module-boundaries**: `feature/<x>` may import `core.*` but **never** `app.aino.mobile.feature.<other>`. Put shared code in `core/`.
- **source-provenance**: scans tracked and untracked files, including docs, for Signal package, repo or calling-library identifiers and AGPL SPDX markers. The patterns are in the script. Never copy Signal code (see `docs/SOURCE_PROVENANCE.md`). New dependencies need a license review.
- **realtime-parity**: the entries of `enum class RealtimeEvent` in `core/realtime/RealtimeEventRegistry.kt` must exactly equal `contracts/realtime-events.json`. Without a sibling `../aino-platform` checkout, the script validates against the committed snapshot. Do not add listeners for events that are not in the snapshot, and do not remove routed events.
- **endpoint-parity**: scans every `ApiRequest(...)`, `jsonRequest("METHOD","path")`, `mutate("path", ...)` and `// @api METHOD path/:param` marker in `app/src/main/java`. The scan output must match `contracts/android-endpoint-coverage.json` and `docs/PARITY_MATRIX.md`, which are generated files; never hand-edit them. **If you add, remove or change any HTTP call, the check fails as "stale".** Regenerate the snapshots with `node scripts/check-endpoint-parity.mjs . --update` and commit both files. Passing `.` reuses the committed `contracts/http-route-inventory.json`. Plain `--update` without a platform path fails because `../aino-platform` does not exist. For routes built from interpolated variables, add a `// @api POST chat/:id/...` comment above the call so the route is recorded. Prefer routes that exist in the inventory, so that `unmatched` stays 0.

## CI and release
- `.github/workflows/android.yml` runs on every push and PR. It has a `guardrails` job (the 5 Node scripts) and a `build` job (JDK 21 plus the Gradle command above).
- `.github/workflows/android-release.yml` runs only on `android-v*` tags. It requires `versionName = "X.Y.Z"` and `versionCode = X*1000000 + Y*1000 + Z` in `app/build.gradle.kts`, written without digit separators, for example `versionCode = 16000 // 0.16.0`. Do not bump the version unless asked. If you do bump it, update both values together.
- BuildConfig endpoints (`AINO_API_URL`, `AINO_WS_URL`, `AINO_OTA_BASE_URL`, `AINO_CONTRACT_VERSION`) default to live servers. You can override them with `-P` or an environment variable. Release signing comes only from `ANDROID_KEYSTORE_*` / `ANDROID_KEY_*` environment variables.

## Layout (`app/src/main/java/app/aino/mobile/`)
- `MainActivity.kt` is the entry point. `ComposeTestHostActivity.kt` is a test host.
- `core/AppContainer.kt` holds the process-wide singletons (manual DI, no Hilt). It provides `api` (caching plus token-refreshing `ApiClient`), `rawApi`, `cachedApi`, `http`/`mediaHttp` (OkHttp), `imageLoader` and the stores. **Use the single `api` instance**, because a separate instance can race token refresh.
- `core/network/` contains `ApiModels.kt` (`ApiClient`, `ApiRequest(method, path, body)`, `ApiError.Http`), `OkHttpApiClient`, `RefreshingApiClient`, `ResponseCache` and `NetworkConfig`. Paths are relative to `.../api`, for example `"notifications/$id/read"`.
- `core/auth`: login, Keystore tokens, biometric credentials. `core/db`: Room `AinoDatabase`, DAO, tenant+user scoped cache, outbox worker. If you change entities, bump the version, add a migration and commit the new schema JSON in `app/schemas/`.
- Other `core/` packages: `realtime` (WebSocket client, dispatcher, event registry), `navigation` (`AinoApp.kt` NavHost, `AinoDestination` enum, `MoreSheet`, notification deep links), `designsystem` (theme, web tokens, `HeroIcons`, shared components), `call` and `call/webrtc` (1:1 calls), `push` (FCM), `notifications`, `media`, `update` (`AppUpdater` contract and banner; implementations are per flavor), `branding`, `common`.
- `feature/<name>/` holds one package per product area: attendance, auth, calendar, chat, debug, home, manager, meeting, notes, notifications, organization, profile, search, tasks, and others. Each package usually follows the pattern `XModels.kt` (`@Serializable` DTOs, `Json { ignoreUnknownKeys = true }`), `XRepository.kt` (takes `ApiClient` and makes blocking calls, which ViewModels wrap in `withContext(Dispatchers.IO)`), `XViewModel.kt` and `XScreen.kt` (Compose).
- Tests in `app/src/test/java/app/aino/mobile/...` mirror the main package structure. Add tests for repositories and logic next to similar existing tests.
- Other paths: `docs/` (parity plans, contracts, provenance policy), `contracts/` (platform API and realtime snapshots), `scripts/` (guardrails, plus Python icon generators), `app/proguard-rules.pro` (R8 rules for the release build; keep `@Serializable` models intact).

## Conventions
- UI should mirror the web design tokens in `core/designsystem/tokens`. Dynamic color is intentionally disabled.
- Local data is always scoped by both tenant **and** user ID.
- Commit style: `feat(android): ...` / `fix(android): ...`.

Trust these instructions. Search the codebase only if something here is incomplete or turns out to be wrong.
