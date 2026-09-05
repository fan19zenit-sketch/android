package com.example.simplephotouploader

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.DiffUtil
import java.io.File

class HistoryAdapter(
    private val onDeleteFromChat: (PhotoHistoryStore.Entry) -> Unit,
    private val onOpenPhoto: (PhotoHistoryStore.Entry) -> Unit,
    private val onRetry: (PhotoHistoryStore.Entry) -> Unit,
) : ListAdapter<PhotoHistoryStore.Entry, HistoryAdapter.ViewHolder>(object : DiffUtil.ItemCallback<PhotoHistoryStore.Entry>() {
    override fun areItemsTheSame(a: PhotoHistoryStore.Entry, b: PhotoHistoryStore.Entry) = a.id == b.id
    override fun areContentsTheSame(a: PhotoHistoryStore.Entry, b: PhotoHistoryStore.Entry) = a == b
}) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), onDeleteFromChat, onOpenPhoto, onRetry)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail = itemView.findViewById<ImageView>(R.id.ivThumb)
        private val title = itemView.findViewById<TextView>(R.id.tvHistoryTitle)
        private val subtitle = itemView.findViewById<TextView>(R.id.tvHistorySubtitle)
        private val status = itemView.findViewById<TextView>(R.id.tvHistoryStatus)
        private val deleteButton = itemView.findViewById<Button>(R.id.btnDeleteFromChat)
        private val retryButton = itemView.findViewById<Button>(R.id.btnRetry)

        fun bind(
            item: PhotoHistoryStore.Entry,
            onDeleteFromChat: (PhotoHistoryStore.Entry) -> Unit,
            onOpenPhoto: (PhotoHistoryStore.Entry) -> Unit,
            onRetry: (PhotoHistoryStore.Entry) -> Unit,
        ) {
            title.text = itemView.context.getString(
                R.string.history_item_time,
                PhotoHistoryStore.formatTimestamp(item.capturedAt) ?: "--:--"
            )

            val sentAt = PhotoHistoryStore.formatTimestamp(item.sentAt)
            subtitle.text = when {
                item.chatDeleted -> itemView.context.getString(R.string.history_deleted_from_chat)
                item.status == "sent" -> if (sentAt != null) {
                    itemView.context.getString(R.string.history_sent_at, sentAt)
                } else {
                    itemView.context.getString(R.string.history_status_sent)
                }
                item.status == "uploaded_to_server" -> itemView.context.getString(R.string.history_status_server)
                item.status == "sending_to_chat" -> itemView.context.getString(R.string.history_status_chat)
                item.status == "sending" -> itemView.context.getString(R.string.history_status_sending)
                item.status == "retrying" -> itemView.context.getString(R.string.history_status_retrying)
                item.error != null -> item.error
                else -> itemView.context.getString(R.string.history_waiting)
            }

            status.text = when (item.status) {
                "sent" -> if (item.chatDeleted) {
                    itemView.context.getString(R.string.history_status_deleted)
                } else {
                    itemView.context.getString(R.string.history_status_sent)
                }
                "queued" -> itemView.context.getString(R.string.history_status_queued)
                "sending" -> itemView.context.getString(R.string.history_status_sending)
                "uploaded_to_server" -> itemView.context.getString(R.string.history_status_server)
                "sending_to_chat" -> itemView.context.getString(R.string.history_status_chat)
                "retrying" -> itemView.context.getString(R.string.history_status_retrying)
                "error" -> itemView.context.getString(R.string.history_status_error)
                "needs_review" -> itemView.context.getString(R.string.history_needs_review)
                else -> item.status
            }

            val thumbFile = item.thumbnailPath?.let(::File)
            if (thumbFile != null && thumbFile.exists()) {
                val bitmap = BitmapFactory.decodeFile(thumbFile.absolutePath)
                thumbnail.setImageBitmap(bitmap)
            } else {
                thumbnail.setImageResource(android.R.drawable.ic_menu_report_image)
            }

            val canDeleteFromChat = item.status == "sent" &&
                !item.chatDeleted &&
                !item.jobId.isNullOrBlank() &&
                !item.messageId.isNullOrBlank()
            deleteButton.visibility = if (canDeleteFromChat) View.VISIBLE else View.GONE
            deleteButton.setOnClickListener {
                onDeleteFromChat(item)
            }
            retryButton?.visibility = if (item.jobId.isNullOrBlank() && !item.chatDeleted &&
                item.status in setOf("needs_review", "error", "retrying") && File(item.photoPath).exists()) View.VISIBLE else View.GONE
            retryButton?.setOnClickListener { onRetry(item) }
            status.setTextColor(android.graphics.Color.parseColor(when {
                item.status == "sent" && !item.chatDeleted -> "#25745B"
                item.status in setOf("error", "needs_review") -> "#B33C4A"
                else -> "#765725"
            }))
            itemView.setOnClickListener {
                onOpenPhoto(item)
            }
        }
    }
}
