package com.example.simplephotouploader

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

class SupportActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refresh: Job? = null
    private var downloading = false
    private var checking = false
    private var probeResult = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_support)
        findViewById<Button>(R.id.supportBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.supportCheck).setOnClickListener {
            if (checking) return@setOnClickListener
            checking = true
            findViewById<Button>(R.id.supportCheck).isEnabled = false
            scope.launch {
                try {
                    probeResult = withContext(Dispatchers.IO) {
                        val result = RemoteSupport.probe()
                        runCatching { RemoteSupport.sync(applicationContext, force = true) }.onFailure {
                            RemoteSupport.prefs(applicationContext).edit().putString("sync_error", "support_unavailable").apply()
                        }
                        result
                    }
                } finally {
                    checking = false
                    findViewById<Button>(R.id.supportCheck).isEnabled = true
                }
            }
        }
        findViewById<Button>(R.id.supportShare).setOnClickListener {
            scope.launch {
                runCatching {
                    val report = withContext(Dispatchers.IO) { DiagnosticJournal.export(applicationContext) }
                    val uri = FileProvider.getUriForFile(this@SupportActivity, "$packageName.updates", report)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        getString(R.string.support_share)))
                }.onFailure { showUpdateError() }
            }
        }
        findViewById<Button>(R.id.supportUpdate).setOnClickListener { installUpdate() }
    }

    override fun onResume() {
        super.onResume()
        RemoteSupport.schedule(this)
        refresh = scope.launch {
            while (isActive) {
                val info = withContext(Dispatchers.IO) { RemoteSupport.diagnostics(this@SupportActivity) }
                val prefs = RemoteSupport.prefs(this@SupportActivity)
                val synced = prefs.getLong("last_sync", 0)
                findViewById<TextView>(R.id.supportDevice).text = getString(R.string.support_device,
                    BuildConfig.VERSION_NAME, info.getString("manufacturer"), info.getString("model"),
                    Build.VERSION.RELEASE, info.getInt("page_size") / 1024)
                findViewById<TextView>(R.id.supportQueue).text = getString(R.string.support_queue,
                    info.getInt("queued"), info.getInt("pending"), info.getInt("issues"), info.getLong("free_mb"))
                findViewById<TextView>(R.id.supportStatus).text = when {
                    checking -> getString(R.string.support_checking)
                    !prefs.getString("sync_error", "").isNullOrEmpty() -> getString(R.string.support_unavailable)
                    synced == 0L -> getString(R.string.support_not_synced)
                    else -> getString(R.string.support_synced, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(synced)))
                }
                val technical = withContext(Dispatchers.IO) {
                    val network = DiagnosticJournal.network(this@SupportActivity)
                    val work = androidx.work.WorkManager.getInstance(this@SupportActivity)
                        .getWorkInfosForUniqueWork("photo-upload-queue").get()
                    val failure = prefs.getString("last_failure", "").orEmpty().let {
                        runCatching { org.json.JSONObject(it) }.getOrNull()
                    }
                    getString(R.string.support_technical, network.optString("transport"),
                        getString(if (network.optBoolean("validated")) R.string.support_yes else R.string.support_no),
                        getString(if (network.optBoolean("background_restricted")) R.string.support_yes else R.string.support_no),
                        work.filter { !it.state.isFinished }.groupingBy { it.state.name }.eachCount()
                            .takeIf { it.isNotEmpty() }?.toString() ?: getString(R.string.support_no_active_work),
                        failure?.let { "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.optLong("at")))}\n" +
                            "${it.optString("error")} / ${it.optString("phase")} / ${it.optString("trace")}" } ?: getString(R.string.support_no))
                }
                findViewById<TextView>(R.id.supportTechnical).text = listOf(technical, DiagnosticJournal.current, probeResult)
                    .filter { it.isNotBlank() }.joinToString("\n\n")
                findViewById<TextView>(R.id.supportSettings).text = if (RemoteSupport.settings(this@SupportActivity).uploadsPaused)
                    getString(R.string.support_paused) else getString(R.string.support_normal)
                val update = AppUpdater.available(this@SupportActivity)
                findViewById<Button>(R.id.supportUpdate).apply {
                    isEnabled = update != null && !downloading
                    text = when {
                        downloading -> getString(R.string.support_downloading)
                        update != null -> getString(R.string.support_install, update.optString("version_name"))
                        else -> getString(R.string.support_no_update)
                    }
                }
                delay(2000)
            }
        }
    }

    private fun installUpdate() {
        val info = AppUpdater.available(this) ?: return
        if (downloading) return
        AlertDialog.Builder(this).setTitle(R.string.support_update_title).setMessage(R.string.support_update_explanation)
            .setNegativeButton(R.string.close, null).setPositiveButton(R.string.support_continue) { _, _ ->
                if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                    runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName"))) }.onFailure { showUpdateError() }
                    return@setPositiveButton
                }
                downloading = true
                scope.launch {
                    try {
                        val apk = withContext(Dispatchers.IO) { AppUpdater.download(this@SupportActivity, info) }
                        startActivity(AppUpdater.installer(this@SupportActivity, apk))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        RemoteSupport.event(applicationContext, "update_failed")
                        showUpdateError()
                    } finally { downloading = false }
                }
            }.show()
    }

    private fun showUpdateError() {
        AlertDialog.Builder(this).setMessage(R.string.support_update_failed).setPositiveButton(R.string.close, null).show()
    }

    override fun onPause() { refresh?.cancel(); super.onPause() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
