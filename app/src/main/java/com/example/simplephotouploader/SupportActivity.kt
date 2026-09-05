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
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

class SupportActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refresh: Job? = null
    private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_support)
        findViewById<Button>(R.id.supportBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.supportCheck).setOnClickListener {
            RemoteSupport.check(this, force = true)
            findViewById<TextView>(R.id.supportStatus).setText(R.string.support_checking)
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
                    !prefs.getString("sync_error", "").isNullOrEmpty() -> getString(R.string.support_unavailable)
                    synced == 0L -> getString(R.string.support_not_synced)
                    else -> getString(R.string.support_synced, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(synced)))
                }
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
