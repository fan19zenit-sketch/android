package com.example.simplephotouploader

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirstLaunchPermissionsTest {
    @Test fun cameraPermissionPrecedesNotificationPermission() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE).edit()
            .putString(AppPrefs.KEY_CITY, "Тест").commit()
        automation.revokeRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        automation.revokeRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)

        fun clickSystemButton(id: String): Boolean {
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val root = automation.rootInActiveWindow
                val button = root?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/$id")
                    ?.firstOrNull { it.isClickable }
                if (button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
                SystemClock.sleep(100)
            }
            return false
        }

        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertTrue("Camera permission dialog did not appear first", clickSystemButton("permission_allow_foreground_only_button"))
                val cameraDeadline = SystemClock.elapsedRealtime() + 5_000
                while (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED &&
                    SystemClock.elapsedRealtime() < cameraDeadline) SystemClock.sleep(100)
                assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.CAMERA))
                assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
                assertTrue("Notification permission dialog did not appear second", clickSystemButton("permission_allow_button"))
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                    SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
                assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
            }
        } finally {
            automation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
            automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
