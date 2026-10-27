# Independent signed APK acceptance: 27 October 2026

Draft release: https://github.com/fan19zenit-sketch/android/releases/tag/untagged-e5cbdc3f8fc55dd240c5
Source commit: `c1e2f42b0365bb511b2fc297d5cded0a2fb29b3d`.

## Artifact

- APK: `photo-v-chistotu-3.2.0-update.apk`, downloaded independently from the existing draft release.
- Size: 7,141,316 bytes.
- SHA-256: `beb6026893a9c2d42a2944f4d11dcd98162151a1b6d214c834d8bc18692053e3`.
- Signer SHA-256: `7d0884e53e704a683a162ca2f34c7db1e1006c07c20147d61f1892d4b3dcfca4`, matching the production-signed 3.1 APK.
- Package: `com.example.photovchistotu`; versionName `3.2.0`; versionCode `20261026`; minSdk 24; targetSdk 34.

## Independent Static Checks

- Downloaded SHA-256 matches both GitHub's asset digest and the Windows verification report.
- `apksigner verify --verbose --print-certs`: valid, one signer, v2 and v3 verified.
- `zipalign -c -P 16 4`: passed.
- `tools/check_apk_alignment.py`: all four 64-bit native libraries passed.
- Compared every original ZIP entry against the exact unsigned APK with SHA-256 `edac8d31c59a955f253bd89028598b31a3705dd15d0fa2e776dd4e5281ca99be`: all original names retained and all original entry contents identical. Only `META-INF/ANDROIDD.RSA`, `META-INF/ANDROIDD.SF`, and `META-INF/MANIFEST.MF` were added.

## Exact Signed APK Runtime Checks

Executed on a dedicated Android 15 emulator with 16 KiB memory pages, using the actual downloaded production-signed files. No re-signing or rebuilding for this test.

- Installed production-signed 3.1, seeded 17 synthetic photo records, then installed signed 3.2 with `adb install -r`.
- In-place update succeeded; city, device UUID, photo paths, dates, original records and acknowledged server job IDs were retained.
- Simulated HTTP 503 preserved the original photograph in the queue.
- Simulated a lost upload acknowledgement: retries retained the same clientUploadId and created only one logical mock delivery job.
- All 13 pre-existing pending jobs reconciled by ID.
- A yesterday photo remained `needs_review` and was never automatically uploaded.
- A held volume key created exactly one actual emulator-camera JPEG; an induced transport outage kept it queued.
- Force-stop and relaunch retained the new photo and all original records. After recovery, the new photo was accepted once by the mock server.
- Chat-delete action succeeded against the mock backend; the local deletion flag persisted.
- The diagnostics screen opened successfully.
- Full emulator reboot retained all 18 records, original files, the deletion flag and the old-photo approval boundary. The sent/deleted photo was not uploaded again.
- No fatal exception from this application appeared in the emulator crash buffer.

Photo and deletion requests in these tests used only `http://127.0.0.1:18080` via adb reverse. No real devices, chats, registrations or photos were changed.

Wi-Fi off does not disable adb reverse. The capture-outage test therefore additionally forced HTTP 503 on the loopback mock server; it did not assume that disabling emulator Wi-Fi alone prevents loopback delivery.

## CI and Live Read-Only Checks

Both GitHub runs for the source commit concluded successfully:

- https://github.com/fan19zenit-sketch/android/actions/runs/40386587005
- https://github.com/fan19zenit-sketch/android/actions/runs/40386594424

CI covers source builds and Android 14 / Android 15 emulator tests; it is distinct from the production-signed runtime acceptance above.

Both the primary and reserve HTTPS support health endpoints returned HTTP 200. No production service/configuration, working download link or release announcement was changed during this acceptance.

## Release Boundary

The signed APK passes the independent technical acceptance above. The release remains a draft and has not been advertised to employees.

Physical phone/OEM/Wi-Fi acceptance remains outstanding. These results do NOT establish that Kirillov's particular network fault has been fixed, or guarantee operation on every Samsung/TECNO/Xiaomi firmware. Update in place; do not uninstall or clear app data.

The former unsigned verification report is historical. Windows signing is now complete, independently checked, and no longer a release blocker. Employee publication/distribution still requires authorization.
