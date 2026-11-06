package com.example.simplephotouploader

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.HorizontalScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalTime
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class CookCameraFlowTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun prepare() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, android.Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            automation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE).edit()
            .putString(AppPrefs.KEY_CITY, "Тест").remove(AppPrefs.KEY_DEVICE_UUID).commit()
        CookLocalSession.cache(context).clear()
        CookPendingShiftStore(CookLocalSession.preferences(context)).clear()
    }

    private fun await(timeoutMs: Long = 20_000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        assertTrue("Timed out waiting for camera workflow", predicate())
    }

    private fun visible(text: String): Boolean = try {
        onView(withText(text)).check(matches(isDisplayed())); true
    } catch (_: Throwable) { false }

    private fun cameraReady(): Boolean = try {
        onView(withId(R.id.captureButton)).check(matches(isEnabled())); true
    } catch (_: Throwable) { false }

    @Test fun secondCookIsConsumedByOneLocallySavedPhoto() {
        val dao = PilotDatabase.get(context).photoRecordDao()
        val existing = dao.getAllHistory().map { it.id }.toSet()
        ActivityScenario.launch(MainActivity::class.java).use {
            await { visible("Смена") }
            onView(withText("Смена")).perform(click())
            await { visible("Повар 1") }
            onView(withText("Повар 1")).perform(click())
            onView(withText("Повар 2")).perform(click())
            onView(withText("Оператор 1")).perform(scrollTo(), click())
            onView(withText("Подтвердить смену")).perform(scrollTo(), click())
            await { visible("Повар 2") && cameraReady() }
            onView(withText("Повар 2")).perform(click())
            onView(withId(R.id.captureButton)).perform(click())
            await { dao.getAllHistory().count { it.id !in existing } >= 1 && cameraReady() }
            onView(withId(R.id.captureButton)).perform(click())
            await { dao.getAllHistory().count { it.id !in existing } >= 2 }
            val captured = dao.getAllHistory().filter { it.id !in existing }.sortedBy { it.capturedAt }
            assertEquals("cook-2", JSONObject(captured[0].photoContext!!).getJSONObject("cookRef").getString("id"))
            assertEquals("cook-1", JSONObject(captured[1].photoContext!!).getJSONObject("cookRef").getString("id"))
        }
    }

    @Test fun changedDeviceCannotCaptureWithPreviousDeviceShift() {
        val dao = PilotDatabase.get(context).photoRecordDao()
        val existing = dao.getAllHistory().map { it.id }.toSet()
        ActivityScenario.launch(MainActivity::class.java).use {
            await { visible("Смена") }
            onView(withText("Смена")).perform(click())
            await { visible("Повар 1") }
            onView(withText("Повар 1")).perform(click())
            onView(withText("Оператор 1")).perform(scrollTo(), click())
            onView(withText("Подтвердить смену")).perform(scrollTo(), click())
            await { visible("Повар 1 · основной") && cameraReady() }
            context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE).edit()
                .putString(AppPrefs.KEY_DEVICE_UUID, "other-device").commit()
            onView(withId(R.id.captureButton)).perform(click())
            await { visible("Подтвердите состав смены перед съёмкой") }
            SystemClock.sleep(500)
            assertEquals(0, dao.getAllHistory().count { it.id !in existing })
        }
    }

    @Test fun openCameraPromptsForShiftWhenElevenArrives() {
        val local = CookLocalSession.preferences(context)
        local.edit().remove(CookOfflineSelectionCache.KEY).commit()
        CookDeviceRosterCache(local).save(CookBinding("preview", 1, "Europe/Moscow"), emptyList())
        val zone = ZoneId.of("Europe/Moscow")
        val morning = ZonedDateTime.now(zone).with(LocalTime.of(10, 59, 59)).toInstant()
        val launch = Intent(context, MainActivity::class.java).putExtra("test_shift_clock_ms", morning.toEpochMilli())
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            await { cameraReady() && visible("Смена") }
            assertTrue(!visible("Подтвердить смену"))
            scenario.onActivity { it.intent.putExtra("test_shift_clock_ms", morning.plusSeconds(1).toEpochMilli()) }
            await { visible("Подтвердить смену") }
        }
    }

    @Test fun ambiguousCookCannotBeSilentlyAssignedToPhoto() {
        val dao = PilotDatabase.get(context).photoRecordDao()
        val existing = dao.getAllHistory().map { it.id }.toSet()
        val zone = ZoneId.of("Europe/Moscow")
        val now = Instant.now().atZone(zone)
        val binding = CookBinding("preview", 1, zone.id)
        val people = listOf(CookEmployee("cook-1", "Повар 1", listOf("cook")),
            CookEmployee("cook-2", "ПОВАР 1", listOf("cook")),
            CookEmployee("operator-1", "Оператор 1", listOf("operator")))
        val local = CookLocalSession.preferences(context)
        local.edit().remove(CookOfflineSelectionCache.KEY).commit()
        assertTrue(CookDeviceRosterCache(local).save(binding, people))
        assertTrue(CookLocalSession.cache(context).save(CachedCookShift(binding,
            CookShift("same-name-shift", 1, binding.branchRef, now.toLocalDate().toString(), zone.id,
                now.minusSeconds(1).toOffsetDateTime().toString(), listOf("cook-1"), listOf("operator-1"), "cook-1"), people)))
        ActivityScenario.launch(MainActivity::class.java).use {
            await { visible("Повар 1 · уточнить имя · основной") && cameraReady() }
            onView(withId(R.id.captureButton)).perform(click())
            await { visible("У сотрудников одинаковые имена. Уточните имена в справочнике или выберите «Автор неизвестен».") }
            SystemClock.sleep(500)
            assertEquals(0, dao.getAllHistory().count { it.id !in existing })
            onView(withText("Понятно")).perform(click())
            onView(isAssignableFrom(HorizontalScrollView::class.java)).perform(swipeLeft())
            onView(withText("Автор неизвестен")).perform(click())
            onView(withId(R.id.captureButton)).perform(click())
            await { dao.getAllHistory().count { it.id !in existing } == 1 }
            val photo = dao.getAllHistory().single { it.id !in existing }
            val metadata = JSONObject(photo.photoContext!!)
            assertEquals("explicit_unknown", metadata.getString("selectionSource"))
            assertTrue(metadata.isNull("cookRef"))
            assertTrue(metadata.isNull("cookNameSnapshot"))
        }
    }
}
