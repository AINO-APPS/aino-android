# Spec: the home "My Work" card — sprint tickets or this week's schedule

Status: ready-for-agent
Scope: `aino-android` (issue 01), `aino-platform` client + desktop (issue 02). No server change.

## Problem

The home screen's task section shows the user's **backlog** whenever they have no
active sprint. On Android it shows the backlog *always*: the sprint branch is dead
code. Users want the card to show the work they are actually committed to — their
tickets in the running sprint, or, when there is no running sprint, the tickets
planned into the current week.

## Ground truth (verified, with sources)

### Web (`aino-platform/client`)

- The section is `components/dashboard/SprintProgressCard.tsx`, mounted by
  `pages/Dashboard.tsx:194-200`.
- `loadCard` (`:60-86`) decides the mode: `hasFeature("agile")` false → backlog;
  `GET /api/sprints/active` returns no sprint → backlog; otherwise
  `GET /api/sprints/:id/tasks`.
- `loadBacklogFallback` (`:45-58`) is the backlog: `getBacklog({ assignee: "me" })`,
  incomplete only, priority-sorted, top 5.
- Sprint mode filters to the current user client-side (`:170`).
- Title at `:133` (`"Backlog"`), empty state at `:138`, rows at `:140-151`.
- The `agile` gate is the tenant plan feature (`FeaturesContext.tsx`, fail-closed);
  the tasks page's own tab rule keys on *active sprint*
  (`pages/tasks/hooks/usePlanningSurface.ts:41-53`).

### Android (`aino-android/app`)

- `feature/home/component/SprintProgressCard.kt:32-68` renders the section;
  `feature/home/DashboardRepository.kt:64-86` is the single `load()` fan-out,
  every source wrapped in `runCatching` and defaulting to empty.
- **Bug 1 — the sprint branch has never run.** `loadActiveSprint()`
  (`DashboardRepository.kt:40`) decodes the body as a bare `ActiveSprint`, but the
  server returns `{ sprint: {…} }` (`server/routes/sprints.ts:77`). The decode
  throws, `getOrNull()` swallows it, `sprint` is always `null`, so
  `SprintProgressCard.kt:36` always takes the backlog branch. The correct pattern
  already exists: `OrganizationRepository.kt:168` decodes `SprintEnvelope` and
  takes `.sprint`.
- **Bug 2 — the done filter is a no-op.** `SprintProgressCard.kt:44` filters
  `it.status != "done"`, but `SprintTask.status` (`DashboardModels.kt:59-67`)
  defaults to `"todo"` and the server has no `tasks.status` column. The dashboard
  DTO is a 4-field stub (`id`, `title`, `status`, `priority`) that omits
  `planned_date`, `completed_at`, `workflow_state_*` and `assigned_to`.
- **Bug 3 — sprint mode does not filter to "me".** `sprints/:id/tasks` returns the
  whole sprint; nothing filters it.

### Server primitives (no change needed)

- Week range: `GET /api/tasks?start_date=&end_date=&assignee=me`
  (`server/routes/tasks/crud.ts:65-111`). The collection builder pins
  `t.sprint_id IS NULL AND t.planned_date BETWEEN …`
  (`server/modules/tasks/tasks.collection.ts:161-166`), so it is ADR-015
  compliant. Ordering for this path is `PRIORITY_ORDER, STATUS_ORDER, created_at`
  (`:107`) — **priority first, not date** — so day grouping is client-side.
- `assignee=me` means `(assigned_to = me OR (user_id = me AND assigned_to IS NULL))`
  (`:200-211`) — wider than "assigned to me".
- The date-range path applies **no completed predicate**: completed tickets come
  back in the same response. The header done count needs no extra request.
