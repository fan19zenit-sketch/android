package com.example.simplephotouploader

import android.content.Context
import java.security.MessageDigest

/** Isolate cached employees and shift data when the device, city, or backend changes. */
object CookLocalSession {
    fun identity(context: Context): String {
        val prefs = context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        val backend = prefs.getString(AppPrefs.KEY_BACKEND_URL, AppPrefs.DEFAULT_BACKEND_URL).orEmpty().trimEnd('/')
        val device = prefs.getString(AppPrefs.KEY_DEVICE_UUID, "").orEmpty()
        val city = prefs.getString(AppPrefs.KEY_CITY, "").orEmpty()
        return listOf(backend, device, city).joinToString("\u0000")
    }

    fun preferences(context: Context): android.content.SharedPreferences {
        val scope = identity(context)
        val digest = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return context.getSharedPreferences("cook_shift_$digest", Context.MODE_PRIVATE)
    }
    fun cache(context: Context): CookShiftCache = CookShiftCache(preferences(context))

    fun offlineScope(context: Context, binding: CookBinding): CookOfflineScope {
        if (BuildConfig.BUILD_TYPE == "preview") return CookOfflineScope("https://preview.invalid", "preview-device", binding)
        val prefs = context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        val backend = prefs.getString(AppPrefs.KEY_BACKEND_URL, AppPrefs.DEFAULT_BACKEND_URL).orEmpty()
        val device = prefs.getString(AppPrefs.KEY_DEVICE_UUID, "").orEmpty()
        require(backend.isNotBlank() && device.isNotBlank())
        return CookOfflineScope(backend, device, binding)
    }
}
