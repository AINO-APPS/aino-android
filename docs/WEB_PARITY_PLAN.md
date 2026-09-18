# AINO Android — Web Parity Implementation Plan

> **Goal:** make `aino-android` an exact native reproduction of the `aino-platform`
> web client as it renders at **430 × 932 (iPhone 16 Pro Max)**, across every
> route, with identical layout, typography, colour, copy, iconography,
> navigation and API behaviour.
>
> **Reference web app:** `D:\Learnings\WorkPulse-Split\aino-platform\client`
> **Reference server:** `D:\Learnings\WorkPulse-Split\aino-platform\server`
> **Target:** `d:\Learnings\WorkPulse-Split\aino-android`
>
> Created: 2026-09-18. Status legend: `TODO` / `WIP` / `DONE` / `BLOCKED`.

---

## 0. Executive summary of the current state

| Area                   | Web (430px)                                                                                                                  | Android today                                                                                                           | Verdict                                            |
| ---------------------- | ---------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------- |
| Bottom tab bar         | `Home · Calendar · Tasks · Chat · More` (64px, `--bg-secondary`)                                                             | `Home · Attendance · Tasks · Chat · More` (Material3 NavigationBar)                                                     | **Wrong tabs, wrong chrome**                       |
| Attendance entry point | inside the **More** popup                                                                                                    | a bottom tab                                                                                                            | **Wrong placement**                                |
| Profile                | `ProfileMenu` dropdown (280px) + modals, **no page**                                                                         | full-screen `ProfileScreen`                                                                                             | **Wrong pattern**                                  |
| Theme                  | dark-default token set (`#131314` / `#2383e2`), 8px radius, Inter                                                            | bespoke cream `#F6F0E4` + "glass/atmosphere"                                                                            | **Different design language**                      |
| Dashboard              | greeting + announcement carousel, WorkTimer, TodayEvents, TasksSummary, SprintProgress, PendingApprovals, EventReminderToast | greeting + hand-drawn Canvas illustration, WorkTimer, 7-day date strip, Work-Location toggle, TodayEvents, TasksPlanner | **~50% missing, ~40% invented**                    |
| Attendance tabs        | `Overview(calendar) · Leaves · Manual Entry · Analytics`                                                                     | `Today · Overview · Leaves · Manual · Analytics`                                                                        | **Extra tab, no calendar, 2 tabs not wired to VM** |
| Chat                   | list ⇄ thread with back stack, reactions, replies, ticks, typing, attachments                                                | list replaced by thread (no back stack), none of the above                                                              | **Major gaps**                                     |
| Endpoint coverage      | 464 operations                                                                                                               | 61 implemented (**13.1%**) per `docs/PARITY_MATRIX.md`                                                                  | **87% pending**                                    |

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

### ProfileMenu dropdown (replaces the current full-screen ProfileScreen)

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
| P0.1 | Guarantee `tenant_features` hydration           | TODO   |
| P0.2 | Surface gate failures instead of hiding the app | TODO   |
| P0.3 | On-device API probe screen                      | TODO   |
| P0.4 | Fix Attendance tab→ViewModel wiring bug         | TODO   |
| P0.5 | Response-decode regression tests                | TODO   |

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

---

### PHASE 1 — Design system + app shell

