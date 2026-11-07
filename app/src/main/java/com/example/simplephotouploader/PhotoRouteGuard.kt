package com.example.simplephotouploader

import org.json.JSONObject

object PhotoRouteGuard {
    fun sameDeviceAndBackend(item: PhotoRecordEntity, deviceId: String, backend: String): Boolean =
        item.photoContext == null || (item.captureDeviceUuid?.isNotBlank() == true &&
            item.captureDeviceUuid == deviceId && item.captureBackendUrl?.isNotBlank() == true &&
            item.captureBackendUrl.trimEnd('/') == backend.trimEnd('/'))

    fun sameBinding(item: PhotoRecordEntity, binding: CookBinding): Boolean {
        val snapshot = item.photoContext ?: return true
        return try {
            val json = JSONObject(snapshot)
            json.optString("branchRef") == binding.branchRef && json.optInt("bindingRevision", -1) == binding.revision
        } catch (_: Exception) { false }
    }
}
