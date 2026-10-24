# Source provenance policy

AINO Android is independently implemented against Android SDK, WebRTC, and the
published AINO platform contracts. External applications may be studied to define
expected behavior, but their source is not automatically a permissible migration
source.

## Signal Android

`signalapp/Signal-Android` is licensed `AGPL-3.0-only`. It may be consulted only
as an architectural and behavioral reference for Android call lifecycle, audio
routing, foreground services, permissions, Picture-in-Picture, and cleanup.

Do not copy or closely adapt Signal implementation code, tests, constants,
comments, resources, UI assets, RingRTC integration, package names, or protocol
assumptions without a separately recorded compatible-license decision. AINO call
code must use AINO names and contracts and be independently designed.

Every migration checkpoint that consults external source records the repository,
paths inspected, date, behaviors derived, and how the AINO implementation differs.

### Consultation log

**2026-10-16: outgoing text and emoji presentation during sending**

- Repository: `signalapp/Signal-Android`, inspected main revision
  `db06ef04799796836b773164b49e018f5ec87dc9` (`AGPL-3.0-only`).
- Behavioral references inspected:
  `conversation/v2/items/V2ConversationItemShape.kt` and
  `conversation/v2/items/V2ConversationItemTextOnlyViewHolder.kt`.
- Behaviors derived: adjacent messages determine bubble geometry when binding
  the row; eligible emoji-only text receives a transparent background during
  that binding, rather than waiting for delivery acknowledgement.
- How AINO differs: independent changes to the existing Compose queued-text
  renderer and chronological thread model reuse AINO's existing shape, emoji
  and organisation-colour helpers. Pending text participates in existing
  grouping, and reply context prevents quoted emoji from losing its bubble.
  Upload boundaries, transport and animation settings remain unchanged. No
  external source, tests, constants, comments or resources were copied.
- Source inspection establishes binding behavior only; the external app was
  not run. AINO's own model, send-order and native-rendered Compose regressions
  verify queued presentation and its transition to confirmed text.

**2026-10-13: chat search and back navigation**

- Repository: `signalapp/Signal-Android`, commit
  `92f5ccd3a52c4780a0c916566e26280f640ba1de` (`AGPL-3.0-only`).
- Behavioral references inspected: `ConversationListFragment.java`,
  `MainToolbarViewModel.kt`, `MainToolbar.kt`, `MainActivity.kt`,
  `MainNavigator.java`, `ListDetailNavDisplay.kt`,
  `conversation/v2/ConversationFragment.kt` and
  `conversation/ConversationSearchViewModel.java`.
- Behaviors derived: opening list-search results preserves search; returning
  from the conversation reveals the same search session; explicitly closing
  search clears the toolbar query and restores the normal list. Clearing only
  the field leaves search open. In-conversation search closes before back leaves
  the conversation, and match/quote selections scroll within the current thread.
- How AINO differs: independently written Compose state and coroutine cleanup
  in the existing `ChatUiState`/`ChatViewModel`, using AINO people/message APIs
  and local cached messages. No Signal source, tests, constants, resources or
  navigation implementation were copied. AINO cancels and guards asynchronous
  search publication and keeps list search separate from thread search.
- Source inspection establishes the search state transitions, not a runtime
  guarantee about Signal's Navigation3 callback order or retained-entry disposal.
  AINO's own navigation tests verify its back-stack behavior.

**2026-10-08: group settings, group links, chat-list receipts, group calls (0.18.x)**

- Repository: `signalapp/Signal-Android`. We used the public app's screens and
  documented behavior only. No source files, resources, strings, drawables,
  colors or dimension values were copied, and none are vendored.
- Behaviors derived:
  - Group settings layout: large avatar and title, quick-action row, members
    list with "Add members", tapping a member opens a sheet with "Make group
    admin" / "Remove as admin" / "Remove from group".
  - Group-link page: on/off switch, share, copy, reset, "Approve new members".
  - Requests & invites, and the Permissions page ("Add members", "Send
    messages", "Edit group info").
  - Join-by-link preview sheet.
  - A delivery tick before the last outgoing message in the chat list.
  - Group-call lobby with a ring toggle; in-call grid and speaker view with a
    floating self view, participants sheet, raise hand and reactions.
- How AINO differs:
  - All code is newly written Compose against AINO contracts (`/api/chat/...`
    group, invite-link, join-request, avatar and active-call routes; the
    `meeting_*` mesh, not RingRTC).
  - Permissions mirror AINO's server rules (owner/admin/member plus
    `post_policy`/`add_policy`), not Signal's group model.
  - Group links work only inside the user's own tenant, with no cryptographic
    group credentials.
  - The automatic group avatar is AINO's own design: a member-photo collage on
    an AINO palette.
  - Icons come from HeroIcons and Material Symbols (Apache-2.0).

**2026-10-10: chat connection stability (realtime lifecycle, offline notices)**

- Repository: `signalapp/Signal-Android`. We read the app-level message
  retrieval observer (`IncomingMessageObserver.kt`) for its behavior only.
  Nothing was copied: no code, constants, names, strings or comments.
- Behaviors derived:
  - One "connection needed" decision combines being in the foreground (or
    within a short grace period after going to the background), a usable
    network, and whether push can wake the app. The socket opens and closes
    only from that decision.
  - Connectivity monitoring starts by assuming the device is online. A network
    change resets the network layer (pooled connections and the socket).
  - Screens keep showing stored data. A failed fetch is not reported to the
    user. Pending sends stay queued until they succeed.
