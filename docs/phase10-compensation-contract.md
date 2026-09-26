# Phase 10 — Compensation / Payroll contract (web → Android)

Source of truth: `aino-platform`
- Server: `server/routes/compensation.ts` (1066 lines, 34 routes)
- Helpers: `server/utils/encryption.ts` (encrypt/decrypt/maskAccountNumber), `server/utils/salarySlipPdf.ts` (pdfkit),
  `server/services/razorpayPayout.ts` (Razorpay X client), `server/utils/attendance.ts` (calculateAttendance)
- Schema: `server/platform/db/tenantSchema/operations.ts` L101-267
- Client wrappers: `client/src/api/compensation.ts`
- Pages: `pages/admin/CompensationSetup.tsx`, `pages/admin/SalarySlips.tsx`, `pages/admin/PaymentSettings.tsx`,
  `pages/attendance/MySalarySlips.tsx` (embedded in `pages/Organization.tsx` tab "Salary Slips"), `pages/admin/index.tsx`

---
## 0. Mounting, middleware, roles

- `server/http/routes.ts:98` → `app.use("/api/compensation", apiLimiter, compensationRoutes)`
- Router-level (ALL 34 routes): `auth, loadUserContext, requireTenant, requireFeature("payroll")`
  - feature off → 403 `{ error: "The Payroll & Compensation feature is not enabled for your subscription plan.", feature: "payroll", plan }`
  - planCatalog: payroll=false on base plan, true on higher plans (L51/62/74)
- `requireRole("hr_admin")` → `req.roleLevel >= 4` (employee 1, team_lead 2, manager 3, hr_admin 4, super_admin 5, platform_admin 6).
  Fail → 403 `{ error: "Insufficient permissions" }`. Tenant custom roles pinned to level 4 pass.
