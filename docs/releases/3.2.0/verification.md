# Android 3.2.0 unsigned acceptance, 26 October 2026

Source: `c1e2f42b0365bb511b2fc297d5cded0a2fb29b3d`.
Draft: https://github.com/fan19zenit-sketch/android/releases/tag/untagged-e5cbdc3f8fc55dd240c5
Draft PR: https://github.com/fan19zenit-sketch/android/pull/1

## Results

- Gradle clean debug APK, release APK, Android tests, unit tests and lint: successful. Existing nonfatal lint warnings remain; this is not a warning-free or dependency-upgrade release.
- Unit tests: 14 passed, no failures.
- Python support service tests: 32 executed, no failures (includes inherited v1 compatibility fixtures).
- Android 15 / 16 KiB full run on the final code: `OK (19 tests)`, no failures. One optional in-place-upgrade fixture is assumption-skipped in that run; 18 tests actually pass.
- Independent 3.1 seed -> `adb install -r` 3.2 -> verify: both fixture runs pass. Original city, device UUID, queue ID and original image bytes preserved. Test-certificate upgrade, NOT production-certificate acceptance.
- Unsigned package metadata: `com.example.photovchistotu`, 3.2.0 / 20261026, minSdk 24, targetSdk 34.
- `zipalign -c -P 16 4`: successful.
- `tools/check_apk_alignment.py`: four native libraries pass 16 KiB validation.
- GitHub asset API digest and size match the locally built unsigned APK. Exact digest and size are in `release-manifest.json`.
- GitHub CI was still pending when this report was written. Local test results do not imply CI or physical phone acceptance.

## Production Preparation

Separate support service and Caddy were backed up before deployment. Support SQLite was consistently backed up server-locally, with private backup permissions. No secret/key was copied into this repository.

Both support health URLs return 200. Empty JSON on reserve registration returns 422 without creating a device. Reserve administrative URL returns 404. APK mirror HEAD works; non-APK paths return 404. Existing backend, support service and Caddy are active. The main MAX/photo backend code hash is unchanged.

The unsigned file is only in a draft release. No production release configuration, working employee download link, real photo, registration or queue was modified by release tests.

## Remaining Gates

1. Sign the exact unsigned APK on Windows with the original production certificate. Do not rebuild.
2. Verify the signed artifact, ZIP entry contents and GitHub round-trip digest.
3. Validate the production-signed update and the affected physical phone/network. Kirillov recovery is NOT yet established.
4. Publish/distribute only after explicit acceptance. Never uninstall or clear application data as an update step.

Windows direct dispatch is blocked because the existing Windows host is not currently connected to Codex. Andrey authorized dispatch; the handoff is prepared but was not delivered to a Windows chat. No substitute chat was created.