- How AINO differs:
  - `RealtimeConnectionPolicy`, `ConnectivityMonitor` / `NetworkTracker` and
    the `ConnectionStatusStrip` are newly written against AINO's realtime
    contract (`/ws` envelopes, app-level ping) and its OkHttp stack. The
    grace period, retry delays and timeouts are AINO's own values.
  - An open call, meeting or ringing call keeps the socket open, because AINO
    call signaling uses it.
  - A text sent over the socket counts as delivered only when the server echoes
    its `clientMsgId`. Otherwise it is sent again over REST, where the server
    dedupes by `clientMsgId`.

**2026-10-11: call confirmation, independent cameras, proximity and notification reliability**

- Repository: `signalapp/Signal-Android`, commit
  `92f5ccd3a52c4780a0c916566e26280f640ba1de` (`AGPL-3.0-only`).
- Call references consulted by the implementation coordinator:
  `CommunicationActions.java`, `WebRtcCallActivity.kt`, `ProximityLock.java`,
  `LockManager.java`, and `ActiveCallManager.kt`.
- Notification references: the existing behavioral references
  `app/src/main/java/org/thoughtcrime/securesms/notifications/v2/DefaultMessageNotifier.kt`
  and `v2/NotificationFactory.kt`; notification directory entries and
  `app/src/main/java/org/thoughtcrime/securesms/notifications/MarkReadReceiver.java`
  and `DeleteNotificationReceiver.java` were checked at the same commit.
- Behaviors derived:
  - Outgoing voice calls have a confirmation step in Signal. AINO deliberately
    confirms **both voice and video**, as explicitly requested; this is not a
    claim that Signal confirms its direct video path.
  - Local camera permission and capture state are independently controlled:
    enabling one participant's camera does not enable the peer's camera.
  - Android SDK proximity behavior distinguishes handset/audio-only state from
    hands-free routes and video presentation. End-of-call, route changes and
    lifecycle disposal release device resources.
  - Visible conversations do not produce tray alerts; dismissing, marking read
    and replying are different notification actions. Notification delivery must
    not be mistaken for the original message's read state.
- How AINO differs:
  - AINO's own Compose confirmation, WebRTC negotiation/media ownership, Android
    audio routing, SDK wake locks, FCM data payloads, scoped caches and notification
    presentation ledger are independently implemented. No RingRTC assumptions,
    Signal protocol, source code, tests, constants, resources or assets are copied.
  - Socket and FCM share AINO tenant/user event identities and explicit
    posted/suppressed/blocked/failed outcomes. Reactions use AINO's persisted
    addition IDs; missed-call recovery uses AINO server call-history/status APIs.
  - The implementation does not claim physical proximity or live notification
    delivery was verified: no Android device was attached during this work.

**2026-10-11: message notification contact photos (post-0.21.9 regression fix)**

- Repository: `signalapp/Signal-Android`, commit
  `92f5ccd3a52c4780a0c916566e26280f640ba1de`.
- Inspected `notifications/v2/NotificationConversation.kt` and
  `notifications/v2/NotificationFactory.kt` for behavior: notifications use the
  conversation contact image when contact display is permitted, with a fallback
  identity when a photo is unavailable; conversation identity differs from each
  group's message sender identity.
- AINO independently restores photos to Android large icons, sender `Person`
  icons and conversation shortcuts using its installed Coil loader and shared
  authenticated media client. Cached photos or initials display immediately;
  asynchronous photo completion silently enriches only the still-active,
  same-account notification. This is AINO's nonblocking delivery strategy, not
  a claim that Signal uses the same asynchronous implementation.
- No external code, resource, palette, dimension, test, or protocol was copied.
  AINO's existing notification privacy setting remains `VISIBILITY_PRIVATE`;
  this change adds no new notification privacy setting or dependency.

## Runtime dependencies

Dependencies are declared in `gradle/libs.versions.toml` and resolved from Google
Maven or Maven Central. New runtime dependencies require review of license,
maintenance status, necessity, and transitive impact. Required upstream notices
must remain in packaged artifacts; do not add blanket packaging exclusions that
discard dependency notices.

Reviewed additions:

- `androidx.compose.material:material-icons-extended` (2026-10-08). Google,
  Apache-2.0, actively maintained.
  - Version: taken from the Compose BOM, so it adds no transitive dependencies
    beyond Compose itself.
  - Size: R8 strips unused icons from release builds.
  - Purpose: Material icons for chat-list media previews, group settings and
    the group-call controls.

## Repository license

The repository does not currently declare a root license. Until the owner records
a distribution-license decision, treat the source as all-rights-reserved project
material: do not add third-party copied code and do not infer permission to
redistribute source merely because GitHub hosts the repository. This policy does
not choose a license on the owner's behalf.

## WebRTC runtime artifact (provenance gap)

`app/build.gradle.kts` depends on `io.github.webrtc-sdk:android:150.7871.01`
(declared in `gradle/libs.versions.toml`), **not** the upstream
`org.webrtc:google-webrtc` artifact. The code consumes the standard
`org.webrtc.*` API, so the swap is source-compatible, but the artifact is a
third-party repackaging of a WebRTC build.

Consequences to be aware of:

- Codec availability, hardware-encoder selection, audio-processing behavior and
  JNI surface can differ from an upstream build, which makes device-specific
  quality differences harder to attribute.
- The build's exact upstream revision and patch set are not recorded here.
- License/notice obligations for the repackaged binary have not been reviewed.

This is a tracked risk, not a known defect. Before treating any
device-specific call-quality report as an AINO bug, confirm the behavior
reproduces against an upstream `org.webrtc` build.