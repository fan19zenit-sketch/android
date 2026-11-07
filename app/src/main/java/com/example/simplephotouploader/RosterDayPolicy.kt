package com.example.simplephotouploader

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Determines when a branch roster needs explicit confirmation for today. */
object RosterDayPolicy {
    fun requiresConfirmation(
        now: Instant,
        branchZone: ZoneId,
        currentBranch: String,
        confirmedBranch: String?,
        confirmedAt: Instant?,
    ): Boolean {
        val localNow = now.atZone(branchZone)
        if (localNow.toLocalTime() < LocalTime.of(11, 0)) return false
        if (currentBranch.isBlank() || currentBranch != confirmedBranch || confirmedAt == null) return true
        return confirmedAt.isAfter(now) || confirmedAt.atZone(branchZone).toLocalDate() != localNow.toLocalDate()
    }
}
