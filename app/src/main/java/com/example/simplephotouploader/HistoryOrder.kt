package com.example.simplephotouploader

/** Filter history without changing the records or their chronological order. */
object HistoryOrder {
    private val attentionStatuses = setOf("error", "needs_review", "delivery_unknown")
    enum class Filter { ALL, IN_PROGRESS, ATTENTION }

    fun needsAttention(status: String): Boolean = status in attentionStatuses

    fun matches(filter: Filter, status: String, queued: Boolean): Boolean = when (filter) {
        Filter.ALL -> true
        Filter.IN_PROGRESS -> queued || status in setOf("uploaded_to_server", "sending_to_chat")
        Filter.ATTENTION -> needsAttention(status)
    }
}
