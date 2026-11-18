package com.example.simplephotouploader

/** Labels do not claim failure when the server's delivery result is unknown. */
object HistoryDisplay {
    fun status(status: String, chatDeleted: Boolean): Int = when {
        chatDeleted -> R.string.history_status_deleted
        status == "sent" -> R.string.history_confirmed
        status == "queued" -> R.string.history_status_queued
        status == "sending" -> R.string.history_status_sending
        status == "uploaded_to_server" -> R.string.history_status_server
        status == "sending_to_chat" -> R.string.history_status_chat
        status == "retrying" -> R.string.history_status_retrying
        status == "error" -> R.string.history_not_sent
        else -> R.string.history_delivery_unknown
    }
}
