package com.example.simplephotouploader

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A deliberate one-photo exception belongs to one branch and operational day. */
class CookUnassignedSelection {
    class Reservation internal constructor(
        val choice: String,
        internal val binding: CookBinding,
        internal val scope: String,
        internal val day: LocalDate,
        internal val revision: Long,
    )

    private var selection: Reservation? = null
    private var revision = 0L

    fun select(choice: String, binding: CookBinding?, scope: String, now: Instant): Boolean {
        if (choice !in setOf("explicit_unknown", "shared") || binding == null ||
            binding.branchRef.isBlank() || binding.revision < 1) return false
        val day = day(binding, now) ?: return false
        revision++
        selection = Reservation(choice, binding, scope, day, revision)
        return true
    }

    fun current(binding: CookBinding?, scope: String, now: Instant): Reservation? {
        val current = selection ?: return null
        if (binding == current.binding && scope == current.scope && day(current.binding, now) == current.day) return current
        clear()
        return null
    }

    fun finish(reservation: Reservation, saved: Boolean) {
        if (saved && selection == reservation) clear()
    }

    fun clear() {
        selection = null
        revision++
    }

    private fun day(binding: CookBinding, now: Instant): LocalDate? = try {
        now.atZone(ZoneId.of(requireNotNull(binding.timezone))).toLocalDate()
    } catch (_: Exception) {
        null
    }
}
