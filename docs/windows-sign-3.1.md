# Windows signing handoff: 3.1.0

Work with `fan19zenit-sketch/android`, draft tag `android-v3.1.0-unsigned-20260905`.
Do not rebuild `main`, do not modify the signing key, and do not publish the draft.
The 3.0 signed APK from the previous draft is not this candidate.

1. Download `app-clean-release-unsigned.apk` and `SHA256SUMS.txt` from the 3.1 draft using authenticated `gh release download`. Verify the downloaded SHA-256 against that file before doing anything else.
2. Inspect package `com.example.photovchistotu`, versionName `3.1.0`, versionCode `20260906`, minSdk 24. Stop on any mismatch.
3. Use the existing `C:\Users\et0ya\.android\debug.keystore`, alias `androiddebugkey`. Verify its certificate SHA-256 equals `7d0884e53e704a683a162ca2f34c7db1e1006c07c20147d61f1892d4b3dcfca4`. Never upload the key or a password. Keep the existing private backup.
4. Use Android Build Tools 35 or newer. Run `zipalign -P 16 -f 4` on the unsigned APK into a separate aligned file. Then sign that file with `apksigner`, producing `photo-v-chistotu-3.1.0-update.apk`. Never run zipalign after signing.
5. Run `apksigner verify --verbose --print-certs`, verify the certificate again, and run `zipalign -c -P 16 4` on the signed APK. Also run `python tools/check_apk_alignment.py <signed APK>` from the 3.1 source branch if Python is available.
6. Compare all original ZIP entries with the unsigned input: file bytes must be unchanged, apart from signing metadata. Recheck package/version/minSdk after signing.
7. Upload only the signed APK as a new asset of the same 3.1 draft. Leave `isDraft=true`, do not replace the unsigned input, do not change the public server download URL, and do not publish an in-app update manifest yet.
8. Report signed SHA-256, file size, certificate, package/version and draft status. Mac verification must use that exact uploaded APK, not a rebuilt lookalike.

An APK update is installed OVER the existing app. Never suggest uninstalling or
clearing app data to resolve a signature error: it can destroy the local queue.
In-app installation of future versions still needs Android's user confirmation.
