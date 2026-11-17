package com.example.simplephotouploader

import java.time.Instant
import java.time.ZoneId

/** Only aggregate facts are exported; never include identifiers, paths or raw error text. */
object IssueSummary {
    data class Fact(val status: String, val hasJob: Boolean, val fileExists: Boolean, val capturedAt: String)

    fun summarize(facts: List<Fact>, now: Instant = Instant.now()): Map<String, Int> {
        val keys = listOf("total", "error", "needs_review", "delivery_unknown", "with_server_job",
            "without_server_job", "file_present", "file_missing", "today", "earlier", "date_unknown")
        val counts = keys.associateWith { 0 }.toMutableMap()
        val zone = ZoneId.of("Europe/Moscow")
        val today = now.atZone(zone).toLocalDate()
        fun add(key: String) { counts[key] = counts.getValue(key) + 1 }
        facts.filter { it.status in setOf("error", "needs_review", "delivery_unknown") }.forEach { fact ->
            add("total")
            add(fact.status)
            add(if (fact.hasJob) "with_server_job" else "without_server_job")
            add(if (fact.fileExists) "file_present" else "file_missing")
            val date = runCatching { Instant.parse(fact.capturedAt).atZone(zone).toLocalDate() }.getOrNull()
            add(when {
                date == today -> "today"
                date != null && date.isBefore(today) -> "earlier"
                else -> "date_unknown"
            })
        }
        return counts
    }
}