| ID   | Task                                                        | Status |
| ---- | ----------------------------------------------------------- | ------ |
| P1.1 | `WebTokens.kt` — port every CSS custom property             | TODO   |
| P1.2 | Typography + Inter font                                     | TODO   |
| P1.3 | `WebCard` / `.status-card` primitive                        | TODO   |
| P1.4 | Top bar rebuild (`Navbar` ≤768px)                           | TODO   |
| P1.5 | Bottom tab bar rebuild (`mobile-tab-bar`)                   | TODO   |
| P1.6 | More popup sheet                                            | TODO   |
| P1.7 | `ProfileMenuSheet` replacing `ProfileScreen`                | TODO   |
| P1.8 | Theme toggle + persistence                                  | TODO   |
| P1.9 | Retire `AinoAtmosphere` / glass styling from parity screens | TODO   |

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
| P2.1  | Delete cream design + `DashboardIllustration` Canvas + date strip + Work-Location card | TODO   |
| P2.2  | Greeting banner + announcement carousel with dots                                      | TODO   |
| P2.3  | `WorkTimerCard` 1:1 port                                                               | TODO   |
| P2.4  | `TodayEventsCard` (today + tomorrow) 1:1 port                                          | TODO   |
| P2.5  | `TasksSummary` 1:1 port                                                                | TODO   |
| P2.6  | `SprintProgressCard` (new on Android)                                                  | TODO   |
| P2.7  | `PendingApprovalsCard` (new on Android, manager-gated)                                 | TODO   |
| P2.8  | `DashboardSkeleton` port                                                               | TODO   |
| P2.9  | `EventReminderToast` + `useEventReminder` logic                                        | TODO   |
| P2.10 | `allSettled` fetch + stale-while-revalidate cache                                      | TODO   |
| P2.11 | WS invalidation + on-resume refresh                                                    | TODO   |

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
| P3.1 | Retab to the web's 4 tabs; delete the Android-only `Today` tab; move clock controls to the Dashboard timer card | TODO   |
| P3.2 | `AttendanceCalendar` month grid: day cells, status colours, legend, month nav, `refreshKey`                     | TODO   |
| P3.3 | Leaves tab: balances, application list, apply form, withdraw                                                    | TODO   |
| P3.4 | Manual Entry tab: form + list + edit                                                                            | TODO   |
| P3.5 | Analytics tab: summary tiles + `HistoryTable` with mobile card collapse                                         | TODO   |
| P3.6 | Clock in/out/break with full payload (geo, face, wifi, `work_mode`)                                             | TODO   |
| P3.7 | `ClockInVerifyModal` + `FaceCapture` equivalents                                                                | TODO   |
| P3.8 | Keep-alive tab state + hash deep links                                                                          | TODO   |

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
`in_meeting #0ea5e9`, `offline` transparent + muted ring), then
`.chatHeaderInfo` (clickable → `onOpenInfo`).

Thread pieces: `ChatMessages.tsx` (+ CSS), bubble =
`components/chat/MessageBubble.tsx` with `MessageContent.tsx`,
`DeliveryStatus.tsx`, `MessageToolbar.tsx`, `ReactionBar.tsx`,
`ReplyPreview.tsx`, `FilePreview.tsx`; composer = `pages/chat/ChatInputBar.tsx`;
info pane = `ConversationInfoPanel.tsx`.

| ID    | Task                                                                                                                                                   | Status |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------ | ------ |
| P4.1  | List ⇄ thread navigation with real back stack mirroring `mobileView`                                                                                   | TODO   |
| P4.2  | Sidebar tabs `Chat/Meet/Calls` + badges + `hasFeature("meetings")` gate                                                                                | TODO   |
| P4.3  | Search header + selection mode + bulk delete                                                                                                           | TODO   |
| P4.4  | Conversation grouping + `ConversationItem` anatomy (avatar, presence dot, name, preview prefixes, time format, unread badge, mute, pin, delivery tick) | TODO   |
| P4.5  | `ChatHeader` (back, avatar, name, presence/last-seen format, call buttons, menu)                                                                       | TODO   |
| P4.6  | Date separators + unread divider + scroll pinning + load-more                                                                                          | TODO   |
| P4.7  | `MessageBubble`: reply quote, forwarded label, attachments by type, text, edited marker, timestamp, `DeliveryStatus` ticks                             | TODO   |
| P4.8  | `ReactionBar` + emoji picker                                                                                                                           | TODO   |
| P4.9  | `ChatInputBar`: full button row, verbatim placeholder, reply bar, edit bar, attachment strip, voice recording                                          | TODO   |
| P4.10 | Typing indicator                                                                                                                                       | TODO   |
| P4.11 | `ConversationInfoPanel`                                                                                                                                | TODO   |
| P4.12 | `CallsTab` + `CallHistory`                                                                                                                             | TODO   |
| P4.13 | Wire the ~35 pending `/api/chat/*` routes                                                                                                              | TODO   |
| P4.14 | Complete WS event coverage                                                                                                                             | TODO   |

