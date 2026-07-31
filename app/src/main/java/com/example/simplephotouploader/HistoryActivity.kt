package com.example.simplephotouploader

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
import okhttp3.OkHttpClient
import okhttp3.Request

class HistoryActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = OkHttpClient.Builder().build()
    private var autoRefreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        val backButton = findViewById<Button>(R.id.btnBack)
        val changeCityButton = findViewById<Button>(R.id.btnChangeCity)
        val emptyText = findViewById<TextView>(R.id.tvEmpty)
        val recycler = findViewById<RecyclerView>(R.id.recyclerHistory)

        recycler.layoutManager = LinearLayoutManager(this)
        renderHistory(recycler, emptyText)

        backButton.setOnClickListener { finish() }
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
                UploadStatusSyncWorker.enqueue(this@HistoryActivity)
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
        val items = PhotoHistoryStore.list(this)
        recycler.adapter = HistoryAdapter(
            items = items,
            onDeleteFromChat = { entry -> deleteFromChat(entry, recycler, emptyText) },
            onOpenPhoto = { entry -> openPhoto(entry) },
        )
        emptyText.visibility = if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
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
                UploadStatusSyncWorker.enqueueBurst(this@HistoryActivity)
                delay(1200)
                val updatedEntry = PhotoHistoryStore.list(this@HistoryActivity).firstOrNull { it.jobId == jobId }
                if (updatedEntry?.chatDeleted == true) {
                    Toast.makeText(this@HistoryActivity, R.string.history_delete_done, Toast.LENGTH_SHORT).show()
                    renderHistory(recycler, emptyText)
                } else {
                    Toast.makeText(this@HistoryActivity, R.string.history_delete_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
