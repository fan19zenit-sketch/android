package com.example.simplephotouploader

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SetupDeviceModeTest {
    @Test fun setupOffersBothPhoneModes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_FORCE_EDIT, true)
        ActivityScenario.launch<SetupActivity>(intent).use {
            onView(withText("Основной телефон")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Телефон пицца-цеха")).perform(scrollTo(), click()).check(matches(isChecked()))
        }
    }
}
