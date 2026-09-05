package com.example.simplephotouploader

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.work.WorkManager
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ScreenSmokeTest {
    @Test fun historyAndCameraRenderOffline() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        WorkManager.getInstance(context).cancelAllWork().result.get()
        context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE).edit()
            .putString(AppPrefs.KEY_CITY, "Тестовый филиал")
            .putString(AppPrefs.KEY_DEVICE_UUID, "offline-qa-device")
            .putString(AppPrefs.KEY_BACKEND_URL, "http://127.0.0.1:9").commit()
        val dao = PilotDatabase.get(context).photoRecordDao()
        listOf("sent", "queued", "needs_review").forEachIndexed { i, status ->
            dao.insert(PhotoRecordEntity("screen-$i", if (i == 0) "qa-sent" else null,
                if (i == 0) "qa-message" else null, false, "/test/screen-$i.jpg", null,
                Instant.now().minusSeconds(i * 100L).toString(), null, status,
                if (i == 2) "Снимок за прошлый день. Нужна проверка." else null, false))
        }
        fun screenshot(name: String) {
            Thread.sleep(1500)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            val output = File(context.getExternalFilesDir(null), name)
            output.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            val descriptor = instrumentation.uiAutomation.executeShellCommand(
                "cp ${output.absolutePath} /data/local/tmp/photo-$name")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        ActivityScenario.launch(HistoryActivity::class.java).use {
            onView(withId(R.id.recyclerHistory)).check(matches(isDisplayed()))
            screenshot("history.png")
        }
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val beforeCapture = dao.count()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.captureButton)).check(matches(isDisplayed()))
            val deadline = SystemClock.elapsedRealtime() + 15000
            var ready = false
            while (!ready && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { ready = it.findViewById<View>(R.id.captureButton).isEnabled }
                Thread.sleep(100)
            }
            assertTrue("Camera must become ready", ready)
            screenshot("camera.png")
            scenario.onActivity { activity ->
                repeat(10) { repeatCount ->
                    activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN,
                        KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, repeatCount))
                }
            }
            val captureDeadline = SystemClock.elapsedRealtime() + 15000
            while (dao.count() == beforeCapture && SystemClock.elapsedRealtime() < captureDeadline) Thread.sleep(100)
            Thread.sleep(500)
            assertEquals("Holding the volume key creates one photo", beforeCapture + 1, dao.count())
            val captured = dao.getAllHistory().first { !it.id.startsWith("screen-") }
            assertTrue(File(captured.photoPath).length() > 0)
        }
        WorkManager.getInstance(context).cancelAllWork().result.get()
        dao.getAllHistory().filter { it.photoPath.startsWith(context.filesDir.absolutePath + "/photos/") }.forEach {
            File(it.photoPath).delete()
            it.thumbnailPath?.let { path -> File(path).delete() }
            dao.deleteByPhotoPath(it.photoPath)
        }
        listOf(0, 1, 2).forEach { dao.deleteByPhotoPath("/test/screen-$it.jpg") }
    }
}
