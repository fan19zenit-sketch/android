package com.example.simplephotouploader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.hamcrest.Matchers.allOf
import org.junit.Test
import org.junit.runner.RunWith

/** Screenshots use only preview identities and an offline disposable emulator. */
@RunWith(AndroidJUnit4::class)
class CookShiftScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

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

    private fun save(name: String) {
        SystemClock.sleep(400)
        val folder = File(requireNotNull(context.getExternalFilesDir(null)), "shift-screens")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        FileOutputStream(File(folder, "$name.png")).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun next() = onView(withText("Далее")).perform(scrollTo(), click())
    private fun choose(value: String) = onView(allOf(withText(value), isAssignableFrom(RadioButton::class.java)))
        .perform(scrollTo(), click())

    @Test fun captureMainAndPizzaShiftScreens() {
        val prefs = context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(AppPrefs.KEY_CITY, "Пример").putString(AppPrefs.KEY_DEVICE_MODE, AppPrefs.DEVICE_MODE_MAIN).commit()
        CookLocalSession.cache(context).clear()
        CookPendingShiftStore(CookLocalSession.preferences(context)).clear()
        CookLocalSession.preferences(context).edit().remove(CookOfflineSelectionCache.KEY).commit()
        val intent = Intent(context, CookShiftActivity::class.java).putExtra("presentation_names", true)
        ActivityScenario.launch<CookShiftActivity>(intent).use {
            waitFor("Начать смену"); save("01-start")
            onView(withText("Начать смену")).perform(click())
            waitFor("Сколько сотрудников на смене?"); save("02-staff-count")
            choose("3"); next()
            waitFor("Сколько операторов в смене?"); choose("1"); next()
            waitFor("Сколько поваров в смене?"); choose("2"); next()
            waitFor("Сколько помощников в смене?"); choose("0"); next()
            waitFor("Выберите оператора 1"); save("03-operator")
            choose("Сидорова Мария"); next()
            waitFor("Выберите повара 1"); choose("Иванов Иван"); next()
            waitFor("Выберите повара 2"); save("04-second-cook")
            choose("Петрова Анна"); next()
            waitFor("Кто основной повар?"); save("05-main-cook")
            choose("Иванов Иван"); next()
            waitFor("Проверьте состав смены"); save("06-confirm")
        }
        prefs.edit().putString(AppPrefs.KEY_DEVICE_MODE, AppPrefs.DEVICE_MODE_PIZZA).commit()
        ActivityScenario.launch<CookShiftActivity>(intent).use {
            waitFor("Сколько поваров сегодня?"); save("07-pizza-count")
            choose("2"); next()
            waitFor("Выберите повара 1"); save("08-pizza-person")
        }
    }
}
