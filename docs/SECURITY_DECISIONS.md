# Android security decisions

Records security choices for the Android app that are deliberate trade-offs, with the reasoning and when to revisit them. Each entry links to its item in [PRODUCTION_READINESS_PLAN.md](PRODUCTION_READINESS_PLAN.md).

## P2.6 Room database encryption: risk accepted (2026-10-09)

**Decision:** we do not encrypt the Room cache (`AinoDatabase`) with SQLCipher. The risk is accepted for the public launch.

**What the database holds:** a read cache of API responses (chat threads and messages, tasks, attendance and similar), plus the outbox of not-yet-sent mutations. It never holds credentials.

**Why the residual risk is low:**

- **Encrypted at rest by the OS.** Every device that can install the app (minSdk 26) uses file-based encryption. Its credential-encrypted storage is unreadable until the user unlocks the device after boot. The database lives in that storage.
- **App-private.** The file is in the app's private data directory. Other apps cannot read it unless the device is rooted.
- **No backup or transfer.** `allowBackup=false` and `data_extraction_rules.xml` exclude every domain from cloud backup and device-to-device transfer (P1.2), so the file cannot leave the device through Android backup.
- **Scoped and wiped.** Rows are keyed by tenant **and** user. Sign-out, a revoked session (password change, removal, or signing in on another phone) and switching accounts all clear the previous scope and cancel its outbox (`AuthViewModel`, `SessionRevocation`).
- **Credentials live elsewhere.** The bearer token is AES-GCM encrypted with a non-exportable Android Keystore key (`KeystoreTokenStore`). Biometric credentials are Keystore-bound as well. A copy of the database cannot be used to call the API.
- **Screenshots of the most sensitive screens are blocked.** `FLAG_SECURE` covers salary slips and view-once media (P2.4).

**What SQLCipher would add:** protection against someone who can read the app's private files on an unlocked, rooted or forensically imaged device. It would cost about 7 MB of native code per ABI, a Keystore-wrapped database key, a destructive migration of every existing cache, and slower cold starts on low-end phones used by frontline staff.

**Revisit when any of these happens:**

- a customer contract or DPDP audit requires application-level encryption at rest;
- the cache starts storing payroll, bank, identity documents or health data;
- MDM or kiosk deployments put shared or unmanaged rooted devices in scope.
