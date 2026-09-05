package com.example.simplephotouploader

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface PhotoRecordDao {
    @Query("SELECT * FROM photo_records WHERE jobId IS NOT NULL AND status IN ('uploaded_to_server', 'sending_to_chat') ORDER BY capturedAt ASC")
    fun getPendingServerRecords(): List<PhotoRecordEntity>

    @Query("UPDATE photo_records SET jobId = :jobId, status = 'uploaded_to_server', error = NULL, queuedInUploadQueue = 0 WHERE photoPath = :path")
    fun acknowledgeUpload(path: String, jobId: String)

    @Query("UPDATE photo_records SET status = 'queued', error = NULL, queuedInUploadQueue = 1, manualRetryApproved = 1 WHERE photoPath = :path AND jobId IS NULL AND chatDeleted = 0 AND status IN ('error', 'needs_review', 'retrying')")
    fun retryLocal(path: String): Int
    @Query("SELECT * FROM photo_records ORDER BY capturedAt DESC")
    fun getAllHistory(): List<PhotoRecordEntity>

    @Query("SELECT * FROM photo_records ORDER BY capturedAt DESC LIMIT 1")
    fun getLatest(): PhotoRecordEntity?

    @Query("SELECT photoPath FROM photo_records WHERE queuedInUploadQueue = 1 ORDER BY capturedAt ASC")
    fun getQueuedPaths(): List<String>

    @Query("SELECT * FROM photo_records WHERE queuedInUploadQueue = 1 ORDER BY capturedAt ASC")
    fun getQueuedRecords(): List<PhotoRecordEntity>

    @Query("SELECT * FROM photo_records WHERE photoPath = :photoPath LIMIT 1")
    fun getByPhotoPath(photoPath: String): PhotoRecordEntity?

    @Query("SELECT * FROM photo_records WHERE jobId = :jobId LIMIT 1")
    fun getByJobId(jobId: String): PhotoRecordEntity?

    @Query("SELECT * FROM photo_records WHERE jobId = :jobId")
    fun getAllByJobId(jobId: String): List<PhotoRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(record: PhotoRecordEntity)

    @Update
    fun update(record: PhotoRecordEntity)

    @Query("DELETE FROM photo_records WHERE photoPath = :photoPath")
    fun deleteByPhotoPath(photoPath: String)

    @Query("UPDATE photo_records SET queuedInUploadQueue = :queued WHERE photoPath = :photoPath")
    fun setQueuedState(photoPath: String, queued: Boolean)

    @Query("SELECT COUNT(*) FROM photo_records")
    fun count(): Int
}
