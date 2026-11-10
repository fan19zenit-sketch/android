package com.example.simplephotouploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CookShiftCacheTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("cook_shift_cache_test", Context.MODE_PRIVATE)
    private val cache = CookShiftCache(prefs)
    private val binding = CookBinding("branch", 1)
    private val cook = CookEmployee("cook", "Повар", listOf("cook"))
    private val operator = CookEmployee("operator", "Оператор", listOf("operator"))
    private val shift = CookShift("shift", 1, "branch", "2026-11-05", "Europe/Moscow",
        "2026-11-05T11:00:00+03:00", listOf("cook"), listOf("operator"), "cook")
    private val snapshot = CachedCookShift(binding, shift, listOf(cook, operator))

    @After fun cleanup() { prefs.edit().clear().commit() }

    @Test fun savedShiftSurvivesNewCacheInstanceButNotAChangedBindingOrDay() {
        assertTrue(cache.save(snapshot))
        val restored = CookShiftCache(prefs).load()!!
        assertEquals(snapshot, restored)
        val now = Instant.parse("2026-11-05T08:01:00Z")
        assertTrue(CookShiftPolicy.usable(restored.shift, binding, restored.binding, now))
        assertFalse(CookShiftPolicy.usable(restored.shift, CookBinding("branch", 2), restored.binding, now))
        assertFalse(CookShiftPolicy.usable(restored.shift, binding, restored.binding, now.plusSeconds(86400)))
    }

    @Test fun invalidSaveKeepsLastValidSnapshotAndCorruptionIsNotGuessed() {
        assertTrue(cache.save(snapshot))
        try { cache.save(snapshot.copy(employees = listOf(cook))); fail("Expected missing operator rejection") }
        catch (_: IllegalArgumentException) { }
        assertEquals(snapshot, cache.load())
        prefs.edit().putString("confirmed_shift_v1", "invalid").commit()
        assertNull(cache.load())
    }

    @Test fun deviceBindingAndRosterSurviveWithoutAnyConfirmedShift() {
        val device = CookDeviceRosterCache(prefs)
        val verified = CookBinding("branch", 1, "Europe/Moscow")
        assertTrue(device.save(verified, listOf(cook, operator)))
        assertNull(cache.load())
        assertEquals(CookDeviceRosterCache.Snapshot(verified, listOf(cook, operator)), CookDeviceRosterCache(prefs).load())
        assertTrue(device.saveBinding(verified))
        assertEquals(listOf(cook, operator), device.load()!!.employees)
        assertTrue(device.saveBinding(verified.copy(revision = 2)))
        assertEquals(emptyList<CookEmployee>(), device.load()!!.employees)
        prefs.edit().putString(CookDeviceRosterCache.KEY, "corrupted").commit()
        assertNull(device.load())
    }
}
