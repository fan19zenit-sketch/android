package com.example.simplephotouploader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RadioButton
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Bot, API and both devices use a loopback-only synthetic fixture; no MAX or branch is contacted. */
@RunWith(AndroidJUnit4::class)
class CookBranchShiftIntegrationTest {
    private fun waitFor(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (true) {
            try { onView(withText(text)).check(matches(isDisplayed())); return }
            catch (error: androidx.test.espresso.NoMatchingViewException) {
                if (SystemClock.elapsedRealtime() >= deadline) throw error
                SystemClock.sleep(100)
            }
        }
    }

    @Test fun botTemplateIsReadByBothDevicesWithoutSharingTheirMainCook() {
        assumeTrue(BuildConfig.BUILD_TYPE == "localtest" &&
            InstrumentationRegistry.getArguments().getString("isolatedCookBackend") == "1")
        val base = "http://127.0.0.1:18433"
        val api = CookWorkflowClient(base)
        val a = "test-device-a"
        val b = "test-device-b"
        val binding = api.initializeBinding(a)
        assertEquals(binding, api.initializeBinding(b))
        val cookA = requireNotNull(api.addManual(a, UUID.randomUUID().toString(), "Бот Тест Повар А", listOf("cook"), binding))
        val cookB = requireNotNull(api.addManual(a, UUID.randomUUID().toString(), "Бот Тест Повар Б", listOf("cook"), binding))
        val operator = requireNotNull(api.addManual(a, UUID.randomUUID().toString(), "Бот Тест Оператор", listOf("operator"), binding))
        val fixture = Request.Builder().url("$base/fixture/branch-shift-from-bot?cook_a=${cookA.id}&cook_b=${cookB.id}&operator=${operator.id}")
            .post(ByteArray(0).toRequestBody()).build()
        PhotoApi.execute(fixture).use { response -> assertEquals(200, response.code) }
        assertTrue(requireNotNull(api.capabilities(a)).branchShiftTemplate)
        val now = Instant.now()
        val zone = ZoneId.of(requireNotNull(binding.timezone))
        val day = now.atZone(zone).toLocalDate().toString()
        val template = requireNotNull(api.branchShift(a, day, binding))
        assertEquals(template, api.branchShift(b, day, binding))
        assertEquals(listOf(cookA.id, cookB.id), template.cooks)
        assertEquals(listOf(operator.id), template.operators)
        val confirmedAt = now.minusSeconds(1).atZone(zone).toOffsetDateTime().toString()
        for ((device, employee, upload) in listOf(Triple(a, cookA, "test-photo-a"), Triple(b, cookB, "test-photo-b"))) {
            val shift = CookShift(UUID.randomUUID().toString(), 0, binding.branchRef, day, zone.id,
                confirmedAt, template.cooks, template.operators, employee.id, template.assistants)
            val saved = requireNotNull(api.confirmShift(device, UUID.randomUUID().toString(), shift, binding))
            val photo = CookPhotoContextFactory.create(binding, binding, shift.copy(revision = saved.second),
                employee, "main_default", now)
            assertEquals("resolved", api.syncPhotoContext(device, upload, photo))
        }
    }

    @Test fun botTemplateIsShownAndConfirmedInRealShiftScreen() {
        assumeTrue(BuildConfig.BUILD_TYPE == "localtest" &&
            InstrumentationRegistry.getArguments().getString("isolatedCookBackend") == "1")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val base = "http://127.0.0.1:18433"
        val device = "test-device-a"
        val api = CookWorkflowClient(base)
        val binding = api.initializeBinding(device)
        val suffix = UUID.randomUUID().toString().take(8)
        val a = requireNotNull(api.addManual(device, UUID.randomUUID().toString(), "Повар Альфа $suffix", listOf("cook"), binding))
        val b = requireNotNull(api.addManual(device, UUID.randomUUID().toString(), "Повар Бета $suffix", listOf("cook"), binding))
        val operator = requireNotNull(api.addManual(device, UUID.randomUUID().toString(), "Оператор Тест $suffix", listOf("operator"), binding))
        fun post(path: String) = PhotoApi.execute(Request.Builder().url("$base$path").post(ByteArray(0).toRequestBody()).build())
            .use { response -> assertEquals(200, response.code) }
        post("/fixture/enable-capability")
        post("/fixture/branch-shift-from-bot?cook_a=${a.id}&cook_b=${b.id}&operator=${operator.id}")
        val prefs = context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        val keys = listOf(AppPrefs.KEY_BACKEND_URL, AppPrefs.KEY_DEVICE_UUID, AppPrefs.KEY_CITY, AppPrefs.KEY_DEVICE_MODE)
        val previous = keys.associateWith { prefs.getString(it, null) }
        try {
            prefs.edit().putString(AppPrefs.KEY_BACKEND_URL, base).putString(AppPrefs.KEY_DEVICE_UUID, device)
                .putString(AppPrefs.KEY_CITY, "test-branch").putString(AppPrefs.KEY_DEVICE_MODE, AppPrefs.DEVICE_MODE_MAIN).commit()
            CookLocalSession.cache(context).clear()
            CookPendingShiftStore(CookLocalSession.preferences(context)).clear()
            CookLocalSession.preferences(context).edit().remove(CookOfflineSelectionCache.KEY).commit()
            ActivityScenario.launch<CookShiftActivity>(Intent(context, CookShiftActivity::class.java)).use {
                waitFor("Состав из бота")
                onView(withText("Начать смену")).perform(click())
                waitFor("Кто основной повар?")
                onView(allOf(withText(b.displayName), isAssignableFrom(RadioButton::class.java))).perform(scrollTo(), click())
                onView(withText("Далее")).perform(scrollTo(), click())
                waitFor("Проверьте состав смены")
                onView(withText("Начать смену")).perform(scrollTo(), click())
                val deadline = SystemClock.elapsedRealtime() + 15_000
                var saved = CookLocalSession.cache(context).load()
                while (saved == null && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(100); saved = CookLocalSession.cache(context).load()
                }
                assertNotNull(saved)
                assertEquals(b.id, saved!!.shift.mainCookId)
                assertEquals(setOf(a.id, b.id), saved!!.shift.cooks.toSet())
                assertEquals(listOf(operator.id), saved!!.shift.operators)
            }
        } finally {
            CookLocalSession.cache(context).clear()
            CookPendingShiftStore(CookLocalSession.preferences(context)).clear()
            prefs.edit().apply { previous.forEach { (key, value) ->
                if (value == null) remove(key) else putString(key, value)
            } }.commit()
        }
    }
}
