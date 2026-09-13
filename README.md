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

API and WebSocket endpoints are compiled into `BuildConfig`. The defaults target the live platform, so a plain debug build is already functional:

| Field | Default |
|---|---|
| `AINO_API_URL` | `https://next.aino.org.in/api` |
| `AINO_WS_URL` | `wss://next.aino.org.in` |
| `AINO_CONTRACT_VERSION` | `0.1.0` |

Override any of them with a Gradle property or an environment variable of the same name (the Gradle property wins):

```shell
./gradlew assembleDebug -PAINO_API_URL=https://example.test/api -PAINO_WS_URL=wss://example.test
```

`AINO_CONTRACT_VERSION` should only be overridden when deliberately testing another compatible contract release.

### Corporate TLS interception

`gradle.properties` sets `systemProp.javax.net.ssl.trustStoreType=Windows-ROOT`. Some networks terminate TLS with a private root CA that Windows trusts but the JDK's bundled `cacerts` does not, which otherwise fails every download with `PKIX path building failed`. Certificates are still fully validated. The setting is inert on Linux and macOS, so CI is unaffected.

On Windows, use `gradlew.bat`. Instrumentation tests require an API 26+ emulator or device:

```shell
./gradlew connectedDebugAndroidTest
```

## CI

`.github/workflows/android.yml` runs unit tests, Android lint, and a debug build on pushes and pull requests.
