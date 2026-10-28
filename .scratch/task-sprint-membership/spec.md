# Spec: task/sprint membership and the Tasks tab rule

Status: ready-for-agent
Scope: `aino-android` (implementation), `aino-platform` (server flag + web client), desktop (rebuild only)

## Problem

1. **A ticket assigned to a sprint still appears in the Android Backlog tab.** The web
   client never had this bug; Android is the only client that unions the backlog with
   sprint work.
2. **The Tasks tabs do not follow the team's sprint state.** A sprint-enabled team
   should see `Backlog` + `Sprint`; a team with no sprints should see `Backlog` +
   `Scheduled`. Today all clients gate the Sprint tab on *sprint rows existing* and
   always show Scheduled.

## Ground truth (verified, with sources)

- The server already enforces exclusivity: `scope: 'backlog'` pushes
  `t.sprint_id IS NULL` (`aino-platform/server/modules/tasks/tasks.collection.ts:112-134`),
  pinned by `server/__tests__/tasks.collection.test.ts:57` and
  `server/__tests__/tasks.completedBacklog.test.ts:44-57`.
- Web honors it: Backlog tab → `GET /tasks/backlog` only (`client/src/pages/tasks/hooks/useBacklog.ts:215-254`);
  Sprint tab → `GET /tasks?sprint_id=` (`client/src/pages/Tasks.tsx:151-172`);
  test `client/src/pages/tasks/__tests__/sprintMembershipExclusive.test.tsx` —
  "A ticket is in a sprint or in the backlog — never both."
- **Android violates it on purpose-ish**: `TaskRepository.loadPlanTasks`
  (`app/src/main/java/app/aino/mobile/feature/tasks/TaskRepository.kt:37-62`) pages
  `/tasks/backlog` **and then loads every available sprint**, and
  `TaskViewModel.projectPlan()` (`TaskViewModel.kt:216-232`) keeps tasks where
  `isBacklogItem || sprints.any { it.id == task.sprintId }`, which
  `groupPlanBacklog` (`TaskLogic.kt:91-101`) renders as "Sprint N" groups inside the
  Backlog tab.
- **"Sprint enabled" has no single source of truth.** There is no `sprint_enabled`
  column. The admin sprint config writes `teams.sprint_start_date`,
  `teams.sprint_duration_weeks`, `teams.sprint_mode`
  (`aino-platform/server/routes/organization.ts:690-738`; UI
  `client/src/components/organization/Teams.tsx:407-514`). Rows are materialised
  lazily by `GET /api/tasks/available-sprints`
  (`server/routes/tasks/_helpers/sprintMaterialise.ts:41-80`) and hourly by
  `server/services/sprintScheduler.ts:243-250`.
- Both clients currently infer the Sprint tab from row existence:
  web `hasFeature("agile") && availableSprints.length > 0`
  (`client/src/pages/tasks/TasksHeader.tsx:240`); Android
  `agileEnabled && sprints.isNotEmpty()` (`TaskViewModel.kt:191-192`).
- **Scheduled is not exclusive with sprints.** `GET /api/tasks?start_date&end_date`
  has no sprint predicate, so a sprint task with a `planned_date` appears in both the
  sprint board and Scheduled. This is deliberate ("a planned date is metadata").
- Desktop is an Electron shell serving `client/dist`; there is no task code under
  `desktop/`.

## Decisions

**D1 — Backlog is exclusive; Scheduled is not.** A ticket with a `sprint_id` appears
only in that sprint. A ticket without one appears in Backlog, and additionally in
Scheduled if it has a `planned_date` in the ±30-day window. Scheduled stays a
calendar, not a container. (Matches web and the server.)

**D2 — Tab sets are two, chosen by team sprint state.**

| Team state | Tabs |
|---|---|
| Sprint-enabled | Backlog, Sprint (+ Service Desk if enabled) |
| No sprints | Backlog, Scheduled (+ Service Desk if enabled) |

Service Desk keeps its existing unconditional visibility on web, and on Android its
current rule. It is not part of the two-tab rule.

**D3 — "Sprint-enabled" = `teams.sprint_start_date IS NOT NULL`.** Not "sprint rows
exist". A configured team with no materialised rows yet is sprint-enabled; a paused
team is sprint-enabled. The backend exposes this as a boolean; clients do not infer
it from list emptiness.

**D4 — Start sprint stays where it is.** *Revised during implementation.* The
original concern was that hiding the Sprint tab before the first start would remove
the only "Start sprint" button. It does not: the tab is now shown as soon as the team
has sprint config (D3), so the existing Start control on the sprint board is reachable
from the first visit. `materialiseTeamSprints` also inserts the current window as
`active`, so most configured teams are already running. No admin Start control is
added.

**D5 — Scheduled before first start.** For a sprint-enabled team with no active
sprint, Scheduled still lists dated, sprint-less tasks. It is their only dating
surface.

**D6 — Stranded tasks are never invisible.** Removing a team's sprint config (or
clearing `sprint_start_date`) clears `sprint_id` on that team's tasks server-side, and
the Android Backlog tab additionally surfaces any remaining sprint-assigned task in
an "In a sprint" group as a safety net.

**D7 — Web keeps `/tasks/schedule` reachable.** For sprint-enabled teams the tab is
not offered in the tab row, but the route, command palette entry and deep links still
resolve.

**D8 — Desktop is a rebuild**, not a code change.

**D9 — An unreported flag is not "no sprints".** *Added during review.* Both clients
treat `team_sprint_enabled` as tri-state: absent means the server predates the field,
and the client falls back to the old sprint-list inference rather than dropping the
Sprint tab for every team. The profile route also degrades column-by-column, so a
tenant DB missing `notification_prefs` or `teams.sprint_start_date` still returns a
profile instead of 500.

**D10 — The stranded group is additive.** *Added during review.* The Backlog list
renders the ordinary sprint-less rows and then the stranded-sprint group; it never
renders one instead of the other.

## Out of scope

- `sprint_mode` (`manual`/`auto`) does not affect tab visibility. It decides who
  starts a sprint.
- The `team_agile_settings` table (unused), `isRollingSprint`/`storedSprintMode`
  (production-dead), and the stale `API_DOCUMENTATION.md:724-735` sprint-config
  contract — note them, do not fix them here.
- Service Desk behaviour.

## Issues

- `01` Android: backlog exclusivity — delete the union. **Done** (`5b7e096`).
- `02` Platform: expose `sprint_enabled` per team. **Done** (`a5f9c4b2`).
- `03` Android: two-tab rule. **Done** (`5b7e096`, `15305d3`).
- `04` Web: two-tab rule. **Done** (`a5f9c4b2`, `674e84b3`); admin Start control
  dropped per D4.
- `05` Platform: clear `sprint_id` when sprint config is removed. **Open** — blocked
  by the auto-mode permission classifier on bulk `UPDATE tasks` statements.
  Until it lands, D6's Android safety net keeps stranded tickets visible.
