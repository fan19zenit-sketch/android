package com.example.simplephotouploader

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.util.AtomicFile
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Strictly structured telemetry: no URLs, bodies, headers, photos or identifiers of employees. */
object DiagnosticJournal {
    private const val LIMIT = 240
    private const val MAX_BYTES = 196608
    private val code = Regex("[a-z0-9_]{1,40}")
    @Volatile var current: String = ""
        private set

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "connection-journal.json"))
    private fun archive(context: Context) = AtomicFile(File(context.noBackupFilesDir, "diagnostic-history.json"))

    private fun read(source: AtomicFile): List<JSONObject> = runCatching {
        val bytes = source.openRead().use {
            if (it.channel.size() > MAX_BYTES) return emptyList()
            it.readBytes()
        }
        if (bytes.size > MAX_BYTES) return emptyList()
        val array = JSONArray(String(bytes, Charsets.UTF_8))
        (0 until array.length()).map { array.getJSONObject(it) }.takeLast(LIMIT)
    }.getOrDefault(emptyList())

    @Synchronized fun events(context: Context): List<JSONObject> = read(file(context))

    private fun write(atomic: AtomicFile, events: List<JSONObject>) {
        var items = events.takeLast(LIMIT)
        var data = JSONArray(items).toString().toByteArray(Charsets.UTF_8)
        while (data.size > MAX_BYTES && items.isNotEmpty()) {
            items = items.drop(1)
            data = JSONArray(items).toString().toByteArray(Charsets.UTF_8)
        }
        val out = atomic.startWrite()
        try { out.write(data); atomic.finishWrite(out) }
        catch (error: Exception) { atomic.failWrite(out); throw error }
    }

    @Synchronized fun record(context: Context, operation: String, phase: String, endpoint: String,
                             trace: String, elapsed: Long = 0, http: Int = 0, error: String = "") {
        if (listOf(operation, phase, endpoint).any { !code.matches(it) } ||
            !Regex("[a-f0-9]{8}").matches(trace) || (error.isNotEmpty() && !code.matches(error))) return
        // Diagnostics must never prevent a photograph from being saved or acknowledged.
        runCatching {
            val event = JSONObject().put("id", UUID.randomUUID().toString()).put("at", System.currentTimeMillis())
                .put("operation", operation).put("phase", phase).put("endpoint", endpoint).put("trace", trace)
                .put("elapsed_ms", elapsed.coerceIn(0, 3600000)).put("http", http.coerceIn(0, 599)).put("error", error)
            write(file(context), events(context) + event)
            // Acknowledged events leave the upload queue but remain in the local support report.
            if (BuildConfig.FLAVOR == "clean") runCatching { write(archive(context), read(archive(context)) + event) }
            if (error.isNotEmpty() && operation != "support") {
                RemoteSupport.prefs(context).edit().putString("last_failure", event.toString()).apply()
            }
        }
    }

    fun stage(operation: String, phase: String, endpoint: String, trace: String) {
        current = "$operation / $phase / $endpoint / $trace"
    }

    fun complete(trace: String) {
        if (current.endsWith(trace)) current = ""
    }

    @Synchronized fun acknowledge(context: Context, ids: Set<String>) {
        runCatching { write(file(context), events(context).filterNot { it.optString("id") in ids }) }
    }

    fun network(context: Context): JSONObject {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val caps = runCatching { manager.getNetworkCapabilities(manager.activeNetwork) }.getOrNull()
        return JSONObject().put("connected", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
            .put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            .put("captive_portal", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true)
            .put("metered", manager.isActiveNetworkMetered)
            .put("transport", when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }).put("power_save", power.isPowerSaveMode).put("idle", power.isDeviceIdleMode)
            .put("battery_exempt", power.isIgnoringBatteryOptimizations(context.packageName))
            .put("background_restricted", Build.VERSION.SDK_INT >= 28 &&
                (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isBackgroundRestricted)
    }

    fun export(context: Context): File {
        val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        return File(directory, "connection-report.json").apply {
            val issueSummary = runCatching {
                val records = PilotDatabase.get(context).photoRecordDao().getAttentionRecords()
                val facts = records.map { IssueSummary.Fact(it.status, !it.jobId.isNullOrBlank(),
                    File(it.photoPath).exists(), it.capturedAt) }
                JSONObject(IssueSummary.summarize(facts))
            }.getOrElse { JSONObject().put("unavailable", true) }
            if (BuildConfig.FLAVOR != "clean") {
                writeText(JSONObject().put("schema", 2).put("version", BuildConfig.VERSION_NAME)
                    .put("sdk", Build.VERSION.SDK_INT).put("model", Build.MODEL.take(100))
                    .put("network", network(context)).put("events", JSONArray(events(context)))
                    .put("photo_attention_summary", issueSummary).toString(2))
                return@apply
            }
            val recentEvents = (read(archive(context)) + events(context))
                .distinctBy { it.optString("id") }.takeLast(LIMIT)
            val appPrefs = context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
            val location = JSONObject()
            appPrefs.getString(AppPrefs.KEY_CITY, "").orEmpty().trim()
                .takeIf { Regex("[\\p{L}][\\p{L} .-]{0,79}").matches(it) }
                ?.let { location.put("branch", it) }
            val device = appPrefs.getString(AppPrefs.KEY_DEVICE_UUID, "").orEmpty()
            if (runCatching { UUID.fromString(device) }.isSuccess) {
                val digest = MessageDigest.getInstance("SHA-256").digest(device.toByteArray(Charsets.UTF_8))
                location.put("device_fingerprint", digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) })
            }
            val technical = runCatching { RemoteSupport.diagnostics(context) }
                .getOrElse { JSONObject().put("unavailable", true) }
            if (!code.matches(technical.optString("last_event"))) technical.remove("last_event")
            val cook = runCatching { RemoteSupport.cookDiagnostics(context) }
                .getOrElse { JSONObject().put("unavailable", true) }
            val workers = JSONObject()
            for (name in listOf("photo-upload-queue", "photo-context-sync", "cook-shift-presence", "support-check")) {
                val counts = runCatching {
                    WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get(3, TimeUnit.SECONDS)
                        .filter { !it.state.isFinished }.groupingBy { it.state.name }.eachCount()
                }.getOrNull()
                workers.put(name, counts?.let { JSONObject(it) } ?: JSONObject().put("unavailable", true))
            }
            val settings = RemoteSupport.settings(context)
            val support = JSONObject().put("uploads_paused", settings.uploadsPaused)
                .put("retry_seconds", settings.retrySeconds).put("volume_capture", settings.volumeCapture)
                .put("last_sync_at", RemoteSupport.prefs(context).getLong("last_sync", 0))
            RemoteSupport.prefs(context).getString("sync_error", "").orEmpty()
                .takeIf { code.matches(it) }?.let { support.put("sync_error", it) }
            val failure = runCatching { JSONObject(RemoteSupport.prefs(context).getString("last_failure", "").orEmpty()) }
                .getOrNull()
            if (failure != null) {
                val safe = JSONObject()
                for (key in listOf("operation", "phase", "endpoint", "error")) {
                    failure.optString(key).takeIf { code.matches(it) }?.let { safe.put(key, it) }
                }
                failure.optString("trace").takeIf { Regex("[a-f0-9]{8}").matches(it) }?.let { safe.put("trace", it) }
                for (key in listOf("at", "http", "elapsed_ms")) if (failure.opt(key) is Number) safe.put(key, failure.optLong(key))
                support.put("last_failure", safe)
            }
            writeText(JSONObject().put("schema", 3).put("generated_at", System.currentTimeMillis())
                .put("version", BuildConfig.VERSION_NAME)
                .put("sdk", Build.VERSION.SDK_INT).put("model", Build.MODEL.take(100))
                .put("network", network(context)).put("events", JSONArray(recentEvents))
                .put("location", location)
                .put("device_and_queue", technical).put("cook_workflow", cook)
                .put("workers", workers).put("support", support)
                .put("photo_attention_summary", issueSummary).toString(2))
        }
    }
}
