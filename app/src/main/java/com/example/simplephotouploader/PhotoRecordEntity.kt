package com.example.simplephotouploader

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "photo_records",
    indices = [
        Index(value = ["photoPath"], unique = true),
        Index(value = ["capturedAt"]),
        Index(value = ["queuedInUploadQueue"]),
    ]
)
data class PhotoRecordEntity(
    @PrimaryKey val id: String,
    val jobId: String?,
    val messageId: String?,
    val chatDeleted: Boolean,
    val photoPath: String,
    val thumbnailPath: String?,
    val capturedAt: String,
    val sentAt: String?,
    val status: String,
    val error: String?,
    val queuedInUploadQueue: Boolean,
)
