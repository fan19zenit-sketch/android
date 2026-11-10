package com.example.simplephotouploader

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Runs only against the loopback harness and a disposable emulator. */
@RunWith(AndroidJUnit4::class)
class CookCameraBackendIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val base = "http://127.0.0.1:18433"
    private val device = "test-device-a"

    private fun await(timeoutMs: Long = 45_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        assertTrue("Timed out waiting for isolated camera upload", predicate())
    }

    @Test fun cameraPhotoKeepsSelectedCookThroughQueueAndBackend() {
        assumeTrue(BuildConfig.BUILD_TYPE == "localtest" &&
            InstrumentationRegistry.getArguments().getString("isolatedCookBackend") == "1")
        assertEquals("com.example.photovchistotu.localtest", BuildConfig.APPLICATION_ID)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, android.Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT >= 33)
            automation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val work = WorkManager.getInstance(context)
        work.cancelAllWork().result.get()
        val prefs = context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(AppPrefs.KEY_CITY, "Тест").putString(AppPrefs.KEY_DEVICE_UUID, device)
            .putString(AppPrefs.KEY_BACKEND_URL, base).commit()
        val local = CookLocalSession.preferences(context)
        local.edit().putBoolean("workflow_enabled", true).remove(CookOfflineSelectionCache.KEY).commit()
        CookLocalSession.cache(context).clear()
        val enable = Request.Builder().url("$base/fixture/enable-capability").post(ByteArray(0).toRequestBody()).build()
        PhotoApi.execute(enable).use { assertEquals(200, it.code) }
        val api = CookWorkflowClient(base)
        val binding = api.initializeBinding(device)
        val first = requireNotNull(api.addManual(device, UUID.randomUUID().toString(), "Тестовый повар A", listOf("cook"), binding))
        val second = requireNotNull(api.addManual(device, UUID.randomUUID().toString(), "Тестовый повар B", listOf("cook"), binding))
        val operator = requireNotNull(api.addManual(device, UUID.randomUUID().toString(), "Тестовый оператор", listOf("operator"), binding))
        val zone = ZoneId.of(requireNotNull(binding.timezone))
        val confirmed = Instant.now().minusSeconds(1).atZone(zone)
        val shift = CookShift(UUID.randomUUID().toString(), 0, binding.branchRef, confirmed.toLocalDate().toString(),
            zone.id, confirmed.toOffsetDateTime().toString(), listOf(first.id, second.id), listOf(operator.id), first.id)
        val saved = requireNotNull(api.confirmShift(device, UUID.randomUUID().toString(), shift, binding))
        val people = listOf(first, second, operator)
        assertTrue(CookDeviceRosterCache(local).save(binding, people))
        assertTrue(CookLocalSession.cache(context).save(CachedCookShift(binding, shift.copy(revision = saved.second), people)))
        val dao = PilotDatabase.get(context).photoRecordDao()
        val existing = dao.getAllHistory().map { it.id }.toSet()
        var capturedPath: String? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                await {
                    try {
                        onView(withText(second.displayName)).check(matches(isDisplayed()))
                        onView(withId(R.id.captureButton)).check(matches(isEnabled()))
                        true
                    } catch (_: Throwable) { false }
                }
                onView(withText(second.displayName)).perform(scrollTo(), click())
                onView(withId(R.id.captureButton)).perform(click())
                await { dao.getAllHistory().any { row -> row.id !in existing && !row.jobId.isNullOrBlank() && row.photoContextAck == "resolved" } }
                val row = dao.getAllHistory().single { it.id !in existing }
                capturedPath = row.photoPath
                assertEquals(second.id, JSONObject(requireNotNull(row.photoContext)).getJSONObject("cookRef").getString("id"))
                assertEquals(device, row.captureDeviceUuid)
                val file = File(row.photoPath)
                assertTrue(file.isFile)
                val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                PhotoApi.execute(Request.Builder().url("$base/fixture/upload/${row.id}").build()).use { response ->
                    assertEquals(200, response.code)
                    val server = JSONObject(requireNotNull(response.body).string())
                    assertEquals(row.jobId, server.getString("jobId"))
                    assertEquals(hash, server.getString("sha256"))
                    assertEquals(1, server.getInt("queued"))
                    assertEquals(second.id, server.getJSONObject("photoContext").getJSONObject("cookRef").getString("id"))
                }
            }
        } finally {
            work.cancelAllWork().result.get()
            capturedPath?.let { PhotoHistoryStore.removeByPhotoPath(context, it); File(it).delete() }
        }
    }
}
