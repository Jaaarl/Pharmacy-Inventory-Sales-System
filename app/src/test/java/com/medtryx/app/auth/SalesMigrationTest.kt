package com.medtryx.app.auth

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SalesMigrationTest {
    @Test fun version_5_migration_creates_atomic_sales_tables_and_immutable_record_triggers() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(TEST_DB)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE users (id TEXT NOT NULL PRIMARY KEY)")
                        db.execSQL("CREATE TABLE products (id TEXT NOT NULL PRIMARY KEY)")
                        db.execSQL("CREATE TABLE inventory_movements (id TEXT NOT NULL PRIMARY KEY)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )

        helper.writableDatabase.apply {
            MedtryxDatabase.MIGRATION_5_6.migrate(this)
            val tables = query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertTrue(tables.containsAll(setOf("sales", "sale_lines", "sale_line_allocations", "transaction_sequences", "rounding_rule_approvals")))
            val triggers = query("SELECT name FROM sqlite_master WHERE type = 'trigger'").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertTrue(triggers.containsAll(setOf("sales_no_update", "sales_no_delete", "sale_lines_no_update", "sale_allocations_no_delete")))
            close()
        }
        context.deleteDatabase(TEST_DB)
    }

    private companion object { const val TEST_DB = "sales-migration-v5.db" }
}
