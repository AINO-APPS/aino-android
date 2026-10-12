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

## Calling behavior

- Direct voice and video calls ask for **Call / Cancel** before permissions, camera
  capture, or signaling. Group calls retain their existing Start / Join lobby.
- Each participant can enable or disable their own camera during a voice or video
  call. Camera permission and foreground-service promotion happen before capture;
  camera failures leave audio running and appear in the call controls.
- The live media state drives the call layout and picture-in-picture, not the
  invitation's original call type. Turning both cameras off returns the audio UI.
- Video activation moves the earpiece route to speaker without replacing an active
  headset. Turning video off does not automatically change the chosen audio route.
- Connected audio-only calls use Android's proximity screen-off wake lock only on
  the earpiece. Speaker, headsets, ringing, video, and teardown release it. Devices
  without a supported proximity wake lock retain normal screen behavior.

Automated tests cover confirmation/cancellation and proximity eligibility/lifecycle.
Camera negotiation, physical ear-proximity behavior, headset routing, and
cross-platform picture-in-picture still require a real-device smoke test.

## Local development

1. Install JDK 21 and Android SDK Platform 35.
2. Set `ANDROID_HOME` or create an untracked `local.properties` with `sdk.dir=...`.
3. Run:

```shell
./gradlew testDebugUnitTest lintDebug assembleDebug
```

CI also runs repository independence, feature dependency-direction, and source
provenance guards. Feature modules may depend on `core`, but never directly on a
sibling feature. See [`docs/SOURCE_PROVENANCE.md`](docs/SOURCE_PROVENANCE.md)
before consulting or importing material from another application. In particular,
Signal Android is an AGPL-3.0-only behavioral reference, not a source migration
dependency.

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

## Android notification delivery

Production APK/AAB publication requires the `ANDROID_GOOGLE_SERVICES_JSON` CI
secret for `app.aino.mobile`. Missing, malformed or mismatched configuration fails
the release workflow; local debug builds remain usable without Firebase. The
Firebase project must match the backend sender's service account. Do not paste
configuration, tokens or service-account credentials into logs or source.

Notification settings show account token-registration state, app permission and
disabled message, reaction, missed-call and conversation channels. Retry registers
the current token; sign-in, foreground and network reconnect also retry. The
background socket is paused only after successful account-scoped FCM registration,
available Play services and enabled notifications. Settings never override user choices.

Socket messages post locally without waiting for FCM or avatar downloads. Socket
and FCM share tenant/user-scoped event identities and separate receipt/presentation
state. Blocked or failed posting is retryable; visible, muted, self and already-read
messages are suppressed. Inline Reply and Mark as read remain available.

Message notifications use chat's first-two-word initials, organization primary
colour/white text, and aspect-preserving centre-cropped photos. Groups use their
uploaded photo, otherwise chat's photo-first member collage/layout/palette, otherwise
the same group glyph. Each message retains its separate sender `Person` icon.
Large icons and conversation shortcuts use ordinary bitmap icons for already-cropped
avatars, avoiding an extra adaptive-icon safe-zone crop. Memory-cached photos render
immediately; otherwise matching initials/collage/glyph appear while the shared
authenticated Coil loader checks disk cache or fetches the image. Successful loading silently updates the existing
notification without a second sound, duplicate message, changed timestamp, or lost
Reply / Mark as read actions. Failed loads keep the matching fallback and are logged
without URLs, credentials or message content. Dismissal, read,
visible-thread suppression, sign-out and account changes prevent late photo loads
from restoring old notifications. The tiny status-bar icon remains monochrome as
required by Android. OS/OEM notification templates still control banner/shade geometry;
avatar content and crop match chat, not every pixel of system chrome.

Android advertises `pushVersion: 4`, retaining general-alert links and reactions.
Only Android v4+ receives optional `groupAvatar` and stringified `groupMemberPreviews`
on group message pushes. Previews contain at most four `{name, avatar}` members
(strings or null), in conversation-list order. Each metadata value is bounded to
2048 UTF-8 bytes and the full JSON data record to 3800 bytes, below FCM's 4096-byte
limit. The backend omits over-budget URLs/collages intact, never truncates URLs,
and logs omissions. Android resolves missing metadata asynchronously from the
account-scoped conversation response cache or authenticated conversation list.
Legacy message maps remain accepted; Android v1–3, iOS and web keep legacy keys.
Calls, missed calls and reactions retain their previous fallback rendering.

