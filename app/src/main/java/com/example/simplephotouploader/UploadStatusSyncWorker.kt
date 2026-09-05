package com.example.simplephotouploader

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class UploadStatusSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        fun enqueue(context: Context): Operation = WorkManager.getInstance(context).enqueueUniqueWork(
            "upload-status-sync", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<UploadStatusSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
        )
        fun enqueueBurst(context: Context): Operation = enqueue(context)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        val backend = prefs.getString(AppPrefs.KEY_BACKEND_URL, AppPrefs.DEFAULT_BACKEND_URL).orEmpty().trimEnd('/')
        val dao = PilotDatabase.get(applicationContext).photoRecordDao()
        var retry = false
        val startedAt = android.os.SystemClock.elapsedRealtime()
        // Poll every unresolved job by ID; the last-ten list loses older queued photos.
        for (item in dao.getPendingServerRecords().distinctBy { it.jobId }) {
            ensureActive()
            if (android.os.SystemClock.elapsedRealtime() - startedAt > 180_000) break
            try {
                PhotoApi.client.newCall(Request.Builder().url("$backend/upload-jobs/${item.jobId}").build())
                    .execute().use { response ->
                        if (response.code == 404) {
                            PhotoHistoryStore.markServerNeedsReview(applicationContext, item.jobId!!,
                                applicationContext.getString(R.string.history_server_unknown))
                        } else if (response.isSuccessful) {
                            val payload = JSONObject(response.body?.string().orEmpty())
                            val job = payload.optJSONObject("job") ?: payload
                            if (job.optString("job_id") != item.jobId) throw IOException("Unexpected server job")
                            val values = listOf("job_id", "status", "sent_at", "error", "message_id", "chat_deleted")
                                .associateWith { key -> job.optString(key).takeIf { it.isNotBlank() && it != "null" } }
                            PhotoHistoryStore.syncStatuses(applicationContext, listOf(values))
                        } else if (UploadPolicy.retryableHttp(response.code)) {
                            retry = true
                        } else {
                            PhotoHistoryStore.markServerNeedsReview(applicationContext, item.jobId!!,
                                applicationContext.getString(R.string.history_http_error, response.code))
                        }
                    }
            } catch (error: IOException) {
                retry = true
            } catch (error: org.json.JSONException) {
                retry = true
            }
        }
        if (retry || dao.getPendingServerRecords().isNotEmpty()) {
            QueueRetryWorker.schedule(applicationContext, true).result.get()
        }
        Result.success()
    }
}
