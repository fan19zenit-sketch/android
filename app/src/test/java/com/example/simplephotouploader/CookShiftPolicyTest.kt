package com.example.simplephotouploader

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CookShiftPolicyTest {
    @Test fun cameraIncludesBothOnDutyTraineesWithoutPromotingThem() {
        val team = shift.copy(trainees = listOf("trainee-a", "trainee-b"), assistants = listOf("assistant"))
        org.junit.Assert.assertEquals(listOf("cook", "operator", "trainee-a", "trainee-b"), team.photoAuthors(true, false))
        org.junit.Assert.assertEquals(listOf("cook", "trainee-a", "trainee-b"), team.photoAuthors(false, false))
        org.junit.Assert.assertEquals(listOf("cook"), team.photoAuthors(true, true))
        org.junit.Assert.assertEquals(listOf("cook"), team.cooks)
    }
    private val binding = CookBinding("branch", 1)
    private val shift = CookShift("shift", 1, "branch", "2026-11-05", "Europe/Moscow",
        "2026-11-05T11:01:00+03:00", listOf("cook"), listOf("operator"), "cook")

    @Test fun reminderStartsAtElevenInBranchTimezone() {
        assertFalse(CookShiftPolicy.needsConfirmation(null, binding, null, "Europe/Moscow", Instant.parse("2026-11-05T07:59:59Z")))
        assertTrue(CookShiftPolicy.needsConfirmation(null, binding, null, "Europe/Moscow", Instant.parse("2026-11-05T08:00:00Z")))
        assertFalse(CookShiftPolicy.needsConfirmation(shift, binding, binding, "Europe/Moscow", Instant.parse("2026-11-05T08:02:00Z")))
    }

    @Test fun oldOtherBranchAndFutureShiftCannotBeUsed() {
        val now = Instant.parse("2026-11-05T08:02:00Z")
        assertTrue(CookShiftPolicy.usable(shift, binding, binding, now))
        assertFalse(CookShiftPolicy.usable(shift, CookBinding("another", 1), binding, now))
        assertFalse(CookShiftPolicy.usable(shift, CookBinding("branch", 2), binding, now))
        assertFalse(CookShiftPolicy.usable(shift, binding, binding, now.plusSeconds(86400)))
        assertFalse(CookShiftPolicy.usable(shift, binding, binding, now.minusSeconds(180)))
        assertTrue(CookShiftPolicy.needsConfirmation(shift, binding, binding, "unknown/timezone", now))
    }

    @Test fun shiftWithTwoCooksAndNoOperatorIsUsableButMainCookRemainsRequired() {
        val now = Instant.parse("2026-11-05T08:02:00Z")
        val twoCooks = shift.copy(cooks = listOf("cook", "second"), operators = emptyList())
        assertTrue(CookShiftPolicy.usable(twoCooks, binding, binding, now))
        assertFalse(CookShiftPolicy.usable(twoCooks.copy(mainCookId = "not-on-shift"), binding, binding, now))
        assertFalse(CookShiftPolicy.usable(twoCooks.copy(cooks = emptyList()), binding, binding, now))
    }

    @Test fun serverRecoveryChoosesNewerConfirmationEvenWhenBothShiftRevisionsAreOne() {
        val now = Instant.parse("2026-11-05T10:00:00Z")
        val replacement = shift.copy(id = "replacement", confirmedAt = "2026-11-05T09:42:00Z",
            cooks = listOf("replacement-cook"), mainCookId = "replacement-cook")
        val wrongBranch = replacement.copy(id = "other", branchRef = "other", confirmedAt = "2026-11-05T09:59:00Z")
        val future = replacement.copy(id = "future", confirmedAt = "2026-11-05T10:01:00Z")
        val previousDay = replacement.copy(id = "yesterday", operationalDate = "2026-11-04")
        assertEquals(replacement, CookShiftPolicy.newerConfirmedShift(shift,
            listOf(replacement, wrongBranch, shift, future, previousDay), binding, now))
        assertNull(CookShiftPolicy.newerConfirmedShift(replacement, listOf(shift), binding, now))
    }

    @Test fun recoveryAcceptsSameShiftRevisionOnlyWhenItAdvances() {
        val now = Instant.parse("2026-11-05T10:00:00Z")
        val sameTimeNewRevision = shift.copy(revision = 2)
        assertEquals(sameTimeNewRevision, CookShiftPolicy.newerConfirmedShift(shift, listOf(sameTimeNewRevision), binding, now))
        val newerTimeOldRevision = shift.copy(confirmedAt = "2026-11-05T09:42:00Z")
        assertNull(CookShiftPolicy.newerConfirmedShift(sameTimeNewRevision, listOf(newerTimeOldRevision), binding, now))
    }
}
