package com.homecam.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ViewerEntity::class, CameraEntity::class, AuditEntity::class, ClipEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class HomecamDatabase : RoomDatabase() {
    abstract fun viewers(): ViewerDao
    abstract fun cameras(): CameraDao
    abstract fun audit(): AuditDao
    abstract fun clips(): ClipDao

    companion object {
        @Volatile private var instance: HomecamDatabase? = null

        fun get(context: Context): HomecamDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                HomecamDatabase::class.java,
                "homecam.db",
            ).build().also { instance = it }
        }
    }
}
