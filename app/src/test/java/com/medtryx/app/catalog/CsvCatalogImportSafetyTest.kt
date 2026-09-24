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
        val initialCsv = headers + "A1,Item,piece,1.00,VATABLE,source,2026-01-01,NONE,OTC,0,false"
        first.commit(owner.sessionId, initialCsv, initial, "initial catalog")
        val conflict = first.validateAgainstCatalog(first.preview(headers + "A1,Replacement,piece,1.00,VATABLE,source,2026-01-01,NONE,OTC,0,false"))
        assertTrue(conflict.rows.single().errors.any { it.field == "sku" })
    }

    @Test fun database_failure_rolls_back_the_whole_import() = runTest {
        val owner = auth.bootstrapOwner("owner", "Owner", "1234".toCharArray())
        var writes = 0
        val importer = CsvCatalogImporter(db, catalog, ProtectedActionAuthorizer(auth), afterProductWrite = { if (++writes == 2) error("injected write failure") })
        val preview = importer.preview(headers + "A1,First,piece,1.00,VATABLE,source,2026-01-01,NONE,OTC,0,false\nA2,Second,piece,2.00,VATABLE,source,2026-01-01,NONE,OTC,0,false")
        runCatching { importer.commit(owner.sessionId, headers + "A1,First,piece,1.00,VATABLE,source,2026-01-01,NONE,OTC,0,false\nA2,Second,piece,2.00,VATABLE,source,2026-01-01,NONE,OTC,0,false", preview, "test rollback") }
        assertEquals(0, db.catalogDao().count())
    }

    @Test fun commit_rejects_source_changed_after_preview() = runTest {
        val owner = auth.bootstrapOwner("owner", "Owner", "1234".toCharArray())
        val importer = CsvCatalogImporter(db, catalog, ProtectedActionAuthorizer(auth))
        val csv = headers + "A1,Item,piece,1.00,VATABLE,source,2026-01-01,NONE,OTC,0,false"
        val preview = importer.preview(csv)
        val changed = csv.replace("Item", "Other")
        val failure = runCatching { importer.commit(owner.sessionId, changed, preview, "commit changed source") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, db.catalogDao().count())
    }

    @Test fun utf8_bom_is_accepted_and_short_rows_are_reported() {
        val importer = CsvCatalogImporter(db, catalog, ProtectedActionAuthorizer(auth))
        val bom = "\uFEFF" + headers + "A1,Item,piece,1.00,VATABLE,source,2026-01-01,NONE,OTC,0,false"
        assertTrue(importer.preview(bom).validRows.size == 1)
        val shortRow = importer.preview(headers + "A1,Item,piece,1.00,VATABLE,source,2026-01-01,NONE,OTC,0")
        assertTrue(shortRow.rows.single().errors.any { it.field == "row" })
    }

    @Test fun file_decoder_rejects_malformed_utf8_and_files_over_the_limit() {
        assertTrue(runCatching { CatalogCsvEncoding.decodeUtf8(byteArrayOf(0xC3.toByte(), 0x28)) }.isFailure)
        assertTrue(runCatching { CatalogCsvEncoding.decodeUtf8(ByteArray(CatalogCsvEncoding.MAX_BYTES + 1)) }.isFailure)
    }

    @Test fun a_single_catalog_row_can_create_multiple_opening_lots() = runTest {
        val owner = auth.bootstrapOwner("owner", "Owner", "1234".toCharArray())
        val importer = CsvCatalogImporter(db, catalog, ProtectedActionAuthorizer(auth))
        val csv = "sku,name,unit,selling_price,tax_class,tax_source,tax_valid_from,benefit_eligibility,prescription_class,reorder_level,requires_lot_expiry,opening_lots\n" +
            "MED-1,Medicine,tablet,1.00,VATABLE,source,2026-01-01,NONE,OTC,0,true,\"L1 | 2027-01-01 | 2\nL2 | 2028-01-01 | 3\""
        val preview = importer.preview(csv)
        assertEquals(1, preview.validRows.size)
        val ids = importer.commit(owner.sessionId, csv, preview, "opening catalog")
        assertEquals(java.math.BigDecimal("5"), InventoryService(db, ProtectedActionAuthorizer(auth)).onHand(ids.single()))
        assertEquals(2, InventoryService(db, ProtectedActionAuthorizer(auth)).availableLots(ids.single()).size)
    }
}
