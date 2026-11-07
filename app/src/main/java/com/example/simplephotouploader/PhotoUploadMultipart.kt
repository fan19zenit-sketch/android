package com.example.simplephotouploader

import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody

object PhotoUploadMultipart {
    fun build(deviceId: String, uploadId: String, photo: File, photoContext: String?): MultipartBody {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("deviceUuid", deviceId)
            .addFormDataPart("clientUploadId", uploadId)
        if (!photoContext.isNullOrBlank()) body.addFormDataPart("photoContext", photoContext)
        return body.addFormDataPart("photo", photo.name, photo.asRequestBody("image/jpeg".toMediaType())).build()
    }
}
