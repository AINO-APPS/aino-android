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

## Runtime dependencies

Dependencies are declared in `gradle/libs.versions.toml` and resolved from Google
Maven or Maven Central. New runtime dependencies require review of license,
maintenance status, necessity, and transitive impact. Required upstream notices
must remain in packaged artifacts; do not add blanket packaging exclusions that
discard dependency notices.

## Repository license

The repository does not currently declare a root license. Until the owner records
a distribution-license decision, treat the source as all-rights-reserved project
material: do not add third-party copied code and do not infer permission to
redistribute source merely because GitHub hosts the repository. This policy does
not choose a license on the owner's behalf.