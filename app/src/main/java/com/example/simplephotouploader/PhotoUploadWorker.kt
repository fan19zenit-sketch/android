package com.example.simplephotouploader

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class PhotoUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "PhotoUploadWorker"
        private const val UNIQUE_WORK_NAME = "photo-upload-queue"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PhotoUploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }

    private val client = OkHttpClient.Builder().build()

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        val currentQueue = PhotoQueueStore.loadQueue(prefs)
        val cleanup = QueuedPhotoMaintenance.removeStaleEntries(
            currentQueue,
            ZoneId.systemDefault()
        )
        cleanup.removedPaths.forEach { removedPath ->
            PhotoQueueStore.dequeue(applicationContext, removedPath)
        }
        val queue = PhotoQueueStore.loadQueue(prefs).toMutableList()
        if (cleanup.removedCount > 0) {
            updateStatus(applicationContext.getString(R.string.status_stale_cleared, cleanup.removedCount))
        }
        if (queue.isEmpty()) {
            updateStatus(applicationContext.getString(R.string.status_ready))
            return Result.success()
        }

        val backendUrl = prefs.getString(AppPrefs.KEY_BACKEND_URL, AppPrefs.DEFAULT_BACKEND_URL)
            ?.trimEnd('/')
            .orEmpty()
        val deviceUuid = prefs.getString(AppPrefs.KEY_DEVICE_UUID, "").orEmpty()

        if (backendUrl.isEmpty() || deviceUuid.isEmpty()) {
            updateStatus(applicationContext.getString(R.string.status_error))
            return Result.failure()
        }

        updateStatus(applicationContext.getString(R.string.status_sending))

        for (entry in queue.toList()) {
            val file = File(entry)
            if (!file.exists()) {
                PhotoHistoryStore.removeByPhotoPath(applicationContext, entry)
                PhotoQueueStore.dequeue(applicationContext, entry)
                continue
            }

            PhotoHistoryStore.markSending(applicationContext, entry)
            val clientUploadId = PhotoHistoryStore.getByPhotoPath(applicationContext, entry)?.id.orEmpty()
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("deviceUuid", deviceUuid)
                .addFormDataPart("clientUploadId", clientUploadId)
                .addFormDataPart(
                    "photo",
                    file.name,
                    file.asRequestBody("image/jpeg".toMediaTypeOrNull())
                )
                .build()

            val request = Request.Builder()
                .url("$backendUrl/upload-v2")
                .post(body)
                .build()

            val outcome = try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 202 -> {
                            val responseBody = response.body?.string().orEmpty()
                            val jobId = JSONObject(responseBody).optJSONObject("job")?.optString("job_id").orEmpty()
                            if (jobId.isBlank()) {
                                UploadOutcome.PermanentFailure(null)
                            } else {
                                UploadOutcome.Accepted(jobId)
                            }
                        }
                        response.code >= 500 -> UploadOutcome.RetryableFailure
                        else -> UploadOutcome.PermanentFailure(null)
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Upload failed for ${file.absolutePath}", error)
                UploadOutcome.RetryableFailure
            }

            when (outcome) {
                is UploadOutcome.Accepted -> {
                    file.delete()
                    PhotoHistoryStore.markUploadedToServer(applicationContext, entry, outcome.jobId)
                    PhotoQueueStore.dequeue(applicationContext, entry)
                    updateStatus(applicationContext.getString(R.string.status_server_received))
                    UploadStatusSyncWorker.enqueueBurst(applicationContext)
                }
                UploadOutcome.RetryableFailure -> {
                    PhotoHistoryStore.markRetry(applicationContext, entry)
                    updateStatus(applicationContext.getString(R.string.status_retrying))
                    return Result.retry()
                }
                is UploadOutcome.PermanentFailure -> {
                    PhotoHistoryStore.markError(applicationContext, entry, outcome.error)
                    updateStatus(applicationContext.getString(R.string.status_error))
                    return Result.failure()
                }
            }
        }

        updateStatus(applicationContext.getString(R.string.status_ready))
        return Result.success()
    }

    private fun updateStatus(text: String) {
        applicationContext
            .getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(AppPrefs.KEY_LAST_STATUS, text)
            .apply()
    }

    private sealed class UploadOutcome {
        data class Accepted(val jobId: String) : UploadOutcome()
        object RetryableFailure : UploadOutcome()
        data class PermanentFailure(val error: String?) : UploadOutcome()
    }
}
