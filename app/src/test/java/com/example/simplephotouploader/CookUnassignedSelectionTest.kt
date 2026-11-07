package com.example.simplephotouploader

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class CookUnassignedSelectionTest {
    private val binding = CookBinding("branch", 1, "Europe/Moscow")
    private val scope = "backend-device-city"
    private val beforeMidnight = Instant.parse("2026-11-05T20:59:59Z")

    @Test fun selectionExpiresAtBranchMidnightAndForOtherBindings() {
        val selection = CookUnassignedSelection()
        assertTrue(selection.select("explicit_unknown", binding, scope, beforeMidnight))
        assertEquals("explicit_unknown", selection.current(binding, scope, beforeMidnight)?.choice)
        assertNull(selection.current(binding, scope, beforeMidnight.plusSeconds(1)))
        assertNull(selection.current(binding, scope, beforeMidnight))
        for (other in listOf(binding.copy(branchRef = "other"), binding.copy(revision = 2), binding.copy(timezone = "UTC"))) {
            assertTrue(selection.select("explicit_unknown", binding, scope, beforeMidnight))
            assertNull(selection.current(other, scope, beforeMidnight))
            assertNull(selection.current(binding, scope, beforeMidnight))
        }
        assertTrue(selection.select("shared", binding, scope, beforeMidnight))
        assertNull(selection.current(binding, "other-device", beforeMidnight))
        assertNull(selection.current(binding, scope, beforeMidnight))
    }

    @Test fun failedCaptureKeepsSelectionAndOlderCaptureCannotConsumeNewChoice() {
        val selection = CookUnassignedSelection()
        assertTrue(selection.select("explicit_unknown", binding, scope, beforeMidnight))
        val first = requireNotNull(selection.current(binding, scope, beforeMidnight))
        selection.finish(first, false)
        assertEquals(first, selection.current(binding, scope, beforeMidnight))
        assertTrue(selection.select("shared", binding, scope, beforeMidnight))
        val second = requireNotNull(selection.current(binding, scope, beforeMidnight))
        selection.finish(first, true)
        assertEquals(second, selection.current(binding, scope, beforeMidnight))
        selection.finish(second, true)
        assertNull(selection.current(binding, scope, beforeMidnight))
    }

    @Test fun malformedOrMissingBindingDoesNotCreateSelection() {
        val selection = CookUnassignedSelection()
        assertFalse(selection.select("explicit_unknown", null, scope, beforeMidnight))
        assertFalse(selection.select("explicit_unknown", binding.copy(timezone = "bad zone"), scope, beforeMidnight))
        assertFalse(selection.select("employee", binding, scope, beforeMidnight))
        assertNull(selection.current(binding, scope, beforeMidnight))
    }
}
