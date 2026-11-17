package com.example.simplephotouploader

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryDisplayTest {
    @Test fun unknownDeliveryNeverClaimsItWasNotSent() {
        assertEquals(R.string.history_confirmed, HistoryDisplay.status("sent", false))
        assertEquals(R.string.history_delivery_unknown, HistoryDisplay.status("delivery_unknown", false))
        assertEquals(R.string.history_delivery_unknown, HistoryDisplay.status("needs_review", false))
        assertEquals(R.string.history_not_sent, HistoryDisplay.status("error", false))
        assertEquals(R.string.history_status_queued, HistoryDisplay.status("queued", false))
        assertEquals(R.string.history_status_deleted, HistoryDisplay.status("sent", true))
    }
}
