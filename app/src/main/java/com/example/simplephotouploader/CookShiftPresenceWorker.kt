package com.example.simplephotouploader

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Report only locally observed state; no missing server record implies absence. */
class CookShiftPresenceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        private fun allowed() = BuildConfig.FLAVOR == "clean" && BuildConfig.BUILD_TYPE !in setOf("preview", "localtest")
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            if (!allowed()) return
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("cook-shift-presence", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CookShiftPresenceWorker>(15, TimeUnit.MINUTES).setConstraints(network).build())
        }

        fun enqueueNow(context: Context) {
            if (!allowed()) return
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<CookShiftPresenceWorker>()
                .setConstraints(network).build())
        }

        internal fun reportState(shiftState: String, invalidSelection: Boolean): String = when {
            invalidSelection -> "unknown"
            shiftState in setOf("offline_today", "cached_today", "pending") -> "confirmed"
            shiftState == "not_available" -> "unconfirmed"
            else -> "unknown"
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!allowed()) return@withContext Result.success()
        try {
            val scopeId = CookLocalSession.identity(applicationContext)
            val local = CookLocalSession.preferences(applicationContext)
            if (!local.getBoolean("workflow_enabled", false)) return@withContext Result.success()
            val cache = CookShiftCache(local).load()
            val roster = CookDeviceRosterCache(local).load()
            val binding = (if (local.contains(CookDeviceRosterCache.KEY)) roster?.binding else cache?.binding)
                ?: return@withContext Result.success()
            val zone = ZoneId.of(binding.timezone ?: return@withContext Result.success())
            val prefs = applicationContext.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
            val backend = prefs.getString(AppPrefs.KEY_BACKEND_URL, AppPrefs.DEFAULT_BACKEND_URL).orEmpty()
            val device = prefs.getString(AppPrefs.KEY_DEVICE_UUID, "").orEmpty()
            if (backend.isBlank() || device.isBlank()) return@withContext Result.success()
            val client = CookWorkflowClient(backend)
            if (client.capabilities(device)?.shiftPresence != true) return@withContext Result.success()
            val now = Instant.now()
            val dao = CookOfflineDatabase.get(applicationContext).queue()
            val state = RemoteSupport.cookShiftState(applicationContext, local, dao, now)
            val pending = CookPendingShiftStore(local).load()
            val invalid = (local.contains(CookOfflineSelectionCache.KEY) && state != "offline_today") ||
                (local.contains(CookShiftCache.KEY) && cache == null) ||
                (local.contains(CookPendingShiftStore.KEY) && (pending == null || pending.binding != binding || pending.shift.operationalDate != now.atZone(zone).toLocalDate().toString()))
            if (CookLocalSession.identity(applicationContext) != scopeId) return@withContext Result.success()
            client.reportShiftPresence(device, binding, now.atZone(zone).toLocalDate().toString(), reportState(state, invalid), now.toString())
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
