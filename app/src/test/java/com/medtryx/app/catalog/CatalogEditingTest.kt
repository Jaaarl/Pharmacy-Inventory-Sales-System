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
import java.math.BigDecimal
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class CatalogEditingTest {
    private lateinit var db: MedtryxDatabase
    private lateinit var auth: AuthenticationService
    private lateinit var catalog: CatalogService

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MedtryxDatabase::class.java).allowMainThreadQueries().build()
        auth = AuthenticationService(db, PasswordHasher(), "catalog-edit-test")
        catalog = CatalogService(db, ProtectedActionAuthorizer(auth), auth)
    }

    @After fun tearDown() = db.close()

    private fun draft(sku: String = "ITEM-1") = ProductDraft(
        sku = sku, name = "Item", genericName = "Generic", unit = "tablet", packSize = BigDecimal("10"),
        sellingPrice = BigDecimal("10.00"), unitCost = BigDecimal("4.00"), taxClass = TaxClass.VATABLE,
        taxSource = "approved source", taxValidFrom = LocalDate.parse("2026-01-01"),
        benefitEligibility = BenefitEligibility.SC_PWD_20, prescriptionClass = PrescriptionClass.OTC,
        reorderLevel = BigDecimal("2"), requiresLotExpiry = true, barcodes = setOf("111"),
        openingLots = listOf(OpeningLotDraft("L1", LocalDate.parse("2027-01-01"), BigDecimal("5"))),
    )

    @Test fun protected_edits_update_fields_versions_and_old_new_audit_together() = runTest {
        val owner = auth.bootstrapOwner("owner", "Owner", "1234".toCharArray())
        val productId = catalog.createProduct(owner.sessionId, draft(), "initial approval")
        val originalSnapshot = catalog.productSnapshot(productId)!!
        catalog.updateProduct(
            owner.sessionId, productId, originalSnapshot,
            draft().copy(name = "Updated item", sellingPrice = BigDecimal("12.50"), unitCost = BigDecimal("5.25"), taxClass = TaxClass.VAT_EXEMPT, taxSource = "updated approved source", benefitEligibility = BenefitEligibility.NONE, prescriptionClass = PrescriptionClass.PRESCRIPTION),
            LocalDate.parse("2026-02-01"), "approved catalog update",
        )

        val product = db.catalogDao().productById(productId)!!
        assertEquals("Updated item", product.name)
        assertEquals(PrescriptionClass.PRESCRIPTION, product.prescriptionClass)
        assertEquals(2, db.catalogDao().priceVersionCount(productId))
        assertEquals("2026-01-31", db.catalogDao().priceVersions(productId).first().effectiveTo)
        assertEquals(12_50L, db.catalogDao().latestPrice(productId)!!.sellingCentavos)
        assertEquals(TaxClass.VAT_EXEMPT, db.catalogDao().latestTax(productId)!!.taxClass)
        assertEquals(BenefitEligibility.NONE, db.catalogDao().latestBenefit(productId)!!.eligibility)
        val audit = db.authDao().auditEvents()
        assertTrue(audit.any { it.action == "PRODUCT_DETAILS_CHANGED" && it.oldValue?.contains("name=Item") == true && it.newValue?.contains("name=Updated item") == true })
        assertTrue(audit.any { it.action == "PRODUCT_PRICE_CHANGED" && it.oldValue?.contains("sellingCentavos=1000") == true && it.newValue?.contains("sellingCentavos=1250") == true && it.reason == "approved catalog update" })
    }

    @Test fun cashier_cannot_change_catalog_even_when_calling_the_service_directly() = runTest {
        val owner = auth.bootstrapOwner("owner", "Owner", "1234".toCharArray())
        val productId = catalog.createProduct(owner.sessionId, draft(), "initial approval")
        val snapshot = catalog.productSnapshot(productId)!!
        auth.createUser(owner.sessionId, "cashier", "Cashier", Role.CASHIER, "5678".toCharArray(), reason = "hire")
        val cashier = (auth.login("cashier", "5678".toCharArray()) as LoginResult.Success).session
        val failure = runCatching { catalog.updateProduct(cashier.sessionId, productId, snapshot, draft().copy(sellingPrice = BigDecimal("11.00")), LocalDate.parse("2026-02-01"), "unauthorized") }.exceptionOrNull()
        assertTrue(failure is AccessDeniedException)
        assertEquals(1, db.catalogDao().priceVersionCount(productId))
        assertEquals(1_000L, db.catalogDao().latestPrice(productId)!!.sellingCentavos)
    }

    @Test fun edit_form_snapshot_is_rejected_after_another_catalog_change() = runTest {
        val owner = auth.bootstrapOwner("owner", "Owner", "1234".toCharArray())
        val productId = catalog.createProduct(owner.sessionId, draft(), "initial approval")
        val staleSnapshot = catalog.productSnapshot(productId)!!
        catalog.updateProduct(owner.sessionId, productId, staleSnapshot, draft().copy(name = "Current name"), LocalDate.parse("2026-02-01"), "first edit")

        val failure = runCatching {
            catalog.updateProduct(owner.sessionId, productId, staleSnapshot, draft().copy(name = "Stale name"), LocalDate.parse("2026-03-01"), "stale edit")
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals("Current name", db.catalogDao().productById(productId)!!.name)
        assertFalse(db.authDao().auditEvents().any { it.action == "PRODUCT_DETAILS_CHANGED" && it.newValue?.contains("Stale name") == true })
    }

    @Test fun failed_barcode_change_rolls_back_other_changes_in_the_same_edit() = runTest {
        val owner = auth.bootstrapOwner("owner", "Owner", "1234".toCharArray())
        val firstId = catalog.createProduct(owner.sessionId, draft("ITEM-1"), "first")
        val secondId = catalog.createProduct(owner.sessionId, draft("ITEM-2").copy(barcodes = setOf("222")), "second")
        val snapshot = catalog.productSnapshot(secondId)!!
        val failure = runCatching { catalog.updateProduct(owner.sessionId, secondId, snapshot, draft("ITEM-2").copy(name = "Should roll back", barcodes = setOf("111")), LocalDate.parse("2026-02-01"), "duplicate barcode") }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("Item", db.catalogDao().productById(secondId)!!.name)
        assertEquals(listOf("222"), db.catalogDao().barcodesForProduct(secondId).map { it.barcode })
        assertEquals(listOf("111"), db.catalogDao().barcodesForProduct(firstId).map { it.barcode })
        assertFalse(db.authDao().auditEvents().any { it.action == "PRODUCT_DETAILS_CHANGED" && it.entityReference == secondId })
    }
}
