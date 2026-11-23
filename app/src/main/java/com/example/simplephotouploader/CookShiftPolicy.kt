package com.example.simplephotouploader

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

object CookShiftPolicy {
    private val reminderTime = LocalTime.of(11, 0)

    /** Never infer today's attribution from yesterday's shift or another city. */
    fun usable(shift: CookShift?, binding: CookBinding?, confirmedBinding: CookBinding?, now: Instant): Boolean {
        if (shift == null || binding == null || confirmedBinding != binding || binding.revision < 1 || shift.revision < 1 ||
            binding.branchRef.isBlank() || shift.branchRef != binding.branchRef ||
            (binding.timezone != null && binding.timezone != shift.timezone)) return false
        return try {
            val zone = ZoneId.of(shift.timezone)
            val confirmed = OffsetDateTime.parse(shift.confirmedAt).toInstant()
            LocalDate.parse(shift.operationalDate) == now.atZone(zone).toLocalDate() &&
                confirmed <= now && shift.cooks.isNotEmpty() &&
                shift.mainCookId in shift.cooks
        } catch (_: Exception) {
            false
        }
    }

    internal fun newerConfirmedShift(local: CookShift, candidates: List<CookShift>, binding: CookBinding, now: Instant): CookShift? {
        if (!usable(local, binding, binding, now)) return null
        val localTime = OffsetDateTime.parse(local.confirmedAt).toInstant()
        return candidates.asSequence()
            .filter { usable(it, binding, binding, now) && it.timezone == local.timezone }
            .map { it to OffsetDateTime.parse(it.confirmedAt).toInstant() }
            .filter { (shift, confirmed) ->
                if (shift.id == local.id) shift.revision > local.revision && confirmed >= localTime
                else confirmed > localTime
            }
            .maxWithOrNull(compareBy<Pair<CookShift, Instant>> { it.second }.thenBy { it.first.revision })
            ?.first
    }

    /** Caller supplies the branch timezone, not the phone's current timezone. */
    fun needsConfirmation(shift: CookShift?, binding: CookBinding?, confirmedBinding: CookBinding?, branchTimezone: String, now: Instant): Boolean {
        val localTime = try { now.atZone(ZoneId.of(branchTimezone)).toLocalTime() } catch (_: Exception) { return true }
        if (localTime < reminderTime) return false
        return shift?.timezone != branchTimezone || !usable(shift, binding, confirmedBinding, now)
    }
}