`chat_reaction` data-only pushes carry
conversation/message/owner/actor IDs, actor name/avatar, emoji, addition
`reactionId`, `action: added`, `dedupeKey: reaction:<id>`, tenant ID, ISO-8601
`sentAt` and epoch-second `expiresAt`. Realtime addition timestamps are numeric
epoch seconds. Only another person's addition to your message alerts; self
reactions, removals, muted conversations and the visible thread do not. Reactions
have their own channel and do not change message unread counts or disappear just
because the original message was read. Opening the conversation handles them.

Missed-call handled and successfully posted IDs persist separately per account.
An expired saved ring is reconciled with server call status before notification;
offline failure retains it for retry. The first successful call-history load
establishes a baseline to avoid flooding old history. Subsequent new incoming
`missed` rows catch up after reconnect, including posting retries. The current
history API has no unread/read field, so explicitly handled local calls and server
answered/declined status are used, not invented unread state. Call back opens the
existing confirmation flow.

Device verification for this change is **not performed** (no attached device).
Compare the same photo, three-word name, uploaded group photo and 2/3/4-member
collage in chat, heads-up and expanded shade on Android 11+ and an OEM handset.
Check Android 13+ permission and channel disable/re-enable; message/reaction
foreground-other-screen, visible-thread suppression, background/swipe-away and
offline restart; socket-first/FCM-first duplicates; tap, reply, read and missed-call
catch-up. Record status-bar icon, shade entry, heads-up, sound and launcher badge
separately. Local Firebase configuration is absent, so installed-release packaging,
live registration/dispatch and server project agreement remain unverified.

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

## Chat camera and polls

The inline camera and attachment-tray camera open the same capture screen. Opening
it hides the keyboard and removes composer focus without discarding the typed
message. Closing it returns to the draft; the keyboard stays closed until tapped.

- **Photo:** tap the shutter for a photo; hold for a quick video and release to finish.
- **Video:** tap to start recording and tap the stop control to finish.
- **Controls:** gallery/recent thumbnail, camera flip, flash and pinch zoom. The
  1×/2×/5× presets only appear when supported by the active camera's zoom range;
  they do not imply separate optical lenses. Flash/Video/flip adapt to hardware.
- Videos retain the existing 60-second / 25 MiB limits. The first recording asks
  for microphone permission; when unavailable, a subsequent recording is clearly
  marked as silent. Closing/backgrounding the camera cancels an unfinished capture.
- Captures still go through the existing media review/edit/send screen.

Group polls use the existing API and voting rules: single-choice replaces the
previous choice, multiple-choice allows several choices, and tapping a selected
option removes that vote. Closed polls are read-only. **View votes** shows voters
by option, including empty options, and refreshes with the live poll. Result bars
are shares of all selections; voter counts are distinct people, not selections.
Voter photos use the shared authenticated image loader; missing, loading, or failed
photos show real-name initials, including for rows labeled **You**. Older poll
responses without a voter photo remain supported.
Creation retains the 2–10 option, 500-character question and 200-character option
limits, with multiple choice off by default.

## Updates

There are two product flavors, built from one codebase:

| Flavor | Release asset | Updates |
|---|---|---|
| `direct` | `AINO-X.Y.Z-direct.apk` | Self-updater: reads the R2 channel, downloads the signed APK and hands it to the Android installer |
| `play` | `AINO-X.Y.Z-play.aab` | Google Play (In-App Updates API, flexible flow). No `REQUEST_INSTALL_PACKAGES` |

The `direct` channel:

- manifest: `https://cdn.aino.org.in/android/latest.json`
- immutable APKs: `https://cdn.aino.org.in/android/releases/android-vX.Y.Z/AINO-X.Y.Z-direct.apk`
  (releases up to 0.21.5 are named `AINO-X.Y.Z.apk`)

Both flavors check silently on launch/resume and show a banner when an update
exists. **Profile → Check for updates** checks on demand and also reports
"up to date" or a failure. APK URLs must be HTTPS, match the configured CDN host,
and use the native `android/releases/` prefix. Android may require the user to
grant AINO permission to install unknown apps. An update installs in place (data
kept) only when it has the same package, the same `CN=AINO` signing key and a
higher `versionCode`; debug builds cannot be updated by release APKs.

`0.2.1` is a call-lifecycle reliability checkpoint. It adds process-scoped call
identity/state ownership, typed deployed realtime-event routing, duplicate signal
quarantine, and exactly-once non-media cleanup. It deliberately does **not** enable
microphone, camera, WebRTC media, or PiP: accepted calls remain at the explicit
waiting-for-media guard until A-082 physical interoperability is complete.

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
