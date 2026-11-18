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

@RunWith(AndroidJUnit4::class)
class CookPizzaShiftActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setup() {
        context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE).edit()
            .putString(AppPrefs.KEY_DEVICE_MODE, AppPrefs.DEVICE_MODE_PIZZA).commit()
        CookLocalSession.cache(context).clear()
        CookPendingShiftStore(CookLocalSession.preferences(context)).clear()
        CookLocalSession.preferences(context).edit().remove(CookOfflineSelectionCache.KEY).commit()
    }

    @After fun cleanup() {
        CookLocalSession.cache(context).clear()
        CookPendingShiftStore(CookLocalSession.preferences(context)).clear()
        context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE).edit().remove(AppPrefs.KEY_DEVICE_MODE).commit()
    }

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

    private fun choose(value: String) = onView(allOf(withText(value), isAssignableFrom(RadioButton::class.java)))
        .perform(scrollTo(), click())
    private fun next() = onView(withText("Далее")).perform(scrollTo(), click())

    @Test fun twoPizzaCooksCanIncludeSomeoneListedAsOperator() {
        ActivityScenario.launch<CookShiftActivity>(Intent(context, CookShiftActivity::class.java)).use {
            waitFor("Сколько поваров сегодня?"); choose("2"); next()
            waitFor("Выберите повара 1"); choose("Оператор 1"); next()
            waitFor("Выберите повара 2"); choose("Повар 1"); next()
            waitFor("Проверьте состав")
            onView(withText("Начать смену")).perform(scrollTo(), click())
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var saved = CookLocalSession.cache(context).load()
            while (saved == null && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100); saved = CookLocalSession.cache(context).load()
            }
            assertNotNull(saved)
            assertEquals(setOf("operator-1", "cook-1"), saved!!.shift.cooks.toSet())
            assertEquals("operator-1", saved!!.shift.mainCookId)
            assertEquals(emptyList<String>(), saved!!.shift.operators)
        }
    }
}
