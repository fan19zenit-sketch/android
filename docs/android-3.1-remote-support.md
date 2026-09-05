# Android 3.1: compatibility and remote support

Candidate date: 2026-09-05. Package `com.example.photovchistotu`.
Version name `3.1.0`, version code `20260906` (a monotonic update identifier).
Source branch: `codex/android-3.1-remote-support`, based on the reviewed 3.0 queue.
The old repository backend snapshot must NOT be deployed over production.

## What can be repaired remotely

Server/bot fixes and three allowlisted application settings do not require an APK:
volume-key capture, retry interval (30..300 seconds), and a temporary upload pause.
Configuration is per device, expires rather than renewing on every heartbeat,
and is discarded after a reboot until synchronized again. Pauses last at most
15 minutes. Photos remain in the queue. No remote command can capture a photo,
delete a file, clear the database, change the backend URL, or execute code.

Android-code fixes still require a signed APK update. The application now has
History -> Diagnostics and updates, with an in-place updater. It downloads only
from the fixed official HTTPS downloads host, refuses redirects, limits size to
50 MiB, and checks SHA-256, package, increasing version, minimum Android API and
the exact installed signing certificate before opening the system installer.
The system still requires the employee's confirmation. This is NOT silent device
management or downloadable executable-code hot patching. No uninstall is needed.

The first 3.1 installation must still be distributed as an APK: old releases do
not contain this updater. The 3.0 draft is superseded, not automatically replaced.

## Compatibility and reliability

- CameraX 1.5.3 replaces 1.3.2. Both 64-bit native libraries have 16 KiB ELF LOAD alignment.
- AGP 8.6.1 / Gradle 8.7 package uncompressed native libraries with 16 KiB ZIP alignment.
- Kotlin 2.1.20 and Room 2.7.2 are tested with the existing non-destructive database migration.
- Minimum Android remains API 24. Compile SDK is 35; target SDK deliberately remains 34 in this rollout, avoiding an unrelated edge-to-edge behavior migration.
- Camera requests have a 30-second watchdog. A timeout does not take another photo. Late callbacks cannot unlock a newer capture. A late JPEG is retained for manual review, not auto-uploaded.
- Existing device ID, city, history, photo files, stable upload IDs and queue logic are preserved.
- Known old HTTP backend preferences migrate to the narrow HTTPS route. Custom/mock URLs are not rewritten. Photo requests cannot follow a TLS downgrade redirect.

## Diagnostics and privacy

A separate WorkManager job attempts diagnostics approximately every 15 minutes
and when the app is foregrounded, throttled to five minutes. Manual checks are
available. Android battery/network restrictions can delay it; no exact schedule
or instant remote setting application is promised. A separate bounded HTTP client
prevents diagnostics from occupying the photo upload connection.

Only package/version, manufacturer/model, API/page size, free space, queue/pending/
issue counts, oldest queued age, and one coarse event code/timestamp are collected.
No photo bytes, message text, phone numbers, employee names, or raw logs are sent.
The server keeps the latest snapshot per enrolled device, not an unlimited event log.

Each device is bound to a random installation identifier in Android's no-backup
storage and a server-derived bearer token. Tokens and installation IDs are not
included in inventory output. Enrollment is idempotent after a lost response.
An unknown registry ID or a different installation bound to the same ID is refused.
Administration is an SSH-only CLI; there is no public admin route.

Security boundary: initial enrollment trusts possession of the existing random
device UUID and the existing registration registry. This is a transitional
bootstrap, not hardware attestation or strong authorization of the old photo API.
The legacy registration/photo API's pre-existing device-ownership limitations are
not solved by the diagnostics token. Do not claim that all device APIs are now authenticated.

## Production deployment

