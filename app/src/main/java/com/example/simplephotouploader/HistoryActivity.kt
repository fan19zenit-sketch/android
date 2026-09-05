package com.example.simplephotouploader

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
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
    private val client = PhotoApi.client
    private var lastItems: List<PhotoHistoryStore.Entry> = emptyList()
    private val deleting = mutableSetOf<String>()
    private var autoRefreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

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
        findViewById<TextView>(R.id.versionText)?.text = getString(R.string.app_version, BuildConfig.VERSION_NAME)
        renderHistory(recycler, emptyText)

        backButton.setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSupport).setOnClickListener {
            startActivity(Intent(this, SupportActivity::class.java))
        }
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

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun renderHistory(recycler: RecyclerView, emptyText: TextView) {
        scope.launch {
            val items = withContext(Dispatchers.IO) { PhotoHistoryStore.list(this@HistoryActivity) }
            if (items != lastItems) {
                lastItems = items
                (recycler.adapter as HistoryAdapter).submitList(items)
            }
            emptyText.visibility = if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            findViewById<TextView>(R.id.tvHistorySubheader)?.text = getString(R.string.history_summary,
                items.count { it.status == "sent" && !it.chatDeleted },
                items.count { it.queuedInUploadQueue || it.status in setOf("uploaded_to_server", "sending_to_chat") },
                items.count { it.status in setOf("error", "needs_review") })
        }
    }

    private fun retryPhoto(entry: PhotoHistoryStore.Entry) {
        AlertDialog.Builder(this).setTitle(R.string.history_retry)
            .setMessage(R.string.history_retry_confirm)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.history_retry) { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) { PilotDatabase.get(this@HistoryActivity).photoRecordDao().retryLocal(entry.photoPath) }
                    PhotoUploadWorker.enqueue(this@HistoryActivity)
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
                    client.newCall(request).execute().use { response ->
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
                        client.newCall(Request.Builder().url("$backendUrl/upload-jobs/$jobId").build())
                            .execute().use { response ->
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
