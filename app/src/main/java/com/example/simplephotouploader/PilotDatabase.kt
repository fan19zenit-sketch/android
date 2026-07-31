package com.example.simplephotouploader

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PhotoRecordEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class PilotDatabase : RoomDatabase() {
    abstract fun photoRecordDao(): PhotoRecordDao

    companion object {
        @Volatile
        private var instance: PilotDatabase? = null

        fun get(context: Context): PilotDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PilotDatabase::class.java,
                    "pilot-photo-db"
                )
                    .fallbackToDestructiveMigration()
                    .allowMainThreadQueries()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
