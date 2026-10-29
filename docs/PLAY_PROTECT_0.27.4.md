# Play Protect block: direct 0.27.4

## Status

Investigated on 2026-10-27. The exact published direct 0.27.4 APK is
repeatedly blocked by Play Protect on Nothing A063, Android 15. Direct
0.27.3 successfully reinstalls on the same device through the same normal
package installer. The installation issue is not fixed.

Re-tested later on 2026-10-27 with the Play Protect cloud verdict captured
on both sides. The block is a server-side verdict attached to the 0.27.4
artifact, not a regression in the release's app code: the two APKs differ
only in Tasks code and version metadata, and Google returns `response=3`
(block) for 0.27.4 against `response=15` (allow) for 0.27.3 on the same
device minutes apart. There is therefore no source change in the release to
revert or repair; see "Verdict codes" below. With user approval,
a classification appeal was submitted and Google confirmed "Your email has
been sent". Google requires the contact to click the verification link in
the confirmation email to avoid delays. Inbox verification is pending and
cannot be performed by the diagnostic agent. Google's underlying reason is
not exposed by the captured logs.

The user reported both in-app updating and standalone installation failing.
The automated reproduction uses the standalone APK and does not require
the in-app updater. No app code, signing identity, or permissions were
changed to try to evade the verdict. Play Protect remained enabled and
"Install anyway" was never selected.

## Artifact

- Package: `app.aino.mobile`; version name/code: `0.27.4` / `27004`.
- Download: https://github.com/AINO-APPS/aino-android/releases/download/android-v0.27.4/AINO-0.27.4-direct.apk
- APK SHA-256: `c3bde307460121c7d5a0bcf7d5851143a3366a64736444b8ebb446139813e851`.
- Signing certificate SHA-256: `c490582bf04deaa2cf5575cbaa4d6b1c40e99ab5df7b950f56457cff4a9b6806`.
- VirusTotal report: https://www.virustotal.com/gui/file/c3bde307460121c7d5a0bcf7d5851143a3366a64736444b8ebb446139813e851

The release download, published checksum, and phone's standalone APK hash
match. `apksigner verify --verbose --print-certs` succeeds with APK v2
signing, one AINO signer, and a 4096-bit RSA key. VirusTotal analysis of
the exact public APK, uploaded with user authorization, reports "No
security vendors flagged this file as malicious". This is supporting
evidence for a suspected false positive, not proof of safety.

## Reproduction

Connect and authorize the affected Android device with ADB. Locate the
download's content URI using the MediaStore downloads provider. URI IDs
are device-specific. Keep Play Protect enabled. The loop confirms Install
or Update through the standard installer and can install the supplied APK;
use only the intended AINO artifact on a diagnostic device.

The script uninstalls `app.aino.mobile` before installing, so it measures a
clean install rather than an in-place upgrade. It dismisses any leftover Play
Protect card by tapping that card's own button, because the card ignores
`KEYCODE_BACK` and a stale one makes the next poll report a false block.

```powershell
& .\scripts\debug\play-protect-loop.ps1 -ApkUri content://media/external/downloads/1000042978 -ExpectedVersion 0.27.4
```

Observed repeatedly in seconds:

```text
PLAY_PROTECT: Harmful app blocked
PLAY_PROTECT: This app may be harmful.
REPRO_RED: Play Protect blocked the APK as harmful
```

The control used URI `content://media/external/downloads/1000042979` and
expected version `0.27.3`:

```text
REPRO_GREEN: app.aino.mobile 0.27.3 installed through the normal installer
```

The loop requires an installer success message plus the expected installed
version for green. A missing dialog or timeout is inconclusive, not green.
It removes its device-side XML dump and never bypasses a protection warning.
The standalone loop is a behavioral regression check; repository unit tests
cannot emulate Google's external classification. The in-app update route
must also be retested after the standalone verdict is resolved.

## Discriminating Evidence

Relevant device logs, restricted to this package:

```text
VerifyApps: APK is not malformed
VerifyApps: APK Analysis scan finished for app.aino.mobile. Verdict: 0
VerifyApps: overallScanResult for package=app.aino.mobile: 3
VerifyApps: chooseScanResult returning verdict 3 for package=app.aino.mobile
```

These logs distinguish the local analysis from the overall decision, but do
not establish the source of the latter or name a threat category.

