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
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Screenshots use only preview identities and an offline disposable emulator. */
@RunWith(AndroidJUnit4::class)
class CookShiftScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After fun resetMode() {
        context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE).edit()
            .putString(AppPrefs.KEY_DEVICE_MODE, AppPrefs.DEVICE_MODE_MAIN).commit()
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

    @Test fun captureProductWorkflow() {
        require(BuildConfig.BUILD_TYPE == "preview")
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT >= 33) inst.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val prefs = context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(AppPrefs.KEY_CITY, "Шексна").putString(AppPrefs.KEY_BACKEND_URL, "http://127.0.0.1:9")
            .putString(AppPrefs.KEY_DEVICE_UUID, "design-fixture").putString(AppPrefs.KEY_DEVICE_MODE, AppPrefs.DEVICE_MODE_MAIN).commit()
        val now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Moscow"))
        val binding = CookBinding("design", 1, "Europe/Moscow")
        val args = InstrumentationRegistry.getArguments()
        val firstName = if (args.getString("long_names") == "true")
            "Преображенский-Рождественский Константин" else "Иванов Иван"
        val people = listOf(CookEmployee("first", firstName, listOf("cook")),
            CookEmployee("second", "Петрова Анна", listOf("cook")),
            CookEmployee("operator", "Сидорова Мария", listOf("operator")))
        val shift = CookShift("design-shift", 1, "design", now.toLocalDate().toString(), "Europe/Moscow",
            now.minusMinutes(10).toOffsetDateTime().toString(), listOf("first", "second"), listOf("operator"), "first")
        val local = CookLocalSession.preferences(context)
        local.edit().remove(CookOfflineSelectionCache.KEY).remove("current_photo_author").commit()
        check(CookDeviceRosterCache(local).save(binding, people))
        check(CookLocalSession.cache(context).save(CachedCookShift(binding, shift, people)))
        val source = File(context.cacheDir, "design-product.jpg")
        inst.context.assets.open("product-fixture.jpg").use { input -> source.outputStream().use { input.copyTo(it) } }
        val dao = PilotDatabase.get(context).photoRecordDao()
        val fixtures = listOf("sent", "queued", "needs_review").mapIndexed { i, state ->
            val time = now.minusMinutes(i + 1L).toInstant()
            PhotoRecordEntity("design-$i", null, null, false, File(context.cacheDir, "design-$i.jpg").absolutePath,
                source.absolutePath, time.toString(), null, state, if (i == 2) "Доставка не подтверждена сервером" else null, i == 1,
                photoContext = CookPhotoContextFactory.create(binding, binding, shift, people[i % 2],
                    if (i == 1) "one_shot" else "main_default", time))
        }
        try {
            fixtures.forEach { source.copyTo(File(it.photoPath), overwrite = true) }
            ActivityScenario.launch<SetupActivity>(Intent(context, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_FORCE_EDIT,true)).use { androidx.test.espresso.Espresso.closeSoftKeyboard(); save("10-setup") }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(firstName)
                if (args.getString("check_landscape") == "true") scenario.onActivity {
                    check(it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                }
                val limit = SystemClock.elapsedRealtime() + 15000
                var ready = false
                while (!ready && SystemClock.elapsedRealtime() < limit) {
                    scenario.onActivity { ready = it.findViewById<android.view.View>(R.id.captureButton).isEnabled &&
                        it.findViewById<android.widget.TextView>(R.id.statusText).text.toString() == context.getString(R.string.camera_ready) }
                    SystemClock.sleep(100)
                }
                check(ready) { "The camera must be ready before recording its appearance" }
                scenario.onActivity { activity ->
                    val preview = activity.findViewById<android.view.View>(R.id.previewView)
                    val content = activity.findViewById<android.view.View>(android.R.id.content)
                    val shutter = activity.findViewById<android.view.View>(R.id.captureButton)
                    check(shutter.width == shutter.height)
                    val tiles = activity.findViewById<android.widget.LinearLayout>(R.id.cookChoices)
                    val heights = (0 until tiles.childCount).map { tiles.getChildAt(it) }
                        .filterIsInstance<android.widget.LinearLayout>().map { it.height }
                    check(heights.size == 3 && heights.distinct().size == 1)
                    (0 until tiles.childCount).map { tiles.getChildAt(it) }.filterIsInstance<android.widget.LinearLayout>().forEach { tile ->
                        val caption = tile.getChildAt(0) as android.widget.TextView
                        check(caption.height >= caption.layout.height && caption.bottom <= tile.height - tile.paddingBottom)
                    }
                    check(activity.findViewById<android.view.View>(R.id.statusText).visibility == android.view.View.GONE)
                    val portrait = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
                    val fraction = preview.height.toDouble() / content.height
                    val bottom = (activity.findViewById<android.view.View>(R.id.cookPanel).parent as android.view.View).height / activity.resources.displayMetrics.density
                    android.util.Log.i("DesignMetrics", "preview fraction=$fraction bottom=$bottom dp")
                    if (portrait && activity.resources.configuration.fontScale <= 1.0f) check(fraction >= 0.70)
                }
                save("11-camera")
                onView(withText("Сидорова Мария")).perform(click()); save("12a-operator")
                onView(withText("Петрова Анна")).perform(click()); save("12-one-shot")
                onView(withText("Ещё")).perform(click()); save("13-other-authors")
                onView(withText("Совместно")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click()); save("13b-shared")
                onView(withText("Совместно")).perform(click())
                onView(withText("Автор неизвестен")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist()); androidx.test.espresso.Espresso.pressBack()
                onView(withText("Смена")).perform(click()); waitFor("Кто готовит дальше?"); save("11c-change")
                onView(withText("Петрова Анна")).perform(click()); waitFor("Петрова Анна"); save("11d-persistent")
            }
            if (args.getString("check_landscape") != "true") {
                prefs.edit().putString(AppPrefs.KEY_DEVICE_MODE, AppPrefs.DEVICE_MODE_PIZZA).commit()
                val pizzaShift = shift.copy(cooks = listOf("first", "operator"), operators = emptyList())
                check(CookLocalSession.cache(context).save(CachedCookShift(binding, pizzaShift, people)))
                ActivityScenario.launch(MainActivity::class.java).use {
                    waitFor(firstName)
                    onView(withText("Сидорова Мария")).perform(click())
                    save("12b-pizza-camera")
                }
                prefs.edit().putString(AppPrefs.KEY_DEVICE_MODE, AppPrefs.DEVICE_MODE_MAIN).commit()
            }
            fixtures.forEach(dao::insert)
            ActivityScenario.launch(HistoryActivity::class.java).use {
                waitFor("История"); save("14-history")
                onView(withText("Проверить")).perform(click()); save("15-attention")
                onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.rowActions)).perform(click()); save("15b-retry"); androidx.test.espresso.Espresso.pressBack()
                onView(withText("В пути")).perform(click()); waitFor("В очереди"); save("16-queue")
                onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.historyMenu)).perform(click()); save("16b-menu")
                val support = onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.btnSupport))
                if (context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                    support.perform(scrollTo())
                support.perform(click()); SystemClock.sleep(900); save("19-diagnostics")
            }
            ActivityScenario.launch<PhotoViewerActivity>(Intent(context, PhotoViewerActivity::class.java)
                .putExtra(PhotoViewerActivity.EXTRA_PHOTO_PATH, source.absolutePath)
                .putExtra(PhotoViewerActivity.EXTRA_CAPTURED_AT, fixtures[0].capturedAt)
                .putExtra(PhotoViewerActivity.EXTRA_STATUS, "sent")).use { save("17-viewer") }
            ActivityScenario.launch(SupportActivity::class.java).use { save("18-support") }
        } finally {
            fixtures.forEach { dao.deleteByPhotoPath(it.photoPath); File(it.photoPath).delete() }
            source.delete()
            CookLocalSession.cache(context).clear()
            local.edit().remove(CookDeviceRosterCache.KEY).commit()
        }
    }

    @Test fun captureThreeTrainees() {
        context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE).edit()
            .putString(AppPrefs.KEY_DEVICE_MODE, AppPrefs.DEVICE_MODE_MAIN).commit()
        val intent = Intent(context, CookShiftActivity::class.java).putExtra("presentation_names", true)
        ActivityScenario.launch<CookShiftActivity>(intent).use {
            waitFor("Начать смену"); onView(withText("Начать смену")).perform(click())
            choose("6"); next(); choose("1"); next(); choose("1"); next(); choose("1"); next()
            choose("3"); save("trainees-03-count"); next()
            choose("Сидорова Мария"); next(); choose("Иванов Иван"); next(); choose("Петрова Анна"); next()
            choose("Орлова Елена"); next(); choose("Зуев Денис"); next()
            waitFor("Выберите стажёра 3"); choose("Волкова Лидия"); next()
            waitFor("Проверьте состав"); save("trainees-03-review")
        }
    }

    @Test fun captureMainAndPizzaShiftScreens() {
        val prefs = context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(AppPrefs.KEY_CITY, "Шексна").putString(AppPrefs.KEY_DEVICE_MODE, AppPrefs.DEVICE_MODE_MAIN).commit()
        CookLocalSession.cache(context).clear()
        CookPendingShiftStore(CookLocalSession.preferences(context)).clear()
        CookLocalSession.preferences(context).edit().remove(CookOfflineSelectionCache.KEY).commit()
        val intent = Intent(context, CookShiftActivity::class.java).putExtra("presentation_names", true)
        ActivityScenario.launch<CookShiftActivity>(intent).use {
            waitFor("Начать смену"); save("01-start")
            onView(withText("Начать смену")).perform(click())
            waitFor("Сколько сотрудников?"); choose("3"); save("02-staff-count")
            next()
            waitFor("Сколько операторов?"); save("02b-operator-count"); choose("1"); next()
            waitFor("Сколько поваров?"); choose("2"); save("02c-cook-count"); next()
            waitFor("Сколько помощников?"); save("02d-assistant-count"); choose("0"); next()
            waitFor("Сколько стажёров?"); save("02e-trainee-count"); choose("0"); next()
            waitFor("Выберите оператора 1"); onView(withText("Добавить стажёра")).perform(scrollTo(), click()); save("02e-add"); androidx.test.espresso.Espresso.pressBack(); choose("Сидорова Мария"); save("03-operator"); next()
            waitFor("Выберите повара 1"); choose("Иванов Иван"); save("03b-first-cook"); next()
            waitFor("Выберите повара 2"); choose("Петрова Анна"); save("04-second-cook"); next()
            waitFor("Кто основной повар?"); choose("Иванов Иван"); save("05-main-cook"); next()
            waitFor("Проверьте состав"); save("06-confirm")
        }
        ActivityScenario.launch<CookShiftActivity>(intent).use {
            waitFor("Начать смену"); onView(withText("Начать смену")).perform(click()); choose("1"); next()
            waitFor("Выберите сотрудника"); choose("Иванов Иван"); save("06b-single"); next(); waitFor("Проверьте состав"); save("06c-dual-role")
        }
        prefs.edit().putString(AppPrefs.KEY_DEVICE_MODE, AppPrefs.DEVICE_MODE_PIZZA).commit()
        ActivityScenario.launch<CookShiftActivity>(intent).use {
            waitFor("Сколько поваров сегодня?"); choose("2"); save("07-pizza-count")
            next()
            waitFor("Выберите повара 1"); choose("Иванов Иван"); save("08-pizza-person"); next(); waitFor("Выберите повара 2"); choose("Петрова Анна"); save("08b-pizza-second"); next(); waitFor("Проверьте состав"); save("08c-pizza-confirm")
        }
    }
}
