# Google Play Console submission pack

Answers for the Play Console forms, based on what the `play` flavor actually
does (verified against the code on 2026-10-07). Review them with legal
before the first production submission and update this file whenever a
feature changes the data the app touches.

## Store listing URLs

| Field | Value |
|---|---|
| Privacy policy | `https://www.aino.org.in/privacy` |
| Account deletion | `https://www.aino.org.in/account-deletion` |
| Terms (optional, in description) | `https://www.aino.org.in/terms` |
| Developer contact email | `privacy@aino.org.in` |

The pages are served by the web app (`client/src/pages/legal/` in
`aino-platform`). Deploy the web app before submitting.

## App access

Every screen needs an account created by an organisation administrator.
Provide a reviewer login for a demo tenant under **App content → App access**
with instructions: "Sign in with the username and password below. Attendance
clock-in may ask for location; choose Allow."

## Ads

The app contains no ads.

## Target audience

18 and over. The app is not designed for children.

## Data safety

**Does the app collect or share any of the required user data types?** Yes.
**Is all collected data encrypted in transit?** Yes (HTTPS / WSS only).
**Can users request deletion?** Yes, in the app (Profile → Edit Profile →
Delete My Account) and at the account-deletion URL.

"Shared" in Play's sense means transfer to a third party for its own use. The
service providers below process data on AINO's behalf, so the data is
**collected, not shared**.

| Data type (Play category) | Collected | Optional? | Purposes | Notes |
|---|---|---|---|---|
| Name | Yes | Required | App functionality, Account management | Set by the employer |
| Email address | Yes | Required | App functionality, Account management | |
| User IDs | Yes | Required | App functionality, Account management | |
| Approximate location | Yes | Optional | App functionality | Requested together with precise, as Android 12+ requires |
| Precise location | Yes | Optional | App functionality | Only at clock-in when the employer enables office verification. No background location |
| Photos | Yes | Optional | App functionality | Profile photo, chat attachments |
| Videos | Yes | Optional | App functionality | Chat attachments |
| Voice or sound recordings | Yes | Optional | App functionality | Chat voice notes. Live call audio is not stored |
| Files and docs | Yes | Optional | App functionality | Chat, task and note attachments |
| Calendar events | Yes | Optional | App functionality | AINO calendar, not the device calendar |
| Emails / SMS | No | | | |
| Other in-app messages | Yes | Optional | App functionality | Chat messages |
| Contacts | No | | | The device address book is never read |
| Biometric / face data | Yes | Optional | App functionality, Fraud prevention | Face template for attendance, enrolled on web; Android only detects a face on the device |
| App interactions | No | | | No analytics SDK |
| Crash logs | Yes | Required in the Play build | Analytics (app stability) | Firebase Crashlytics, no user identifiers |
| Diagnostics | Yes | Required in the Play build | Analytics (app stability) | App version, OS version, device model |
| Device or other IDs | Yes | Required | App functionality | App-generated device id and FCM push token |
| Financial info (salary) | Yes | Optional | App functionality | Salary slips the employer publishes, read-only |

## Permissions declarations

| Permission | Declaration | Justification text |
|---|---|---|
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Foreground only (no form needed) | "Verifies that an employee is at the office when they clock in, if their employer enables it." |
| `USE_FULL_SCREEN_INTENT` | **Full-screen intent declaration**: *Calling app* use case | "Shows the incoming voice/video call screen from colleagues over the lock screen." |
| `FOREGROUND_SERVICE_PHONE_CALL` | **Foreground service** declaration: *Phone call* | "Keeps an incoming call ringing and the call connected while the app is in the background." Provide a video of an incoming call. |
| `FOREGROUND_SERVICE_MICROPHONE` / `FOREGROUND_SERVICE_CAMERA` | **Foreground service** declaration: *Microphone* / *Camera* | "Keeps the microphone and camera running during an active call or meeting when the user leaves the app." |
| `MANAGE_OWN_CALLS` | None | Self-managed calls through `ConnectionService`-style integration |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` | **Photo and video permissions** declaration | "Shows recent photos in the chat attachment tray so users can share them with colleagues." If Play rejects this, switch the tray to the system photo picker and remove these permissions. |
| `REQUEST_INSTALL_PACKAGES` | Not in the Play build | Only the `direct` (sideloaded) flavor has it; `android-release.yml` fails if it leaks into `play` |

## Release artifact

Upload `AINO-<version>-play.aab` from the GitHub release (built by
`android-release.yml`). Use the **internal testing** track first.

Enroll in **Play App Signing**. The upload key is the existing release
keystore (`CN=AINO`). Play then re-signs with an app signing key it holds.
Pilots who sideloaded the `direct` APK must uninstall before installing from
Play, because the signatures differ. Alternatively, upload the existing key
as the app signing key during enrollment, which keeps the APKs compatible.
