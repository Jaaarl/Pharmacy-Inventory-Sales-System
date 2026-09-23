package com.medtryx.app.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.medtryx.app.auth.AuthenticationService
import com.medtryx.app.auth.MedtryxDatabase
import com.medtryx.app.auth.PasswordHasher
import com.medtryx.app.auth.ProtectedActionAuthorizer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CsvCatalogImportSafetyTest {
    private lateinit var db: MedtryxDatabase
    private lateinit var auth: AuthenticationService
    private lateinit var catalog: CatalogService
    private val headers = "sku,name,unit,selling_price,tax_class,tax_source,tax_valid_from,benefit_eligibility,prescription_class,reorder_level,requires_lot_expiry\n"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MedtryxDatabase::class.java).allowMainThreadQueries().build()
        auth = AuthenticationService(db, PasswordHasher(), "device")
        catalog = CatalogService(db, ProtectedActionAuthorizer(auth), auth)
    }
    @After fun tearDown() = db.close()

    @Test fun empty_and_invalid_boolean_csv_are_rejected_without_defaulting() {
        val importer = CsvCatalogImporter(db, catalog, ProtectedActionAuthorizer(auth))
        assertTrue(importer.preview("").rows.single().errors.any { it.field == "csv" })
        val preview = importer.preview(headers + "A1,Item,piece,1.00,VATABLE,source,2026-01-01,NONE,OTC,0,yes")
        assertTrue(preview.rows.single().errors.any { it.field == "requires_lot_expiry" })
    }

    @Test fun existing_catalog_values_are_shown_as_preview_errors() = runTest {
        val owner = auth.bootstrapOwner("owner", "Owner", "1234".toCharArray())
        val first = CsvCatalogImporter(db, catalog, ProtectedActionAuthorizer(auth))
        val initial = first.preview(headers + "A1,Item,piece,1.00,VATABLE,source,2026-01-01,NONE,OTC,0,false")
        first.commit(owner.sessionId, initial, "initial catalog")
        val conflict = first.validateAgainstCatalog(first.preview(headers + "A1,Replacement,piece,1.00,VATABLE,source,2026-01-01,NONE,OTC,0,false"))
        assertTrue(conflict.rows.single().errors.any { it.field == "sku" })
    }

    @Test fun database_failure_rolls_back_the_whole_import() = runTest {
        val owner = auth.bootstrapOwner("owner", "Owner", "1234".toCharArray())
        var writes = 0
        val importer = CsvCatalogImporter(db, catalog, ProtectedActionAuthorizer(auth), afterProductWrite = { if (++writes == 2) error("injected write failure") })
        val preview = importer.preview(headers + "A1,First,piece,1.00,VATABLE,source,2026-01-01,NONE,OTC,0,false\nA2,Second,piece,2.00,VATABLE,source,2026-01-01,NONE,OTC,0,false")
        runCatching { importer.commit(owner.sessionId, preview, "test rollback") }
        assertEquals(0, db.catalogDao().count())
    }
}
