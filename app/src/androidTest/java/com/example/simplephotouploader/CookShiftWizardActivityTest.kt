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
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Run in the isolated cleanPreview build; never against a branch account. */
@RunWith(AndroidJUnit4::class)
class CookShiftWizardActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun reset() {
        CookLocalSession.cache(context).clear()
        CookPendingShiftStore(CookLocalSession.preferences(context)).clear()
        CookLocalSession.preferences(context).edit().remove(CookOfflineSelectionCache.KEY).commit()
    }

    @After fun cleanup() = reset()

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

    private fun next() = onView(withText("Далее")).perform(scrollTo(), click())
    private fun choose(text: String) = onView(allOf(withText(text), isAssignableFrom(RadioButton::class.java)))
        .perform(scrollTo(), click())

    @Test fun oneCookIsBothRolesWithoutFurtherCountScreens() {
        ActivityScenario.launch<CookShiftActivity>(Intent(context, CookShiftActivity::class.java)).use {
            waitFor("Начать смену")
            onView(withText("Начать смену")).perform(click())
            waitFor("Сколько сотрудников на смене?")
            choose("1"); next()
            waitFor("Выберите сотрудника")
            choose("Повар 1"); next()
            waitFor("Проверьте состав смены")
            onView(withText("Начать смену")).perform(scrollTo(), click())
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var saved = CookLocalSession.cache(context).load()
            while (saved == null && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100); saved = CookLocalSession.cache(context).load()
            }
            assertNotNull(saved)
            assertEquals(listOf("cook-1"), saved!!.shift.cooks)
            assertEquals(listOf("cook-1"), saved!!.shift.operators)
            assertEquals("cook-1", saved!!.shift.mainCookId)
            assertEquals(emptyList<String>(), saved!!.shift.assistants)
        }
    }

    @Test fun twoPeopleCanSelectSeparateOperatorAndCookWithoutAssistant() {
        ActivityScenario.launch<CookShiftActivity>(Intent(context, CookShiftActivity::class.java)).use {
            waitFor("Начать смену"); onView(withText("Начать смену")).perform(click())
            waitFor("Сколько сотрудников на смене?"); choose("2"); next()
            waitFor("Сколько операторов в смене?"); choose("1"); next()
            waitFor("Сколько поваров в смене?"); choose("1"); next()
            waitFor("Сколько помощников в смене?"); choose("0"); next()
            waitFor("Выберите оператора 1"); choose("Оператор 1"); next()
            waitFor("Выберите повара 1"); choose("Повар 1"); next()
            waitFor("Проверьте состав смены")
            onView(withText("Начать смену")).perform(scrollTo(), click())
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var saved = CookLocalSession.cache(context).load()
            while (saved == null && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100); saved = CookLocalSession.cache(context).load()
            }
            assertNotNull(saved)
            assertEquals(listOf("operator-1"), saved!!.shift.operators)
            assertEquals(listOf("cook-1"), saved!!.shift.cooks)
            assertEquals(emptyList<String>(), saved!!.shift.assistants)
        }
    }

    @Test fun helperIsStoredSeparatelyFromCook() {
        ActivityScenario.launch<CookShiftActivity>(Intent(context, CookShiftActivity::class.java)).use {
            waitFor("Начать смену"); onView(withText("Начать смену")).perform(click())
            waitFor("Сколько сотрудников на смене?"); choose("3"); next()
            waitFor("Сколько операторов в смене?"); choose("1"); next()
            waitFor("Сколько поваров в смене?"); choose("1"); next()
            waitFor("Сколько помощников в смене?"); choose("1"); next()
            waitFor("Выберите оператора 1"); choose("Оператор 1"); next()
            waitFor("Выберите повара 1"); choose("Повар 1"); next()
            waitFor("Выберите помощника 1"); choose("Повар 2"); next()
            waitFor("Проверьте состав смены")
            onView(withText("Начать смену")).perform(scrollTo(), click())
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var saved = CookLocalSession.cache(context).load()
            while (saved == null && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100); saved = CookLocalSession.cache(context).load()
            }
            assertNotNull(saved)
            assertEquals(listOf("cook-1"), saved!!.shift.cooks)
            assertEquals(listOf("cook-2"), saved!!.shift.assistants)
            assertEquals(listOf("operator-1"), saved!!.shift.operators)
        }
    }

    @Test fun sharedBranchTemplateLetsEachPhoneChooseItsOwnMainCook() {
        for ((name, id) in listOf("Повар 1" to "cook-1", "Повар 2" to "cook-2")) {
            reset()
            val intent = Intent(context, CookShiftActivity::class.java).putExtra("test_branch_template", true)
            ActivityScenario.launch<CookShiftActivity>(intent).use {
                waitFor("Начать смену"); onView(withText("Начать смену")).perform(click())
                waitFor("Кто основной повар?"); choose(name); next()
                waitFor("Проверьте состав смены")
                onView(withText("Начать смену")).perform(scrollTo(), click())
                val deadline = SystemClock.elapsedRealtime() + 15_000
                var saved = CookLocalSession.cache(context).load()
                while (saved == null && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(100); saved = CookLocalSession.cache(context).load()
                }
                assertNotNull(saved)
                assertEquals(id, saved!!.shift.mainCookId)
                assertEquals(listOf("cook-1", "cook-2"), saved!!.shift.cooks)
            }
        }
    }
}
