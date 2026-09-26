# Phase 10 notes

## admin.ts routes
35: router.get('/organizations', requireRole('platform_admin'), async (req: Request, res: Response) => {
54: router.get('/organizations/:id', requireRole('platform_admin'), async (req: Request, res: Response) => {
75: router.post('/organizations', requireRole('platform_admin'), async (req: Request, res: Response) => {
108: router.put('/organizations/:id', requireRole('platform_admin'), async (req: Request, res: Response) => {
165: router.delete('/organizations/:id', requireRole('platform_admin'), async (req: Request, res: Response) => {
204: router.get('/users', async (req: Request, res: Response) => {
261: router.get('/users/:id', async (req: Request, res: Response) => {
294: router.put('/users/:id/role', requireTenantAdminIdentity, async (req: Request, res: Response) => {
356: router.get('/role-requests', async (req: Request, res: Response) => {
390: router.post('/role-requests/:id/approve', async (req: Request, res: Response) => {
438: router.post('/role-requests/:id/reject', async (req: Request, res: Response) => {
467: router.post('/role-requests/:id/cancel', async (req: Request, res: Response) => {
486: router.put('/users/:id/assignment', requireTenantAdminIdentity, async (req: Request, res: Response) => {
545: router.put('/users/:id/deactivate', requireTenantAdminIdentity, async (req: Request, res: Response) => {
574: router.post('/users/:id/reset-password', requireRole('hr_admin'), requireTenantAdminIdentity, async (req: Request, res: Response) => {
608: router.delete('/users/:id/face-enroll', requireRole('hr_admin'), requireTenantAdminIdentity, async (req: Request, res: Response) => {
628: router.delete('/users/:id', requireRole('super_admin'), requireTenantAdminIdentity, async (req: Request, res: Response) => {
665: router.post('/users', requireRole('hr_admin'), requireTenantAdminIdentity, async (req: Request, res: Response) => {
755: router.get('/audit-logs', requireFeature('audit_logs'), async (req: Request, res: Response) => {
779: router.get('/stats', requireSameOrg, async (req: Request, res: Response) => {
823: router.get('/registration-settings', async (req: Request, res: Response) => {
832: router.put('/registration-settings', requireRole('platform_admin'), async (req: Request, res: Response) => {
849: router.get('/invite-codes', async (req: Request, res: Response) => {
878: router.post('/invite-codes', async (req: Request, res: Response) => {
900: router.delete('/invite-codes/:id', async (req: Request, res: Response) => {
917: router.get('/task-labels', async (req: Request, res: Response) => {
931: router.post('/task-labels', async (req: Request, res: Response) => {
953: router.put('/task-labels/:id', async (req: Request, res: Response) => {
979: router.delete('/task-labels/:id', async (req: Request, res: Response) => {
999: router.get('/pay-periods', requireSameOrg, async (req: Request, res: Response) => {
1018: router.post('/pay-periods', requireRole('hr_admin'), requireSameOrg, async (req: Request, res: Response) => {
1050: router.delete('/pay-periods/:id', requireRole('hr_admin'), async (req: Request, res: Response) => {
1088: router.post('/users/import', requireRole('hr_admin'), requireTenantAdminIdentity, importUpload.single('file'), async (req: Request, res: Response) => {
1273: router.get('/announcements', requireRole('super_admin'), async (req: Request, res: Response) => {
1299: router.post('/announcements', requireRole('super_admin'), async (req: Request, res: Response) => {
1328: router.put('/announcements/:id', requireRole('super_admin'), async (req: Request, res: Response) => {
1382: router.delete('/announcements/:id', requireRole('super_admin'), async (req: Request, res: Response) => {

## pending matrix rows (admin/org)
| GET | `/api/admin/announcements` | pending | A-108/A-110 |
| POST | `/api/admin/announcements` | pending | A-108/A-110 |
| DELETE | `/api/admin/announcements/:id` | pending | A-108/A-110 |
| PUT | `/api/admin/announcements/:id` | pending | A-108/A-110 |
| GET | `/api/admin/audit-logs` | pending | A-108/A-110 |
| GET | `/api/admin/invite-codes` | pending | A-108/A-110 |
| POST | `/api/admin/invite-codes` | pending | A-108/A-110 |
| DELETE | `/api/admin/invite-codes/:id` | pending | A-108/A-110 |
| GET | `/api/admin/organizations` | pending | A-108/A-110 |
| POST | `/api/admin/organizations` | pending | A-108/A-110 |
| DELETE | `/api/admin/organizations/:id` | pending | A-108/A-110 |
| GET | `/api/admin/organizations/:id` | pending | A-108/A-110 |
| PUT | `/api/admin/organizations/:id` | pending | A-108/A-110 |
| GET | `/api/admin/pay-periods` | pending | A-108/A-110 |
| POST | `/api/admin/pay-periods` | pending | A-108/A-110 |
| DELETE | `/api/admin/pay-periods/:id` | pending | A-108/A-110 |
| GET | `/api/admin/registration-settings` | pending | A-108/A-110 |
| PUT | `/api/admin/registration-settings` | pending | A-108/A-110 |
| GET | `/api/admin/role-requests` | pending | A-108/A-110 |
| POST | `/api/admin/role-requests/:id/approve` | pending | A-108/A-110 |
| POST | `/api/admin/role-requests/:id/cancel` | pending | A-108/A-110 |
| POST | `/api/admin/role-requests/:id/reject` | pending | A-108/A-110 |
| GET | `/api/admin/stats` | pending | A-108/A-110 |
| GET | `/api/admin/task-labels` | pending | A-108/A-110 |
| POST | `/api/admin/task-labels` | pending | A-108/A-110 |
| DELETE | `/api/admin/task-labels/:id` | pending | A-108/A-110 |
| PUT | `/api/admin/task-labels/:id` | pending | A-108/A-110 |
| GET | `/api/admin/tenants` | pending | A-108/A-110 |
| POST | `/api/admin/tenants` | pending | A-108/A-110 |
| DELETE | `/api/admin/tenants/:id` | pending | A-108/A-110 |
| GET | `/api/admin/tenants/:id` | pending | A-108/A-110 |
| PUT | `/api/admin/tenants/:id` | pending | A-108/A-110 |
| GET | `/api/admin/tenants/:id/access-requests` | pending | A-108/A-110 |
| POST | `/api/admin/tenants/:id/access-requests` | pending | A-108/A-110 |
| PUT | `/api/admin/tenants/:id/domain` | pending | A-108/A-110 |
| POST | `/api/admin/tenants/:id/exit-impersonate` | pending | A-108/A-110 |
| PUT | `/api/admin/tenants/:id/features` | pending | A-108/A-110 |
| POST | `/api/admin/tenants/:id/impersonate` | pending | A-108/A-110 |
| GET | `/api/admin/tenants/:id/impersonation-session` | pending | A-108/A-110 |
| PUT | `/api/admin/tenants/:id/limits` | pending | A-108/A-110 |
| PUT | `/api/admin/tenants/:id/plan` | pending | A-108/A-110 |
| PUT | `/api/admin/tenants/:id/reactivate` | pending | A-108/A-110 |
| POST | `/api/admin/tenants/:id/seed` | pending | A-108/A-110 |
| GET | `/api/admin/tenants/:id/stats` | pending | A-108/A-110 |
| PUT | `/api/admin/tenants/:id/suspend` | pending | A-108/A-110 |
| GET | `/api/admin/tenants/:id/users` | pending | A-108/A-110 |
| POST | `/api/admin/tenants/:id/users` | pending | A-108/A-110 |
| PUT | `/api/admin/tenants/:tenantId/users/:userId/deactivate` | pending | A-108/A-110 |
| GET | `/api/admin/tenants/access-requests` | pending | A-108/A-110 |
| DELETE | `/api/admin/tenants/access-requests/:reqId` | pending | A-108/A-110 |
| GET | `/api/admin/tenants/alerts` | pending | A-108/A-110 |
| GET | `/api/admin/tenants/announcements` | pending | A-108/A-110 |
| POST | `/api/admin/tenants/announcements` | pending | A-108/A-110 |
| DELETE | `/api/admin/tenants/announcements/:id` | pending | A-108/A-110 |
| PUT | `/api/admin/tenants/announcements/:id` | pending | A-108/A-110 |
| GET | `/api/admin/tenants/audit-logs` | pending | A-108/A-110 |
| GET | `/api/admin/tenants/impersonation-policy` | pending | A-108/A-110 |
| PUT | `/api/admin/tenants/impersonation-policy` | pending | A-108/A-110 |
| GET | `/api/admin/tenants/overview` | pending | A-108/A-110 |
| GET | `/api/admin/tenants/plan-catalog` | pending | A-108/A-110 |
| PUT | `/api/admin/tenants/plan-catalog` | pending | A-108/A-110 |
| POST | `/api/admin/tenants/plan-catalog/reset` | pending | A-108/A-110 |
| GET | `/api/admin/tenants/platform-config` | pending | A-108/A-110 |
| PUT | `/api/admin/tenants/platform-config` | pending | A-108/A-110 |
| GET | `/api/admin/tenants/platform-users` | pending | A-108/A-110 |
| POST | `/api/admin/tenants/platform-users` | pending | A-108/A-110 |
| PUT | `/api/admin/tenants/platform-users/:id/deactivate` | pending | A-108/A-110 |
| GET | `/api/admin/tenants/platform-users/:id/links` | pending | A-108/A-110 |
| POST | `/api/admin/tenants/platform-users/:id/links` | pending | A-108/A-110 |
| DELETE | `/api/admin/tenants/platform-users/:id/links/:tenantId` | pending | A-108/A-110 |
| POST | `/api/admin/tenants/platform-users/:id/reset-password` | pending | A-108/A-110 |
| GET | `/api/admin/users` | pending | A-108/A-110 |
| POST | `/api/admin/users` | pending | A-108/A-110 |
| DELETE | `/api/admin/users/:id` | pending | A-108/A-110 |
| GET | `/api/admin/users/:id` | pending | A-108/A-110 |
| PUT | `/api/admin/users/:id/assignment` | pending | A-108/A-110 |
| PUT | `/api/admin/users/:id/deactivate` | pending | A-108/A-110 |
| DELETE | `/api/admin/users/:id/face-enroll` | pending | A-108/A-110 |
| POST | `/api/admin/users/:id/reset-password` | pending | A-108/A-110 |
| PUT | `/api/admin/users/:id/role` | pending | A-108/A-110 |
| POST | `/api/admin/users/import` | pending | A-108/A-110 |
| POST | `/api/org/invite` | pending | A-105 |
| POST | `/api/org/remove-member` | pending | A-105 |
| GET | `/api/org/roles` | pending | A-105 |
| POST | `/api/org/roles` | pending | A-105 |
| DELETE | `/api/org/roles/:role_key` | pending | A-105 |
| PATCH | `/api/org/roles/:role_key` | pending | A-105 |
| PUT | `/api/org/settings` | pending | A-105 |

## shapes (bare JSON)
- GET audit-logs ?actor_id&entity_type&entity_id&action&from&to&limit(<=500,def100)&offset&org_id(platform) -> queryLogs result
- GET stats -> {totalUsers,activeUsers,departments,teams,pendingApprovals,clockedInToday}
- GET registration-settings -> {mode} (default open); PUT {mode} platform_admin -> {mode,message}
- GET invite-codes -> [ic.* + created_by_name]; POST {role?,max_uses?,expires_days?} -> {code,message}; DELETE :id -> {message} (deactivates)
- GET organizations ?page&per_page -> {data:[{id,name,slug,timezone,work_hours_per_day,work_days,fiscal_year_start,member_count}],total,page,perPage} (platform_admin)
- GET organizations/:id -> {...org, memberCount, deptCount, teamCount}; POST {name,work_hours_per_day,work_days,timezone} -> {id,name,slug,message}; PUT :id {name,work_hours_per_day,work_days,timezone,fiscal_year_start} -> org row; DELETE :id -> {message}
- GET users ?search&role&is_active&org_id&page&per_page(<=100,def50) -> {data:[{id,username,full_name,email,avatar,role,is_active,org_id,department_id,team_id,manager_id,created_at,org_name,department_name,team_name,manager_name}],total,page,perPage}
- GET users/:id -> same + timezone_offset
- PUT users/:id/role {role,reason} -> {message, immediate?:true} | {message, request_id, pending:true}
- GET role-requests ?status -> [r.* + target_name,target_username,requester_name,current_role,requested_role] (r.*: id,org_id,target_user_id,requested_by,from_role,to_role,status,reason,reject_reason?,approvals{role:{status,by,at}},created_at,resolved_at)
- POST role-requests/:id/approve -> {message,fully_approved}; /reject {reject_reason} -> {message}; /cancel -> {message}
- PUT users/:id/assignment {org_id,department_id,team_id,manager_id} -> {message}
- PUT users/:id/deactivate (TOGGLE) -> {message,is_active}
- POST users/:id/reset-password {new_password} -> {message}
- DELETE users/:id/face-enroll -> {message}; DELETE users/:id (super_admin) -> {message}
- POST users {username,password?,full_name,email,role,org_id,department_id,team_id,manager_id} -> {id,message,initial_password?}
- GET task-labels -> [tl.* + created_by_username]; POST {name,color} -> label; PUT :id {name,color} -> row; DELETE -> {message}
- GET pay-periods ?org_id -> [pp.* (id,org_id,label,start_date,end_date,locked_by,created_at?) + locked_by_name]; POST {label,start_date,end_date} -> row (409 dup); DELETE :id -> {message}
- POST users/import JSON {users:[{username,password,full_name,email,role,department_name,team_name,manager_username}], org_id?} or multipart file -> {imported:N, failed:[{row,error}], details:[...]}
- announcements (super_admin): GET -> {data:[a.* + created_by_name, org_name?]}; POST {message,type(info|warning|success|urgent|quote),duration(hours)} -> {data:row}; PUT :id {message?,type?,is_active?,duration?} -> {data}; DELETE -> {ok:true}
- audit-logs -> {total, logs:[al.* + actor_username, actor_name, actor_is_inspector, actor_inspector_real_name, actor_inspector_username]}
## /org extras (server/routes/organization.ts)
- PUT /org/settings (hr_admin) {name*,work_hours_per_day*,work_days "1,2,3",timezone,fiscal_year_start,min_hours_present,office_start_time HH:MM,attendance_verification_enabled,office_latitude,office_longitude,office_radius_m(10..10000),office_address,office_wifi_bssids[{bssid,label}],office_wifi_verification_enabled,biometric_login_enabled} (*super_admin only)
- POST /org/invite {user_id,role,department_id,team_id} -> {message}
- POST /org/remove-member {user_id} -> {message}
- GET /org/roles ?org_id -> {defaults:[role], roles:[role+user_count]} role={role_key,label,description,color,permission_level(1..4),is_system,sort_order,customised}
- POST /org/roles {role_key,label,description,color,permission_level,org_id?} -> {defaults,roles}
- PATCH /org/roles/:key {label?,description?,color?,permission_level?,sort_order?} -> {defaults,roles}
- DELETE /org/roles/:key ?org_id -> {defaults,roles}

## Web UI reality check (2026-09-26)
- Mounted admin SECTIONS: home, users, add, role-requests, departments, teams, org-chart, agile, projects, integrations, payroll, compensation, salary-slips, payment-config, audit, platform-access, org-settings (rail: general, attendance, roles, branding, email-templates)
- NO web client wrapper at all: admin/invite-codes (GET/POST/DELETE), admin/registration-settings (GET/PUT)
- Wrapper exists but NO page calls it: admin/task-labels (UI uses tasks/labels), admin/announcements (AnnouncementsTab.tsx not mounted; /platform uses tenants/announcements), org/invite, org/remove-member, admin/users/:id/face-enroll (no wrapper)
- OrganizationsTab.tsx (admin/organizations CRUD) not mounted in index.tsx; AddPeopleWizard/CreateUser/UserManagement call getAdminOrganizations for pickers

- registration-settings: GET {mode} (open|invite_only|closed), PUT {mode} -> {mode,message}
- invite-codes: GET rows ic.* + created_by_name (code,role,max_uses,uses?,expires_at,is_active); POST {role,max_uses,expires_days} -> {code,message}; DELETE -> deactivates {message}. roles employee|team_lead|manager|hr_admin, below own level
- User decision: BUILD EVERYTHING incl. android-only screens for unused endpoints

- endpoint-parity: '// @api METHOD path' markers are scanned; use them on every call. ApiRequest(method, path, headers, body). OrganizationRepository has load/mutate/query/serverMessage plumbing + OrganizationFailure/orgMessage

## schema
- invite_codes: id,code,created_by,org_id,role,max_uses(def1; 0=unlimited on POST),used_count,expires_at,is_active,created_at
- organizations: work_hours_per_day INTEGER, work_days TEXT (0-6 in /org/settings; admin/organizations validates 1-7!), timezone, fiscal_year_start INT, min_hours_present NUMERIC(string!), office_latitude DOUBLE
- pay_periods: id,org_id,label,start_date,end_date,locked_by,locked_at,created_at
- announcements: id,org_id,created_by,message,type,is_active,expires_at,created_at
- audit_logs: id,org_id,actor_id,action,entity_type,entity_id,details TEXT,ip_address,user_agent,created_at
- role_change_requests: ...,rejected_by
## Batch1 design
- new package feature/admin (own UI components; boundary forbids importing organization/tasks). Routes admin/s/{key} + admin/users/{userId}; AdminScreen (tasks) section list extended; depts/teams/org-chart -> Organization route

## inventory admin/org
[ ] GET /api/admin/announcements
[ ] POST /api/admin/announcements
[ ] DELETE /api/admin/announcements/:id
[ ] PUT /api/admin/announcements/:id
[ ] GET /api/admin/audit-logs
[ ] GET /api/admin/invite-codes
[ ] POST /api/admin/invite-codes
[ ] DELETE /api/admin/invite-codes/:id
[ ] GET /api/admin/organizations
[ ] POST /api/admin/organizations
[ ] DELETE /api/admin/organizations/:id
[ ] GET /api/admin/organizations/:id
[ ] PUT /api/admin/organizations/:id
[ ] GET /api/admin/pay-periods
[ ] POST /api/admin/pay-periods
[ ] DELETE /api/admin/pay-periods/:id
[ ] GET /api/admin/registration-settings
[ ] PUT /api/admin/registration-settings
[ ] GET /api/admin/role-requests
[ ] POST /api/admin/role-requests/:id/approve
[ ] POST /api/admin/role-requests/:id/cancel
[ ] POST /api/admin/role-requests/:id/reject
[ ] GET /api/admin/stats
[ ] GET /api/admin/task-labels
[ ] POST /api/admin/task-labels
[ ] DELETE /api/admin/task-labels/:id
[ ] PUT /api/admin/task-labels/:id
[ ] GET /api/admin/tenants
[ ] POST /api/admin/tenants
[ ] DELETE /api/admin/tenants/:id
[ ] GET /api/admin/tenants/:id
[ ] PUT /api/admin/tenants/:id
[ ] GET /api/admin/tenants/:id/access-requests
[ ] POST /api/admin/tenants/:id/access-requests
[ ] PUT /api/admin/tenants/:id/domain
[ ] POST /api/admin/tenants/:id/exit-impersonate
[ ] PUT /api/admin/tenants/:id/features
[ ] POST /api/admin/tenants/:id/impersonate
[ ] GET /api/admin/tenants/:id/impersonation-session
[ ] PUT /api/admin/tenants/:id/limits
[ ] PUT /api/admin/tenants/:id/plan
[ ] PUT /api/admin/tenants/:id/reactivate
[ ] POST /api/admin/tenants/:id/seed
[ ] GET /api/admin/tenants/:id/stats
[ ] PUT /api/admin/tenants/:id/suspend
[ ] GET /api/admin/tenants/:id/users
[ ] POST /api/admin/tenants/:id/users
[ ] PUT /api/admin/tenants/:tenantId/users/:userId/deactivate
[ ] GET /api/admin/tenants/access-requests
[ ] DELETE /api/admin/tenants/access-requests/:reqId
[ ] GET /api/admin/tenants/alerts
[ ] GET /api/admin/tenants/announcements
[ ] POST /api/admin/tenants/announcements
[ ] DELETE /api/admin/tenants/announcements/:id
[ ] PUT /api/admin/tenants/announcements/:id
[ ] GET /api/admin/tenants/audit-logs
[ ] GET /api/admin/tenants/impersonation-policy
[ ] PUT /api/admin/tenants/impersonation-policy
[ ] GET /api/admin/tenants/overview
[ ] GET /api/admin/tenants/plan-catalog
[ ] PUT /api/admin/tenants/plan-catalog
[ ] POST /api/admin/tenants/plan-catalog/reset
[ ] GET /api/admin/tenants/platform-config
[ ] PUT /api/admin/tenants/platform-config
[ ] GET /api/admin/tenants/platform-users
[ ] POST /api/admin/tenants/platform-users
[ ] PUT /api/admin/tenants/platform-users/:id/deactivate
[ ] GET /api/admin/tenants/platform-users/:id/links
[ ] POST /api/admin/tenants/platform-users/:id/links
[ ] DELETE /api/admin/tenants/platform-users/:id/links/:tenantId
[ ] POST /api/admin/tenants/platform-users/:id/reset-password
[ ] GET /api/admin/users
[ ] POST /api/admin/users
[ ] DELETE /api/admin/users/:id
[ ] GET /api/admin/users/:id
[ ] PUT /api/admin/users/:id/assignment
[ ] PUT /api/admin/users/:id/deactivate
[ ] DELETE /api/admin/users/:id/face-enroll
[ ] POST /api/admin/users/:id/reset-password
[ ] PUT /api/admin/users/:id/role
[ ] POST /api/admin/users/import
[x] POST /api/org
[x] GET /api/org/chart
[x] GET /api/org/current
[x] GET /api/org/departments
[x] POST /api/org/departments
[x] DELETE /api/org/departments/:id
[x] PUT /api/org/departments/:id
[ ] POST /api/org/invite
[x] GET /api/org/members
[ ] POST /api/org/remove-member
[ ] GET /api/org/roles
[ ] POST /api/org/roles
[ ] DELETE /api/org/roles/:role_key
[ ] PATCH /api/org/roles/:role_key
[ ] PUT /api/org/settings
[x] GET /api/org/teams
[x] POST /api/org/teams
[x] DELETE /api/org/teams/:id
[x] PUT /api/org/teams/:id
[x] GET /api/org/teams/:id/sprint-config
[x] PUT /api/org/teams/:id/sprint-config

## Batch1 UI facts (session 3)
- web sections w/ feature: payroll(feature payroll), agile/projects(feature agile); approver=super/hr/platform; audit ungated in UI but server requireFeature audit_logs
- UserDrawer: role select (super/platform => 'Update role' msg 'Role updated'; else reason 'Reason (will be shown to approvers)' placeholder 'Why this change?' + 'Submit role request' msg 'Role change request submitted'); canAssignRole = !platformTarget && !self; canDelete = !self && !platformTarget && super/platform; toggle confirm '${action} user' hints; delete typed confirm username 'Delete permanently'; reset pw 'New password (min 8 chars)' 'Minimum 8 characters' msg 'Password reset'; errors 'Action failed'
- Announcement TYPES info/success/warning/urgent/quote; DURATIONS ''=No expiry,1,6,12,24(1 day),72(3 days),168(1 week),336(2 weeks),720(1 month); edit 'Keep current'
- Audit ENTITY_TYPES user,leave,time_entry,task,team,department,organization,leave_policy,holiday,approval_request,role_change_request; ACTIONS create,update,delete,approve,reject,login,update_role,request_role_change,approve_role_change,reject_role_change,deactivate,reactivate,admin_create,admin_update,admin_delete,admin_reset_password,invite,remove_member
- OrgRoleLabels PERMISSION_LEVELS 1 Standard member,2 Team lead,3 Manager,4 HR admin; default color #6366f1; HEX check 'Color must be #RRGGBB'
- PayPeriods errors: 'Label is required','Start and end dates are required','End date must be on or after start date'; 'No pay periods locked yet.'
- OrgSettings labels: Organization Name, Work Hours Per Day, Working Days, Timezone, Fiscal Year Start Month (1-12), Minimum Hours to be Marked Present (optional), Regular Office Start Time, Allow biometric & passkey sign-in; msgs 'Settings saved','Pick at least one working day','Failed'; button Save Settings
- import: server JSON body {users:[..]} supported -> Android uses JSON (paste CSV parsed client-side)
- ProjectsRepositoryTest.adminSectionsAreGatedByOrgAndAgileFeature must be updated when sections grow
- OfficeLocation labels: Require face + location for clock-in, Office Address (optional) ph 'e.g. 5th Floor, Tech Park, Pune', Latitude 'e.g. 19.076000', Longitude 'e.g. 72.877700', Geofence Radius (metres) hint 'Allowed: 10–10000 m. Smaller is stricter.', Office Wi-Fi (recommended), Trust office Wi-Fi for clock-in, BSSID (AA:BB:CC:DD:EE:FF), Label (e.g. Floor 5 AP), Add, Save Attendance Settings; payload lat,lng,radius(def150),address|null,verify,wifi_bssids[{bssid,label}],wifi verify
- org/invite {user_id,role,department_id,team_id} -> {message '<name> added to the organization'}; remove-member {user_id}

## P10.2 decision (2026-09-26)
- User: platform console is web/desktop only. P10.2 = N/A, endpoints stay pending. More > Tenants shows a 'use the web console' placeholder. Contract kept in docs/phase10-tenants-contract.md.


## P10.3 Compensation - DONE (2026-09-26)
- 34/34 compensation endpoints; coverage 326/464 + 44 waived; 557 tests green; all guards pass.
- Decision: Organization -> Salary Slips tab first/default only with the payroll feature.
- P10.2 follow-up: 44 tenants endpoints moved to docs/parity-waivers.json (script supports waivers); footnote 3 updated; service-desk GET/PATCH tickets/:id stay pending (not platform-realm).
- Contract doc completed (endpoints 21-34 + Android mapping).

## P10.4 Branding - DONE (2026-09-27)
- 10/10 branding endpoints; coverage 336/464 + 44 waived, 84 pending.
- Admin Branding + Email templates share BrandingViewModel (own VM, refetched via AdminUiState.settingsRefresh). Email preview = WebView with JS/file/content access off, navigation blocked.
- CalendarViewModel change = new events default to the org accent (web branding.accent_color || #2383e2).
- Build note: a parallel Gradle build in the same tree corrupts compile outputs; rerun with --rerun-tasks.