Both release APKs have the same signing certificate, requested permissions,
minSdk 26, and targetSdk 35. Between the tags, direct updater code, manifest
permissions, dependencies, signing configuration, and release workflow are
unchanged. Source changes concern Tasks workflow/ownership, response models,
state handling, and task navigation. Decompressed APK entry hashes differ
only for the main DEX, manifest/resources, and baseline profiles. Native
libraries and other assets are identical. No concrete cause for the harmful
classification was established by that comparison.

## Verdict Codes

Captured on 2026-10-27 23:57 by re-running the loop against both downloads
already on the device (`content://media/external/downloads/1000042978` for
0.27.4, `.../1000042979` for 0.27.3), fifteen seconds apart on the same
installer, same originating package (`com.android.shell`), and same
installer package (`com.google.android.packageinstaller`).

```text
# 0.27.4 — blocked
VerifyApps: APK Analysis scan finished ... Verdict: 0
VerifyApps: Verification package=app.aino.mobile, id=24, response=3, upload_requested=false, enable_ecm=false
VerifyApps: overallScanResult ...: 3
VerifyApps: chooseScanResult returning verdict 3
START ... com.google.android.finsky.protectdialogs.activity.PlayProtectDialogsActivity

# 0.27.3 — allowed, installed
VerifyApps: APK Analysis scan finished ... Verdict: 0
VerifyApps: Verification package=app.aino.mobile, id=25, response=15, upload_requested=false, enable_ecm=false
VerifyApps: overallScanResult ...: 15
REPRO_GREEN: app.aino.mobile 0.27.3 installed through the normal installer
```

