# AINO Android — Web Parity Implementation Plan

> **Goal:** make `aino-android` an exact native reproduction of the `aino-platform`
> web client as it renders at **430 × 932 (iPhone 16 Pro Max)**, across every
> route, with identical layout, typography, colour, copy, iconography,
> navigation and API behaviour.
>
> **Reference web app:** sibling repo `../aino-platform/client`
> **Reference server:** sibling repo `../aino-platform/server`
> **Target:** this repository (`aino-android`)
> **Interaction references:** the legacy mono-repo's `mobile/` Expo app and
> Signal-Android — patterns only, per `docs/SOURCE_PROVENANCE.md` (no code copied).
>
> Created: 2026-09-18. Revised: 2026-09-25 (Phase R stabilisation, status audit; Phases 5, 6, 7 and 9 landed).
> Status legend: `TODO` / `WIP` / `DONE` / `BLOCKED`.

### Product decisions (confirmed 2026-09-25)

- Android is the **mobile edition** of the platform: same backend, same data,
  realtime-synced with web/desktop, shipped as an independent app.
- **Features, data and copy follow the web.** **Interactions** (keyboard,
  voice recorder, media viewer, gestures) follow Signal-Android patterns.
- Manual **Refresh buttons are not used**. Lists get pull-to-refresh; every
  screen also refreshes from realtime events and on app resume (the web's
  focus/visibility refetch).
- Media stack: **Coil 3** (images, video posters) and **Media3/ExoPlayer**
  (voice/audio/video), all through the authenticated `AppContainer.mediaHttp`.
- Architecture: single `:app` module, manual factories, one `AppContainer`
  for process singletons; new feature screens create their ViewModel **per
  route** inside the NavHost (`viewModel(factory = …)`), cross-route VMs
  (auth, realtime, chat, attendance, dashboard) stay Activity-scoped.
- **Profile is a full page** on Android (tapping the top-bar avatar), not the
  web's dropdown; Edit Profile, Notification Sounds and Face Enrollment are
  full-screen sub-pages. Loading states use native Android indicators, not the
  web's skeleton/shimmer animations. See Phase 5 notes.

---

## 0. Executive summary of the current state

| Area                   | Web (430px)                                                                                                                  | Android today                                                                                                           | Verdict                                            |
| ---------------------- | ---------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------- |
| Bottom tab bar         | `Home · Calendar · Tasks · Chat · More` (64px, `--bg-secondary`)                                                             | `Home · Attendance · Tasks · Chat · More` (Material3 NavigationBar)                                                     | **Wrong tabs, wrong chrome**                       |
| Attendance entry point | inside the **More** popup                                                                                                    | a bottom tab                                                                                                            | **Wrong placement**                                |
| Profile                | `ProfileMenu` dropdown (280px) + modals, **no page**                                                                         | full-page `ProfileScreen` + sub-pages (deliberate, Phase 5)                                                             | **Decided deviation**                              |
| Theme                  | dark-default token set (`#131314` / `#2383e2`), 8px radius, Inter                                                            | bespoke cream `#F6F0E4` + "glass/atmosphere"                                                                            | **Different design language**                      |
| Dashboard              | greeting + announcement carousel, WorkTimer, TodayEvents, TasksSummary, SprintProgress, PendingApprovals, EventReminderToast | greeting + hand-drawn Canvas illustration, WorkTimer, 7-day date strip, Work-Location toggle, TodayEvents, TasksPlanner | **~50% missing, ~40% invented**                    |
| Attendance tabs        | `Overview(calendar) · Leaves · Manual Entry · Analytics`                                                                     | `Today · Overview · Leaves · Manual · Analytics`                                                                        | **Extra tab, no calendar, 2 tabs not wired to VM** |
| Chat                   | list ⇄ thread with back stack, reactions, replies, ticks, typing, attachments                                                | native list ⇄ thread routes, complete message actions/composer, calls/info panes, and realtime reconciliation           | **Phase 4 complete**                               |
| Endpoint coverage      | 464 operations                                                                                                               | 248 implemented (**53.4%**) per `docs/PARITY_MATRIX.md` after Phase 9                                                   | **216 pending**                                    |

### The single blocking defect

`AinoDestination.visibleBottomDestinations()` hides **Attendance / Tasks / Chat**
unless `features[key] == true`. `AinoUser.tenantFeatures` is decoded from the
JSON key `tenant_features`.

- `POST /api/auth/login` → `finishLogin()` (`server/routes/auth.ts:399`) returns
  **10 keys only** — `id, username, full_name, email, avatar, role, org_id,
tenant_id, has_reports, must_change_password`. **No `tenant_features`.**
- `GET /api/profile` (`server/routes/profile.ts:195-199`) **does** add them, but
  **only when `req.tenant` is truthy**:
  ```ts
  if (req.tenant) {
    const { getEffectiveFeatures } = require("../utils/planCatalog");
    user.tenant_features = getEffectiveFeatures(
      req.tenant.plan,
      req.tenant.features,
    );
    user.tenant_plan = req.tenant.plan || "standard";
  }
  ```
- `AuthRepository.complete()` re-fetches `profile` inside a
  `runCatching { }.getOrNull()` — **any** failure (decode error, 500, timeout)
  silently falls back to the sparse login user with `tenantFeatures = emptyMap()`,
  and because the gate is fail-closed the whole app becomes an empty shell with
  only Home and More.

**Canonical feature keys** (`server/utils/planCatalog.ts:43-80`):

| key             | standard | pro | enterprise |
| --------------- | -------- | --- | ---------- |
| `attendance`    | ✅       | ✅  | ✅         |
| `leaves`        | ✅       | ✅  | ✅         |
| `tasks`         | ✅       | ✅  | ✅         |
| `calendar`      | ✅       | ✅  | ✅         |
| `notes`         | ✅       | ✅  | ✅         |
| `notifications` | ✅       | ✅  | ✅         |
| `export`        | ✅       | ✅  | ✅         |
| `chat`          | ❌       | ✅  | ✅         |
| `calls`         | ❌       | ✅  | ✅         |
| `payroll`       | ❌       | ✅  | ✅         |
| `meetings`      | ❌       | ❌  | ✅         |
| `agile`         | ❌       | ❌  | ✅         |
| `custom_fields` | ❌       | ❌  | ✅         |
| `audit_logs`    | ❌       | ❌  | ✅         |
| `webhooks`      | ❌       | ❌  | ✅         |

Platform admins with no tenant bypass the gate entirely (`hasFeature` returns
`true` when `role === "platform_admin" && !tenant_id`).

### Transport is NOT the problem

`server/utils/cookie.ts:108` `readAuthToken()` accepts
`Authorization: Bearer …` whenever the resolved cookie name is `TENANT_COOKIE`
(every host except the console host). Android already sends
`Authorization: Bearer`, `X-Requested-With: AINO`, `x-timezone-offset`
(`core/network/RequestHelpers.kt`). The realtime socket accepts `?token=<jwt>`
and `verifyClient` explicitly allows a missing `Origin` header, which is what
OkHttp sends. Sign-in works and nothing else does **because of the feature gate
and per-screen wiring**, not because of auth.

---

## 1. Design tokens to transcribe (verbatim from `client/src/global.css`)

These become `core/designsystem/tokens/WebTokens.kt`. **Dark is the default.**

### Dark (`:root, [data-theme="dark"]`)

```
--primary            #2383e2
--primary-light      #529cca
--primary-dark       #1a6dbe
--primary-glow       rgba(35,131,226,0.15)
--accent             #2383e2
--accent-hover       #529cca
--on-accent          #ffffff
--success            #4daa57
--success-glow       rgba(77,170,87,0.18)
--warning            #cb912f
--warning-glow       rgba(203,145,47,0.18)
--danger             #e03e3e
--danger-glow        rgba(224,62,62,0.18)
--bg                 #131314
--bg-secondary       #1b1b1c
--bg-hover           rgba(255,255,255,0.055)
--bg-elevated        #202021
--surface            rgba(255,255,255,0.04)
--surface-hover      rgba(255,255,255,0.07)
--glass              rgba(255,255,255,0.04)
--glass-border       rgba(255,255,255,0.09)
--text               rgba(255,255,255,0.81)
--text-primary       rgba(255,255,255,0.81)
--text-secondary     rgba(255,255,255,0.53)
--text-muted         rgba(255,255,255,0.38)
--input-bg           rgba(255,255,255,0.065)
--card-bg            rgba(255,255,255,0.035)
--border             rgba(255,255,255,0.09)
--shadow             0 4px 24px rgba(0,0,0,0.35)
--radius             8px
--radius-sm          6px
--radius-full        9999px
```

### Light (`[data-theme="light"]`)

```
--bg                 #fbfbfa
--bg-secondary       #f5f4f1
(remaining overrides to be transcribed 1:1 during P1.1 — read global.css
 `[data-theme="light"]` block in full and port every property)
```

### Typography

- Family: `'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif`
- Base size **16px** → `1rem = 16.sp`
- `body { line-height: 1.6; color: var(--text) }`

### Shell chrome at ≤768px (`components/navbar/Navbar.module.css`)

```css
@media (max-width: 768px) {
  .navbar {
    padding: 0.5rem 0.75rem;
    min-height: 56px;
  }
  .nav-links {
    display: none;
  }
  .mobile-tab-bar {
    display: flex;
    position: fixed;
    bottom: 0;
    left: 0;
    right: 0;
    height: 64px;
    padding-bottom: env(safe-area-inset-bottom);
    background: var(--bg-secondary);
    border-top: 1px solid var(--border);
    z-index: 1000;
    justify-content: space-around;
    align-items: center;
  }
  .mobile-tab-bar a {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 2px;
    flex: 1;
    color: var(--text-muted);
    text-decoration: none;
    font-size: 0.62rem;
    font-weight: 600;
    padding: 6px 0;
    transition: color 0.15s;
  }
  .mobile-tab-bar a.active {
    color: var(--primary);
  }
  .tab-label {
    font-size: 0.62rem;
    letter-spacing: 0.01em;
  }
  .search-trigger {
    display: none;
  }
  .profile-name {
    display: none;
  }
  .brand-text {
    display: none;
  }
}
```

