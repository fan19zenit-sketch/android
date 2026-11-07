package com.example.simplephotouploader

import org.json.JSONObject

object PhotoContextAcknowledgement {
    fun resolution(json: JSONObject?, uploadId: String): String? {
        if (json == null || json.optString("clientUploadId") != uploadId ||
            json.optInt("schemaVersion") != 1 || json.optString("photoContextStatus") != "stored") return null
        return json.optString("resolution").takeIf { it in setOf("resolved", "unresolved", "not_applicable") }
    }
}
