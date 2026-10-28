# 03 — Android: Backlog + Sprint, or Backlog + Scheduled

Type: task
Status: done
Blocked by: 02
Repo: aino-android

## Problem

`TaskViewModel.visibleTabs` (`TaskViewModel.kt:191-192`) shows Backlog, Scheduled,
Service Desk always, and Sprint when `agileEnabled && sprints.isNotEmpty()`. A team
with sprint config but no materialised rows sees no Sprint tab; a team that will
never use sprints still sees one as soon as a row appears.

## Change

- Consume the `sprint_enabled` flag from issue 02 (store it on `TaskUiState` alongside
  `agileEnabled`, set in `TaskViewModel.bind`, which already receives team context
  from `AinoApp.kt:547-556`).
- Replace `visibleTabs` with a pure function in `TaskLogic.kt`:

  ```
  sprintEnabled -> [Backlog, Sprint] (+ ServiceDesk by its current rule)
  otherwise     -> [Backlog, Scheduled] (+ ServiceDesk by its current rule)
  ```

- Tab order stays `Backlog, Sprint` / `Backlog, Scheduled`; the enum's entry order
  (`TaskTab.entries`) already produces Backlog → Scheduled → Sprint, so index-based
  pager positions must be recomputed from the filtered list, not the enum.
- Landing: keep `landingTab` but replace its `sprints.isNotEmpty()` test with
  `sprintEnabled`. A sprint-enabled team with no active sprint lands on Backlog.
- Bounce-offs at `TaskViewModel.kt:349`, `:378`, `:982` must use the new rule so the
  selected tab can never be one that is not in `visibleTabs`.
- Scheduled for a sprint-enabled team shows dated, sprint-less tasks (spec D5). No
  data-layer change — it already reads `GET tasks?start_date&end_date`, which spans
  sprint and non-sprint tasks; because the tab is not shown for sprint teams this is
  not user-visible, but the filter must not be relied on for exclusivity.

## Tests

Four combinations in `app/src/test/.../SprintLandingTest.kt` style:
no agile → no Sprint; agile + not enabled → Backlog+Scheduled; agile + enabled →
Backlog+Sprint; agile + enabled + active sprint → lands on Sprint.

## Done when

A configured-but-unstarted team sees Backlog + Sprint, a never-configured team sees
Backlog + Scheduled, and no code path can select a hidden tab.
