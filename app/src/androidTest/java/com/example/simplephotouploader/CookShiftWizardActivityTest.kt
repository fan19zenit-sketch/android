package com.example.simplephotouploader

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RadioButton
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.hasErrorText
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Run in the isolated cleanPreview build; never against a branch account. */
@RunWith(AndroidJUnit4::class)
class CookShiftWizardActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun reset() {
        context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE).edit()
            .putString(AppPrefs.KEY_DEVICE_MODE, AppPrefs.DEVICE_MODE_MAIN).commit()
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
            waitFor("Сколько сотрудников?")
            choose("1"); next()
            waitFor("Выберите сотрудника")
            choose("Повар 1"); next()
            waitFor("Проверьте состав")
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
            waitFor("Сколько сотрудников?"); choose("2"); next()
            waitFor("Сколько операторов?"); choose("1"); next()
            waitFor("Сколько поваров?"); choose("1"); next()
            waitFor("Сколько помощников?"); choose("0"); next()
            waitFor("Сколько стажёров?"); choose("0"); next()
            waitFor("Выберите оператора 1"); choose("Оператор 1"); next()
            waitFor("Выберите повара 1"); choose("Повар 1"); next()
            waitFor("Проверьте состав")
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

    @Test fun traineeCanBePromotedInTheOperatorPicker() {
        ActivityScenario.launch<CookShiftActivity>(Intent(context, CookShiftActivity::class.java)).use {
            waitFor("Начать смену"); onView(withText("Начать смену")).perform(click())
            choose("2"); next(); choose("1"); next(); choose("1"); next(); choose("0"); next(); choose("0"); next()
            waitFor("Выберите оператора 1")
            onView(withText("Добавить стажёра")).perform(scrollTo(), click())
            onView(withText("Стажёр 1")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
            waitFor("Стажёр 1"); next()
            choose("Повар 1"); next()
            onView(withText("Оператор: Стажёр 1")).check(matches(isDisplayed()))
        }
    }

    @Test fun activeShiftAddsOneLateCookWithoutRestartingTheWizard() {
        val now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Moscow"))
        val binding = CookBinding("preview", 1, now.zone.id)
        val original = CookShift("active", 1, "preview", now.toLocalDate().toString(), now.zone.id,
            now.minusSeconds(1).toOffsetDateTime().toString(), listOf("cook-1"), listOf("cook-1"), "cook-1")
        val people = listOf(CookEmployee("cook-1", "Повар 1", listOf("cook")), CookEmployee("cook-2", "Повар 2", listOf("cook")))
        check(CookLocalSession.cache(context).save(CachedCookShift(binding, original, people)))
        val intent = Intent(context, CookShiftActivity::class.java).putExtra("shift_id", original.id).putExtra("revision", 1)
            .putExtra("author_ids", arrayOf("cook-1")).putExtra("author_names", arrayOf("Повар 1")).putExtra("current_author", "cook-1")
        ActivityScenario.launch<CookShiftActivity>(intent).use {
            waitFor("Кто готовит дальше?")
            onView(withText("Начать смену")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
            onView(withText("Добавить в состав")).perform(click())
            onView(withText("Повар")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
            onView(withText("Повар 2")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (CookLocalSession.cache(context).load()?.shift?.revision == 1 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            val updated = requireNotNull(CookLocalSession.cache(context).load()).shift
            assertEquals(original.id, updated.id); assertEquals(2, updated.revision)
            assertEquals(listOf("cook-1", "cook-2"), updated.cooks); assertEquals(original.operators, updated.operators)
        }
    }

    @Test fun activeShiftCreatesAndSelectsManualCookOfflineWithoutChangingOlderPhoto() {
        val now = Instant.now().atZone(ZoneId.of("Europe/Moscow"))
        val binding = CookBinding("preview", 1, now.zone.id)
        val original = CookShift(UUID.randomUUID().toString(), 1, binding.branchRef, now.toLocalDate().toString(),
            now.zone.id, now.minusSeconds(1).toOffsetDateTime().toString(),
            listOf("cook-1", "cook-2"), listOf("operator-1"), "cook-1")
        val people = listOf(CookEmployee("cook-1", "Повар 1", listOf("cook")),
            CookEmployee("cook-2", "Повар 2", listOf("cook")), CookEmployee("operator-1", "Оператор 1", listOf("operator")))
        val local = CookLocalSession.preferences(context)
        check(CookLocalSession.cache(context).saveRefreshed(CachedCookShift(binding, original, people), "cook-2"))
        val scope = CookLocalSession.offlineScope(context, binding)
        val database = CookOfflineDatabase.get(context)
        val offline = CookOfflineSelectionCache(local, database.queue(), scope)
        val olderFile = File.createTempFile("active-shift-", ".jpg", context.cacheDir)
        val history = PilotDatabase.get(context).photoRecordDao()
        val oldContext = CookPhotoContextFactory.create(binding, binding, original, people[1], "one_shot", now.toInstant())
        val manualName = "Тестов " + UUID.randomUUID().toString().take(8)
        var addedId: String? = null
        var offlineId: String? = null
        try {
            olderFile.writeText("isolated photo bytes")
            val originalUploadId = PhotoHistoryStore.addQueued(context, olderFile.absolutePath, now.toInstant().toString(), oldContext,
                captureDeviceUuid = scope.deviceUuid, captureBackendUrl = scope.backendUrl)
            val intent = Intent(context, CookShiftActivity::class.java).putExtra("shift_id", original.id).putExtra("revision", 1)
                .putExtra("author_ids", arrayOf("cook-1", "cook-2")).putExtra("author_names", arrayOf("Повар 1", "Повар 2"))
                .putExtra("current_author", "cook-2").putExtra("test_offline", true)
            ActivityScenario.launch<CookShiftActivity>(intent).use {
                waitFor("Кто готовит дальше?")
                onView(withText("Добавить в состав")).perform(click())
                onView(withText("Повар")).inRoot(isDialog()).perform(click())
                onView(withText("Новый сотрудник")).inRoot(isDialog()).perform(click())
                onView(withHint("Имя сотрудника")).perform(replaceText("  ПОВАР 1  "), closeSoftKeyboard())
                onView(withText("Добавить")).inRoot(isDialog()).perform(click())
                onView(withHint("Имя сотрудника")).check(matches(hasErrorText(
                    "Такое имя уже есть. Для другого сотрудника укажите отличающееся имя.")))
                onView(withHint("Имя сотрудника")).perform(replaceText(manualName), closeSoftKeyboard())
                onView(withText("Добавить")).inRoot(isDialog()).perform(click())
                val deadline = SystemClock.elapsedRealtime() + 15_000
                while (offline.load() == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
                val updated = requireNotNull(offline.load())
                val person = updated.employees.single { employee -> employee.displayName == manualName }
                val pendingShift = requireNotNull(updated.offlineShift)
                addedId = person.id
                offlineId = pendingShift.provisionalId
                val pendingPerson = database.queue().pendingEmployees(scope.backendUrl, scope.deviceUuid, binding.branchRef, binding.revision)
                    .single { employee -> employee.name == manualName }
                assertEquals(person.id, pendingPerson.provisionalId)
                assertEquals(listOf("cook"), person.roles)
                assertEquals(original.cooks.toSet() + person.id, updated.shift.cooks.toSet())
                assertEquals(original.operators, updated.shift.operators)
                assertEquals(original.mainCookId, updated.shift.mainCookId)
                val author = JSONObject(requireNotNull(local.getString(CookShiftCache.AUTHOR_KEY, null)))
                assertEquals("cook-2", author.getString("employee"))
                assertEquals(updated.shift.id, author.getString("shift"))
                val oldPhoto = requireNotNull(history.getByPhotoPath(olderFile.absolutePath))
                assertEquals(originalUploadId, oldPhoto.id)
                assertTrue(oldPhoto.queuedInUploadQueue)
                assertEquals(oldContext, oldPhoto.photoContext)
                assertEquals("isolated photo bytes", olderFile.readText())
                val ref = CookOfflineSelectionCache.cookRef(pendingShift, person.id)
                val nextPhoto = JSONObject(CookPhotoContextFactory.createProvisional(scope, pendingShift, person, ref, "one_shot", Instant.now()))
                assertEquals("provisional", nextPhoto.getJSONObject("cookRef").getString("kind"))
                assertEquals(person.id, nextPhoto.getJSONObject("cookRef").getString("id"))
                assertEquals(manualName, nextPhoto.getString("cookNameSnapshot"))
            }
        } finally {
            offline.clear()
            history.deleteByPhotoPath(olderFile.absolutePath)
            olderFile.delete()
            offlineId?.let {
                database.openHelper.writableDatabase.execSQL("DELETE FROM offline_shifts WHERE provisionalId=?", arrayOf<Any>(it))
            }
            addedId?.let {
                database.openHelper.writableDatabase.execSQL("DELETE FROM offline_employees WHERE provisionalId=?", arrayOf<Any>(it))
            }
            local.edit().remove(CookShiftCache.AUTHOR_KEY).commit()
        }
    }

    @Test fun threeTraineesCanJoinAndCannotBePickedTwice() {
        ActivityScenario.launch<CookShiftActivity>(Intent(context, CookShiftActivity::class.java)).use {
            waitFor("Начать смену"); onView(withText("Начать смену")).perform(click())
            choose("6"); next(); choose("1"); next(); choose("1"); next(); choose("1"); next()
            waitFor("Сколько стажёров?"); choose("3"); next()
            choose("Оператор 1"); next(); choose("Повар 1"); next(); choose("Повар 2"); next()
            for (n in 1..3) {
                waitFor("Выберите стажёра $n")
                if (n > 1) onView(withText("Стажёр " + (n - 1))).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
                choose("Стажёр $n"); next()
            }
            onView(withText("Начать смену")).perform(scrollTo(), click())
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (CookLocalSession.cache(context).load() == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertEquals(listOf("trainee-1", "trainee-2", "trainee-3"), requireNotNull(CookLocalSession.cache(context).load()).shift.trainees)
        }
    }

    @Test fun traineeIsSelectedAfterHelpersAndStoredSeparately() {
        ActivityScenario.launch<CookShiftActivity>(Intent(context, CookShiftActivity::class.java)).use {
            waitFor("Начать смену"); onView(withText("Начать смену")).perform(click())
            choose("3"); next(); choose("1"); next(); choose("1"); next(); choose("0"); next(); choose("1"); next()
            waitFor("Выберите оператора 1"); choose("Оператор 1"); next()
            choose("Повар 1"); next()
            waitFor("Выберите стажёра 1"); choose("Стажёр 1"); next()
            onView(withText("Начать смену")).perform(scrollTo(), click())
            val until = SystemClock.elapsedRealtime() + 15_000
            while (CookLocalSession.cache(context).load() == null && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
            val shift = requireNotNull(CookLocalSession.cache(context).load()).shift
            assertEquals(listOf("trainee-1"), shift.trainees)
            assertEquals(listOf("cook-1"), shift.cooks)
        }
    }

    @Test fun helperIsStoredSeparatelyFromCook() {
        ActivityScenario.launch<CookShiftActivity>(Intent(context, CookShiftActivity::class.java)).use {
            waitFor("Начать смену"); onView(withText("Начать смену")).perform(click())
            waitFor("Сколько сотрудников?"); choose("3"); next()
            waitFor("Сколько операторов?"); choose("1"); next()
            waitFor("Сколько поваров?"); choose("1"); next()
            waitFor("Сколько помощников?"); choose("1"); next()
            waitFor("Сколько стажёров?"); choose("0"); next()
            waitFor("Выберите оператора 1"); choose("Оператор 1"); next()
            waitFor("Выберите повара 1"); choose("Повар 1"); next()
            waitFor("Выберите помощника 1"); choose("Повар 2"); next()
            waitFor("Проверьте состав")
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
                waitFor("Проверьте состав")
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
