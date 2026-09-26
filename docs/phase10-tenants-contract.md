# Phase 10 — Tenants (Platform Console) HTTP + UI Contract

Source repo: `aino-platform`. Extracted from `server/routes/tenants.ts`, `server/routes/platformAnnouncements.ts`,
`server/services/{platformAnnouncements,platformUserManagement}.ts`, `server/utils/{platformConfig,planCatalog,impersonationApproval,platformAudit,cookie}.ts`,
`server/middleware/{platformIdentity,auth,rbac,impersonationAudit}.ts`, `server/http/routes.ts`, `server/platform/db/{masterSchema,tenantPools}.ts`,
`client/src/api/organization.ts`, `client/src/pages/tenants/*.tsx`, `client/src/components/common/{KeepAlive,ImpersonationBanner}.tsx`, `client/src/App.tsx`.

All errors are JSON `{ error: string, code?: string }`. Generic 500 message given per endpoint as "500:".

---

## 0. Mounting, auth, routing

- Mount: `server/http/routes.ts:80` → `app.use("/api/admin/tenants", apiLimiter, tenantRoutes)`, mounted BEFORE `/api/admin` (the general admin router has `requireTenant` and would otherwise answer "Organization context required").
- Global `/api` middleware that runs first: `impersonationAudit`, `maintenanceModeMiddleware`.
- Guard for the whole router (`tenants.ts:55`): `router.use(auth, loadUserContext, requireRole("platform_admin"), requirePlatformIdentity)`.
  - `auth`: verifies the JWT from cookie or Bearer and checks its realm. Wrong realm → 401 `{error:"This session is not valid for this site. Please sign in again.", code:"WRONG_REALM"}`. tv mismatch → 401 "Session expired. Please sign in again."; session missing → 401 "Session ended. You may have signed in on another device."; idle → 401 code `SESSION_IDLE_EXPIRED` (idle check is skipped for tenantless platform users).
  - `loadUserContext`: a tenantless platform user (`decoded.platform` truthy, no `tenant_id`) gets `userRole="platform_admin"` with no DB lookup.
  - `requirePlatformIdentity`: 403 `{error:"A tenantless platform administrator identity is required", code:"PLATFORM_IDENTITY_REQUIRED"}` if `!req.isPlatformUser || req.tenantId != null`. If `CONSOLE_HOST` is set and `req.realm !== "platform"` → 403 `{error:"The platform console must be used for this operation.", code:"PLATFORM_REALM_REQUIRED"}`.
- Cookies (`utils/cookie.ts`): tenant realm = `token`; platform realm = `aino_console` (on CONSOLE_HOST). No `domain` attribute.
- Nested: `router.use("/announcements", platformAnnouncementsRoutes)` (inherits the guard).
- Literal paths are registered before `/:id`. `:id` goes through `Number(...)` only (no NaN check).

### Web routing / guards
- One path, `/tenants` (in `KEEP_ALIVE_PATHS`, App.tsx:120), lazy-loads `pages/tenants/index.tsx` via `KeepAlive.tsx`.
- `ROLE_REQUIREMENTS["/tenants"] = "platform_admin"` (role-level check in KeepAlive). The page itself also checks `user.role !== "platform_admin"`.
- Realm gate: if `VITE_CONSOLE_HOST` is set, the platform realm mounts only `/tenants` and the tenant realm mounts everything except `/tenants`. Wrong plane → `<Navigate to={realmHomePath(realm)}>`.
- A tenantless platform admin can mount only `/tenants`. Other pages show "No tenant selected" / "This workspace is tenant-scoped. Create or select a tenant from the platform console before using this section." + link "Open Platform Console".
- `must_change_password` → `/change-password` first.
- Section is chosen by query `?tab=`: `dashboard` (default; the param is removed), `tenants`, `create`, `plans`, `admins`, `settings`, `audit`. Tenant drill-down is component state (`selectedTenantId`), not a URL.


---

## 1. Enumerations / catalogs

### Tenant status (DB CHECK masterSchema.ts:26)
- `active` | `suspended` | `migrating` | `deleted` (default `active`). The web filter offers active/suspended/deleted only.
- The list excludes `deleted` by default; `?status=deleted` returns only deleted tenants.

### Plans (`planCatalog.ts` DEFAULT_PLANS; `PLAN_KEYS` = `standard, pro, enterprise`)
- DB CHECK `tenants_plan_check`: plan IN ('standard','pro','enterprise'); default `standard`. `PLAN_RANK` standard 1, pro 2, enterprise 3.

| key | label | description | max_users | max_storage_mb |
|---|---|---|---|---|
| standard | "Standard" | "Essential workforce management" | 25 | 5120 |
| pro | "Pro" | "Collaboration & payroll" | 100 | 25600 |
| enterprise | "Enterprise" | "Full platform with unlimited access" | null | null |

- Catalog lives in `app_settings` key `plans_catalog` (JSON) and is cached for 60s. It can hold custom plan keys, but those can't be assigned to tenants (PLAN_KEYS and the DB CHECK only allow the 3 base plans).

### Feature keys (`FEATURE_LABELS`; order = `FEATURE_KEYS`) + plan defaults
| key | label | std | pro | ent |
|---|---|---|---|---|
| attendance | "Attendance & Time Tracking" | on | on | on |
| leaves | "Leave Management" | on | on | on |
| tasks | "Tasks & Kanban" | on | on | on |
| calendar | "Calendar" | on | on | on |
| notes | "Notes & Wiki" | on | on | on |
| notifications | "Notifications" | on | on | on |
| export | "Export & Reports" | on | on | on |
| chat | "Chat & Messaging" | off | on | on |
| calls | "Audio/Video Calls" | off | on | on |
| meetings | "Scheduled Meetings" | off | off | on |
| agile | "Agile & Sprints" | off | off | on |
| payroll | "Payroll & Compensation" | off | on | on |
| custom_fields | "Custom Fields" | off | off | on |
| audit_logs | "Audit Logs" | off | off | on |
| webhooks | "Webhooks & Integrations" | off | off | on |

- Override coercion: true/'true'/1/'1'/'on'/'yes' → true; false/'false'/0/'0'/'off'/'no'/'' → false; null/undefined/anything else → null (not overridden).
- Non-feature keys in `tenants.features` (e.g. `registration_mode`) are kept as "extras". Effective features = plan defaults, then overrides; missing keys → false.

### Limit keys
- `max_users` (int, or null = unlimited), `max_storage_mb` (int, or null). Web labels: "Max Users", "Max Storage (MB)" / "Max Storage". Defaults per plan are in the table above.
- Catalog limit validation: must be a non-negative int, or null/"" → null. Otherwise 400 `Plan "<key>" has an invalid <field>: must be a non-negative integer or null (unlimited).`


### Platform config keys (`platformConfig.ts`; stored in `app_settings`; ALL values are strings)
| key | semantic type | default |
|---|---|---|
| maintenance_mode | bool-string "true"/"false" | "false" |
| maintenance_message | string | "" (runtime fallback: "The system is currently under maintenance. Please try again later.") |
| password_min_length | int-string | "8" |
| password_require_uppercase | bool-string | "true" |
| password_require_number | bool-string | "true" |
| password_require_special | bool-string | "false" |
| allowed_email_domains | CSV string | "" |
| audit_log_retention_days | int-string | "365" |
| deleted_tenant_cleanup_days | int-string | "90" |
| session_log_retention_days | int-string | "90" |