```css
.chatBadge {
  position: absolute;
  top: -5px;
  right: -8px;
  background: var(--danger);
  color: #fff;
  font-size: 0.6rem;
  font-weight: 700;
  min-width: 15px;
  height: 15px;
  padding: 0 3px;
  border-radius: 999px;
  line-height: 1;
  border: 2px solid var(--bg-secondary);
}

.mobile-more-btn {
  flex-direction: column;
  gap: 2px;
  background: none;
  border: none;
  color: var(--text-muted);
  padding: 6px 0;
  width: 100%;
  font-size: 0.62rem;
  font-weight: 600;
}
.mobile-more-btn.active {
  color: var(--primary);
}

.mobile-more-popup {
  position: absolute;
  bottom: calc(100% + 10px);
  right: 4px;
  min-width: 190px;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: 14px;
  box-shadow: 0 10px 34px rgba(0, 0, 0, 0.3);
  padding: 6px;
  gap: 2px;
  z-index: 1200;
  animation: moreSlideUp 0.16s ease;
}
.mobile-more-popup a {
  gap: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  color: var(--text-secondary);
  font-size: 0.85rem;
  font-weight: 600;
}
.mobile-more-popup a:hover,
.mobile-more-popup a.active {
  background: var(--bg-hover);
  color: var(--text);
}
```

Page body gets `padding-bottom: 90px` at ≤768px (`global.css` `.app`), removed
when `body[data-chat-active]`.

Global card (`global.css` `.status-card`):

```css
background: var(--bg-secondary);
border: 1px solid var(--border);
border-radius: 8px;
padding: 1.1rem;
box-shadow: none;
margin-bottom: 1rem;
text-align: center;
position: relative;
overflow: hidden;
/* @media (max-width: 768px) { padding: 1.2rem; border-radius: 20px; } */
```

Each dashboard module overrides to `text-align: left; padding: 1.2rem; margin-bottom: 0;`.

---

## 2. Navigation contract (exact web parity — confirmed decision)

### Bottom tab bar — `components/navbar/MobileTabBar.tsx`

| #   | Label      | lucide icon (22px)             | Route       | Condition                |
| --- | ---------- | ------------------------------ | ----------- | ------------------------ |
| 1   | `Home`     | `Home`                         | `/`         | always                   |
| 2   | `Calendar` | `Calendar`                     | `/calendar` | `hasFeature("calendar")` |
| 3   | `Tasks`    | `ClipboardList`                | `/tasks`    | `hasFeature("tasks")`    |
| 4   | `Chat`     | `MessageSquare` + unread badge | `/chat`     | `hasFeature("chat")`     |
| 5   | `More`     | 3-line hamburger SVG (20px)    | popup       | always                   |

### More popup contents, in this exact order

| Label          | lucide icon (18px) | Route           | Condition                                    |
| -------------- | ------------------ | --------------- | -------------------------------------------- |
| `Notes`        | `FileText`         | `/notes`        | `hasFeature("notes")`                        |
| `Attendance`   | `CalendarCheck`    | `/attendance`   | `hasFeature("attendance")`                   |
| `Organization` | `Building2`        | `/organization` | `user.org_id \|\| role === "platform_admin"` |
| `My Team`      | `Users`            | `/manager`      | `ROLE_LEVEL >= 2 \|\| has_reports`           |
| `Admin`        | `Settings`         | `/admin`        | `ROLE_LEVEL >= 4`                            |
| `Tenants`      | `Server`           | `/tenants`      | `role === "platform_admin"`                  |

`ROLE_LEVELS = { employee:1, team_lead:2, manager:3, hr_admin:4, super_admin:5, platform_admin:6 }`

The More button renders `active` when `mobileMoreOpen || moreItems.some(i => i.to === pathname)`.

### Top bar (`Navbar` at ≤768px)

56dp min-height, `0.5rem 0.75rem` padding, `--bg-secondary`, bottom border
`--border`. Contents: logo mark only (`.brand-text` hidden), notification bell,
`ProfileMenu` trigger (32px avatar + status dot, `.profile-name` hidden,
chevron). `.search-trigger` hidden.

### ProfileMenu dropdown (superseded on Android — see Phase 5: Profile is a full page)

280dp wide, 18dp radius, `--bg-elevated`, `max-height: calc(100vh - 92px)`,
scrollable. Status dot colour map:
`available→--success`, `busy→#ef4444`, `dnd→#ef4444`, `brb→--warning`,
`away→--warning`, `offline→transparent + #64748b ring`, `in_call→#ef4444`,
`in_meeting→#0ea5e9`.

---

## 3. Phase plan

Each task lists **files**, **work**, and **acceptance**. Phases are ordered by
dependency; P0 must land before anything else is testable on device.

---

### PHASE 0 — Unblock (must land first)

| ID   | Task                                            | Status |
| ---- | ----------------------------------------------- | ------ |
| P0.1 | Guarantee `tenant_features` hydration           | DONE   |
| P0.2 | Surface gate failures instead of hiding the app | DONE   |
| P0.3 | On-device API probe screen                      | DONE   |
| P0.4 | Fix Attendance tab→ViewModel wiring bug         | DONE   |
| P0.5 | Response-decode regression tests                | DONE   |

**P0.1 — Guarantee `tenant_features` hydration**

- Files: `core/auth/AuthRepository.kt`, `core/auth/AuthViewModel.kt`, `core/auth/AuthModels.kt`
- Work:
  - Replace the silent `runCatching { … }.getOrNull()` in `complete()` with an
    explicit result type. A profile fetch failure must produce a
    `FeaturesUnavailable` state, not an empty map.
  - Retry the profile fetch with backoff (3 attempts) before giving up.
  - Persist the last-known-good `tenant_features` in the token store so a cold
    start with a transient network failure does not collapse the tab bar.
  - Log the decoded feature map at INFO on every hydration.
- Acceptance: after a fresh login on a `pro` tenant the bottom bar shows
  `Home · Calendar · Tasks · Chat · More`; killing the network during login
  shows the P0.2 banner rather than a 2-tab shell.

**P0.2 — Surface gate failures**

- Files: `core/navigation/AinoApp.kt`, `core/navigation/AinoDestination.kt`
- Work: when `tenantFeatures.isEmpty() && role != "platform_admin"`, render a
  dismissible `--warning` banner: _"Workspace features could not be loaded.
  Some sections are hidden."_ with a Retry action. Never render a silently
  truncated tab bar.
- Acceptance: forced-failure build shows the banner and Retry re-hydrates.

**P0.3 — On-device API probe**

- Files (new): `feature/debug/ApiProbeScreen.kt`, `feature/debug/ApiProbeViewModel.kt`
- Work: a debug-only screen (reachable from More in debug builds) that issues
  `GET` against the 12 core endpoints and prints status code + first 300 bytes:
  `profile`, `tracker/status`, `tracker/task-summary`, `tracker/entries/<today>`,
  `calendar?start&end`, `notifications/announcements`, `chat/conversations`,
  `leaves`, `leaves/balance`, `leave-policy/policies`, `tracker/manual-entries`,
  `me/status`.
- Acceptance: every probe returns 200 on a seeded tenant.

**P0.4 — Attendance tab wiring bug**

- File: `feature/attendance/AttendanceScreen.kt`
- Work: `AttendanceTabs.onSelect` currently maps only `Today/Overview/Manual`
  to `viewModel.selectTab(...)`; `Leaves` and `Analytics` fall into
  `else -> Unit`, so their data never loads. Extend `AttendanceTab` and route
  every page. (This tab set is replaced wholesale in P3.1, but the fix is
  needed now so the screen is testable.)
- Acceptance: switching to Leaves/Analytics triggers a network fetch.

**P0.5 — Decode regression tests**

- Files (new): `app/src/test/java/app/aino/mobile/core/network/ResponseDecodeTest.kt`
- Work: capture real JSON payloads from P0.3 into
  `app/src/test/resources/payloads/` and assert each model decodes without
  throwing and without producing empty collections. This is the guard against
  the entire class of "screen renders but is blank" bugs.
- Acceptance: tests fail if a model's `@SerialName` drifts from the server.
- Status (2026-09-25): 12 fixtures — the original 6 plus `org-current`,
  `calendar-events`, `chat-conversations`, `chat-messages`,
  `leave-policy-policies`, `tracker-manual-entries`. `me/status` is not
  consumed on Android yet; its fixture lands with P5.1. `org-current` is the
  regression for the object-shaped `office_wifi_bssids` clock-in bug (R11).

---

### PHASE 1 — Design system + app shell

| ID   | Task                                                        | Status |
| ---- | ----------------------------------------------------------- | ------ |
| P1.1 | `WebTokens.kt` — port every CSS custom property             | DONE   |
| P1.2 | Typography + Inter font                                     | DONE   |
| P1.3 | `WebCard` / `.status-card` primitive                        | DONE   |
| P1.4 | Top bar rebuild (`Navbar` ≤768px)                           | DONE   |
| P1.5 | Bottom tab bar rebuild (`mobile-tab-bar`)                   | DONE   |
| P1.6 | More popup sheet                                            | DONE   |
| P1.7 | `ProfileMenuSheet` replacing `ProfileScreen` (superseded by the P5.0 full page) | DONE   |
| P1.8 | Theme toggle + persistence                                  | DONE   |
| P1.9 | Retire `AinoAtmosphere` / glass styling from parity screens | DONE   |

**P1.1 — `WebTokens.kt`**

- Files (new): `core/designsystem/tokens/WebTokens.kt`, `core/designsystem/tokens/WebTheme.kt`
- Work: transcribe §1 verbatim. Provide `LocalWebColors` / `LocalWebDimens`
  `CompositionLocal`s with `WebColorsDark` and `WebColorsLight` sets. Radii:
  `radius = 8.dp`, `radiusSm = 6.dp`, `radiusFull = 9999.dp`. Shadow:
  `0 4px 24px rgba(0,0,0,0.35)`.
- Acceptance: a token-dump unit test asserts each colour's ARGB matches the CSS.

**P1.2 — Typography**

- Files: `app/src/main/res/font/` (Inter family), `core/designsystem/tokens/WebTypography.kt`
- Work: bundle Inter (Regular/Medium/SemiBold/Bold/ExtraBold). Build a `rem`
  helper (`1.rem == 16.sp`) and map the sizes used across the app
  (`0.6/0.62/0.85/0.88/1.4 rem`, line-height 1.6).

**P1.3 — `WebCard`**

- Files (new): `core/designsystem/component/WebCard.kt`
- Work: `.status-card` at mobile → `background = bgSecondary`,
  `border = 1.dp borderColor`, `radius = 20.dp` (mobile override),
  `padding = 1.2rem (19.2.dp)`, no shadow, left-aligned content.

**P1.4 — Top bar**