Chat endpoints still `pending` in `docs/PARITY_MATRIX.md`:
`/chat/blocked`, `/chat/calls/:callId/{accept,end,reject}`, `/chat/calls/active`,
`/chat/calls/delete`, `DELETE /chat/conversations/:id`,
`/chat/conversations/:id/files` (GET), `/chat/conversations/:id/group` (PUT),
`/chat/conversations/:id/leave`, `DELETE /chat/conversations/:id/messages`,
`GET /chat/conversations/:id/messages`, `/participants/:userId/role`,
`/chat/conversations/:id/pinned`, `/polls`, `/transfer-owner`, `/unread`,
`/chat/conversations/group`, `/chat/link-preview`,
`/chat/messages/:id/delivered`, `/chat/messages/:id/view`,
`/chat/polls/:id`, `/chat/polls/:id/vote`, `/chat/presence`,
`/chat/search`, `/chat/search-messages`, `/chat/starred`,
`/chat/users/:userId/block` (POST+DELETE).

Realtime: server is **raw `ws`**, not socket.io.
Path `/ws`, `maxPayload 64KB`. Auth precedence:
cookie `TENANT_COOKIE` → **`?token=<jwt>` (what Android uses)** →
`Sec-WebSocket-Protocol`. **No in-band handshake message.**
`verifyClient` allows a missing `Origin` (OkHttp), `workpulse://`, `aino://`,
same-host, `CORS_ORIGIN` entries, dev localhost.
Keep `contracts/realtime-events.json` in sync via
`scripts/check-realtime-parity.mjs`.

---

### PHASE 5 — Profile / account surface

There is **no** `Profile.tsx` or `Settings.tsx` on web. The surface is:
`components/navbar/ProfileMenu.tsx` + `EditProfileModal.tsx` +
`NotificationSoundsModal.tsx` + `StatusPicker` popover +
`pages/profile/FaceEnrollment.tsx` (standalone `/profile/face` route).

| ID    | Task                                                                                                | Status |
| ----- | --------------------------------------------------------------------------------------------------- | ------ |
| P5.1  | `StatusPicker` — options + dot colours + `GET/PUT /api/me/status`                                   | TODO   |
| P5.2  | `EditProfileModal` — exact fields + `PUT /api/profile`                                              | TODO   |
| P5.3  | Avatar upload/delete — `POST/DELETE /api/profile/avatar`                                            | TODO   |
| P5.4  | `NotificationSoundsModal` — `GET/PUT /api/profile/notification-prefs`                               | TODO   |
| P5.5  | Face enrollment — `POST/DELETE /api/profile/face-enroll`, `GET /api/profile/face-status`            | TODO   |
| P5.6  | Change email — `PUT /api/profile/email`                                                             | TODO   |
| P5.7  | Theme toggle persistence + `GET/PUT /api/tracker/theme`                                             | TODO   |
| P5.8  | Presence preference — `PUT /api/me/status/presence-preference`, `POST /api/me/status/activity-ping` | TODO   |
| P5.9  | Logout parity — `POST /api/auth/logout` + local wipe                                                | TODO   |
| P5.10 | Delete account — `DELETE /api/profile`                                                              | TODO   |

Already implemented: `GET /api/profile`, `PUT /api/profile`,
`PUT /api/profile/email`, `GET /api/profile/face-status`,
`PUT /api/profile/password`.

---

### PHASE 6 — Tasks · Agile · Sprints · Projects (`A-104`, ~90 ops)

| ID   | Task                                                                                           | Status |
| ---- | ---------------------------------------------------------------------------------------------- | ------ |
| P6.1 | Tasks list/board parity with `pages/Tasks.tsx` + `Tasks.module.css`                            | TODO   |
| P6.2 | Task detail: comments, acceptance criteria, dependencies, history, git refs, custom fields     | TODO   |
| P6.3 | Backlog + carry-forward + labels management                                                    | TODO   |
| P6.4 | Sprints: list, start/pause/resume/complete, burndown, CFD, cycle-time, velocity, retrospective | TODO   |
| P6.5 | Projects CRUD + archive + project tasks                                                        | TODO   |
| P6.6 | Agile settings: work item types, workflow states, reorder, permissions/grants/requests         | TODO   |
| P6.7 | Service desk tickets + stats                                                                   | TODO   |
| P6.8 | `SprintInsights` page                                                                          | TODO   |

