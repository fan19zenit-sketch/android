package com.example.simplephotouploader

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

data class CookOfflineScope(val backendUrl: String, val deviceUuid: String, val binding: CookBinding)
data class CookOfflineSyncResult(val employees: Int, val shifts: Int, val waitingShifts: Int)
class CookOfflineConflict(message: String) : IOException(message)

/** Caller runs this off the UI thread and retries the same persisted requests after network failures. */
class CookOfflineSync(private val dao: CookOfflineDao, private val client: CookWorkflowClient) {
    fun sync(scope: CookOfflineScope): CookOfflineSyncResult {
        require(scope.backendUrl.isNotBlank() && scope.deviceUuid.isNotBlank())
        if (!client.matchesBackend(scope.backendUrl)) throw CookOfflineConflict("Backend changed")
        if (client.capabilities(scope.deviceUuid)?.supportsAttributedCapture != true) throw IOException("Server does not support attributed capture")
        if (client.initializeBinding(scope.deviceUuid) != scope.binding) throw CookOfflineConflict("Device binding changed")
        val binding = scope.binding
        var employees = 0
        var shifts = 0
        var waiting = 0
        for (pending in dao.pendingEmployees(scope.backendUrl, scope.deviceUuid, binding.branchRef, binding.revision)) {
            val roleArray = JSONArray(pending.rolesJson)
            val roles = (0 until roleArray.length()).map { roleArray.getString(it) }
            val confirmed = client.addManual(scope.deviceUuid, pending.requestId, pending.name, roles,
                binding, pending.provisionalId) ?: throw IOException("Missing employee confirmation")
            dao.confirmEmployee(scope.backendUrl, scope.deviceUuid, binding.branchRef, binding.revision,
                pending.provisionalId, confirmed.id)
            employees++
        }
        for (pending in dao.pendingShifts(scope.backendUrl, scope.deviceUuid, binding.branchRef, binding.revision)) {
            if (binding.timezone != null && pending.timezone != binding.timezone) throw CookOfflineConflict("Shift timezone changed")
            val cooks = resolveArray(scope, pending.cooksJson)
            val operators = resolveArray(scope, pending.operatorsJson)
            val main = resolve(scope, JSONObject(pending.mainCookJson))
            if (cooks == null || operators == null || main == null) {
                waiting++
                continue
            }
            if (main !in cooks || cooks.size != cooks.toSet().size || operators.size != operators.toSet().size)
                throw CookOfflineConflict("Resolved shift has conflicting employees")
            val shift = CookShift(pending.serverShiftId, 0, binding.branchRef, pending.operationalDate,
                pending.timezone, pending.confirmedAt, cooks, operators, main)
            val confirmed = client.confirmShift(scope.deviceUuid, pending.requestId, shift, binding, pending.provisionalId)
                ?: throw IOException("Missing shift confirmation")
            dao.confirmShift(scope.backendUrl, scope.deviceUuid, binding.branchRef, binding.revision,
                pending.provisionalId, confirmed.second)
            shifts++
        }
        return CookOfflineSyncResult(employees, shifts, waiting)
    }

    private fun resolveArray(scope: CookOfflineScope, raw: String): List<String>? {
        val array = JSONArray(raw)
        val resolved = (0 until array.length()).map { resolve(scope, array.getJSONObject(it)) }
        return if (resolved.any { it == null }) null else resolved.filterNotNull()
    }

    private fun resolve(scope: CookOfflineScope, ref: JSONObject): String? {
        val id = ref.getString("id")
        return when (ref.getString("kind")) {
            "employee" -> id
            "provisional" -> dao.employee(scope.backendUrl, scope.deviceUuid, scope.binding.branchRef,
                scope.binding.revision, id)?.canonicalId
            else -> throw CookOfflineConflict("Invalid employee reference")
        }
    }
}
