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
import android.widget.LinearLayout
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
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    companion object {
        private val captures = CaptureGate()
        private val cookSelection = CookCaptureSelection()
        private var selectedShift: CachedCookShift? = null
        private var unassignedChoice: String? = null
        private var unassignedRevision = 0L
    }
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var captureButton: View
    private lateinit var settingsButton: View
    private var imageCapture: ImageCapture? = null
    private var cameraError = false
    private var cameraStarting = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refreshJob: Job? = null
    private var cookMode = false
    private var currentBinding: CookBinding? = null
    private var lastShiftPrompt = ""
    private var cookRefreshJob: Job? = null
    private var cookPanelKey = ""
    private val shiftForm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) refreshCookWorkflow()
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) {
            startCamera()
            if (BuildConfig.FLAVOR == "clean") requestNotificationPermissionIfNeeded()
        } else showPermissionError()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        captureButton = findViewById(R.id.captureButton)
        settingsButton = findViewById(R.id.settingsButton)
        if (BuildConfig.FLAVOR == "clean") {
            cookMode = BuildConfig.BUILD_TYPE == "preview" ||
                CookLocalSession.preferences(applicationContext).getBoolean("workflow_enabled", false)
        }
        captureButton.isEnabled = false
        val prefs = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
        if (BuildConfig.BUILD_TYPE == "preview" && prefs.getString(AppPrefs.KEY_CITY, null).isNullOrBlank()) {
            prefs.edit().putString(AppPrefs.KEY_CITY, getString(R.string.preview_city)).apply()
        }
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
            if (BuildConfig.FLAVOR == "clean") requestNotificationPermissionIfNeeded()
        } else permission.launch(Manifest.permission.CAMERA)
        if (BuildConfig.FLAVOR != "clean") requestNotificationPermissionIfNeeded()
        captureButton.setOnClickListener { takePhoto() }
        settingsButton.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        if (imageCapture == null && !cameraStarting &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        RemoteSupport.schedule(this)
        RemoteSupport.checkImmediately(applicationContext)
        UploadCoordinator.kick(applicationContext)
        if (BuildConfig.FLAVOR == "clean") refreshCookWorkflow()
        refreshJob = scope.launch {
            while (isActive) {
                val items = withContext(Dispatchers.IO) { PhotoHistoryStore.list(this@MainActivity) }
                val queued = items.count { it.queuedInUploadQueue }
                val server = items.count { it.status in setOf("uploaded_to_server", "sending_to_chat") }
                val issues = items.count { it.status in setOf("error", "needs_review", "delivery_unknown") }
                val shiftSync = if (cookMode) CookLocalSession.preferences(this@MainActivity)
                    .getString(CookOfflineSyncWorker.SYNC_STATE, null) else null
                statusText.text = when {
                    cameraError -> getString(R.string.camera_unavailable)
                    captures.busy -> getString(R.string.camera_capturing)
                    cookMode && !hasCaptureSelection() -> getString(R.string.camera_shift_required)
                    filesDir.usableSpace < 200L * 1024 * 1024 -> getString(R.string.camera_low_storage)
                    RemoteSupport.settings(this@MainActivity).uploadsPaused -> getString(R.string.support_paused)
                    issues > 0 -> getString(R.string.camera_issues, issues)
                    shiftSync == "attention" -> getString(R.string.camera_shift_sync_attention)
                    queued > 0 -> getString(R.string.camera_queued, queued)
                    server > 0 -> getString(R.string.camera_server, server)
                    shiftSync == "waiting" -> getString(R.string.camera_shift_sync_pending)
                    else -> getString(R.string.camera_ready)
                }
                captureButton.isEnabled = imageCapture != null && !captures.busy
                if (cookMode) refreshCookPanel()
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
            if (event?.repeatCount == 0 && RemoteSupport.settings(this).volumeCapture) takePhoto()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startCamera() {
        if (cameraStarting) return
        cameraStarting = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraStarting = false
            if (isDestroyed || isFinishing) return@addListener
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                imageCapture = capture
                cameraError = false
                captureButton.isEnabled = !captures.busy
            } catch (error: Exception) {
                Log.e("Camera", "Camera unavailable", error)
                cameraError = true
                RemoteSupport.event(this, "camera_unavailable")
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
        val captureId = captures.begin() ?: return
        val instant = Instant.now()
        val capturedAt = instant.toString()
        var reservation: CookCaptureSelection.Reservation? = null
        var photoContext: String? = null
        var unassignedAtCapture: Pair<String, Long>? = null
        if (cookMode) {
            val snapshot = selectedShift
            val binding = currentBinding
            if (snapshot == null || !CookShiftPolicy.usable(snapshot.shift, binding, snapshot.binding, instant)) {
                val choice = unassignedChoice
                try {
                    photoContext = CookPhotoContextFactory.createUnassigned(requireNotNull(binding), requireNotNull(choice), instant)
                    unassignedAtCapture = choice to unassignedRevision
                } catch (_: Exception) {
                    captures.finish(captureId)
                    showShiftRequired()
                    return
                }
            } else {
                reservation = cookSelection.reserve()
                if (reservation == null) { captures.finish(captureId); return }
                try {
                    val employee = snapshot.employees.firstOrNull { it.id == reservation.cookId }
                    if (employee != null && CookEmployeeLabels.ambiguous(employee, snapshot.employees)) {
                        cookSelection.finish(reservation, false)
                        captures.finish(captureId)
                        showAmbiguousCook()
                        return
                    }
                    val offline = snapshot.offlineShift
                    photoContext = if (offline != null) {
                        val selected = reservation.cookId?.let { CookOfflineSelectionCache.cookRef(offline, it) }
                        CookPhotoContextFactory.createProvisional(requireNotNull(snapshot.offlineScope), offline,
                            employee, selected, reservation.selectionSource, instant)
                    } else CookPhotoContextFactory.create(requireNotNull(binding), snapshot.binding,
                        snapshot.shift, employee, reservation.selectionSource, instant)
                } catch (error: Exception) {
                    cookSelection.finish(reservation, false)
                    captures.finish(captureId)
                    showShiftRequired()
                    return
                }
            }
        }
        val reserved = reservation
        val frozenContext = photoContext
        val frozenUnassigned = unassignedAtCapture
        val capturePrefs = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
        val frozenDevice = capturePrefs.getString(AppPrefs.KEY_DEVICE_UUID, null)?.takeIf { it.isNotBlank() }
        val frozenBackend = capturePrefs.getString(AppPrefs.KEY_BACKEND_URL, AppPrefs.DEFAULT_BACKEND_URL)
            ?.trimEnd('/')?.takeIf { it.isNotBlank() }
        val timedOut = AtomicBoolean(false)
        captureButton.isEnabled = false
        statusText.setText(R.string.camera_capturing)
        val folder = File(filesDir, "photos").apply { mkdirs() }
        val file = File(folder, "IMG_${UUID.randomUUID()}.jpg")
        val watchdog = App.ioScope.launch {
            delay(30_000)
            withContext(Dispatchers.Main) {
                if (captures.finish(captureId)) {
                    timedOut.set(true)
                    reserved?.let { cookSelection.finish(it, false) }
                    RemoteSupport.event(applicationContext, "camera_timeout")
                    if (!isDestroyed && !isFinishing) {
                        imageCapture = null
                        captureButton.isEnabled = false
                        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) startCamera()
                        AlertDialog.Builder(this@MainActivity).setMessage(R.string.camera_capture_timeout)
                            .setPositiveButton(R.string.close, null).show()
                    }
                }
            }
        }
        try {
            camera.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                    override fun onError(error: ImageCaptureException) {
                        watchdog.cancel()
                        if (!timedOut.get()) RemoteSupport.event(applicationContext, "camera_capture_error")
                        reserved?.let { cookSelection.finish(it, false) }
                        if (captures.finish(captureId) && !isDestroyed) {
                            captureButton.isEnabled = imageCapture != null
                            statusText.setText(R.string.status_error)
                        }
                    }
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        watchdog.cancel()
                        // Application scope survives screen rotation while persisting the capture.
                        App.ioScope.launch {
                            try {
                                if (timedOut.get()) {
                                    val database = PilotDatabase.get(applicationContext)
                                    database.runInTransaction {
                                        PhotoHistoryStore.addQueued(applicationContext, file.absolutePath, capturedAt)
                                        database.photoRecordDao().getByPhotoPath(file.absolutePath)?.let {
                                            database.photoRecordDao().update(it.copy(status = "needs_review",
                                                queuedInUploadQueue = false, error = getString(R.string.camera_late_capture)))
                                        }
                                    }
                                } else {
                                    PhotoHistoryStore.addQueued(applicationContext, file.absolutePath, capturedAt, frozenContext,
                                        frozenDevice.takeIf { frozenContext != null }, frozenBackend.takeIf { frozenContext != null })
                                    reserved?.let { cookSelection.finish(it, true) }
                                    withContext(Dispatchers.Main) {
                                        if (frozenUnassigned != null && unassignedChoice == frozenUnassigned.first &&
                                            unassignedRevision == frozenUnassigned.second) {
                                            unassignedChoice = null
                                            unassignedRevision++
                                        }
                                    }
                                    PhotoUploadWorker.enqueue(applicationContext)
                                    UploadCoordinator.kick(applicationContext)
                                }
                                withContext(Dispatchers.Main) {
                                    if (!isDestroyed) statusText.setText(R.string.camera_saved)
                                }
                            } catch (error: Exception) {
                                reserved?.let { cookSelection.finish(it, false) }
                                RemoteSupport.event(applicationContext, "queue_save_error")
                                Log.e("Camera", "Could not queue saved photo", error)
                                withContext(Dispatchers.Main) {
                                    if (!isDestroyed) statusText.setText(R.string.camera_save_error)
                                }
                            } finally {
                                captures.finish(captureId)
                                withContext(Dispatchers.Main) {
                                    if (!isDestroyed) captureButton.isEnabled = imageCapture != null && !captures.busy
                                }
                            }
                        }
                    }
                })
        } catch (error: Exception) {
            watchdog.cancel()
            reserved?.let { cookSelection.finish(it, false) }
            captures.finish(captureId)
            captureButton.isEnabled = imageCapture != null && !captures.busy
            statusText.setText(R.string.status_error)
        }
    }

    private fun showShiftRequired() {
        if (!isFinishing && !isDestroyed) AlertDialog.Builder(this)
            .setMessage("Подтвердите состав смены перед съёмкой")
            .setPositiveButton("Открыть смену") { _, _ -> openShift() }
            .setNegativeButton(R.string.close, null).show()
    }

    private fun showAmbiguousCook() {
        if (!isFinishing && !isDestroyed) AlertDialog.Builder(this)
            .setMessage("У сотрудников одинаковые имена. Уточните имена в справочнике или выберите «Автор неизвестен».")
            .setPositiveButton("Понятно", null).show()
    }

    private fun openShift() = shiftForm.launch(Intent(this, CookShiftActivity::class.java))

    private fun hasCaptureSelection(): Boolean {
        val snapshot = selectedShift
        return (snapshot != null && CookShiftPolicy.usable(snapshot.shift, currentBinding, snapshot.binding, Instant.now())) ||
            (currentBinding?.timezone != null && unassignedChoice != null)
    }

    private fun styleCookChip(chip: com.google.android.material.chip.Chip) {
        chip.chipBackgroundColor = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(android.graphics.Color.rgb(186, 56, 127), android.graphics.Color.rgb(48, 48, 48)))
        chip.setTextColor(android.graphics.Color.WHITE)
        chip.isAllCaps = false
    }

    private fun refreshCookWorkflow() {
        cookRefreshJob?.cancel()
        cookRefreshJob = scope.launch {
            val local = CookLocalSession.preferences(applicationContext)
            val expectedScope = CookLocalSession.identity(applicationContext)
            val preview = BuildConfig.BUILD_TYPE == "preview"
            suspend fun applyLocalState(verified: CookBinding? = null) {
              val state = withContext(Dispatchers.IO) {
                val cached = CookShiftCache(local).load()
                val roster = CookDeviceRosterCache(local).load()
                val prefs = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
                val enabled = preview || local.getBoolean("workflow_enabled", false)
                val binding = verified ?: if (local.contains(CookDeviceRosterCache.KEY)) roster?.binding else cached?.binding
                val offline = if (binding != null && local.contains(CookOfflineSelectionCache.KEY)) {
                    runCatching {
                        val scope = CookLocalSession.offlineScope(applicationContext, binding)
                        val dao = CookOfflineDatabase.get(applicationContext).queue()
                        val active = CookOfflineSelectionCache(local, dao, scope).load()
                            ?.takeIf { CookShiftPolicy.usable(it.shift, binding, it.binding, Instant.now()) }
                        if (!preview && (dao.pendingEmployees(scope.backendUrl, scope.deviceUuid, binding.branchRef, binding.revision).isNotEmpty()
                                || dao.pendingShifts(scope.backendUrl, scope.deviceUuid, binding.branchRef, binding.revision).isNotEmpty())) {
                            val city = prefs.getString(AppPrefs.KEY_CITY, "").orEmpty()
                            runCatching { CookOfflineSyncWorker.enqueue(applicationContext, scope, city) }
                        }
                        active
                    }.getOrNull()
                } else null
                Triple(enabled, binding, CookShiftSelection.choose(local.contains(CookOfflineSelectionCache.KEY), offline, cached))
              }
              if (CookLocalSession.identity(applicationContext) != expectedScope) return
              cookMode = state.first
              currentBinding = state.second
              val candidate = state.third?.takeIf { it.binding == currentBinding }
              if (candidate != selectedShift) {
                selectedShift = candidate
                unassignedChoice = null
                unassignedRevision++
                if (candidate != null) cookSelection.setRoster(candidate.shift.cooks.toSet(), candidate.shift.mainCookId)
              }
              refreshCookPanel()
              maybePromptForShift()
            }
            applyLocalState()
            if (!preview) {
                val verified = withContext(Dispatchers.IO) {
                    try {
                        val prefs = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
                        val backend = prefs.getString(AppPrefs.KEY_BACKEND_URL, AppPrefs.DEFAULT_BACKEND_URL).orEmpty()
                        val device = prefs.getString(AppPrefs.KEY_DEVICE_UUID, "").orEmpty()
                        if (backend.isBlank() || device.isBlank()) null else {
                            val client = CookWorkflowClient(backend)
                            if (client.capabilities()?.supportsAttributedCapture != true) null else {
                                val binding = client.initializeBinding(device)
                                ensureActive()
                                if (binding.timezone == null || CookLocalSession.identity(applicationContext) != expectedScope) null
                                else {
                                    val cache = CookDeviceRosterCache(local)
                                    val oldShift = CookShiftCache(local).load()
                                    val saved = if (!local.contains(CookDeviceRosterCache.KEY) && oldShift?.binding == binding)
                                        cache.save(binding, oldShift.employees) else cache.saveBinding(binding)
                                    if (!saved) null else {
                                        local.edit().putBoolean("workflow_enabled", true).commit()
                                        binding
                                    }
                                }
                            }
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { null }
                }
                if (verified != null) applyLocalState(verified)
            }
        }
    }

    private fun maybePromptForShift() {
        val binding = currentBinding
        val timezone = binding?.timezone ?: return
        val candidate = selectedShift
        if (cookMode && CookShiftPolicy.needsConfirmation(candidate?.shift, binding, candidate?.binding, timezone, Instant.now())) {
            val day = Instant.now().atZone(ZoneId.of(timezone)).toLocalDate().toString()
            if (lastShiftPrompt != day && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                lastShiftPrompt = day
                openShift()
            }
        }
    }

    private fun refreshCookPanel() {
        val panel = findViewById<View>(R.id.cookPanel) ?: return
        panel.visibility = if (cookMode) View.VISIBLE else View.GONE
        if (!cookMode) return
        val next = findViewById<TextView>(R.id.cookNext)
        val choices = findViewById<LinearLayout>(R.id.cookChoices)
        val snapshot = selectedShift?.takeIf { CookShiftPolicy.usable(it.shift, currentBinding, it.binding, Instant.now()) }
        val selected = cookSelection.current()
        val source = if (snapshot != null) cookSelection.currentSource() else unassignedChoice
        val key = "$snapshot|$selected|$source|$currentBinding"
        if (key == cookPanelKey) { maybePromptForShift(); return }
        cookPanelKey = key
        val nextLabel = when (source) {
            "explicit_unknown" -> "Следующее фото: автор неизвестен"
            "shared" -> "Следующее фото: совместная работа"
            else -> snapshot?.employees?.firstOrNull { it.id == selected }?.let { "Следующее фото: ${CookEmployeeLabels.label(it, snapshot.employees)}" }
                ?: "Смена не подтверждена"
        }
        next.text = if (snapshot?.offlineShift != null) "$nextLabel · локальная смена" else nextLabel
        choices.removeAllViews()
        if (snapshot != null) snapshot.shift.cooks.forEach { id ->
            val employee = snapshot.employees.firstOrNull { it.id == id } ?: return@forEach
            choices.addView(com.google.android.material.chip.Chip(this).apply {
                styleCookChip(this)
                text = CookEmployeeLabels.label(employee, snapshot.employees) + if (id == snapshot.shift.mainCookId) " · основной" else ""
                isCheckable = true; isChecked = id == selected
                isEnabled = !CookEmployeeLabels.ambiguous(employee, snapshot.employees)
                setOnClickListener { cookSelection.selectOneShot(id); refreshCookPanel() }
            })
        }
        if (currentBinding?.timezone != null) {
            listOf("explicit_unknown" to "Автор неизвестен", "shared" to "Совместно").forEach { (value, title) ->
                choices.addView(com.google.android.material.chip.Chip(this).apply {
                    styleCookChip(this)
                    text = title; isCheckable = true; isChecked = source == value
                    setOnClickListener {
                        if (snapshot != null) cookSelection.selectUnassigned(value)
                        else { unassignedChoice = value; unassignedRevision++ }
                        refreshCookPanel()
                    }
                })
            }
        }
        choices.addView(com.google.android.material.chip.Chip(this).apply { styleCookChip(this); text = "Смена"; setOnClickListener { openShift() } })
        maybePromptForShift()
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
