package com.example.simplephotouploader

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID

class SetupActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_FORCE_EDIT = "force_edit"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val isCleanApp = BuildConfig.FLAVOR == "clean"
        val prefs = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
        val forceEdit = intent.getBooleanExtra(EXTRA_FORCE_EDIT, false)
        val backendUrl = AppPrefs.DEFAULT_BACKEND_URL
        val savedCity = prefs.getString(AppPrefs.KEY_CITY, null)
        val savedDevice = prefs.getString(AppPrefs.KEY_DEVICE_UUID, null)
        val modeOnly = isCleanApp && !forceEdit && AppPrefs.needsPilotModeChoice(savedCity, savedDevice,
            prefs.getString(AppPrefs.KEY_MODE_CONFIRMED_DEVICE, null))
        if (!modeOnly && !forceEdit && !savedCity.isNullOrEmpty()) {
            startMain()
            return
        }

        val instructions = findViewById<TextView>(R.id.tvInstructions)
        val cityInput = findViewById<EditText>(R.id.etCity)
        val transportLabel = findViewById<TextView>(R.id.tvTransportLabel)
        val transportGroup = findViewById<RadioGroup>(R.id.rgTransport)
        val deviceModeGroup = findViewById<RadioGroup>(R.id.rgDeviceMode)
        val maxChatLabel = findViewById<TextView>(R.id.tvMaxChatIdLabel)
        val maxChatInput = findViewById<EditText>(R.id.etMaxChatId)
        val telegramChatLabel = findViewById<TextView>(R.id.tvTelegramChatIdLabel)
        val telegramChatInput = findViewById<EditText>(R.id.etTelegramChatId)
        val telegramThreadLabel = findViewById<TextView>(R.id.tvTelegramThreadIdLabel)
        val telegramThreadInput = findViewById<EditText>(R.id.etTelegramThreadId)
        val button = findViewById<Button>(R.id.btnSave)
        findViewById<Button>(R.id.btnSetupSupport).setOnClickListener {
            startActivity(Intent(this, SupportActivity::class.java))
        }

        if (isCleanApp) {
            instructions.text = getString(R.string.setup_instructions_clean)
            findViewById<TextView>(R.id.tvDeviceMode).visibility = android.view.View.VISIBLE
            deviceModeGroup.visibility = android.view.View.VISIBLE
            val savedMode = prefs.getString(AppPrefs.KEY_DEVICE_MODE, null)
            if (modeOnly) deviceModeGroup.clearCheck()
            else if (savedMode == AppPrefs.DEVICE_MODE_PIZZA) deviceModeGroup.check(R.id.rbDevicePizza)
            else if (savedMode == AppPrefs.DEVICE_MODE_MAIN)
                deviceModeGroup.check(R.id.rbDeviceMain)
        }

        cityInput.setText(savedCity.orEmpty())
        maxChatInput.setText(prefs.getString(AppPrefs.KEY_MAX_CHAT_ID, "").orEmpty())
        telegramChatInput.setText(prefs.getString(AppPrefs.KEY_TELEGRAM_CHAT_ID, "").orEmpty())
        telegramThreadInput.setText(prefs.getString(AppPrefs.KEY_TELEGRAM_THREAD_ID, "").orEmpty())

        val savedTransport = prefs.getString(AppPrefs.KEY_TRANSPORT_TYPE, "max")

        fun updateTransportFields() {
            val isMax = transportGroup.checkedRadioButtonId == R.id.rbMax
            val maxVisibility = if (isMax) android.view.View.VISIBLE else android.view.View.GONE
            val telegramVisibility = if (isMax) android.view.View.GONE else android.view.View.VISIBLE

            maxChatLabel.visibility = maxVisibility
            maxChatInput.visibility = maxVisibility
            telegramChatLabel.visibility = telegramVisibility
            telegramChatInput.visibility = telegramVisibility
            telegramThreadLabel.visibility = telegramVisibility
            telegramThreadInput.visibility = telegramVisibility
        }

        transportGroup.setOnCheckedChangeListener { _, _ -> updateTransportFields() }
        transportGroup.check(if (savedTransport == "telegram") R.id.rbTelegram else R.id.rbMax)
        updateTransportFields()

        if (isCleanApp) {
            val hidden = android.view.View.GONE
            transportLabel.visibility = hidden
            transportGroup.visibility = hidden
            maxChatLabel.visibility = hidden
            maxChatInput.visibility = hidden
            telegramChatLabel.visibility = hidden
            telegramChatInput.visibility = hidden
            telegramThreadLabel.visibility = hidden
            telegramThreadInput.visibility = hidden
        }

        if (isCleanApp && !savedCity.isNullOrEmpty()) {
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        } else cityInput.requestFocus()

        fun selectedMode() = when (deviceModeGroup.checkedRadioButtonId) {
            R.id.rbDeviceMain -> AppPrefs.DEVICE_MODE_MAIN
            R.id.rbDevicePizza -> AppPrefs.DEVICE_MODE_PIZZA
            else -> null
        }
        fun clearPreviousMode(mode: String?): Boolean =
            !isCleanApp || prefs.getString(AppPrefs.KEY_DEVICE_MODE, AppPrefs.DEVICE_MODE_MAIN) == mode ||
                CookLocalSession.preferences(applicationContext).edit().remove(CookShiftCache.KEY)
                    .remove(CookOfflineSelectionCache.KEY).remove("current_photo_author").commit()

        if (modeOnly) {
            findViewById<TextView>(R.id.tvTitle).text = savedCity
            findViewById<TextView>(R.id.tvCityLabel).visibility = android.view.View.GONE
            cityInput.visibility = android.view.View.GONE
            button.setText(R.string.save_button)
            button.isEnabled = selectedMode() != null
            deviceModeGroup.setOnCheckedChangeListener { _, _ -> button.isEnabled = selectedMode() != null }
            button.setOnClickListener {
                val mode = selectedMode() ?: return@setOnClickListener
                if (clearPreviousMode(mode) && prefs.edit().putString(AppPrefs.KEY_DEVICE_MODE, mode)
                        .putString(AppPrefs.KEY_MODE_CONFIRMED_DEVICE, savedDevice).commit()) startMain()
                else android.widget.Toast.makeText(this, R.string.setup_storage_error, android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }
        button.setOnClickListener {
            if (PhotoQueueStore.hasItems(this) || PilotDatabase.get(this).photoRecordDao().getPendingServerRecords().isNotEmpty()) {
                cityInput.error = getString(R.string.setup_queue_pending)
                return@setOnClickListener
            }
            val city = cityInput.text.toString().trim()
            val deviceMode = if (isCleanApp) selectedMode() else null
            val transportType = if (isCleanApp) {
                null
            } else if (transportGroup.checkedRadioButtonId == R.id.rbTelegram) {
                "telegram"
            } else {
                "max"
            }
            val maxChatId = if (isCleanApp) null else maxChatInput.text.toString().trim().takeIf { it.isNotEmpty() }
            val telegramChatId = if (isCleanApp) null else telegramChatInput.text.toString().trim().takeIf { it.isNotEmpty() }
            val telegramThreadId = if (isCleanApp) null else telegramThreadInput.text.toString().trim().takeIf { it.isNotEmpty() }

            if (city.isEmpty()) {
                cityInput.error = getString(R.string.enter_city_error)
                return@setOnClickListener
            }
            if (isCleanApp && deviceMode == null) {
                android.widget.Toast.makeText(this, "Выберите тип телефона", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val deviceUuid = prefs.getString(AppPrefs.KEY_DEVICE_UUID, null) ?: UUID.randomUUID().toString()
            // The server may have accepted a request even when its response was lost.
            if (!prefs.edit().putString(AppPrefs.KEY_DEVICE_UUID, deviceUuid).commit()) {
                cityInput.error = getString(R.string.setup_storage_error)
                return@setOnClickListener
            }
            button.isEnabled = false

            scope.launch {
                val result = registerDevice(
                    backendUrl = backendUrl,
                    deviceUuid = deviceUuid,
                    city = city,
                    transportType = transportType,
                    maxChatId = maxChatId,
                    telegramChatId = telegramChatId,
                    telegramThreadId = telegramThreadId
                )

                button.isEnabled = true
                if (result.isSuccess) {
                    val registration = result.getOrThrow()
                    if (!clearPreviousMode(deviceMode)) {
                        cityInput.error = getString(R.string.setup_storage_error)
                        return@launch
                    }
                    prefs.edit()
                        .putString(AppPrefs.KEY_BACKEND_URL, backendUrl)
                        .putString(AppPrefs.KEY_DEVICE_UUID, deviceUuid)
                        .putString(AppPrefs.KEY_CITY, registration.cityName)
                        .apply { if (deviceMode != null) {
                            putString(AppPrefs.KEY_DEVICE_MODE, deviceMode)
                            putString(AppPrefs.KEY_MODE_CONFIRMED_DEVICE, deviceUuid)
                        } }
                        .putString(AppPrefs.KEY_TRANSPORT_TYPE, registration.transportType)
                        .putString(AppPrefs.KEY_MAX_CHAT_ID, registration.maxChatId)
                        .putString(AppPrefs.KEY_TELEGRAM_CHAT_ID, registration.telegramChatId)
                        .putString(AppPrefs.KEY_TELEGRAM_THREAD_ID, registration.telegramThreadId)
                        .apply()
                    startMain()
                } else {
                    RemoteSupport.checkImmediately(applicationContext)
                    val message = result.exceptionOrNull()?.message ?: getString(R.string.registration_failed)
                    cityInput.error = message
                    cityInput.requestFocus()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun startMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private suspend fun registerDevice(
        backendUrl: String,
        deviceUuid: String,
        city: String,
        transportType: String?,
        maxChatId: String?,
        telegramChatId: String?,
        telegramThreadId: String?
    ): Result<RegistrationResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("device_uuid", deviceUuid)
                .put("city", city)
                .put("transport_type", transportType)
                .put("max_chat_id", maxChatId)
                .put("telegram_chat_id", telegramChatId)
                .put("telegram_thread_id", telegramThreadId)

            val request = Request.Builder()
                .url("$backendUrl/register-device")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            PhotoApi.execute(request).use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    val message = try {
                        JSONObject(errorBody).optString("detail")
                    } catch (_: Exception) {
                        ""
                    }
                    throw IllegalStateException(
                        message.takeIf { it.isNotBlank() } ?: "Server error ${response.code}"
                    )
                }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                RegistrationResponse(
                    cityName = json.getString("city_name"),
                    transportType = json.getString("transport_type"),
                    maxChatId = json.optString("max_chat_id").takeIf { it.isNotBlank() && it != "null" },
                    telegramChatId = json.optString("telegram_chat_id").takeIf { it.isNotBlank() && it != "null" },
                    telegramThreadId = json.optString("telegram_thread_id").takeIf { it.isNotBlank() && it != "null" }
                )
            }
        }
    }

    private data class RegistrationResponse(
        val cityName: String,
        val transportType: String,
        val maxChatId: String?,
        val telegramChatId: String?,
        val telegramThreadId: String?
    )
}
