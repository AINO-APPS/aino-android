# 04 — Web: two-tab rule, and a Start sprint control in admin

Type: task
Status: done
Blocked by: 02
Repo: aino-platform (client + admin)

> Part B (admin Start sprint) was dropped during implementation: the Sprint tab is
> now visible as soon as the team has sprint config, so the existing Start control on
> the sprint board stays reachable. See spec D4.

Desktop inherits this by rebuilding against `client/dist`; there is no code change
under `desktop/`.

## Part A — tab rule

`client/src/pages/tasks/TasksHeader.tsx:236-292` renders Sprint (gated on
`hasFeature("agile") && availableSprints.length > 0`), Backlog, Scheduled, Service
Desk — all four. Change to:

| Team | Tab row |
|---|---|
| sprint-enabled | Sprint, Backlog, Service Desk |
| not sprint-enabled | Backlog, Scheduled, Service Desk |

- Gate on the `sprint_enabled` flag from issue 02, not on `availableSprints.length`.
- Keep Scheduled reachable (spec D7): `/tasks/schedule` still routes and the command
  palette entry stays; it is only removed from the tab row. Deep links must not 404.
- `useTasksRouteSync` landing and the `Tasks.tsx:129-133` / `:282-290` bounces must
  use the new rule.
- `showsBacklog` (`useTasksRoute.ts:38-49`) already keeps the plan view loading both
  panes; unchanged.

## Part B — Start sprint in admin

Manual-mode teams currently start a sprint only from Tasks → Sprint tab, which is
hidden before the first start. Add a **Start sprint** action to the sprint-config row
in `client/src/components/organization/Teams.tsx:407-514`, shown when
`sprint_mode === "manual"` and a non-completed sprint exists to start (or when a
materialised `planned` sprint exists). It calls the existing
`POST /api/sprints/:id/start`. Auto-mode keeps Pause/Resume only.

Consider whether starting from admin should also be offered for the *planned* sprint
the config implies when no row exists — if so, rely on `available-sprints`
materialisation rather than creating rows in the admin route.

## Tests

- Sprint-enabled team: Scheduled button absent, `/tasks/schedule` still renders.
- Non-enabled team: Sprint button absent even if a stray row exists.
- Start sprint from admin flips the team to active and the Sprint tab appears.

## Done when

Web and Android present the same tabs for the same team state, and a manual-mode team
can start its first sprint without visiting a hidden tab.
