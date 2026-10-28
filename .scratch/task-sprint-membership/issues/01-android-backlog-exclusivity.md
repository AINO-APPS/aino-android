# 01 — Android: a sprint ticket must not appear in the Backlog tab

Type: task
Status: done
Blocked by: none
Repo: aino-android

## Problem

Assign a ticket to a sprint from the Backlog and it remains in the Backlog tab, while
also showing on the sprint board. The Backlog tab in fact renders sprint groups.

## Root cause

`TaskRepository.loadPlanTasks`
(`app/src/main/java/app/aino/mobile/feature/tasks/TaskRepository.kt:37-62`) pages
`GET tasks/backlog` and then calls `loadSprintTasks` for every entry in
`tasks/available-sprints`, unioning the results. `TaskViewModel.projectPlan()`
(`TaskViewModel.kt:216-232`) keeps the union (`isBacklogItem || sprints.any { … }`),
and `groupPlanBacklog` (`TaskLogic.kt:91-101`) renders the sprint-assigned rows as
"Sprint N" groups inside Backlog.

The server is correct: `scope: 'backlog'` is `t.sprint_id IS NULL`.

## Change

- `loadPlanTasks` loads the backlog only. Delete the per-sprint union and the
  `sprints/{id}/tasks` overflow hydration that exists only to serve it.
- `projectPlan()` keeps `isBacklogItem` tasks only — no `sprints.any { … }` clause.
- `groupPlanBacklog` loses its sprint buckets; the Backlog tab is one flat list.
- The cross-sprint overview lives solely in `SprintPlanPage` (Sprint tab, plan view),
  which already loads both sides.
- Keep D6's safety net: if a task still carries a `sprintId` that no longer matches
  a known sprint, surface it in an "In a sprint" group rather than dropping it.

## Tests

- A backlog response containing a `sprint_id` row must not appear in
  `ui.backlog` (add to `app/src/test/java/app/aino/mobile/feature/tasks/`).
- Sprint plan view still shows both panes (regression guard for moving the overview).
- Existing `TaskRepositoryTest.kt:87` and `TaskSyncTest.kt:128` must still pass; update
  them only if they assert the union.

## Done when

Assigning a ticket to a sprint removes it from the Backlog tab immediately, and it
appears only on that sprint's board.
