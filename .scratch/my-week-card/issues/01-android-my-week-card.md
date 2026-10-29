# 01 — Android: the home card shows sprint tickets or this week's schedule

Type: task
Status: done
Blocked by: none
Repo: aino-android
Spec: `.scratch/my-week-card/spec.md`

## Problem

The home screen's task section always shows the user's backlog, even on a team
with a running sprint. Three defects share one cause: the card was built as a
sprint widget whose sprint branch never worked, and its fallback is the only code
path that ever runs.

1. **The sprint branch has never run.** `DashboardRepository.loadActiveSprint()`
   (`app/src/main/java/app/aino/mobile/feature/home/DashboardRepository.kt:40`)
   decodes the response as a bare `ActiveSprint`, but the server sends
   `{ sprint: {…} }`. The decode throws, `getOrNull()` swallows it, so `sprint` is
   always null and `SprintProgressCard.kt:36` always takes the backlog branch.
2. **The done filter is a no-op.** `SprintProgressCard.kt:44` filters
   `status != "done"`, but `SprintTask` (`feature/home/DashboardModels.kt:59-67`)
   is a 4-field stub whose `status` defaults to `"todo"`, and the server has no
   `tasks.status` column.
3. **Nothing filters the sprint list to the user.** `sprints/:id/tasks` returns the
   whole sprint.

## Change

### Data layer

- **Revised during implementation.** `feature/home` may not import
  `feature/tasks` — `scripts/check-module-boundaries.mjs` forbids sibling-feature
  imports — so the card keeps a local DTO instead of reusing
  `feature.tasks.Task`. `DashboardTask` (`DashboardModels.kt`) carries the nine
  fields the card needs, and `ActiveSprintEnvelope` replaces the bare-sprint
  decode.
- Delete the dashboard's own `SprintTask` / `SprintTasksEnvelope` stubs in favour
  of `DashboardTask` + `DashboardTaskList`.
- ~~Add `workflow_state_name` to `Task`~~ — superseded by the point above:
  `DashboardTask` decodes it, and `feature.tasks.Task` is left alone (the Tasks
  page does not use the display name).
- In `DashboardRepository.load()`:
  - Decode `sprints/active` through an envelope. Do not swallow a genuine
    decode failure any more silently than the other sources do.
  - Sprint mode: `GET sprints/:id/tasks`, then keep `assignedTo == userId` and
    `completedAt == null`, and drop `workflowStateKey == "cancelled"`.
  - Week mode: `GET tasks?start_date=<Mon>&end_date=<Sun>&assignee=me` with
    local dates, plus a second narrow request ending at the previous Sunday for
    **Overdue**. From both, keep `assignedTo == userId` and drop `cancelled`;
    retain completed rows from the week request only, so the header can say
    "N of M done".
- Keep the existing `runCatching`-per-source fan-out: a failing week request must
  not blank the rest of the dashboard.

### Time helpers

- Add pure functions next to the existing task date helpers
  (`feature/tasks/TaskLogic.kt`) — Monday/Sunday of the device's current week as
  `YYYY-MM-DD`, and the Sunday before it. Pure and therefore unit-tested; do not
  use `java.time.DayOfWeek` arithmetic inline in the composable.

### UI

- Rename `feature/home/component/SprintProgressCard.kt` → `MyWorkCard.kt`, and the
  `DashboardSnapshot` field to match. Same for the `SprintProgressCard` render
  call in `HomeScreen.kt:66-78`.
- Title: the sprint's name in sprint mode, **"This Week"** in week mode. Fix the
  dangling `" — " + sprint.name` concatenation.
- Header shows **"N of M done"** in week mode.
- Groups, in order: **Overdue** (each row labelled with its planned day), then one
  group per day that has work, Mon…Sun, day name as the label. Priority order
  within a group — the server returns priority order, so no sort is needed inside
  a day.
- Row content: title, `workflowStateName` with the state's colour dot, priority.
  No avatar, no ticket key. When `workflowStateName` is null, fall back to the
  category name as web does today.
- Cap: 6 rows across all groups, plus a "＋N more" footer when there are more.
- Empty week: *"Nothing planned for this week"*. **Never** fall back to the
  backlog (spec D7).
- Taps: header/arrow → Scheduled in week mode, that sprint's board in sprint
  mode — via `taskLinkRoute(tab = …)` / the existing Tasks destination, not a new
  route. A row tap → `openDetailById`.

## Tests

- `activeSprintEnvelopeDecodes` — a `{ sprint: {…} }` body must produce a sprint
  (the current bug, pinned).
- `weekDates` — Monday/Sunday for a Wednesday, a Sunday, and across a month and
  year boundary; and the previous Sunday for the Overdue range.
- `weekModeKeepsOnlyMine` — a `GET tasks` fixture containing another user's
  ticket, a ticket the user created but nobody is assigned, a cancelled ticket,
  and the user's own open ticket → only the last survives.
- `weekModeCountsDone` — completed rows excluded from the list but counted in
  "N of M done"; a cancelled row is in neither.
- `emptyWeekDoesNotFallBack` — zero rows renders the empty text, and the backlog
  endpoint is not called.
- Update any existing `DashboardRepository` / `HomeScreen` tests for the renamed
  types.

## Notes

- `contracts/android-endpoint-coverage.json` is generated from the `// @api`
  annotations on repository methods; add them for the new calls and regenerate
  rather than hand-editing.
- The dashboards refresh on `task_assigned` / `task_updated` already
  (`AinoApp.kt:1521-1529`); reuse that wiring unchanged.
