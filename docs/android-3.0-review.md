# Android 3.0 candidate (2026-09-05)

## Scope

Android-only changes on `codex/android-reliable-queue-20260905`.
The production backend, MAX messages, dataset and Google Sheets were not modified.
Do not deploy the backend snapshot in this repository over the production backend.

## Reliability changes

- One WorkManager upload chain replaces the activity and foreground-service upload loops.
- The Room photo ID is sent as `clientUploadId` on every attempt, including a lost acknowledgement.
- An HTTP 202 response must contain a job ID. The job ID and queue removal are committed together.
- Accepted photos only poll their server job. They are not uploaded again on local retry.
- Every pending job is checked by ID instead of looking only at the last ten jobs.
- Delayed retry kicks do not become prerequisites for new captures. Android can still defer background work; a 30-second kick is not a delivery-time guarantee.
- Work passes yield after roughly three minutes, plus the current request (120-second limit).
- Originals and history are retained. Old-day uploads pause for explicit review using Moscow dates.
- Room v1 -> v2 uses a non-destructive migration. Legacy preferences and orphaned saved files are recovered; orphaned files are never automatically submitted.
- Repeated key-down events and simultaneous capture clicks cannot start overlapping captures.
- Changing the branch while uploads are unresolved is blocked.
- Delete-from-chat requires confirmation and checks the individual job if the delete acknowledgement is lost.

## Interface

The clean flavor has a redesigned camera, light history cards, status summaries,
stable scrolling, clear retry actions and a visible version number. A low-storage
warning is shown below 200 MiB; capture is blocked below 15 MiB.

Requests carry X-App-Version, X-App-Version-Code and X-App-Package. The production
backend does not yet persist these headers, so this alone is not a deployed
branch-version dashboard. The exact installed Vokhtoga build could not be proven
from existing server data. Missing clientUploadId suggests an older build or path,
not a definitive version number.

## Build and tests

Requires JDK 17 and Android SDK 34. On a JIT crash in Microsoft JDK on macOS,
`JAVA_TOOL_OPTIONS=-XX:TieredStopAtLevel=1` was used for the build tools only.

```sh
bash gradlew :app:testCleanDebugUnitTest :app:lintCleanDebug :app:assembleCleanPreview :app:assembleCleanRelease
bash gradlew :app:connectedCleanDebugAndroidTest
```

Unit tests cover eligibility, Moscow date boundaries and HTTP retry classification.
Instrumented tests cover schema migration, identity preservation, acknowledged
job protection, more than ten pending jobs, offline screens, and a mock HTTP server
disconnecting after receiving the upload. No test sends photos to production.

## Distribution gate

Final local verification: 6 unit tests and 7 Android 14 emulator tests passed;
Android Lint and both APK builds succeeded. Lint still reports non-blocking warnings
(including old dependencies and unused resources). The camera test captured a real
emulated-camera JPEG and checked ten repeated volume-key events produced one record.
Server-deduplicated jobs update all local references, including deletion and error states.

Screenshots: [camera](qa/camera.png), [history](qa/history.png). These use offline QA
data and the emulator camera, not employee photos.

Final preview APK SHA-256: `71202236dbdf2a2c61d1ca132d3104b9f86b1e224d8eb8c324abe663c4a72f13`.
Unsigned release SHA-256: `373436fab0db94e426a4c133901471c0bc52ef80be744ee2f462deb02a86731c`.

- Preview package: `com.example.photovchistotu.preview`, label `Фото в чистоту · Тест`.
- Production package remains `com.example.photovchistotu`; versionCode 20260905, versionName 3.0.0.
- Preview installs separately. Registering it with a real branch would create a new device and send real photos; use a dedicated test branch for delivery checks.
- Release output is unsigned. Do not distribute it until signing and field QA are complete.
- Published pilot signer SHA-256: `7d0884e53e704a683a162ca2f34c7db1e1006c07c20147d61f1892d4b3dcfca4`.
- The local debug signer differs. Updating in place requires the original signing key, probably `%USERPROFILE%\.android\debug.keystore` on the original Windows build computer. Verify its certificate before use; never commit it.
- Do not uninstall the working app, clear its storage or replace the download URL with this candidate. That can lose the local queue/history.
- First field-check one dedicated device: offline capture, reconnect, restart, long press of volume, individual deletion, day boundary and update over the old APK with queued photos. Then expand gradually.

## Remaining limits

- This is not an exactly-once guarantee for MAX delivery. The server's client-key lookup is bounded; durable unique idempotency and delivery reconciliation still need server work.
- Existing API traffic uses HTTP. HTTPS, device authentication and server version inventory require coordinated backend deployment, not a unilateral Android URL change.
- Retaining originals consumes phone storage. A safe export/retention policy with confirmed delivery must be agreed before automatic deletion is reintroduced.
- Physical camera behavior, OEM battery restrictions and update signing need real-device tests in addition to emulator tests.
- The legacy flavor now uses the same queue; rollout targets the clean flavor only. Do not publish a legacy build without separate QA.
