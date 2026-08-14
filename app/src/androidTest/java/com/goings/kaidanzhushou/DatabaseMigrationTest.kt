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
}
