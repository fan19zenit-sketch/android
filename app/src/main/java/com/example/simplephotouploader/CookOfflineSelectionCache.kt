package com.example.simplephotouploader

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object CookShiftSelection {
    fun choose(hasOfflineSelection: Boolean, offline: CachedCookShift?, confirmed: CachedCookShift?): CachedCookShift? =
        if (hasOfflineSelection) offline else confirmed
}

/** Active local shift points at the immutable Room record, scoped by current app settings. */
class CookOfflineSelectionCache(private val preferences: SharedPreferences, private val dao: CookOfflineDao,
                                private val scope: CookOfflineScope) {
    fun save(shift: OfflineCookShift, employees: List<CookEmployee>): Boolean {
        val snapshot = build(shift, employees)
        val data = JSONObject().put("id", snapshot.offlineShift!!.provisionalId)
            .put("employees", JSONArray(snapshot.employees.map { person ->
                JSONObject().put("id", person.id).put("name", person.displayName).put("roles", JSONArray(person.roles))
            }))
        return preferences.edit().putString(KEY, data.toString()).commit()
    }

    fun load(): CachedCookShift? {
        return try {
            val raw = preferences.getString(KEY, null) ?: return null
            val data = JSONObject(raw)
            val binding = scope.binding
            val shift = dao.shift(scope.backendUrl, scope.deviceUuid, binding.branchRef, binding.revision, data.getString("id"))
                ?: return null
            val array = data.getJSONArray("employees")
            val employees = (0 until array.length()).map { index ->
                val person = array.getJSONObject(index)
                val roles = person.getJSONArray("roles")
                CookEmployee(person.getString("id"), person.getString("name"), (0 until roles.length()).map { roles.getString(it) })
            }
            build(shift, employees)
        } catch (_: Exception) { null }
    }

    fun clear(): Boolean = preferences.edit().remove(KEY).commit()

    private fun build(record: OfflineCookShift, employees: List<CookEmployee>): CachedCookShift {
        val binding = scope.binding
        require(record.backendUrl == scope.backendUrl && record.deviceUuid == scope.deviceUuid
            && record.branchRef == binding.branchRef && record.bindingRevision == binding.revision)
        require(binding.timezone == null || binding.timezone == record.timezone)
        val stored = dao.shift(scope.backendUrl, scope.deviceUuid, binding.branchRef, binding.revision, record.provisionalId)
        require(stored?.copy(canonicalRevision = null) == record.copy(canonicalRevision = null))
        CookOfflineValidation.shift(record.copy(canonicalRevision = null))
        val people = employees.associateBy { it.id }
        require(people.size == employees.size && people.keys.none { it.isBlank() })
        require(employees.all { it.displayName.isNotBlank() && it.roles.isNotEmpty() && it.roles.all { role -> role in setOf("cook", "operator") } })
        fun memberRefs(raw: String, role: String): List<String> {
            val refs = JSONArray(raw)
            return (0 until refs.length()).map { index ->
                val ref = refs.getJSONObject(index)
                val id = ref.getString("id")
                val person = requireNotNull(people[id])
                require(role in person.roles)
                if (ref.getString("kind") == "provisional") {
                    val local = requireNotNull(dao.employee(scope.backendUrl, scope.deviceUuid, binding.branchRef, binding.revision, id))
                    val localRoles = JSONArray(local.rolesJson).let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
                    require(local.name == person.displayName && localRoles == person.roles.toSet())
                }
                id
            }
        }
        val cooks = memberRefs(record.cooksJson, "cook")
        val operators = memberRefs(record.operatorsJson, "operator")
        val main = JSONObject(record.mainCookJson).getString("id")
        val shift = CookShift(record.provisionalId, 1, record.branchRef, record.operationalDate, record.timezone,
            record.confirmedAt, cooks, operators, main)
        return CachedCookShift(binding, shift, employees, record.copy(canonicalRevision = null), scope)
    }

    companion object {
        internal const val KEY = "offline_active_shift_v1"
        fun cookRef(record: OfflineCookShift, id: String): CookOfflineRef {
            val refs = JSONArray(record.cooksJson)
            val matches = (0 until refs.length()).map { refs.getJSONObject(it) }.filter { it.getString("id") == id }
            require(matches.size == 1)
            return CookOfflineRef(matches.single().getString("kind"), id)
        }
    }
}
