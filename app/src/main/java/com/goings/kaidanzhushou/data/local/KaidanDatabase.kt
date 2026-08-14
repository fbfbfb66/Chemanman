package com.goings.kaidanzhushou.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [BatchEntity::class, RecordEntity::class, ExportEntity::class], version = 2, exportSchema = false)
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
    }
}
