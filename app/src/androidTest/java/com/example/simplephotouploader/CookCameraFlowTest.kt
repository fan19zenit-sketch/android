package com.example.simplephotouploader

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RadioButton
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
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
import androidx.test.espresso.matcher.ViewMatchers.hasSibling
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.withParent
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
import java.io.File
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

    private var originalRows: Set<String> = emptySet()
    private var originalFiles: Set<String> = emptySet()
    private val photoFolder get() = File(context.filesDir, "photos")

    private fun newPhotos(dao: PhotoRecordDao) = dao.getAllHistory().filterNot {
        it.id in originalRows || it.photoPath in originalFiles
    }

    @org.junit.After fun removeOnlyPhotosCreatedByThisCameraTest() {
        if (BuildConfig.BUILD_TYPE != "preview") return
        val dao = PilotDatabase.get(context).photoRecordDao()
        newPhotos(dao).forEach { photo ->
            val file = File(photo.photoPath).canonicalFile
            if (file.parentFile == photoFolder.canonicalFile && file.name.startsWith(CaptureJpeg.PREFIX)) {
                if (!file.exists() || file.delete()) PhotoHistoryStore.removeByPhotoPath(context, photo.photoPath)
            }
        }
    }

    @Before fun prepare() {
        require(BuildConfig.BUILD_TYPE == "preview")
        originalFiles = photoFolder.listFiles().orEmpty().map { it.absolutePath }.toSet()
        originalRows = PilotDatabase.get(context).photoRecordDao().getAllHistory().map { it.id }.toSet()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, android.Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            automation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val device = "camera-test-" + java.util.UUID.randomUUID()
        context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE).edit()
            .putString(AppPrefs.KEY_CITY, "Тест")
            .putString(AppPrefs.KEY_DEVICE_UUID, device)
            .putString(AppPrefs.KEY_MODE_CONFIRMED_DEVICE, device)
            .putString(AppPrefs.KEY_DEVICE_MODE, AppPrefs.DEVICE_MODE_MAIN).commit()
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

    private fun primaryCookDot(name: String) = onView(allOf(isAssignableFrom(RadioButton::class.java),
        withParent(hasDescendant(withText(name)))))

    private fun startManualShift() = ActivityScenario.launch<MainActivity>(
        Intent(context, MainActivity::class.java).putExtra("test_shift_clock_ms",
            ZonedDateTime.now(ZoneId.of("Europe/Moscow")).with(LocalTime.of(10, 0)).toInstant().toEpochMilli()))

    private fun cameraWithActiveShift(): ActivityScenario<MainActivity> {
        val now = ZonedDateTime.now(ZoneId.of("Europe/Moscow"))
        val binding = CookBinding("preview", 1, now.zone.id)
        val people = listOf(CookEmployee("cook-1", "Повар 1", listOf("cook")),
            CookEmployee("cook-2", "Повар 2", listOf("cook")),
            CookEmployee("operator-1", "Оператор 1", listOf("operator")))
        val shift = CookShift("confirmed-test", 1, binding.branchRef, now.toLocalDate().toString(), now.zone.id,
            now.minusSeconds(1).toOffsetDateTime().toString(), listOf("cook-1", "cook-2"), listOf("operator-1"), "cook-1")
        val local = CookLocalSession.preferences(context)
        local.edit().remove(CookOfflineSelectionCache.KEY).commit()
        check(CookDeviceRosterCache(local).save(binding, people))
        check(CookLocalSession.cache(context).save(CachedCookShift(binding, shift, people)))
        return ActivityScenario.launch(MainActivity::class.java)
    }

    private fun chooseShift(twoCooks: Boolean) {
        await { visible("Начать смену") }
        onView(withText("Начать смену")).perform(click())
        choose(if (twoCooks) "3" else "2"); next()
        choose("1"); next()
        choose(if (twoCooks) "2" else "1"); next()
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
        startManualShift().use {
            await { visible("Смена") }
            onView(withText("Смена")).perform(click())
            chooseShift(twoCooks = true)
            await { visible("Повар 2") && cameraReady() }
            onView(withText("Автор неизвестен")).check(doesNotExist())
            onView(withText("Повар 2")).check(matches(withTagValue(`is`("cook-2" as Any))))
            onView(withText("Повар 2")).perform(click())
            assertTrue(selectedAuthor("Повар 2"))
            onView(withId(R.id.captureButton)).perform(click())
            await { newPhotos(dao).isNotEmpty() && cameraReady() }
            onView(withId(R.id.captureButton)).perform(click())
            await { newPhotos(dao).size >= 2 }
            val captured = newPhotos(dao).sortedBy { it.capturedAt }
            assertEquals("cook-2", JSONObject(captured[0].photoContext!!).getJSONObject("cookRef").getString("id"))
            assertEquals("cook-1", JSONObject(captured[1].photoContext!!).getJSONObject("cookRef").getString("id"))
        }
    }

    @Test fun secondTraineeFromMoreOwnsOnePhotoWithoutPromotion() {
        val dao = PilotDatabase.get(context).photoRecordDao()
        startManualShift().use {
            await { visible("Смена") }
            onView(withText("Смена")).perform(click())
            await { visible("Начать смену") }
            onView(withText("Начать смену")).perform(click())
            listOf("4", "1", "1", "0", "2", "Оператор 1", "Повар 1", "Стажёр 1", "Стажёр 2").forEach {
                choose(it); next()
            }
            onView(withText("Начать смену")).perform(scrollTo(), click())
            await { visible("Стажёр 1") && cameraReady() }
            onView(withText("Ещё")).perform(click())
            onView(withText("Стажёр 2")).inRoot(isDialog()).perform(click())
            await { selectedAuthor("Стажёр 2") }
            onView(withId(R.id.captureButton)).perform(click())
            await { selectedAuthor("Повар 1") && cameraReady() }
            onView(withId(R.id.captureButton)).perform(click())
            await { newPhotos(dao).size == 2 }
            val authors = newPhotos(dao).sortedBy { it.capturedAt }
                .map { JSONObject(it.photoContext!!).getJSONObject("cookRef").getString("id") }
            assertEquals(listOf("trainee-2", "cook-1"), authors)
        }
    }

    @Test fun operatorOnCameraOwnsOnlyOnePhoto() {
        val dao = PilotDatabase.get(context).photoRecordDao()
        startManualShift().use {
            await { visible("Смена") }
            onView(withText("Смена")).perform(click())
            chooseShift(twoCooks = true)
            await { visible("Оператор 1") && cameraReady() }
            onView(withText("Оператор 1")).perform(click())
            onView(withTagValue(`is`("operator-1" as Any))).check(matches(allOf(withText("Оператор 1"),
                androidx.test.espresso.matcher.ViewMatchers.withParent(androidx.test.espresso.matcher.ViewMatchers.isSelected()))))
            onView(withId(R.id.captureButton)).perform(click())
            await { selectedAuthor("Повар 1") && cameraReady() }
            onView(withId(R.id.captureButton)).perform(click())
            await { newPhotos(dao).size == 2 }
            val ids = newPhotos(dao).sortedBy { it.capturedAt }
                .map { JSONObject(it.photoContext!!).getJSONObject("cookRef").getString("id") }
            assertEquals(listOf("operator-1", "cook-1"), ids)
        }
    }

    @Test fun moreListsPinnedCooksWithoutJointCapture() {
        val dao = PilotDatabase.get(context).photoRecordDao()
        cameraWithActiveShift().use {
            await { selectedAuthor("Повар 1") && cameraReady() }
            onView(withText("Ещё")).perform(click())
            for (name in listOf("Повар 1", "Повар 2", "Оператор 1")) {
                onView(withText(name)).inRoot(isDialog()).perform(scrollTo()).check(matches(isDisplayed()))
            }
            onView(withText("Совместно")).inRoot(isDialog()).check(doesNotExist())
            onView(withText("Повар 2")).inRoot(isDialog()).perform(scrollTo(), click())
            onView(withId(R.id.captureButton)).perform(click())
            await { newPhotos(dao).size == 1 && selectedAuthor("Повар 1") }
            val context = JSONObject(newPhotos(dao).single().photoContext!!)
            assertEquals("cook-2", context.getJSONObject("cookRef").getString("id"))
        }
    }

    @Test fun changingCurrentCookPersistsAcrossPhotosAndActivityRecreation() {
        val dao = PilotDatabase.get(context).photoRecordDao()
        cameraWithActiveShift().use { scenario ->
            await { selectedAuthor("Повар 1") && cameraReady() }
            onView(withText("Смена")).perform(click())
            await { visible("Основной повар") }
            onView(withText("Сейчас на смене")).check(doesNotExist())
            onView(withText("Начать смену")).check(doesNotExist())
            primaryCookDot("Повар 1").check(matches(isChecked()))
            primaryCookDot("Повар 2").perform(scrollTo(), click()).check(matches(isChecked()))
            primaryCookDot("Повар 1").check(matches(org.hamcrest.Matchers.not(isChecked())))
            onView(allOf(withText("Основной повар"), hasSibling(withText("Повар 2")))).check(matches(isDisplayed()))
            onView(withText(R.string.cook_shift_return_camera)).perform(click())
            await { selectedAuthor("Повар 2") }
            onView(withText("Смена")).perform(click())
            await { visible("Основной повар") }
            onView(allOf(withText("Основной повар"), hasSibling(withText("Повар 2")))).check(matches(isDisplayed()))
            pressBack()
            await { cameraReady() }
            onView(withId(R.id.captureButton)).perform(click())
            await { newPhotos(dao).isNotEmpty() && cameraReady() }
            scenario.recreate()
            await { selectedAuthor("Повар 2") && cameraReady() }
            onView(withId(R.id.captureButton)).perform(click())
            await { newPhotos(dao).size >= 2 }
            val photos = newPhotos(dao).sortedBy { it.capturedAt }
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
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await { visible("Повар 2") && cameraReady() }
                onView(withText("Ещё")).perform(click())
                onView(withText("Состав пицца-цеха")).inRoot(isDialog()).perform(scrollTo(), click())
                await { visible("Основной повар") }
                primaryCookDot("Повар 2").perform(scrollTo(), click()).check(matches(isChecked()))
                onView(withText(R.string.cook_shift_return_camera)).perform(click())
                await { selectedAuthor("Повар 2") && cameraReady() }
                assertEquals("cook-2", JSONObject(CookLocalSession.preferences(context)
                    .getString("current_photo_author", null)!!).getString("employee"))
                onView(withId(R.id.captureButton)).perform(click())
                await { newPhotos(dao).isNotEmpty() && cameraReady() }
                scenario.recreate()
                await { selectedAuthor("Повар 2") && cameraReady() }
                onView(withId(R.id.captureButton)).perform(click())
                await { newPhotos(dao).size >= 2 }
                val photos = newPhotos(dao).sortedBy { it.capturedAt }
                assertEquals("cook-2", JSONObject(photos[0].photoContext!!).getJSONObject("cookRef").getString("id"))
                assertEquals("cook-2", JSONObject(photos[1].photoContext!!).getJSONObject("cookRef").getString("id"))
            }
        } finally { prefs.edit().remove(AppPrefs.KEY_DEVICE_MODE).commit() }
    }

    @Test fun changedDeviceCannotCaptureWithPreviousDeviceShift() {
        val dao = PilotDatabase.get(context).photoRecordDao()
        startManualShift().use {
            await { visible("Смена") }
            onView(withText("Смена")).perform(click())
            chooseShift(twoCooks = false)
            await { selectedAuthor("Повар 1") && cameraReady() }
            context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE).edit()
                .putString(AppPrefs.KEY_DEVICE_UUID, "other-device").commit()
            await { visible(context.getString(R.string.camera_shift_required)) && cameraReady() }
            onView(withId(R.id.captureButton)).perform(click())
            await { visible("Подтвердите состав смены перед съёмкой") || visible("Начать смену") }
            if (visible("Подтвердите состав смены перед съёмкой")) onView(withText("Открыть смену")).inRoot(isDialog()).perform(click())
            await { visible("Начать смену") }
            SystemClock.sleep(500)
            assertEquals(0, newPhotos(dao).size)
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
            await { selectedAuthor("Повар 1 · уточнить имя") && cameraReady() }
            onView(withId(R.id.captureButton)).perform(click())
            await { visible("У сотрудников одинаковые имена. Попросите управляющего уточнить их в справочнике.") }
            SystemClock.sleep(500)
            assertEquals(0, newPhotos(dao).size)
            onView(withText("Закрыть")).inRoot(isDialog()).perform(click())
            assertEquals(0, newPhotos(dao).size)
        }
    }
}
