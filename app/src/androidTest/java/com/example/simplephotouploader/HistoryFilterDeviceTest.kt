package com.example.simplephotouploader

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class HistoryFilterDeviceTest {
    @Test fun filtersShowOnlyCorrectRecordsWithoutChangingThem() {
        require(BuildConfig.APPLICATION_ID.endsWith(".preview")) { "Only use the isolated preview app" }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = PilotDatabase.get(context).photoRecordDao()
        val existing = dao.getAllHistory()
        existing.forEach { dao.deleteByPhotoPath(it.photoPath) }
        val now = Instant.now().toString()
        val error = PhotoRecordEntity("fixture-error", null, null, false, "/tmp/fixture-error.jpg", null,
            now, null, "error", "Проверка фильтра: ошибка", false)
        val queued = PhotoRecordEntity("fixture-queued", null, null, false, "/tmp/fixture-queued.jpg", null,
            now, null, "queued", null, true)
        val sent = PhotoRecordEntity("fixture-sent", "fixture-job", "fixture-mid", false, "/tmp/fixture-sent.jpg", null,
            now, now, "sent", null, false)
        listOf(error, queued, sent).forEach(dao::insert)
        try {
            ActivityScenario.launch(HistoryActivity::class.java).use { scenario ->
                fun waitCount(expected: Int) {
                    repeat(60) {
                        var count = -1
                        scenario.onActivity { count = it.findViewById<RecyclerView>(R.id.recyclerHistory).adapter!!.itemCount }
                        if (count == expected) return
                        Thread.sleep(100)
                    }
                    fail("Filter did not show $expected records")
                }
                waitCount(3)
                if (BuildConfig.FLAVOR == "clean") {
                    onView(withId(R.id.btnHistoryAttention)).check(matches(withEffectiveVisibility(Visibility.GONE)))
                    onView(withText("Проверка фильтра: ошибка")).check(matches(isDisplayed()))
                } else {
                    onView(withId(R.id.btnHistoryAttention)).perform(click())
                    waitCount(1)
                    onView(withText("Проверка фильтра: ошибка")).check(matches(isDisplayed()))
                }
                onView(withId(R.id.btnHistoryProgress)).perform(click())
                waitCount(1)
                if (BuildConfig.FLAVOR == "clean") onView(org.hamcrest.Matchers.allOf(withId(R.id.tvHistoryStatus),
                    withText(R.string.history_confirmed))).check(matches(isDisplayed()))
                else onView(withText(R.string.history_status_queued)).check(matches(isDisplayed()))
                onView(withId(R.id.btnHistoryAll)).perform(click())
                waitCount(3)
            }
            assertEquals(error, dao.getByPhotoPath(error.photoPath))
            assertEquals(queued, dao.getByPhotoPath(queued.photoPath))
            assertEquals(sent, dao.getByPhotoPath(sent.photoPath))
        } finally {
            listOf(error, queued, sent).forEach { dao.deleteByPhotoPath(it.photoPath) }
            existing.forEach(dao::insert)
        }
    }
}
