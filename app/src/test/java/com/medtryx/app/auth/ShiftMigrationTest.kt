package com.medtryx.app.auth

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShiftMigrationTest {
    @Test fun version_6_migration_adds_shift_tables_and_preserves_legacy_sales_without_shift() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(TEST_DB)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(TEST_DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE users (id TEXT NOT NULL PRIMARY KEY)")
                        db.execSQL("CREATE TABLE sales (id TEXT NOT NULL PRIMARY KEY)")
                        db.execSQL("INSERT INTO sales(id) VALUES ('historic-sale')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        helper.writableDatabase.use { db ->
            MedtryxDatabase.MIGRATION_6_7.migrate(db)
            val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertTrue(tables.containsAll(setOf("cashier_shifts", "active_shift_claims", "shift_cash_movements", "shift_variance_policies", "shift_adjustments")))
            db.query("SELECT shiftId FROM sales WHERE id = 'historic-sale'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
            val triggers = db.query("SELECT name FROM sqlite_master WHERE type = 'trigger'").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertTrue(triggers.containsAll(setOf("shift_closed_no_update", "shift_closed_no_delete", "shift_movements_no_update", "shift_adjustments_no_delete")))
        }
        helper.close()
        context.deleteDatabase(TEST_DB)
    }

    private companion object { const val TEST_DB = "shift-migration-v6.db" }
}
