package com.medtryx.app.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.medtryx.app.auth.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class InventoryServiceTest {
    private lateinit var db: MedtryxDatabase; private lateinit var auth: AuthenticationService; private lateinit var catalog: CatalogService; private lateinit var inventory: InventoryService
    @Before fun setUp() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MedtryxDatabase::class.java).allowMainThreadQueries().build(); auth = AuthenticationService(db, PasswordHasher(), "device"); catalog = CatalogService(db, ProtectedActionAuthorizer(auth), auth); inventory = InventoryService(db, ProtectedActionAuthorizer(auth)) }
    @After fun close() = db.close()
    private fun draft() = ProductDraft("MED-1", "Medicine", unit = "tablet", sellingPrice = BigDecimal("10.00"), taxClass = TaxClass.VATABLE, taxSource = "approved", taxValidFrom = LocalDate.parse("2026-01-01"), benefitEligibility = BenefitEligibility.NONE, prescriptionClass = PrescriptionClass.OTC, reorderLevel = BigDecimal.ONE, requiresLotExpiry = true, openingLots = listOf(OpeningLotDraft("LATE", LocalDate.parse("2028-01-01"), BigDecimal("2")), OpeningLotDraft("EARLY", LocalDate.parse("2027-01-01"), BigDecimal("3"))))
    @Test fun opening_stock_is_derived_from_immutable_movements_and_allocates_earliest_expiry() = runTest {
        val owner = auth.bootstrapOwner("owner", "Owner", "1234".toCharArray()); val productId = catalog.createProduct(owner.sessionId, draft(), "opening stock")
        assertEquals(BigDecimal("5"), inventory.onHand(productId)); val allocation = inventory.allocateEarliestExpiry(productId, BigDecimal("4"))
        assertEquals("EARLY", db.inventoryDao().lotMovementRows(productId).first { it.lotId == allocation.first().first }.lotNumber); assertEquals(BigDecimal("3"), allocation.first().second); assertEquals(BigDecimal("1"), allocation.last().second)
    }
    @Test fun medicine_receipt_requires_lot_and_expiry() = runTest {
        val owner = auth.bootstrapOwner("owner", "Owner", "1234".toCharArray()); val productId = catalog.createProduct(owner.sessionId, draft().copy(openingLots = emptyList()), "create")
        try { inventory.receive(owner.sessionId, StockReceiptDraft(productId, BigDecimal.ONE), "receipt"); fail("Expected lot/expiry validation") } catch (_: IllegalArgumentException) { }
        inventory.receive(owner.sessionId, StockReceiptDraft(productId, BigDecimal.ONE, "R1", "2027-02-01"), "receipt")
        assertEquals(BigDecimal.ONE, inventory.onHand(productId))
    }
}
