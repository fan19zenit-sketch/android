#!/usr/bin/env bash
set -eu

collect_diagnostics() {
    mkdir -p app/build/reports/emulator-diagnostics
    adb logcat -d -v threadtime -s AndroidRuntime ActivityTaskManager ActivityManager TestRunner \
        > app/build/reports/emulator-diagnostics/logcat.txt || true
}
trap collect_diagnostics EXIT

# These rules apply only to the disposable CI emulator and fail closed.
adb root
adb wait-for-device
adb shell iptables -w 10 -I OUTPUT 1 -j REJECT
adb shell iptables -w 10 -I OUTPUT 1 -o lo -j ACCEPT
adb shell ip6tables -w 10 -I OUTPUT 1 -j REJECT
adb shell ip6tables -w 10 -I OUTPUT 1 -o lo -j ACCEPT

preview_status=0
bash gradlew :app:connectedCleanPreviewAndroidTest -PofflineInstrumentation=true -Pandroid.testInstrumentationRunnerArguments.class=com.example.simplephotouploader.PhotoRouteGuardTest,com.example.simplephotouploader.CookShiftActivityTest,com.example.simplephotouploader.CookPendingShiftTest,com.example.simplephotouploader.PhotoContextAcknowledgementTest,com.example.simplephotouploader.CookShiftCacheTest,com.example.simplephotouploader.CookPhotoContextFactoryTest,com.example.simplephotouploader.CookWorkflowClientTest,com.example.simplephotouploader.QueueDatabaseTest,com.example.simplephotouploader.CookOfflineDatabaseTest,com.example.simplephotouploader.CookCameraFlowTest --no-daemon --max-workers=2 || preview_status=$?

debug_status=0
bash gradlew :app:connectedCleanDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.simplephotouploader.CameraRecoveryTest,com.example.simplephotouploader.DiagnosticsRecoveryTest,com.example.simplephotouploader.RemoteSupportTest,com.example.simplephotouploader.ScreenSmokeTest,com.example.simplephotouploader.SharedServerJobTest,com.example.simplephotouploader.UploadRecoveryTest,com.example.simplephotouploader.UpgradePersistenceTest --no-daemon --max-workers=2 || debug_status=$?

printf 'Preview tests exit: %s; debug tests exit: %s\n' "$preview_status" "$debug_status"
test "$preview_status" -eq 0 && test "$debug_status" -eq 0
