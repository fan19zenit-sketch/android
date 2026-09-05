package com.example.simplephotouploader

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit

object RemoteSupport {
    const val BASE_URL = "https://194-55-235-241.sslip.io/mobile-support"
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    fun prefs(context: Context) = context.getSharedPreferences("remote_support", Context.MODE_PRIVATE)
    private fun boot(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    fun settings(context: Context): SupportSettings {
        val prefs = prefs(context)
        val ttl = prefs.getLong("ttl", 0)
        if (!SupportPolicy.cacheValid(prefs.getLong("received", 0), SystemClock.elapsedRealtime(), ttl,
                prefs.getInt("boot", -1), boot(context))) return SupportSettings()
        return SupportPolicy.settings(prefs.getBoolean("volume", true), prefs.getLong("retry", 30),
            prefs.getBoolean("paused", false), ttl)
    }

    fun event(context: Context, code: String) {
        if (!Regex("[a-z0-9_]{1,40}").matches(code)) return
        prefs(context).edit().putString("event", code).putLong("event_at", System.currentTimeMillis()).apply()
    }

    fun schedule(context: Context) {
        // Preview builds and local mock servers must never report as a production phone.
        if (BuildConfig.APPLICATION_ID.endsWith(".preview")) return
        val backend = context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
            .getString(AppPrefs.KEY_BACKEND_URL, AppPrefs.DEFAULT_BACKEND_URL)
        if (backend != AppPrefs.DEFAULT_BACKEND_URL && backend != AppPrefs.LEGACY_BACKEND_URL) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("support-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RemoteSupportWorker>(15, TimeUnit.MINUTES).setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES).build())
        check(context)
    }

    fun check(context: Context, force: Boolean = false): Operation =
        WorkManager.getInstance(context).enqueueUniqueWork("support-check", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RemoteSupportWorker>().setInputData(workDataOf("force" to force))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES).build())

    @Synchronized
    private fun identity(context: Context): Properties {
        val file = File(context.noBackupFilesDir, "support-identity.properties")
        if (file.exists()) return Properties().apply { file.inputStream().use { load(it) } }
        val value = Properties().apply { setProperty("installation", UUID.randomUUID().toString()) }
        saveIdentity(context, value)
        return value
    }

    @Synchronized
    private fun saveIdentity(context: Context, value: Properties) {
        val atomic = android.util.AtomicFile(File(context.noBackupFilesDir, "support-identity.properties"))
        val out = atomic.startWrite()
        try { value.store(out, null); atomic.finishWrite(out) }
        catch (error: Exception) { atomic.failWrite(out); throw error }
    }

    fun diagnostics(context: Context): JSONObject {
        val dao = PilotDatabase.get(context).photoRecordDao()
        val prefs = prefs(context)
        val oldest = dao.oldestQueuedAt()?.let { runCatching {
            (Instant.now().epochSecond - Instant.parse(it).epochSecond).coerceIn(0, 1_000_000_000)
        }.getOrDefault(0) } ?: 0
        return JSONObject().put("package", BuildConfig.APPLICATION_ID)
            .put("version_name", BuildConfig.VERSION_NAME).put("version_code", BuildConfig.VERSION_CODE)
            .put("manufacturer", Build.MANUFACTURER.take(80)).put("model", Build.MODEL.take(100))
            .put("sdk", Build.VERSION.SDK_INT).put("page_size", Os.sysconf(OsConstants._SC_PAGESIZE))
            .put("free_mb", context.filesDir.usableSpace / 1048576)
            .put("queued", dao.queuedCount()).put("pending", dao.pendingCount()).put("issues", dao.issueCount())
            .put("oldest_queue_seconds", oldest).put("last_event", prefs.getString("event", ""))
            .put("last_event_at", prefs.getLong("event_at", 0))
    }

    internal fun post(path: String, body: JSONObject, token: String? = null,
                      transport: OkHttpClient = client, endpoint: String = BASE_URL): JSONObject {
        val request = Request.Builder().url("$endpoint/$path")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (token != null) request.header("Authorization", "Bearer $token")
        return transport.newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("support_http_${response.code}")
            val source = response.body?.source() ?: throw IOException("support_empty")
            source.request(8193)
            val data = source.buffer.readByteArray(minOf(source.buffer.size, 8193))
            if (data.size > 8192) throw IOException("support_too_large")
            JSONObject(String(data, Charsets.UTF_8))
        }
    }

