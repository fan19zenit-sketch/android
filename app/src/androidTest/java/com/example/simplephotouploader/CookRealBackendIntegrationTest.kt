package com.example.simplephotouploader

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.io.File
import java.security.MessageDigest
import java.io.IOException

/** Opt-in only: requires the isolated loopback harness and a blocked external network. */
@RunWith(AndroidJUnit4::class)
class CookRealBackendIntegrationTest {
    @Test fun twoDevicesKeepShiftsAndPhotoContextsSeparate() = runBlocking {
        assumeTrue(BuildConfig.BUILD_TYPE == "localtest" &&
            InstrumentationRegistry.getArguments().getString("isolatedCookBackend") == "1")
        assertEquals("com.example.photovchistotu.localtest", BuildConfig.APPLICATION_ID)
        try {
            PhotoApi.execute(Request.Builder().url("https://example.invalid/cook-v2/capabilities").build())
            fail("The isolated build must refuse external endpoints")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("only allows the isolated backend"))
        }
        val base = "http://127.0.0.1:18433"
        val api = CookWorkflowClient(base)
        val a = "test-device-a"
        val b = "test-device-b"
        val capabilities = requireNotNull(api.capabilities())
        assertTrue(capabilities.roster && capabilities.shift)
        assertFalse("Production capability remains disabled", capabilities.photoContext)
        val binding = api.initializeBinding(a)
        assertEquals(binding, api.initializeBinding(b))
        val cookA = requireNotNull(api.addManual(a, UUID.randomUUID().toString(), "Тестовый повар А", listOf("cook"), binding))
        val cookB = requireNotNull(api.addManual(a, UUID.randomUUID().toString(), "Тестовый повар Б", listOf("cook"), binding))
        val operator = requireNotNull(api.addManual(a, UUID.randomUUID().toString(), "Тестовый оператор", listOf("operator"), binding))
        assertTrue(requireNotNull(api.roster(b)).employees.any { it.id == cookA.id })
        val now = Instant.now()
        val zone = ZoneId.of(requireNotNull(binding.timezone))
        val confirmed = now.minusSeconds(1).atZone(zone)
        fun shift(main: String) = CookShift(UUID.randomUUID().toString(), 0, binding.branchRef,
            confirmed.toLocalDate().toString(), zone.id, confirmed.toOffsetDateTime().toString(),
            listOf(cookA.id, cookB.id), listOf(operator.id), main)
        val shiftA = shift(cookA.id)
        val shiftB = shift(cookB.id)
        val requestA = UUID.randomUUID().toString()
        val confirmedA = requireNotNull(api.confirmShift(a, requestA, shiftA, binding))
        assertEquals(confirmedA, api.confirmShift(a, requestA, shiftA, binding))
        val confirmedB = requireNotNull(api.confirmShift(b, UUID.randomUUID().toString(), shiftB, binding))
        assertEquals(listOf(shiftA.id), requireNotNull(api.shifts(a, shiftA.operationalDate)).map { it.id })
        assertEquals(listOf(shiftB.id), requireNotNull(api.shifts(b, shiftB.operationalDate)).map { it.id })
        val photoA = CookPhotoContextFactory.create(binding, binding, shiftA.copy(revision = confirmedA.second),
            cookA, "main_default", now)
        val photoB = CookPhotoContextFactory.create(binding, binding, shiftB.copy(revision = confirmedB.second),
            cookB, "main_default", now)
        assertEquals("resolved", api.syncPhotoContext(a, "test-photo-a", photoA))
        assertEquals("resolved", api.syncPhotoContext(a, "test-photo-a", photoA))
        assertEquals("resolved", api.syncPhotoContext(b, "test-photo-b", photoB))
        try {
            api.syncPhotoContext(b, "test-photo-b", photoA)
            fail("Another device's shift must not be accepted")
        } catch (error: CookApiHttpException) {
            assertEquals(409, error.status)
        }
        listOf("a" to cookA.id, "b" to cookB.id).forEach { (suffix, employeeId) ->
            val request = Request.Builder().url("$base/fixture/photo/$suffix").build()
            PhotoApi.execute(request).use { response ->
                assertEquals(200, response.code)
                val result = JSONObject(requireNotNull(response.body).string())
                assertEquals("resolved", result.getString("resolution"))
                assertEquals(employeeId, result.getJSONObject("snapshot").getJSONObject("cookRef").getString("id"))
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("cook-api-test-", ".jpg", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) } }
            finally { bitmap.recycle() }
            val bytes = file.readBytes()
            val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val uploadId = "test-upload-${UUID.randomUUID()}"
            fun upload(): Pair<Int, JSONObject> {
                val request = Request.Builder().url("$base/cook-v2/upload")
                    .post(PhotoUploadMultipart.build(a, uploadId, file, photoA)).build()
                return PhotoApi.execute(request).use { response ->
                    response.code to JSONObject(requireNotNull(response.body).string())
                }
            }
            val (firstCode, first) = upload()
            assertEquals(202, firstCode)
            assertEquals("resolved", PhotoContextAcknowledgement.resolution(first, uploadId))
            val jobId = first.getJSONObject("job").getString("job_id")
            val (secondCode, second) = upload()
            assertEquals(202, secondCode)
            assertTrue(second.getBoolean("deduplicated"))
            assertEquals(jobId, second.getJSONObject("job").getString("job_id"))
            val inspection = Request.Builder().url("$base/fixture/upload/$uploadId").build()
            PhotoApi.execute(inspection).use { response ->
                assertEquals(200, response.code)
                val saved = JSONObject(requireNotNull(response.body).string())
                assertEquals(jobId, saved.getString("jobId"))
                assertEquals("uploaded_to_server", saved.getString("status"))
                assertEquals(sha256, saved.getString("sha256"))
                assertEquals(1, saved.getInt("queued"))
            }
            file.writeBytes(bytes + byteArrayOf(0))
            assertEquals(409, upload().first)
            file.writeBytes(bytes)

