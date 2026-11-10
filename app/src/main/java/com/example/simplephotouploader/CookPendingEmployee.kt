package com.example.simplephotouploader

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class PendingCookEmployee(val requestId: String, val binding: CookBinding, val name: String, val roles: List<String>)

class CookPendingEmployeeStore(private val preferences: SharedPreferences) {
    fun save(value: PendingCookEmployee): Boolean = preferences.edit().putString(KEY,
        JSONObject().put("request", value.requestId).put("branch", value.binding.branchRef)
            .put("revision", value.binding.revision).put("timezone", value.binding.timezone ?: JSONObject.NULL)
            .put("name", value.name).put("roles", JSONArray(value.roles)).toString()).commit()

    fun load(): PendingCookEmployee? {
        return try {
            val raw = preferences.getString(KEY, null) ?: return null
            val json = JSONObject(raw)
            val zone = json.optString("timezone").takeIf { it.isNotBlank() && it != "null" }
            val roles = json.getJSONArray("roles")
            PendingCookEmployee(json.getString("request"), CookBinding(json.getString("branch"), json.getInt("revision"), zone),
                json.getString("name"), (0 until roles.length()).map { roles.getString(it) })
        } catch (_: Exception) { null }
    }

    fun clear(): Boolean = preferences.edit().remove(KEY).commit()
    companion object { private const val KEY = "pending_employee_v1" }
}
