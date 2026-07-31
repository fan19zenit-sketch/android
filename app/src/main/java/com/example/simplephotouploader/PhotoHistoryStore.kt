package com.example.simplephotouploader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

object PhotoHistoryStore {
    private const val THUMB_SIZE = 320

    data class Entry(
        val id: String,
        val jobId: String?,
        val messageId: String?,
        val chatDeleted: Boolean,
        val photoPath: String,
        val thumbnailPath: String?,
        val capturedAt: String,
        val sentAt: String?,
        val status: String,
        val error: String?,
        val queuedInUploadQueue: Boolean,
    )

    fun addQueued(context: Context, photoPath: String): String {
        val dao = PilotDatabase.get(context).photoRecordDao()
        val existing = dao.getByPhotoPath(photoPath)
        if (existing != null) {
            dao.update(existing.copy(status = "queued", error = null, queuedInUploadQueue = true))
            return existing.id
        }

        val id = UUID.randomUUID().toString()
        val thumbnailPath = createThumbnail(context, photoPath, id)
        dao.insert(
            PhotoRecordEntity(
                id = id,
                jobId = null,
                messageId = null,
                chatDeleted = false,
                photoPath = photoPath,
                thumbnailPath = thumbnailPath,
                capturedAt = Instant.now().toString(),
                sentAt = null,
                status = "queued",
                error = null,
                queuedInUploadQueue = true,
            )
        )
        return id
    }

    fun markSending(context: Context, photoPath: String) = updateByPhotoPath(context, photoPath) {
        it.copy(status = "sending", error = null)
    }

    fun markUploadedToServer(context: Context, photoPath: String, jobId: String) = updateByPhotoPath(context, photoPath) {
        it.copy(jobId = jobId, status = "uploaded_to_server", error = null, queuedInUploadQueue = false)
    }

    fun markSent(context: Context, photoPath: String) = updateByPhotoPath(context, photoPath) {
        it.copy(status = "sent", sentAt = Instant.now().toString(), error = null, queuedInUploadQueue = false)
    }

    fun markRetry(context: Context, photoPath: String, error: String? = null) = updateByPhotoPath(context, photoPath) {
        it.copy(status = "retrying", error = error, queuedInUploadQueue = true)
    }

    fun markError(context: Context, photoPath: String, error: String? = null) = updateByPhotoPath(context, photoPath) {
        it.copy(status = "error", error = error, queuedInUploadQueue = false)
    }

    fun removeByPhotoPath(context: Context, photoPath: String) {
        val dao = PilotDatabase.get(context).photoRecordDao()
        val item = dao.getByPhotoPath(photoPath) ?: return
        item.thumbnailPath?.let { File(it).delete() }
        dao.deleteByPhotoPath(photoPath)
    }

    fun markChatDeleted(context: Context, jobId: String) {
        val dao = PilotDatabase.get(context).photoRecordDao()
        val item = dao.getByJobId(jobId) ?: return
        dao.update(item.copy(messageId = null, chatDeleted = true))
    }

    fun list(context: Context): List<Entry> {
        return PilotDatabase.get(context)
            .photoRecordDao()
            .getAllHistory()
            .map(::toEntry)
    }

    fun getByPhotoPath(context: Context, photoPath: String): Entry? {
        val entity = PilotDatabase.get(context).photoRecordDao().getByPhotoPath(photoPath) ?: return null
        return toEntry(entity)
    }

    fun syncStatuses(
        context: Context,
        jobs: List<Map<String, String?>>,
    ) {
        val dao = PilotDatabase.get(context).photoRecordDao()
        for (job in jobs) {
            val jobId = job["job_id"] ?: continue
            val item = dao.getByJobId(jobId) ?: continue
            val mappedStatus = when (job["status"]) {
                "uploaded_to_server" -> "uploaded_to_server"
                "sending_to_chat" -> "sending_to_chat"
                "sent" -> "sent"
                "failed" -> "error"
                else -> item.status
            }
            dao.update(
                item.copy(
                    status = mappedStatus,
                    sentAt = when {
                        mappedStatus == "sent" -> job["sent_at"] ?: item.sentAt ?: Instant.now().toString()
                        else -> job["sent_at"] ?: item.sentAt
                    },
                    error = job["error"] ?: item.error,
                    messageId = job["message_id"] ?: item.messageId,
                    chatDeleted = job["chat_deleted"] == "true",
                    queuedInUploadQueue = false,
                )
            )
        }
    }

