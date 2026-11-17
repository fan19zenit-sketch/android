package com.example.simplephotouploader

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class IssueSummaryTest {
    @Test fun `reports safe aggregates without including healthy photos`() {
        val data = listOf(
            IssueSummary.Fact("error", false, true, "2026-11-16T08:00:00Z"),
            IssueSummary.Fact("needs_review", false, false, "2026-11-15T08:00:00Z"),
            IssueSummary.Fact("delivery_unknown", true, true, "bad date"),
            IssueSummary.Fact("sent", true, true, "2026-11-16T08:00:00Z"),
        )
        val summary = IssueSummary.summarize(data, Instant.parse("2026-11-16T09:00:00Z"))
        assertEquals(3, summary["total"])
        assertEquals(1, summary["error"])
        assertEquals(1, summary["needs_review"])
        assertEquals(1, summary["delivery_unknown"])
        assertEquals(1, summary["with_server_job"])
        assertEquals(2, summary["without_server_job"])
        assertEquals(2, summary["file_present"])
        assertEquals(1, summary["file_missing"])
        assertEquals(1, summary["today"])
        assertEquals(1, summary["earlier"])
        assertEquals(1, summary["date_unknown"])
        assertEquals(false, summary.toString().contains("2026-11-15"))
    }
}
