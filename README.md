# Aino Android

Native Android foundation for **MIG-0103 / MIG-0601 / MIG-0602**.

## Stack

- Kotlin and Gradle Kotlin DSL
- Jetpack Compose with Material 3
- Navigation-Compose, Lifecycle/ViewModel and Kotlin Coroutines
- OkHttp and kotlinx-serialization for typed platform API access
- Package and application ID: `app.aino.mobile`
- Minimum SDK 26, target/compile SDK 35
- JDK 17 bytecode target; Gradle and CI run on JDK 21

## Structure

```text
app/src/main/java/app/aino/mobile/
├── core/
│   ├── auth/                 # Auth state, repository and Keystore credentials
│   ├── db/                   # Tenant/user-scoped Room cache and durable outbox
│   ├── designsystem/theme/   # Compose theme
│   ├── navigation/           # Platform-aligned app shell and destinations
│   └── network/              # OkHttp transport, endpoint and header policy
└── feature/
    ├── auth/                 # Login, realm choice and forced password change
    └── home/                 # Dashboard foundation
```

Add future product areas under `feature/<name>` and shared platform code under `core/<area>`.
The shell follows `aino-platform/client/src/App.tsx`: Home, Attendance, Tasks,
Chat and More are primary destinations; Calendar, Notes, Organization, Profile,
Manager, Admin and Tenants live under role-gated secondary navigation.

Native bearer authentication currently targets the tenant realm. The platform
administration realm remains isolated behind its web console hostname; if the API
returns a platform handoff, Android fails closed and directs the operator to the
web console rather than weakening the realm boundary.

Biometric sign-in is optional and tenant-only. Enrollment mints a revocable,
high-entropy server credential and encrypts it with an authentication-bound Android
Keystore key. Every decrypt requires `BIOMETRIC_STRONG`; weak-only biometric devices
continue to use password login rather than storing a credential behind a weaker
app-level check. No face, fingerprint, or biometric template leaves the device.

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
| `AINO_WS_URL` | `wss://www.aino.org.in` |
| `AINO_OTA_BASE_URL` | `https://cdn.aino.org.in` |
| `AINO_CONTRACT_VERSION` | `0.1.0` |

Override any of them with a Gradle property or an environment variable of the same name (the Gradle property wins):

```shell
./gradlew assembleDebug -PAINO_API_URL=https://example.test/api -PAINO_WS_URL=wss://example.test
```

`AINO_CONTRACT_VERSION` should only be overridden when deliberately testing another compatible contract release.

Realtime uses `wss://www.aino.org.in/ws`, not the direct Railway web origin. The
Cloudflare edge routes `/ws` to the separate realtime service. Android maintains
one tenant-authenticated socket with a 25-second heartbeat, a 10-second liveness
timeout, exponential full-jitter reconnects capped at 15 seconds, and terminal
handling for authentication (`4001`), unavailable tenant (`4003`), and connection
limit (`4029`) closes.

Local data is scoped by both tenant and user. Room primary keys and every DAO
query require both identifiers; logout and terminal session invalidation cancel
that scope's outbox worker and transactionally remove only its conversations,
messages and outbox rows. The last scope identifiers are kept in DataStore so a
process restarted with an expired token can still wipe the correct cache. No
token or message content is stored in DataStore.

The Home screen is backed by the live platform dashboard APIs. It shows the
authenticated user, attendance state, work mode, live work/break duration,
target progress, and task summary, and refreshes after relevant realtime events.
Clock-in/out controls are intentionally deferred until the attendance feature can
satisfy each tenant's face, location, accuracy and office Wi-Fi policy.

## Visual system

The Compose UI mirrors the existing `aino-platform` design tokens and patterns:

- AINO blue `#2383E2`, light blue `#529CCA`, and cyan `#38BDF8` accents;
- dark-first `#131314`, `#1B1B1C`, and `#202021` surfaces;
- subtle translucent borders and elevated glass-style cards;
- blue gradient primary controls and semantic green/amber/red status surfaces;
- compact metric rows, bold headings, real vector navigation icons, and the
  existing AINO product icon.

Material dynamic color is intentionally disabled so device wallpaper colors do
not replace the AINO product identity. Light mode uses the platform's warm neutral
palette and preserves the same blue accent hierarchy.

## Updates

Native releases use an isolated R2 channel:

- manifest: `https://cdn.aino.org.in/android/latest.json`
- immutable APKs: `https://cdn.aino.org.in/android/releases/android-vX.Y.Z/AINO-X.Y.Z.apk`

Authenticated users can check and install updates from **More**. APK URLs must be
HTTPS, match the configured CDN host, and use the native `android/releases/`
prefix. Android may require the user to grant AINO permission to install unknown
apps. This direct installer is for sideload distribution and must be disabled if
the app is later distributed through Google Play.

`0.1.1` is the first build containing this updater; install it manually from R2
or GitHub once. Every later native release can then be discovered in-app.

### Corporate TLS interception

If Gradle fails with `PKIX path building failed`, your network is terminating TLS with a private root CA that Windows trusts but the JDK's bundled `cacerts` does not. Add this to **`%USERPROFILE%\.gradle\gradle.properties`**:

```properties
systemProp.javax.net.ssl.trustStoreType=Windows-ROOT
```

Certificates are still fully validated — this only changes which trust store is consulted.

Do **not** put it in the repository's `gradle.properties`: that file is also read by the wrapper before the JVM starts, and `Windows-ROOT` does not exist on Linux or macOS, so it breaks CI with `problem accessing trust store` before Gradle is even downloaded.

On Windows, use `gradlew.bat`. Instrumentation tests require an API 26+ emulator or device:

```shell
./gradlew connectedDebugAndroidTest
```

## CI

`.github/workflows/android.yml` runs unit tests, Android lint, and a debug build on pushes and pull requests.
