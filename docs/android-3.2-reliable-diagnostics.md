# Android 3.2.0: connection recovery and durable diagnostics

Package `com.example.photovchistotu`, versionCode `20261026`, minSdk 24, targetSdk 34.
This builds on the shipped 3.1 branch, not the outdated Android source on `main`.

## Scope

- Persist the device UUID BEFORE registration. Losing a response no longer creates a different UUID on the next attempt.
- Record bounded, atomic connection events in `noBackupFilesDir`: operation, phase, endpoint label, random trace, duration, HTTP status and coarse error code. No URLs, headers, exception messages, photos, employee identities, network names or IP addresses.
- Keep at most 240 local events / 192 KiB. Journal write failure cannot prevent a photo acknowledgement. The last non-support failure is retained separately with its timestamp.
- OkHttp observes DNS, connect, TLS, request, upload and response phases. Registration has a shorter timeout budget than photo transfer. Error details are visible on retrying history entries instead of hidden by the generic retry label.
- Fixed HTTPS fallback for existing photo API operations and support. No HTTP downgrade, redirects, arbitrary hosts or configurable executable code. Reuse the exact device UUID and clientUploadId across attempts. Server acknowledgement is committed before scheduling status polling.
- A shared coroutine mutex serializes upload workers and direct foreground attempts. Opening the main screen, taking a photo or explicitly retrying can start uploads without waiting for WorkManager. Background scheduling remains as a recovery mechanism. Stop a queue pass on the first network failure rather than repeating the timeout for every photo.
- The manual connection check executes directly on IO, independently of WorkManager constraints. Diagnostics are also accessible before registration. A sanitized local JSON report can be shared even before enrollment.
- Support v2 carries optional event batches and coarse network/power state alongside the unchanged v1 diagnostics. Drop only acknowledged submitted IDs. Retain events until acknowledged, subject to the local size cap. Existing v1 clients remain supported.
- Server authenticates journal heartbeats, deduplicates by device/event ID, retains 14 days using server receipt time and at most 2,000 events per device. No public journal-reading endpoint or new remote camera/delete commands.
- Update downloads can use the two fixed official HTTPS locations. Existing SHA-256, package, increasing version, minSdk and installed-certificate checks remain mandatory.

## Endpoints

Primary: `https://194-55-235-241.sslip.io/mobile-api` and `/mobile-support`.
Reserve: `https://app.sushigohub.ru/photo-api` and `/photo-support`.
APK mirrors: primary `/downloads/<filename>.apk`, reserve `/photo-downloads/<filename>.apk`.

Both hosts currently use the SAME machine/IP. This can work around a hostname/SNI/DNS-specific problem; it is NOT an independent network path, host failover or a guarantee against provider blocking. Connection checks show technical evidence, not a diagnosis of a provider from a timeout alone.

Onboarding events remain local until the device can enroll and reach support. If neither HTTPS endpoint is reachable, no server can collect a fresh queue or journal remotely. The export action is a last resort, not a required employee workflow.

## Production preparation, 26 October 2026

Only the separate support service and isolated HTTPS routes were updated. The main photo/MAX backend file remained unchanged (SHA-256 `f7a6f1df3a3b4f425687a65811e5d6e7eb3b671e88565481df12b91abb495579`).
Before deployment: copied current Caddy configuration and support sources; made a consistent SQLite backup on the server. Validated the candidate Caddy configuration, restarted only support and gracefully reloaded Caddy.
Checks: both support health endpoints HTTP 200; empty registration payload on reserve HTTP 422 without creating a device; reserve `/photo-api/admin` HTTP 404. Main backend, support and Caddy remained active.

No real devices were enrolled, no real photos were uploaded/replayed/deleted by these tests, and no APK was distributed or announced through production update configuration.

## Verification

- Unit tests: capture gate, upload/date policy, remote settings, official URLs and error classification.
- Python tests: v1 authorization/configuration plus v2 journal authentication, idempotent acknowledgement, retention and strict privacy schema.
- Android 15, 16 KiB: real DNS failure and delayed response, bounded private journal, HTTPS failover with identical multipart clientUploadId, no failover on HTTP 429, foreground uploads independent of workers and serialized concurrent attempts, lost acknowledgement recovery, camera recovery, queue, history and support screens.
- Separate actual 3.1 -> 3.2 in-place update on an emulator using the same TEST certificate preserved city, device UUID, queue ID and original photo bytes. This is not a test using the production Windows signing key.
- Release APK native alignment is checked by `tools/check_apk_alignment.py`.

The optional upgrade fixture test is skipped in a normal full run; run it with `upgrade_mode=seed` on 3.1, install 3.2 with `adb install -r`, then `upgrade_mode=verify`.

## Release Gate

The Mac debug certificate is NOT the production certificate. Do not distribute the debug APK and do not tell users to uninstall or clear data.
Build an unsigned clean release, upload it to a draft GitHub release with its exact digest, then sign that exact file on Windows. See `windows-sign-3.2.md`.
Do not mark the Kirillov/Wozhega incident fixed solely because emulator and server tests pass. Physical phone/network recovery requires new evidence after the signed update is actually installed.

Android code/native defects can still require a signed APK. Remote settings are deliberately restricted and cannot fix every possible Android/network/OEM failure without an update.
