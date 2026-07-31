package com.example.simplephotouploader

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class PhotoViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_viewer)

        val imageView = findViewById<ImageView>(R.id.ivFullscreenPhoto)
        val metaText = findViewById<TextView>(R.id.tvPhotoMeta)
        val closeButton = findViewById<ImageButton>(R.id.btnCloseViewer)

        closeButton.setOnClickListener { finish() }

        val originalPath = intent.getStringExtra(EXTRA_PHOTO_PATH)
        val thumbPath = intent.getStringExtra(EXTRA_THUMBNAIL_PATH)
        val capturedAt = intent.getStringExtra(EXTRA_CAPTURED_AT)
        val status = intent.getStringExtra(EXTRA_STATUS).orEmpty()

        val imagePath = sequenceOf(originalPath, thumbPath)
            .filterNotNull()
            .firstOrNull { File(it).exists() }

        if (imagePath != null) {
            val bitmap = BitmapFactory.decodeFile(imagePath)
            imageView.setImageBitmap(bitmap)
        } else {
            imageView.setImageResource(android.R.drawable.ic_menu_report_image)
        }

        val timeText = PhotoHistoryStore.formatTimestamp(capturedAt) ?: "--:--"
        metaText.text = getString(R.string.history_item_time, timeText) + " • " + mapStatus(status)
    }

    private fun mapStatus(status: String): String {
        return when (status) {
            "sent" -> getString(R.string.history_status_sent)
            "queued" -> getString(R.string.history_status_queued)
            "sending" -> getString(R.string.history_status_sending)
            "uploaded_to_server" -> getString(R.string.history_status_server)
            "sending_to_chat" -> getString(R.string.history_status_chat)
            "retrying" -> getString(R.string.history_status_retrying)
            "error" -> getString(R.string.history_status_error)
            else -> status
        }
    }

    companion object {
        const val EXTRA_PHOTO_PATH = "extra_photo_path"
        const val EXTRA_THUMBNAIL_PATH = "extra_thumbnail_path"
        const val EXTRA_CAPTURED_AT = "extra_captured_at"
        const val EXTRA_STATUS = "extra_status"
    }
}