The isolated service is installed in `/opt/photo-mobile-support`, with its own
Python virtual environment and pinned dependencies. It listens only on loopback
8050. Caddy exposes `/mobile-support/*` and a method/path allowlist at `/mobile-api/*`.
MAX webhook, backend code, likes, weights, datasets and spreadsheets were not edited.
The old HTTP route remains for already installed releases.

Service: `photo-mobile-support.service`. Memory limit 128 MiB, CPU quota 25%,
read-only system, no new privileges, network limited to localhost. It currently
runs with root read access to the existing root-owned registry; this is not a
claim of full privilege separation. Moving that registry to a least-privilege
shared location is separate work.

SQLite and the HMAC secret are under `/var/lib/photo-mobile-support` with private
permissions. The secret is not in Git. `photo-mobile-support-backup.timer` creates
a consistent SQLite + secret archive daily, retaining seven days locally. These
are server-local recovery copies, NOT off-server disaster recovery.

Deployment on 2026-09-05 passed 16 server tests before Caddy reload. The bot's PID
was unchanged. External checks: support health 200; support docs, mobile admin and
device-list paths 404; empty registration/upload POSTs 422 without creating data.
Initial support RAM was about 28 MiB, zero restarts. No update is published in the
support release table yet, so phones cannot be offered an unverified APK.

## Verification and release gate

Release source commit: `6c8612338f6f219636a369070eb3360300cc7ea3`.
Unsigned APK SHA-256: `eed4238a0e64d83d1236e3638baa121e30eba566a1cf1f660f98b7c8cf2a1177`.
Uploaded size: 7,041,513 bytes. GitHub's uploaded-asset digest matches the local APK.

Final local checks passed: 11 JVM tests and all 13 Android 15 / ARM64 / 16 KiB
instrumented tests, including the final TLS-redirect hardening. Tests cover migration, queue recovery, more
than ten pending jobs, shared server IDs, offline screens, camera capture, held
volume key, watchdog recovery, remote-config expiry, and short/oversized/failed
diagnostics responses. Android Lint reports no errors, but has non-blocking warnings.

Android 10 / ARM64: 12 applicable tests passed, including recovery after the emulator
camera driver crashed. Successful JPEG capture is NOT verified on this image:
`camera.ranchu.so` crashes in `NV21JpegCompressor`, outside the app. The full initial
suite recorded that failure; it was not silently relabeled as a pass.

GitHub CI run [33964225714](https://github.com/fan19zenit-sketch/android/actions/runs/33964225714)
passed all three jobs: build/unit/lint/server/alignment, API 34 device tests and
API 35 16 KiB device tests. CI still emits non-blocking deprecation warnings for
the pre-existing major versions of official GitHub Actions; their runtime is
automatically upgraded by GitHub. No CI test sends
photos to production. Physical Samsung/Tecno/Xiaomi behavior is still unverified.

Release APK is unsigned on the Mac. The original Windows signing certificate is
`7d0884e53e704a683a162ca2f34c7db1e1006c07c20147d61f1892d4b3dcfca4`.
Use `docs/windows-sign-3.1.md`, then verify the signed artifact and an update over
the previous APK before publishing. Do not overwrite working download links,
publish a draft, uninstall the app, or clear storage as part of this process.

No claim is made that all 18 phones have 3.1: inventory appears only after each
phone installs the update, opens it and successfully sends diagnostics.

## Remaining limits

Physical camera faults and OEM background throttling cannot be eliminated by
server settings. Code fixes need consented APK updates. The existing backend's
bounded deduplication lookup still does not provide an exactly-once MAX guarantee.
Retained originals consume storage; there is intentionally no automatic deletion.
The HTTPS hostname relies on sslip.io availability. Signing-key custody remains
on the Windows computer and its private backup, never in this repository.

References: [Android 16 KiB support](https://developer.android.com/guide/practices/page-sizes),
[CameraX releases](https://developer.android.com/jetpack/androidx/releases/camera),
[Android Emulator Runner](https://github.com/ReactiveCircus/android-emulator-runner).
