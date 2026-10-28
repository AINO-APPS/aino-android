# 02 — Platform: expose whether a team has sprint config

Type: task
Status: done
Blocked by: none
Repo: aino-platform

## Problem

No API tells a client whether a team is sprint-enabled. Clients infer it from
`available-sprints` being empty, which is wrong in two ways:

- `available-sprints` auto-materialises rows as a side effect of the read, so the
  answer changes on the second call.
- It returns only `active`/`planned`, so a team whose sprint is paused or whose last
  sprint completed looks unconfigured.

## Change

Derive `sprint_enabled` from `teams.sprint_start_date IS NOT NULL` (spec D3) and
expose it wherever the Tasks clients already read team context. Preferred: a
`team` object (or `sprint_enabled` boolean) on the response of
`GET /api/tasks/available-sprints`, since that call is already made by both clients
when agile is on. Acceptable alternative: a field on `GET /api/profile` for the
caller's team, plus `GET /api/org/teams` for admins.

Note `sprint_start_date` is TEXT; treat empty string as NULL.

`resolveTeamMode` (`sprint_mode`) is **not** the flag — do not repurpose it.

## Tests

- Configured team with no sprint rows → `sprint_enabled: true`.
- Team with `sprint_start_date` unset → `false`.
- Paused team → `true`.
- Tenant without the `agile` feature → endpoint still 403s as today (clients treat
  403 as "no sprints").

## Done when

Both clients can read sprint-enabled state without calling `available-sprints` for
its rows.
