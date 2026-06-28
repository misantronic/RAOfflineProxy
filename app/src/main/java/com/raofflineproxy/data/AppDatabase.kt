package com.raofflineproxy.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Cache-preservation policy: bumping `version` triggers fallbackToDestructiveMigration,
// which drops every cached game and pending award. Bump it ONLY on a major app release.
// Minor/patch releases must not change any @Entity (Room would force a bump and wipe).
// exportSchema commits the schema to app/schemas so any entity change shows up as a
// diff in review; AppDatabaseSchemaLockTest fails if the committed schema and `version`
// drift apart. See RELEASING.md ("Database schema & cache-preservation policy").
@Database(
    entities = [CacheEntry::class, PendingAward::class],
    version = 10,
    exportSchema = true
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