- Files: `core/navigation/AinoApp.kt` (replace `AinoShellTopBar`)
- Work: 56dp min-height, 8dp/12dp padding, `bgSecondary`, 1px bottom border,
  logo mark only, notification bell, `ProfileMenu` trigger (32dp avatar +
  10dp status dot with 2dp `surface` ring + the per-status SVG glyph).
  Keep `statusBarsPadding()`.
- Acceptance: screenshot diff vs web at 430px within tolerance.

**P1.5 — Bottom tab bar**

- Files: `core/navigation/AinoApp.kt`, `core/navigation/AinoDestination.kt`,
  `core/designsystem/component/WebTabBar.kt` (new)
- Work: rewrite `AinoDestination` to the §2 table — `Dashboard("/", Home)`,
  `Calendar`, `Tasks`, `Chat`, `More` in the bottom bar; `Attendance` demoted to
  a More-sheet destination. Implement the bar per the verbatim CSS: 64dp,
  `bgSecondary`, 1dp top border `border`, `navigationBarsPadding()`,
  `SpaceAround`, per-item `Column(gap 2dp, padding 6dp vertical)`, icon 22dp,
  label 0.62rem/600, active `primary` / inactive `textMuted`, 150ms colour
  transition. Chat badge per `.chatBadge`.
- Acceptance: tab set, order, icons, badge and colours match the web at 430px.

**P1.6 — More popup**

- Files (new): `core/navigation/MoreSheet.kt`; delete `MoreScreen` from `AinoApp.kt`
- Work: anchored popup above the bar (`bottom: calc(100% + 10px); right: 4px`),
  min 190dp, `bgElevated`, 14dp radius, 1dp border, 6dp padding, 2dp item gap,
  `0 10px 34px rgba(0,0,0,0.3)` shadow, 160ms slide-up. Rows: 18dp icon + 10dp
  gap + label 0.85rem/600 `textSecondary`, 10dp radius, `10px 12px` padding,
  active → `bgHover` + `text`. Items and conditions exactly per §2.
- Acceptance: item list and ordering match `MobileTabBar.tsx:52-61` for each role.

**P1.7 — `ProfileMenuSheet`**

- Files (new): `feature/profile/ProfileMenuSheet.kt`;
  modify `feature/profile/ProfileScreen.kt` → becomes the Edit-Profile form only
- Work: port `ProfileMenu.tsx` — 280dp, 18dp radius, header (avatar, full name,
  role, email), `StatusPicker` row, menu rows, theme toggle, `Log out`.
  Remove `AinoDestination.Profile` from the NavHost as a top-level page.
- Acceptance: rows, labels, icons and order match `ProfileMenu.tsx`.

**P1.8 — Theme toggle**

- Files: `core/designsystem/tokens/WebTheme.kt`, DataStore preference
- Work: dark default, persisted override, applied without restart. Mirror the
  web's `data-theme` attribute semantics.

**P1.9 — Retire glass styling**

- Files: `core/designsystem/AinoComponents.kt`
- Work: keep `AinoAtmosphere`/`AinoGlassCard` only where no web counterpart
  exists (incoming-call UI). Every parity screen uses `WebCard`.

---

### PHASE 2 — Home / Dashboard

Web source: `client/src/pages/Dashboard.tsx` (205 L) + `Dashboard.module.css`,
children in `client/src/components/dashboard/`.

Render order at 430px:

1. `.greeting-banner`
   - `.greeting-text` → `"{getGreeting()}, {user.full_name || "there"}!"`
     where `getGreeting()` = `hour < 12 ? "Good Morning" : hour < 17 ? "Good Afternoon" : "Good Evening"`
   - `.greeting-date` → `toLocaleDateString("en-US", { weekday:"long", month:"long", day:"numeric", year:"numeric" })`
   - `.announcement-carousel` — only when `announcements.length > 0`.
     Quote type wraps the message in `"…"`, other types render bare.
     Dots only when `announcements.length > 1`; rotation every
     `QUOTE_ROTATION_INTERVAL` (read from `client/src/constants.ts`).
2. `.dashboard-content`
   - `<WorkTimerCard />`
   - `<TodayEventsCard events={todayEvents} tomorrowEvents={tomorrowEvents} />`
   - `<TasksSummary taskSummary={taskSummary} />`
   - `<SprintProgressCard />`
   - `{isManager && <PendingApprovalsCard />}` where
     `isManager = ROLE_LEVEL[user.role] >= ROLE_LEVEL.team_lead || user.has_reports`
3. `<EventReminderToast reminders onDismiss />`

First-load data (`fetchDashboard()`, all via `Promise.allSettled` — **a partial
failure must not blank the page**):

| call                                     | method | path                                               |
| ---------------------------------------- | ------ | -------------------------------------------------- |
| `getTaskSummary()`                       | GET    | `/tracker/task-summary`                            |
| `getCalendarEvents(dayStart, dayEnd)`    | GET    | `/calendar?start=&end=`                            |
| `getCalendarEvents(dayEnd, tomorrowEnd)` | GET    | `/calendar?start=&end=`                            |
| `getActiveAnnouncements()`               | GET    | `/notifications/announcements` → `{ data: [...] }` |

`isLoading` → `<DashboardSkeleton />`. Refetch triggers: WS `calendar_refresh`,
`meeting_updated`, `meeting_cancelled`; and re-entry to `/`.

| ID    | Task                                                                                   | Status |
| ----- | -------------------------------------------------------------------------------------- | ------ |
| P2.1  | Delete cream design + `DashboardIllustration` Canvas + date strip + Work-Location card | DONE   |
| P2.2  | Greeting banner + announcement carousel with dots                                      | DONE   |
| P2.3  | `WorkTimerCard` 1:1 port                                                               | DONE   |
| P2.4  | `TodayEventsCard` (today + tomorrow) 1:1 port                                          | DONE   |
| P2.5  | `TasksSummary` 1:1 port                                                                | DONE   |
| P2.6  | `SprintProgressCard` (new on Android)                                                  | DONE   |
| P2.7  | `PendingApprovalsCard` (new on Android, manager-gated)                                 | DONE   |
| P2.8  | `DashboardSkeleton` port                                                               | DONE   |
| P2.9  | `EventReminderToast` + `useEventReminder` logic                                        | DONE   |
| P2.10 | `allSettled` fetch + stale-while-revalidate cache                                      | DONE   |
| P2.11 | WS invalidation + on-resume refresh                                                    | DONE   |

Files:
`feature/home/HomeScreen.kt` (rewrite), `feature/home/DashboardRepository.kt`,
`DashboardModels.kt`, `DashboardViewModel.kt`, plus new
`feature/home/component/{GreetingBanner,WorkTimerCard,TodayEventsCard,TasksSummaryCard,SprintProgressCard,PendingApprovalsCard,DashboardSkeleton,EventReminderToast}.kt`.

Acceptance: side-by-side screenshot at 430×932 in both themes; every literal
string, icon and ordering matches; manager role shows the approvals card.

---

### PHASE 3 — Attendance

Web source: `client/src/pages/Attendance.tsx` (113 L) + `Attendance.module.css`.

Shell:

```
<h1 class=title>Attendance</h1>
<p class=subtitle>Track your daily attendance, leaves, manual entries and analytics in one place</p>
<nav role=tablist aria-label="Attendance sections"> … </nav>
```

`.page` padding `1.25rem 1rem 4rem` at ≤768px; title `1.4rem/800/-0.03em`;
subtitle `0.88rem var(--text-muted)`; `fadeInUp .3s ease`.

Tabs (`TABS` array — exact order, labels, icons, hashes):

| id          | label          | lucide         | hash            |
| ----------- | -------------- | -------------- | --------------- |
| `overview`  | `Overview`     | `CalendarDays` | `""`            |
| `leaves`    | `Leaves`       | `Palmtree`     | `#leaves`       |
| `manual`    | `Manual Entry` | `FileEdit`     | `#manual-entry` |
| `analytics` | `Analytics`    | `BarChart3`    | `#analytics`    |

Tabs are keep-alive panels (`display:none` when inactive) — mirror with a
`SaveableStateHolder` so scroll position and form state survive switching.
At ≤480px `.tabs { width:100% }` and each `.tab` flexes.

Legacy redirects to honour as deep links: `/leaves→#leaves`,
`/manual-entry→#manual-entry`, `/analytics→#analytics`, `/leave-policy→#leaves`.

Overview **is a month calendar**: `client/src/pages/attendance/AttendanceCalendar.tsx`
(+ `.module.css`), driven by `refreshKey`. Android currently has no calendar.

Clock-in/out verification lives in `client/src/components/attendance/`:
`ClockInVerifyModal.tsx`, `FaceCapture.tsx`, `VerifyError.tsx` — used by the
Dashboard/FloatingTimer, not by the Attendance page.

| ID   | Task                                                                                                            | Status |
| ---- | --------------------------------------------------------------------------------------------------------------- | ------ |
| P3.1 | Retab to the web's 4 tabs; delete the Android-only `Today` tab; move clock controls to the Dashboard timer card | DONE   |
| P3.2 | `AttendanceCalendar` month grid: day cells, status colours, legend, month nav, `refreshKey`                     | DONE   |
| P3.3 | Leaves tab: balances, application list, apply form, withdraw                                                    | DONE   |
| P3.4 | Manual Entry tab: form + list + edit                                                                            | DONE   |
| P3.5 | Analytics tab: summary tiles + `HistoryTable` with mobile card collapse                                         | DONE   |
| P3.6 | Clock in/out/break with full payload (geo, face, wifi, `work_mode`)                                             | DONE   |
| P3.7 | `ClockInVerifyModal` + `FaceCapture` equivalents                                                                | DONE   |
| P3.8 | Keep-alive tab state + hash deep links                                                                          | DONE   |

Phase 3 implementation notes (Android, 2026-09-21):

- The old `feature/leaves` module was absorbed into `feature/attendance` — the
  web redirects `/leaves` into the Attendance > Leaves tab, so the standalone
  screen, its calendar sub-tab and its `Today` tab are gone.
- The Leaves tab hosts all four web sub-tabs. HR-only **Policies** and
  **All Balances** are read-only views; policy/balance mutation and holiday
  management stay on the web until Phase 10. The web's **Export** buttons
  (`export/my-leaves`, `export/my-analytics`) are deferred to Phase 10 as well.
- The Analytics tab ships the widgets grid, summary tiles, work-vs-break bars,
  trend line, both distribution doughnuts and the daily log as cards. The
  web-only **Notification Routing** widget belongs to Phase 7
  (`/notifications/metrics`).
