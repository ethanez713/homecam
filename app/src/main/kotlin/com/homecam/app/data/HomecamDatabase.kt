package com.homecam.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ViewerEntity::class, CameraEntity::class, AuditEntity::class, ClipEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class HomecamDatabase : RoomDatabase() {
    abstract fun viewers(): ViewerDao
    abstract fun cameras(): CameraDao
    abstract fun audit(): AuditDao
    abstract fun clips(): ClipDao

    companion object {
        /**
         * A destructive fallback would drop the allowlist and the audit log, which is
         * exactly the state this app exists to protect. Migrations here are never optional.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE viewers ADD COLUMN alertPublicKey BLOB")
                db.execSQL("ALTER TABLE viewers ADD COLUMN alertTopic TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE viewers ADD COLUMN alertEndpoint TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE viewers ADD COLUMN alertsEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE viewers ADD COLUMN sessionAlerts INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE viewers ADD COLUMN detectionAlerts INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE cameras ADD COLUMN remoteHost TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE cameras ADD COLUMN remotePort INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cameras ADD COLUMN lastArmed INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE cameras ADD COLUMN lastRecordingMode TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE viewers ADD COLUMN healthAlerts INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        @Volatile private var instance: HomecamDatabase? = null

        fun get(context: Context): HomecamDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                HomecamDatabase::class.java,
                "homecam.db",
            ).addMigrations(*MIGRATIONS).build().also { instance = it }
        }
    }
}