Currently implemented in this group: `POST /tasks`, `DELETE /tasks/:id`,
`POST /tasks/:id/comments`, `GET /tasks/:id/detail`, `PATCH /tasks/:id/status`,
`GET /tasks/assignable-users`, `POST /tasks/backlog`,
`POST /tasks/carry-forward`, `GET /tasks/labels`. Everything else pending.

---

### PHASE 7 — Calendar · Notes · Notifications · Organization · Search (`A-105`, ~60 ops)

| ID   | Task                                                                                                            | Status |
| ---- | --------------------------------------------------------------------------------------------------------------- | ------ |
| P7.1 | Calendar page — `GET/POST/PUT/DELETE /api/calendar`                                                             | TODO   |
| P7.2 | Notes: page tree, editor, daily prefill, 1:1 prefill, history/snapshots, links, mentions, sharing, public notes | TODO   |
| P7.3 | Notes embeds: sprint-embed, time-summary, search-events/meetings/tasks                                          | TODO   |
| P7.4 | Notifications centre: list, read, read-all, delete, metrics                                                     | TODO   |
| P7.5 | Organization: chart, departments, teams, roles, members, invite, settings, sprint-config                        | TODO   |
| P7.6 | Global search — `GET /api/search`                                                                               | TODO   |

Currently implemented: `GET /notifications/announcements`, `GET /org/current`.

---

### PHASE 8 — Manager / My Team (`A-106`, 12 ops) — all pending

`/manager/approvals` (+ approve/reject/bulk), `/manager/member/:userId/{overview,hours,leaves,requests,tasks}`,
`/manager/my-requests`, `/manager/team-analytics`, `/manager/team-attendance`.
Mirrors `pages/ManagerDashboard`.

---

### PHASE 9 — Meetings & calls (`A-107`, 13 ops)

Meetings CRUD, participants, conflicts, messages, HLS start/status/stop;
`MeetingJoin`, `MeetingRoom`, `HuddleAutoJoin`, `CallPipPage` equivalents.
The Android call stack (`core/call/*`, WebRTC, Telecom, ring service) already
exists and should be reused rather than replaced.

---

### PHASE 10 — Admin · Tenants · Compensation (`A-108` / `A-110`, ~150 ops)

| ID    | Task                                                                                                                                                                      | Status |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| P10.1 | Admin: users, roles, announcements, invite codes, audit logs, pay periods, registration settings, role requests, stats, task labels, organizations                        | TODO   |
| P10.2 | Tenants console: CRUD, features, limits, plan, suspend/reactivate, seed, stats, users, impersonation, access requests, plan catalog, platform config, platform users      | TODO   |
| P10.3 | Compensation/payroll: employees, CTC config, templates, salary slips, PDF, publish, bulk publish, payroll run, disbursements, bank details + verification, payment config | TODO   |
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
| `AinoDestination.Profile` as a NavHost page                                    | Web has no profile page            |
| `AinoDestination.Attendance.inBottomBar = true`                                | Attendance is a More item on web   |
| `AinoAtmosphere` / `AinoGlassCard` on parity screens                           | Web has no glass aesthetic         |

---

## 7. Suggested execution order

```
P0.1 → P0.2 → P0.4 → P0.3 → P0.5          (unblock + instrument)
P1.1 → P1.2 → P1.3                        (tokens first — everything depends on them)
P1.4 → P1.5 → P1.6 → P1.7 → P1.8 → P1.9   (shell)
P2.*                                      (Home)
P3.*                                      (Attendance)
P4.*                                      (Chat)
P5.*                                      (Profile)
P6 → P7 → P8 → P9 → P10                   (remaining routes)
X.* run continuously
```

Coverage checkpoints (`docs/PARITY_MATRIX.md`):
after P5 ≈ **120/464 (26%)**, after P6 ≈ **210 (45%)**,
after P7 ≈ **270 (58%)**, after P8 ≈ **282 (61%)**,
after P9 ≈ **295 (64%)**, after P10 ≈ **464 (100%)**.
