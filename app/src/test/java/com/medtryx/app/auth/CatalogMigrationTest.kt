package com.medtryx.app.auth

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.medtryx.app.catalog.PrescriptionClass

@RunWith(RobolectricTestRunner::class)
class CatalogMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MedtryxDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun version_4_products_migrate_with_safe_prescription_default() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(TEST_DB)
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL("INSERT INTO products (id, sku, name, genericName, brand, strength, dosageForm, unit, packSize, active, reorderLevel, requiresLotExpiry, createdAt, updatedAt) VALUES ('p-1', 'SKU-1', 'Legacy item', NULL, NULL, NULL, NULL, 'piece', NULL, 1, '0', 0, 1, 1)")
            close()
        }

        val migrated = Room.databaseBuilder(context, MedtryxDatabase::class.java, TEST_DB)
            .addMigrations(MedtryxDatabase.MIGRATION_4_5)
            .build()
        try {
            assertEquals(PrescriptionClass.OTHER, migrated.catalogDao().productById("p-1")!!.prescriptionClass)
        } finally {
            migrated.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    private companion object { const val TEST_DB = "catalog-migration-v4.db" }
}
