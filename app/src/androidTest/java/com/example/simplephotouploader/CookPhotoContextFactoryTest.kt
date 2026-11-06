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
            val noShift = JSONObject(CookPhotoContextFactory.createUnassigned(binding.copy(timezone = "Europe/Moscow"), source, time))
            assertTrue(noShift.isNull("shiftRef"))
            assertTrue(noShift.isNull("cookRef"))
            assertEquals(source, noShift.getString("selectionSource"))
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

    @Test fun provisionalSnapshotPreservesOfflineChoiceAndScope() {
        val provisionalCook = "11111111-1111-4111-8111-111111111111"
        val provisionalShift = "22222222-2222-4222-8222-222222222222"
        val scope = CookOfflineScope("https://example.test", "device", binding.copy(timezone = "Europe/Moscow"))
        val offline = OfflineCookShift(scope.backendUrl, scope.deviceUuid, "branch", 2, provisionalShift,
            "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444",
            "2026-11-05", "Europe/Moscow", "2026-11-05T11:00:00+03:00",
            "[{\"kind\":\"provisional\",\"id\":\"$provisionalCook\"},{\"kind\":\"employee\",\"id\":\"second\"}]",
            "[{\"kind\":\"employee\",\"id\":\"operator\"}]",
            "{\"kind\":\"provisional\",\"id\":\"$provisionalCook\"}")
        val first = CookEmployee(provisionalCook, "Повар", listOf("cook"))
        val selected = CookOfflineRef("provisional", provisionalCook)
        val snapshot = CookPhotoContextFactory.createProvisional(scope, offline, first, selected, "main_default", time)
        val data = JSONObject(snapshot)
        assertEquals("provisional", data.getJSONObject("shiftRef").getString("kind"))
        assertEquals(provisionalShift, data.getJSONObject("shiftRef").getString("id"))
        assertEquals(provisionalCook, data.getJSONObject("cookRef").getString("id"))
        assertEquals("Повар", data.getString("cookNameSnapshot"))
        assertEquals(snapshot, CookPhotoContextFactory.createProvisional(scope, offline.copy(canonicalRevision = 3), first, selected, "main_default", time))
        val oneShot = JSONObject(CookPhotoContextFactory.createProvisional(scope, offline, second,
            CookOfflineRef("employee", "second"), "one_shot", time))
        assertEquals("second", oneShot.getJSONObject("cookRef").getString("id"))
        invalid { CookPhotoContextFactory.createProvisional(scope.copy(deviceUuid = "other"), offline, first, selected, "main_default", time) }
        invalid { CookPhotoContextFactory.createProvisional(scope.copy(binding = binding.copy(revision = 3)), offline, first, selected, "main_default", time) }
        invalid { CookPhotoContextFactory.createProvisional(scope, offline, first, selected, "main_default", time.plusSeconds(86400)) }
        invalid { CookPhotoContextFactory.createProvisional(scope, offline, second, CookOfflineRef("employee", "second"), "main_default", time) }
    }
}
