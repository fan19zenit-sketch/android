package com.example.simplephotouploader

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

class HistoryActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastItems: List<PhotoHistoryStore.Entry> = emptyList()
    private val deleting = mutableSetOf<String>()
    private var autoRefreshJob: Job? = null
    private var renderJob: Job? = null
    private var scrollAfterFilter = false
    private var filter = HistoryOrder.Filter.ALL
    private var sharingDiagnostics = false
    private var downloadingUpdate = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        filter = savedInstanceState?.getString("history_filter")?.let {
            runCatching { HistoryOrder.Filter.valueOf(it) }.getOrNull()
        } ?: HistoryOrder.Filter.ALL
        if (BuildConfig.FLAVOR == "clean" && filter in setOf(HistoryOrder.Filter.IN_PROGRESS, HistoryOrder.Filter.ATTENTION)) {
            filter = HistoryOrder.Filter.ALL
        }

        val backButton = findViewById<Button>(R.id.btnBack)
        val changeCityButton = findViewById<Button>(R.id.btnChangeCity)
        val emptyText = findViewById<TextView>(R.id.tvEmpty)
        val recycler = findViewById<RecyclerView>(R.id.recyclerHistory)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = HistoryAdapter(
            onDeleteFromChat = { entry ->
                AlertDialog.Builder(this).setMessage(R.string.history_confirm_delete)
                    .setNegativeButton(R.string.close, null)
                    .setPositiveButton(R.string.history_delete_chat) { _, _ -> deleteFromChat(entry, recycler, emptyText) }.show()
            }, onOpenPhoto = ::openPhoto, onRetry = ::retryPhoto)
        val progressFilter = if (BuildConfig.FLAVOR == "clean") HistoryOrder.Filter.CONFIRMED else HistoryOrder.Filter.IN_PROGRESS
        val attentionFilter = if (BuildConfig.FLAVOR == "clean") HistoryOrder.Filter.UNCONFIRMED else HistoryOrder.Filter.ATTENTION
        for ((id, value) in listOf(R.id.btnHistoryAll to HistoryOrder.Filter.ALL,
            R.id.btnHistoryProgress to progressFilter, R.id.btnHistoryAttention to attentionFilter)) {
            findViewById<Button>(id).setOnClickListener {
                filter = value
                scrollAfterFilter = true
                renderHistory(recycler, emptyText)
            }
        }
        findViewById<TextView>(R.id.versionText)?.text = getString(R.string.app_version, BuildConfig.VERSION_NAME)
        renderHistory(recycler, emptyText)

        backButton.setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSupport).setOnClickListener {
            if (BuildConfig.FLAVOR == "clean") shareDiagnostics()
            else startActivity(Intent(this, SupportActivity::class.java))
        }
        findViewById<Button>(R.id.btnAvailableUpdate).setOnClickListener { installUpdate() }
        changeCityButton.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java).apply {
                putExtra(SetupActivity.EXTRA_FORCE_EDIT, true)
            })
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        UploadStatusSyncWorker.enqueueBurst(this)
        if (BuildConfig.FLAVOR == "clean") RemoteSupport.schedule(this)
        refreshUpdateButton()
        val recycler = findViewById<RecyclerView>(R.id.recyclerHistory)
        val emptyText = findViewById<TextView>(R.id.tvEmpty)
        renderHistory(recycler, emptyText)
        autoRefreshJob?.cancel()
        autoRefreshJob = scope.launch {
            while (true) {
                delay(2500)
                renderHistory(recycler, emptyText)
            }
        }
    }

    override fun onPause() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("history_filter", filter.name)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun renderHistory(recycler: RecyclerView, emptyText: TextView) {
        // A slow query can outlast the refresh interval. Let it finish rather than
        // repeatedly cancelling it; the latest selected filter is applied below.
        if (renderJob?.isActive == true) return
        renderJob = scope.launch {
            val items = withContext(Dispatchers.IO) { PhotoHistoryStore.list(this@HistoryActivity) }
            val visible = items.filter { HistoryOrder.matches(filter, it.status, it.queuedInUploadQueue, it.chatDeleted) }
            if (visible != lastItems) {
                lastItems = visible
                val renderedFilter = filter
                (recycler.adapter as HistoryAdapter).submitList(visible) {
                    if (scrollAfterFilter && renderedFilter == filter) {
                        recycler.scrollToPosition(0)
                        scrollAfterFilter = false
                    }
                }
            } else if (scrollAfterFilter) {
                recycler.scrollToPosition(0)
                scrollAfterFilter = false
            }
            emptyText.setText(if (filter == HistoryOrder.Filter.ALL) R.string.history_empty else R.string.history_filter_empty)
            emptyText.visibility = if (visible.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            val attentionCount = items.count { HistoryOrder.needsAttention(it.status) }
            val confirmed = items.count { HistoryOrder.matches(HistoryOrder.Filter.CONFIRMED, it.status, it.queuedInUploadQueue, it.chatDeleted) }
            val unconfirmed = items.count { HistoryOrder.matches(HistoryOrder.Filter.UNCONFIRMED, it.status, it.queuedInUploadQueue, it.chatDeleted) }
            val summary = if (BuildConfig.FLAVOR == "clean") getString(R.string.history_binary_summary, confirmed, unconfirmed)
                else getString(R.string.history_summary, confirmed,
                    items.count { it.queuedInUploadQueue || it.status in setOf("uploaded_to_server", "sending_to_chat") }, attentionCount)
            findViewById<TextView>(R.id.tvHistorySubheader)?.text = summary
            findViewById<Button>(R.id.btnHistoryAttention).apply {
                text = if (BuildConfig.FLAVOR == "clean") getString(R.string.history_unconfirmed)
                    else getString(R.string.history_filter_attention, attentionCount)
            }
            val progressFilter = if (BuildConfig.FLAVOR == "clean") HistoryOrder.Filter.CONFIRMED else HistoryOrder.Filter.IN_PROGRESS
            val attentionFilter = if (BuildConfig.FLAVOR == "clean") HistoryOrder.Filter.UNCONFIRMED else HistoryOrder.Filter.ATTENTION
            if (BuildConfig.FLAVOR == "clean") findViewById<Button>(R.id.btnHistoryProgress).setText(R.string.history_confirmed)
            for ((id, value) in listOf(R.id.btnHistoryAll to HistoryOrder.Filter.ALL,
                R.id.btnHistoryProgress to progressFilter, R.id.btnHistoryAttention to attentionFilter)) {
                findViewById<Button>(id).apply {
                    if (BuildConfig.FLAVOR == "clean") {
                        isEnabled = true
                        isSelected = filter == value
                        setTextColor(android.graphics.Color.parseColor(if (isSelected) "#9B2456" else "#686866"))
                    } else isEnabled = filter != value
                }
            }
            refreshUpdateButton()
        }
    }

    private fun shareDiagnostics() {
        if (sharingDiagnostics) return
        sharingDiagnostics = true
        findViewById<Button>(R.id.btnSupport).apply {
            isEnabled = false
            setText(R.string.diagnostics_preparing)
        }
        scope.launch {
            try {
                val report = withContext(Dispatchers.IO) { DiagnosticJournal.export(applicationContext) }
                startActivity(Intent.createChooser(DiagnosticShare.intent(this@HistoryActivity, report),
                    getString(R.string.support_share)))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Toast.makeText(this@HistoryActivity, R.string.diagnostics_share_failed, Toast.LENGTH_LONG).show()
            } finally {
                sharingDiagnostics = false
                findViewById<Button>(R.id.btnSupport).apply {
                    isEnabled = true
                    setText(R.string.diagnostics_share)
                }
            }
        }
    }

    private fun refreshUpdateButton() {
        val button = findViewById<Button>(R.id.btnAvailableUpdate)
        val update = if (BuildConfig.FLAVOR == "clean") AppUpdater.available(this) else null
        button.visibility = if (update != null || downloadingUpdate) android.view.View.VISIBLE else android.view.View.GONE
        button.isEnabled = update != null && !downloadingUpdate
        button.text = if (downloadingUpdate) getString(R.string.support_downloading)
            else update?.let { getString(R.string.support_install, it.optString("version_name")) }.orEmpty()
    }

    private fun installUpdate() {
        val info = AppUpdater.available(this) ?: return
        if (downloadingUpdate) return
        AlertDialog.Builder(this).setTitle(R.string.support_update_title).setMessage(R.string.support_update_explanation)
            .setNegativeButton(R.string.close, null).setPositiveButton(R.string.support_continue) { _, _ ->
                if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                    runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName"))) }.onFailure { showUpdateError() }
                    return@setPositiveButton
                }
                downloadingUpdate = true
                refreshUpdateButton()
                scope.launch {
                    try {
                        val apk = withContext(Dispatchers.IO) { AppUpdater.download(this@HistoryActivity, info) }
                        startActivity(AppUpdater.installer(this@HistoryActivity, apk))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        RemoteSupport.event(applicationContext, "update_failed")
                        showUpdateError()
                    } finally {
                        downloadingUpdate = false
                        refreshUpdateButton()
                    }
                }
            }.show()
    }

    private fun showUpdateError() {
        AlertDialog.Builder(this).setMessage(R.string.support_update_failed).setPositiveButton(R.string.close, null).show()
    }

    private fun retryPhoto(entry: PhotoHistoryStore.Entry) {
        if (entry.photoContextAck == "invalid_context") {
            scope.launch {
                val updated = withContext(Dispatchers.IO) {
                    PilotDatabase.get(this@HistoryActivity).photoRecordDao().retryInvalidPhotoContext(entry.photoPath)
                }
                if (updated == 1) PhotoContextSyncWorker.enqueue(applicationContext)
                renderHistory(findViewById(R.id.recyclerHistory), findViewById(R.id.tvEmpty))
            }
            return
        }
        AlertDialog.Builder(this).setTitle(R.string.history_retry)
            .setMessage(R.string.history_retry_confirm)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.history_retry) { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) { PilotDatabase.get(this@HistoryActivity).photoRecordDao().retryLocal(entry.photoPath) }
                    // Run while the user is here, even if Android has delayed WorkManager.
                    UploadCoordinator.kick(applicationContext)
                    renderHistory(findViewById(R.id.recyclerHistory), findViewById(R.id.tvEmpty))
                }
            }.show()
    }

    private fun openPhoto(entry: PhotoHistoryStore.Entry) {
        startActivity(Intent(this, PhotoViewerActivity::class.java).apply {
            putExtra(PhotoViewerActivity.EXTRA_PHOTO_PATH, entry.photoPath)
            putExtra(PhotoViewerActivity.EXTRA_THUMBNAIL_PATH, entry.thumbnailPath)
            putExtra(PhotoViewerActivity.EXTRA_CAPTURED_AT, entry.capturedAt)
            putExtra(PhotoViewerActivity.EXTRA_STATUS, entry.status)
            putExtra(PhotoViewerActivity.EXTRA_CHAT_DELETED, entry.chatDeleted)
        })
    }

    private fun deleteFromChat(
        entry: PhotoHistoryStore.Entry,
        recycler: RecyclerView,
        emptyText: TextView,
    ) {
        val jobId = entry.jobId ?: return
        if (!deleting.add(jobId)) return
        val prefs = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
        val backendUrl = prefs.getString(AppPrefs.KEY_BACKEND_URL, AppPrefs.DEFAULT_BACKEND_URL)
            ?.trimEnd('/')
            .orEmpty()

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder()
                        .url("$backendUrl/upload-jobs/$jobId/chat-message")
                        .delete()
                        .build()
                    PhotoApi.execute(request).use { response ->
                        if (!response.isSuccessful) {
                            throw IllegalStateException("Server error ${response.code}")
                        }
                    }
                }
            }

            if (result.isSuccess) {
                PhotoHistoryStore.markChatDeleted(this@HistoryActivity, jobId)
                Toast.makeText(this@HistoryActivity, R.string.history_delete_done, Toast.LENGTH_SHORT).show()
                UploadStatusSyncWorker.enqueueBurst(this@HistoryActivity)
                renderHistory(recycler, emptyText)
            } else {
                val confirmedDeleted = withContext(Dispatchers.IO) {
                    runCatching {
                        PhotoApi.execute(Request.Builder().url("$backendUrl/upload-jobs/$jobId").build()).use { response ->
                                if (!response.isSuccessful) return@use false
                                val payload = JSONObject(response.body?.string().orEmpty())
                                val job = payload.optJSONObject("job") ?: payload
                                job.optString("job_id") == jobId && job.optBoolean("chat_deleted")
                            }
                    }.getOrDefault(false)
                }
                if (confirmedDeleted) {
                    PhotoHistoryStore.markChatDeleted(this@HistoryActivity, jobId)
                    Toast.makeText(this@HistoryActivity, R.string.history_delete_done, Toast.LENGTH_SHORT).show()
                    renderHistory(recycler, emptyText)
                } else {
                    Toast.makeText(this@HistoryActivity, R.string.history_delete_failed, Toast.LENGTH_SHORT).show()
                }
            }
            deleting.remove(jobId)
        }
    }
}