### Impersonation policy (keys in `app_settings`; API response is camelCase)
| response field | app_settings key | default | PUT body field | validation |
|---|---|---|---|---|
| requiresConsent | impersonation_requires_consent | true | requires_consent (bool) | ignored if not boolean |
| breakGlassAllowed | impersonation_break_glass_allowed | false | break_glass_allowed (bool) | ignored if not boolean |
| maxSessionMinutes | impersonation_max_session_minutes | 60 | max_session_minutes (int) | 5..240, else 400 |
| codeTtlMinutes | impersonation_code_ttl_minutes | 15 | code_ttl_minutes (int) | 1..60, else 400 |

### Access request enums (master `tenant_access_requests`)
- scope: `read` | `write` (default write). duration_minutes: DB CHECK 5..240 (default 30).
- status: `pending|approved|denied|consumed|expired|revoked|cancelled`. API `status` is the effective status (approved + `code_expires_at` in the past → `expired`); `raw_status` is the DB value.

### Announcement types
- Valid: `info|warning|success|urgent|quote`. Create: an invalid type silently becomes `info`. Update: an invalid type → 400. The web offers info/success/warning/urgent.

### Alert types (GET /alerts)
- `users_approaching_limit` (≥80% of max_users), `storage_approaching_limit` (≥80% of max_storage_mb; only for DBs on the same host), `no_active_super_admin`.

### Audit actions the server emits
`tenant_created, tenant_updated, tenant_suspended, tenant_suspend_reauth_failed, tenant_reactivated, tenant_soft_deleted, tenant_hard_deleted, tenant_delete_reauth_failed, tenant_domain_changed, tenant_features_updated, tenant_plan_changed, tenant_limits_updated, tenant_impersonation_session, tenant_impersonation_reauth_failed, tenant_impersonation_bad_code, platform_tenant_user_read, tenant_user_created, platform_tenant_user_deactivated, tenant_seeded, plan_catalog_updated, plan_catalog_reset, impersonation_policy_updated, tenant_access_request_created, tenant_access_request_cancelled, platform_config_updated, platform_admin_created, platform_admin_deactivated, platform_admin_reactivated, platform_admin_reset_password, platform_principal_linked, platform_principal_unlinked, create_announcement, update_announcement, delete_announcement`.
Entity types: `tenant, user, platform_user, platform, platform_settings, tenant_access_request, platform_announcement`.

---

## 2. Endpoints (prefix `/api/admin/tenants`) — 44 total

Format: `METHOD path | query | body | response | errors | web caller`.

Tenant row columns (masterSchema): `id, org_name, slug, db_name, db_host, custom_domain, status, max_users, max_storage_mb, features (JSONB), is_default, suspended_at, suspended_reason, created_at, updated_at, plan`.

### A. Tenant CRUD & lifecycle
1. `POST /` | – | `org_name:string req`; `slug:string req` (regex `^[a-z0-9][a-z0-9-]{1,48}[a-z0-9]$`, 3–50 chars); `plan:string opt` (default "standard"); `features:object opt` ({}); `max_users:int opt` (falsy → null); `max_storage_mb:int opt` (falsy → null) | 201 `{ tenant }` (full row) | 400 "org_name and slug are required"; 400 "Slug must be 3-50 chars, lowercase alphanumeric with dashes, no leading/trailing dash."; 400 "Invalid plan. Must be one of: standard, pro, enterprise"; 409 "A tenant with that slug already exists."; 500 "Failed to create tenant". The platform admin is NOT added as a user in the new tenant. | CreateTenant
2. `GET /` | `status` (any value; `deleted` is special; omitted → excludes deleted); `search` (ILIKE on org_name/slug); `limit` (default 50, clamped 1..200); `offset` (≥0) | `{ total:int, tenants:[ tenant row + user_count:string ] }`, newest first | 500 "Failed to list tenants" | TenantList; PlatformAuditLogs (`limit:200`)
3. `GET /overview` | – | – | `{ total_tenants:int (excludes deleted), total_users:int, by_status:{[status]:int}, by_plan:{[plan]:int} (active tenants only), trend_30d:[{day, count:string}], recent:[{id, org_name, slug, status, created_at}] (5, not deleted), pool_stats }` | 500 "Failed to get overview" | TenantList, PlatformDashboard
   - Real `pool_stats` shape: `{ poolCount, maxPools, poolSize, metrics:{evictions, hits, misses, …, hitRate, totalWaiting}, pools:{[dbName]:{total, idle, waiting, lastUsed}} }` (see §4.8).
4. `GET /:id` | – | – | tenant row spread + `user_count:int` (from user_directory) + `effective_features:{[fk]:bool}` | 404 "Tenant not found"; 500 "Failed to get tenant" | TenantDetail
5. `PUT /:id` | – | `org_name:string opt`; `max_users:number opt` (ignored unless >0); `max_storage_mb:number opt` (ignored unless >0); sending `features` is FORBIDDEN | `{ tenant }` | 404 "Tenant not found"; 400 `{error:"Cannot update features via PUT /:id. Use PUT /:id/features (merge) or PUT /:id/plan instead.", code:"FEATURES_REQUIRES_DEDICATED_ENDPOINT"}`; 500 "Failed to update tenant" | TenantDetail Settings (sends only `{org_name}`)

6. `PUT /:id/suspend` | – | `reason:string opt`; `password:string req` | `{ tenant }` | 403 "The default platform tenant cannot be suspended." (checked first); 400 `{error:"Your password is required to confirm this action.", code:"REAUTH_REQUIRED"}`; 403 "Your account is no longer active."; 401 "Password did not match. Please try again." (audited as `tenant_suspend_reauth_failed`); 404 "Tenant not found"; 500 "Failed to suspend tenant" | TenantList, TenantDetail
7. `PUT /:id/reactivate` | – | none | `{ tenant }` | 404 "Tenant not found"; 500 "Failed to reactivate tenant". No password needed. | TenantList, TenantDetail
8. `DELETE /:id` | `hard` (`"true"` → hard delete; anything else → soft) | JSON body on DELETE: `password:string req` | `{ message: "Tenant permanently deleted." | "Tenant marked as deleted." }` | 403 "The default platform tenant cannot be deleted."; 400 REAUTH_REQUIRED (same as suspend); 403 "Your account is no longer active."; 401 "Password did not match. Please try again." (audited as `tenant_delete_reauth_failed`); 404 "Tenant not found"; 500 "Failed to delete tenant" | TenantList and TenantDetail (both always hard=false). Web: `API.delete(url, { params:{hard}, data:{password} })`. Android: `@HTTP(method="DELETE", hasBody=true)`.
   - Password re-check (`verifyActorPassword`): tenantless caller → `platform_users`; caller in tenant context → tenant `users`.
