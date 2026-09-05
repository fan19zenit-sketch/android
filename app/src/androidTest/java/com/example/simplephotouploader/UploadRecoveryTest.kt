package com.example.simplephotouploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class UploadRecoveryTest {
    @Test fun lostAcknowledgementReusesIdentityAndKeepsOriginal() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val work = WorkManager.getInstance(context)
        work.cancelAllWork().result.get()
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "POST") {
                    requests.add(request.body.readUtf8() to request.getHeader("X-App-Version"))
                    if (requests.size == 1) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    return MockResponse().setResponseCode(202).setBody("""{"job":{"job_id":"recovery-job"}}""")
                }
                return MockResponse().setBody("""{"job_id":"recovery-job","status":"sent","message_id":"test-message"}""")
            }
        }
        server.start()
        val prefs = context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(AppPrefs.KEY_BACKEND_URL, server.url("/").toString())
            .putString(AppPrefs.KEY_DEVICE_UUID, "offline-recovery-device").commit()
        val file = File(context.cacheDir, "recovery-photo.jpg")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        val dao = PilotDatabase.get(context).photoRecordDao()
        val stableId = PhotoHistoryStore.addQueued(context, file.absolutePath)
        try {
            repeat(3) {
                if (dao.getByPhotoPath(file.absolutePath)?.jobId == null) {
                    TestListenableWorkerBuilder<PhotoUploadWorker>(context).build().doWork()
                }
            }
            assertEquals("recovery-job", dao.getByPhotoPath(file.absolutePath)?.jobId)
            assertTrue(requests.size >= 2)
            requests.forEach { (body, version) ->
                assertTrue(body.contains("name=\"clientUploadId\""))
                assertTrue(body.contains(stableId))
                assertEquals(BuildConfig.VERSION_NAME, version)
            }
            val acceptedCount = requests.size
            TestListenableWorkerBuilder<PhotoUploadWorker>(context).build().doWork()
            assertEquals(acceptedCount, requests.size)
            assertTrue(file.exists())
            assertTrue(dao.getQueuedPaths().isEmpty())
        } finally {
            work.cancelAllWork().result.get()
            dao.deleteByPhotoPath(file.absolutePath)
            file.delete()
            prefs.edit().putString(AppPrefs.KEY_BACKEND_URL, "http://127.0.0.1:9").commit()
            server.shutdown()
        }
    }
}
