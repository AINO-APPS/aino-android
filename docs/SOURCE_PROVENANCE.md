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