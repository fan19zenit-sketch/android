package com.example.simplephotouploader

import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
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
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Two phases are orchestrated by the loopback-only emulator script. */
@RunWith(AndroidJUnit4::class)
class CookBackgroundIntegrationTest {
    private val base = "http://127.0.0.1:18433"
    private val device = "test-device-a"
    private val recordId = "99999999-9999-4999-8999-999999999999"
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file get() = File(context.filesDir, "local-background-test.jpg")

    private fun requireIsolated() {
        assumeTrue(BuildConfig.BUILD_TYPE == "localtest" &&
            InstrumentationRegistry.getArguments().getString("isolatedCookBackend") == "1")
        assertEquals("com.example.photovchistotu.localtest", BuildConfig.APPLICATION_ID)
    }

    @Test fun seed() {
        requireIsolated()
        val work = WorkManager.getInstance(context)
        work.cancelAllWork().result.get()
        val dao = PilotDatabase.get(context).photoRecordDao()
        PhotoHistoryStore.removeByPhotoPath(context, file.absolutePath)
        val prefs = context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(AppPrefs.KEY_BACKEND_URL, base).putString(AppPrefs.KEY_DEVICE_UUID, device).commit()
        val api = CookWorkflowClient(base)
        assertFalse(requireNotNull(api.capabilities()).photoContext)
        val binding = api.initializeBinding(device)
        val cook = requireNotNull(api.addManual(device, UUID.randomUUID().toString(), "Тестовый повар", listOf("cook"), binding))
        val operator = requireNotNull(api.addManual(device, UUID.randomUUID().toString(), "Тестовый оператор", listOf("operator"), binding))
        val now = Instant.now()
        val zone = ZoneId.of(requireNotNull(binding.timezone))
        val confirmed = now.minusSeconds(1).atZone(zone)
        val shift = CookShift(UUID.randomUUID().toString(), 0, binding.branchRef, confirmed.toLocalDate().toString(),
            zone.id, confirmed.toOffsetDateTime().toString(), listOf(cook.id), listOf(operator.id), cook.id)
        val saved = requireNotNull(api.confirmShift(device, UUID.randomUUID().toString(), shift, binding))
        val snapshot = CookPhotoContextFactory.create(binding, binding, shift.copy(revision = saved.second), cook, "main_default", now)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) } }
        finally { bitmap.recycle() }
        dao.insert(PhotoRecordEntity(recordId, null, null, false, file.absolutePath, null, now.toString(), null,
            "queued", null, true, photoContext = snapshot, captureDeviceUuid = device, captureBackendUrl = base))
        PhotoUploadWorker.enqueue(context).result.get()
        val deadline = SystemClock.elapsedRealtime() + 25_000
        while (dao.getByPhotoPath(file.absolutePath)?.status != "retrying" && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
        }
        val queued = requireNotNull(dao.getByPhotoPath(file.absolutePath))
        assertEquals("retrying", queued.status)
        assertTrue(queued.queuedInUploadQueue)
        assertNull(queued.jobId)
        PhotoApi.execute(Request.Builder().url("$base/fixture/upload/$recordId").build()).use {
            assertEquals(404, it.code)
        }
        val request = Request.Builder().url("$base/fixture/background-pid?pid=${Process.myPid()}")
            .post(ByteArray(0).toRequestBody()).build()
        PhotoApi.execute(request).use { assertEquals(200, it.code) }
    }

    @Test fun verify() {
        requireIsolated()
        val dao = PilotDatabase.get(context).photoRecordDao()
        try {
            val row = requireNotNull(dao.getByPhotoPath(file.absolutePath))
            assertEquals(recordId, row.id)
            assertFalse(row.jobId.isNullOrBlank())
            assertFalse(row.queuedInUploadQueue)
            assertEquals("resolved", row.photoContextAck)
            assertTrue(file.exists())
            val request = Request.Builder().url("$base/fixture/upload/$recordId").build()
            PhotoApi.execute(request).use { response ->
                assertEquals(200, response.code)
                val saved = JSONObject(requireNotNull(response.body).string())
                assertEquals(row.jobId, saved.getString("jobId"))
                assertEquals("uploaded_to_server", saved.getString("status"))
                assertEquals(1, saved.getInt("queued"))
            }
        } finally {
            WorkManager.getInstance(context).cancelAllWork().result.get()
            PhotoHistoryStore.removeByPhotoPath(context, file.absolutePath)
            file.delete()
        }
    }
}
