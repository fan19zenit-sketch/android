package com.example.simplephotouploader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    companion object { private val capturing = AtomicBoolean(false) }
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var captureButton: View
    private lateinit var settingsButton: View
    private var imageCapture: ImageCapture? = null
    private var cameraError = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refreshJob: Job? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else showPermissionError()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        captureButton = findViewById(R.id.captureButton)
        settingsButton = findViewById(R.id.settingsButton)
        captureButton.isEnabled = false
        val prefs = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
        if (prefs.getString(AppPrefs.KEY_CITY, null).isNullOrBlank()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        findViewById<TextView>(R.id.headerSubtitle)?.text =
            getString(R.string.camera_city, prefs.getString(AppPrefs.KEY_CITY, "").orEmpty())
        findViewById<TextView>(R.id.versionText)?.text = getString(R.string.app_version, BuildConfig.VERSION_NAME)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else permission.launch(Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        captureButton.setOnClickListener { takePhoto() }
        settingsButton.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        refreshJob = scope.launch {
            while (isActive) {
                val items = withContext(Dispatchers.IO) { PhotoHistoryStore.list(this@MainActivity) }
                val queued = items.count { it.queuedInUploadQueue }
                val server = items.count { it.status in setOf("uploaded_to_server", "sending_to_chat") }
                val issues = items.count { it.status in setOf("error", "needs_review") }
                statusText.text = when {
                    cameraError -> getString(R.string.camera_unavailable)
                    capturing.get() -> getString(R.string.camera_capturing)
                    filesDir.usableSpace < 200L * 1024 * 1024 -> getString(R.string.camera_low_storage)
                    issues > 0 -> getString(R.string.camera_issues, issues)
                    queued > 0 -> getString(R.string.camera_queued, queued)
                    server > 0 -> getString(R.string.camera_server, server)
                    else -> getString(R.string.camera_ready)
                }
                captureButton.isEnabled = imageCapture != null && !capturing.get()
                val thumb = items.firstOrNull()?.thumbnailPath
                (settingsButton as? ImageView)?.let { image ->
                    if (image.tag != thumb) {
                        val bitmap = withContext(Dispatchers.IO) { thumb?.let { BitmapFactory.decodeFile(it) } }
                        if (bitmap != null) image.setImageBitmap(bitmap)
                        image.tag = thumb
                    }
                }
                delay(2000)
            }
        }
    }

    override fun onPause() {
        refreshJob?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event?.repeatCount == 0) takePhoto()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isDestroyed || isFinishing) return@addListener
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                imageCapture = capture
                cameraError = false
                captureButton.isEnabled = !capturing.get()
            } catch (error: Exception) {
                Log.e("Camera", "Camera unavailable", error)
                cameraError = true
                statusText.setText(R.string.camera_unavailable)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val camera = imageCapture ?: return
        if (filesDir.usableSpace < 15L * 1024 * 1024) {
            AlertDialog.Builder(this).setMessage(R.string.camera_low_storage)
                .setPositiveButton(R.string.close, null).show()
            return
        }
        if (!capturing.compareAndSet(false, true)) return
        captureButton.isEnabled = false
        statusText.setText(R.string.camera_capturing)
        val folder = File(filesDir, "photos").apply { mkdirs() }
        val file = File(folder, "IMG_${UUID.randomUUID()}.jpg")
        try {
            camera.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                    override fun onError(error: ImageCaptureException) {
                        capturing.set(false)
                        captureButton.isEnabled = true
                        statusText.setText(R.string.status_error)
                    }
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        // Application scope survives screen rotation while persisting the capture.
                        App.ioScope.launch {
                            try {
                                PhotoHistoryStore.addQueued(applicationContext, file.absolutePath)
                                PhotoUploadWorker.enqueue(applicationContext)
                                withContext(Dispatchers.Main) {
                                    if (!isDestroyed) statusText.setText(R.string.camera_saved)
                                }
                            } catch (error: Exception) {
                                Log.e("Camera", "Could not queue saved photo", error)
                                withContext(Dispatchers.Main) {
                                    if (!isDestroyed) statusText.setText(R.string.camera_save_error)
                                }
                            } finally {
                                capturing.set(false)
                                withContext(Dispatchers.Main) {
                                    if (!isDestroyed) captureButton.isEnabled = true
                                }
                            }
                        }
                    }
                })
        } catch (error: Exception) {
            capturing.set(false)
            captureButton.isEnabled = true
            statusText.setText(R.string.status_error)
        }
    }

    private fun showPermissionError() {
        AlertDialog.Builder(this).setTitle(R.string.permission_required_title)
            .setMessage(R.string.permission_required_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                })
            }.setNegativeButton(R.string.close) { _, _ -> finish() }.show()
    }
}
