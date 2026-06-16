package com.raofflineproxy.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CacheEntry::class, PendingAward::class],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
    abstract fun pendingAwardDao(): PendingAwardDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "raofflineproxy.db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build().also { instance = it }
            }
    }
}
