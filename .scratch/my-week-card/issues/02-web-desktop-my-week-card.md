# 02 — Web: the home card shows sprint tickets or this week's schedule (then rebuild desktop)

Type: task
Status: done
Blocked by: none
Repo: aino-platform (client, then desktop package)
Spec: `.scratch/my-week-card/spec.md`

## Problem

On web the home card already switches to the active sprint when one exists — but
its fallback is the backlog (`getBacklog({ assignee: "me" })`, top 5 by priority),
which is not what the user is committed to this week. Desktop ships the same code
but only after its own release, so it must be rebuilt.

## Change

`client/src/components/dashboard/SprintProgressCard.tsx`:

- Rename the component and its file to `MyWorkCard.tsx`; update the mount in
  `client/src/pages/Dashboard.tsx:194-200`.
- Keep `loadCard`'s mode switch (`:60-86`) as is: `hasFeature("agile")` and an
  active sprint → `getSprintTasks`; otherwise → the new week load. Keep the 403
  degradation.
- Replace `loadBacklogFallback` (`:45-58`) with a week load:
  - `GET /tasks?start_date=<Mon>&end_date=<Sun>&assignee=me` and a second narrow
    request ending at the previous Sunday for **Overdue**.
  - Keep `assigned_to === user.id` only — the server's `assignee=me` also returns
    the user's own unassigned creations.
  - Drop `workflow_state_key === "cancelled"`: the date-range path does not
    carry the board's cancelled exclusion, and cancelled rows would otherwise
    appear and inflate the done count.
  - Retain completed rows from the week request so the header can show
    **"N of M done"**; they are not listed.
- Sprint mode keeps its client-side assignee filter (`:170`) and gains the same
  cancelled exclusion.
- Title: the sprint's name in sprint mode, **"This Week"** in week mode
  (`:133`). Empty state: *"Nothing planned for this week"* (`:138`). No backlog
  fallback at any point.
- Rows (`:140-151`): title, widget state **name** (fall back to the category
  label when absent), the state's colour dot, priority. Add `workflow_state_name`
  to the `SprintTask` type if it is not already typed — the server sends it
  (`enrich.ts:79`).
- Groups: Overdue (row labelled with its day), then one group per day with work,
  Mon…Sun. Priority order within a day (that is the server's order for this
  path). **6-row** cap across groups plus a "＋N more" footer.
- Taps: header/arrow → `/tasks?tab=scheduled` in week mode, the sprint board in
  sprint mode (`:173`). Row tap → the ticket detail, matching how the Tasks page
  opens one.

Week dates must be computed in the browser's local timezone (the same
`monday`/`sunday` arithmetic the attendance service uses server-side), as pure
helpers that can be unit-tested.

## Tests

- `weekModeKeepsOnlyMine` — another user's ticket, the user's own unassigned
  creation, a cancelled ticket and the user's own open ticket → only the last
  renders.
- `weekModeCountsDone` — completed rows not listed, counted in "N of M done";
  cancelled in neither.
- `emptyWeekDoesNotFallBack` — the backlog endpoint is not called and the empty
  text renders. (Guard against regression: the current test suite has a
  sprint-membership test asserting backlog behaviour; keep it pointed at the
  Tasks page.)
- Week-date helper unit test, including a month and year boundary.
- Existing sprint-mode tests still pass with the renamed component.

## Desktop

`desktop/` has no task code — it serves `client/dist` baked in at package time
(`desktop/electron-builder.yml:59-63`). Nothing to change, but the feature does
not reach desktop users until a desktop release is cut (`npm run build:win` /
the `v*` tag workflow). Note that in the release notes.

## Follow-through

- No server change (spec D13) — the week uses the existing
  `GET /tasks?start_date&end_date&assignee=me`.
- **Revised during implementation.** Two follow-through items were wrong as
  written:
  - The rules live in `client/src/components/dashboard/myWork.ts` with
    `myWork.test.ts`, not inside the component, so the 6-row cap and the
    "mine, open, not cancelled" rule are unit-testable without rendering.
  - `contracts/tasks-parity.json` *is* the cross-client registry for this kind
    of rule, so it gained a `home-my-work` feature (web/desktop/android
    `shipped`, iOS waived) backed by new decision **D53** in
    `docs/TASKS_DESIGN_SPEC.md`. `npm run contracts:validate` passes.
  - `docs/PARITY_MATRIX.md` in the Android repo is a *generated endpoint*
    matrix with no widget rows; nothing to edit there.