9. `GET /:id/stats` | – | – | `{ user_count:int (active users in tenant DB), task_count:int|null, message_count:int|null, db_size_bytes:int, storage_bytes:int|null, storage_objects:int|null, storage_by_kind:{[kind]:{objects, bytes}}|null, last_activity:ts|null, activity_restricted:bool, activity_restricted_reason?:"Tenant activity data requires an approved access session." }`. task/message/last_activity are null unless the tenant consented. | 404 "Tenant not found"; 500 "Failed to get stats" | TenantDetail (errors swallowed; stats becomes null)
10. `PUT /:id/domain` | – | `custom_domain:string|null|""` (falsy clears it); regex `^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$` (case-insensitive) | `{ tenant }` | 404 "Tenant not found"; 400 "Invalid domain format"; 409 `{error:"That hostname is reserved for the platform console and cannot be assigned to a tenant.", code:"RESERVED_DOMAIN"}`; 409 "This domain is already assigned to another tenant."; 500 "Failed to update domain" | TenantDetail Settings

11. `PUT /:id/features` | – | `features:object req` ({key: bool or coercible value}). The patch is MERGED into existing overrides: keys you leave out keep their old value, so an override can't be removed by omitting it. | `{ tenant, effective_features }` | 400 "features object is required"; 404 "Tenant not found"; 500 "Failed to update features". Sends WS `tenant_features_changed` to the tenant. | TenantDetail Settings
12. `PUT /:id/plan` | `dry_run` (`true`/`1`) | `plan:string req` (one of PLAN_KEYS); `apply_plan_limits:bool opt` (truthy → sets max_users/max_storage_mb to the plan's limits) | dry run: `{ dry_run:true, preview }`; otherwise `{ tenant, effective_features, preview }`. `preview = { from_plan, to_plan, features_disabled:[fk], features_enabled:[fk], new_max_users:int|null, new_max_storage_mb:int|null, current_users:int|null, over_user_limit:bool }` | 400 "Invalid plan. Must be one of: standard, pro, enterprise"; 404 "Tenant not found"; 500 "Failed to update plan". Sends WS `plan_changed`. | TenantDetail Settings (always `apply_plan_limits:true`, never dry-run)
13. `PUT /:id/limits` | – | `max_users:int|null opt`; `max_storage_mb:int|null opt`. Null/undefined means KEEP the current value (`??`), so this endpoint can't set a limit back to unlimited. No check that values are positive. | `{ tenant }` | 404 "Tenant not found"; 500 "Failed to update limits" | TenantDetail Settings

### B. Impersonation
14. `POST /:id/impersonate` | – | `password:string req`; `approval_code:string opt` (6 digits; required when consent is on and this isn't break-glass); `break_glass:bool opt` (must be literal `true`) | `{ tenant:{id, org_name, slug}, user:{id, username, full_name, email, role, token_version}, token:string (JWT), session:{ request_id:int|null, break_glass:bool, scope:"read"|"write", ends_at:ts, duration_minutes:int } }` **plus** Set-Cookie `token=<impersonation JWT>` (tenant cookie, maxAge = TTL) **plus** Set-Cookie `_wp_orig_token=<caller's current token>` (if the caller had one). All `cookieOptions` (HttpOnly). | 404 "Tenant not found or not active"; 400 `{error:"Password is required to start an impersonation session.", code:"REAUTH_REQUIRED"}`; 403 "Your platform account is no longer active."; 401 "Password did not match. Please try again." (no code; audited); 403 `{error:"Break-glass access is disabled by platform policy.", code:"BREAK_GLASS_DISABLED"}`; 400 `{error:"A 6-digit approval code is required.", code:"APPROVAL_CODE_REQUIRED"}`; 401 `{error:"The approval code is invalid or has expired. Ask the tenant to approve a new request.", code:"INVALID_APPROVAL_CODE"}` (audited as `tenant_impersonation_bad_code`); 500 "Failed to start impersonation" | RequestAccessModal
   - JWT claims: `{ id (Inspector's users.id in the tenant DB), username, tv, tenant_id, impersonated:true, impersonated_by:<platform user id>, impersonated_tenant_name, is_virtual:false, access_request_id, break_glass, scope, aud:"tenant" }`. `expiresIn = min(policy.maxSessionMinutes, request.duration_minutes || max)` minutes. There is NO `platform` claim.
   - Identity: a synthetic "Platform Inspector" user in the tenant DB (role `platform_admin`, `hidden_from_directory=TRUE`), created on first use and re-synced after that.
   - Side effects: the request becomes `consumed` (code hash cleared). Break-glass inserts a synthetic consumed request with reason "BREAK-GLASS EMERGENCY ACCESS". Writes an audit row `tenant_impersonation_session`, starts the in-memory session tracker, and sends WS `platform_access_session_started` to the tenant.
   - If `requiresConsent=false`: no code needed, TTL = policy max, scope "write".

15. `POST /:id/exit-impersonate` | – | none | `{ message:"Impersonation ended.", session_summary:{ session_start, total, reads, writes, actions:[{type:"read"|"write", method, path, status, timestamp}] } }`. If cookie `_wp_orig_token` is present → Set-Cookie `token=<orig>` (8h) and clears `_wp_orig_token`. | 500 "Failed to exit impersonation". Actor = `req.impersonatedBy || req.userId`. Fills in the audit row: `ended_at` + details `{target_user, duration_seconds, total_actions, reads, writes, actions}`. | ImpersonationBanner (app-wide, not a tenants page)
16. `GET /:id/impersonation-session` | – | – | `{ session_start, total, reads, writes, actions:[…] }` (if there's no session: empty summary with `session_start = now`) | 500 "Failed to get session actions" | ImpersonationBanner (polls every 15s)
   - **Warning:** 15 and 16 are behind `requirePlatformIdentity`. An impersonation token has `tenant_id` and no `platform` claim, so the code as written rejects it with 403 PLATFORM_IDENTITY_REQUIRED (§4.1).

### C. Cross-tenant users & seed
17. `GET /:id/users` | `search` (ILIKE on full_name/username/email); `limit` (50, 1..200); `offset` | `{ total:int, users:[{id, username, full_name, email, role, is_active, org_id, created_at}] }` (excludes role platform_admin and hidden users) | 404 "Tenant not found"; 403 `{error:"Tenant user data requires an approved access session.", code:"TENANT_USER_DATA_RESTRICTED"}` (no consent); 500 "Failed to list users". Audited as `platform_tenant_user_read`. | Not called (the `getTenantUsers` wrapper is unused)
18. `POST /:id/users` | – | `username req`; `password req` (validatePassword); `full_name req`; `email req`; `role opt` (only `"super_admin"` accepted) | 201 `{ user:{id, username, full_name, email, role} }` (created with `must_change_password=TRUE`, org_id 1) | 404 "Tenant not found or not active"; 400 "username, password, full_name and email are required"; 400 <password-policy message>; 400 <username message>; 400 `{error:"The initial tenant administrator must use the super_admin role", code:"INITIAL_ADMIN_ROLE_REQUIRED"}`; 409 "Email or username already exists globally."; 403 "Tenant user limit (<n>) reached."; 403 `{error:"Tenant user data requires an approved access session.", code:"TENANT_USER_DATA_RESTRICTED"}` (the tenant already has a non-platform user, so first-admin setup is closed); 500 "Failed to create user" | CreateTenant step 4
19. `PUT /:tenantId/users/:userId/deactivate` | – | none | `{ message:"User deactivated", user:{id, username} }` | 404 "Tenant not found"; 403 TENANT_USER_DATA_RESTRICTED; 404 "User not found"; 500 "Failed to deactivate user" | Not called
20. `POST /:id/seed` | – | none | `{ message:"Seed data applied", seeded:{ departments:int, leave_policies:int } }`. Safe to repeat. Departments: Engineering, Product, Design, Marketing, Sales, Human Resources, Finance. Leave policies: Annual 20 (carry-forward 5), Sick 10/0, Personal 5/0. | 404 "Tenant not found or not active"; 500 "Failed to seed tenant data" | CreateTenant step 5


### D. Plan catalog
21. `GET /plan-catalog` | – | – | `{ plans:{[key]:{label, description, features:{[fk]:bool}, limits:{max_users, max_storage_mb}}}, feature_labels:{[fk]:label}, feature_keys:[fk] }` | 500 "Failed to get plan catalog" | CreateTenant, TenantDetail Settings, PlanManagement
22. `PUT /plan-catalog` | – | `plans:object req` (not empty; must include standard, pro, enterprise; each plan needs a non-empty string `label`) | same shape as #21 (normalized) | 400 "plans object is required with at least one plan"; 400 validation messages: `Plan "<k>" must be an object with label, features and limits.` / `Plan "<k>" must have a non-empty string label.` / the invalid-limit message / `Plan catalog is missing required base plan(s): <list>.`; 500 "Failed to update plan catalog" | PlanManagement
23. `POST /plan-catalog/reset` | – | none | same shape as #21 (DEFAULT_PLANS) | 500 "Failed to reset plan catalog" | PlanManagement

### E. Platform users (`services/platformUserManagement.ts`)
24. `GET /platform-users` | – | – | **bare array** `[{ id, username, full_name, email, avatar, is_active, created_at, platform_role, mfa_required }]`, newest first | 500 "Failed to list platform users" | PlatformAdmins, PlatformAuditLogs
25. `POST /platform-users` | – | `username req`; `password req` (validatePassword); `full_name req`; `email req` (regex `^[^\s@]+@[^\s@]+\.[^\s@]+$`). Username and email are lowercased. | 201 `{ user:{id, username, full_name, email, is_active, created_at}, message:"Platform admin created successfully" }` | 400 "username, password, full_name and email are required"; 400 <pw message>; 400 <username message>; 400 "Invalid email format"; 409 "Username or email already exists"; 500 "Failed to create platform admin" | PlatformAdmins
26. `PUT /platform-users/:id/deactivate` (**toggles** active/inactive) | – | none | `{ message:"<full_name> has been reactivated|deactivated", is_active:bool }` | 400 "Cannot deactivate yourself"; 404 "Platform user not found"; 400 `{error:"Cannot deactivate the last active platform admin. Create or reactivate another platform admin first.", code:"LAST_PLATFORM_ADMIN"}`; 500 "Failed to update platform user". Deactivating bumps token_version and ends that user's sessions. | PlatformAdmins
27. `POST /platform-users/:id/reset-password` | – | `new_password:string req` (8..72 chars + validatePassword) | `{ message:"Password reset for <full_name>" }` | 400 `{error:"Use Change Password to update your own password without unexpectedly ending this console session.", code:"SELF_PASSWORD_RESET_DENIED"}`; 400 "Password must be at least 8 characters"; 400 "Password must be 72 characters or less"; 400 <pw message>; 404 "Platform user not found"; 500 "Failed to reset password" | PlatformAdmins
28. `GET /platform-users/:id/links` (platform_owner only) | – | – | `{ links:[{ platform_user_id, tenant_id, tenant_user_id, default_realm, linked_at, org_name, slug, status }] }` | 403 `{error:"Platform owner role required", code:"PLATFORM_OWNER_REQUIRED"}`. No try/catch. | PlatformAdmins
29. `POST /platform-users/:id/links` (owner only) | – | `tenant_id:int req`; `tenant_user_id:int req`; `default_realm:"platform"|"tenant" opt` (anything else → "tenant"). Upserts on (platform_user_id, tenant_id). | 201 `{ link:<row>, tenant_user:{id, username, full_name, email, is_active, hidden_from_directory} }` | 403 PLATFORM_OWNER_REQUIRED; 400 "tenant_id and tenant_user_id are required"; 404 "Platform user or tenant not found"; 400 `{error:"An active, visible tenant user is required", code:"INVALID_TENANT_PRINCIPAL"}` | PlatformAdmins
30. `DELETE /platform-users/:id/links/:tenantId` (owner only) | – | – | `{ message:"Linked tenant principal removed" }` | 403 PLATFORM_OWNER_REQUIRED; 404 "Link not found" | PlatformAdmins


### F. Announcements (`/announcements`; master table `platform_announcements`)
31. `GET /announcements` | – | – | `{ data:[ row + created_by_name ] }` (all rows, newest first, LIMIT 100). Row (`a.*`) includes `id, created_by, message, type, is_active, expires_at, created_at`. | 500 "Failed to fetch announcements" | PlatformSettings
32. `POST /announcements` | – | `message:string req` (trimmed, cut to 500 chars); `type opt` (invalid → "info"); `duration:number|string|null opt` (HOURS; >0 sets expires_at, otherwise null) | 201 `{ data: row }` | 400 "Message is required"; 500 "Failed to create announcement" | PlatformSettings
33. `PUT /announcements/:id` | – | any of: `message` (not empty), `type` (valid enum), `is_active:bool`, `duration` (hours; null/"" → no expiry) | `{ data: row }` | 400 "Message cannot be empty"; 400 "Invalid announcement type"; 400 "No fields to update"; 404 "Announcement not found"; 500 "Failed to update announcement" | PlatformSettings (only the `{is_active}` toggle)
34. `DELETE /announcements/:id` | – | – | `{ ok:true }` | 404 "Announcement not found"; 500 "Failed to delete announcement" | PlatformSettings

### G. Impersonation policy
35. `GET /impersonation-policy` | – | – | `{ requiresConsent:bool, breakGlassAllowed:bool, maxSessionMinutes:int, codeTtlMinutes:int }` | 500 "Failed to read policy" | RequestAccessModal, PlatformSettings
36. `PUT /impersonation-policy` | – | `requires_consent:bool opt`; `break_glass_allowed:bool opt`; `max_session_minutes:int opt`; `code_ttl_minutes:int opt` (wrong types are silently ignored) | same as #35 (re-read) | 400 "max_session_minutes must be between 5 and 240"; 400 "code_ttl_minutes must be between 1 and 60"; 500 "Failed to update policy" | PlatformSettings (camelCase in, snake_case out)

### H. Access requests (platform side; only the caller's own requests)
`AccessRequest` = `{ id, tenant_id, tenant_org_name, tenant_slug, requested_by, requested_by_name, requested_by_email, requested_at, reason, scope, duration_minutes, status (effective), raw_status, approved_by, approved_by_name, approved_at, denied_reason, code_expires_at, consumed_at, session_ends_at, revoked_at, revoked_by_name, revoked_reason, cancelled_at, created_at, updated_at }`. `approval_code_hash` is never returned.

37. `POST /:id/access-requests` | – | `reason:string req` (trimmed ≥10, ≤500); `scope opt` ("read", anything else → "write"); `duration_minutes:int opt` (default 30; 5..policy.maxSessionMinutes) | 201 `{ request: AccessRequest }` | 404 "Tenant not found or not active"; 400 "A reason of at least 10 characters is required."; 400 "Reason must be 500 characters or fewer."; 400 "duration_minutes must be between 5 and <max>"; 409 `{error:"You already have an open access request for this tenant. Cancel it before opening a new one.", existing_id}`; 500 "Failed to create access request". Notifies the tenant's super_admins (notification "Platform support access requested") + WS `platform_access_request_created`. | RequestAccessModal
38. `GET /access-requests` | `status` (DB status); `tenant_id`; `limit` (50, 1..200); `offset` | `{ requests:[AccessRequest] }`, newest first, only `requested_by = me` | 500 "Failed to list access requests" | Not called (`listMyAccessRequests` unused)
39. `GET /:id/access-requests` | – | – | `{ requests:[AccessRequest] }` (mine, this tenant, LIMIT 50) | 500 "Failed to list access requests" | RequestAccessModal (on mount + every 4s while waiting)
40. `DELETE /access-requests/:reqId` | – | – | `{ message:"Request cancelled" }` | 404 "Request not found"; 409 "Cannot cancel a request in status '<status>'." (only pending/approved can be cancelled); 500 "Failed to cancel request" | RequestAccessModal


### I. Audit, config, alerts
41. `GET /audit-logs` | `actor_id`, `entity_type`, `entity_id`, `action`, `tenant_id`, `from` (created_at ≥), `to` (created_at ≤), `limit` (default 50, clamped 1..500), `offset` (0) | `{ total:int, logs:[ platform_audit_logs.* (id, actor_id, action, entity_type, entity_id, tenant_id, details (JSON), ip_address, user_agent, created_at, ended_at) + actor_username, actor_name, tenant_name, tenant_slug ] }`, newest first | 500 "Failed to query audit logs" | PlatformAuditLogs
42. `GET /platform-config` | – | – | flat object with all 10 keys → string values (defaults filled in) | 500 "Failed to get platform config" | PlatformSettings
43. `PUT /platform-config` | – | any subset of the 10 keys; values become `String(val)`; unknown keys ignored; NO value validation | full config (same as #42) | 500 "Failed to update platform config". Clears the maintenance cache if `maintenance_mode` was sent. | PlatformSettings
44. `GET /alerts` | – | – | `{ alerts:[{ tenant_id, tenant_name, slug, alert_type, current_value:int, limit_value:int, percentage:int }] }`, highest percentage first (no_active_super_admin: current 0, limit 1, pct 100). A failed check is skipped, not fatal. | 500 "Failed to get alerts" | PlatformDashboard

---

## 3. Web pages

### 3.1 `index.tsx` — shell "Platform Console"
- Guard: `!user || user.role !== "platform_admin"` → h1 "Platform Console" + "Access denied. Platform Admin role required."
- Sidebar brand "Platform Console" / "Platform Admin". Groups are collapsible (saved in localStorage `platform_groups_collapsed`):
  - Overview: "Dashboard" · Tenants: "Tenants", "New Tenant" · Configuration: "Plans" · Access: "Platform Admins", "Platform Settings" · Compliance: "Audit Trail"
  - Switch: "Back to Admin" (title "Back to your organization's admin panel") → `window.location.href="/admin"`.
- Mobile: "Menu" (aria "Open platform menu"); close aria "Close menu".
- Header title = section label, or "Tenant Details" when a tenant is open. Descriptions:
  - tenants: "Manage every tenant on the platform. Suspend, restore, impersonate, or drill into a tenant's resources."
  - create: "Provision a new tenant. A dedicated database is created and the schema initialised."
  - admins: "Manage platform-level administrators (these accounts can act across all tenants)."
  - settings: "Global settings that apply across every tenant."
  - audit: "Platform-wide audit trail across every tenant."
- Picking a tenant (from the list, or after create) shows TenantDetail. Switching section clears it.


### 3.2 PlatformDashboard (tab `dashboard`)
- Calls GET /overview and GET /alerts together. Loading "Loading…"; error banner (default "Failed to load dashboard").
- Stat cards: "Total Tenants" (accent), "Total Users" (success), "Active" = by_status.active (success), "Suspended" (warning).
- "Plan Distribution" (only if by_plan has entries): count + plan key (capitalized).
- "Alerts (<n>)". Empty: "No alerts — all tenants within limits." Columns: Tenant (tenant_name bold + slug in mono) | Alert (badge) | Usage | Limit | %.
  - Labels: users_approaching_limit "Users at limit"; storage_approaching_limit "Storage at limit"; no_active_super_admin "No active admin"; otherwise the raw type. Badge is danger for no_active_super_admin, warning for the rest.
  - For no_active_super_admin, Usage/Limit show "—" and % is hidden. Storage values get a " MB" suffix. % is danger if ≥95, otherwise warning.
- "New Tenants (Last 30 Days)": bar chart (tooltip "<day>: <count> new"), footer "Total: <sum> new tenants".
- "Recently Created": Organization | Slug | Status (green badge if active, otherwise warning) | Created (local date).
- "Connection Pool": "Active Pools" (`pool_stats.active||0`), "Max Pools" (`pool_stats.max||10`), "Evictions" (`pool_stats.evictions||0`).

### 3.3 TenantList (tab `tenants`)
- Every load calls GET / (search debounced 300 ms, status) and GET /overview. Loading "Loading tenants…". Error banner can be dismissed (X). Fallback messages: "Failed to load tenants" / "Failed to suspend" / "Failed to reactivate" / "Failed to delete".
- Stats: "Tenants", "Total Users", "Active", "Suspended".
- Toolbar: search placeholder "Search tenants…"; status select "All Status" (""), "Active", "Suspended", "Deleted".
- Empty: "No tenants found".
- Cards (tap → detail): org_name, slug, status badge, "<user_count> users", created date.
- **Status badge** (background = `color-mix(<c> 14%, transparent)`, text = c): active → `var(--success)`; suspended → `var(--warning)`; deleted → `var(--danger)`; unknown (e.g. `migrating`) → uses the active colors. The label is the raw status text.
- Card buttons:
  - active: "Request Access" (title "Request consent-gated access to this tenant") → RequestAccessModal; "Suspend" (warning color).
  - suspended: "Reactivate" (success color) → calls PUT reactivate right away (no confirm).
  - `!is_default`: "Delete" (danger color), shown for every status including deleted.
- **Suspend ConfirmDialog**: title "Suspend Tenant"; "Provide a reason for suspending this tenant:" input placeholder "Suspension reason…"; "Re-enter your password to confirm:" password field placeholder "Your password" (autocomplete current-password); buttons "Suspend" / "Cancel". Confirm only fires when reason.trim() AND password are both filled, so a reason is REQUIRED on this page. Enter submits. The dialog closes before the request; errors go to the page banner.
- **Delete ConfirmDialog** (isDanger): title "Delete Tenant"; `Are you sure you want to delete "<name>"? This marks the tenant as deleted and is recorded in the audit log.`; "Re-enter your password to confirm:" placeholder "Your password"; buttons "Delete" / "Cancel". Password required. Always soft delete (`hard=false`).
- No typed-name confirmation anywhere in the console. The only confirmation is re-entering your password.

### 3.4 TenantDetail (drill-down)
- Calls GET /:id and GET /:id/stats (a stats error just leaves stats null). Loading "Loading tenant…"; missing → "Tenant not found". Back button "Back to Tenants".
- Header: h1 org_name, slug in mono, status badge (same colors as the list).
- Buttons: active → "Request Access" (primary) + "Suspend"; suspended → "Reactivate" (no confirm); `!is_default && status!=="deleted"` → "Delete".
- Tabs: "Overview", "Settings". There are deliberately no Users/Departments/Teams/Org Chart tabs.
- Overview cards: "Custom Domain" (value or "None"), "Database" (db_name or "—"), "Max Users" (value or "∞"), "Max Storage" ("<n> MB" or "∞"), "User Count" (tenant.user_count or 0), "DB Size" (`(db_size_bytes/1MiB).toFixed(1) MB`, when stats loaded), "Upload Storage" (only if storage_bytes != null: formatted bytes + ` (<pct>% of <quota> MB)` when there's a quota; red at ≥90%, amber at ≥75%; plus ` · <n> file(s)`), "Tasks"/"Messages" (only when `!activity_restricted`).
- Restricted notice (when activity_restricted): title "Tenant-private data is not shown here"; body "User accounts, departments, teams, org chart and activity metrics belong to <org_name>. Viewing them requires a time-boxed access session approved by one of their administrators. Every action during a session is recorded in the audit trail and visible to the tenant." + a "Request Access" button if the tenant is active.
- **Confirm modal** (custom modal, not ConfirmDialog):
  - Title "Suspend tenant" / "Delete tenant".
  - Suspend text: `This will suspend "<org_name>" and block all its users from signing in. Re-enter your password to confirm.` Delete text: `This will mark as deleted "<org_name>". This action is recorded in the audit log. Re-enter your password to confirm.` (A "PERMANENTLY delete" hard variant is in the code, but the UI never sets hard=true.)
  - Suspend only: label "Reason (optional)", placeholder "Reason for suspension", pre-filled "Suspended by platform admin" (an empty value also sends that default).
  - Label "Your password", placeholder "Enter your password", required, autofocus. Client error "Password is required."
  - Buttons "Cancel" and submit "Suspend"/"Delete" ("Working…" while busy; Delete text is red). Server errors show inside the modal (fallback "Action failed"). After a successful delete it calls onBack().

- **Settings tab** (`TenantSettings`). All results show in one message line; generic fallback "Failed".
  - "Tenant Details": input placeholder "Organization name" + "Save" (disabled when empty or unchanged). Client message "Organization name cannot be empty"; success "Name updated". Read-only note `Slug: <slug> (cannot be changed after creation)`.
  - "Subscription Plan": badge with the plan label + select with options `"<label> — <description>"`. Changing it immediately calls PUT /:id/plan `{plan, apply_plan_limits:true}` (no confirm, no dry run). Success "Plan updated"; fallback "Failed to change plan".
  - "Feature Overrides" (after the catalog loads): hint `Override individual features from the plan defaults. "Default" uses the plan setting.` Each row: label, chip "Plan: ON"/"Plan: OFF", 3-way toggle "Default" | "On" | "Off". "Save Features" → PUT /:id/features `{features: overrides}` (the whole local map). Success "Features updated". Choosing "Default" only deletes the key locally; because the server merges, an existing override is NOT removed on the server.
  - "Custom Domain": placeholder "e.g. app.company.com". Client regex `^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}$` (case-insensitive). Inline error "Enter a valid domain (e.g. app.company.com)" (on save: "Enter a valid domain"); "Save" is disabled while there's an error. Success "Domain updated". Empty clears the domain.
  - "Limits": "Max Users" (number, placeholder "∞"), "Max Storage (MB)" (placeholder "∞"), "Save Limits" → PUT /:id/limits (an empty field sends null, and the server then keeps the old value). Success "Limits updated".

### 3.5 RequestAccessModal (consent-gated impersonation)
- Header "Request access — <org_name>" + close X. Step indicator "Reason", "Wait", "Code", "Enter" (break-glass counts as the Code step).
- On mount: GET /impersonation-policy, then GET /:id/access-requests. The first request that is pending → "waiting" step; approved → "code" step.
- **reason**: "The tenant's super admin must approve your request before you can enter the workspace. They'll receive a notification with the reason below."
  - "Reason for access *" textarea (4 rows, maxLength 500), placeholder "e.g. Customer ticket #4231 — investigating missing salary slip records."
  - "Scope": "Read-only (recommended)" (read) | "Full write access" (write). **Default is "write".**
  - "Duration (minutes)": number, min 5, max `policy.maxSessionMinutes||60`, default 30; hint "Max <n> min".
  - Buttons "Cancel", "Request access". Client check "Please describe why access is needed (at least 10 characters)." Fallback "Failed to create request".
  - If breakGlassAllowed: "Break-glass emergency access is enabled by platform policy." + link "Use it instead".
- **waiting**: spinner, "Waiting for approval", "Your request was sent to the tenant's super admin. They'll generate a one-time 6-digit code and share it with you over your support channel." Shows "Reason:", "Scope:", "Duration: <n> minutes". Button "Cancel request" (DELETE, then close). Polls every 4000 ms: approved → code; denied → error `Request denied: <denied_reason||"—">` and back to reason; expired → "The approval code expired. Please request again."; cancelled → back to reason.

- **code**: banner "Approved by <approved_by_name||"the tenant">."; "Enter the 6-digit approval code the tenant shared with you, plus your platform password. Both will be verified before the session starts."
  - "6-digit approval code" (digits only, max 6, placeholder "123456", autocomplete one-time-code); shows "Code expires <local time>" when code_expires_at is set.
  - "Your platform password (re-auth)", placeholder "Enter your password".
  - Buttons "Cancel" (cancels the request), "Enter tenant". Client errors "Enter your password to continue." / "Enter the 6-digit approval code."; fallback "Failed to start session".
- **break_glass**: danger banner "Emergency break-glass access" / "Bypassing tenant consent is heavily audited. All tenant super admins will be notified immediately. Only use this for genuine incidents."; field "Your platform password (re-auth required)"; buttons "Back", "Break the glass" (disabled until a password is entered).
- **done**: "Session started" / "Loading the tenant workspace…".
- **Impersonate hand-off, exact web code:**
  ```ts
  await impersonateTenant(tenant.id as any, {
      approval_code: useBreakGlass ? undefined : approvalCode,
      password,
      break_glass: useBreakGlass || undefined,
  } as any);
  setStep("done");
  // Hand off to the parent — they typically window.location.href = '/'
  setTimeout(() => { window.location.href = "/"; }, 400);
  ```
  The web ignores the response body (`token`, `user`, `session`) and relies on the Set-Cookie that overwrites `token`. Server comment: "Bearer-token clients (mobile) can't read the HttpOnly cookie — return the impersonation JWT in the body too. They store their original platform token locally and swap back on exit." Another server comment says that once the console has its own host, the client must redirect to the app host to install the token. The web doesn't do that yet.

### 3.6 Exit impersonation (ImpersonationBanner, shown app-wide when `user.impersonated`)
- Banner: "MONITORED", `<impersonated_by_name||"Platform Admin"> inspecting <impersonated_tenant_name||tenant_id>`, elapsed timer, reads (title "Pages inspected"), writes (title "Actions taken"). Polls GET /:tenant_id/impersonation-session every 15000 ms. Exit first re-fetches the session and shows a summary, then confirms.
- **Exit, exact web code:**
  ```ts
  await exitImpersonation(user.tenant_id);
  // Server restores the original platform admin cookie (HttpOnly)
  window.location.href = "/tenants";
  ```
  On error it just calls `setExiting(false)`, with no message.
- Suggested Android flow: keep the platform token saved locally. On impersonate, use `token` from the response body as the active bearer. On exit, restore the saved platform token no matter what the exit call returns (§4.1).


### 3.7 CreateTenant (tab `create`) — 5-step wizard
- Title "Create New Tenant"; subtitle "Set up a new organization with admin access and optional seed data". Steps: "Basics", "Plan", "Limits", "Super Admin", "Seed Data". Error banner dismiss "×".
- **1 Basics**: "Organization Name *", placeholder "Acme Inc." (auto-fills the slug: lowercase, non-alphanumeric → "-", dashes trimmed, max 50). "Slug *" (mono). Client regex `^[a-z0-9](?:[a-z0-9-]{0,48}[a-z0-9])?$`, error "Lowercase alphanumeric with hyphens, 2–50 chars" (the regex actually allows 1 char; the server needs 3). "Next: Plan" only moves on with a name, a slug, and no slug error.
- **2 Plan**: one card per catalog plan: label, check mark if selected, description, "<max_users|∞> users", "<n> MB" or "∞ storage", list of enabled feature labels. Default selection "standard" (limits aren't pre-filled until a card is tapped). Buttons "Back", "Next: Limits".
- **3 Limits**: "Pre-filled from the <label> plan. Override if needed." Fields "Max Users (leave empty for unlimited)" and "Max Storage in MB (leave empty for unlimited)", both placeholder "∞". Buttons "Back", "Create & Continue" ("Creating…") → POST / `{org_name, slug, plan, max_users|null, max_storage_mb|null}`. Client errors "Organization name and slug are required" / "Invalid slug format"; fallback "Failed to create tenant".
- **4 Super Admin**: "Create the initial super admin for <orgName>. This user will be the primary administrator." Fields "Full Name *", "Username *", "Email *" (email), "Temporary Password *" (password). Buttons "Skip", "Create Admin & Continue" ("Creating…") → POST /:id/users `{username, email, full_name, password, role:"super_admin"}`. Client "All fields are required"; fallback "Failed to create admin user".
- **5 Seed**: "Optionally seed <orgName> with default departments (Engineering, Product, Design, Marketing, Sales, HR, Finance) and leave policies (Annual, Sick, Personal)." Buttons "Skip & Finish", "Seed Default Data" ("Seeding…") → POST /:id/seed. When done: "Seed data applied — <d> departments, <l> leave policies created." + "View Tenant" → opens TenantDetail. Fallback "Failed to seed data".

### 3.8 PlanManagement (tab `plans`)
- Loads GET /plan-catalog. Loading "Loading…"; fallback "Failed to load plan catalog".
- Toolbar: "Save All Plans" (PUT the whole catalog; success "Plan catalog saved"; fallback "Failed to save"); "Reset to Defaults" (opens confirm); input placeholder "new_plan_key" + "Add Plan" (Enter works too).
  - Add: the key is lowercased and `[^a-z0-9_]` → "_". Errors "Plan key is required" / `Plan "<key>" already exists`. A new plan gets the key capitalized as label, empty description, all features off, limits {25, 5120}, and opens expanded.
- Each plan is a collapsible section headed by chevron + label (bold) + "(<key>)" in mono.
  - Expanded: "Label", "Description", "Max Users (empty = unlimited)" (number, min 1, placeholder "unlimited"), "Max Storage (MB, empty = unlimited)" (min 100, placeholder "unlimited"), "Features" checkbox grid.
  - Base plans: "Base plan — cannot be deleted." Others: "Delete Plan" (local only, no confirm). Trying to delete a base plan shows `The "<key>" plan is a required base plan and cannot be deleted.`
- Empty: "No plans defined. Add a plan or reset to defaults."
- Reset ConfirmDialog (isDanger): "Reset Plan Catalog" / "This will replace all custom plans with the original defaults (Standard, Pro, Enterprise). Existing tenants will keep their current plan assignment." / "Reset" / "Cancel". Success "Plan catalog reset to defaults"; fallback "Failed to reset".

### 3.9 PlatformAdmins (tab `admins`)
- Loads GET /platform-users (expects an array). Loading "Loading…"; fallback "Failed to load platform admins".
- Toolbar: "Platform Administrators (<n>)" + "New Platform Admin" (shows/hides the form).
- Create form: "Create Platform Admin" / "This user will have full platform access across all tenants"; fields "Full Name *", "Username *", "Email *" (email), "Password *" (password); buttons "Cancel", "Create Admin" ("Creating…"). Client "All fields are required"; success "Platform admin created successfully"; fallback "Failed to create platform admin".
- Cards: avatar initial (or "?"), full_name + "(you)" for yourself, "<username> · <email>", badge "active"/"inactive" + " · Joined <date>".
- Card buttons: link icon (title "Manage linked tenant principals") only when `user.platform_role === "platform_owner"`. Key (title "Reset Password") and toggle (title "Deactivate"/"Reactivate"; red/green) are hidden on your own card. The toggle has NO confirm; success shows the server's `message`.
- Reset ConfirmDialog: title "Reset Password — <name>"; label "New Password (min 8 characters)"; buttons "Reset Password" / "Cancel". Client "Password must be at least 8 characters"; success = server message.
- Links modal: "Linked principals — <name>"; hint "Enter an existing tenant ID and tenant user ID. This creates no user and grants no tenant permission; it only enables explicit realm switching." Each row: "<org_name> · tenant #<id> · user #<uid> · default <realm>" + unlink icon (no confirm). Inputs placeholder "Tenant ID" and "Tenant user ID"; select "Tenant default" (tenant) | "Platform default" (platform); "Link" (disabled until both IDs are filled). Fallbacks "Failed to load links" / "Failed to create link".


### 3.10 PlatformSettings (tab `settings`)
- Loads GET /announcements, GET /impersonation-policy and GET /platform-config together (each error is swallowed). Loading "Loading…". Success and error banners.
- Each config section's Save button is disabled until one of its keys changes, and sends only those keys. Success "Settings saved"; fallback "Failed to save settings".
- **Maintenance Mode**: "When enabled, all non-platform-admin users receive a 503 maintenance page. Use during deployments, migrations, or emergency fixes." Checkbox "Enable maintenance mode", hint "All API requests (except login and health) will return 503 for non-platform-admin users." "Maintenance message" textarea, placeholder "The system is currently under maintenance. Please try again later." Button "Save". Keys: maintenance_mode ("true"/"false"), maintenance_message.
- **Impersonation Policy**: "Controls how platform admins access tenant workspaces. Tightening these settings improves SOC2 / ISO 27001 support-access posture."
  - "Require tenant consent", hint "When on, platform admins must submit an access request that a tenant super-admin approves before they can enter the workspace. Strongly recommended."
  - "Allow break-glass access" (disabled while consent is off), hint "Lets platform admins bypass tenant consent for genuine emergencies. Every bypass is heavily audited and notifies the tenant after the fact. Keep off unless you have a documented incident-response policy."
  - "Max session length (minutes)" (5–240, hint "5–240 min"); "Approval code TTL (minutes)" (1–60, hint "How long an approved code stays valid before expiry."). "Save policy" → PUT with snake_case fields. Success "Impersonation policy updated"; fallback "Failed to update policy".
- **Security**: "Platform-wide password policies. Console administrators remain signed in until explicit logout, account deactivation, or password change." "Password min length" (6–32). Checkboxes "Require uppercase", "Require number", "Require special character". "Allowed email domains (comma-separated, leave empty for any)", placeholder "e.g. company.com, subsidiary.com", hint "When set, only users with emails matching these domains can register." Button "Save security settings".
- **Data Retention**: "Control how long various logs and deleted data are retained before cleanup." "Audit log retention (days)" (30–3650, "Platform and tenant audit logs."); "Deleted tenant cleanup (days)" (7–365, "Days before soft-deleted tenants are permanently removed."); "Session log retention (days)" (7–365, "Impersonation session history."). Button "Save retention policy". These ranges exist only in the HTML inputs; the server doesn't check them.
- **Global Announcements**: "Announcements visible to all tenants across the platform." Input placeholder "Announcement message…"; type select Info/Success/Warning/Urgent (default info); duration select "No expiry" (""), "1 hour" (1), "6 hours" (6), "1 day" (24), "1 week" (168); "Post" (does nothing if the message is blank) → POST `{message, type, duration: newDuration||null}`. Success "Announcement created".
  - Empty: "No announcements". Columns: Message | Type (badge) | Active ("yes"/"no") | Created | Actions. Toggle (title "Disable"/"Enable") → PUT `{is_active:!is_active}`; trash icon → confirm. `expires_at` is not shown.
  - Delete ConfirmDialog (isDanger): "Delete Announcement" / "Are you sure you want to delete this announcement?" / "Delete" / "Cancel".

### 3.11 PlatformAuditLogs (tab `audit`)
- Also loads GET / `{limit:200}` (for the tenant filter) and GET /platform-users (stored but not shown). Page size 50: GET /audit-logs `{limit, offset, action?, tenant_id?, entity_type?}`. Errors only go to console.error.
- Header "Platform Audit Trail" / "Every platform admin action is logged for compliance and accountability"; count "<total> event(s)".
- Filters: entity "All Entities" + tenant / "platform user" / user; action "All Actions" + the ACTION_COLORS keys with "_" shown as spaces; tenant "All Tenants" + "<org_name> (<slug>)". Changing a filter goes back to page 0.
- Columns: Time | User | Action | Entity | Tenant | IP.
  - Time: session rows (`tenant_impersonation_session`) show the start plus "→ <ended_at>", or "● Active" if not ended.
  - User: actor_name || actor_username || `Admin #<actor_id>`.
  - Action badge colors: created/reactivated/platform_admin_reactivated #10b981; updated #3b82f6; suspended and platform_admin_reset_password #f59e0b; soft/hard_deleted, tenant_user_deactivated, platform_admin_deactivated #ef4444; domain_changed #8b5cf6; features/limits_updated #6366f1; tenant_impersonation_* #f97316; tenant_user_created #0ea5e9; tenant_seeded #14b8a6; platform_admin_created #0284c7; anything else #6b7280. Background = color + "18" (alpha).
  - Row highlight: high = impersonation_session/started, hard_deleted, suspended, platform_admin_deactivated; medium = platform_admin_reset_password, soft_deleted, tenant_user_deactivated.
  - Entity "<entity_type> #<entity_id>"; Tenant = tenant_name or "—"; IP or "—".
- Tapping a row expands it. Session rows show duration ("<s>s" or "<m>m <s>s"), "<n> reads", "<n> writes", "<n> total actions", "as <target_username>", and the action log (method, path, time). Other rows show "Details:" + pretty-printed JSON. Both show "User-Agent:" when present.
- Empty: "No audit logs found". Paging (when more than one page): "Previous", "Page X of Y", "Next".
- The action filter lists names the server never emits (`tenant_impersonation_started/ended`, `tenant_user_deactivated`) and leaves out ones it does emit (`tenant_plan_changed`, `platform_tenant_user_deactivated`, access-request and catalog actions).


---

## 4. Mismatches and gotchas for Android

1. **Exit / session-poll are probably broken for impersonation tokens.** `exit-impersonate` and `impersonation-session` sit behind `requirePlatformIdentity`. The impersonation JWT has `tenant_id` and no `platform` claim, so the code as written returns 403 PLATFORM_IDENTITY_REQUIRED. With CONSOLE_HOST set, the realm is also "tenant" instead of "platform". The web banner hides this failure. On Android: always restore the saved platform token locally. You can also call exit with the ORIGINAL platform token to close the audit row: then actor = req.userId, which matches the session key `(platformUserId, tenantId)`.
2. Exit writes `_wp_orig_token` back into the `token` cookie (the tenant cookie name), even if the original session was in the `aino_console` cookie.
3. `PUT /:id/limits` treats null as "keep", so you can't go back to unlimited. `PUT /:id` also ignores values ≤0 or null.
4. `PUT /:id/features` merges. Sending null for a key is coerced to null and dropped, so the old override stays. No endpoint can delete an override; only an explicit true/false replaces it.
5. The web changes plans immediately with `apply_plan_limits:true`. `?dry_run=true` (the preview) exists but the web never uses it; Android can use it for a downgrade confirm dialog.
6. Hard delete (`?hard=true`) works on the server but has no web UI.
7. `GET /platform-users` returns a bare array, not a wrapped object.
8. The dashboard reads `pool_stats.active/max/evictions`, but the server sends `poolCount/maxPools/metrics.evictions`, so the web always shows the fallbacks 0/10/0.
9. `user_count` differs by endpoint: in the list it's a string (PG count); in detail it's an int from user_directory; in stats it's active users counted in the tenant DB.
10. Slug length: the server needs 3–50; the web regex allows 1–50.
11. Domain regex differs: the web requires an alphabetic TLD of 2+ chars; the server doesn't.
12. Suspend reason: TenantList requires it on the client; TenantDetail makes it optional with the default "Suspended by platform admin"; the server makes it optional.
13. Announcement type `quote` is valid on the server but not offered by the web. `duration` is in hours.
14. The link endpoints (#28–30) have no try/catch, so an unexpected error may return Express's default HTML 500.
15. The plan catalog can store custom keys, but tenants can only be on standard/pro/enterprise.
16. Access-request lists only return requests created by the calling admin.
17. `GET /:id/users` and user deactivate need an approved consent context, so they're effectively unreachable from the tenantless console. `POST /:id/users` only works for the first admin (a tenant with no non-platform user yet).
18. Web wrappers with no caller in the console: `getTenantUsers`, `deactivateTenantUser`, `listMyAccessRequests`. `exitImpersonation` and `getImpersonationSession` are used only by the app-wide ImpersonationBanner.