- `FaceCapture` parity note: the web computes a face-api.js 128-float
  descriptor in the browser; no on-device model produces a compatible
  descriptor, so a confident in-frame face (CameraX + ML Kit, same
  auto-capture coaching UX) gates the platform biometric proof and the request
  carries `fingerprint_verified` + `wifi_bssid` + geo — the server-sanctioned
  native fallback in `server/routes/tracker.ts`.
- Date/time inputs map `<input type=date|time>` to the platform pickers;
  `<input type=month>` and the year `<select>` map to chevron steppers.

Endpoints (from `docs/PARITY_MATRIX.md`, tracker + leaves groups):

| status      | method  | path                                      |
| ----------- | ------- | ----------------------------------------- |
| implemented | GET     | `/api/tracker/status`                     |
| implemented | GET     | `/api/tracker/entries/:date`              |
| implemented | POST    | `/api/tracker/clock-in`                   |
| implemented | POST    | `/api/tracker/clock-out`                  |
| implemented | POST    | `/api/tracker/break-start`                |
| implemented | POST    | `/api/tracker/break-end`                  |
| implemented | GET     | `/api/tracker/manual-entries`             |
| implemented | POST    | `/api/tracker/manual-entry`               |
| implemented | PUT     | `/api/tracker/manual-entry/:date`         |
| implemented | GET     | `/api/tracker/task-summary`               |
| implemented | POST    | `/api/tracker/overtime-request`           |
| implemented | GET     | `/api/tracker/overtime-requests`          |
| **pending** | GET     | `/api/tracker/analytics`                  |
| **pending** | GET     | `/api/tracker/history`                    |
| **pending** | GET     | `/api/tracker/weekly`                     |
| **pending** | GET     | `/api/tracker/widgets`                    |
| **pending** | DELETE  | `/api/tracker/entries/:date`              |
| **pending** | GET/PUT | `/api/tracker/theme`                      |
| **pending** | GET     | `/api/leaves`                             |
| **pending** | GET     | `/api/leaves/balance`                     |
| **pending** | GET     | `/api/leaves/summary`                     |
| **pending** | GET     | `/api/leaves/monthly-summary`             |
| **pending** | GET     | `/api/leaves/pending`                     |
| **pending** | PATCH   | `/api/leaves/:id/{approve,reject,revoke}` |
| **pending** | GET     | `/api/leave-policy/balances`, `/holidays` |

Note: most `/api/tracker/*` handlers live in
`server/modules/attendance/attendance.routes.ts`, **not** `server/routes/tracker.ts`
(which only holds clock-in/clock-out). Every tracker endpoint reads the client
timezone from the `x-timezone-offset` header (IST = `-330`) — Android already
sends it.

Responses are **bare** (no `{success,data}` envelope). The only envelope
exceptions in the whole API are `GET /api/notifications/announcements`
(`{ data: [...] }`) and `POST /api/auth/login` (`{ user, token }`).

---

### PHASE 4 — Chat

Web source: `client/src/pages/Chat.tsx` (627 L) + `client/src/pages/chat/*`
(30 files) + `client/src/components/chat/*`.

Layout collapse: `Chat.tsx` renders `<ChatSidebar>` and `<div class=chatArea>`.
`mobileView` (`useChatState.ts`, initial `"list"`) toggles `.hideMobile`
(`display:none` at ≤768px) so exactly one pane shows. `selectConversation()` →
`"chat"`; `ChatHeader onBack` → `"list"`. No animation.

Sidebar order:

1. Selection bar (only in `selectionMode`): `X` "Cancel selection",
   `{n} selected`, `CheckSquare2` + `Select all`/`Clear all`, `Trash2` + `Delete`.
2. `.sidebarTabs` — always present: `MessageSquare(14) Chat` (+ `.totalBadge`),
   `Video(14) Meet` (only `hasFeature("meetings")`), `Phone(14) Calls`.
   **Verbatim labels: `Chat`, `Meet`, `Calls`.**
3. Search header.
4. Conversation groups.

`ConversationItem` class names are `.convItem`, `.convInfo`, `.convTop`,
`.convName`, `.convTime`, `.convPreview`, `.badge` (avatar from
`ChatAvatar.module.css`).

`ChatHeader` children in order: back button (`ArrowLeft` 18, `display:none`
desktop → 32×32 8px-radius at ≤768px), `ChatAvatar size="md"` (40×40,
`--radius-full`, 13×13 status dot with 2px `--bg` border; colours
`available #22c55e`, `busy/dnd/in_call #ef4444`, `away #f59e0b`,
`in_meeting #f59e0b`, `offline` transparent + muted ring), then
`.chatHeaderInfo` (clickable → `onOpenInfo`).

Thread pieces: `ChatMessages.tsx` (+ CSS), bubble =
`components/chat/MessageBubble.tsx` with `MessageContent.tsx`,
`DeliveryStatus.tsx`, `MessageToolbar.tsx`, `ReactionBar.tsx`,
`ReplyPreview.tsx`, `FilePreview.tsx`; composer = `pages/chat/ChatInputBar.tsx`;
info pane = `ConversationInfoPanel.tsx`.

| ID    | Task                                                                                                                                                   | Status |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------ | ------ |
| P4.1  | List ⇄ thread navigation with a native back stack and `aino://chat/{conversationId}` deep links                                                       | DONE   |
| P4.2  | Sidebar tabs `Chat/Meet/Calls` + badges + `hasFeature("meetings")` gate                                                                                | DONE   |
| P4.3  | Search header + selection mode + server-backed bulk delete                                                                                             | DONE   |
| P4.4  | Conversation grouping + `ConversationItem` anatomy (avatar, presence dot, name, preview prefixes, time format, unread badge, mute, pin, delivery tick) | DONE   |
| P4.5  | `ChatHeader` (back, avatar, name, presence/status format, outgoing voice/video call buttons, info/menu actions)                                        | DONE   |
| P4.6  | Date separators + scroll pinning + load-more (the reference web client has no unread-divider component)                                                | DONE   |
| P4.7  | `MessageBubble`: reply quote, forwarded/system/poll messages, typed attachments, text, edited marker, timestamp, delivery ticks, swipe-to-reply + long-press gestures | DONE   |
| P4.8  | `ReactionBar` (10-emoji quick set + categorized picker) wired into long-press, matching `ReactionPicker.tsx`/`ReactionBar.tsx` verbatim                              | DONE   |
| P4.9  | `ChatInputBar`: verbatim placeholder, reply/edit bars, document/camera attachments, emoji picker, native AAC voice recording with Signal-style recording bar          | DONE   |
| P4.10 | Typing indicator                                                                                                                                       | DONE   |
| P4.11 | `ConversationInfoPanel`                                                                                                                                | DONE   |
| P4.12 | `CallsTab` + conversation call history + selection/select-all/delete                                                                                   | DONE   |
| P4.13 | Wire all 29 formerly pending `/api/chat/*` operations                                                                                                  | DONE   |
| P4.14 | Complete WS event routing, typed high-frequency patches, refetch reconciliation, heartbeat and reconnect policy                                       | DONE   |

Phase 4 implementation notes (Android, 2026-09-21):

- Chat list and thread are distinct Navigation Compose destinations. The thread
  carries only `conversationId`, supports process/deep-link restoration, and
  leaves the Chat bottom tab selected.
- The composer uses Android content URIs and the existing multipart endpoint for
  documents, Camera capture and AAC voice notes. It requests camera/microphone
  permission at the point of use and exposes temporary media through FileProvider.
- Message presentation covers grouped bubbles, dates, replies, forwarding,
  system/poll formats, image/audio/video/document attachments, media-job state,
  reactions, edit/delete/star/pin/forward, read receipts and optimistic outbox rows.
- All 52 operations under `/api/chat/*` are now proven by
  `scripts/check-endpoint-parity.mjs`; total Android coverage is 92/464.
- `scripts/check-realtime-parity.mjs` proves all 73 server event names are routed.
  Chat patches typing, receipts, reactions, edits, deletes and pins immediately;
  authoritative collection mutations use the registry-driven reconcile/refetch
  policy. Transport already supplies JWT query auth, application heartbeat,
  jittered reconnect and terminal close-code handling.

Realtime: server is **raw `ws`**, not socket.io.

Phase 4 correction (Android, 2026-09-24): a documentation/code audit found P4.7-P4.9
had been marked DONE prematurely. Four gesture/media/reaction/voice files
(`ChatMessageGestures.kt`, `ChatReactionOverlay.kt`, `ChatVoiceUi.kt`,
`ChatMediaPreview.kt`) existed on disk but were never wired into `ChatScreen.kt`,
and the project did not compile (`ChatReactionOverlay.kt`'s `EmojiPicker` used
`Modifier.align()` outside a `BoxScope`). Concretely, before this fix:

- The composer's `.navigationBarsPadding().imePadding()` chain **summed** both
  insets instead of taking the max, leaving a blank gap above the keyboard once
  the IME opened.
- Long-press only opened a single "thumbs up" action, not the full reaction bar;
  there was no swipe-to-reply gesture.
- The quick-reaction set only had 6 emoji instead of web's 10
  (`ReactionPicker.tsx`), and there was no categorized "+" emoji picker.
- Delivery ticks were a 2-state (sent/read) boolean instead of the 3-state
  Sent/Delivered/Read logic in `DeliveryStatus.tsx`.
- Voice recording used a plain status row instead of the Signal-style
  recording bar, and media attachments rendered as a bare filename/icon row
  instead of real image/audio/video previews.

All of the above are now fixed and wired: `EmojiPicker`'s layout bug is fixed,
`MessageGestureBox` (swipe-to-reply + long-press) is wired into `MessageBubble`,
`ChatReactionOverlay` (10-emoji quick bar + categorized picker, matching web
verbatim) replaces the old single-reaction menu and is also used for the
composer's emoji button, `ChatMediaPreview` renders real attachment previews,
a ported 3-state `deliveryTick()` (see `DeliveryStatus.tsx` thresholds) drives
the tick icon, and `SignalRecordingBar` replaces the plain recording row. The
keyboard gap is fixed via `Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))`,
which combines insets by max instead of summing them. Covered by
`ChatMessageGesturesTest`, `ChatReactionOverlayTest`, and the new
`DeliveryTickTest`.
Path `/ws`, `maxPayload 64KB`. Auth precedence:
cookie `TENANT_COOKIE` → **`?token=<jwt>` (what Android uses)** →
`Sec-WebSocket-Protocol`. **No in-band handshake message.**
`verifyClient` allows a missing `Origin` (OkHttp), the legacy custom scheme, `aino://`,
same-host, `CORS_ORIGIN` entries, dev localhost.
Keep `contracts/realtime-events.json` in sync via
`scripts/check-realtime-parity.mjs`.

