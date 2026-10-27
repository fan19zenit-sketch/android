# Windows signing handoff: Android 3.2.0

Andrey authorized signing through the existing Windows Codex chat, without publishing to employees.
At preparation time the Windows host was not connected to the Mac Codex app; direct message dispatch was not possible. This file is the complete handoff, not evidence that a Windows agent received it.

## Task for Windows Codex

Use repository `fan19zenit-sketch/android`. Download `app-clean-release-unsigned.apk` and `release-manifest.json` from draft release `android-v3.2.0-unsigned-20261026` using authenticated GitHub access. Keep the release a draft.

1. Verify unsigned APK SHA-256 and byte size against the manifest. Stop if either differs.
2. Verify package `com.example.photovchistotu`, versionName `3.2.0`, versionCode `20261026`, minSdk `24`, targetSdk `34`. Do not rebuild local source or use any 3.0/3.1 artifact.
3. Use the original key at `C:\Users\et0ya\.android\debug.keystore`, alias `androiddebugkey`. Back up the key outside Git before use. Expected certificate SHA-256: `7d0884e53e704a683a162ca2f34c7db1e1006c07c20147d61f1892d4b3dcfca4`. Stop on mismatch; never replace the key or request uninstalling the app.
4. `zipalign -P 16 -f 4` BEFORE signing, then sign as `photo-v-chistotu-3.2.0-update.apk`. Never zipalign after signing. Enable v2/v3 signatures, with compatibility down to API 24.
5. Run `apksigner verify --verbose --print-certs`, `zipalign -c -P 16 4`, and `python tools/check_apk_alignment.py` on the signed file. Compare all original ZIP entry contents against the unsigned APK: unchanged, except added signature metadata.
6. Report signed file SHA-256, certificate digest, size, package/version/minSdk/targetSdk and all verification results. Do not include key contents, passwords, credentials or tokens.
7. Upload ONLY the signed APK and a non-secret verification report to the SAME draft release. Download the uploaded asset again and confirm its SHA-256 equals the local signed file.
8. Do not publish the release, touch production servers, update working download links or advertise the APK to employees. Further acceptance is required.

If a file, key, host connection or authorization is missing, stop with the exact missing item rather than substituting an APK, a key, a release or a newly compiled build.