            val work = WorkManager.getInstance(context)
            work.cancelAllWork().result.get()
            val prefs = context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
            val oldBackend = prefs.getString(AppPrefs.KEY_BACKEND_URL, null)
            val oldDevice = prefs.getString(AppPrefs.KEY_DEVICE_UUID, null)
            prefs.edit().putString(AppPrefs.KEY_BACKEND_URL, base).putString(AppPrefs.KEY_DEVICE_UUID, a).commit()
            val dao = PilotDatabase.get(context).photoRecordDao()
            val recordId = PhotoHistoryStore.addQueued(context, file.absolutePath, now.toString(), photoA, a, base)
            try {
                TestListenableWorkerBuilder<PhotoUploadWorker>(context).build().doWork()
                val blocked = requireNotNull(dao.getByPhotoPath(file.absolutePath))
                assertNull(blocked.jobId)
                assertEquals("retrying", blocked.status)
                assertTrue(blocked.queuedInUploadQueue)
                val before = Request.Builder().url("$base/fixture/upload/$recordId").build()
                PhotoApi.execute(before).use { assertEquals(404, it.code) }

                val enable = Request.Builder().url("$base/fixture/enable-capability")
                    .post(ByteArray(0).toRequestBody()).build()
                PhotoApi.execute(enable).use { response ->
                    assertTrue(JSONObject(requireNotNull(response.body).string()).getBoolean("enabledForFixtureOnly"))
                }
                assertTrue(requireNotNull(api.capabilities()).photoContext)
                TestListenableWorkerBuilder<PhotoUploadWorker>(context).build().doWork()
                val uploaded = requireNotNull(dao.getByPhotoPath(file.absolutePath))
                assertFalse(uploaded.jobId.isNullOrBlank())
                assertFalse(uploaded.queuedInUploadQueue)
                assertEquals("resolved", uploaded.photoContextAck)
                val inspection = Request.Builder().url("$base/fixture/upload/$recordId").build()
                PhotoApi.execute(inspection).use { response ->
                    assertEquals(200, response.code)
                    val saved = JSONObject(requireNotNull(response.body).string())
                    assertEquals(uploaded.jobId, saved.getString("jobId"))
                    assertEquals(sha256, saved.getString("sha256"))
                    assertEquals("uploaded_to_server", saved.getString("status"))
                    assertEquals(2, saved.getInt("queued"))
                }
                TestListenableWorkerBuilder<PhotoUploadWorker>(context).build().doWork()
                PhotoApi.execute(inspection).use { response ->
                    assertEquals(2, JSONObject(requireNotNull(response.body).string()).getInt("queued"))
                }
            } finally {
                work.cancelAllWork().result.get()
                PhotoHistoryStore.removeByPhotoPath(context, file.absolutePath)
                prefs.edit().apply {
                    if (oldBackend == null) remove(AppPrefs.KEY_BACKEND_URL) else putString(AppPrefs.KEY_BACKEND_URL, oldBackend)
                    if (oldDevice == null) remove(AppPrefs.KEY_DEVICE_UUID) else putString(AppPrefs.KEY_DEVICE_UUID, oldDevice)
                }.commit()
            }
        } finally {
            file.delete()
        }
    }
}
