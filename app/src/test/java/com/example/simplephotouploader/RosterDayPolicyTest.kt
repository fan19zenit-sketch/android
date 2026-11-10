package com.example.simplephotouploader

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class RosterDayPolicyTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private fun at(value: String) = Instant.parse(value)

    @Test fun thresholdAndDailyConfirmation() {
        val yesterday = at("2026-11-04T09:00:00Z")
        assertFalse(RosterDayPolicy.requiresConfirmation(at("2026-11-05T07:59:00Z"), zone, "a", "a", yesterday))
        assertTrue(RosterDayPolicy.requiresConfirmation(at("2026-11-05T08:00:00Z"), zone, "a", "a", yesterday))
        assertFalse(RosterDayPolicy.requiresConfirmation(at("2026-11-05T09:00:00Z"), zone, "a", "a", at("2026-11-05T08:01:00Z")))
    }

    @Test fun changingBranchOrFutureConfirmationRequiresConfirmation() {
        val now = at("2026-11-05T09:00:00Z")
        assertTrue(RosterDayPolicy.requiresConfirmation(now, zone, "b", "a", at("2026-11-05T08:00:00Z")))
        assertTrue(RosterDayPolicy.requiresConfirmation(now, zone, "a", "a", at("2026-11-05T10:00:00Z")))
        assertTrue(RosterDayPolicy.requiresConfirmation(now, zone, "a", null, null))
    }
}
