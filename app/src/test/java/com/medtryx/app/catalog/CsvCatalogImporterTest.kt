package com.medtryx.app.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.medtryx.app.auth.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CsvCatalogImporterTest {
 private lateinit var db:MedtryxDatabase; private lateinit var auth:AuthenticationService; private lateinit var importer:CsvCatalogImporter
 @Before fun setup(){ db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),MedtryxDatabase::class.java).allowMainThreadQueries().build(); auth=AuthenticationService(db,PasswordHasher(),"device"); val catalog=CatalogService(db,ProtectedActionAuthorizer(auth)); importer=CsvCatalogImporter(db,catalog,ProtectedActionAuthorizer(auth)) }
 @After fun close()=db.close()
 private val headers="sku,name,unit,selling_price,tax_class,tax_source,tax_valid_from,benefit_eligibility,prescription_class,reorder_level,requires_lot_expiry\n"
 @Test fun preview_reports_row_errors_and_full_commit_is_atomic()=runTest { val owner=auth.bootstrapOwner("owner","Owner","1234".toCharArray()); val csv=headers+"A1,Valid,tablet,10.00,VATABLE,source,2026-01-01,NONE,OTC,1,false\nBAD,,tablet,oops,VATABLE,source,2026-01-01,NONE,OTC,1,false"; val p=importer.validateAgainstCatalog(importer.preview(csv)); assertEquals(1,p.validRows.size); assertTrue(p.rows[1].errors.isNotEmpty()); try{importer.commit(owner.sessionId,csv,p,"initial import");fail()}catch(_:IllegalArgumentException){}; assertEquals(0,db.catalogDao().count()); val ids=importer.commit(owner.sessionId,csv,p,"reviewed",setOf(2)); assertEquals(1,ids.size); assertEquals(1,db.catalogDao().count()) }
 @Test fun csv_supports_unicode_and_quoted_commas() { val p=importer.preview(headers+"U1,\"Biogesic, 500mg\",tablet,10.00,VATABLE,Pinoy source,2026-01-01,SC_PWD_20,OTC,1,false"); assertTrue(p.rows.single().errors.isEmpty()); assertEquals("Biogesic, 500mg",p.rows.single().draft!!.name) }
 @Test fun duplicate_and_malformed_input_are_preview_errors() { val duplicate=importer.preview(headers+"D1,One,piece,1.00,VATABLE,source,2026-01-01,NONE,OTC,0,false\nD1,Two,piece,1.00,VATABLE,source,2026-01-01,NONE,OTC,0,false"); assertTrue(duplicate.rows.all { it.errors.any { e -> e.field=="sku" } }); val malformed=importer.preview(headers+"Q1,\"unclosed,piece,1.00,VATABLE,source,2026-01-01,NONE,OTC,0,false"); assertTrue(malformed.rows.single().errors.any { it.field=="csv" }) }
 @Test fun duplicates_are_reported_even_when_one_row_has_other_errors() { val csv = headers + "D2,,piece,1.00,VATABLE,source,2026-01-01,NONE,OTC,0,false\nD2,Valid,piece,1.00,VATABLE,source,2026-01-01,NONE,OTC,0,false"; val preview = importer.preview(csv); assertTrue(preview.rows.all { row -> row.errors.any { it.field == "sku" } }) }
}
