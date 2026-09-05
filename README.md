# Aino Android

Native Android foundation for **MIG-0103 / MIG-0601 / MIG-0602**.

## Stack

- Kotlin and Gradle Kotlin DSL
- Jetpack Compose with Material 3
- AndroidX only; no third-party runtime libraries
- Package and application ID: `app.aino.mobile`
- Minimum SDK 26, target/compile SDK 35
- JDK 17 bytecode target; Gradle and CI run on JDK 21

## Structure

```text
app/src/main/java/app/aino/mobile/
├── core/
│   ├── designsystem/theme/   # Compose theme
│   └── navigation/           # App shell and destinations
└── feature/
    └── home/                 # Feature-oriented UI
```

Add future product areas under `feature/<name>` and shared platform code under `core/<area>`.
The shell deliberately uses Compose state rather than a navigation library until navigation requirements are confirmed.

## Local development

1. Install JDK 21 and Android SDK Platform 35.
2. Set `ANDROID_HOME` or create an untracked `local.properties` with `sdk.dir=...`.
3. Run:

```shell
./gradlew testDebugUnitTest lintDebug assembleDebug
```

API and WebSocket endpoints are compiled into `BuildConfig`. The checked-in defaults use the non-routable `example.invalid` domain and must be overridden with Gradle properties or environment variables at build time:

```shell
./gradlew assembleDebug -PWORKPULSE_API_URL=https://example.test/api -PWORKPULSE_WS_URL=wss://example.test/ws
```

Environment variables with the same names are also supported. Gradle properties take precedence. The app pins contract baseline `0.1.0`; `AINO_CONTRACT_VERSION` can be overridden only when deliberately testing another compatible contract release.

On Windows, use `gradlew.bat`. Instrumentation tests require an API 26+ emulator or device:

```shell
./gradlew connectedDebugAndroidTest
```

## CI

`.github/workflows/android.yml` runs unit tests, Android lint, and a debug build on pushes and pull requests.
