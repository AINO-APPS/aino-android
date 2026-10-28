# 05 — Platform: no task may be stranded by removing sprint config

Type: task
Status: needs-triage
Blocked by: 02, 03
Repo: aino-platform

## Problem

If an admin clears a team's `sprint_start_date`, that team becomes "not sprint-enabled"
and loses its Sprint tab. Any task still holding a `sprint_id` then appears in no tab:
the backlog query is `sprint_id IS NULL`, and there is no sprint surface. From the
user's point of view the ticket has vanished.

## Change

In `PUT /api/org/teams/:id/sprint-config`
(`server/routes/organization.ts:690-738`), when `sprint_start_date` transitions from
non-empty to NULL/empty, clear `sprint_id` on that team's tasks:

```sql
UPDATE tasks SET sprint_id = NULL WHERE team_id = $1 AND sprint_id IS NOT NULL
```

Decide and document what to do with the now-orphaned `sprints` rows: keep them
(history, stats, velocity) but they are no longer listed because
`sprint_start_date` is NULL, or mark them completed. Keeping them is preferred — the
rows hold velocity and retro data.

Also consider the `sprint_paused`-style trap: saving config does not clear anything
today, so a paused team flipped back to configured has `sprint_paused = TRUE` and
gets no sprint. Out of scope here, but note it in the PR.

## Safety net

Issue 01 adds an "In a sprint" group to the Android Backlog tab for tasks whose
`sprintId` matches no known sprint. That group should become unreachable after this
change; it stays as defence in depth.

## Tests

- Clear `sprint_start_date` → previously sprinted tasks return to the backlog list.
- Clearing config twice is idempotent.
- Tasks in *other* teams are untouched.
- Completed tasks keep their `sprint_id`? — decide: do not touch rows whose workflow
  state is terminal, so completed-sprint history survives.

## Done when

No sequence of admin actions can make a task unreachable from every tab.
