package com.example.simplephotouploader

import android.content.SharedPreferences
import java.time.OffsetDateTime
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

data class CachedCookShift(val binding: CookBinding, val shift: CookShift, val employees: List<CookEmployee>)

/** Only confirmed shifts are cached; a one-shot choice never survives process death. */
class CookShiftCache(private val preferences: SharedPreferences) {
    fun save(snapshot: CachedCookShift): Boolean {
        validate(snapshot)
        val shift = snapshot.shift
        val employees = JSONArray(snapshot.employees.map { employee ->
            JSONObject().put("id", employee.id).put("name", employee.displayName).put("roles", JSONArray(employee.roles))
        })
        val data = JSONObject().put("schema", 1).put("branch", snapshot.binding.branchRef)
            .put("bindingRevision", snapshot.binding.revision).put("shiftId", shift.id)
            .put("shiftRevision", shift.revision).put("day", shift.operationalDate).put("timezone", shift.timezone)
            .put("confirmedAt", shift.confirmedAt).put("cooks", JSONArray(shift.cooks))
            .put("operators", JSONArray(shift.operators)).put("mainCook", shift.mainCookId).put("employees", employees)
        return preferences.edit().putString(KEY, data.toString()).commit()
    }

    fun load(): CachedCookShift? {
        return try {
            val raw = preferences.getString(KEY, null) ?: return null
            val data = JSONObject(raw)
            require(data.getInt("schema") == 1)
            val binding = CookBinding(data.getString("branch"), data.getInt("bindingRevision"))
            val shift = CookShift(data.getString("shiftId"), data.getInt("shiftRevision"), binding.branchRef,
                data.getString("day"), data.getString("timezone"), data.getString("confirmedAt"),
                strings(data.getJSONArray("cooks")), strings(data.getJSONArray("operators")), data.getString("mainCook"))
            val array = data.getJSONArray("employees")
            val employees = (0 until array.length()).map { index ->
                val entry = array.getJSONObject(index)
                CookEmployee(entry.getString("id"), entry.getString("name"), strings(entry.getJSONArray("roles")))
            }
            CachedCookShift(binding, shift, employees).also(::validate)
        } catch (_: Exception) {
            null
        }
    }

    fun clear(): Boolean = preferences.edit().remove(KEY).commit()

    private fun validate(snapshot: CachedCookShift) {
        val binding = snapshot.binding
        val shift = snapshot.shift
        require(binding.branchRef.isNotBlank() && binding.revision > 0 && shift.branchRef == binding.branchRef)
        require(shift.id.isNotBlank() && shift.revision > 0 && shift.cooks.isNotEmpty() && shift.operators.isNotEmpty())
        require(shift.mainCookId in shift.cooks && shift.cooks.size == shift.cooks.toSet().size &&
            shift.operators.size == shift.operators.toSet().size)
        java.time.LocalDate.parse(shift.operationalDate)
        ZoneId.of(shift.timezone)
        OffsetDateTime.parse(shift.confirmedAt)
        val employees = snapshot.employees.associateBy { it.id }
        require(employees.size == snapshot.employees.size && employees.keys.none { it.isBlank() })
        require(snapshot.employees.all { it.displayName.isNotBlank() && it.roles.isNotEmpty() &&
            it.roles.all { role -> role in setOf("cook", "operator") } })
        require(shift.cooks.all { employees[it]?.roles?.contains("cook") == true })
        require(shift.operators.all { employees[it]?.roles?.contains("operator") == true })
    }

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }

    companion object { private const val KEY = "confirmed_shift_v1" }
}
