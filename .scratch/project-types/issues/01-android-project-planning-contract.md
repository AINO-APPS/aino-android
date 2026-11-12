# 01: Android project planning contract and type display

Type: task
Status: resolved
Repo: aino-android
Branch: ticket/project-types-01
Acceptance scope: Android ticket01 slice only.

Platform ticket01 was accepted at `83cd4065` in the separate platform repository.
Its spec and issue remain the source of the project-type lifecycle contract.
This Android branch starts from integration `a42eb5c`, including ticket02 start dates.

## Acceptance checklist

- [x] All three project readers preserve type, ordered server `planTabs`,
  available/changeable types, Kanban backlog/WIP switches and `canChangeType`.
- [x] The selected project's shared `PlanScope` exposes that metadata without
  deriving tabs or permissions from the agile feature or administration rights.
- [x] Older Phase 2 responses without type fields retain Scrum metadata defaults,
  existing permissions and legacy planning behavior; missing capabilities never
  grant type changes.
- [x] The real Tasks screen displays the selected project type and dropdown-row
  types, including archived projects; selection updates the shared contract.
- [x] Project settings remain web-only. No Kanban board/backlog, Task list,
  Scheduled replacement or sprint-plan replacement from tickets03–05 is included.

## Answer

`ProjectOption` and `ProjectCapabilities` carry the additive server contract
through the existing `TaskRepository` readers. `PlanScope` projects the selected
row, and the switcher displays its type on a separate line so it remains visible
on a narrow screen. README documents the compatibility boundary.

Validation completed on 2026-11-09:

- `gradlew.bat --no-daemon --console=plain --quiet testPlayDebugUnitTest lintPlayDebug assemblePlayDebug testDirectDebugUnitTest assembleDirectDebug`
  exited 0 (`android-ticket01-ci`): Play 206 suites / 1,396 tests and Direct
  207 suites / 1,402 tests, with no failures or skipped tests. Lint and both
  debug APK builds passed.
- Both flavors passed `ProjectPlanningContractTest` (4 tests),
  `ProjectTypeSwitcherUiTest` (2 tests, including legacy-server screen behavior)
  and `ProjectOwnershipViewModelTest` (8 regression tests).
- The switcher test first failed on visible type text, then passed after the
  layout change.
- All five native guardrails passed: independence, module boundaries, source
  provenance, realtime parity and endpoint parity. Realtime used the committed
  85-event snapshot; endpoint parity reported 276/532 covered operations,
  234 waived, 3 client-retired, 21 pending and 0 unmatched.
- Integration ancestry was verified, and merging the current local Android
  `integration/project-types` tip `a42eb5c` reported already up to date.

No ticket05 implementation, production operations, push or release is included.
