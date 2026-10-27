package com.example.simplephotouploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DiagnosticsRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun startingDiagnosticsNeverWaitsForAnOngoingNetworkSync() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val holder = Thread {
            synchronized(RemoteSupport) { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        }.apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val task = java.util.concurrent.FutureTask { RemoteSupport.checkImmediately(context) }
        Thread(task).start()
        try { task.get(500, TimeUnit.MILLISECONDS) }
        finally { release.countDown(); holder.join(2000) }
    }

    @Test fun journalIsBoundedPrivateAndAcknowledgesOnlyKnownIds() {
        DiagnosticJournal.acknowledge(context, DiagnosticJournal.events(context).map { it.getString("id") }.toSet())
        repeat(245) { DiagnosticJournal.record(context, "upload", "response", "primary", "01234567", it.toLong()) }
        assertEquals(240, DiagnosticJournal.events(context).size)
        DiagnosticJournal.record(context, "upload", "response", "https://secret/?token=private", "01234567")
        val ids = DiagnosticJournal.events(context).take(12).map { it.getString("id") }.toSet()
        DiagnosticJournal.acknowledge(context, ids + UUID.randomUUID().toString())
        assertEquals(228, DiagnosticJournal.events(context).size)
        val report = DiagnosticJournal.export(context).readText()
        listOf("token", "installation", "device_uuid", "photoPath", "Authorization", "private").forEach {
            assertFalse(it, report.contains(it))
        }
        assertTrue(DiagnosticJournal.export(context).length() < 196608)
    }

    @Test fun dnsAndResponseTimeoutAreRecordedWithoutRawHostOrException() {
        val client = PhotoApi.client.newBuilder().dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> = throw UnknownHostException("private-token-name")
        }).build()
        assertTrue(runCatching { PhotoApi.execute(Request.Builder().url("https://private.invalid/status").build(), client) }.isFailure)
        assertTrue(DiagnosticJournal.events(context).any { it.optString("error") == "dns_failed" })
        assertFalse(DiagnosticJournal.export(context).readText().contains("private-token-name"))
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(2, TimeUnit.SECONDS))
            val quick = PhotoApi.client.newBuilder().readTimeout(200, TimeUnit.MILLISECONDS).build()
            val result = runCatching { PhotoApi.execute(Request.Builder().url(server.url("/status")).build(), quick) }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("response_timeout"))
        }
    }

    @Test fun httpsFailoverReplaysTheSameUploadAndDoesNotRetryClientErrors() {
        MockWebServer().use { server ->
            server.start()
            val addressed = mutableListOf<String>()
            val transport = PhotoApi.client.newBuilder().addInterceptor { chain ->
                addressed.add(chain.request().url.host)
                val local = chain.request().newBuilder().url(server.url("/upload-v2")).build()
                chain.proceed(local)
            }.build()
            server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(202).setBody("""{"job":{"job_id":"test"}}"""))
            val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("clientUploadId", "stable-test-only").build()
            val request = Request.Builder().url("${AppPrefs.DEFAULT_BACKEND_URL}/upload-v2").post(body).build()
            PhotoApi.execute(request, transport).use { assertEquals(202, it.code) }
            assertEquals(setOf("194-55-235-241.sslip.io", "app.sushigohub.ru"), addressed.toSet())
            val first = server.takeRequest(5, TimeUnit.SECONDS)!!
            val second = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals(first.body.readUtf8(), second.body.readUtf8())
            assertEquals(BuildConfig.VERSION_NAME, second.getHeader("X-App-Version"))
            addressed.clear()
            server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
            PhotoApi.execute(request, transport).use { assertEquals(429, it.code) }
            assertEquals(1, addressed.size)
        }
    }

    @Test fun manualUploadDoesNotDependOnWorkerAndConcurrentAttemptsDoNotDuplicate() = runBlocking {
        val work = WorkManager.getInstance(context)
        work.cancelAllWork().result.get()
        val prefs = context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(AppPrefs.KEY_BACKEND_URL, null)
        val previousDevice = prefs.getString(AppPrefs.KEY_DEVICE_UUID, null)
        val dao = PilotDatabase.get(context).photoRecordDao()
        val file = File(context.cacheDir, "manual-recovery.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        MockWebServer().use { server ->
            server.start()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = if (request.method == "POST")
                    MockResponse().setResponseCode(202).setBody("""{"job":{"job_id":"manual-job"}}""")
                else MockResponse().setBody("""{"job_id":"manual-job","status":"sent","message_id":"test-only"}""")
            }
            prefs.edit().putString(AppPrefs.KEY_BACKEND_URL, server.url("/").toString())
                .putString(AppPrefs.KEY_DEVICE_UUID, "manual-test-only").commit()
            val stable = PhotoHistoryStore.addQueued(context, file.absolutePath)
            try {
                listOf(async(Dispatchers.IO) { UploadCoordinator.process(context, file.absolutePath) },
                    async(Dispatchers.IO) { UploadCoordinator.process(context, file.absolutePath) }).awaitAll()
                assertEquals("manual-job", dao.getByPhotoPath(file.absolutePath)?.jobId)
                val upload = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("POST", upload.method)
                assertTrue(upload.body.readUtf8().contains(stable))
                assertTrue(file.exists())
                assertFalse(dao.getByPhotoPath(file.absolutePath)!!.queuedInUploadQueue)
                val next = server.takeRequest(500, TimeUnit.MILLISECONDS)
                assertTrue(next == null || next.method == "GET")
            } finally {
                work.cancelAllWork().result.get()
                dao.deleteByPhotoPath(file.absolutePath)
                file.delete()
                prefs.edit().putString(AppPrefs.KEY_BACKEND_URL, previous).putString(AppPrefs.KEY_DEVICE_UUID, previousDevice).commit()
            }
        }
    }
}
