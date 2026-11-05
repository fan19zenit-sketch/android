package com.example.simplephotouploader

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class PendingCookShift(val requestId: String, val binding: CookBinding, val shift: CookShift)

/** A retry reuses both the request ID and the exact submitted shift snapshot. */
class CookPendingShiftStore(private val preferences: SharedPreferences) {
    fun save(pending: PendingCookShift): Boolean {
        val binding = pending.binding
        val shift = pending.shift
        val data = JSONObject().put("request", pending.requestId).put("branch", binding.branchRef)
            .put("bindingRevision", binding.revision).put("timezone", shift.timezone)
            .put("shift", shift.id).put("day", shift.operationalDate).put("confirmedAt", shift.confirmedAt)
            .put("cooks", JSONArray(shift.cooks)).put("operators", JSONArray(shift.operators)).put("main", shift.mainCookId)
        return preferences.edit().putString(KEY, data.toString()).commit()
    }

    fun load(): PendingCookShift? {
        return try {
            val raw = preferences.getString(KEY, null) ?: return null
            val data = JSONObject(raw)
            fun strings(name: String): List<String> {
                val items = data.getJSONArray(name)
                return (0 until items.length()).map { items.getString(it) }
            }
            val timezone = data.getString("timezone")
            val binding = CookBinding(data.getString("branch"), data.getInt("bindingRevision"), timezone)
            val shift = CookShift(data.getString("shift"), 0, binding.branchRef, data.getString("day"), timezone,
                data.getString("confirmedAt"), strings("cooks"), strings("operators"), data.getString("main"))
            PendingCookShift(data.getString("request"), binding, shift)
        } catch (_: Exception) { null }
    }

    fun clear(): Boolean = preferences.edit().remove(KEY).commit()

    companion object { private const val KEY = "pending_shift_v1" }
}
