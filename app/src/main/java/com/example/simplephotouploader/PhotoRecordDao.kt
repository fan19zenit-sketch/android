package com.example.simplephotouploader

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface PhotoRecordDao {
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
