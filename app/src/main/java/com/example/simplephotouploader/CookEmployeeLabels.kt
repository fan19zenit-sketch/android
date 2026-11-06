package com.example.simplephotouploader

import java.util.Locale

/** Keep identities separate even when two employees have identical display names. */
object CookEmployeeLabels {
    fun label(person: CookEmployee, roster: List<CookEmployee>): String {
        val name = person.displayName.trim().lowercase(Locale.ROOT)
        val matches = roster.filter { it.displayName.trim().lowercase(Locale.ROOT) == name }.sortedBy { it.id }
        if (matches.size < 2) return person.displayName
        val index = matches.indexOfFirst { it.id == person.id }
        return if (index < 0) person.displayName else "${person.displayName} · ${index + 1}"
    }
}
