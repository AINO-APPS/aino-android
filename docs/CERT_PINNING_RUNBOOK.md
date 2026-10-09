# TLS certificate pinning runbook (P2.5)

Release builds of the Android app can pin the TLS public keys of `aino.org.in` and every subdomain (`next.`, `www.`, `cdn.` and so on). A pinned app refuses to talk to a server whose certificate chain contains none of the pinned keys. That blocks interception through a rogue or user-installed CA, but **a wrong pin locks every installed app out until users update**. Follow this runbook exactly.

## How it works

- Pins come from the `AINO_CERT_PINS` build property or environment variable. The value is `;`-separated `sha256/<base64 SHA-256 of the SubjectPublicKeyInfo>` entries.
- `CertificatePinning` (`core/network/CertificatePinning.kt`) applies them to the shared OkHttp client used by the API, media, realtime WebSocket and avatar fetches, and to the `direct` updater.
- **At least two pins are required.** With fewer, pinning stays off. Debug builds never pin.
- The release workflow reads the GitHub Actions **variable** `AINO_CERT_PINS`, not a secret, because pins are public.
- Hosts outside `aino.org.in` are never pinned. This covers GIPHY, link previews, Firebase and Play.

## Choosing pins

A chain matches when **any** certificate in it carries a pinned key. Pin keys that survive a normal certificate renewal:

1. **Primary:** the current issuing intermediate CA key of the edge certificate. Cloudflare issues from Google Trust Services (WE1 or WR1) or Let's Encrypt (E5/E6 or R10/R11) and may switch between them.
2. **Backup:** the root CA key behind each issuer the edge may use. Pin at least two different CA families, so a CA switch at the edge does not lock users out.

Never pin only the leaf certificate. It changes every renewal, about every 90 days.

## Computing a pin

Run this **from a network without TLS inspection**. On a corporate network the proxy re-signs the traffic, and you would pin the proxy's key.

```bash
host=next.aino.org.in
openssl s_client -connect $host:443 -servername $host -showcerts </dev/null 2>/dev/null \
  | awk '/BEGIN CERT/{n++} {print > "cert" n ".pem"}'
for f in cert*.pem; do
  echo "$f $(openssl x509 -in $f -noout -subject -issuer | tr '\n' ' ')"
  echo "  sha256/$(openssl x509 -in $f -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64)"
done
```

Check every host the app uses (API, WebSocket and CDN), and confirm that each chain contains at least one of your pins. Root pins can also be computed from the CA's published root certificate.

## Enabling

1. Set the repository variable: Settings → Secrets and variables → Actions → Variables → `AINO_CERT_PINS` = `sha256/AAAA...=;sha256/BBBB...=`.
2. Build a release locally with the same value: `./gradlew assemblePlayRelease -PAINO_CERT_PINS="..."`. Install it on a device **on a normal mobile network** and test sign-in, chat media, a call (realtime) and the update check.
3. Tag the release.

## Rotating or adding a CA

1. Add the new CA's key **in an app release first** and keep the old pins. Users need time to update, so wait until most active installs run that version (Play Console, release dashboard) before changing anything on the server.
2. Switch the edge certificate.
3. Remove the old pin only in a later release.

## Kill switch

If a pin mismatch ships:

1. Point the edge back at a certificate whose chain matches a shipped pin. This is the fastest fix, because the pins already in the app keep working.
2. Otherwise, ship a hotfix with `AINO_CERT_PINS` empty, which turns pinning off. On Play, use a high-priority in-app update. The `direct` updater cannot reach the server while pins mismatch, so pilots must reinstall the APK manually.

## Status

As of 2026-10-09 the code is shipped with pinning **off**: `AINO_CERT_PINS` is unset. The pins could not be computed safely from the build machine because it sits behind TLS inspection. Enable pinning with the steps above from a clean network.
