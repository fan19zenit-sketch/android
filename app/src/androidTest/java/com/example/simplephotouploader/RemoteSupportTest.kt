package com.example.simplephotouploader

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.work.WorkManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteSupportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After fun clear() {
        RemoteSupport.prefs(context).edit().clear().commit()
        WorkManager.getInstance(context).cancelAllWork().result.get()
    }

    @Test fun telemetryContainsOnlyTechnicalFields() {
        val data = RemoteSupport.diagnostics(context)
        assertEquals(14, data.length())
        assertFalse(data.has("photos"))
        assertFalse(data.has("city"))
        assertFalse(data.has("device"))
        assertTrue(data.getLong("page_size") in setOf(4096L, 16384L, 65536L))
    }

    @Test fun remoteSettingsExpireAndDoNotChangeRecords() {
        val dao = PilotDatabase.get(context).photoRecordDao()
        val before = dao.getAllHistory()
        RemoteSupport.applyResponse(context, JSONObject("""{"schema":1,"ttl_seconds":120,"settings":{"volume_capture":false,"retry_seconds":60,"uploads_paused":true}}"""))
        assertEquals(SupportSettings(false, 60, true), RemoteSupport.settings(context))
        RemoteSupport.prefs(context).edit().putLong("received", android.os.SystemClock.elapsedRealtime() - 121000).commit()
        assertEquals(SupportSettings(), RemoteSupport.settings(context))
        assertEquals(before, dao.getAllHistory())
    }

    @Test fun invalidConfigurationCannotEraseOrTriggerAnything() {
        val before = PilotDatabase.get(context).photoRecordDao().getAllHistory()
        assertTrue(runCatching {
            RemoteSupport.applyResponse(context, JSONObject("""{"schema":1,"ttl_seconds":120,"settings":{"take_photo":true}}"""))
        }.isFailure)
        assertTrue(runCatching {
            RemoteSupport.applyResponse(context, JSONObject("""{"schema":1,"ttl_seconds":120,"settings":{"volume_capture":"false"}}"""))
        }.isFailure)
        assertEquals(before, PilotDatabase.get(context).photoRecordDao().getAllHistory())
    }

    @Test fun supportScreenRendersWithoutServer() {
        context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE).edit()
            .putString(AppPrefs.KEY_BACKEND_URL, "http://127.0.0.1:9").commit()
        ActivityScenario.launch(SupportActivity::class.java).use {
            onView(withId(R.id.supportCheck)).check(matches(isDisplayed()))
            onView(withId(R.id.supportUpdate)).check(matches(isNotEnabled()))
            Thread.sleep(1500)
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            java.io.File(context.getExternalFilesDir(null), "support.png").outputStream().use { stream ->
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            }
            screenshot.recycle()
            val descriptor = instrumentation.uiAutomation.executeShellCommand(
                "cp ${java.io.File(context.getExternalFilesDir(null), "support.png").absolutePath} /data/local/tmp/photo-support.png")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream -> stream.readBytes() }
        }
    }

    @Test fun transportAcceptsShortJsonAndRejectsOversizedOrFailedResponse() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start()
            val client = okhttp3.OkHttpClient()
            val endpoint = server.url("/").toString().trimEnd('/')
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"schema\":1}"))
            assertEquals(1, RemoteSupport.post("heartbeat", JSONObject(), "test-token", client, endpoint).getInt("schema"))
            assertEquals("Bearer test-token", server.takeRequest().getHeader("Authorization"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("x".repeat(8193)))
            assertTrue(runCatching { RemoteSupport.post("heartbeat", JSONObject(), null, client, endpoint) }.isFailure)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503))
            assertTrue(runCatching { RemoteSupport.post("heartbeat", JSONObject(), null, client, endpoint) }.isFailure)
        }
    }
}
