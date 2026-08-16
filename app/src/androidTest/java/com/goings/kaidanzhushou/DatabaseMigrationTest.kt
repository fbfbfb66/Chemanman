package com.goings.kaidanzhushou

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.goings.kaidanzhushou.data.local.KaidanDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @Test fun migrationKeepsLegacyPaymentAsBillingAndAddsImageColumns() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migration-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE records (id TEXT NOT NULL PRIMARY KEY)")
                    db.execSQL("CREATE TABLE exports (id TEXT NOT NULL PRIMARY KEY)")
                    db.execSQL("INSERT INTO records(id) VALUES ('legacy')")
                    db.execSQL("INSERT INTO exports(id) VALUES ('export')")
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        )
        val db = helper.writableDatabase
        KaidanDatabase.MIGRATION_1_2.migrate(db)
        db.query("SELECT paymentType, documentPath, edgeDetectionWarning FROM records WHERE id='legacy'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("pay_billing", cursor.getString(0))
            assertEquals(true, cursor.isNull(1))
            assertEquals(0, cursor.getInt(2))
        }
        db.query("SELECT publicUri FROM exports WHERE id='export'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(true, cursor.isNull(0))
        }
        helper.close()
        context.deleteDatabase(name)
    }

    @Test fun migration3To4AddsDestinationColumnsWithDefaults() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migration-3-4-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE records (id TEXT NOT NULL PRIMARY KEY)")
                    db.execSQL("INSERT INTO records(id) VALUES ('legacy')")
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        )
        val db = helper.writableDatabase
        KaidanDatabase.MIGRATION_3_4.migrate(db)
        db.query("SELECT destinationUniqueKey, destinationDisplay, destinationCandidates FROM records WHERE id='legacy'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(true, cursor.isNull(0))
            assertEquals("", cursor.getString(1))
            assertEquals("", cursor.getString(2))
        }
        helper.close()
        context.deleteDatabase(name)
    }

    @Test fun migration4To5AddsAssociationTablesAndRecordLinks() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migration-4-5-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE records (id TEXT NOT NULL PRIMARY KEY)")
                    db.execSQL("INSERT INTO records(id) VALUES ('legacy')")
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        )
        val db = helper.writableDatabase
        KaidanDatabase.MIGRATION_4_5.migrate(db)
        db.query("SELECT receiverProfileId, goodsProfileId FROM records WHERE id='legacy'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(true, cursor.isNull(0))
            assertEquals(true, cursor.isNull(1))
        }
        listOf("receiver_profiles", "goods_profiles", "profile_learning_state").forEach { table ->
            db.query("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$table'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
        }
        helper.close()
        context.deleteDatabase(name)
    }

    @Test fun migration5To6AddsAdvancePaymentColumnsAsNull() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migration-5-6-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE records (id TEXT NOT NULL PRIMARY KEY, freight REAL)")
                    db.execSQL("INSERT INTO records(id, freight) VALUES ('legacy', 20.0)")
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        )
        val db = helper.writableDatabase
        KaidanDatabase.MIGRATION_5_6.migrate(db)
        db.query("SELECT freight, advancePayment, advanceReturnType FROM records WHERE id='legacy'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(20.0, cursor.getDouble(0), 0.0)
            assertEquals(true, cursor.isNull(1))
            assertEquals(true, cursor.isNull(2))
        }
        helper.close()
        context.deleteDatabase(name)
    }

    @Test fun migration6To7AddsSenderProfilesAndRecordLink() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migration-6-7-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE records (id TEXT NOT NULL PRIMARY KEY)")
                    db.execSQL("INSERT INTO records(id) VALUES ('legacy')")
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        )
        val db = helper.writableDatabase
        KaidanDatabase.MIGRATION_6_7.migrate(db)
        db.query("SELECT senderProfileId FROM records WHERE id='legacy'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(true, cursor.isNull(0))
        }
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='sender_profiles'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        helper.close()
        context.deleteDatabase(name)
    }
}
