package com.medtryx.app.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.medtryx.app.auth.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class InventoryServiceTest {
    private lateinit var db: MedtryxDatabase
    private lateinit var auth: AuthenticationService
    private lateinit var catalog: CatalogService
    private lateinit var inventory: InventoryService
    private lateinit var owner: AuthenticatedSession
    private val now = LocalDate.parse("2026-06-01")

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MedtryxDatabase::class.java).allowMainThreadQueries().build()
        auth = AuthenticationService(db, PasswordHasher(), "test-device", clock = { 1000L })
        catalog = CatalogService(db, ProtectedActionAuthorizer(auth), auth)
        inventory = InventoryService(db, ProtectedActionAuthorizer(auth), clock = { 2000L }, today = { now })
    }
    @After fun close() = db.close()

    private fun draft(sku: String = "MED-1", opening: List<OpeningLotDraft> = emptyList(), medicine: Boolean = true, reorder: BigDecimal = BigDecimal.ONE) = ProductDraft(
        sku, "Medicine $sku", unit = "tablet", sellingPrice = BigDecimal("10.00"), taxClass = TaxClass.VATABLE,
        taxSource = "approved", taxValidFrom = LocalDate.parse("2026-01-01"), benefitEligibility = BenefitEligibility.NONE,
        prescriptionClass = PrescriptionClass.OTC, reorderLevel = reorder, requiresLotExpiry = medicine, openingLots = opening,
    )
    private suspend fun owner() { owner = auth.bootstrapOwner("owner", "Owner", "1234".toCharArray()) }
    private suspend fun product(value: ProductDraft = draft()) = catalog.createProduct(owner.sessionId, value, "test product")
    private suspend fun lotId(productId: String, number: String) = inventory.availableLots(productId).single { it.lotNumber == number }.lotId
    private suspend fun rejects(block: suspend () -> Unit) { try { block(); fail("Expected validation to reject the operation") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { } }

    @Test fun opening_stock_is_derived_from_immutable_movements_and_allocates_earliest_unexpired_lots() = runTest {
        owner()
        val id = product(draft(opening = listOf(OpeningLotDraft("LATE", LocalDate.parse("2028-01-01"), BigDecimal("2")), OpeningLotDraft("EARLY", LocalDate.parse("2027-01-01"), BigDecimal("3")))))
        assertEquals(BigDecimal("5"), inventory.onHand(id))
        val movements = db.inventoryDao().movements(id)
        assertEquals(2, movements.size); assertTrue(movements.all { it.type == InventoryMovementType.OPENING_BALANCE && it.quantity.toBigDecimal() > BigDecimal.ZERO })
        val allocation = inventory.allocateEarliestExpiry(id, BigDecimal("4"))
        assertEquals(listOf("EARLY", "LATE"), allocation.map { item -> inventory.availableLots(id).single { it.lotId == item.lotId }.lotNumber })
        assertEquals(listOf(BigDecimal("3"), BigDecimal.ONE), allocation.map { it.quantity })
        val ties = product(draft("MED-TIE", opening = listOf(OpeningLotDraft("B", LocalDate.parse("2027-01-01"), BigDecimal.ONE), OpeningLotDraft("A", LocalDate.parse("2027-01-01"), BigDecimal.ONE))))
        val tiedLots = inventory.availableLots(ties)
        assertEquals(tiedLots.map { it.lotId }.sorted(), inventory.allocateEarliestExpiry(ties, BigDecimal("2")).map { it.lotId })
    }

    @Test fun receipts_validate_medicine_lots_dates_cost_and_append_audited_movement() = runTest {
        owner(); val id = product()
        rejects { inventory.receive(owner.sessionId, StockReceiptDraft(id, BigDecimal.ONE), "receipt") }
        rejects { inventory.receive(owner.sessionId, StockReceiptDraft(id, BigDecimal.ONE, "R1", "2020-01-01"), "receipt") }
        rejects { inventory.receive(owner.sessionId, StockReceiptDraft(id, BigDecimal.ONE, "R1", "not-a-date"), "receipt") }
        rejects { inventory.receive(owner.sessionId, StockReceiptDraft(id, BigDecimal.ONE, "R1", "2027-02-01", -1), "receipt") }
        val movementId = inventory.receive(owner.sessionId, StockReceiptDraft(id, BigDecimal("2.5"), "R1", "2027-02-01", 1250, "supplier-7"), "invoice from supplier")
        assertEquals(BigDecimal("2.5"), inventory.onHand(id))
        val movement = db.inventoryDao().movements(id).single()
        assertEquals(movementId, movement.id); assertEquals(InventoryMovementType.RECEIPT, movement.type); assertEquals("R1", inventory.availableLots(id).single().lotNumber)
        assertEquals(1250L, movement.costCentavos); assertEquals("supplier-7", movement.sourceReference)
        val audit = db.authDao().auditEvents().single { it.action == "INVENTORY_RECEIPT_RECORDED" }
        assertEquals("INVENTORY_RECEIPT_RECORDED", audit.action); assertEquals("onHand=0", audit.oldValue)
        assertTrue(audit.newValue.orEmpty().contains("movement=$movementId")); assertTrue(audit.newValue.orEmpty().contains("onHand=2.5")); assertEquals("test-device", audit.deviceId)
    }

    @Test fun adjustments_are_authorized_lot_scoped_signed_and_cannot_create_negative_stock() = runTest {
        owner()
        val id = product(draft(opening = listOf(OpeningLotDraft("A", LocalDate.parse("2027-01-01"), BigDecimal("2")), OpeningLotDraft("B", LocalDate.parse("2027-02-01"), BigDecimal("3")))))
        val lot = lotId(id, "A")
        rejects { inventory.adjust(owner.sessionId, id, null, BigDecimal.ONE.negate(), "count") }
        rejects { inventory.adjust(owner.sessionId, id, lot, BigDecimal("-3"), "count") }
        rejects { inventory.adjust(owner.sessionId, id, "missing", BigDecimal.ONE, "count") }
        val other = product(draft("MED-2", medicine = true))
        rejects { inventory.adjust(owner.sessionId, other, lot, BigDecimal.ONE, "wrong SKU lot") }
        val adjustmentIn = inventory.adjust(owner.sessionId, id, lot, BigDecimal.ONE, "physical count surplus")
        val adjustmentOut = inventory.adjust(owner.sessionId, id, lot, BigDecimal("-0.5"), "physical count shortage")
        assertEquals(BigDecimal("5.5"), inventory.onHand(id))
        val movements = db.inventoryDao().movements(id)
        val incoming = movements.single { it.id == adjustmentIn }; val outgoing = movements.single { it.id == adjustmentOut }
        assertEquals(InventoryMovementType.ADJUSTMENT_IN, incoming.type); assertEquals("1", incoming.quantity)
        assertEquals(InventoryMovementType.ADJUSTMENT_OUT, outgoing.type); assertEquals("-0.5", outgoing.quantity)
        val audit = db.authDao().auditEvents().filter { it.action == "INVENTORY_ADJUSTMENT_RECORDED" }
        assertEquals(2, audit.size)
        val shortage = audit.single { it.entityReference == id && it.reason == "physical count shortage" }
        assertEquals("onHand=6", shortage.oldValue); assertTrue(shortage.newValue.orEmpty().contains("onHand=5.5")); assertTrue(shortage.newValue.orEmpty().contains("lot=A"))
    }

    @Test fun expired_stock_is_never_allocated_and_disposal_is_negative_expired_movement() = runTest {
        owner()
        val id = product(draft(opening = listOf(OpeningLotDraft("PAST", LocalDate.parse("2026-05-31"), BigDecimal("2")), OpeningLotDraft("SOON", LocalDate.parse("2026-06-20"), BigDecimal("5")))))
        val expired = lotId(id, "PAST")
        assertEquals(listOf(BigDecimal("3")), inventory.allocateEarliestExpiry(id, BigDecimal("3")).map { it.quantity })
        rejects { inventory.allocateEarliestExpiry(id, BigDecimal("3.1")) }
        rejects { inventory.disposeExpired(owner.sessionId, lotId(id, "SOON"), BigDecimal.ONE, "not expired") }
        rejects { inventory.disposeExpired(owner.sessionId, expired, BigDecimal("3"), "count") }
        rejects { inventory.adjust(owner.sessionId, id, expired, BigDecimal.ONE, "expired count") }
        val movementId = inventory.disposeExpired(owner.sessionId, expired, BigDecimal.ONE, "destroyed per procedure")
        assertEquals(BigDecimal("6"), inventory.onHand(id))
        val movement = db.inventoryDao().movements(id).single { it.id == movementId }
        assertEquals(movementId, movement.id); assertEquals(InventoryMovementType.EXPIRED, movement.type); assertEquals("-1", movement.quantity)
        assertTrue(db.authDao().auditEvents().any { it.action == "EXPIRED_STOCK_DISPOSED" && it.newValue.orEmpty().contains("remaining=1") })
    }

    @Test fun dashboard_status_derives_low_expired_and_configured_near_expiry_alerts() = runTest {
        owner()
        val id = product(draft(opening = listOf(OpeningLotDraft("EXPIRED", LocalDate.parse("2026-05-31"), BigDecimal.ONE), OpeningLotDraft("NEAR", LocalDate.parse("2026-06-15"), BigDecimal("2"))), reorder = BigDecimal("4")))
        val noHorizon = inventory.alerts(now)
        assertTrue(noHorizon.any { it.productId == id && it.type == InventoryAlertType.LOW_STOCK })
        assertTrue(noHorizon.any { it.productId == id && it.type == InventoryAlertType.EXPIRED && it.lotNumber == "EXPIRED" })
        assertFalse(noHorizon.any { it.type == InventoryAlertType.NEAR_EXPIRY })
        val configured = inventory.alerts(now.plusDays(13))
        assertTrue(configured.none { it.type == InventoryAlertType.NEAR_EXPIRY })
        val inclusive = inventory.alerts(now.plusDays(14))
        assertTrue(inclusive.any { it.type == InventoryAlertType.NEAR_EXPIRY && it.lotNumber == "NEAR" })
        val status = inventory.status(now).single { it.productId == id }
        assertEquals(BigDecimal("3"), status.onHand); assertEquals(BigDecimal("4"), status.reorderLevel); assertTrue(status.isLowStock)
    }

    @Test fun non_medicine_skus_can_receive_and_allocate_without_lots() = runTest {
        owner(); val id = product(draft("RETAIL-1", medicine = false))
        inventory.receive(owner.sessionId, StockReceiptDraft(id, BigDecimal("4"), unitCostCentavos = 99), "retail receipt")
        val allocation = inventory.allocateEarliestExpiry(id, BigDecimal("3"))
        assertEquals(listOf(LotAllocation(null, BigDecimal("3"))), allocation)
    }

    @Test fun pack_receipts_use_each_skus_configured_conversion_without_rounding() = runTest {
        owner()
        val tenUnitSku = product(draft("PACK-10", medicine = false).copy(packSize = BigDecimal("10")))
        val twelveUnitSku = product(draft("PACK-12", medicine = false).copy(packSize = BigDecimal("12")))
        val first = inventory.receive(owner.sessionId, StockReceiptDraft(tenUnitSku, BigDecimal("3"), receivedInPacks = true), "three ten-unit packs")
        val second = inventory.receive(owner.sessionId, StockReceiptDraft(twelveUnitSku, BigDecimal("3"), receivedInPacks = true), "three twelve-unit packs")
        assertEquals(BigDecimal("30"), inventory.onHand(tenUnitSku)); assertEquals(BigDecimal("36"), inventory.onHand(twelveUnitSku))
        assertEquals("30", db.inventoryDao().movements(tenUnitSku).single { it.id == first }.quantity)
        assertEquals("36", db.inventoryDao().movements(twelveUnitSku).single { it.id == second }.quantity)
        val noConversion = product(draft("PACK-NONE", medicine = false))
        rejects { inventory.receive(owner.sessionId, StockReceiptDraft(noConversion, BigDecimal.ONE, receivedInPacks = true), "no conversion configured") }
        assertEquals(BigDecimal.ZERO, inventory.onHand(noConversion))
        val fractionalPack = product(draft("PACK-FRACTION", medicine = false).copy(packSize = BigDecimal("0.001")))
        rejects { inventory.receive(owner.sessionId, StockReceiptDraft(fractionalPack, BigDecimal("0.1234"), receivedInPacks = true), "quantity would need rounding") }
        assertEquals(BigDecimal.ZERO, inventory.onHand(fractionalPack))
    }

    @Test fun optional_expiry_lots_on_retail_skus_are_not_received_expired_or_allocated_after_expiry() = runTest {
        owner()
        val id = product(draft("RETAIL-EXP", medicine = false, opening = listOf(OpeningLotDraft("PAST", LocalDate.parse("2026-05-31"), BigDecimal.ONE))))
        rejects { inventory.receive(owner.sessionId, StockReceiptDraft(id, BigDecimal.ONE, "BAD", "2026-05-31"), "expired retail receipt") }
        inventory.receive(owner.sessionId, StockReceiptDraft(id, BigDecimal("2"), "FRESH", "2026-06-20"), "dated retail receipt")
        val freshLot = lotId(id, "FRESH")
        rejects { inventory.adjust(owner.sessionId, id, null, BigDecimal.ONE.negate(), "ambiguous count") }
        inventory.receive(owner.sessionId, StockReceiptDraft(id, BigDecimal("2")), "untracked retail receipt")
        inventory.adjust(owner.sessionId, id, freshLot, BigDecimal.ONE.negate(), "lot count")
        assertEquals(listOf(LotAllocation(freshLot, BigDecimal.ONE), LotAllocation(null, BigDecimal("2"))), inventory.allocateEarliestExpiry(id, BigDecimal("3")))
        rejects { inventory.allocateEarliestExpiry(id, BigDecimal("3.1")) }
    }

    @Test fun receipt_cost_is_snapshotted_and_future_receipts_use_the_current_cost() = runTest {
        owner(); val initial = draft().copy(unitCost = BigDecimal("4.00")); val id = product(initial)
        val firstId = inventory.receive(owner.sessionId, StockReceiptDraft(id, BigDecimal.ONE, "L1", "2027-01-01"), "first receipt")
        val snapshot = catalog.productSnapshot(id)!!
        catalog.updateProduct(owner.sessionId, id, snapshot, initial.copy(unitCost = BigDecimal("5.25")), LocalDate.parse("2026-02-01"), "cost update")
        val secondId = inventory.receive(owner.sessionId, StockReceiptDraft(id, BigDecimal.ONE, "L1", "2027-01-01"), "second receipt")
        val movements = db.inventoryDao().movements(id).associateBy { it.id }
        assertEquals(400L, movements[firstId]!!.costCentavos)
        assertEquals(525L, movements[secondId]!!.costCentavos)
    }

    @Test fun cashiers_cannot_receive_or_adjust_stock() = runTest {
        owner(); val id = product()
        auth.createUser(owner.sessionId, "cashier", "Cashier", Role.CASHIER, "1234".toCharArray(), reason = "test")
        val cashier = (auth.login("cashier", "1234".toCharArray()) as LoginResult.Success).session
        try { inventory.receive(cashier.sessionId, StockReceiptDraft(id, BigDecimal.ONE, "LOT", "2027-01-01"), "unauthorized"); fail("cashier must not receive") } catch (_: AccessDeniedException) { }
        try { inventory.adjust(cashier.sessionId, id, null, BigDecimal.ONE, "unauthorized"); fail("cashier must not adjust") } catch (_: AccessDeniedException) { }
        assertEquals(BigDecimal.ZERO, inventory.onHand(id))
    }
}