---

### PHASE R — Stabilisation audit (2026-09-25)

A code audit of the uncommitted tree against this plan found several items
marked DONE that were broken on device. Root causes and fixes:

| ID  | Defect (root cause)                                                                                                                                                                              | Fix                                                                                                                                                                                                                       | Status |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| R1  | Tapping **More** broke the tab bar: `MoreSheet` lived inside the Scaffold `bottomBar` slot, so the measured bar grew by the sheet + 74dp and the bar jumped up; no outside-tap/back dismissal.    | More and Profile popups moved to one overlay layer above the Scaffold (scrim composed *below* the sheet, `BackHandler`, 160ms slide/fade). `More` NavHost placeholder route removed. Tab bar border is top-only + 150ms tint animation. | DONE   |
| R2  | Profile menu rows were untappable (scrim drawn *over* the sheet); "Edit Profile" was a no-op; "Enroll face" navigated to an unregistered route (**crash**). API Probe unreachable.            | Scrim order fixed; `profile` route registered (stop-gap Edit Profile until P5.2); Enroll face lands there; Notification Sounds row hidden until P5.4 (no dead rows); API Probe listed in More for debug builds.            | DONE   |
| R3  | "Refresh conversations", thread "Refresh messages" and "Refresh Profile" buttons — leftovers from pre-parity screens; the web has none.                                                           | All removed. `PullToRefreshBox` on Home, Chat list/calls, Tasks and Attendance; app-resume refresh of the visible route in `AinoApp` (web focus refetch); realtime refresh unchanged. Dashboard refresh now coalesces instead of dropping mid-load requests (manager approvals could stay empty). | DONE   |
| R4  | **Gap between keyboard and composer**: no `windowSoftInputMode`, so with edge-to-edge the system could pan the window *and* the composer's IME padding lifted it again; thread list not bottom-anchored. | `adjustResize` on `MainActivity`; thread is `LazyColumn(reverseLayout = true)` (Signal-style anchoring), auto load-older at the top, scroll-to-bottom FAB with unseen count; removed double status-bar padding on the chat selection header and a double nav inset on the Tasks FAB. | DONE   |
| R5  | Voice notes: one boolean, deprecated `MediaRecorder()`, **no pause/resume**, fake static waveform, no preview, start errors swallowed.                                                        | `VoiceNoteRecorder` + pure `VoicePhase.reduce` state machine: hold to record, slide left to cancel, slide up to lock; locked → pause/resume, live amplitude waveform, stop → draft with play/pause/seek/delete, send. <1s release discards with a hint; 1h cap. Upload pins `audio/mp4`. | DONE   |
| R6  | Media preview: `onOpen` never passed (taps did nothing), no viewer, full-size `BitmapFactory` decode per bubble with a new `OkHttpClient` each (no cache, OOM risk), only `/uploads/…` URLs resolved, no video poster/player, bubbles printed "Attachment: file.m4a", uploads sent with no preview. | Shared Coil `ImageLoader` (auth headers, memory+disk cache, downsampling, video frames); every relative URL form resolves to the server origin; `ChatMediaViewer` (pager, pinch/double-tap zoom, Media3 video, share); documents download once via the auth client and open with `ACTION_VIEW`; pre-send `AttachmentPreviewDialog` with caption; caption rendered under media. | DONE   |
| R7  | Audio playback: a `MediaPlayer` prepared per bubble on compose, several could play at once, no speed.                                                                                           | One `SharedAudioPlayer` (Media3) in `AppContainer`: one note at a time, 100ms progress, tap/drag seek, 1×/1.5×/2× (web `SPEEDS`), audio focus + becoming-noisy.                                                             | DONE   |
| R8  | Chat features present on web but missing/different on Android.                                                                                                                                   | Added: @mentions (web `MentionInput` rules), per-conversation drafts (web key format), unread divider, web long-press menu order/labels (Copy, Unpin/Unsave, Edit rules), composer "+" menu (Poll for groups, Attach file) with `PollCreator` port, web placeholders, `ConversationInfoPanel` parity (Call/Video/Search, group settings & members with rename/remove/leave, shared files, pinned, saved, block/unblock, clear chat), New group, direct chats open immediately. Plus: sender-side link previews and mention ids over the web's WS `chat_message` frame (REST send drops both; plain text keeps the offline outbox), received link-preview cards, message selection bar (copy, forward, pin, save, delete for me / everyone), reply/edit draft restore, pinned/saved/search jump-to-message with highlight, saved list "in {conversation}" + unsave, byte-level upload progress (`ApiRequest.onUploadProgress`). | DONE   |
| R9  | Every ViewModel/worker built its own `OkHttpClient` + `RefreshingApiClient`; the refresh lock is per instance, so concurrent 401s in two VMs could race two refreshes and sign the user out.   | `core/AppContainer` holds one token store, HTTP client, API client, media client, image loader and audio player; all factories/workers use it.                                                                            | DONE   |
| R10 | Mojibake in UI strings (`Loadingâ€¦`, `Â·`, `â€”`) from double-encoded sources, and 4 cp1252 files compiled as UTF-8 (`�`).                                                                        | All sources re-encoded as UTF-8 and repaired.                                                                                                                                                                             | DONE   |

R8 notes: the web has no message-info/read-by view, and its Saved view is
global (not per conversation), so neither is added. Jump-to-message only
scrolls within already-loaded history. Link previews and mentions need a live
socket; offline sends go through the REST outbox without them (a server change
to accept `linkPreview`/`mentions` on `POST /chat/conversations/:id/messages`
would close that gap).

| ID  | Defect (root cause)                                                                                                                                                                              | Fix                                                                                                                                                                                                                       | Status |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| R11 | **Clock-in not working.** (1) `office_wifi_bssids` is JSONB `{bssid,label,…}` objects; Android decoded `List<String>`, so the whole org policy failed and fell back to "verification off" — Login skipped the verify sheet and the server rejected it with `LOCATION_REQUIRED`. (2) Android 12+ ignores a FINE-only location request, so the permission never showed and the sheet hung on "Collecting office signals". (3) `ACCESS_WIFI_STATE` missing, so the BSSID was never readable. (4) Errors/results were written to Attendance state that Home never displayed. (5) A slow page load could overwrite the fresh post-clock-in status (looked like the clock-in failed; a retry then hit "Already logged in"). (6) Login was disabled/"Logging in..." during every attendance page load. (7) GPS-only fix with no timeout hung indoors. (8) Biometric `STRONG`-only prompt failed silently on devices without a strong sensor; a denied camera blocked the verify button. | Lenient `BssidListSerializer`; FINE+COARSE requested together; `ACCESS_WIFI_STATE`; Home shows attendance result/error toasts; `statusVersion` guards stale reloads and triggers a dashboard reload; separate `clockBusy` flag; policy re-fetched on tap when missing/degraded; fused → network → GPS with timeouts and a fresh cached fix; biometric **or screen lock**, with in-sheet errors; permission denial shown in the sheet; verify button always available; server-worded geofence/accuracy messages. `WorkTimerCard` ported fully (stats, Remaining/Breaks, ETA, overtime, progress colours, Resume + Logout on break, logout confirmation, ✓). Attendance page loads now coalesce instead of dropping month changes. | DONE   |

Regression checks: `ChatVoiceAndMediaTest` (voice state machine, amplitude,
URL resolution, speeds, mentions, unread divider, link detection, WS frame),
`AttendanceVerifyTest` (office-proof messages, fix selection), `ResponseDecodeTest`
(object BSSIDs, chat link previews, leave policies, manual entries); full unit suite green.

---

### PHASE 5 — Profile / account surface

There is **no** `Profile.tsx` or `Settings.tsx` on web. The surface is:
`components/navbar/ProfileMenu.tsx` + `EditProfileModal.tsx` +
`NotificationSoundsModal.tsx` + `StatusPicker` popover +
`pages/profile/FaceEnrollment.tsx` (standalone `/profile/face` route).

| ID    | Task                                                                                                | Status |
| ----- | --------------------------------------------------------------------------------------------------- | ------ |
| P5.0  | Profile as a full page (top-bar avatar → `profile`), shared authenticated `UserAvatar`              | DONE   |
| P5.1  | `StatusPicker` — options + dot colours + `GET/PUT /api/me/status`                                   | DONE   |
| P5.2  | `EditProfileModal` — exact fields + `PUT /api/profile`                                              | DONE   |
| P5.3  | Avatar upload/delete — `POST/DELETE /api/profile/avatar`                                            | DONE   |
| P5.4  | `NotificationSoundsModal` — `GET/PUT /api/profile/notification-prefs`                               | DONE   |
| P5.5  | Face enrollment — `POST/DELETE /api/profile/face-enroll`, `GET /api/profile/face-status`            | DONE ¹ |
| P5.6  | Change email — `PUT /api/profile/email`                                                             | DONE   |
| P5.7  | Theme toggle persistence + `GET/PUT /api/tracker/theme`                                             | DONE   |
| P5.8  | Presence preference — `PUT /api/me/status/presence-preference`, `POST /api/me/status/activity-ping` | DONE   |
| P5.9  | Logout parity — `POST /api/auth/logout` + local wipe                                                | DONE   |
| P5.10 | Delete account — `DELETE /api/profile`                                                              | DONE   |

¹ `POST /api/profile/face-enroll` is intentionally not called: it needs the
web's face-api.js descriptor, which Android cannot produce. Android shows the
status, clears enrolment and tells the user to enrol from the web/desktop app.

Phase 5 implementation notes (Android, 2026-09-25):

- **Deliberate deviation (product decision 2026-09-25):** tapping the top-bar
  avatar opens a full-page `ProfileScreen` (route `profile`) instead of the
  web's dropdown. It holds the dropdown content — photo + camera, name,
  `@username`, email, status/work-mode badges, an inline `StatusPicker`, then
  Edit Profile · Remove Photo · Notification Sounds · Face Enrollment ·
  Light/Dark Mode · Sign Out. Edit Profile (`profile/edit`), Notification
  Sounds (`profile/sounds`) and Face Enrollment (`profile/face`) are full-screen
  sub-pages. All four hide the shell top/bottom bars (`isFullScreenRoute`).
  `ProfileMenuSheet` and the Profile popup were removed; the old Profile
  "Search" tab was dropped (global search belongs to P7.6).
