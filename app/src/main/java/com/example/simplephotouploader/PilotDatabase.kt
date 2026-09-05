package com.example.simplephotouploader

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PhotoRecordEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class PilotDatabase : RoomDatabase() {
    abstract fun photoRecordDao(): PhotoRecordDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photo_records ADD COLUMN manualRetryApproved INTEGER NOT NULL DEFAULT 0")
            }
        }
        @Volatile
        private var instance: PilotDatabase? = null

        fun get(context: Context): PilotDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PilotDatabase::class.java,
                    "pilot-photo-db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .allowMainThreadQueries()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
