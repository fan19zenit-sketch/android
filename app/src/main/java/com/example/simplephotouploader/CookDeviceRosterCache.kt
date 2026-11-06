package com.example.simplephotouploader

import android.content.SharedPreferences
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/** Scoped preferences are supplied by CookLocalSession, not shared across devices or servers. */
class CookDeviceRosterCache(private val preferences: SharedPreferences) {
    data class Snapshot(val binding: CookBinding, val employees: List<CookEmployee>)

    fun save(binding: CookBinding, employees: List<CookEmployee>): Boolean = synchronized(LOCK) {
        require(binding.branchRef.isNotBlank() && binding.revision > 0)
        ZoneId.of(requireNotNull(binding.timezone))
        require(employees.map { it.id }.distinct().size == employees.size)
        require(employees.all { it.id.isNotBlank() && it.displayName.isNotBlank() && it.roles.isNotEmpty() &&
            it.roles.all { role -> role in setOf("cook", "operator") } })
        val data = JSONObject().put("branch", binding.branchRef).put("revision", binding.revision)
            .put("timezone", binding.timezone).put("employees", JSONArray(employees.map { employee ->
                JSONObject().put("id", employee.id).put("name", employee.displayName).put("roles", JSONArray(employee.roles))
            }))
        preferences.edit().putString(KEY, data.toString()).commit()
    }

    fun saveBinding(binding: CookBinding): Boolean = synchronized(LOCK) {
        val previous = load()
        if (previous?.binding == binding) true else save(binding, emptyList())
    }

    fun load(): Snapshot? { return try {
        val data = JSONObject(preferences.getString(KEY, null) ?: return null)
        val branch = data.getString("branch")
        val revision = data.getInt("revision")
        val timezone = data.getString("timezone")
        require(branch.isNotBlank() && revision > 0)
        ZoneId.of(timezone)
        val array = data.getJSONArray("employees")
        val employees = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val roles = item.getJSONArray("roles")
            CookEmployee(item.getString("id"), item.getString("name"), (0 until roles.length()).map { roles.getString(it) })
        }
        require(employees.map { it.id }.distinct().size == employees.size)
        require(employees.all { it.id.isNotBlank() && it.displayName.isNotBlank() && it.roles.isNotEmpty() &&
            it.roles.all { role -> role in setOf("cook", "operator") } })
        Snapshot(CookBinding(branch, revision, timezone), employees)
    } catch (_: Exception) { null } }

    companion object {
        internal const val KEY = "device_roster_v1"
        private val LOCK = Any()
    }
}
