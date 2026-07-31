package com.example.simplephotouploader

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class HistoryAdapter(
    private val items: List<PhotoHistoryStore.Entry>,
    private val onDeleteFromChat: (PhotoHistoryStore.Entry) -> Unit,
    private val onOpenPhoto: (PhotoHistoryStore.Entry) -> Unit,
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_entry, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], onDeleteFromChat, onOpenPhoto)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail = itemView.findViewById<ImageView>(R.id.ivThumb)
        private val title = itemView.findViewById<TextView>(R.id.tvHistoryTitle)
        private val subtitle = itemView.findViewById<TextView>(R.id.tvHistorySubtitle)
        private val status = itemView.findViewById<TextView>(R.id.tvHistoryStatus)
        private val deleteButton = itemView.findViewById<Button>(R.id.btnDeleteFromChat)

        fun bind(
            item: PhotoHistoryStore.Entry,
            onDeleteFromChat: (PhotoHistoryStore.Entry) -> Unit,
            onOpenPhoto: (PhotoHistoryStore.Entry) -> Unit,
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
            itemView.setOnClickListener {
                onOpenPhoto(item)
            }
        }
    }
}