The on-device analysis is identical for both (`Verdict: 0`, "APK is not
malformed"). Only the cloud response differs, and `upload_requested=false`
means Google already held a verdict for each hash — the 0.27.4 artifact was
classified before it ever reached this device. Nothing the device or the app
does at install time changes that verdict.

## Artifact Delta

`0.27.4` versus `0.27.3` compared entry by entry from the published APKs:

| Surface | 0.27.3 | 0.27.4 | Different? |
| --- | --- | --- | --- |
| Signing cert SHA-256 | `c490582b…` | `c490582b…` | no |
| Signature schemes | v2 only | v2 only | no |
| Requested permissions | 29 (incl. `REQUEST_INSTALL_PACKAGES`) | identical | no |
| `AndroidManifest.xml` | — | `versionCode`/`versionName` only | version only |
| Native libraries (`lib/**`) | — | byte-identical | no |
| Other assets | — | byte-identical | no |
| `classes.dex` | 46,467 strings / 10,940 classes | 46,519 / 10,963 | yes |
| `resources.arsc`, `assets/dexopt/*` | — | version string + rebuilt profile | derived |

Every new DEX class is a Tasks model `Companion`/serializer; every new string
is a Tasks DTO field or a toast (`Some tickets are no longer available to
you`). No new URL, host, `Runtime`/`ProcessBuilder`, class loader, reflection
target, SMS/contacts/call-log access, or accessibility surface appears in
0.27.4 — the same scan over both DEX files returns an identical capability
list. The release also matches the tagged source: `version-control-info` in
the APK reports revision `11acfdb`, the `android-v0.27.4` commit, and the
download matches the published `checksums.txt`.

What actually changed in source is `4d7f899 fix(tasks): agree with the server
about ownership, bulk and reorder` — bulk now sends `ids` and decodes
per-ticket results, four Tasks endpoints decode the shapes the server really
sends instead of failing silently, status changes follow the team-aware
policy and `/agile/config` transitions, backlog reorder brackets the moved
row, and the dead generated model file is deleted.

## Resolution and Verification

> **Historical investigation.** The later device experiments and the approved
> direct-channel signing migration are recorded below. They do not establish
> Google's classification criteria or guarantee that a new release will pass.

Official classification appeal:
https://support.google.com/googleplay/android-developer/contact/protectappeals

Request review of the exact APK hash above, citing the VirusTotal report,
same-device previous-version control, signature verification, and verifier
evidence. Do not claim a confirmed false positive or invent a scanner cause.
Do not rotate signing keys, disable protection, or repack unrelated code to
try to avoid the warning. The appeal form requires a developer contact and
allows 1,000 characters of supporting information; its notice says appeal
decisions are final and no response is provided.

Completion requires rerunning the 0.27.4 loop to verified installation
success with protection enabled, then checking the original in-app update
workflow. Until then the issue remains open. The previously installed
0.27.3 remains on the diagnostic phone after the blocked 0.27.4 attempts.

Because the verdict is bound to the artifact rather than to a defect, there
are two ways to close this and neither is a source revert:

1. **Clear the classification.** Finish the pending appeal — Google's
   confirmation email must be verified or the request is not registered —
   and cite the hash, the same-device 0.27.3 control, and the `response=3`
   versus `response=15` capture above.
2. **Ship a new artifact.** Build and publish the next direct version from
   unchanged source and re-run the loop. A new hash gets its own verdict; if
   the new build is blocked too, the classification is content-driven rather
   than per-hash, and the next diagnostic step is to bisect the Tasks commit
   through CI builds rather than to keep re-signing the same bytes.
   *Later observation: multiple releases were blocked while 0.27.9 passed.
   Re-signing changed the observed result, but also changed the APK hash;
   these tests do not isolate Google's classification key.*

Do not mutate or re-sign the blocked APK purely to change its hash, and do
not walk users through disabling Play Protect. Both turn a false-positive
appeal into evasion and lose the standing that the appeal depends on.

Repository validation completed successfully:

```text
gradlew.bat --no-daemon testPlayDebugUnitTest lintPlayDebug assemblePlayDebug testDirectDebugUnitTest assembleDirectDebug
BUILD SUCCESSFUL in 4m 57s
```

All five required Node guardrails also passed. Build and guardrail success
does not validate or override a Play Protect classification.

## Later device observations and approved signing migration

Prior diagnostic runs reported blocks for 0.27.7, 0.27.8, 0.27.10,
0.27.11 and 0.27.12, with 0.27.9 passing. Re-signed 0.27.12 test
artifacts passed three repeated clean installs on the diagnostic device.
A locally rebuilt APK using the new direct key also passed three attempts.
These observations are not a guarantee for the next published APK.

Re-signing changes both the signing certificate and the APK hash. The tests
therefore support a signing/artifact-associated difference but do not prove
that Google keys its verdict exclusively to the certificate or exclude all
content heuristics. Matching certificate digests alone cannot transfer an
installation result from one APK to another. The previously reported
reason for 0.27.9 passing (install telemetry) was not established.

The absence of a public Play listing and a Play upload step does not prove
that a Play Console entry is absent, nor explain why an appeal email did not
arrive. The earlier investigation recorded a successful appeal submission;
its email delivery and review outcome remain unverified.

### Approved migration: 0.27.13

The user approved a new direct signing identity while retaining the package
name and existing Play signing credentials. Local builds use
ANDROID_DIRECT_KEYSTORE_PATH, ANDROID_DIRECT_KEYSTORE_PASSWORD,
ANDROID_DIRECT_KEY_ALIAS and ANDROID_DIRECT_KEY_PASSWORD for the direct
flavor. Release CI requires separate direct credentials and verifies the
exact certificate against the ANDROID_DIRECT_CERT_SHA256 repository variable.
Play credentials are unchanged.

The GitHub Actions repository secrets are:

- ANDROID_DIRECT_KEYSTORE_BASE64 (base64 of the keystore)
- ANDROID_DIRECT_KEYSTORE_PASSWORD
- ANDROID_DIRECT_KEY_ALIAS
- ANDROID_DIRECT_KEY_PASSWORD

Keep the keystore and password backed up outside source control. A lost key
prevents future in-place updates. Never include their values in logs or
release assets.

Existing 0.27.12-and-earlier installs use the old key and cannot update in
place to this release. Sync pending work and save local-only files before
uninstalling, because uninstalling removes app data. Install the new direct
APK and sign in again. Future releases signed with this direct key can
update in place. Moving between direct and Play keys also needs a reinstall.

The new updater detects a different current signer set before invoking the
installer. Old installed binaries do not contain that new warning; release
notes must provide the migration instruction. Unit tests cover the helper,
not Google classification or an end-to-end upgrade of the old binary.

### Verification limits and diagnostic cautions

Verify the exact published APK, with Play Protect enabled, on a connected
device. Compare its checksum and signing certificate against the release.
If blocked, record that outcome and seek classification review; do not
disable protection or interpret signing rotation as proof of safety.

Clean-install and in-place-update results are different paths and should be
reported separately. The clean-install diagnostic script is destructive:
it removes the installed package and its local data. Use a diagnostic device.
Dismiss stale Play Protect cards using OK, never Install anyway. A stale
card can contaminate the next UI observation; BACK did not dismiss it on the
tested device. The loop must verify the installed version after completion.
