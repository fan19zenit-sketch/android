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

    private val client = PhotoApi.client
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val isCleanApp = BuildConfig.FLAVOR == "clean"
        val prefs = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
        val forceEdit = intent.getBooleanExtra(EXTRA_FORCE_EDIT, false)
        val backendUrl = AppPrefs.DEFAULT_BACKEND_URL
        val savedCity = prefs.getString(AppPrefs.KEY_CITY, null)
        if (!forceEdit && !savedCity.isNullOrEmpty()) {
            startMain()
            return
        }

        val instructions = findViewById<TextView>(R.id.tvInstructions)
        val cityInput = findViewById<EditText>(R.id.etCity)
        val transportLabel = findViewById<TextView>(R.id.tvTransportLabel)
        val transportGroup = findViewById<RadioGroup>(R.id.rgTransport)
        val maxChatLabel = findViewById<TextView>(R.id.tvMaxChatIdLabel)
        val maxChatInput = findViewById<EditText>(R.id.etMaxChatId)
        val telegramChatLabel = findViewById<TextView>(R.id.tvTelegramChatIdLabel)
        val telegramChatInput = findViewById<EditText>(R.id.etTelegramChatId)
        val telegramThreadLabel = findViewById<TextView>(R.id.tvTelegramThreadIdLabel)
        val telegramThreadInput = findViewById<EditText>(R.id.etTelegramThreadId)
        val button = findViewById<Button>(R.id.btnSave)

        if (isCleanApp) {
            instructions.text = getString(R.string.setup_instructions_clean)
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

        cityInput.requestFocus()

        button.setOnClickListener {
            if (PhotoQueueStore.hasItems(this) || PilotDatabase.get(this).photoRecordDao().getPendingServerRecords().isNotEmpty()) {
                cityInput.error = getString(R.string.setup_queue_pending)
                return@setOnClickListener
            }
            val city = cityInput.text.toString().trim()
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
            val deviceUuid = prefs.getString(AppPrefs.KEY_DEVICE_UUID, null) ?: UUID.randomUUID().toString()
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
                    prefs.edit()
                        .putString(AppPrefs.KEY_BACKEND_URL, backendUrl)
                        .putString(AppPrefs.KEY_DEVICE_UUID, deviceUuid)
                        .putString(AppPrefs.KEY_CITY, registration.cityName)
                        .putString(AppPrefs.KEY_TRANSPORT_TYPE, registration.transportType)
                        .putString(AppPrefs.KEY_MAX_CHAT_ID, registration.maxChatId)
                        .putString(AppPrefs.KEY_TELEGRAM_CHAT_ID, registration.telegramChatId)
                        .putString(AppPrefs.KEY_TELEGRAM_THREAD_ID, registration.telegramThreadId)
                        .apply()
                    startMain()
                } else {
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

            client.newCall(request).execute().use { response ->
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
