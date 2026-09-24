package com.medtryx.app.auth

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CatalogMigrationTest {
    @Test fun version_4_products_migrate_with_safe_prescription_default() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(TEST_DB)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE products (id TEXT NOT NULL PRIMARY KEY, sku TEXT NOT NULL, name TEXT NOT NULL, genericName TEXT, brand TEXT, strength TEXT, dosageForm TEXT, unit TEXT NOT NULL, packSize TEXT, active INTEGER NOT NULL, reorderLevel TEXT NOT NULL, requiresLotExpiry INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )

        helper.writableDatabase.apply {
            execSQL("INSERT INTO products (id, sku, name, genericName, brand, strength, dosageForm, unit, packSize, active, reorderLevel, requiresLotExpiry, createdAt, updatedAt) VALUES ('p-1', 'SKU-1', 'Legacy item', NULL, NULL, NULL, NULL, 'piece', NULL, 1, '0', 0, 1, 1)")
            MedtryxDatabase.MIGRATION_4_5.migrate(this)
            query("SELECT prescriptionClass FROM products WHERE id = 'p-1'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("OTHER", cursor.getString(0))
            }
            close()
        }
        context.deleteDatabase(TEST_DB)
    }

    private companion object { const val TEST_DB = "catalog-migration-v4.db" }
}
