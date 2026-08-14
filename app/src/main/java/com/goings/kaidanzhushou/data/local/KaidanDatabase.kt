package com.goings.kaidanzhushou.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [BatchEntity::class, RecordEntity::class, ExportEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class KaidanDatabase : RoomDatabase() {
    abstract fun dao(): KaidanDao
}
