package com.example.simplephotouploader

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CookOfflineSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        internal const val SYNC_STATE = "offline_shift_sync_state_v1"
        fun enqueue(context: Context, scope: CookOfflineScope, city: String) {
            require(city.isNotBlank() && scope.binding.branchRef.isNotBlank() && scope.binding.revision > 0)
            val identity = listOf(scope.backendUrl, scope.deviceUuid, city, scope.binding.branchRef,
                scope.binding.revision.toString()).joinToString("\u0000")
            val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val data = workDataOf("backend" to scope.backendUrl, "device" to scope.deviceUuid, "city" to city,
                "branch" to scope.binding.branchRef, "revision" to scope.binding.revision,
                "timezone" to scope.binding.timezone.orEmpty())
            val work = OneTimeWorkRequestBuilder<CookOfflineSyncWorker>().setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            CookLocalSession.preferences(context).edit().putString(SYNC_STATE, "waiting").commit()
            WorkManager.getInstance(context).enqueueUniqueWork("cook-offline-$hash", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }

        internal fun shouldRetry(error: IOException): Boolean = when (error) {
            is CookOfflineConflict, is CookApiProtocolException -> false
            is CookApiHttpException -> error.status == 408 || error.status == 429 || error.status >= 500
            else -> true
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (BuildConfig.FLAVOR != "clean") return@withContext Result.success()
        val backend = inputData.getString("backend").orEmpty()
        val device = inputData.getString("device").orEmpty()
        val city = inputData.getString("city").orEmpty()
        val branch = inputData.getString("branch").orEmpty()
        val revision = inputData.getInt("revision", 0)
        val timezone = inputData.getString("timezone").orEmpty().ifBlank { null }
        if (backend.isBlank() || device.isBlank() || city.isBlank() || branch.isBlank() || revision < 1)
            return@withContext Result.failure()
        val prefs = applicationContext.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(AppPrefs.KEY_BACKEND_URL, AppPrefs.DEFAULT_BACKEND_URL).orEmpty().trimEnd('/') != backend.trimEnd('/')
            || prefs.getString(AppPrefs.KEY_DEVICE_UUID, "").orEmpty() != device
            || prefs.getString(AppPrefs.KEY_CITY, "").orEmpty() != city) return@withContext Result.success()
        val scope = CookOfflineScope(backend, device, CookBinding(branch, revision, timezone))
        val syncState = CookLocalSession.preferences(applicationContext)
        try {
            val result = CookOfflineSync(CookOfflineDatabase.get(applicationContext).queue(), CookWorkflowClient(backend)).sync(scope)
            if (result.employees > 0 || result.shifts > 0) PhotoContextSyncWorker.enqueue(applicationContext)
            if (result.waitingShifts > 0) {
                syncState.edit().putString(SYNC_STATE, "waiting").commit()
                Result.retry()
            } else {
                syncState.edit().remove(SYNC_STATE).commit()
                Result.success()
            }
        } catch (error: IOException) {
            val retry = shouldRetry(error)
            syncState.edit().putString(SYNC_STATE, if (retry) "waiting" else "attention").commit()
            if (retry) Result.retry() else Result.failure()
        } catch (_: org.json.JSONException) {
            syncState.edit().putString(SYNC_STATE, "attention").commit()
            Result.failure()
        } catch (_: IllegalArgumentException) {
            syncState.edit().putString(SYNC_STATE, "attention").commit()
            Result.failure()
        }
    }
}