- **Avatars:** `core/designsystem/component/UserAvatar` loads photos through
  the shared authenticated Coil loader (`/uploads` is behind auth), with the
  initials circle as placeholder/fallback (no web skeleton animation). Used by
  the top bar, Profile, chat list/header/info/members/user search and the
  incoming-call screen. Chat message bubbles still have no avatar slot.
- **Status v2:** payload is camelCase (`userId`, `manualStatus`,
  `presencePreference` …). Loaded on tenant sign-in, patched by the
  `user_status` WS event, `activity-ping` throttled to one per minute on real
  input. The top-bar dot and Profile badge use the `ProfileMenu` map (including
  "Working" / "Working Remotely" while clocked in).
- **Edit Profile** mirrors `EditProfileModal` sections and copy: Name &
  Username, Email Address, Change Password (the rotated token is kept),
  Biometric Login (this device) — Android adaptation of the desktop section —
  Devices with biometric sign-in (`GET /api/auth/biometric`,
  `DELETE /api/auth/biometric/:id`), Danger Zone.
- **Avatar upload** uses the Android photo picker; jpg/png/webp/gif ≤ 5 MB are
  sent untouched like the web, anything else (HEIC, large camera photos) is
  downscaled to 1024 px JPEG instead of rejected. Upload shows a native
  progress spinner.
- **Notification Sounds** syncs the web prefs. Mute all / Play when focused /
  Play on send / Read receipts apply on Android; "Incoming call ringtone" opens
  the system ringtone picker (used by `CallRingService`), "New message" and
  "Mention / @-tag" open the Messages channel settings. Volume sliders,
  "Outgoing call tone" and "Reaction" have no Android counterpart and are
  hidden. Server fix: `validateNotificationPrefs` now accepts `readReceipts`
  (it was silently dropped although chat queries read it).
- **Theme:** server theme applied once on tenant sign-in, `PUT` on toggle,
  `theme_changed` WS applies changes from other devices.
- **Sign out:** confirmation dialog; office sessions must clock out from the
  Work Timer first, remote sessions are clocked out automatically (web
  `confirmSignOut`); sign-out also clears the image caches and sound prefs.
  The web's "Switch to Platform Console" row is not ported (Android does not
  support the platform realm).

Endpoint coverage after Phase 5: **106/464** (`docs/PARITY_MATRIX.md`).

---

### PHASE 6 — Tasks · Agile · Sprints · Projects (`A-104`, ~90 ops)

| ID   | Task                                                                                           | Status |
| ---- | ---------------------------------------------------------------------------------------------- | ------ |
| P6.1 | Tasks list/board parity with `pages/Tasks.tsx` + `Tasks.module.css`                            | DONE   |
| P6.2 | Task detail: comments, acceptance criteria, dependencies, history, git refs, custom fields     | DONE ¹ |
| P6.3 | Backlog + carry-forward + labels management                                                    | DONE   |
| P6.4 | Sprints: list, start/pause/resume/complete, burndown, CFD, cycle-time, velocity, retrospective | DONE ² |
| P6.5 | Projects CRUD + archive + project tasks                                                        | DONE   |
| P6.6 | Agile settings: work item types, workflow states, reorder, permissions/grants/requests         | DONE ³ |
| P6.7 | Service desk tickets + stats                                                                   | DONE ⁴ |
| P6.8 | `SprintInsights` page                                                                          | DONE   |

¹ Git refs (`/tasks/:id/git`) have API wrappers on the web but no UI anywhere, so
they are not ported (no invented UI). Custom-field *values* are edited in the
detail; custom-field *definitions* stay in P10.5.
² Sprint create/update/delete and `carried-over` are unused by the web UI and
stay pending; pause/resume live in Organization → Teams (P7.5).
³ The web removed the access request/grant UI ("access is purely role-based
now"); the endpoints are in `AgileSettingsRepository` and tested, and only
`permissions/me` drives the page. Reorder is repository-only: on the current
server `PUT …/reorder` is registered after `PUT …/:id`, so it always answers
400 "Invalid id" — **server bug to fix in `server/routes/agile.ts`** (move the
reorder routes above `/:id`) before adding up/down controls.
⁴ `GET/PATCH /service-desk/tickets/:id` are admin-console calls (P10.2).

Phase 6 implementation notes (Android, 2026-09-25):

- **Tasks page** (`feature/tasks`, bottom tab): the invented Today/date-strip
  planner is gone. The page is now the web's Sprint · Backlog · Service Desk
  switcher (default Backlog; Sprint only with the `agile` feature and at least
  one available sprint), the ≤480px header/toolbar (Insights, sprint select,
  Filters (n), ➕ New Ticket, Import from Backlog), "Search all tasks..." global
  search (300 ms debounce) and the filter bar (Assignee / Label / Priority /
  Status on Sprint). Carry-forward runs once per local day with the 4 s banner.
- **Sprint board:** progress card (counts, story points / unestimated /
  blocked from `sprints/:id/stats`), `SprintLifecycleControls` (Start ·
  Complete + rollover to Backlog or another sprint, team_lead+), the
  SprintImportPanel, and the tenant's workflow columns stacked vertically
  (`@media (max-width: 640px)`), WIP counts when enabled. **Deliberate
  interaction change:** desktop drag-and-drop becomes long-press → "Move to"
  (same "Change Status" confirm, optimistic with WIP rollback); the card hint
  reads "⠿ hold to move".
- **Backlog:** summary chips (Total / priorities toggle the filter), sort,
  pagination (10/25/50/100), New Backlog Ticket form (assignee, due date,
  sprint → due = sprint end, labels, type, project, story points, priority),
  inline Schedule. Labels management already lives in Organization (P7.5).
- **Task detail** is the full-screen `tasks/detail` route (the modal is full
  screen ≤768px): view/edit mode, Move to (sprint tickets), Schedule to Day /
  Move to Backlog, blocker, acceptance criteria, parent/children (Epics) with
  in-place navigation, dependencies with quicksearch, custom fields (with the
  `custom_fields` feature), Comments · History tabs. Comments support
  @mentions (`mention-chip` spans), one attachment (multipart `file`), edit and
  delete; the card 💬 opens the same thread in a bottom sheet. Descriptions are
  edited as plain text; untouched text keeps its original HTML. Web
  `/tasks?task=&tab=&sprint_id=` links now land via `tasks/link`.
- **Service Desk** tab: stats chips, type filter, New Service Desk Ticket,
  expandable tickets, owner cancel while open.
- **Sprint Insights** (`sprint-insights`, Tasks tab stays selected): summary,
  tickets, burndown, velocity, cumulative flow, cycle & lead time and the
  retrospective editor, all drawn natively; the web refresh button is replaced
  by pull-to-refresh.
- **Admin** (More → Admin) is no longer a placeholder: it lists the sections
  Android has so far — Structure → Agile Config (`admin/agile`) and Projects
  (`admin/projects`) — without dead rows. P10.1 fills in the rest.
- Refresh: pull-to-refresh on every surface, app-resume refetch, and
  `task_assigned` reloads the visible tab (the only task event the server
  emits). `AinoUser` now decodes `team_id` / `team_name` from `/profile`.

Endpoint coverage after Phase 6: **227/464 (48.9%)** (`docs/PARITY_MATRIX.md`).

---

### PHASE 7 — Calendar · Notes · Notifications · Organization · Search (`A-105`, ~60 ops)

| ID   | Task                                                                                                            | Status |
| ---- | --------------------------------------------------------------------------------------------------------------- | ------ |
| P7.1 | Calendar page — `GET/POST/PUT/DELETE /api/calendar`                                                             | DONE   |
| P7.2 | Notes: page tree, editor, daily prefill, 1:1 prefill, history/snapshots, links, mentions, sharing, public notes | DONE ¹ |
| P7.3 | Notes embeds: sprint-embed, time-summary, search-events/meetings/tasks                                          | DONE   |
| P7.4 | Notifications centre: list, read, read-all, delete, metrics                                                     | DONE   |
| P7.5 | Organization: chart, departments, teams, roles, members, invite, settings, sprint-config                        | DONE ² |
| P7.6 | Global search — `GET /api/search`                                                                               | DONE   |

¹ Public notes: sharing creates/revokes the public link and hands it to the
Android share sheet; the anonymous `/public/notes/:token` viewer stays web-only.
² Scoped to the web `/organization` page (decision 2026-09-25): My Department,
My Team (incl. sprint-config), Org Chart, Task Labels. Salary Slips moves to
P10.3; org roles / invite / members / remove-member / settings live in Admin →
My Organization on the web and move to P10.1.

Phase 7 implementation notes (Android, 2026-09-25):

- **Calendar** (`feature/calendar`, bottom tab): `CalendarPage` + `Calendar.tsx`
  day / week / month views at the ≤768px layout (40dp gutter, 60dp hours, now
  line, past-slot shading, overlap columns), and `EventFormModal` as a
  full-screen dialog — 15-minute time menus with the web's past-slot rules,
  native date pickers, all-day, "Custom days this week", Link to Task. With the
  `meetings` feature it creates a meeting per occurrence (required/optional
  people via `/chat/search`, 500 ms conflict check, mute/screen-share settings)
  and shows meeting details on existing events; **Join** opens a
  `meeting/{code}` placeholder until P9. Refetches on `calendar_refresh`,
  `meeting_updated`, `meeting_cancelled`, resume and pull-to-refresh.
- **Notes** (`feature/notes`): notes home in the shell, editor
  `notes/{pageId}` and history `notes/history/{pageId}` full screen. Native
  Compose editor for the web's Quill HTML (paragraphs, H1–H6, quotes, code
  blocks, dividers, nested bullet/numbered/check lists; bold/italic/underline/
  strike/inline code/links/page links/@mentions/date chips). Images and callouts
  render statically, tables as a text grid; toggles, draw.io, math, audio,
  video/iframes and unknown blocks are preserved byte-for-byte as read-only
  cards. Sprint and time-tracking embeds render live. Saves merge onto a freshly
  fetched notebook (only locally changed pages/fields), debounce like the web
  (10 s content, ~300 ms structure), flush on leaving the editor / app stop and
  keep an on-disk draft. Not ported: live collaboration cursors, draw.io/math/
  mermaid editing, audio recording, inserting tables/toggles/images/callouts,
  the Todo app (its data is preserved), reactions, page properties,
  import/export, undo/redo.
