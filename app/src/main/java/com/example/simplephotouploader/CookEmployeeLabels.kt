package com.example.simplephotouploader

import java.util.Locale
import java.text.Normalizer

/** A number alone does not tell the user which person has a duplicated name. */
object CookEmployeeLabels {
    private fun normalized(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFKC)
        .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    fun ambiguous(person: CookEmployee, roster: List<CookEmployee>): Boolean =
        roster.any { it.id != person.id && normalized(it.displayName) == normalized(person.displayName) }

    fun label(person: CookEmployee, roster: List<CookEmployee>): String {
        return if (ambiguous(person, roster)) "${person.displayName} · уточнить имя" else person.displayName
    }
}