    fun sync(context: Context, force: Boolean) {
        if (BuildConfig.APPLICATION_ID.endsWith(".preview")) return
        val app = context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        val backend = app.getString(AppPrefs.KEY_BACKEND_URL, AppPrefs.DEFAULT_BACKEND_URL)
        if (backend != AppPrefs.DEFAULT_BACKEND_URL && backend != AppPrefs.LEGACY_BACKEND_URL) return
        val device = app.getString(AppPrefs.KEY_DEVICE_UUID, "").orEmpty()
        if (device.isBlank()) return
        val prefs = prefs(context)
        if (!force && SupportPolicy.cacheValid(prefs.getLong("last_sync_elapsed", 0), SystemClock.elapsedRealtime(),
                300, prefs.getInt("last_sync_boot", -1), boot(context))) return
        val identity = identity(context)
        if (identity.getProperty("device") != device || identity.getProperty("token").isNullOrBlank()) {
            val response = post("enroll", JSONObject().put("device", device)
                .put("installation", identity.getProperty("installation")))
            val token = response.getString("token")
            if (!Regex("[0-9a-f]{64}").matches(token)) throw IOException("support_invalid_token")
            identity.setProperty("device", device)
            identity.setProperty("token", token)
            saveIdentity(context, identity)
        }
        val response = post("heartbeat", JSONObject().put("device", device).put("diagnostics", diagnostics(context)),
            identity.getProperty("token"))
        applyResponse(context, response)
    }

    internal fun applyResponse(context: Context, response: JSONObject) {
        if (response.getInt("schema") != 1) throw IOException("support_schema")
        val config = response.getJSONObject("settings")
        if (config.keys().asSequence().any { it !in setOf("volume_capture", "retry_seconds", "uploads_paused") }) {
            throw IOException("support_unknown_setting")
        }
        val ttl = response.getLong("ttl_seconds")
        if ((config.has("volume_capture") && config.get("volume_capture") !is Boolean) ||
            (config.has("uploads_paused") && config.get("uploads_paused") !is Boolean) ||
            (config.has("retry_seconds") && config.get("retry_seconds") !is Int)) {
            throw IOException("support_invalid_setting")
        }
        val value = SupportPolicy.settings(config.optBoolean("volume_capture", true),
            config.optLong("retry_seconds", 30), config.optBoolean("uploads_paused", false), ttl)
        val update = response.optJSONObject("update")
        val validUpdate = update?.takeIf {
            it.optString("package") == BuildConfig.APPLICATION_ID &&
                it.optInt("version_code") > BuildConfig.VERSION_CODE &&
                it.optInt("min_sdk", Int.MAX_VALUE) <= Build.VERSION.SDK_INT &&
                Regex("[0-9a-f]{64}").matches(it.optString("sha256")) &&
                SupportPolicy.allowedDownload(it.optString("url"))
        }
        prefs(context).edit().putBoolean("volume", value.volumeCapture).putLong("retry", value.retrySeconds)
            .putBoolean("paused", value.uploadsPaused).putLong("ttl", ttl.coerceIn(0, 86400))
            .putLong("received", SystemClock.elapsedRealtime()).putInt("boot", boot(context))
            .putLong("last_sync", System.currentTimeMillis()).putLong("last_sync_elapsed", SystemClock.elapsedRealtime())
            .putInt("last_sync_boot", boot(context)).putString("sync_error", "")
            .putString("update", validUpdate?.toString()).apply()
        if (!value.uploadsPaused && PhotoQueueStore.hasItems(context)) PhotoUploadWorker.enqueue(context)
    }
}

class RemoteSupportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            RemoteSupport.sync(applicationContext, inputData.getBoolean("force", false))
            Result.success()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            val code = error.message?.takeIf { Regex("support_[a-z0-9_]{1,40}").matches(it) } ?: "support_unavailable"
            RemoteSupport.prefs(applicationContext).edit().putString("sync_error", code).apply()
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
}
