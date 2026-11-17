package com.example.simplephotouploader

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryDiagnosticsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Suppress("DEPRECATION")
    @Test fun sharedFileContainsTechnicalStateWithoutPrivateValues() {
        assumeTrue(BuildConfig.FLAVOR == "clean")
        val prefs = RemoteSupport.prefs(context)
        val previous = prefs.getString("last_failure", null)
        val appPrefs = context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        val previousCity = appPrefs.getString(AppPrefs.KEY_CITY, null)
        val previousDevice = appPrefs.getString(AppPrefs.KEY_DEVICE_UUID, null)
        try {
            val device = "c9489d50-52a8-4bde-b3bc-8578b04cf513"
            appPrefs.edit().putString(AppPrefs.KEY_CITY, "Тестовый филиал").putString(AppPrefs.KEY_DEVICE_UUID, device).commit()
            prefs.edit().putString("last_failure", """{"error":"private-token-name","trace":"secret","at":5}""").commit()
            DiagnosticJournal.record(context, "upload", "response", "primary", "1234abcd", http = 422, error = "http_422")
            val id = DiagnosticJournal.events(context).last().getString("id")
            DiagnosticJournal.acknowledge(context, setOf(id))
            val report = DiagnosticJournal.export(context)
            val intent = DiagnosticShare.intent(context, report)
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("application/json", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val payload = JSONObject(text)
            assertEquals(3, payload.getInt("schema"))
            assertTrue(payload.has("device_and_queue"))
            assertTrue(payload.has("cook_workflow"))
            assertTrue(payload.has("workers"))
            assertEquals("Тестовый филиал", payload.getJSONObject("location").getString("branch"))
            assertEquals(16, payload.getJSONObject("location").getString("device_fingerprint").length)
            assertFalse(text.contains(device))
            assertTrue(text.contains(id))
            assertFalse(text.contains("private-token-name"))
            assertFalse(text.contains("secret"))
            assertFalse(text.contains("photoPath"))
        } finally {
            prefs.edit().putString("last_failure", previous).commit()
            appPrefs.edit().putString(AppPrefs.KEY_CITY, previousCity).putString(AppPrefs.KEY_DEVICE_UUID, previousDevice).commit()
        }
    }

    @Test fun availableUpdateRemainsAccessibleSeparately() {
        assumeTrue(BuildConfig.FLAVOR == "clean")
        val prefs = RemoteSupport.prefs(context)
        val previousUpdate = prefs.getString("update", null)
        val previousSync = prefs.getLong("last_sync", 0)
        val update = JSONObject().put("package", context.packageName)
            .put("version_code", BuildConfig.VERSION_CODE + 1).put("version_name", "next")
            .put("min_sdk", 24).put("sha256", "a".repeat(64))
            .put("url", "https://app.sushigohub.ru/photo-downloads/update.apk")
        try {
            prefs.edit().putString("update", update.toString()).putLong("last_sync", System.currentTimeMillis()).commit()
            ActivityScenario.launch(HistoryActivity::class.java).use {
                onView(withId(R.id.btnSupport)).check(matches(isDisplayed()))
                onView(withId(R.id.btnAvailableUpdate)).check(matches(isDisplayed()))
            }
        } finally {
            prefs.edit().putString("update", previousUpdate).putLong("last_sync", previousSync).commit()
        }
    }
}
