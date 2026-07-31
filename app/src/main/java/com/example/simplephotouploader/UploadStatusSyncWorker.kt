package com.example.simplephotouploader

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class UploadStatusSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val UNIQUE_WORK_NAME = "upload-status-sync"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadStatusSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun enqueueBurst(context: Context): Operation {
            val workManager = WorkManager.getInstance(context)
            val immediate = OneTimeWorkRequestBuilder<UploadStatusSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            val delayedShort = OneTimeWorkRequestBuilder<UploadStatusSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInitialDelay(2, TimeUnit.SECONDS)
                .build()
            val delayedLong = OneTimeWorkRequestBuilder<UploadStatusSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInitialDelay(6, TimeUnit.SECONDS)
                .build()

            return workManager.beginUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                immediate
            ).then(delayedShort).then(delayedLong).enqueue()
        }
    }

    private val client = OkHttpClient.Builder().build()

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        val backendUrl = prefs.getString(AppPrefs.KEY_BACKEND_URL, AppPrefs.DEFAULT_BACKEND_URL)
            ?.trimEnd('/')
            .orEmpty()
        val deviceUuid = prefs.getString(AppPrefs.KEY_DEVICE_UUID, "").orEmpty()

        if (backendUrl.isEmpty() || deviceUuid.isEmpty()) {
            return Result.success()
        }

        val request = Request.Builder()
            .url("$backendUrl/devices/$deviceUuid/recent-jobs?limit=10")
            .get()
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (_: Exception) {
            return Result.retry()
        }

        response.use {
            if (!it.isSuccessful) {
                return if (it.code >= 500) Result.retry() else Result.failure()
            }
            val body = it.body?.string().orEmpty()
            val json = JSONObject(body)
            val jobsArray = json.optJSONArray("jobs") ?: JSONArray()
            val jobs = mutableListOf<Map<String, String?>>()
            for (index in 0 until jobsArray.length()) {
                val obj = jobsArray.getJSONObject(index)
                jobs.add(
                    mapOf(
                        "job_id" to obj.optString("job_id"),
                        "status" to obj.optString("status"),
                        "sent_at" to obj.optString("sent_at").takeIf { value -> value.isNotBlank() && value != "null" },
                        "error" to obj.optString("error").takeIf { value -> value.isNotBlank() && value != "null" },
                        "message_id" to obj.optString("message_id").takeIf { value -> value.isNotBlank() && value != "null" },
                        "chat_deleted" to obj.optBoolean("chat_deleted", false).toString(),
                    )
                )
            }
            PhotoHistoryStore.syncStatuses(applicationContext, jobs)
            jobs.firstOrNull()?.get("status")?.let { latestStatus ->
                val text = when (latestStatus) {
                    "uploaded_to_server" -> applicationContext.getString(R.string.status_server_received)
                    "sending_to_chat" -> applicationContext.getString(R.string.history_status_chat)
                    "sent" -> applicationContext.getString(R.string.status_uploaded)
                    "failed" -> applicationContext.getString(R.string.status_error)
                    else -> null
                }
                if (text != null) {
                    prefs.edit().putString(AppPrefs.KEY_LAST_STATUS, text).apply()
                }
            }
            return Result.success()
        }
    }
}
