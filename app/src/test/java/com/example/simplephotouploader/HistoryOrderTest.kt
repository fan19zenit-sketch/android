package com.example.simplephotouploader

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryOrderTest {
    @Test fun `attention filter selects each issue type without changing order`() {
        val records = listOf("new-sent" to "sent", "new-error" to "error", "pending" to "queued",
            "older-review" to "needs_review", "old-unknown" to "delivery_unknown", "old-sent" to "sent")
        assertEquals(listOf("new-error", "older-review", "old-unknown"), records.filter {
            HistoryOrder.matches(HistoryOrder.Filter.ATTENTION, it.second, false)
        }.map { it.first })
    }

    @Test fun `all includes every status and progress includes queue and server pending`() {
        val records = listOf("sent", "queued", "uploaded_to_server", "sending_to_chat", "delivery_unknown")
        assertEquals(records, records.filter { HistoryOrder.matches(HistoryOrder.Filter.ALL, it, it == "queued") })
        assertEquals(listOf("queued", "uploaded_to_server", "sending_to_chat"), records.filter {
            HistoryOrder.matches(HistoryOrder.Filter.IN_PROGRESS, it, it == "queued")
        })
    }
}
