package com.example.simplephotouploader

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.work.WorkInfo
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Runs only in the separate localtest package against the synthetic loopback fixture. */
@RunWith(AndroidJUnit4::class)
class CookOfflineBackgroundIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val base = "http://127.0.0.1:18433"
    private val device = "test-device-a"
    private val city = "Test branch"
    private val employeeId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val shiftId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val prefs get() = context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
    private val backup get() = context.getSharedPreferences("offline-background-fixture", Context.MODE_PRIVATE)

    private fun requireIsolated() {
        assumeTrue(BuildConfig.BUILD_TYPE == "localtest" &&
            InstrumentationRegistry.getArguments().getString("isolatedCookBackend") == "1")
        assertEquals("com.example.photovchistotu.localtest", BuildConfig.APPLICATION_ID)
    }

    @Test fun seed() {
        requireIsolated()
        val work = WorkManager.getInstance(context)
        work.cancelAllWork().result.get()
        val db = CookOfflineDatabase.get(context)
        val api = CookWorkflowClient(base)
        assertFalse(requireNotNull(api.capabilities()).photoContext)
        val binding = api.initializeBinding(device)
        val scope = CookOfflineScope(base, device, binding)
        val keys = listOf(AppPrefs.KEY_BACKEND_URL, AppPrefs.KEY_DEVICE_UUID, AppPrefs.KEY_CITY)
        backup.edit().clear().apply { keys.forEach { key -> putString(key, prefs.getString(key, null)) } }.commit()
        val local = CookLocalSession.preferences(context)
        backup.edit().putString("syncState", local.getString(CookOfflineSyncWorker.SYNC_STATE, null)).commit()
        prefs.edit().putString(AppPrefs.KEY_BACKEND_URL, base).putString(AppPrefs.KEY_DEVICE_UUID, device)
            .putString(AppPrefs.KEY_CITY, city).commit()
        val operator = requireNotNull(api.addManual(device, UUID.randomUUID().toString(),
            "Background operator", listOf("operator"), binding))
        val cook = OfflineCookEmployee(base, device, binding.branchRef, binding.revision, employeeId,
            "cccccccc-cccc-4ccc-8ccc-cccccccccccc", "Background cook", "[\"cook\"]")
        val zone = ZoneId.of(requireNotNull(binding.timezone))
        val confirmed = Instant.now().atZone(zone).toLocalDate().atTime(11, 0).atZone(zone)
        fun ref(kind: String, id: String) = JSONObject().put("kind", kind).put("id", id)
        val shift = OfflineCookShift(base, device, binding.branchRef, binding.revision, shiftId,
            "dddddddd-dddd-4ddd-8ddd-dddddddddddd", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
            confirmed.toLocalDate().toString(), zone.id, confirmed.toOffsetDateTime().toString(),
            JSONArray().put(ref("provisional", employeeId)).toString(),
            JSONArray().put(ref("employee", operator.id)).toString(), ref("provisional", employeeId).toString())
        db.queue().enqueueEmployee(cook)
        db.queue().enqueueShift(shift)
        CookOfflineSyncWorker.enqueue(context, scope, city)
        val deadline = SystemClock.elapsedRealtime() + 30_000
        var attempted = false
        while (!attempted && SystemClock.elapsedRealtime() < deadline) {
            attempted = work.getWorkInfosByTag(CookOfflineSyncWorker::class.java.name).get()
                .any { it.runAttemptCount > 0 && it.state == WorkInfo.State.ENQUEUED }
            if (!attempted) SystemClock.sleep(100)
        }
        assertTrue("Offline worker must have tried and deferred while capability was disabled", attempted)
        assertNull(db.queue().employee(base, device, binding.branchRef, binding.revision, employeeId)?.canonicalId)
        PhotoApi.execute(Request.Builder().url("$base/fixture/offline-background").build()).use { response ->
            val result = JSONObject(requireNotNull(response.body).string())
            assertFalse(result.getBoolean("employeeMapped"))
            assertFalse(result.getBoolean("shiftMapped"))
        }
        val request = Request.Builder().url("$base/fixture/background-pid?pid=${Process.myPid()}")
            .post(ByteArray(0).toRequestBody()).build()
        PhotoApi.execute(request).use { assertEquals(200, it.code) }
    }

    @Test fun verify() {
        requireIsolated()
        val db = CookOfflineDatabase.get(context)
        val binding = CookWorkflowClient(base).initializeBinding(device)
        try {
            val employee = requireNotNull(db.queue().employee(base, device, binding.branchRef, binding.revision, employeeId))
            val shift = requireNotNull(db.queue().shift(base, device, binding.branchRef, binding.revision, shiftId))
            assertFalse(employee.canonicalId.isNullOrBlank())
            assertTrue(requireNotNull(shift.canonicalRevision) > 0)
            assertEquals(1, requireNotNull(CookWorkflowClient(base).roster(device)).employees.count { it.displayName == "Background cook" })
        } finally {
            WorkManager.getInstance(context).cancelAllWork().result.get()
            db.openHelper.writableDatabase.execSQL("DELETE FROM offline_shifts WHERE backendUrl=? AND deviceUuid=? AND branchRef=? AND bindingRevision=? AND provisionalId=?",
                arrayOf<Any>(base, device, binding.branchRef, binding.revision, shiftId))
            db.openHelper.writableDatabase.execSQL("DELETE FROM offline_employees WHERE backendUrl=? AND deviceUuid=? AND branchRef=? AND bindingRevision=? AND provisionalId=?",
                arrayOf<Any>(base, device, binding.branchRef, binding.revision, employeeId))
            val keys = listOf(AppPrefs.KEY_BACKEND_URL, AppPrefs.KEY_DEVICE_UUID, AppPrefs.KEY_CITY)
            prefs.edit().apply { keys.forEach { key -> backup.getString(key, null).let { if (it == null) remove(key) else putString(key, it) } } }.commit()
            CookLocalSession.preferences(context).edit().apply {
                backup.getString("syncState", null).let { if (it == null) remove(CookOfflineSyncWorker.SYNC_STATE) else putString(CookOfflineSyncWorker.SYNC_STATE, it) }
            }.commit()
            backup.edit().clear().commit()
        }
    }
}
