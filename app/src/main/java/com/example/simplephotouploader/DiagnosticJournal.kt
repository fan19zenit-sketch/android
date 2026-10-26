package com.example.simplephotouploader

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Strictly structured telemetry: no URLs, bodies, headers, photos or identifiers of employees. */
object DiagnosticJournal {
    private const val LIMIT = 240
    private const val MAX_BYTES = 196608
    private val code = Regex("[a-z0-9_]{1,40}")
    @Volatile var current: String = ""
        private set

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "connection-journal.json"))

    @Synchronized fun events(context: Context): List<JSONObject> = runCatching {
        val bytes = file(context).openRead().use {
            if (it.channel.size() > MAX_BYTES) return emptyList()
            it.readBytes()
        }
        if (bytes.size > MAX_BYTES) return emptyList()
        val array = JSONArray(String(bytes, Charsets.UTF_8))
        (0 until array.length()).map { array.getJSONObject(it) }.takeLast(LIMIT)
    }.getOrDefault(emptyList())

    private fun write(context: Context, events: List<JSONObject>) {
        val atomic = file(context)
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
            write(context, events(context) + event)
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
        runCatching { write(context, events(context).filterNot { it.optString("id") in ids }) }
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
            writeText(JSONObject().put("schema", 2).put("version", BuildConfig.VERSION_NAME)
                .put("sdk", Build.VERSION.SDK_INT).put("model", Build.MODEL.take(100))
                .put("network", network(context)).put("events", JSONArray(events(context))).toString(2))
        }
    }
}