- File-local `requireSameOrg` (NOT rbac's): `!req.userOrgId && role !== "platform_admin"` → 403 `{ error: "Organization required" }`.
- In-handler "isAdmin" = `["hr_admin","super_admin","platform_admin"].includes(req.userRole)` — role KEY match, not level
  (custom level-4 role keys are treated as non-admin inside those handlers).
- Self-service (no role guard, no requireSameOrg): `GET /my-slips`, `GET /my-slips/:id/pdf`, `GET /my-bank-details`, `POST /my-bank-details`.
- Any role + requireSameOrg (admin/owner branch inside): `GET /templates`, `GET /salary-slips`, `GET /salary-slips/:id`,
  `GET /salary-slips/:id/pdf`, `GET /bank-details/:userId`.
- All other 25 routes: `requireRole("hr_admin") + requireSameOrg`.
- Audit (`audit_logs`, fire-and-forget): template create/delete, employee_compensation create, payroll_run, disburse (bulk),
  org_payment_config update, employee_bank_details (admin) update.
- Generic error shape: `{ error: "<message>" }`.

## 0.1 Type notes (pg → JSON)
- No `pg.types.setTypeParser` anywhere in server → NUMERIC come back as **strings**.
  - NUMERIC(12,2) strings e.g. `"50000.00"`: `ctc_annual, base_salary, gross_earnings, total_deductions, net_pay, amount`
  - NUMERIC(5,2)/(6,2) strings: `days_worked, days_absent, leave_days, overtime_hours`
  - CTC config: NUMERIC strings (`"40.00"`, `"1800.00"`) when row exists; **plain numbers** when defaults returned (no row).
  - `result.rowCount` → number.
- SERIAL/INTEGER ids → numbers. BOOLEAN → bool. JSONB → parsed.
- TIMESTAMPTZ → ISO-8601 string (`created_at, updated_at, published_at, verified_at, initiated_at, processed_at`).
- TEXT dates: `effective_from`/`effective_to` "YYYY-MM-DD" (as sent); `slip_month` "YYYY-MM" (= `pay_period.start_date.slice(0,7)`);
  pay_period `start_date/end_date` TEXT "YYYY-MM-DD".

## 0.2 Tables (columns returned by `SELECT *`)
- compensation_templates: id, org_id, name, description|null, components JSONB array (default []), is_default, created_by,
  created_at, updated_at. UNIQUE(org_id,name).
- employee_compensation: id, user_id, org_id, template_id|null, effective_from TEXT, effective_to TEXT|null, ctc_annual NUM,
  base_salary NUM, components JSONB object key→amount (default {}), currency ("INR"), payment_frequency
  ('monthly'|'biweekly'|'weekly'), bank_account|null, notes|null, created_by, created_at, updated_at.
  UNIQUE(user_id,effective_from). Active = `effective_to IS NULL`.
- salary_slips: id, org_id, user_id, pay_period_id, compensation_id, slip_month, earnings JSONB {key:number}, deductions JSONB
  {key:number}, gross_earnings, total_deductions, net_pay, days_worked, days_absent, leave_days, overtime_hours,
  status ('draft'|'generated'|'published'|'revised'; code uses only draft/published), generated_by, published_at,
  created_at, updated_at. UNIQUE(org_id,user_id,pay_period_id).
- payroll_disbursements: id, org_id, salary_slip_id UNIQUE, user_id, amount NUM, currency, razorpay_payout_id,
  razorpay_fund_account_id, transfer_mode ('NEFT'), status ('queued'|'processing'|'processed'|'reversed'|'failed'),
  failure_reason, utr, initiated_by, initiated_at, processed_at, created_at.
- org_payment_config: id, org_id UNIQUE, provider ('razorpay'), api_key_id (encrypted), api_key_secret (encrypted),
  account_number (PLAINTEXT), webhook_secret (encrypted), default_transfer_mode ('NEFT'), is_active (default FALSE), created_at, updated_at.
- employee_bank_details: id, user_id, org_id, account_holder_name, account_number (encrypted), ifsc_code, bank_name|null,
  account_type ('savings'), razorpay_contact_id, razorpay_fund_account_id, is_verified, verified_at, created_at, updated_at.
  UNIQUE(user_id,org_id).
- org_ctc_config: org_id PK, basic_pct NUM(5,2)=40, hra_pct=50, conveyance_pct=5, pf_pct=12, pf_max NUM(10,2)=1800, pt_fixed=200,
  updated_by, updated_at.
- pay_periods (context): id, org_id, label, start_date, end_date, locked_by|null, locked_at, created_at.
- `maskAccountNumber(s)`: `!s || s.length<4 ? "****" : "****" + s.slice(-4)`.

---
## 1. Endpoints (34) — `METHOD path | guard | query | body | response | errors | web caller`
(all paths prefixed `/api/compensation`)

### Templates
1. `GET /templates` | any role + sameOrg | – | – |
   200 `Template[]` ORDER BY is_default DESC, name — id:num, org_id:num, name:str, description:str|null,
   components:`{key,label,type,calc_type?,taxable?}[]`, is_default:bool, created_by:num|null, created_at, updated_at |
   500 "Failed to fetch templates" | CompensationSetup (load; Assign modal "Template" select)
2. `POST /templates` | hr_admin | – | name:str req (trimmed), description:str opt, components:array req non-empty, is_default:bool opt |
   201 Template row | 400 "Name is required"; 400 "At least one component is required"; 409 "Template name already exists";
   500 "Failed to create template". is_default → clears other defaults first | CompensationSetup (Create)
3. `PUT /templates/:id` | hr_admin | – | name?, description?, components?, is_default? | 200 updated row |
   404 "Template not found"; 409 "Template name already exists"; 500 "Failed to update template".
   `is_default` written as `!!is_default` (omitted → false); description `?? old` | CompensationSetup (Update)
4. `DELETE /templates/:id` | hr_admin | – | – | 200 `{ message: "Template deleted" }` |
   400 "Template is in use by employees. Reassign them first." (counts ALL employee_compensation rows incl. history, no org filter);
   500 "Failed to delete template". Nonexistent id → still 200 | CompensationSetup (trash icon)

### Employee compensation
5. `GET /employees` | hr_admin | – | – | 200 active records (effective_to NULL) ORDER BY full_name: `ec.*` + full_name:str, email:str,
   role:str, department_name:str|null, team_name:str|null | 500 "Failed to fetch employee compensations" | CompensationSetup (Employees tab)
6. `GET /employees/:userId` | hr_admin | – | – | 200 history `ec.* + template_name:str|null` ORDER BY effective_from DESC |
   500 "Failed to fetch compensation history" | **no caller** (getEmployeeCompensation unused)
7. `POST /employees/:userId` | hr_admin | – | effective_from:"YYYY-MM-DD" req; base_salary:num|str req (truthy, 0 rejected);
   ctc_annual opt (→0); template_id opt|null; components:object opt (→{}); currency opt ("INR"); payment_frequency opt ("monthly");
   bank_account opt; notes opt | 201 new ec row |
   400 "effective_from and base_salary are required"; 404 "Employee not found in organization";
   409 "Compensation for this effective date already exists"; 500 "Failed to assign compensation".
   Side effect (no transaction): closes active record with `effective_to = effective_from`, then INSERT |
   CompensationSetup (Assign modal, both new + edit)
8. `PUT /employees/:userId/:id` | hr_admin | – | base_salary?, ctc_annual?, components?, currency?, payment_frequency?, bank_account?,
   notes? (COALESCE; null keeps) | 200 updated row | 404 "Compensation record not found"; 500 "Failed to update compensation" |
   **no caller** (updateCompensation unused)

### Payroll run
9. `POST /payroll-run` | hr_admin | – | pay_period_id:int req | 200 `{ message: "Generated N salary slips", count: N }` |
   400 "pay_period_id is required"; 404 "Pay period not found"; 400 "Pay period must be locked before generating salary slips";
   400 "No employees with active compensation found"; 500 "Payroll run failed: <err.message>" | SalarySlips ("Generate Slips").
   Inserts 'draft'; ON CONFLICT (org,user,period) updates amounts but NOT status (published stays published w/ new numbers). §3.1.

### Salary slips
10. `GET /salary-slips` | any role + sameOrg | admin only: pay_period_id?, status?, user_id? | 200 array `ss.*` + full_name, email,
    department_name|null, razorpay_payout_id|null, disbursement_status|null, utr|null; ORDER BY slip_month DESC, full_name.
    Non-admin: forced own + published; filters ignored | 500 "Failed to fetch salary slips" | SalarySlips (`{pay_period_id}`)
11. `GET /salary-slips/:id` | any role + sameOrg | – | – | 200 `ss.*` + full_name, email, role, department_name, team_name |
    404 "Salary slip not found"; 403 "Access denied" (non-admin & (not owner or not published)); 500 "Failed to fetch salary slip" |
    **no caller** (getSalarySlip unused)
12. `PUT /salary-slips/:id/publish` | hr_admin | – | – | 200 updated slip row (status 'published', published_at) |
    404 "Salary slip not found or already published"; 500 "Failed to publish salary slip" | SalarySlips (row ✓)
13. `POST /salary-slips/bulk-publish` | hr_admin | – | pay_period_id req | 200 `{ message: "Published N salary slips", count: N }` |
    400 "pay_period_id is required"; 500 "Failed to publish salary slips" | SalarySlips ("Publish All Drafts")
14. `GET /salary-slips/:id/pdf` | any role + sameOrg (admin any; else own+published) | – | – |
    200 `Content-Type: application/pdf`; `Content-Disposition: attachment; filename="salary_slip_<Full_Name>_<YYYY-MM>.pdf"`
    (whitespace→`_`, then `[^a-zA-Z0-9._-]`→`_`). Has YTD block (FY start = `organizations.fiscal_year_start` default "04";
    sums published slips FY-start..slip month) + masked bank acct |
    404 "Salary slip not found"; 403 "Access denied"; 500 "Failed to generate PDF" (JSON) | SalarySlips (Download, blob)
15. `GET /my-slips` | self | – | – | 200 own published, ORDER BY slip_month DESC: `ss.*` + disbursement_status|null, utr|null,
    paid_at (=pd.processed_at)|null | 500 "Failed to fetch salary slips" | MySalarySlips
16. `GET /my-slips/:id/pdf` | self | – | – | 200 PDF, filename `salary_slip_<YYYY-MM>.pdf`, **no YTD** |
    404 "Salary slip not found"; 500 "Failed to generate PDF" | MySalarySlips ("PDF")

### Disbursement (Razorpay X)
17. `POST /disburse` | hr_admin | – | pay_period_id req | 200 `{ message: "Disbursement initiated", disbursed:num, failed:num, total:num }` |
    400 "pay_period_id is required"; 400 one of:
    - "All slips already disbursed (X) or missing bank details (Y)."
    - "All X slip(s) have already been disbursed."
    - "Y slip(s) missing bank details. No slips are ready for disbursement."
    - "No published slips found. Publish draft slips before disbursing."
    - "No eligible slips for disbursement."
    500 "Disbursement failed: <msg>" (e.g. "Payment configuration not found or disabled", "Incomplete payment configuration").
    Eligible = published, no disbursement row, bank row w/ razorpay_fund_account_id. Per slip: createPayout NEFT, purpose "salary",
    referenceId `WP-<YYYY-MM>-<userId padStart(4,"0")>`, narration `Salary <YYYY-MM>`, amount paise = round(net_pay*100),
    queue_if_low_balance true → row 'processing', or 'failed'+failure_reason | SalarySlips ("Disburse All")
18. `POST /disburse/:slipId` | hr_admin | – | – | 200 `{ message: "Disbursement initiated", payout_id }` |
    404 "Slip not found or not published"; 400 "Employee bank details not registered with Razorpay";
    400 "Already disbursed or in progress"; 500 "Disbursement failed: <msg>" | **no caller** (disburseSingle unused)
19. `GET /disbursements` | hr_admin | pay_period_id?, status? | 200 `pd.*` + full_name, slip_month; ORDER BY created_at DESC |
    500 "Failed to fetch disbursements" | SalarySlips (matched to slip via salary_slip_id)
20. `POST /disburse/retry/:id` | hr_admin | – | – (id = disbursement id, must be 'failed') |
    200 `{ message: "Retry initiated", payout_id }` | 404 "Failed disbursement not found"; 500 "Retry failed: <msg>". referenceId suffix `-R` |
    SalarySlips (row ↻)

### Payment config (Razorpay X)
21. `GET /payment-config` | hr_admin | 200 row with `api_key_id` "****"+last4, secrets "********", or `null` | 500 "Failed to fetch payment config" | PaymentSettings
22. `PUT /payment-config` | hr_admin | api_key_id, api_key_secret, account_number req; webhook_secret opt (blank keeps old); default_transfer_mode ("NEFT"); is_active (`!== false`) |
    200 `{ message: "Payment configuration saved" }` | 400 "api_key_id, api_key_secret, and account_number are required"; 500 "Failed to save payment config" | PaymentSettings
23. `POST /payment-config/test` | hr_admin | 200 `{ success: true, balance }` (paise) | 400 `{ success: false, error }` | PaymentSettings ("Test Connection")

### CTC config
24. `GET /ctc-config` | hr_admin | row (NUMERIC strings) or `{ org_id, ...CTC_DEFAULTS }` (numbers) | 500 "Failed to fetch CTC config" | CompensationSetup
25. `PUT /ctc-config` | hr_admin | basic_pct, hra_pct, conveyance_pct, pf_pct, pf_max, pt_fixed (each `?? default`) | 200 upserted row | 500 "Failed to save CTC config" | CompensationSetup

### Bank details
26. `GET /bank-details` | hr_admin | 200 `ebd.*` + full_name, email, masked account_number, ORDER BY full_name | 500 | **no caller**
27. `GET /bank-details/:userId` | any role + sameOrg (admin key or self) | 200 masked row or `null` | 403 "Access denied" | **no caller**
28. `POST /bank-details/:userId` | hr_admin | account_holder_name, account_number, ifsc_code req; bank_name, account_type ("savings") | 200 row (masked) |
    400 required; 404 "Employee not found"; Razorpay contact + fund account created best-effort (failure only logged). Does NOT reset is_verified | **no caller**
29. `POST /bank-details/:userId/verify` | hr_admin | 200 `{ message: "Verification initiated (penny drop)" }` | 404; 400 "Fund account not registered with Razorpay"; 500 "Verification failed: <msg>" | **no caller**
30. `GET /bank-verifications` | hr_admin | 200 `ebd.*` + full_name, email, department_name, masked; ORDER BY is_verified ASC, updated_at DESC | CompensationSetup (Bank tab)
31. `POST /bank-details/:userId/approve` | hr_admin | 200 `{ message: "Bank details approved" }` | 404 "Bank details not found" | CompensationSetup
32. `POST /bank-details/:userId/reject` | hr_admin | 200 `{ message: "Bank details rejected" }` (is_verified false, verified_at null) | 404 | CompensationSetup
33. `GET /my-bank-details` | self | 200 masked row or `null` | 500 | MySalarySlips
34. `POST /my-bank-details` | self | same body as 28 | 200 `{ message: "Bank details saved successfully" }`; upsert resets `is_verified = false` | 400 required | MySalarySlips

## 2. Android mapping (P10.3, implemented)
- Admin (feature/admin): `CompensationRepository` (30 endpoints + `org/members`), `PayrollViewModel`, routes `admin/payroll/{key}`
  (compensation · salary-slips · payment-config, gated like web `isAllowed`: feature payroll + orgId), `admin/payroll-employee/{userId}`, `admin/salary-slip/{slipId}`.
- Android-only screens for the uncalled endpoints: employee page (6 history, 8 correction, 26/27/28/29 bank), slip page (11 detail, 18 single payout), Bank tab "All accounts" (26).
- Self-service (feature/organization): `OrganizationRepository.mySalary/mySlipPdf/saveMyBankDetails` (15, 16, 33, 34); Organization → Salary Slips tab,
  first + default only when the tenant has payroll (product decision 2026-09-26).
- PDFs: bytes from the authenticated API, written to the FileProvider `downloads/` cache (`core/media/DownloadedFile.kt`) and opened with ACTION_VIEW.
- Amounts: `core/common/IndianNumberFormat.kt` = `toLocaleString("en-IN")`; `calcFromCtc` ported with JS `Math.round` semantics.

<!--END-->
