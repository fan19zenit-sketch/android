package com.example.simplephotouploader

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CookShiftActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun reset() {
        CookLocalSession.cache(context).clear()
        CookPendingShiftStore(CookLocalSession.preferences(context)).clear()
    }

    @After fun cleanup() = reset()

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
}
