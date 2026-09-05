package com.example.simplephotouploader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class PhotoUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        private const val UNIQUE_WORK_NAME = "photo-upload-queue"
        fun enqueue(context: Context): Operation {
            val request = OneTimeWorkRequestBuilder<PhotoUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            // Preserve a capture arriving while the previous worker finishes.
            return WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = "photo_upload_channel"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(channel,
                applicationContext.getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW))
        }
        return ForegroundInfo(1001, NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(applicationContext.getString(R.string.notification_title))
            .setContentText(applicationContext.getString(R.string.status_sending))
            .setOngoing(true).build())
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        val backend = prefs.getString(AppPrefs.KEY_BACKEND_URL, AppPrefs.DEFAULT_BACKEND_URL).orEmpty().trimEnd('/')
        val deviceId = prefs.getString(AppPrefs.KEY_DEVICE_UUID, "").orEmpty()
        if (backend.isBlank() || deviceId.isBlank()) return@withContext Result.failure()
        val dao = PilotDatabase.get(applicationContext).photoRecordDao()
        if (RemoteSupport.settings(applicationContext).uploadsPaused) {
            updateStatus(R.string.support_paused)
            if (dao.queuedCount() > 0) QueueRetryWorker.schedule(applicationContext, false).result.get()
            return@withContext Result.success()
        }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        for (queued in dao.getQueuedRecords()) {
            ensureActive()
            if (RemoteSupport.settings(applicationContext).uploadsPaused) break
            if (android.os.SystemClock.elapsedRealtime() - startedAt > 180_000) break
            val item = dao.getByPhotoPath(queued.photoPath) ?: continue
            if (!UploadPolicy.mayUpload(item.jobId, item.status, item.chatDeleted)) {
                dao.setQueuedState(item.photoPath, false)
                continue
            }
            if (UploadPolicy.needsDateApproval(item.capturedAt, item.manualRetryApproved)) {
                dao.update(item.copy(status = "needs_review", queuedInUploadQueue = false,
                    error = applicationContext.getString(R.string.history_older_photo)))
                continue
            }
            val file = File(item.photoPath)
            if (!file.exists()) {
                PhotoHistoryStore.markError(applicationContext, item.photoPath,
                    applicationContext.getString(R.string.history_file_missing))
                continue
            }
            PhotoHistoryStore.markSending(applicationContext, item.photoPath)
            updateStatus(R.string.status_sending)
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("deviceUuid", deviceId)
                .addFormDataPart("clientUploadId", item.id)
                .addFormDataPart("photo", file.name, file.asRequestBody("image/jpeg".toMediaType())).build()
            try {
                PhotoApi.client.newCall(Request.Builder().url("$backend/upload-v2").post(body).build())
                    .execute().use { response ->
                        if (response.code == 202) {
                            val job = runCatching { JSONObject(response.body?.string().orEmpty()).optJSONObject("job") }.getOrNull()
                            val jobId = job?.optString("job_id").orEmpty()
                            if (jobId.isBlank() || jobId == "null") throw IOException("Missing server acknowledgement")
                            // Commit acknowledgement before cleanup or scheduling other work.
                            dao.acknowledgeUpload(item.photoPath, jobId)
                            RemoteSupport.event(applicationContext, "upload_acknowledged")
                            updateStatus(R.string.status_server_received)
                            UploadStatusSyncWorker.enqueue(applicationContext)
                        } else if (UploadPolicy.retryableHttp(response.code)) {
                            RemoteSupport.event(applicationContext, "upload_http_${response.code}")
                            throw IOException("HTTP ${response.code}")
                        } else {
                            RemoteSupport.event(applicationContext, "upload_http_${response.code}")
                            PhotoHistoryStore.markError(applicationContext, item.photoPath,
                                applicationContext.getString(R.string.history_http_error, response.code))
                        }
                    }
            } catch (error: IOException) {
                if (!error.message.orEmpty().startsWith("HTTP ")) {
                    RemoteSupport.event(applicationContext, if (error is java.net.SocketTimeoutException)
                        "upload_timeout" else "upload_connection_error")
                }
                PhotoHistoryStore.markRetry(applicationContext, item.photoPath, error.message)
                updateStatus(R.string.status_retrying)
            }
        }
        if (dao.getQueuedPaths().isNotEmpty()) QueueRetryWorker.schedule(applicationContext, false).result.get()
        Result.success()
    }

    private fun updateStatus(resource: Int) {
        applicationContext.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE).edit()
            .putString(AppPrefs.KEY_LAST_STATUS, applicationContext.getString(resource)).apply()
    }
}
