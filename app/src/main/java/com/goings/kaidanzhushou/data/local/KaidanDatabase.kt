package com.goings.kaidanzhushou.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BatchEntity::class,
        RecordEntity::class,
        ExportEntity::class,
        ReceiverProfileEntity::class,
        GoodsProfileEntity::class,
        ProfileLearningStateEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class KaidanDatabase : RoomDatabase() {
    abstract fun dao(): KaidanDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE records ADD COLUMN paymentType TEXT")
                db.execSQL("UPDATE records SET paymentType = 'pay_billing'")
                db.execSQL("ALTER TABLE records ADD COLUMN documentPath TEXT")
                db.execSQL("ALTER TABLE records ADD COLUMN edgeDetectionWarning INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE exports ADD COLUMN publicUri TEXT")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE records ADD COLUMN rotationDegrees INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE records ADD COLUMN destinationUniqueKey TEXT")
                db.execSQL("ALTER TABLE records ADD COLUMN destinationDisplay TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE records ADD COLUMN destinationCandidates TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE records ADD COLUMN receiverProfileId TEXT")
                db.execSQL("ALTER TABLE records ADD COLUMN goodsProfileId TEXT")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS receiver_profiles (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        normalizedName TEXT NOT NULL,
                        phone TEXT NOT NULL,
                        useCount INTEGER NOT NULL,
                        lastUsedAt INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_receiver_profiles_normalizedName ON receiver_profiles(normalizedName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_receiver_profiles_lastUsedAt ON receiver_profiles(lastUsedAt)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS goods_profiles (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        normalizedName TEXT NOT NULL,
                        packageName TEXT NOT NULL,
                        useCount INTEGER NOT NULL,
                        lastUsedAt INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_goods_profiles_normalizedName ON goods_profiles(normalizedName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_goods_profiles_lastUsedAt ON goods_profiles(lastUsedAt)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS profile_learning_state (
                        `key` TEXT NOT NULL PRIMARY KEY,
                        completedAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }
    }
}
