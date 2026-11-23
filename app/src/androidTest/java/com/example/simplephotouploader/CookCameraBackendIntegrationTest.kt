package com.example.simplephotouploader

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.hamcrest.Matchers.allOf
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

    @Test fun reopeningCameraRecoversNewerDeviceShiftWithoutChangingQueuedPhoto() {
        assumeTrue(BuildConfig.BUILD_TYPE == "localtest" &&
            InstrumentationRegistry.getArguments().getString("isolatedCookBackend") == "1")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, android.Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT >= 33)
            automation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val work = WorkManager.getInstance(context)
        work.cancelAllWork().result.get()
        val prefs = context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(AppPrefs.KEY_CITY, "Тест").putString(AppPrefs.KEY_DEVICE_UUID, device)
            .putString(AppPrefs.KEY_BACKEND_URL, base).putString(AppPrefs.KEY_DEVICE_MODE, AppPrefs.DEVICE_MODE_MAIN)
            .putString(AppPrefs.KEY_MODE_CONFIRMED_DEVICE, device).commit()
        val local = CookLocalSession.preferences(context)
        local.edit().putBoolean("workflow_enabled", true).remove(CookOfflineSelectionCache.KEY).commit()
        CookPendingShiftStore(local).clear()
        val support = RemoteSupport.prefs(context)
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        assertTrue(boot >= 0)
        support.edit().putBoolean("paused", true).putLong("ttl", 300).putInt("boot", boot)
            .putLong("received", SystemClock.elapsedRealtime()).commit()
        val enable = Request.Builder().url("$base/fixture/enable-capability").post(ByteArray(0).toRequestBody()).build()
        PhotoApi.execute(enable).use { assertEquals(200, it.code) }
        val api = CookWorkflowClient(base)
        val binding = api.initializeBinding(device)
        fun person(name: String, roles: List<String>) = requireNotNull(api.addManual(device,
            UUID.randomUUID().toString(), name, roles, binding))
        val oldCook = person("Старов Антон", listOf("cook", "operator"))
        val newCook = person("Новиков Борис", listOf("cook", "operator"))
        val zone = ZoneId.of(requireNotNull(binding.timezone))
        fun confirm(cook: CookEmployee): CookShift {
            val stamp = Instant.now().atZone(zone)
            val shift = CookShift(UUID.randomUUID().toString(), 0, binding.branchRef, stamp.toLocalDate().toString(),
                zone.id, stamp.toOffsetDateTime().toString(), listOf(cook.id), listOf(cook.id), cook.id)
            return shift.copy(revision = requireNotNull(api.confirmShift(device, UUID.randomUUID().toString(), shift, binding)).second)
        }
        val oldShift = confirm(oldCook)
        val cache = CookLocalSession.cache(context)
        assertTrue(CookDeviceRosterCache(local).save(binding, listOf(oldCook)))
        assertTrue(cache.saveRefreshed(CachedCookShift(binding, oldShift, listOf(oldCook)), oldCook.id))
        val dao = PilotDatabase.get(context).photoRecordDao()
        val existing = dao.getAllHistory().map { it.id }.toSet()
        fun photos() = dao.getAllHistory().filter { it.id !in existing }
        fun capture(cook: CookEmployee) {
            await { runCatching {
                onView(withText(cook.displayName)).check(matches(isDisplayed()))
                onView(withId(R.id.captureButton)).check(matches(isEnabled()))
                true
            }.getOrDefault(false) }
            onView(withId(R.id.captureButton)).perform(click())
        }
        fun author(raw: String) = JSONObject(raw).getJSONObject("cookRef").getString("id")
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                capture(oldCook)
                await { photos().size == 1 }
            }
            val oldPhoto = photos().single()
            val oldContext = requireNotNull(oldPhoto.photoContext)
            assertEquals(oldCook.id, author(oldContext))
            assertTrue(oldPhoto.queuedInUploadQueue)
            assertNull(oldPhoto.jobId)
            val newShift = confirm(newCook)
            assertEquals(oldShift.revision, newShift.revision)
            assertEquals(oldShift.id, cache.load()?.shift?.id)
            ActivityScenario.launch(MainActivity::class.java).use {
                await { cache.load()?.shift?.id == newShift.id }
                capture(newCook)
                await { photos().size == 2 }
                val newPhoto = photos().single { it.id != oldPhoto.id }
                assertEquals(newCook.id, author(requireNotNull(newPhoto.photoContext)))
                assertEquals(oldContext, photos().single { it.id == oldPhoto.id }.photoContext)
            }
        } finally {
            work.cancelAllWork().result.get()
            support.edit().remove("paused").commit()
            for (photo in photos()) {
                PhotoHistoryStore.removeByPhotoPath(context, photo.photoPath)
                File(photo.photoPath).delete()
            }
        }
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
                onView(withText(second.displayName)).check(matches(isDisplayed())).perform(click())
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
                val retry = Request.Builder().url("$base/cook-v2/upload")
                    .post(PhotoUploadMultipart.build(device, row.id, file, row.photoContext)).build()
                PhotoApi.execute(retry).use { response ->
                    assertEquals(202, response.code)
                    assertEquals(row.jobId, JSONObject(requireNotNull(response.body).string())
                        .getJSONObject("job").getString("job_id"))
                }
                PhotoApi.execute(Request.Builder().url("$base/fixture/upload/${row.id}").build()).use { response ->
                    assertEquals(200, response.code)
                    assertEquals(1, JSONObject(requireNotNull(response.body).string()).getInt("queued"))
                }
                repeat(2) {
                    val qc = Request.Builder().url("$base/fixture/fake-qc/${row.id}")
                        .post(ByteArray(0).toRequestBody()).build()
                    PhotoApi.execute(qc).use { response ->
                        assertEquals(200, response.code)
                        val result = JSONObject(requireNotNull(response.body).string())
                        assertTrue(result.getBoolean("fakeTransport"))
                        assertEquals(1, result.getInt("photoCount"))
                        assertEquals(1, result.getInt("operatorCardCount"))
                        assertTrue(result.getBoolean("operatorRouteCorrect"))
                        assertTrue(result.getBoolean("operatorButtonWorks"))
                        assertEquals(second.id, result.getString("cookId"))
                        assertTrue(result.getBoolean("captionHasReason"))
                        assertTrue(result.getBoolean("cardHasLesson"))
                        assertTrue(result.getBoolean("reportHasPhotoAndLesson"))
                    }
                }
            }
        } finally {
            work.cancelAllWork().result.get()
            capturedPath?.let { PhotoHistoryStore.removeByPhotoPath(context, it); File(it).delete() }
        }
    }

    @Test fun botChangeOnOpenCameraKeepsOldQueuedPhotoAndPhoneAuthor() {
        assumeTrue(BuildConfig.BUILD_TYPE == "localtest" &&
            InstrumentationRegistry.getArguments().getString("isolatedCookBackend") == "1")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, android.Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT >= 33)
            automation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val prefs = context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(AppPrefs.KEY_CITY, "Тест").putString(AppPrefs.KEY_DEVICE_UUID, device)
            .putString(AppPrefs.KEY_BACKEND_URL, base).putString(AppPrefs.KEY_DEVICE_MODE, AppPrefs.DEVICE_MODE_MAIN).commit()
        val local = CookLocalSession.preferences(context)
        local.edit().putBoolean("workflow_enabled", true).remove(CookOfflineSelectionCache.KEY)
            .remove(CookShiftCache.AUTHOR_KEY).commit()
        CookPendingShiftStore(local).clear()
        val cache = CookLocalSession.cache(context)
        val support = RemoteSupport.prefs(context)
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        assertTrue(boot >= 0)
        support.edit().putBoolean("paused", true).putLong("ttl", 300).putInt("boot", boot)
            .putLong("received", SystemClock.elapsedRealtime()).commit()
        val work = WorkManager.getInstance(context)
        work.cancelAllWork().result.get()
        fun post(path: String) = PhotoApi.execute(Request.Builder().url("$base$path")
            .post(ByteArray(0).toRequestBody()).build()).use { assertEquals(200, it.code) }
        post("/fixture/enable-capability")
        val api = CookWorkflowClient(base)
        val binding = api.initializeBinding(device)
        fun person(name: String, role: String) = requireNotNull(api.addManual(device, UUID.randomUUID().toString(), name,
            listOf(role), binding))
        val first = person("Тест смены А", "cook")
        val second = person("Тест смены Б", "cook")
        val third = person("Тест смены В", "cook")
        val operator = person("Тест смены О", "operator")
        val replacement = person("Тест смены П", "operator")
        val zone = ZoneId.of(requireNotNull(binding.timezone))
        val now = Instant.now().minusSeconds(2).atZone(zone)
        val shift = CookShift(UUID.randomUUID().toString(), 0, binding.branchRef, now.toLocalDate().toString(),
            zone.id, now.toOffsetDateTime().toString(), listOf(first.id, second.id), listOf(operator.id), first.id)
        val revision = requireNotNull(api.confirmShift(device, UUID.randomUUID().toString(), shift, binding)).second
        val snapshot = CachedCookShift(binding, shift.copy(revision = revision), listOf(first, second, operator))
        assertTrue(CookDeviceRosterCache(local).save(binding, snapshot.employees))
        assertTrue(cache.saveRefreshed(snapshot, second.id))
        val dao = PilotDatabase.get(context).photoRecordDao()
        val existing = dao.getAllHistory().map { it.id }.toSet()
        fun photos() = dao.getAllHistory().filter { it.id !in existing }
        fun captureReady(): Boolean = runCatching {
            onView(withId(R.id.captureButton)).check(matches(isEnabled())); true
        }.getOrDefault(false)
        fun onCameraAuthor(name: String) {
            await { runCatching {
                onView(withText(name)).check(matches(androidx.test.espresso.matcher.ViewMatchers.withParent(androidx.test.espresso.matcher.ViewMatchers.isSelected()))); true
            }.getOrDefault(false) }
        }
        fun author(raw: String) = JSONObject(raw).getJSONObject("cookRef").getString("id")
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                onCameraAuthor(second.displayName)
                await { captureReady() }
                onView(withText(first.displayName)).check(matches(isDisplayed())).perform(click())
                onView(withId(R.id.captureButton)).perform(click())
                await { photos().size == 1 }
                val old = photos().single()
                val oldContext = requireNotNull(old.photoContext)
                assertEquals(first.id, author(oldContext))
                assertTrue(old.queuedInUploadQueue)
                assertNull(old.jobId)
                post("/fixture/branch-shift-from-bot?cook_a=${first.id}&cook_b=${second.id}&operator=${replacement.id}")
                await { cache.load()?.shift?.operators == listOf(replacement.id) }
                val refreshed = requireNotNull(cache.load()).shift
                assertEquals(shift.id, refreshed.id)
                assertEquals(revision + 1, refreshed.revision)
                onCameraAuthor(second.displayName)
                assertEquals(second.id, JSONObject(local.getString(CookShiftCache.AUTHOR_KEY, null)!!).getString("employee"))
                assertEquals(oldContext, photos().single().photoContext)
                await { captureReady() }
                onView(withId(R.id.captureButton)).perform(click())
                await { photos().size == 2 }
                val next = photos().single { it.id != old.id }
                assertEquals(second.id, author(requireNotNull(next.photoContext)))
                assertEquals(refreshed.revision, JSONObject(next.photoContext!!).getInt("shiftRevision"))
                support.edit().remove("paused").commit()
                runBlocking { UploadCoordinator.process(context) }
                await { photos().size == 2 && photos().all { it.photoContextAck == "resolved" && !it.jobId.isNullOrBlank() } }
                for ((row, employee) in listOf(old to first, next to second)) {
                    PhotoApi.execute(Request.Builder().url("$base/fixture/upload/${row.id}").build()).use { response ->
                        assertEquals(200, response.code)
                        val stored = JSONObject(requireNotNull(response.body).string())
                        val photoContext = stored.getJSONObject("photoContext")
                        assertEquals(employee.id, author(photoContext.toString()))
                        assertEquals(JSONObject(row.photoContext!!).getInt("shiftRevision"), photoContext.getInt("shiftRevision"))
                        assertEquals(2, stored.getInt("queued"))
                    }
                }
                post("/fixture/branch-shift-from-bot?cook_a=${first.id}&cook_b=${third.id}&operator=${replacement.id}")
                await { cache.requiresAuthorReview(refreshed) }
                onView(withText("Выбрать автора")).check(matches(isDisplayed()))
                onView(withId(R.id.captureButton)).perform(click())
                await { runCatching {
                    onView(withText("Начать смену")).check(matches(isDisplayed())); true
                }.getOrDefault(false) }
                onView(withText("Начать смену")).perform(click())
                onView(allOf(withText(third.displayName), isAssignableFrom(android.widget.RadioButton::class.java)))
                    .perform(scrollTo(), click())
                onView(withText("Далее")).perform(scrollTo(), click())
                onView(withText("Начать смену")).perform(scrollTo(), click())
                onCameraAuthor(third.displayName)
                assertEquals(2, photos().size)
                assertEquals(oldContext, photos().single { it.id == old.id }.photoContext)
            }
        } finally {
            support.edit().remove("paused").commit()
            work.cancelAllWork().result.get()
            for (row in photos()) {
                PhotoHistoryStore.removeByPhotoPath(context, row.photoPath)
                File(row.photoPath).delete()
            }
            cache.clear()
            CookPendingShiftStore(local).clear()
        }
    }
}