    fun formatTimestamp(iso: String?): String? {
        if (iso.isNullOrBlank()) return null
        return runCatching {
            DateTimeFormatter.ofPattern("dd.MM HH:mm")
                .format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
        }.getOrNull()
    }

    fun migrateFromLegacyPrefsIfNeeded(context: Context) {
        val dao = PilotDatabase.get(context).photoRecordDao()
        if (dao.count() > 0) {
            return
        }

        val prefs = context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        val historyRaw = prefs.getString(AppPrefs.KEY_HISTORY, "[]") ?: "[]"
        val queueRaw = prefs.getString(AppPrefs.KEY_QUEUE, "[]") ?: "[]"
        val queuedPaths = mutableSetOf<String>()
        val queueArray = JSONArray(queueRaw)
        for (index in 0 until queueArray.length()) {
            queueArray.optString(index)?.takeIf { it.isNotBlank() }?.let(queuedPaths::add)
        }

        val historyArray = JSONArray(historyRaw)
        for (index in 0 until historyArray.length()) {
            val obj = historyArray.getJSONObject(index)
            val path = obj.getString("photo_path")
            dao.insert(
                PhotoRecordEntity(
                    id = obj.getString("id"),
                    jobId = obj.optString("job_id").takeIf { it.isNotBlank() && it != "null" },
                    messageId = obj.optString("message_id").takeIf { it.isNotBlank() && it != "null" },
                    chatDeleted = obj.optBoolean("chat_deleted", false),
                    photoPath = path,
                    thumbnailPath = obj.optString("thumbnail_path").takeIf { it.isNotBlank() && it != "null" },
                    capturedAt = obj.getString("captured_at"),
                    sentAt = obj.optString("sent_at").takeIf { it.isNotBlank() && it != "null" },
                    status = obj.getString("status"),
                    error = obj.optString("error").takeIf { it.isNotBlank() && it != "null" },
                    queuedInUploadQueue = path in queuedPaths,
                )
            )
        }
    }

    private fun updateByPhotoPath(context: Context, photoPath: String, transform: (PhotoRecordEntity) -> PhotoRecordEntity) {
        val dao = PilotDatabase.get(context).photoRecordDao()
        val item = dao.getByPhotoPath(photoPath) ?: return
        dao.update(transform(item))
    }

    private fun createThumbnail(context: Context, photoPath: String, id: String): String? {
        val source = File(photoPath)
        if (!source.exists()) return null

        val thumbDir = File(context.filesDir, "history_thumbs")
        if (!thumbDir.exists()) {
            thumbDir.mkdirs()
        }

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photoPath, opts)
        var sampleSize = 1
        while (opts.outWidth / sampleSize > THUMB_SIZE || opts.outHeight / sampleSize > THUMB_SIZE) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeFile(photoPath, decodeOpts) ?: return null
        val target = File(thumbDir, "$id.jpg")
        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
        }
        bitmap.recycle()
        return target.absolutePath
    }

    private fun toEntry(entity: PhotoRecordEntity): Entry {
        return Entry(
            id = entity.id,
            jobId = entity.jobId,
            messageId = entity.messageId,
            chatDeleted = entity.chatDeleted,
            photoPath = entity.photoPath,
            thumbnailPath = entity.thumbnailPath,
            capturedAt = entity.capturedAt,
            sentAt = entity.sentAt,
            status = entity.status,
            error = entity.error,
            queuedInUploadQueue = entity.queuedInUploadQueue,
        )
    }
}
