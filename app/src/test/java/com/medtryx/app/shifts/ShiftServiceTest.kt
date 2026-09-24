package com.medtryx.app.shifts

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.medtryx.app.auth.*
import com.medtryx.app.catalog.*
import com.medtryx.app.financial.CustomerBenefit
import com.medtryx.app.sales.*
import com.medtryx.app.security.SensitiveDataProtector
import kotlinx.coroutines.test.runTest
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
class ShiftServiceTest {
    private lateinit var db: MedtryxDatabase
    private lateinit var auth: AuthenticationService
    private lateinit var shifts: ShiftService
    private lateinit var catalog: CatalogService
    private lateinit var inventory: InventoryService
    private lateinit var owner: AuthenticatedSession
    private lateinit var sales: SaleFinalizationService
    private var now = Instant.parse("2026-06-01T12:00:00Z")
    private val date = LocalDate.parse("2026-06-01")

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MedtryxDatabase::class.java)
            .addCallback(MedtryxDatabase.IMMUTABILITY_CALLBACK).allowMainThreadQueries().build()
        auth = AuthenticationService(db, PasswordHasher(), "shift-test-device", clock = { now.toEpochMilli() })
        shifts = ShiftService(db, auth, ProtectedActionAuthorizer(auth), clock = { now })
        catalog = CatalogService(db, ProtectedActionAuthorizer(auth), auth, clock = { now.toEpochMilli() })
        inventory = InventoryService(db, ProtectedActionAuthorizer(auth), clock = { now.toEpochMilli() }, today = { date })
    }

    @After fun tearDown() = db.close()

    private suspend fun bootstrap() {
        owner = auth.bootstrapOwner("owner", "Owner", "1234".toCharArray())
        sales = SaleFinalizationService(db, auth, ProtectedActionAuthorizer(auth), inventory,
            object : SensitiveDataProtector { override fun protect(plainText: String) = "encrypted" },
            clock = { now }, today = { date })
        sales.approveRoundingRule(owner.sessionId, RoundingMode.HALF_UP, "test rule")
    }

    private suspend fun product(sku: String): String = catalog.createProduct(owner.sessionId, ProductDraft(
        sku = sku, name = "Test $sku", unit = "tablet", sellingPrice = BigDecimal("112.00"),
        taxClass = TaxClass.VATABLE, taxSource = "approved", taxValidFrom = date.minusDays(1),
        benefitEligibility = BenefitEligibility.NONE, prescriptionClass = PrescriptionClass.OTC,
        reorderLevel = BigDecimal.ZERO, requiresLotExpiry = true,
        openingLots = listOf(OpeningLotDraft("LOT-$sku", date.plusYears(1), BigDecimal("10"))),
    ), "test product")

    @Test fun duplicate_open_is_blocked_and_cash_and_qr_reconcile_separately() = runTest {
        bootstrap()
        val open = shifts.openShift(owner.sessionId, 5_000)
        assertEquals(5_000L, open.expectedCashCentavos)
        try { shifts.openShift(owner.sessionId, 0); fail("duplicate open must fail") } catch (_: IllegalStateException) { }

        val product = product("SHIFT-SALES")
        fun draft(settlement: SettlementMethod) = CheckoutDraft(UUID.randomUUID().toString(), listOf(CheckoutLineDraft(product, BigDecimal.ONE)), settlementMethod = settlement)
        sales.finalize(owner.sessionId, draft(SettlementMethod.CASH))
        sales.finalize(owner.sessionId, draft(SettlementMethod.QR))
        shifts.recordCashInOut(owner.sessionId, 1_000, ShiftCashMovementType.CASH_IN, "Float top-up")
        shifts.recordCashInOut(owner.sessionId, 500, ShiftCashMovementType.CASH_OUT, "Petty cash")
        shifts.recordCashRefund(owner.sessionId, open.shift!!.id, 700, "reversal-1", "Cash reversal", "1234".toCharArray())

        val totals = shifts.currentShift(owner.sessionId)
        assertEquals(22_400L, totals.grossSalesCentavos)
        assertEquals(11_200L, totals.cashSalesCentavos)
        assertEquals(11_200L, totals.qrSalesCentavos)
        assertEquals(700L, totals.cashRefundsCentavos)
        assertEquals(16_000L, totals.expectedCashCentavos)
        assertTrue(db.salesDao().recentSales(10).all { it.shiftId == open.shift.id })
    }

    @Test fun nonzero_variance_needs_note_and_separate_supervisor_approval_and_close_is_immutable() = runTest {
        bootstrap()
        val cashierId = auth.createUser(owner.sessionId, "cashier", "Cashier", Role.CASHIER, "5678".toCharArray(), reason = "shift test")
        val cashier = (auth.login("cashier", "5678".toCharArray()) as LoginResult.Success).session
        val opened = shifts.openShift(cashier.sessionId, 10_000).shift!!
        try { shifts.requestClose(cashier.sessionId, 10_100, null, null); fail("variance requires note") } catch (_: IllegalArgumentException) { }
        val requested = shifts.requestClose(cashier.sessionId, 10_100, mapOf(10_000L to 1, 100L to 1), "Count is ten pesos over")
        assertTrue(requested.approvalPending)
        assertEquals(ShiftStatus.CLOSING_PENDING, requested.dashboard.shift!!.status)
        val supervisorId = auth.createUser(owner.sessionId, "supervisor", "Supervisor", Role.SUPERVISOR, "9876".toCharArray(),
            explicitPermissions = setOf(Permission.SHIFT_VARIANCE_APPROVE), reason = "variance reviewer")
        val supervisor = (auth.login("supervisor", "9876".toCharArray()) as LoginResult.Success).session
        assertEquals(1, shifts.pendingClosures(supervisor.sessionId).size)
        val closed = shifts.decideVariance(supervisor.sessionId, opened.id, true, "Count checked against drawer", "9876".toCharArray())
        assertEquals(ShiftStatus.CLOSED, closed.shift!!.status)
        assertEquals(100L, closed.shift.varianceCentavos)
        assertEquals(supervisorId, closed.shift.supervisorUserId)
        try { db.openHelper.writableDatabase.execSQL("UPDATE cashier_shifts SET actualCashCentavos = 0 WHERE id = ?", arrayOf(opened.id)); fail("closed shift immutable") }
        catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertEquals(ShiftStatus.CLOSED, db.shiftDao().shiftById(opened.id)!!.status)
    }

    @Test fun zero_variance_closes_immediately_and_denominations_must_reconcile() = runTest {
        bootstrap()
        shifts.openShift(owner.sessionId, 2_000)
        try { shifts.requestClose(owner.sessionId, 2_000, mapOf(1_000L to 1), null); fail("denomination mismatch") } catch (_: IllegalArgumentException) { }
        val closed = shifts.requestClose(owner.sessionId, 2_000, mapOf(1_000L to 2), null)
        assertFalse(closed.approvalPending)
        assertEquals(ShiftStatus.CLOSED, closed.dashboard.shift!!.status)
        assertEquals(mapOf(1_000L to 2L), DenominationCountCodec.decode(closed.dashboard.shift.denominationCounts))
        val adjustment = shifts.addAdjustment(owner.sessionId, closed.dashboard.shift.id, 125, "Late cash slip", "1234".toCharArray())
        assertEquals(125L, adjustment.amountCentavos)
        assertEquals(1, shifts.dashboard(owner.sessionId, closed.dashboard.shift.id).adjustments.size)
    }

    @Test fun configured_variance_threshold_closes_at_boundary_and_escalates_above_it() = runTest {
        bootstrap()
        shifts.configureVarianceThreshold(owner.sessionId, 100, "Require review above one peso", "1234".toCharArray())
        shifts.openShift(owner.sessionId, 10_000)
        val atBoundary = shifts.requestClose(owner.sessionId, 10_100, null, "One peso variance")
        assertFalse(atBoundary.approvalPending)
        assertEquals(100L, atBoundary.dashboard.shift!!.varianceCentavos)

        shifts.openShift(owner.sessionId, 10_000)
        val aboveBoundary = shifts.requestClose(owner.sessionId, 10_101, null, "Variance exceeds configured limit")
        assertTrue(aboveBoundary.approvalPending)
        assertEquals(101L, aboveBoundary.dashboard.shift!!.varianceCentavos)
    }

    @Test fun failed_close_audit_rolls_back_the_shift_transition_and_keeps_its_active_claim() = runTest {
        bootstrap()
        val opened = shifts.openShift(owner.sessionId, 2_000).shift!!
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_shift_close_audit BEFORE INSERT ON audit_events WHEN NEW.action = 'SHIFT_CLOSED' BEGIN SELECT RAISE(ABORT, 'injected close interruption'); END")
        try {
            shifts.requestClose(owner.sessionId, 2_000, null, null)
            fail("injected audit failure must abort close")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertEquals(ShiftStatus.OPEN, db.shiftDao().shiftById(opened.id)!!.status)
        assertNotNull(db.shiftDao().openShift(ShiftService.STORE_ID, "shift-test-device", owner.userId))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_shift_close_audit")
    }

    @Test fun cashier_cannot_configure_threshold_and_manager_cannot_close_cashiers_shift() = runTest {
        bootstrap()
        val cashierId = auth.createUser(owner.sessionId, "cashier", "Cashier", Role.CASHIER, "5678".toCharArray(), reason = "shift test")
        val cashier = (auth.login("cashier", "5678".toCharArray()) as LoginResult.Success).session
        val opened = shifts.openShift(cashier.sessionId, 0).shift!!
        try { shifts.configureVarianceThreshold(cashier.sessionId, 500, "test", "5678".toCharArray()); fail("cashier cannot set threshold") } catch (_: AccessDeniedException) { }
        val other = auth.createUser(owner.sessionId, "other", "Other cashier", Role.CASHIER, "7654".toCharArray(), reason = "shift test")
        val otherSession = (auth.login("other", "7654".toCharArray()) as LoginResult.Success).session
        try { shifts.requestClose(otherSession.sessionId, 0, null, null); fail("wrong cashier cannot close") } catch (_: IllegalArgumentException) { }
        assertEquals(cashierId, db.shiftDao().shiftById(opened.id)!!.cashierUserId)
        assertNotEquals(cashierId, other)
    }
}
