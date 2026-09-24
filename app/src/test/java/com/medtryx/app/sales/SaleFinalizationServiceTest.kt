package com.medtryx.app.sales

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.medtryx.app.auth.*
import com.medtryx.app.catalog.*
import com.medtryx.app.financial.CustomerBenefit
import com.medtryx.app.security.SensitiveDataProtector
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SaleFinalizationServiceTest {
    private lateinit var db: MedtryxDatabase
    private lateinit var auth: AuthenticationService
    private lateinit var catalog: CatalogService
    private lateinit var inventory: InventoryService
    private lateinit var owner: AuthenticatedSession
    private lateinit var service: SaleFinalizationService
    private val date = LocalDate.parse("2026-06-01")

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MedtryxDatabase::class.java)
            .addCallback(MedtryxDatabase.IMMUTABILITY_CALLBACK).allowMainThreadQueries().build()
        auth = AuthenticationService(db, PasswordHasher(), "sales-test-device", clock = { Instant.parse("2026-06-01T12:00:00Z").toEpochMilli() })
        catalog = CatalogService(db, ProtectedActionAuthorizer(auth), auth, clock = { 1_780_000_000_000L })
        inventory = InventoryService(db, ProtectedActionAuthorizer(auth), clock = { 1_780_000_000_000L }, today = { date })
    }

    @After fun tearDown() = db.close()

    private suspend fun setupService(failure: (FinalizationCheckpoint) -> Unit = {}) {
        owner = auth.bootstrapOwner("owner", "Owner", "1234".toCharArray())
        service = SaleFinalizationService(
            db, auth, ProtectedActionAuthorizer(auth), inventory,
            object : SensitiveDataProtector { override fun protect(plainText: String) = "enc:test:${plainText.reversed()}" },
            clock = { Instant.parse("2026-06-01T12:00:00Z") }, today = { date }, failureInjector = failure,
        )
        service.approveRoundingRule(owner.sessionId, RoundingMode.HALF_UP, "approved store rounding")
    }

    private fun product(sku: String, eligible: Boolean, stock: String = "5") = ProductDraft(
        sku = sku, name = "Item $sku", unit = "tablet", sellingPrice = BigDecimal("112.00"),
        taxClass = TaxClass.VATABLE, taxSource = "approved classification", taxValidFrom = date.minusDays(10),
        benefitEligibility = if (eligible) BenefitEligibility.SC_PWD_20 else BenefitEligibility.NONE,
        prescriptionClass = PrescriptionClass.OTC, reorderLevel = BigDecimal.ZERO, requiresLotExpiry = true,
        openingLots = listOf(OpeningLotDraft("LOT-$sku", date.plusYears(1), BigDecimal(stock))),
    )

    private suspend fun createProduct(sku: String, eligible: Boolean, stock: String = "5") =
        catalog.createProduct(owner.sessionId, product(sku, eligible, stock), "checkout test setup")

    private fun scDraft(productId: String, key: String = UUID.randomUUID().toString()) = CheckoutDraft(
        idempotencyKey = key, lines = listOf(CheckoutLineDraft(productId, BigDecimal.ONE, true)),
        customerBenefit = CustomerBenefit.SENIOR_CITIZEN, customerName = "Sample Customer",
        benefitIdType = BenefitIdType.SENIOR_CITIZEN_ID, benefitIdNumber = "SC-12345678",
        physicalIdChecked = true, settlementMethod = SettlementMethod.CASH,
    )

    @Test fun SC_checkout_encrypts_identity_and_deducts_FEFO_stock_once_for_repeated_confirmation() = runTest {
        setupService()
        val id = createProduct("SC-1", eligible = true)
        val draft = scDraft(id)

        val first = service.finalize(owner.sessionId, draft)
        val second = service.finalize(owner.sessionId, draft)

        assertEquals(first.saleId, second.saleId)
        assertEquals("MTX-20260601-000001", first.humanTransactionId)
        assertEquals("INTERNAL SALES RECORD — NOT AN INVOICE", first.documentLabel)
        assertEquals("••••5678", first.maskedBenefitIdNumber)
        assertTrue(first.amountDue.centavos < 11200L)
        val saved = db.salesDao().saleByIdempotencyKey(draft.idempotencyKey)!!
        assertEquals("enc:test:remotsuC elpmaS", saved.customerNameEncrypted)
        assertEquals("enc:test:87654321-CS", saved.benefitIdNumberEncrypted)
        assertFalse(saved.customerNameEncrypted!!.contains("Sample Customer"))
        assertTrue(saved.physicalIdChecked)
        assertEquals(BigDecimal("4"), inventory.onHand(id))
        assertEquals(1, db.salesDao().recentSales(10).size)
        assertEquals(1, db.salesDao().linesForSale(first.saleId).size)
        assertEquals(1, db.salesDao().allocationsForLine(db.salesDao().linesForSale(first.saleId).single().id).size)
        assertTrue(db.authDao().auditEvents().any { it.action == "SALE_FINALIZED" && it.entityReference == first.saleId })
    }

    @Test fun mixed_SC_cart_applies_benefit_only_to_the_explicit_eligible_line() = runTest {
        setupService()
        val eligible = createProduct("ELIGIBLE", eligible = true)
        val regular = createProduct("REGULAR", eligible = false)
        val draft = scDraft(eligible).copy(lines = listOf(
            CheckoutLineDraft(eligible, BigDecimal.ONE, true), CheckoutLineDraft(regular, BigDecimal.ONE, false),
        ))

        val preview = service.preview(owner.sessionId, draft)

        assertEquals(2, preview.lines.size)
        assertTrue(preview.lines[0].calculation.amounts.statutoryDiscount.centavos > 0)
        assertEquals(0L, preview.lines[1].calculation.amounts.statutoryDiscount.centavos)
        assertEquals(11200L, preview.lines[1].calculation.amounts.amountDue.centavos)
    }

    @Test fun preview_aggregates_split_lines_for_the_same_SKU_before_stock_check() = runTest {
        setupService()
        val id = createProduct("LOW-STOCK", eligible = true, stock = "5")
        val draft = scDraft(id).copy(lines = listOf(
            CheckoutLineDraft(id, BigDecimal("3"), true), CheckoutLineDraft(id, BigDecimal("3"), false),
        ))
        try { service.preview(owner.sessionId, draft); fail("aggregate quantity exceeds available stock") }
        catch (_: IllegalStateException) { }
    }

    @Test fun invalid_SC_evidence_and_ineligible_line_are_blocked_before_sale_creation() = runTest {
        setupService()
        val eligible = createProduct("ELIGIBLE", eligible = true)
        val regular = createProduct("REGULAR", eligible = false)
        try { service.finalize(owner.sessionId, scDraft(eligible).copy(physicalIdChecked = false)); fail("physical ID check required") } catch (_: IllegalArgumentException) { }
        try { service.finalize(owner.sessionId, scDraft(regular)); fail("ineligible SKU cannot receive SC treatment") } catch (_: IllegalArgumentException) { }
        assertTrue(db.salesDao().recentSales(10).isEmpty())
    }

    @Test fun injected_failure_at_each_finalization_checkpoint_rolls_back_every_write() = runTest {
        setupService()
        val stockedId = createProduct("ROLLBACK", eligible = true)
        FinalizationCheckpoint.entries.forEach { checkpoint ->
            val failing = SaleFinalizationService(
                db, auth, ProtectedActionAuthorizer(auth), inventory,
                object : SensitiveDataProtector { override fun protect(plainText: String) = "enc:test:$plainText" },
                clock = { Instant.parse("2026-06-01T12:00:00Z") }, today = { date },
                failureInjector = { reached -> if (reached == checkpoint) error("injected $checkpoint") },
            )
            try { failing.finalize(owner.sessionId, scDraft(stockedId)); fail("expected failure at $checkpoint") }
            catch (e: IllegalStateException) { assertEquals("injected $checkpoint", e.message) }
            assertTrue("sales after $checkpoint", db.salesDao().recentSales(10).isEmpty())
            assertEquals("stock after $checkpoint", BigDecimal("5"), inventory.onHand(stockedId))
            assertNull("sequence after $checkpoint", db.salesDao().sequenceFor(date.toString()))
            assertTrue("finalized audit after $checkpoint", db.authDao().auditEvents().none { it.action == "SALE_FINALIZED" })
        }
        assertTrue(db.salesDao().recentSales(10).isEmpty())
        val recovered = service.finalize(owner.sessionId, scDraft(stockedId))
        assertEquals("MTX-20260601-000001", recovered.humanTransactionId)
    }

    @Test fun cashiers_can_checkout_but_cannot_approve_rounding_and_fresh_database_records_are_immutable() = runTest {
        setupService()
        val cashierId = auth.createUser(owner.sessionId, "cashier", "Cashier", Role.CASHIER, "5678".toCharArray(), reason = "checkout access")
        val cashier = (auth.login("cashier", "5678".toCharArray()) as LoginResult.Success).session
        try { service.approveRoundingRule(cashier.sessionId, RoundingMode.DOWN, "unauthorized change"); fail("cashier cannot approve rounding") } catch (_: AccessDeniedException) { }
        val id = createProduct("CASHIER-1", eligible = false)
        val sale = service.finalize(cashier.sessionId, CheckoutDraft(UUID.randomUUID().toString(), listOf(CheckoutLineDraft(id, BigDecimal.ONE)), settlementMethod = SettlementMethod.CASH))
        assertEquals(cashierId, db.salesDao().saleById(sale.saleId)!!.cashierUserId)
        try { db.openHelper.writableDatabase.execSQL("UPDATE sales SET amountDueCentavos = 0 WHERE id = '${sale.saleId}'"); fail("finalized sale must be immutable") } catch (_: android.database.sqlite.SQLiteException) { }
    }

    @Test fun QR_is_an_unverified_declaration_and_gets_a_distinct_transaction_id() = runTest {
        setupService()
        val id = createProduct("QR-1", eligible = false)
        val sale = service.finalize(owner.sessionId, CheckoutDraft(
            UUID.randomUUID().toString(), listOf(CheckoutLineDraft(id, BigDecimal.ONE)),
            settlementMethod = SettlementMethod.QR, qrReference = "external-ref", customerShowedQrSuccess = true,
        ))
        assertEquals(SettlementMethod.QR, sale.settlementMethod)
        assertEquals("external-ref", sale.qrReference)
        assertTrue(sale.customerShowedQrSuccess)
        assertEquals("MTX-20260601-000001", sale.humanTransactionId)
        val second = service.finalize(owner.sessionId, CheckoutDraft(
            UUID.randomUUID().toString(), listOf(CheckoutLineDraft(id, BigDecimal.ONE)), settlementMethod = SettlementMethod.CASH,
        ))
        assertEquals("MTX-20260601-000002", second.humanTransactionId)
    }

    @Test fun PWD_checkout_keeps_its_separate_benefit_and_tax_result() = runTest {
        setupService()
        val id = createProduct("PWD-1", eligible = true)
        val sale = service.finalize(owner.sessionId, scDraft(id).copy(
            customerBenefit = CustomerBenefit.PERSON_WITH_DISABILITY, benefitIdType = BenefitIdType.PWD_ID,
            benefitIdNumber = "PWD-87654321",
        ))
        assertEquals(CustomerBenefit.PERSON_WITH_DISABILITY, sale.customerBenefit)
        assertEquals(com.medtryx.app.financial.TaxResult.VAT_EXEMPT_PWD, sale.lines.single().calculation.taxResult)
    }

    @Test fun finalization_persists_earliest_expiry_allocations_in_the_inventory_ledger() = runTest {
        setupService()
        val id = catalog.createProduct(owner.sessionId, product("FEFO", eligible = false).copy(
            openingLots = listOf(
                OpeningLotDraft("LATE", date.plusYears(2), BigDecimal("2")),
                OpeningLotDraft("EARLY", date.plusYears(1), BigDecimal("2")),
            ),
        ), "FEFO test setup")
        val sale = service.finalize(owner.sessionId, CheckoutDraft(
            UUID.randomUUID().toString(), listOf(CheckoutLineDraft(id, BigDecimal("3"))), settlementMethod = SettlementMethod.CASH,
        ))
        val line = db.salesDao().linesForSale(sale.saleId).single()
        val allocated = db.salesDao().allocationsForLine(line.id).map { allocation ->
            val lot = db.inventoryDao().lotById(requireNotNull(allocation.lotId))!!
            lot.lotNumber to BigDecimal(allocation.quantity)
        }.toMap()
        assertEquals(mapOf("EARLY" to BigDecimal("2"), "LATE" to BigDecimal.ONE), allocated)
        assertEquals(BigDecimal.ONE, inventory.onHand(id))
    }

    @Test fun concurrent_checkouts_cannot_sell_the_last_unit_twice() = runTest {
        setupService()
        val id = createProduct("LAST-UNIT", eligible = false, stock = "1")
        val attempts = coroutineScope {
            (1..2).map {
                async(Dispatchers.IO) {
                    runCatching {
                        service.finalize(owner.sessionId, CheckoutDraft(
                            UUID.randomUUID().toString(), listOf(CheckoutLineDraft(id, BigDecimal.ONE)),
                            settlementMethod = SettlementMethod.CASH,
                        ))
                    }
                }
            }.awaitAll()
        }
        assertEquals(1, attempts.count { it.isSuccess })
        assertEquals(1, attempts.count { it.isFailure })
        assertEquals(BigDecimal.ZERO, inventory.onHand(id))
        assertEquals(1, db.salesDao().recentSales(10).size)
    }
}
