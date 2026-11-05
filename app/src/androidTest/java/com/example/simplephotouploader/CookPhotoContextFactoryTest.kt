package com.example.simplephotouploader

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CookPhotoContextFactoryTest {
    private val binding = CookBinding("branch", 2)
    private val cook = CookEmployee("cook", "Повар", listOf("cook"))
    private val second = CookEmployee("second", "Второй", listOf("cook"))
    private val shift = CookShift("shift", 3, "branch", "2026-11-05", "Europe/Moscow",
        "2026-11-05T11:00:00+03:00", listOf("cook", "second"), listOf("operator"), "cook")
    private val time = Instant.parse("2026-11-05T08:05:00Z")

    private fun invalid(block: () -> Unit) {
        try { block(); fail("Expected invalid capture to be rejected") } catch (_: IllegalArgumentException) { }
    }

    @Test fun knownAndOneShotUseTheReservedSelection() {
        val first = JSONObject(CookPhotoContextFactory.create(binding, binding, shift, cook, "main_default", time))
        val next = JSONObject(CookPhotoContextFactory.create(binding, binding, shift, second, "one_shot", time))
        assertEquals("cook", first.getJSONObject("cookRef").getString("id"))
        assertEquals("second", next.getJSONObject("cookRef").getString("id"))
        assertEquals("Второй", next.getString("cookNameSnapshot"))
        assertEquals(time.toString(), next.getString("capturedAt"))
        assertEquals(3, next.getInt("shiftRevision"))
        assertEquals(2, next.getInt("bindingRevision"))
    }

    @Test fun unknownAndSharedNeverNameAnEmployee() {
        for (source in listOf("explicit_unknown", "shared")) {
            val result = JSONObject(CookPhotoContextFactory.create(binding, binding, shift, null, source, time))
            assertTrue(result.isNull("cookRef"))
            assertTrue(result.isNull("cookNameSnapshot"))
            invalid { CookPhotoContextFactory.create(binding, binding, shift, cook, source, time) }
        }
    }

    @Test fun wrongBranchDayCookOrFutureShiftCannotBeApplied() {
        invalid { CookPhotoContextFactory.create(CookBinding("other", 2), binding, shift, cook, "main_default", time) }
        invalid { CookPhotoContextFactory.create(CookBinding("branch", 3), binding, shift, cook, "main_default", time) }
        invalid { CookPhotoContextFactory.create(binding, binding, shift, cook, "main_default", time.plusSeconds(86400)) }
        invalid { CookPhotoContextFactory.create(binding, binding, shift, second, "main_default", time) }
        invalid { CookPhotoContextFactory.create(binding, binding, shift, cook, "one_shot", time) }
        invalid { CookPhotoContextFactory.create(binding, binding, shift, cook.copy(roles = listOf("operator")), "main_default", time) }
        invalid { CookPhotoContextFactory.create(binding, binding, shift, cook, "main_default", time.minusSeconds(600)) }
    }
}
