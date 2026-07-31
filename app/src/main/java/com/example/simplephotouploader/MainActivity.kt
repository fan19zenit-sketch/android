package com.example.simplephotouploader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.widget.TextView
import android.view.View
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var captureButton: View
    private lateinit var settingsButton: View
    private lateinit var imageCapture: ImageCapture

    private val client = OkHttpClient.Builder().build()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val inProcess = AtomicBoolean(false)
    private var prefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                showPermissionError()
            }
        }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        captureButton = findViewById(R.id.captureButton)
        settingsButton = findViewById(R.id.settingsButton)

        val prefs = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
        val isCleanApp = packageName == "com.example.photovchistotu"
        val backendUrl = prefs.getString(AppPrefs.KEY_BACKEND_URL, null)
        val city = prefs.getString(AppPrefs.KEY_CITY, null)
        if (backendUrl.isNullOrEmpty() || city.isNullOrEmpty()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        if (isCameraPermissionGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        if (isCleanApp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        captureButton.setOnClickListener {
            takePhotoAndUpload()
        }
        settingsButton.setOnClickListener {
            if (isCleanApp) {
                startActivity(Intent(this, HistoryActivity::class.java))
            } else {
                startActivity(Intent(this, SetupActivity::class.java).apply {
                    putExtra(SetupActivity.EXTRA_FORCE_EDIT, true)
                })
            }
        }

        if (isCleanApp) {
            val initialStatus = prefs.getString(AppPrefs.KEY_LAST_STATUS, null)
                ?: getString(R.string.status_ready)
            showStatus(mapPilotStatusForDisplay(initialStatus))
            UploadStatusSyncWorker.enqueueBurst(this)

            prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, key ->
                if (key == AppPrefs.KEY_LAST_STATUS) {
                    val text = changedPrefs.getString(AppPrefs.KEY_LAST_STATUS, null)
                    if (!text.isNullOrBlank()) {
                        showStatus(mapPilotStatusForDisplay(text))
                    }
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(prefsListener)

            if (PhotoQueueStore.hasItems(this)) {
                PhotoUploadForegroundService.start(this)
            }
            refreshHistoryPreview()
        } else {
            coroutineScope.launch { processQueue(backendUrl) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (packageName == "com.example.photovchistotu") {
            refreshHistoryPreview()
        }
    }

    override fun onDestroy() {
        prefsListener?.let {
            getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(it)
        }
        super.onDestroy()
        coroutineScope.cancel()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                takePhotoAndUpload()
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun showStatus(text: String) {
        runOnUiThread {
            statusText.text = if (packageName == "com.example.photovchistotu") {
                text
            } else {
                getString(R.string.status_format, text)
            }
        }
    }

    private fun mapPilotStatusForDisplay(raw: String): String {
        if (packageName != "com.example.photovchistotu") {
            return raw
        }
        return when (raw) {
            getString(R.string.status_queued),
            getString(R.string.status_sending),
            getString(R.string.status_server_received),
            getString(R.string.status_uploaded),
            getString(R.string.history_status_chat),
            getString(R.string.status_ready) -> getString(R.string.status_ready_next)
            getString(R.string.status_retrying) -> getString(R.string.status_background_retry)
            getString(R.string.status_error) -> getString(R.string.status_background_error)
            else -> raw
        }
    }

    private fun refreshHistoryPreview() {
        val imageView = settingsButton as? ImageView ?: return
        val latest = PhotoHistoryStore.list(this).firstOrNull()
        val thumbPath = latest?.thumbnailPath
        if (!thumbPath.isNullOrBlank()) {
            val bitmap = BitmapFactory.decodeFile(thumbPath)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
                return
            }
        }
        imageView.setImageResource(android.R.drawable.ic_menu_gallery)
    }

    private fun isCameraPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun showPermissionError() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_required_title)
            .setMessage(R.string.permission_required_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                })
            }
            .setNegativeButton(R.string.close) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                showStatus(getString(R.string.status_ready))
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to bind camera use cases", t)
                showStatus(getString(R.string.status_error))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhotoAndUpload() {
        if (!::imageCapture.isInitialized) {
            showStatus(getString(R.string.status_error))
            return
        }

        showStatus(getString(R.string.status_sending))

        val file = createCaptureFile()
        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}")
                    showStatus(getString(R.string.status_error))
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    coroutineScope.launch {
                        PhotoHistoryStore.addQueued(this@MainActivity, file.absolutePath)
                        PhotoQueueStore.enqueue(this@MainActivity, file.absolutePath)
                        val prefs = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
                        val isCleanApp = packageName == "com.example.photovchistotu"
                        if (isCleanApp) {
                            prefs.edit()
                                .putString(AppPrefs.KEY_LAST_STATUS, getString(R.string.status_ready_next))
                                .apply()
                            showStatus(getString(R.string.status_ready_next))
                            PhotoUploadForegroundService.start(this@MainActivity)
                        } else {
                            prefs.getString(AppPrefs.KEY_BACKEND_URL, null)
                                ?.let { processQueue(it) }
                        }
                    }
                }
            }
        )
    }

    private fun createCaptureFile(): File {
        val folder = File(
            externalMediaDirs.firstOrNull()?.absolutePath ?: filesDir.absolutePath,
            "photos"
        )
        if (!folder.exists()) {
            folder.mkdirs()
        }

        val formatter = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.getDefault())
        return File(folder, "IMG_${formatter.format(Date())}.jpg")
    }

    private suspend fun storeQueue(
        prefs: android.content.SharedPreferences,
        data: List<String>
    ) = withContext(Dispatchers.IO) {
        PhotoQueueStore.saveQueue(prefs, data)
    }

    private suspend fun processQueue(backendUrl: String) {
        if (!inProcess.compareAndSet(false, true)) {
            return
        }

        try {
            val prefs = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
            val queue = PhotoQueueStore.loadQueue(prefs).toMutableList()
            if (queue.isEmpty()) {
                showStatus(getString(R.string.status_ready))
                return
            }

            for (entry in queue.toList()) {
                val file = File(entry)
                if (!file.exists()) {
                    queue.remove(entry)
                    continue
                }

                val url = backendUrl.trimEnd('/') + "/upload"
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "deviceUuid",
                        getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
                            .getString(AppPrefs.KEY_DEVICE_UUID, "")
                            .orEmpty()
                    )
                    .addFormDataPart(
                        "photo",
                        file.name,
                        file.asRequestBody("image/jpeg".toMediaTypeOrNull())
                    )
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .build()

                val response = try {
                    withContext(Dispatchers.IO) { client.newCall(request).execute() }
                } catch (e: Exception) {
                    Log.e(TAG, "Upload failed for ${file.absolutePath}", e)
                    showStatus(getString(R.string.status_error))
                    return
                }

                response.use {
                    if (it.isSuccessful) {
                        file.delete()
                        queue.remove(entry)
                        storeQueue(prefs, queue)
                        showStatus(getString(R.string.status_uploaded))
                        delay(300)
                    } else {
                        Log.e(TAG, "Upload returned ${it.code}")
                        showStatus(getString(R.string.status_error))
                        return
                    }
                }
            }

            storeQueue(prefs, queue)
            showStatus(getString(R.string.status_ready))
        } finally {
            inProcess.set(false)
        }
    }
}
