# AINO production-readiness plan

Tracks what has to happen before the public Google Play release and the first
sales-led customers. It covers both repos: this Android app and the
`aino-platform` server, web and desktop. Update the item's status in the same
commit as the work, and write the commit SHA in the Done column.

**Legend:** ✅ Done · 🔄 In progress · ⏳ Pending · ⛔ Blocked (reason) · 🚫 Dropped (reason)

## Next up

1. Platform v3.0.45 is deployed (migrations 0010 + master 0009 applied, `MFA_ENC_KEY` set). Install Android 0.21.0 on two phones and run the sign-out test, then enrol an admin in two-step verification on the web (owner).
2. Compute the real TLS pins from a network without TLS inspection and set `AINO_CERT_PINS` ([CERT_PINNING_RUNBOOK.md](CERT_PINNING_RUNBOOK.md)) (owner).
3. Set `SENTRY_DSN` / `VITE_SENTRY_DSN` and add Sentry to the subprocessor list (P3.1) (owner).
4. Deploy the web app so `/privacy`, `/terms` and `/account-deletion` are live, then create the Play Console listing with [PLAY_CONSOLE_SUBMISSION.md](PLAY_CONSOLE_SUBMISSION.md) (owner).
5. Phase 3: legal and operations.

**Current release:** [android-v0.21.0](https://github.com/AINO-APPS/aino-android/releases/tag/android-v0.21.0), with platform v3.0.45 (Phase 2). **Earlier test release:** [android-v0.18.0](https://github.com/AINO-APPS/aino-android/releases/tag/android-v0.18.0). `AINO-0.18.0.apk` is the `direct` build (pilots; also served by the R2 update channel). `AINO-0.18.0-play.aab` is for the Play Console internal-testing track.

## Decisions (confirmed 2026-10-07)

| Topic | Decision |
|---|---|
| Distribution | Google Play, public listing |
| Market | India first, SMB and mid-market |
| Segments | Knowledge-work SMBs and frontline/field businesses |
| Billing | Sales-led: tenants onboarded manually and invoiced offline. No self-serve billing at launch |
| Payroll | Integrate with an Indian payroll provider. No in-house statutory engine |
| Meetings | Stay on WebRTC mesh, enforce and show a participant cap. SFU later |
| Admin security | TOTP MFA for admins at launch. Google / Microsoft OIDC SSO right after |
| Localization | Externalize strings now (English). Hindi right after launch |
| Updater | `play` flavor uses Play In-App Updates. `direct` flavor keeps the APK updater for pilots |
| Plan semantics | `calls` (Pro) = 1:1 and group calls / huddles. `meetings` (Enterprise) = scheduled meetings, lobby, HLS |
| Crash reporting | Firebase Crashlytics (reuses the existing Firebase project) |
| Admin on mobile | Stays web-only (security decision 2026-10-01). Not a gap |
| WhatsApp notifications | Not needed |

## Baseline (verified 2026-10-07)

- **HTTP parity:** 240 / 467 operations implemented, 227 waived on purpose, 0 pending, 0 unmatched ([PARITY_MATRIX.md](PARITY_MATRIX.md)).
- **Realtime:** all 80 server events are in `RealtimeEventRegistry`.
- **Recent web features already on Android:**
  - Calling/Ringing ack and the 60 s ring timeout.
  - Per-user clear/delete chat.
  - Huddles (`/huddle/:code`).
  - Scheduled tab, "New" status and owner-only status moves.
- **Feature gates:** Agile, meetings and payroll are gated by plan, the same as on web.
- **Account and debug:** in-app account deletion exists. The API probe is debug-only.

## Phase 0: verify before building

| ID | Item | Repo | Status | Done |
|---|---|---|---|---|
| P0.1 | Full CI green: Android (`play` + `direct` tests, lint, debug and release builds, 5 guardrails), platform server Jest (1,309) and web Vitest (285) + typecheck | both | ✅ | 2026-10-08 · android-v0.18.0 |
| P0.2 | 16 KB page-size check (`zipalign -c -P 16`, ELF `LOAD` alignment of every `.so`). CameraX 1.3.4 `libimage_processing_util_jni.so` was 4 KB aligned; fixed by P1.7 | android | ✅ | 2026-10-07 |
| P0.3 | Release-build smoke test on a device: login, clock-in with location and face, chat media, polls, 1:1 call from a killed app, huddle, notification deep links, update banner | android | ✅ Verified on the owner's device | 2026-10-09 · android-v0.18.0+ |
| P0.4 | `calls` vs `meetings` plan semantics. Decided: `calls` (Pro) = 1:1 and group calls / huddles; `meetings` (Enterprise) = scheduled meetings, lobby, HLS. Server: creating or reading a huddle needs `calls`, everything else needs `meetings`. Web and Android hide the call buttons without `calls`; Android `startCall` refuses too | both | ✅ | 2026-10-07 |
| P0.5 | `chat_media_job` / `chat_poll_vote` impact. Confirmed: media-processing progress and other users' poll votes only showed after a reload. Fixed in P4.1 | android | ✅ | 2026-10-08 · android-v0.18.0 |
| P0.6 | Branch / location model. Confirmed: organization → departments → teams, with **one** office geofence and Wi-Fi list per organization. No branches or multi-site attendance; added as P5.0 | platform | ✅ | 2026-10-08 · android-v0.18.0 |

## Phase 1: Google Play blockers

| ID | Item | Repo | Status | Done |
|---|---|---|---|---|
| P1.1 | `play` / `direct` flavors. `play` uses Play In-App Updates (flexible) and has no `REQUEST_INSTALL_PACKAGES`; `direct` keeps the R2 APK updater. The update banner is wired again (it had been unwired since 0.4.0). CI tests both flavors; the release workflow ships the `direct` APK + `play` AAB and fails if the permission leaks into `play` | android | ✅ | 2026-10-08 · android-v0.18.0 |
| P1.2 | `allowBackup=false`, `fullBackupContent=false`, and `data_extraction_rules.xml` excluding every domain from cloud backup and device transfer | android | ✅ | 2026-10-08 · android-v0.18.0 |
| P1.3 | Public `/privacy` and `/terms` pages on the web (draft based on the data the apps actually collect; **legal review pending**). Android links them on the login screen and the Profile page; "Forgot password?" opens the web reset flow | both | ✅ (legal review ⏳) | 2026-10-08 · android-v0.18.0, platform b62083e6 |
| P1.4 | Public `/account-deletion` page: the in-app steps, an email fallback, and what is deleted vs kept | platform | ✅ | 2026-10-08 · android-v0.18.0 |
| P1.5 | Data Safety answers in [PLAY_CONSOLE_SUBMISSION.md](PLAY_CONSOLE_SUBMISSION.md) | android | ✅ | 2026-10-08 · android-v0.18.0 |
| P1.6 | Play declarations and justification text (full-screen intent, foreground services, photo/video) in [PLAY_CONSOLE_SUBMISSION.md](PLAY_CONSOLE_SUBMISSION.md). Submitting them in the Console is an owner task | android | ✅ | 2026-10-08 · android-v0.18.0 |
| P1.7 | Fix 16 KB alignment: CameraX 1.3.4 → 1.4.2. All arm64-v8a / x86_64 libraries now align to 2**14 and zipalign `-P 16` passes | android | ✅ | 2026-10-07 |
| P1.8 | Firebase Crashlytics in the `play` flavor only: release builds, no user identifiers, auto-collection off until `CrashReporting.init`; mapping upload via the Crashlytics Gradle plugin | android | ✅ | 2026-10-08 · android-v0.18.0 |

## Phase 2: security hardening

| ID | Item | Repo | Status | Done |
|---|---|---|---|---|
| P2.1 | TOTP MFA for `hr_admin` and higher and for platform operators, on web / desktop. Sign-in asks for a code (`MFA_REQUIRED`), or forces enrollment (`MFA_ENROLL_REQUIRED`) with a QR code and 10 one-time recovery codes. Secrets are AES-GCM encrypted (`MFA_ENC_KEY`). Every change under `/api/admin*` and `/api/platform-access` needs a code from the last 10 minutes (step-up, `MFA_STEP_UP_REQUIRED`); the web app asks for the code and retries. Passkey and device-biometric sign-in count as MFA. App sign-ins are not challenged because the app has no admin surface | platform | ✅ | 2026-10-09 · android-v0.21.0, platform v3.0.45 |
| P2.2 | Sentry on the server, web and desktop renderer; off unless `SENTRY_DSN` / `VITE_SENTRY_DSN` is set. No request bodies, cookies, auth headers, query strings, emails, IP addresses or local variables are sent; only numeric user and tenant IDs | platform | ✅ (DSN ⏳ owner) | 2026-10-09 · android-v0.21.0, platform v3.0.45 |
| P2.3 | Strict allowlist instead of malware scanning. Every upload route checks the size, the declared MIME type and the file's magic bytes (`utils/uploadPolicy.ts`, 415 on mismatch). SVG logos are no longer accepted (they can contain script). File names are sanitized | platform | ✅ | 2026-10-09 · android-v0.21.0, platform v3.0.45 |
| P2.4 | `FLAG_SECURE` through a shared `SecureScreen()` on salary slips and view-once media | android | ✅ | 2026-10-09 · android-v0.21.0, platform v3.0.45 |
| P2.5 | OkHttp certificate pinning for `aino.org.in` and its subdomains (API, media, realtime, avatars, `direct` updater). At least 2 pins are required; debug builds never pin. See the [runbook](CERT_PINNING_RUNBOOK.md). Shipped **off** until the real pins are computed outside the corporate proxy | android | ✅ (pins ⏳ owner) | 2026-10-09 · android-v0.21.0, platform v3.0.45 |
| P2.6 | Risk accepted, no SQLCipher: see [SECURITY_DECISIONS.md](SECURITY_DECISIONS.md) | android | ✅ | 2026-10-09 · android-v0.21.0, platform v3.0.45 |
| P2.7 | The app gets a 15-minute access token plus a rotating refresh token (`POST /sessions/token`). Only hashes are stored, and replaying an old refresh token revokes the session. Older builds keep the long-lived rolling token. Signed-in devices list with remote sign-out on web (profile) and Android (Profile → Signed-in devices) | both | ✅ | 2026-10-09 · android-v0.21.0, platform v3.0.45 |
| P2.8 | Cap of 8 participants for meetings, group calls and huddles. The server refuses a 9th person (`meeting_full`); people rejoining keep their place. Web and Android show "n / 8" and a "Meeting is full" state | both | ✅ | 2026-10-09 · android-v0.21.0, platform v3.0.45 |
| P2.9 | One active session per client type: one phone plus one web / desktop. A new sign-in ends the other session of that type at once. Its socket is closed with `4001 "Signed in on another device"` on every server instance, and a `session_revoked` push signs out a backgrounded or killed app. Logout, password change or reset, and admin deactivation also close sockets at once | both | ✅ | 2026-10-09 · android-v0.21.0, platform v3.0.45 |

## Phase 3: legal and operations for India

| ID | Item | Repo | Status | Done |
|---|---|---|---|---|
| P3.1 | Privacy policy, terms, DPA, subprocessor list, breach SOP, retention schedule (DPDP Act 2023) | platform | ⏳ | |
| P3.2 | Restore drill with documented RPO / RTO | platform | ⏳ | |
| P3.3 | Status page and alerting on Prometheus metrics | platform | ⏳ | |
| P3.4 | Close open rollout items in the platform runbooks (R2 copier verification, D4.5, impersonation and tenant-separation production gates) | platform | ⏳ | |
| P3.5 | Browser E2E smoke tests for critical web flows in CI | platform | ⏳ | |

## Phase 4: sync polish

| ID | Item | Repo | Status | Done |
|---|---|---|---|---|
| P4.1 | `chat_media_job` patches media progress live. Poll bubbles now show the web `PollDisplay` tally (counts, %, your vote, total, multiple choice) and refresh on `chat_poll_vote` | android | ✅ | 2026-10-08 · android-v0.18.0 |
| P4.2 | Verified App Links (`autoVerify`) for meeting, huddle and notification URLs | both | ⏳ | |
| P4.3 | Move Android strings into `strings.xml` (English). Hindi follows | android | ⏳ | |
| P4.4 | Native face enrollment on Android. Needs a descriptor format the server accepts | both | ⏳ | |

## Phase 4b: UI/UX audit and fixes (Android and web)

The web design tokens stay the source of truth. This is a polish pass, not a redesign.

**Checklist per screen:**
- token use and spacing;
- empty, first-load, error, offline, permission-denied and plan-gated states;
- the same copy on web and Android;
- touch targets: 48 dp on Android, 44 px on mobile web;
- WCAG AA contrast;
- TalkBack, keyboard and ARIA support;
- font scaling up to 200 %;
- large screens;
- one-handed primary actions for frontline users.

| ID | Item | Repo | Status | Done |
|---|---|---|---|---|
| P4b.1 | Inventory and screenshots of every screen: web 1440 / 430 px, Android phone and tablet, light and dark | both | ⏳ | |
| P4b.2 | Findings ledger, triaged by severity | both | ⏳ | |
| P4b.3 | Fixes: shell and navigation, Home | both | ⏳ | |
| P4b.4 | Fixes: Attendance, Tasks | both | ⏳ | |
| P4b.5 | Fixes: Chat, Calls, Meetings | both | ⏳ | |
| P4b.6 | Fixes: Calendar, Notes, Manager, Profile, Organization, web Admin | both | ⏳ | |
| P4b.7 | Shared empty / error / offline components and UI tests for the fixed states | both | ⏳ | |

## Phase 5: features that help sell (ranked)

| ID | Item | Repo | Status | Done |
|---|---|---|---|---|
| P5.0 | Branches / multiple office locations: geofence and Wi-Fi per branch, users assigned to a branch, branch-level reports | both | ⏳ | |
| P5.1 | Shift and roster scheduling, shift-aware attendance, late / early / OT rules | both | ⏳ | |
| P5.2 | Expense claims and reimbursements (receipt photo, approval, payroll export) | both | ⏳ | |
| P5.3 | Payroll-provider integration: attendance, LOP and OT export / sync | platform | ⏳ | |
| P5.4 | Configurable multi-level approval workflows | both | ⏳ | |
| P5.5 | Field force: geo-tagged customer visits, beat plans, visit notes. Foreground location only | both | ⏳ | |
| P5.6 | Kiosk / shared-device attendance and offline punch through the outbox | both | ⏳ | |
| P5.7 | Onboarding / offboarding checklists and an employee document vault | both | ⏳ | |
| P5.8 | Later: OKRs and performance reviews, asset management, SSO, SFU meetings | both | ⏳ | |
