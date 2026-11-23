package com.example.simplephotouploader

object AppPrefs {
    const val LEGACY_BACKEND_URL = "http://194.55.235.241:8000"
    const val DEFAULT_BACKEND_URL = "https://194-55-235-241.sslip.io/mobile-api"
    const val PREFS = "app_prefs"
    const val KEY_BACKEND_URL = "backend_url"
    const val KEY_QUEUE = "photo_queue"
    const val KEY_LAST_STATUS = "last_status"
    const val KEY_HISTORY = "photo_history"
    const val KEY_DEVICE_UUID = "device_uuid"
    const val KEY_CITY = "city"
    const val KEY_DEVICE_MODE = "device_mode"
    const val KEY_MODE_CONFIRMED_DEVICE = "pilot_mode_confirmed_device"
    const val DEVICE_MODE_MAIN = "main"
    const val DEVICE_MODE_PIZZA = "pizza"
    const val KEY_TRANSPORT_TYPE = "transport_type"
    const val KEY_MAX_CHAT_ID = "max_chat_id"
    const val KEY_TELEGRAM_CHAT_ID = "telegram_chat_id"
    const val KEY_TELEGRAM_THREAD_ID = "telegram_thread_id"

    fun needsPilotModeChoice(city: String?, device: String?, confirmedDevice: String?): Boolean =
        !city.isNullOrBlank() && !device.isNullOrBlank() && device != confirmedDevice

    fun confirmedPilotMode(): String? {
        if (BuildConfig.APPLICATION_ID != "com.example.photovchistotu" || BuildConfig.BUILD_TYPE != "release") return null
        val prefs = App.instance.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val device = prefs.getString(KEY_DEVICE_UUID, null)
        if (device.isNullOrBlank() || device != prefs.getString(KEY_MODE_CONFIRMED_DEVICE, null) ||
            prefs.getString(KEY_CITY, null).isNullOrBlank()) return null
        return prefs.getString(KEY_DEVICE_MODE, null)?.takeIf { it == DEVICE_MODE_MAIN || it == DEVICE_MODE_PIZZA }
    }
}
