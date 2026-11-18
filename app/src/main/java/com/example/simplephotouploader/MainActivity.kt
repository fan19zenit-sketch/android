package com.example.simplephotouploader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
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
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    companion object {
        private val captures = CaptureGate()
        private val cookSelection = CookCaptureSelection()
        private var selectedShift: CachedCookShift? = null
        private var selectedShiftScope: String? = null
        private var operatorPhotoAuthorEnabled = false
        private val unassignedSelection = CookUnassignedSelection()
    }
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var captureButton: View
    private lateinit var settingsButton: View
    private var imageCapture: ImageCapture? = null
    private var physicalRotation: Int? = null
    private val rotationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees != ORIENTATION_UNKNOWN) physicalRotation = UseCase.snapToSurfaceRotation(degrees)
            }
        }
    }
    private var cameraError = false
    private var cameraStarting = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refreshJob: Job? = null
    private var cookMode = false
    private var currentBinding: CookBinding? = null
    private var currentBindingScope: String? = null
    private var lastShiftPrompt = ""
    private var cookRefreshJob: Job? = null
    private var botRosterJob: Job? = null
    private var botRosterScope: Pair<String, CookBinding>? = null
    private var botRosterCapabilityChecked = false
    private var cookPanelKey = ""
    private val shiftForm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            val data = it.data
            val employee = data?.getStringExtra("author_id")
            val snapshot = usableShift(scopedBinding(), Instant.now())
            if (employee != null && snapshot != null && snapshot.shift.id == data.getStringExtra("shift_id") &&
                snapshot.shift.revision == data.getIntExtra("revision", -1) && employee in authorCandidates(snapshot))
                selectCurrentAuthor(snapshot, employee)
            refreshCookWorkflow()
        }
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
        if (BuildConfig.FLAVOR == "clean") statusText.doAfterTextChanged {
            statusText.visibility = if (it.isNullOrEmpty() || it.toString() == getString(R.string.camera_ready)) View.GONE else View.VISIBLE
        }
        captureButton = findViewById(R.id.captureButton)
        settingsButton = findViewById(R.id.settingsButton)
        if (BuildConfig.FLAVOR == "clean") {
            cookMode = isPizzaPhone() || BuildConfig.BUILD_TYPE == "preview" ||
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
        if (BuildConfig.FLAVOR == "clean" && rotationListener.canDetectOrientation()) rotationListener.enable()
        if (imageCapture == null && !cameraStarting &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        RemoteSupport.schedule(this)
        CookShiftPresenceWorker.enqueueNow(applicationContext)
        RemoteSupport.checkImmediately(applicationContext)
        UploadCoordinator.kick(applicationContext)
        if (BuildConfig.FLAVOR == "clean") {
            refreshCookWorkflow()
            if (BuildConfig.BUILD_TYPE != "preview" && !isPizzaPhone()) botRosterJob = scope.launch {
                cookRefreshJob?.join()
                while (isActive) {
                    refreshCameraRoster()
                    delay(15_000)
                    if (!botRosterCapabilityChecked && usableShift(scopedBinding(), Instant.now()) != null) {
                        refreshCookWorkflow()
                        cookRefreshJob?.join()
                    }
                }
            }
        }
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
        if (BuildConfig.FLAVOR == "clean") {
            rotationListener.disable()
            physicalRotation = null
        }
        refreshJob?.cancel()
        botRosterJob?.cancel()
        cookRefreshJob?.cancel()
        botRosterScope = null
        botRosterCapabilityChecked = false
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
        if (needsRosterReview()) {
            captures.finish(captureId)
            openShift()
            return
        }
        val capturedAt = instant.toString()
        var reservation: CookCaptureSelection.Reservation? = null
        var photoContext: String? = null
        var unassignedAtCapture: CookUnassignedSelection.Reservation? = null
        if (cookMode) {
            val binding = scopedBinding()
            val snapshot = usableShift(binding, instant)
            if (snapshot == null) {
                if (isPizzaPhone()) {
                    captures.finish(captureId)
                    showShiftRequired()
                    return
                }
                val choice = unassignedSelection.current(binding, CookLocalSession.identity(applicationContext), instant)
                try {
                    photoContext = CookPhotoContextFactory.createUnassigned(requireNotNull(binding), requireNotNull(choice).choice, instant)
                    unassignedAtCapture = choice
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
        val cleanCapture = BuildConfig.FLAVOR == "clean"
        val file = File(folder, (if (cleanCapture) CaptureJpeg.PREFIX else "IMG_") + captureId + ".jpg")
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
        val shutterRotation = physicalRotation ?: previewView.display?.rotation ?: Surface.ROTATION_0
        App.ioScope.launch(Dispatchers.Main.immediate) {
            try {
                val stagedId = if (cleanCapture) withContext(Dispatchers.IO) {
                    PhotoHistoryStore.addQueued(applicationContext, file.absolutePath, capturedAt, frozenContext,
                        frozenDevice.takeIf { frozenContext != null }, frozenBackend.takeIf { frozenContext != null },
                        locallyPreparing = true)
                } else null
                if (cleanCapture) {
                    check(!timedOut.get() && !isDestroyed && !isFinishing &&
                        lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                    camera.targetRotation = shutterRotation
                }
                camera.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),
                    ContextCompat.getMainExecutor(this@MainActivity), object : ImageCapture.OnImageSavedCallback {
                        override fun onError(error: ImageCaptureException) {
                            watchdog.cancel()
                            if (cleanCapture) App.ioScope.launch { PhotoHistoryStore.holdOrDropPreparation(applicationContext, file) }
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
                                    if (stagedId != null) {
                                        try {
                                            CaptureJpeg.prepare(file)
                                            PhotoHistoryStore.finishPreparation(applicationContext, file.absolutePath, stagedId,
                                                getString(R.string.camera_late_capture).takeIf { timedOut.get() })
                                        } catch (error: Exception) {
                                            PhotoHistoryStore.markError(applicationContext, file.absolutePath, getString(R.string.camera_save_error))
                                            throw error
                                        }
                                    } else if (timedOut.get()) {
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
                                    }
                                    if (!timedOut.get()) {
                                        reserved?.let { cookSelection.finish(it, true) }
                                        withContext(Dispatchers.Main) {
                                            frozenUnassigned?.let { unassignedSelection.finish(it, true) }
                                        }
                                        PhotoUploadWorker.enqueue(applicationContext)
                                        UploadCoordinator.kick(applicationContext)
                                    }
                                    withContext(Dispatchers.Main) {
                                        if (!isDestroyed) statusText.setText(if (timedOut.get()) R.string.camera_late_capture else R.string.camera_saved)
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
                if (cleanCapture) App.ioScope.launch { PhotoHistoryStore.holdOrDropPreparation(applicationContext, file) }
                reserved?.let { cookSelection.finish(it, false) }
                captures.finish(captureId)
                captureButton.isEnabled = imageCapture != null && !captures.busy
                statusText.setText(R.string.status_error)
            }
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
            .setMessage("У сотрудников одинаковые имена. Попросите управляющего уточнить их в справочнике.")
            .setPositiveButton("Открыть смену") { _, _ -> openShift() }
            .setNegativeButton(R.string.close, null).show()
    }

    private fun authorCandidates(snapshot: CachedCookShift) = (snapshot.shift.cooks +
        if (operatorPhotoAuthorEnabled && !isPizzaPhone()) snapshot.shift.operators else emptyList()).distinct()
        .filter { id -> snapshot.employees.any { it.id == id && !CookEmployeeLabels.ambiguous(it, snapshot.employees) } }

    private fun openShift() = shiftForm.launch(Intent(this, CookShiftActivity::class.java).apply {
        val snapshot = usableShift(scopedBinding(), Instant.now())
        if (snapshot != null && !needsRosterReview() && !isPizzaPhone()) {
            val ids = authorCandidates(snapshot)
            putExtra("author_ids", ids.toTypedArray())
            putExtra("author_names", ids.map { id -> CookEmployeeLabels.label(snapshot.employees.first { it.id == id }, snapshot.employees) }.toTypedArray())
            putExtra("current_author", cookSelection.persistentAuthor() ?: snapshot.shift.mainCookId)
            putExtra("shift_id", snapshot.shift.id)
            putExtra("revision", snapshot.shift.revision)
        }
        if (BuildConfig.BUILD_TYPE == "preview" && intent.getBooleanExtra("presentation_names", false)) {
            putExtra("presentation_names", true)
        }
    })

    private fun hasCaptureSelection(): Boolean {
        if (needsRosterReview()) return false
        val now = Instant.now()
        val binding = scopedBinding()
        return usableShift(binding, now) != null || (!isPizzaPhone() &&
            unassignedSelection.current(binding, CookLocalSession.identity(applicationContext), now) != null)
    }

    private fun isPizzaPhone(): Boolean = BuildConfig.FLAVOR == "clean" &&
        getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE).getString(AppPrefs.KEY_DEVICE_MODE, AppPrefs.DEVICE_MODE_MAIN) == AppPrefs.DEVICE_MODE_PIZZA

    private fun savedCurrentAuthor(snapshot: CachedCookShift, allowed: Set<String>): String? = try {
        val saved = JSONObject(CookLocalSession.preferences(applicationContext).getString(CookShiftCache.AUTHOR_KEY, null) ?: return null)
        saved.optString("employee").takeIf { it in allowed && saved.optString("shift") == snapshot.shift.id &&
            saved.optInt("revision") == snapshot.shift.revision }
    } catch (_: Exception) { null }

    private fun selectCurrentAuthor(snapshot: CachedCookShift, employeeId: String) {
        if (usableShift(scopedBinding(), Instant.now()) != snapshot || needsRosterReview()) return
        val value = CookShiftCache.encodeAuthor(snapshot.shift, employeeId)
        if (!CookLocalSession.preferences(applicationContext).edit().putString(CookShiftCache.AUTHOR_KEY, value).commit()) {
            android.widget.Toast.makeText(this, "Не удалось сохранить выбор", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        cookSelection.selectPersistent(employeeId)
        refreshCookPanel()
    }

    private fun scopedBinding(): CookBinding? = CookScopeGuard.binding(
        currentBinding, currentBindingScope, CookLocalSession.identity(applicationContext))

    private fun usableShift(binding: CookBinding?, now: Instant): CachedCookShift? {
        val candidate = selectedShift ?: return null
        if (!CookScopeGuard.matches(selectedShiftScope, CookLocalSession.identity(applicationContext))) return null
        return candidate.takeIf { CookShiftPolicy.usable(it.shift, binding, it.binding, now) }
    }

    private fun needsRosterReview(): Boolean {
        val shift = usableShift(scopedBinding(), Instant.now())?.shift ?: return false
        return CookShiftCache(CookLocalSession.preferences(applicationContext)).requiresAuthorReview(shift)
    }

    private suspend fun refreshCameraRoster() {
        val identity = CookLocalSession.identity(applicationContext)
        val binding = scopedBinding() ?: return
        val previous = usableShift(binding, Instant.now()) ?: return
        if (botRosterScope != (identity to binding) || previous.offlineShift != null || captures.busy) return
        val local = CookLocalSession.preferences(applicationContext)
        val cache = CookShiftCache(local)
        fun stillCurrent(): Boolean = !captures.busy && botRosterScope == (identity to binding) &&
            scopedBinding() == binding && usableShift(binding, Instant.now()) == previous &&
            !local.contains(CookOfflineSelectionCache.KEY) && CookPendingShiftStore(local).load() == null &&
            cache.load() == previous
        try {
            if (!stillCurrent()) return
            val device = CookLocalSession.offlineScope(applicationContext, binding)
            val client = CookWorkflowClient(device.backendUrl)
            val (template, employees) = withContext(Dispatchers.IO) {
                val dao = CookOfflineDatabase.get(applicationContext).queue()
                if (dao.pendingShifts(device.backendUrl, device.deviceUuid, binding.branchRef, binding.revision).isNotEmpty())
                    return@withContext null
                val template = client.branchShift(device.deviceUuid, previous.shift.operationalDate, binding) ?: return@withContext null
                if (!CookCameraRosterRefresh.newer(template, previous.shift, Instant.now()) ||
                    client.initializeBinding(device.deviceUuid) != binding) return@withContext null
                val roster = client.roster(device.deviceUuid)?.takeIf { it.branchRef == binding.branchRef } ?: return@withContext null
                template to roster.employees
            } ?: return
            if (!stillCurrent()) return
            val participants = (template.cooks + template.operators + template.assistants).toSet()
            val known = employees.associateBy { it.id }
            if (participants.any { id -> known[id]?.let { CookEmployeeLabels.ambiguous(it, employees) } != false }) return
            val roster = (template.cooks + if (operatorPhotoAuthorEnabled) template.operators else emptyList()).toSet()
            val main = previous.shift.mainCookId
            fun authorStillValid(): Boolean = main in template.cooks && cookSelection.canRefreshRoster(roster, main)
            if (!authorStillValid()) {
                cache.requireAuthorReview(previous.shift)
                refreshCookPanel()
                return
            }
            val updated = CookCameraRosterRefresh.withTemplate(previous.shift, template)
            val saved = withContext(Dispatchers.IO) {
                client.confirmShift(device.deviceUuid, CookCameraRosterRefresh.requestId(updated, template), updated, binding)
            } ?: return
            if (saved.first != updated.id || saved.second <= previous.shift.revision || !stillCurrent()) return
            if (!authorStillValid()) {
                cache.requireAuthorReview(previous.shift)
                refreshCookPanel()
                return
            }
            val snapshot = CachedCookShift(binding, updated.copy(revision = saved.second), employees)
            if (!cache.saveRefreshed(snapshot, cookSelection.persistentAuthor())) return
            selectedShift = snapshot
            cookSelection.refreshRoster(roster, main)
            refreshCookPanel()
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Retry on the next foreground refresh; keep the last confirmed roster. */ }
    }

    private fun styleCookChip(chip: com.google.android.material.chip.Chip) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        chip.setTextColor(android.content.res.ColorStateList(states, intArrayOf(android.graphics.Color.BLACK, android.graphics.Color.WHITE)))
        chip.chipBackgroundColor = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(getColor(R.color.sushi_pink), android.graphics.Color.rgb(42, 38, 39)))
        chip.setEnsureMinTouchTargetSize(true)
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
                val enabled = isPizzaPhone() || preview || local.getBoolean("workflow_enabled", false)
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
              currentBindingScope = expectedScope
              val candidate = state.third?.takeIf { it.binding == currentBinding }
              val operatorAuthor = preview || (currentBinding != null && local.getString("operator_photo_author_binding", null) ==
                  operatorAuthorBindingKey(currentBinding!!))
              if (candidate != selectedShift || selectedShiftScope != expectedScope || operatorPhotoAuthorEnabled != operatorAuthor) {
                selectedShift = candidate
                selectedShiftScope = expectedScope
                operatorPhotoAuthorEnabled = operatorAuthor
                unassignedSelection.clear()
                if (candidate != null) {
                    val allowed = (candidate.shift.cooks +
                        if (operatorAuthor && !isPizzaPhone()) candidate.shift.operators else emptyList()).toSet()
                    cookSelection.setRoster(allowed, candidate.shift.mainCookId)
                    savedCurrentAuthor(candidate, allowed)?.let { cookSelection.selectPersistent(it) }
                }
              }
              refreshCookPanel()
              maybePromptForShift()
              if (verified != null) CookShiftPresenceWorker.enqueueNow(applicationContext)
            }
            applyLocalState()
            if (!preview) {
                var unsupported = false
                val verified = withContext(Dispatchers.IO) {
                    try {
                        val prefs = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
                        val backend = prefs.getString(AppPrefs.KEY_BACKEND_URL, AppPrefs.DEFAULT_BACKEND_URL).orEmpty()
                        val device = prefs.getString(AppPrefs.KEY_DEVICE_UUID, "").orEmpty()
                        if (backend.isBlank() || device.isBlank()) null else {
                            val client = CookWorkflowClient(backend)
                            val capabilities = client.capabilities(device)
                            if (capabilities?.supportsAttributedCapture != true) {
                                unsupported = true
                                local.edit().remove("operator_photo_author_binding").commit()
                                null
                            } else {
                                val binding = client.initializeBinding(device)
                                ensureActive()
                                if (binding.timezone == null || CookLocalSession.identity(applicationContext) != expectedScope) null
                                else {
                                    val cache = CookDeviceRosterCache(local)
                                    val oldShift = CookShiftCache(local).load()
                                    val saved = if (!local.contains(CookDeviceRosterCache.KEY) && oldShift?.binding == binding)
                                        cache.save(binding, oldShift.employees) else cache.saveBinding(binding)
                                    if (!saved) null else {
                                        local.edit().putBoolean("workflow_enabled", true).apply {
                                            if (capabilities.operatorPhotoAuthor) putString("operator_photo_author_binding",
                                                operatorAuthorBindingKey(binding))
                                            else remove("operator_photo_author_binding")
                                        }.commit()
                                        binding to capabilities.branchShiftTemplate
                                    }
                                }
                            }
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { null }
                }
                botRosterCapabilityChecked = verified != null || unsupported
                botRosterScope = verified?.takeIf { it.second && !isPizzaPhone() }?.let { expectedScope to it.first }
                if (verified != null) applyLocalState(verified.first) else applyLocalState()
            }
        }
    }

    private fun operatorAuthorBindingKey(binding: CookBinding): String = JSONObject()
        .put("branch", binding.branchRef).put("revision", binding.revision).toString()

    private fun maybePromptForShift() {
        val binding = scopedBinding()
        val timezone = binding?.timezone ?: return
        val now = if (BuildConfig.BUILD_TYPE == "preview" && intent.hasExtra("test_shift_clock_ms"))
            Instant.ofEpochMilli(intent.getLongExtra("test_shift_clock_ms", 0)) else Instant.now()
        val candidate = usableShift(binding, now)
        val needsShift = if (isPizzaPhone()) candidate == null else
            CookShiftPolicy.needsConfirmation(candidate?.shift, binding, candidate?.binding, timezone, now)
        if (cookMode && needsShift) {
            val day = now.atZone(ZoneId.of(timezone)).toLocalDate().toString()
            if (lastShiftPrompt != day && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                lastShiftPrompt = day
                openShift()
            }
        }
    }

    private fun refreshCookPanel() {
        val panel = findViewById<View>(R.id.cookPanel) ?: return
        panel.visibility = if (cookMode) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.shiftShortcut)?.apply {
            visibility = panel.visibility; setOnClickListener { openShift() }
        }
        if (!cookMode) return
        val choices = findViewById<LinearLayout>(R.id.cookChoices)
        val binding = scopedBinding()
        val snapshot = usableShift(binding, Instant.now())
        val selected = cookSelection.current()
        val source = if (snapshot != null) cookSelection.currentSource() else
            unassignedSelection.current(binding, CookLocalSession.identity(applicationContext), Instant.now())?.choice
        val review = needsRosterReview()
        val key = "$snapshot|$selected|$source|$binding|$operatorPhotoAuthorEnabled|${isPizzaPhone()}|$review"
        if (key == cookPanelKey) { maybePromptForShift(); return }
        cookPanelKey = key
        if (review) {
            choices.removeAllViews()
            choices.addView(com.google.android.material.chip.Chip(this).apply {
                styleCookChip(this); text = "Выбрать автора"; setOnClickListener { openShift() }
            })
            return
        }
        choices.removeAllViews()
        val authorIds = if (snapshot == null) emptyList() else (snapshot.shift.cooks +
                if (operatorPhotoAuthorEnabled && !isPizzaPhone()) snapshot.shift.operators else emptyList()).distinct()
        val main = cookSelection.persistentAuthor() ?: snapshot?.shift?.mainCookId
        val operators = snapshot?.shift?.operators.orEmpty().filter { it in authorIds && !isPizzaPhone() }
        // Operators stay on the camera even when several cooks are on duty.
        val pinned = (listOfNotNull(main) + operators + listOfNotNull(selected)).distinct()
        val shown = (pinned + authorIds).distinct().take(maxOf(if (operators.isEmpty()) 2 else 3, pinned.size))
        if (snapshot != null) shown.forEach { id ->
            val employee = snapshot.employees.firstOrNull { it.id == id } ?: return@forEach
            val active = id == selected
            val name = CookEmployeeLabels.label(employee, snapshot.employees)
            val role = if (id == main) "Основной"
                else if (id in operators && !active) "Оператор"
                else if (isPizzaPhone()) "До смены повара" else "1 фото"
            val tone = if (active) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            val unit = resources.displayMetrics.density
            choices.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val pad = ((if (shown.size >= 3) 6 else 10) * unit).toInt()
                setPadding(pad, pad / 2, pad, pad / 2)
                minimumHeight = (48 * unit).toInt()
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (4 * unit).toInt() }
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = resources.getDimension(R.dimen.sushi_radius)
                    setColor(if (active) getColor(R.color.sushi_pink) else android.graphics.Color.rgb(39,39,39))
                }
                isSelected = active
                contentDescription = listOf(name, role, if (active) "Выбран" else "").filter { it.isNotEmpty() }.joinToString(", ")
                addView(TextView(context).apply {
                    text = name; tag = id; textSize = 14f; sushiFont(R.attr.sushiLabelFont)
                    setTextColor(tone); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(context).apply { text = (if (active) "✓  " else "") + role; textSize = 12f; setTextColor(tone) })
                if (authorIds.size > 1 && !CookEmployeeLabels.ambiguous(employee, snapshot.employees))
                    setOnClickListener {
                        if (isPizzaPhone()) selectCurrentAuthor(snapshot, id)
                        else { cookSelection.selectOneShot(id); refreshCookPanel() }
                    }
            })
        }
        if (binding?.timezone != null && !isPizzaPhone()) {
            val more = authorIds.filterNot { it in shown }.mapNotNull { id -> snapshot?.employees?.find { it.id == id } }
                .filterNot { CookEmployeeLabels.ambiguous(it, snapshot!!.employees) }
            choices.addView(com.google.android.material.chip.Chip(this).apply {
                styleCookChip(this); text = when (source) { "shared" -> "Совместно"; "explicit_unknown" -> "Без автора"; else -> "Ещё" }; sushiFont(R.attr.sushiLabelFont)
                setOnClickListener {
                    val labels = more.map { CookEmployeeLabels.label(it, snapshot!!.employees) } + listOf("Совместно", "Автор неизвестен")
                    AlertDialog.Builder(this@MainActivity).setTitle("Кто приготовил").setItems(labels.toTypedArray()) { _, i ->
                        if (i < more.size) cookSelection.selectOneShot(more[i].id) else {
                            val kind = if (i == more.size) "shared" else "explicit_unknown"
                            if (snapshot != null) cookSelection.selectUnassigned(kind)
                            else unassignedSelection.select(kind, binding, CookLocalSession.identity(applicationContext), Instant.now())
                        }
                        refreshCookPanel()
                    }.show()
                }
            })
        }
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
