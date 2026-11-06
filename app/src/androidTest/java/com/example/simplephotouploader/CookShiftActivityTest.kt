package com.example.simplephotouploader

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.CheckBox
import android.widget.RadioButton
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.allOf
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class CookShiftActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun reset() {
        CookLocalSession.cache(context).clear()
        CookPendingShiftStore(CookLocalSession.preferences(context)).clear()
        CookPendingEmployeeStore(CookLocalSession.preferences(context)).clear()
        CookLocalSession.preferences(context).edit().remove(CookOfflineSelectionCache.KEY).commit()
        CookLocalSession.preferences(context).edit().remove(CookDeviceRosterCache.KEY).commit()
    }

    @After fun cleanup() = reset()

    @Test fun duplicateNamesCannotBeSelected() {
        val intent = Intent(context, CookShiftActivity::class.java).putExtra("test_duplicate_names", true)
        ActivityScenario.launch<CookShiftActivity>(intent).use { scenario ->
            val deadline = SystemClock.elapsedRealtime() + 10_000
            var choices: List<CheckBox> = emptyList()
            fun descendants(view: View): List<View> = listOf(view) +
                if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            while (choices.size < 2 && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { activity ->
                    choices = descendants(activity.window.decorView).filterIsInstance<CheckBox>()
                        .filter { it.text.toString() == "Повар 1 · уточнить имя" }
                }
                if (choices.size < 2) SystemClock.sleep(100)
            }
            assertEquals(2, choices.size)
            scenario.onActivity { choices.forEach { assertFalse(it.isEnabled) } }
        }
    }

    @Test fun previouslySelectedAmbiguousCookCanBeRemovedButNotRechecked() {
        val now = Instant.now().atZone(ZoneId.of("Europe/Moscow"))
        val binding = CookBinding("preview", 1, "Europe/Moscow")
        val previous = CachedCookShift(binding, CookShift("old", 1, "preview", now.toLocalDate().toString(),
            "Europe/Moscow", now.toOffsetDateTime().toString(), listOf("cook-1"), listOf("operator-1"), "cook-1"),
            listOf(CookEmployee("cook-1", "Повар 1", listOf("cook")),
                CookEmployee("cook-2", "Повар 1", listOf("cook")), CookEmployee("operator-1", "Оператор 1", listOf("operator"))))
        assertTrue(CookLocalSession.cache(context).save(previous))
        val intent = Intent(context, CookShiftActivity::class.java).putExtra("test_duplicate_names", true)
        ActivityScenario.launch<CookShiftActivity>(intent).use { scenario ->
            val deadline = SystemClock.elapsedRealtime() + 10_000
            var checked: CheckBox? = null
            fun descendants(view: View): List<View> = listOf(view) +
                if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            while (checked == null && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { activity ->
                    checked = descendants(activity.window.decorView).filterIsInstance<CheckBox>()
                        .firstOrNull { it.text.toString() == "Повар 1 · уточнить имя" && it.isChecked }
                }
                if (checked == null) SystemClock.sleep(100)
            }
            assertNotNull(checked)
            scenario.onActivity { assertTrue(checked!!.isEnabled); checked!!.performClick() }
            scenario.onActivity { activity ->
                val choices = descendants(activity.window.decorView).filterIsInstance<CheckBox>()
                    .filter { it.text.toString() == "Повар 1 · уточнить имя" }
                assertEquals(2, choices.size)
                choices.forEach { assertFalse(it.isChecked); assertFalse(it.isEnabled) }
            }
        }
    }

    @Test fun previewShiftCanBeConfirmedAndRestored() {
        ActivityScenario.launch(CookShiftActivity::class.java).use {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            var loaded = false
            while (!loaded && SystemClock.elapsedRealtime() < deadline) {
                try { onView(withText("Повар 1")).check(androidx.test.espresso.assertion.ViewAssertions.matches(
                    androidx.test.espresso.matcher.ViewMatchers.isDisplayed())); loaded = true }
                catch (_: androidx.test.espresso.NoMatchingViewException) { SystemClock.sleep(100) }
            }
            onView(withText("Повар 1")).perform(click())
            onView(withText("Повар 2")).perform(click())
            onView(withText("Оператор 1")).perform(scrollTo(), click())
            onView(withText("Подтвердить смену")).perform(scrollTo(), click())
            while (CookLocalSession.cache(context).load() == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            val shift = CookLocalSession.cache(context).load()
            assertNotNull(shift)
            assertEquals(listOf("cook-1", "cook-2"), shift!!.shift.cooks)
            assertEquals(listOf("operator-1"), shift.shift.operators)
            assertEquals("cook-1", shift.shift.mainCookId)
        }
    }

    @Test fun removingMainWithSeveralCooksRequiresExplicitReplacement() {
        val intent = Intent(context, CookShiftActivity::class.java).putExtra("test_three_cooks", true)
        ActivityScenario.launch<CookShiftActivity>(intent).use { scenario ->
            val deadline = SystemClock.elapsedRealtime() + 10_000
            var loaded = false
            while (!loaded && SystemClock.elapsedRealtime() < deadline) {
                try { onView(allOf(withText("Повар 3"), isAssignableFrom(CheckBox::class.java)))
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                        androidx.test.espresso.matcher.ViewMatchers.isDisplayed())); loaded = true }
                catch (_: androidx.test.espresso.NoMatchingViewException) { SystemClock.sleep(100) }
            }
            onView(allOf(withText("Повар 1"), isAssignableFrom(CheckBox::class.java))).perform(click())
            onView(allOf(withText("Повар 2"), isAssignableFrom(CheckBox::class.java))).perform(scrollTo(), click())
            onView(allOf(withText("Повар 3"), isAssignableFrom(CheckBox::class.java))).perform(scrollTo(), click())
            onView(allOf(withText("Повар 1"), isAssignableFrom(CheckBox::class.java))).perform(scrollTo(), click())
            fun descendants(view: View): List<View> = listOf(view) +
                if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            scenario.onActivity { activity ->
                val radios = descendants(activity.window.decorView).filterIsInstance<RadioButton>()
                assertEquals(2, radios.size)
                radios.forEach { assertFalse(it.isChecked) }
            }
            onView(allOf(withText("Повар 2"), isAssignableFrom(RadioButton::class.java))).perform(scrollTo(), click())
            scenario.onActivity { activity ->
                val radios = descendants(activity.window.decorView).filterIsInstance<RadioButton>()
                assertEquals("Повар 2", radios.single { it.isChecked }.text.toString())
            }
        }
    }

    @Test fun offlineNewEmployeeAndShiftAreStoredWithoutReplacingConfirmedShift() {
        val newName = "Новый повар ${UUID.randomUUID().toString().take(8)}"
        val binding = CookBinding("preview", 1, "Europe/Moscow")
        val now = Instant.now().atZone(ZoneId.of("Europe/Moscow"))
        val previous = CachedCookShift(binding, CookShift("old-shift", 1, "preview", now.toLocalDate().toString(),
            "Europe/Moscow", now.minusMinutes(2).toOffsetDateTime().toString(), listOf("cook-1"),
            listOf("operator-1"), "cook-1"), listOf(CookEmployee("cook-1", "Повар 1", listOf("cook")),
            CookEmployee("operator-1", "Оператор 1", listOf("operator"))))
        assertEquals(true, CookLocalSession.cache(context).save(previous))
        val intent = Intent(context, CookShiftActivity::class.java).putExtra("test_offline", true)
        ActivityScenario.launch<CookShiftActivity>(intent).use {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var loaded = false
            while (!loaded && SystemClock.elapsedRealtime() < deadline) {
                try { onView(withText("Добавить сотрудника")).check(androidx.test.espresso.assertion.ViewAssertions.matches(
                    androidx.test.espresso.matcher.ViewMatchers.isDisplayed())); loaded = true }
                catch (_: Throwable) { SystemClock.sleep(100) }
            }
            onView(withText("Добавить сотрудника")).perform(scrollTo(), click())
            onView(withHint("Имя сотрудника")).perform(replaceText(newName), closeSoftKeyboard())
            onView(withText("Добавить")).perform(click())
            var appeared = false
            while (!appeared && SystemClock.elapsedRealtime() < deadline) {
                try { onView(withText(newName)).check(androidx.test.espresso.assertion.ViewAssertions.matches(
                    androidx.test.espresso.matcher.ViewMatchers.isDisplayed())); appeared = true }
                catch (_: Throwable) { SystemClock.sleep(100) }
            }
            onView(allOf(withText("Повар 1"), isAssignableFrom(CheckBox::class.java))).perform(click())
            onView(allOf(withText(newName), isAssignableFrom(CheckBox::class.java))).perform(scrollTo(), click())
            onView(withText("Подтвердить смену")).perform(scrollTo(), click())
            val scope = CookLocalSession.offlineScope(context, binding)
            val cache = CookOfflineSelectionCache(CookLocalSession.preferences(context), CookOfflineDatabase.get(context).queue(), scope)
            var local = cache.load()
            while (local == null && SystemClock.elapsedRealtime() < deadline) { SystemClock.sleep(100); local = cache.load() }
            assertNotNull(local)
            assertEquals(previous, CookLocalSession.cache(context).load())
            val active = local!!
            val newCook = active.employees.first { person -> person.displayName == newName }
            val selected = CookOfflineSelectionCache.cookRef(active.offlineShift!!, newCook.id)
            assertEquals("provisional", selected.kind)
            val captured = JSONObject(CookPhotoContextFactory.createProvisional(scope, active.offlineShift,
                newCook, selected, "main_default", Instant.now()))
            assertEquals(newCook.id, captured.getJSONObject("cookRef").getString("id"))
            assertEquals("provisional", captured.getJSONObject("shiftRef").getString("kind"))
        }
    }

    @Test fun firstOfflineShiftRestoresWithoutCanonicalShift() {
        context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE).edit().putString(AppPrefs.KEY_CITY, "Тест").commit()
        reset()
        val binding = CookBinding("preview", 1, "Europe/Moscow")
        val newcomer = "Новый ${UUID.randomUUID().toString().take(8)}"
        val people = listOf(CookEmployee("cook-1", "Повар 1", listOf("cook")),
            CookEmployee("cook-2", "Повар 2", listOf("cook")), CookEmployee("operator-1", "Оператор 1", listOf("operator")))
        assertTrue(CookDeviceRosterCache(CookLocalSession.preferences(context)).save(binding, people))
        assertEquals(null, CookLocalSession.cache(context).load())
        val intent = Intent(context, CookShiftActivity::class.java).putExtra("test_offline", true)
        ActivityScenario.launch<CookShiftActivity>(intent).use {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var loaded = false
            while (!loaded && SystemClock.elapsedRealtime() < deadline) {
                try { onView(withText("Повар 1")).check(androidx.test.espresso.assertion.ViewAssertions.matches(
                    androidx.test.espresso.matcher.ViewMatchers.isDisplayed())); loaded = true }
                catch (_: androidx.test.espresso.NoMatchingViewException) { SystemClock.sleep(100) }
            }
            onView(withText("Добавить сотрудника")).perform(scrollTo(), click())
            onView(withHint("Имя сотрудника")).perform(replaceText(newcomer), closeSoftKeyboard())
            onView(withText("Добавить")).perform(click())
            var added = false
            while (!added && SystemClock.elapsedRealtime() < deadline) {
                try { onView(withText(newcomer)).check(androidx.test.espresso.assertion.ViewAssertions.matches(
                    androidx.test.espresso.matcher.ViewMatchers.isDisplayed())); added = true }
                catch (_: androidx.test.espresso.NoMatchingViewException) { SystemClock.sleep(100) }
            }
            onView(withText(newcomer)).perform(scrollTo(), click())
            onView(withText("Оператор 1")).perform(scrollTo(), click())
            onView(withText("Подтвердить смену")).perform(scrollTo(), click())
            val local = CookLocalSession.preferences(context)
            while (!local.contains(CookOfflineSelectionCache.KEY) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertTrue(local.contains(CookOfflineSelectionCache.KEY))
            assertEquals(null, CookLocalSession.cache(context).load())
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.CAMERA").close()
        ActivityScenario.launch(MainActivity::class.java).use {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var restored = false
            while (!restored && SystemClock.elapsedRealtime() < deadline) {
                try { onView(withText("Следующее фото: $newcomer · локальная смена"))
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                        androidx.test.espresso.matcher.ViewMatchers.isDisplayed())); restored = true }
                catch (_: androidx.test.espresso.NoMatchingViewException) { SystemClock.sleep(100) }
            }
            assertTrue("First offline shift was not restored", restored)
        }
    }
}
