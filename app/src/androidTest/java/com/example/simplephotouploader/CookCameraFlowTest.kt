package com.example.simplephotouploader

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
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.`is`
import java.time.LocalTime
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class CookCameraFlowTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun role(employeeId: String, role: String) {
        onView(withTagValue(`is`("role:$employeeId:$role" as Any))).perform(scrollTo(), click())
    }

    @Before fun prepare() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, android.Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            automation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE).edit()
            .putString(AppPrefs.KEY_CITY, "Тест").remove(AppPrefs.KEY_DEVICE_UUID).remove(AppPrefs.KEY_DEVICE_MODE).commit()
        CookLocalSession.cache(context).clear()
        CookPendingShiftStore(CookLocalSession.preferences(context)).clear()
        CookLocalSession.preferences(context).edit().remove("current_photo_author").commit()
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

    private fun selectedAuthor(name: String): Boolean = runCatching {
        onView(withText(name)).check(matches(androidx.test.espresso.matcher.ViewMatchers.withParent(
            androidx.test.espresso.matcher.ViewMatchers.isSelected())))
        true
    }.getOrDefault(false)

    private fun cameraReady(): Boolean = try {
        onView(withId(R.id.captureButton)).check(matches(isEnabled())); true
    } catch (_: Throwable) { false }

    private fun choose(text: String) = onView(allOf(withText(text), isAssignableFrom(RadioButton::class.java)))
        .perform(scrollTo(), click())

    private fun next() = onView(withText("Далее")).perform(scrollTo(), click())

    private fun chooseShift(twoCooks: Boolean) {
        await { visible("Начать смену") }
        onView(withText("Начать смену")).perform(click())
        choose(if (twoCooks) "3" else "2"); next()
        choose("1"); next()
        choose(if (twoCooks) "2" else "1"); next()
        choose("0"); next()
        choose("Оператор 1"); next()
        choose("Повар 1"); next()
        if (twoCooks) {
            choose("Повар 2"); next()
            choose("Повар 1"); next()
        }
        onView(withText("Начать смену")).perform(scrollTo(), click())
    }

    @Test fun secondCookIsConsumedByOneLocallySavedPhoto() {
        val dao = PilotDatabase.get(context).photoRecordDao()
        val existing = dao.getAllHistory().map { it.id }.toSet()
        ActivityScenario.launch(MainActivity::class.java).use {
            await { visible("Смена") }
            onView(withText("Смена")).perform(click())
            chooseShift(twoCooks = true)
            await { visible("Повар 2") && cameraReady() }
            onView(withText("Автор неизвестен")).check(doesNotExist())
            onView(withText("Повар 2")).check(matches(withTagValue(`is`("cook-2" as Any))))
            onView(withText("Повар 2")).perform(click())
            assertTrue(selectedAuthor("Повар 2"))
            onView(withId(R.id.captureButton)).perform(click())
            await { dao.getAllHistory().count { it.id !in existing } >= 1 && cameraReady() }
            onView(withId(R.id.captureButton)).perform(click())
            await { dao.getAllHistory().count { it.id !in existing } >= 2 }
            val captured = dao.getAllHistory().filter { it.id !in existing }.sortedBy { it.capturedAt }
            assertEquals("cook-2", JSONObject(captured[0].photoContext!!).getJSONObject("cookRef").getString("id"))
            assertEquals("cook-1", JSONObject(captured[1].photoContext!!).getJSONObject("cookRef").getString("id"))
        }
    }

    @Test fun changingCurrentCookPersistsAcrossPhotosAndActivityRecreation() {
        val dao = PilotDatabase.get(context).photoRecordDao()
        val existing = dao.getAllHistory().map { it.id }.toSet()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { visible("Смена") }
            onView(withText("Смена")).perform(click())
            chooseShift(twoCooks = true)
            await { visible("Смена") && cameraReady() }
            onView(withText("Смена")).perform(click())
            await { visible("Основной автор на телефоне") }
            onView(withText("Основной автор на телефоне")).perform(click())
            onView(withText("Повар 2")).inRoot(isDialog()).perform(click())
            await { selectedAuthor("Повар 2") }
            onView(withId(R.id.captureButton)).perform(click())
            await { dao.getAllHistory().count { it.id !in existing } >= 1 && cameraReady() }
            scenario.recreate()
            await { selectedAuthor("Повар 2") && cameraReady() }
            onView(withId(R.id.captureButton)).perform(click())
            await { dao.getAllHistory().count { it.id !in existing } >= 2 }
            val photos = dao.getAllHistory().filter { it.id !in existing }.sortedBy { it.capturedAt }
            assertEquals("cook-2", JSONObject(photos[0].photoContext!!).getJSONObject("cookRef").getString("id"))
            assertEquals("cook-2", JSONObject(photos[1].photoContext!!).getJSONObject("cookRef").getString("id"))
        }
    }

    @Test fun pizzaCookRemainsAuthorAfterMultiplePhotosAndRecreation() {
        val prefs = context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(AppPrefs.KEY_DEVICE_MODE, AppPrefs.DEVICE_MODE_PIZZA).commit()
        val zone = ZoneId.of("Europe/Moscow")
        val now = Instant.now().atZone(zone)
        val binding = CookBinding("preview", 1, zone.id)
        val people = listOf(CookEmployee("cook-1", "Повар 1", listOf("cook")),
            CookEmployee("cook-2", "Повар 2", listOf("cook")))
        val shift = CookShift("pizza-test", 1, "preview", now.toLocalDate().toString(), zone.id,
            now.minusSeconds(1).toOffsetDateTime().toString(), listOf("cook-1", "cook-2"), emptyList(), "cook-1")
        assertTrue(CookLocalSession.cache(context).save(CachedCookShift(binding, shift, people)))
        val dao = PilotDatabase.get(context).photoRecordDao()
        val existing = dao.getAllHistory().map { it.id }.toSet()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await { visible("Повар 2") && cameraReady() }
                onView(withText("Повар 2")).perform(click())
                await { selectedAuthor("Повар 2") }
                assertEquals("cook-2", JSONObject(CookLocalSession.preferences(context)
                    .getString("current_photo_author", null)!!).getString("employee"))
                onView(withId(R.id.captureButton)).perform(click())
                await { dao.getAllHistory().count { it.id !in existing } >= 1 && cameraReady() }
                scenario.recreate()
                await { selectedAuthor("Повар 2") && cameraReady() }
                onView(withId(R.id.captureButton)).perform(click())
                await { dao.getAllHistory().count { it.id !in existing } >= 2 }
                val photos = dao.getAllHistory().filter { it.id !in existing }.sortedBy { it.capturedAt }
                assertEquals("cook-2", JSONObject(photos[0].photoContext!!).getJSONObject("cookRef").getString("id"))
                assertEquals("cook-2", JSONObject(photos[1].photoContext!!).getJSONObject("cookRef").getString("id"))
            }
        } finally { prefs.edit().remove(AppPrefs.KEY_DEVICE_MODE).commit() }
    }

    @Test fun changedDeviceCannotCaptureWithPreviousDeviceShift() {
        val dao = PilotDatabase.get(context).photoRecordDao()
        val existing = dao.getAllHistory().map { it.id }.toSet()
        ActivityScenario.launch(MainActivity::class.java).use {
            await { visible("Смена") }
            onView(withText("Смена")).perform(click())
            chooseShift(twoCooks = false)
            await { visible("Повар 1") && cameraReady() }
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
            assertTrue(!visible("Начать смену"))
            scenario.onActivity { it.intent.putExtra("test_shift_clock_ms", morning.plusSeconds(1).toEpochMilli()) }
            await { visible("Начать смену") }
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
            await { visible("Повар 1 · уточнить имя") && cameraReady() }
            onView(withId(R.id.captureButton)).perform(click())
            await { visible("У сотрудников одинаковые имена. Попросите управляющего уточнить их в справочнике.") }
            SystemClock.sleep(500)
            assertEquals(0, dao.getAllHistory().count { it.id !in existing })
            onView(withText("Закрыть")).inRoot(isDialog()).perform(click())
            assertEquals(0, dao.getAllHistory().count { it.id !in existing })
        }
    }
}