- **Cancelled leaks.** The `cancelled` exclusion lives only in the board query
  (`:313-318`), not in the shared builder. Cancelled tickets have
  `completed_at` set (the state's category is `done`), so they would render as
  rows and inflate the done count. Clients must exclude
  `workflow_state_key == "cancelled"` themselves.
- Enriched payload carries `workflow_state_key`, `workflow_state_name`,
  `workflow_state_category` (`server/routes/tasks/_helpers/enrich.ts:79-80`).
  Android's `Task` decodes key and category but **not** name
  (`feature/tasks/TaskModels.kt:74-75`); web's `SprintTask` type omits it too.

## Decisions

**D1 — The mode switch is kept, and repaired.** `agile` feature on *and* an active
sprint → that sprint's tickets, filtered to the current user. Every other case →
this week's scheduled. A sprint team between sprints, paused, or never started
lands on the week list; both clients give such a team a Scheduled tab
(`planningTabs`, `usePlanningSurface.ts:41-53`), so the tap target exists.

**D2 — "This week" is Monday 00:00 to Sunday 23:59 in the device's local
timezone**, sent as `YYYY-MM-DD` `start_date` / `end_date`. Not a rolling 7 days,
not ISO-week numbering.

**D3 — The container is `planned_date` only.** A ticket merely *due* this week is
not in the week (ADR-015 amendment: a due date is a deadline, never a container).

**D4 — Overdue is pinned on top.** Open tickets assigned to me with a
`planned_date` before this Monday are fetched with a second narrow range
(`end_date` = the Sunday before Monday), and render as an **Overdue** group, each
row labelled with its day. Slipped work must not silently disappear.

**D5 — "My" means strictly assigned.** Both halves filter to
`assigned_to == me` client-side, tightening the server's `assignee=me`. Tickets
the user created and left unassigned are a triage inbox, not a commitment list,
and would make the header count wrong.

**D6 — States listed: all non-completed**, in the team's workflow order. The
header shows **"N of M done"**, derived from the completed rows in the same week
response. No collapsed Done sub-section.

**D7 — The backlog leaves the home screen on both clients**, and is never a
fallback. An empty week renders *"Nothing planned for this week"*. A section whose
meaning silently flips is the ambiguity ADR-015 exists to end; the backlog is one
tap away in the Tasks tab.

**D8 — Titles.** Sprint mode: the sprint's name. Week mode: **"This Week"**. The
card is renamed `MyWorkCard` (web component, Android composable, and the
`DashboardSnapshot` field) — it is no longer a sprint widget.

**D9 — Rows.** Title, the workflow state's **name** (not its category), the
state's colour dot, and priority. No assignee avatar (every row is the viewer), no
ticket key. This requires decoding `workflow_state_name` on both clients.

**D10 — Grouped by day, Overdue first.** Then one group per day that has work
(Mon…Sun, day name as the label), priority-ordered inside each group. A single
**6-row** cap across all groups, plus a "＋N more" footer. A week view that does
not show *when* is just a second priority queue.

**D11 — Tap targets.** The header/arrow navigates to the matching tab — Scheduled
in week mode (`?tab=scheduled` on web; `tasks/link?tab=` on Android), that
sprint's board in sprint mode. Individual rows open the ticket detail. Both
platforms already support these routes.

**D12 — No team scope.** Ambient server visibility only, no team picker on the
card (`team_id` is not sent).

**D13 — Client-only.** No backend endpoint is added or changed, so `contracts/`
is untouched and no server deploy gates the feature. Web ships on the web
release; **desktop requires its own release** (`client/dist` is baked in at
package time, `desktop/electron-builder.yml:59-63`); Android ships independently.

**D14 — Cancelled tickets are excluded client-side.** The board's rule
(`tasks.collection.ts:313-318`), applied next to the completed filter, because the
date-range path does not carry it.

## Out of scope

- Server-side assignee filter for `sprints/:id/tasks`; both clients filter.
- A week-scoped server endpoint, or a date ordering to replace the priority
  ordering on the date-range path.
- Any "My Week" container concept in the glossary: the week is a *filter over
  Scheduled*, not a fourth container. If that needs saying in `GLOSSARY.md`, it
  is a separate edit.
- Real-time refresh behaviour: both clients already refresh the card on
  `task_assigned` / `task_updated`; the existing wiring is reused unchanged.
- Service Desk, the Tasks page itself, and the mobile phase-10 compensation work.

## Amended during implementation

- **D9's state name forced a DTO decision.** `feature/home` cannot import
  `feature/tasks` (`scripts/check-module-boundaries.mjs`), so Android keeps a
  local `DashboardTask` DTO carrying `workflow_state_name` rather than reusing
  `feature.tasks.Task`. The test seam stays in `feature/home`.
- **The rule is registered as decision D53** in
  `aino-platform/docs/TASKS_DESIGN_SPEC.md`, with a `home-my-work` feature in
  `contracts/tasks-parity.json` (web/desktop/android shipped, iOS waived). That
  contract is what enforces cross-client parity, so the card could not ship
  with a rule recorded only in this file.
- Desktop still requires its own release to receive the change (D13).
- **D4 gained a 30-day Overdue cap** (`OVERDUE_DAYS`), which the first draft did
  not specify. Without a floor the Overdue query is unbounded to the beginning of
  the project, and a single ancient stale ticket would hold a permanent row and
  eat the 6-row budget. 30 days matches `SCHEDULED_HISTORY_DAYS`, the window the
  server already uses for finished Scheduled work.
- **D9's "state's colour dot" cannot be the team's real colour.** The server
  sends only `workflow_state_key`/`name`/`category` — not `workflow_states.color`
  — so both clients map the category to three tones. A tenant-defined state reads
  as neutral. Making it exact needs the colour added to the enriched task payload
  (a server change, out of scope per D13).
- **D6's "team's workflow order" is not implemented.** Rows are priority-ordered
  within a group (D10), not ordered by `workflow_states.sort_order`. The two
  group-level orders would fight; D10 wins because "what to do next" is the
  card's question.

## Issues

- `01` Android: rewrite the card (`aino-android`). **Done.**
- `02` Web + desktop: same change in `client`, then rebuild desktop (`aino-platform`). **Done** — desktop release pending.