- **Notifications** (`feature/notifications`): the top-bar bell opens a
  full-screen page (the web dropdown) with the unread badge on the bell; mark
  read, "Mark all read", delete (swipe or ✕). 30 s poll while foregrounded, plus
  refetch on `notification`, `leave_update`, `task_assigned`,
  `approval_update`, `meeting_invite`, `meeting_started`.
  Metrics: Attendance → Analytics shows the web's "Notification Routing" card
  (`GET /notifications/metrics`); Android posts routing telemetry
  (`notification_tapped` → `route_consumed` / `route_failed`) to
  `/notifications/metrics/events`. Tapped pushes are now actually routed
  (chat → thread, others → notifications page) — previously the extras were
  ignored. Web fix: the Analytics card read `routingAttempts` /
  `successfulRoutes` / `p95LatencyMs` at the top level although the server nests
  them under `counts` / `latency` ("undefined/undefined routes").
- **Search** (`feature/search`): a search icon left of the bell opens a
  full-screen search (the web hides its navbar search at ≤768px) with the web's
  buckets, "Pages & Features" shortcuts per role and result links.
- **Organization** (`feature/organization`): tables become cards on phones,
  member tooltips become tap-to-open details, sprint duration uses a 1–8 picker;
  includes the Pause/Resume Sprint action from the team sprint-config editor
  (`sprints/active`, `sprints/:id/pause|resume`) and task labels
  (`tasks/labels/manage`, `POST/PUT/DELETE tasks/labels`).
- Web links from notifications, search and notes map to Android routes through
  `webLinkToRoute` (`?task=` / `?taskId=` / `?tab=` / `?sprint_id=` open the
  task link route since P6).

Endpoint coverage after Phase 7: **161/464** (`docs/PARITY_MATRIX.md`).

---

### PHASE 8 — Manager / My Team (`A-106`, 12 ops)

| ID   | Task                                                                                                   | Status |
| ---- | ------------------------------------------------------------------------------------------------------ | ------ |
| P8.1 | Approvals — list, approve, reject (with reason), bulk approve/reject                                    | DONE   |
| P8.2 | My Requests — the signed-in manager's own submitted approval requests                                  | DONE   |
| P8.3 | Team Analytics — range/custom filters, search, department filter, sort, summary cards, per-member trend | DONE   |
| P8.4 | Team Attendance — status-grouped roster for a given date                                               | DONE   |
| P8.5 | Member detail — Overview / Leaves / Requests / Hours (+ Tasks, Android-only) sub-screens                | DONE ¹ |

¹ The web's `EmployeeDashboard.tsx` has 4 sub-tabs (Overview/Leaves/Requests/
Hours); Android adds a 5th "Tasks" sub-tab to surface
`GET /manager/member/:userId/tasks`, which the server implements but the web
UI itself never renders.

Phase 8 implementation notes (Android, 2026-09-26):

- **Manager** (`feature/manager`, More-sheet destination `manager`, gated by
  the existing `availableMoreDestinations` role/`hasReports` check): a tab
  shell (Team Attendance · Approvals · Analytics · My Requests, mirroring
  `index.tsx`) rendered inside the app shell, `Section<T>` stale-while-revalidate
  state per tab so a failed refetch never blanks already-loaded content
  (WEB_PARITY_PLAN §5 rule 6), and pull-to-refresh / on-resume refetch of the
  active tab only.
- **Approvals**: pending/approved/rejected/all filter, per-row and
  select-all checkboxes, bulk approve/reject, and a reject-reason dialog;
  approve/reject/bulk all refetch the approvals list on success and — matching
  `ApprovalsTab.tsx` exactly — surface no visible success/failure notice (the
  web only `console.error`s failures).
- **Team Analytics**: This Week/Month/Quarter/custom-range selector, search,
  department filter, column sort, summary cards (members, avg hours, planner
  completed, target met, punctuality, pending approvals), and a tap-to-expand
  member card with the `MemberExpandedCard.tsx` stat grid (email, department,
  team, days worked, target met, avg break, work mode split, utilization,
  streak, task completion, leave breakdown) plus a mini trend sparkline.
  Deferred: the web's "Export Team" `ExportButton` (CSV export via
  `exportTeamAnalytics`) is not ported — no other P1–P8 phase has shipped a
  file-export/share affordance yet, so this is deferred alongside the rest of
  export tooling rather than introduced ad hoc here.
- **Team Attendance**: date picker plus four status-grouped sections (🟢
  Working / 🟡 Away / ⚪ Not Started / 🔴 On Leave) with per-member hours,
  work-mode, current task and leave-type rows.
- **My Requests**: the signed-in manager's own submitted approval requests
  (type, details, status, submitted date, reviewed by).
