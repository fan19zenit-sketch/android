package com.example.simplephotouploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CookPendingShiftTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("cook_pending_test", Context.MODE_PRIVATE)
    @After fun cleanup() { prefs.edit().clear().commit() }

    @Test fun timeoutRetryKeepsTheExactRequestAndSnapshot() {
        val binding = CookBinding("branch", 3, "Europe/Moscow")
        val shift = CookShift("shift-id", 0, "branch", "2026-11-05", "Europe/Moscow",
            "2026-11-05T11:00:00+03:00", listOf("cook"), listOf("operator"), "cook")
        val pending = PendingCookShift("request-id", binding, shift)
        assertTrue(CookPendingShiftStore(prefs).save(pending))
        assertEquals(pending, CookPendingShiftStore(prefs).load())
        CookPendingShiftStore(prefs).clear()
        assertNull(CookPendingShiftStore(prefs).load())
    }

    @Test fun employeeRetryKeepsItsRequestAndBinding() {
        val value = PendingCookEmployee("request", CookBinding("branch", 3, "Europe/Moscow"), "Повар", listOf("cook", "operator"))
        assertTrue(CookPendingEmployeeStore(prefs).save(value))
        assertEquals(value, CookPendingEmployeeStore(prefs).load())
        CookPendingEmployeeStore(prefs).clear()
        assertNull(CookPendingEmployeeStore(prefs).load())
    }
}
