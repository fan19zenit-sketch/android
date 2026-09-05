package com.example.simplephotouploader

import android.Manifest
import android.content.Context
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CameraRecoveryTest {
    @Test fun cameraCompletesOrRecoversWithoutAutomaticRetake() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        WorkManager.getInstance(context).cancelAllWork().result.get()
        context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE).edit()
            .putString(AppPrefs.KEY_CITY, "Тест камеры")
            .putString(AppPrefs.KEY_DEVICE_UUID, "offline-camera-qa")
            .putString(AppPrefs.KEY_BACKEND_URL, "http://127.0.0.1:9").commit()
        RemoteSupport.prefs(context).edit().clear().commit()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val dao = PilotDatabase.get(context).photoRecordDao()
        val before = dao.getAllHistory().map { it.id }.toSet()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var ready = false
                val initialization = SystemClock.elapsedRealtime() + 15000
                while (!ready && SystemClock.elapsedRealtime() < initialization) {
                    scenario.onActivity { ready = it.findViewById<View>(R.id.captureButton).isEnabled }
                    Thread.sleep(100)
                }
                assertTrue(ready)
                scenario.onActivity { it.findViewById<View>(R.id.captureButton).performClick() }
                Thread.sleep(35000)
                val added = dao.getAllHistory().filter { it.id !in before }
                assertTrue("No automatic retakes", added.size <= 1)
                if (added.isEmpty()) {
                    assertTrue(RemoteSupport.prefs(context).getString("event", "") in
                        setOf("camera_timeout", "camera_capture_error", "camera_unavailable"))
                    scenario.onActivity { assertTrue("Camera control must not stay locked",
                        it.findViewById<View>(R.id.captureButton).isEnabled) }
                } else {
                    assertTrue(File(added.single().photoPath).length() > 0)
                }
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                File(context.getExternalFilesDir(null), "camera-recovery.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
        } finally {
            WorkManager.getInstance(context).cancelAllWork().result.get()
            dao.getAllHistory().filter { it.id !in before }.forEach {
                File(it.photoPath).delete()
                it.thumbnailPath?.let { path -> File(path).delete() }
                dao.deleteByPhotoPath(it.photoPath)
            }
            RemoteSupport.prefs(context).edit().clear().commit()
        }
    }
}