- **Member detail** (`manager/member/{userId}`, full screen, own back button,
  shares the parent `ManagerViewModel` instance via
  `nav.getBackStackEntry(AinoDestination.Manager.route)` rather than a new
  ViewModel): header (avatar, name, role, email, department, team) plus 5
  sub-tabs — Overview (quick stats, weekly trend bars, 30-day performance,
  planner/leave-balance stats, today's planner, recent leaves/requests),
  Leaves, Requests, Hours (last 30 days, work-mode icon), and Tasks
  (Android-only; see ¹ above).
- Reuses `feature.organization.roleLabel`/`ROLE_LABELS` and
  `feature.attendance.getLeaveType` (identical `LEAVE_ICONS` mapping) rather
  than duplicating them.

Endpoint coverage after Phase 8: **237/464 (51.1%)** (`docs/PARITY_MATRIX.md`).

---

### PHASE 9 — Meetings & calls (`A-107`, 13 ops)

| ID   | Task                                                                                                                   | Status |
| ---- | ---------------------------------------------------------------------------------------------------------------------- | ------ |
| P9.1 | Shared WebRTC core — `RtcPeer` (perfect negotiation), `LocalMedia` (mic/camera), `CallAudio`, `VideoRenderer`           | DONE   |
| P9.2 | 1:1 call media — in-call screen, offer/answer/ICE over `call_signal`, mute/camera state, timeouts, end/cancel, PiP      | DONE   |
| P9.3 | Meetings API — all 13 `/meetings` operations (+ `GET /health` for the lobby network badge)                              | DONE ¹ |
| P9.4 | `MeetingJoin` lobby, `MeetingRoom` (mesh, grid, bottom bar, chat, participants), `HuddleAutoJoin`, `MeetingPiP`         | DONE ² |
| P9.5 | Group calls — group chats start a huddle; group-call rings join `/huddle/:code`; `huddle_decline`                        | DONE   |
| P9.6 | `GlobalMeetingNotification` card and the chat `MeetingCard`                                                             | DONE   |

¹ The web UI only calls `GET /:code`, `/:code/messages`, create and
check-conflicts. List / update / cancel / participant CRUD / HLS have no web
screen, so Android implements them in `MeetingRepository` with decode tests
and **no UI** (product decision 2026-09-25, "no invented UI"). The web's HLS
hooks (`useHlsBroadcast`, `HlsViewer`) are never mounted.

² Scope confirmed 2026-09-25: screen share, local recording and reactions
are not ported (the web never renders reactions in the room either).

Phase 9 implementation notes (Android, 2026-09-25):

- **Before this phase Android had no call media at all**: `PeerConnectionSession`
  was never instantiated and signals were quarantined, so a 1:1 call rang and
  then showed "Secure media connection will be enabled in the WebRTC stage".
  It is replaced by the protocol-agnostic `core/call/webrtc/RtcPeer`; the
  dead `PeerConnectionSession` / `IceRecoveryController` are deleted.
- **Shared core** (`core/call/webrtc`): `WebRtcRuntime.shared()` is one
  factory + EGL context per process, with a `JavaAudioDeviceModule` (hardware
  AEC/NS) whose samples feed the mic level used for `meeting_audio_level`.
  `LocalMedia` = web `getUserMedia` (640×480@24, front camera); camera off
  stops capture (LED off) instead of renegotiating. `CallAudio` = in-call mode,
  voice focus, earpiece/speaker (Signal's audio manager, built-in devices only).
  `ICE`: `GET chat/ice-config`, falling back to the web's `FALLBACK_ICE_SERVERS`.
  Permissions (`RECORD_AUDIO`, `CAMERA`) are asked at the point of use; a
  denied camera joins with video off and a denied mic joins muted (the web's
  `getUserMedia` fallback). `ActiveCallService` only claims the camera /
  microphone FGS types that are actually granted.
- **1:1 calls** (`core/call/ActiveCallController` + `ActiveCallScreen`): the
  caller offers after `call_accepted` (re-sends on `call_peer_ready`, rebuilds
  on `call_reconnect`); the callee is polite and sends `call_subscribe` +
  `call_ready` after `POST chat/calls/:id/accept`. `audio-state` / `video-state`
  signals drive the peer's mute badge and avatar. Timeouts match
  `call/index.tsx`: 35 s ringing → "No answer", 30 s connecting/reconnecting
  → "Couldn't connect", shown 1.8 s then `call_end`; busy → "`{name}` is on
  another call". Hang-up sends `call_end` (REST `chat/calls/:id/end` if the
  socket is down) or `call_cancel` before an id exists. One ICE restart after
  2 s disconnected. Layout follows `CallOverlay.module.css` ≤600px (caller
  card, full-bleed remote video, 120×180 self preview that swaps on tap, the
  "You" card for voice, the blurred control pill). Camera flip and speaker are
  Android additions (the web switches devices through pickers); the web's hold,
  noise toggle, screen share, recording, reactions and in-call chat are not
  ported. Incoming ring and in-call screens now overlay the shell instead of
  replacing it, so the NavHost (and a minimised meeting) survive a call.
- **Meetings** (`feature/meeting`): `MeetingSession` is the process-wide
  `MeetingContext` + `useMeetingState` — full mesh, existing members offer to
  a newcomer, collisions resolved by the web's *lexicographic string*
  `selfId > remoteId` rule. Join = `meeting_join` + `meeting_subscribe` (+
  `meeting_track_state` 300 ms later, re-join at 2.5 s, "Unable to join" at
  15 s); `meeting_ready` once peers exist; `meeting_peer_ready` re-sends a
  pending offer. Mute / camera / hand / mute-participant / mute-all /
  add-participant / leave / end-for-all use the server frames verbatim.
  Chat: REST history on join, optimistic rows with Sending… / Uploading… /
  Failed (tap to retry), `meeting_message_ack` / `_error`, attachments (10 MB,
  uploaded to the meeting conversation then sent as `file_url`). A new socket
  closes every peer, re-joins, re-sends unacked chat and asks
  `meeting_chat_replay` from the highest id. ICE restart after 2 s
  disconnected (max 3 per peer), 30 s → "Couldn't connect" + Retry. Video
  bitrate is capped by mesh size (500k / 300k / 150k) with 48 kbps audio.
  Remote screen shares from web users are received and shown in the presenter
  layout (sending is out of scope).
- **Screens**: `/meeting/:code` lobby (preview, mic/camera, network badge from
  `GET /health` RTT, code copy, "Hosted by", Join now / Rejoin meeting),
  `/meeting/:code/room` (≤480px header, connection banner, count-based grid,
  presenter strip, tiles with speaking / active-speaker rings, status pill +
  Retry, raised hand, mute badge, 60px bottom bar, More menu, full-body Chat /
  Participants panels, Joining / Unable to join overlays), `/huddle/:code`
  (no lobby, voice calls join camera-off). Minimise returns to Home and shows
  the draggable `MeetingPiP` card; leaving the app during a call or meeting
  enters system PiP (active speaker only). Android additions in the room's
  More menu: Switch camera, Speaker. Not ported: device pickers / speaker test
  and the ICE preflight banner in the lobby, tile quality dots,
  `meeting_request_quality` and high-count video demotion (the mesh still
  caps bitrates), Bluetooth / wired-headset routing.
- **Group calls**: the chat header's call buttons in a group create a huddle
  (`POST /meetings` `{huddle:true, settings:{callType}}`) and open
  `/huddle/:code`; the server rings members. A ring carrying `meetingCode`
  (socket or push — `CallRingService` / `CallActionActivity` now forward it)
  answers into `/huddle/:code` and declines with `huddle_decline`.
- `webLinkToRoute` maps `/meeting/:code/room` and `/huddle/:code`; ViewModels
  and chat cards navigate through `core/navigation/RouteRequests`.
  `openAuthenticatedFile` moved from Tasks to `core/media` so meeting chat
  files reuse it.
- A 1:1 call and a meeting may overlap: `ActiveCallService` and `CallAudio`
  are owner-counted so ending one never drops the other's foreground service
  or audio mode. The realtime socket is Activity-scoped, so finishing the
  Activity (swiping the task away) leaves the meeting / hangs up the call
  rather than keeping media alive without signaling.
- **Fix (2026-09-25): answering a desktop → phone call tore it down.** The
  server sends `call_handled_elsewhere {action:"accepted"}` to *every* session
  of the accepting user (socket and data push), including the phone that just
  accepted; the web ignores it there, Android ended the call. It now ends a
  session only while it is still Ringing (`CallSessionController`,
  `AinoFirebaseMessagingService` via `acceptedHere`).
- **Rings**: a socket `call_incoming` also starts `CallRingService` (ringtone,
  CallStyle notification, full-screen intent when backgrounded/locked),
  de-duplicated per call id with the push. Push requires Firebase: local
  builds without `app/google-services.json` never register a token (now
  logged as `AinoPush` warnings); CI writes it from
  `ANDROID_GOOGLE_SERVICES_JSON`, which must belong to the server's Firebase
  project with an Android app for `app.aino.mobile`. The chat push validator
  no longer rejects group messages (`"{sender}: {150-char preview}"` bodies)
  or a zero unread count.
- **Call screens follow Signal's layout with the web's copy**: incoming =
  blurred avatar backdrop, name, "Incoming voice/video call...", Decline /
  Accept, plus Signal's "Answer without video" for video calls; in call = top
  bar (minimise → PiP, name, status/duration, peer mute), blurred avatar or
  remote video (own camera full-screen while a video call rings), a draggable
  corner self preview with flip and tap-to-swap, bottom Speaker · Camera ·
  Mic · End toggles (white = on), chrome auto-hides on video, and an outgoing
  ringback tone while "Ringing...". Decline and missed rings close the screen
  immediately.

Endpoint coverage after Phase 9: **248/464 (53.4%)** (`docs/PARITY_MATRIX.md`).

---

### PHASE 10 — Admin · Tenants · Compensation (`A-108` / `A-110`, ~150 ops)

| ID    | Task                                                                                                                                                                      | Status |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| P10.1 | Admin: users, roles, announcements, invite codes, audit logs, pay periods, registration settings, role requests, stats, task labels, organizations; plus Admin → My Organization (org roles, invite, members, remove member, org settings incl. office geofences — moved from P7.5) | TODO   |
| P10.2 | Tenants console: CRUD, features, limits, plan, suspend/reactivate, seed, stats, users, impersonation, access requests, plan catalog, platform config, platform users      | TODO   |
| P10.3 | Compensation/payroll: employees, CTC config, templates, salary slips (incl. the Organization page's "Salary Slips" tab — moved from P7.5), PDF, publish, bulk publish, payroll run, disbursements, bank details + verification, payment config | TODO   |
| P10.4 | Branding: logo, email templates, preview                                                                                                                                  | TODO   |
| P10.5 | Custom fields                                                                                                                                                             | TODO   |
| P10.6 | Integrations: GitHub OAuth, repos, webhooks                                                                                                                               | TODO   |
| P10.7 | Exports: my/team analytics, leaves, tasks, payroll hours                                                                                                                  | TODO   |
| P10.8 | Platform access requests                                                                                                                                                  | TODO   |
| P10.9 | Remaining auth: register, forgot/reset password, refresh, handoff, switch-realm, registration-mode, webauthn, biometric list/delete                                       | TODO   |

---

## 4. Cross-cutting workstreams

| ID  | Task                                                                                                                               | Status |
| --- | ---------------------------------------------------------------------------------------------------------------------------------- | ------ |
| X.1 | Regenerate `docs/PARITY_MATRIX.md` after every phase (`node scripts/check-endpoint-parity.mjs`); coverage % is the phase exit gate | TODO   |
| X.2 | Keep `contracts/realtime-events.json` current (`scripts/check-realtime-parity.mjs`)                                                | TODO   |
| X.3 | Keep `contracts/http-route-inventory.json` and `contracts/android-endpoint-coverage.json` in sync                                  | TODO   |
| X.4 | Screenshot-diff harness: render each Compose screen at 430×932 (both themes) and diff against the web at the same viewport         | TODO   |
| X.5 | Per-repository decode tests against captured real payloads                                                                         | TODO   |
| X.6 | `scripts/check-module-boundaries.mjs`, `check-independence.mjs`, `check-source-provenance.mjs` must stay green                     | TODO   |
| X.7 | Offline/outbox behaviour preserved for every new write path (`core/db/OutboxWorker`)                                               | TODO   |
| X.8 | Accessibility: content descriptions mirroring the web's `aria-label`s                                                              | TODO   |

---

## 5. Conventions for every task

1. **Copy is verbatim.** Every user-visible string is taken character-for-character
   from the web source. No paraphrasing, no "improvements".
2. **Icons map 1:1.** lucide → the closest `Icons.Outlined.*`; where no match
   exists, add a vector drawable traced from the lucide SVG. Sizes match the
   web's `size={n}` prop in dp.
3. **Spacing is converted, not guessed.** `1rem = 16.dp`, `0.62rem = 9.92.sp`,
   etc. Keep a `rem()` helper so the conversion is auditable.
4. **Mobile overrides win.** Where a `@media (max-width: 768px)` or `480px` rule
   exists it supersedes the desktop rule; 430px is below both.
5. **Responses are bare.** No `{success,data}` envelope except
   `/notifications/announcements` and `/auth/login`.
6. **Partial failure never blanks a screen.** Mirror `Promise.allSettled`
   semantics — render what resolved, surface what failed inline.
7. **Fail-closed gating, fail-loud diagnostics.** A missing feature hides a
   section (matching web), but a _failure to load features_ must be visible.
8. **No new invented UI.** If it is not in the web app at 430px, it does not
   ship. The cream dashboard, the 7-day date strip, the Work-Location toggle and
   the `Today` attendance tab are all removed for this reason.

---

## 6. Deletions required for parity

| File / symbol                                                                  | Reason                             |
| ------------------------------------------------------------------------------ | ---------------------------------- |
| `feature/home/HomeScreen.kt` → `DashboardIllustration` Canvas (lines ~179-231) | No web counterpart                 |
| `feature/home/HomeScreen.kt` → `DashboardDateStrip` (~234-263)                 | No web counterpart                 |
| `feature/home/HomeScreen.kt` → `WorkLocationCard` (~358-402)                   | No web counterpart                 |
| `feature/home/HomeScreen.kt` → cream palette constants (67-74)                 | Wrong design language              |
| `feature/home/HomeScreen.kt` → `"Refresh dashboard"` text button               | No web counterpart                 |
| `feature/attendance/AttendanceScreen.kt` → `Today` tab                         | Web has 4 tabs, not 5              |
| `core/navigation/AinoApp.kt` → `MoreScreen` composable                         | Replaced by anchored popup         |
| `core/navigation/AinoApp.kt` → `destinationSubtitle()`                         | More rows have no subtitles on web |
| `AinoDestination.Profile` as a NavHost page — **reversed 2026-09-25**: Profile is a full page by product decision (Phase 5) | Web has no profile page            |
| `AinoDestination.Attendance.inBottomBar = true`                                | Attendance is a More item on web   |
| `AinoAtmosphere` / `AinoGlassCard` on parity screens                           | Web has no glass aesthetic         |

---

## 7. Suggested execution order

```
P0.* → P1.* → P2.* → P3.* → P4.*          (landed; see Phase R corrections)
R1 … R10                                  (stabilisation — must stay green)
P5.* Profile / account                    (landed 2026-09-25 — 106/464)
P7.* Calendar · Notes · Notifications · Organization · Search   (landed 2026-09-25 — 161/464)
P6.* Tasks · Agile · Sprints · Projects   (landed 2026-09-25 — 227/464)
P8   Manager / My Team                    (landed 2026-09-26 — 237/464)
P9   Meetings & group calls               (landed 2026-09-25 — 248/464)
P10  Admin · Tenants · Compensation · remaining auth   (next)
X.* run continuously
```

Every feature phase exits only when: its route has no `PlaceholderScreen`,
its endpoints have decode fixtures (X.5), it has pull-to-refresh + resume +
realtime refresh (no manual Refresh buttons), and `docs/PARITY_MATRIX.md`
is regenerated (X.1).

Coverage checkpoints (`docs/PARITY_MATRIX.md`):
after P5 ≈ **120/464 (26%)**, after P6 ≈ **210 (45%)**,
after P7 ≈ **270 (58%)**, after P8 ≈ **282 (61%)**,
after P9 ≈ **295 (64%)**, after P10 ≈ **464 (100%)**.
