package com.medtryx.app.shifts

import androidx.room.withTransaction
import com.medtryx.app.auth.AuthenticationService
import com.medtryx.app.auth.MedtryxDatabase
import com.medtryx.app.auth.Permission
import com.medtryx.app.auth.PermissionPolicy
import com.medtryx.app.auth.ProtectedActionAuthorizer
import com.medtryx.app.auth.requirePermission
import java.time.Instant
import java.util.UUID

/** F08 use cases. All amounts at this boundary are integer centavos. */
class ShiftService(
    private val database: MedtryxDatabase,
    private val authentication: AuthenticationService,
    private val authorizer: ProtectedActionAuthorizer,
    private val clock: () -> Instant = Instant::now,
) {
    suspend fun currentShift(sessionId: String): ShiftDashboard {
        val active = requireNotNull(authentication.requireActiveSession(sessionId)) { "Session is no longer active." }
        val session = requireNotNull(database.authDao().session(sessionId)) { "Session is no longer active." }
        val own = database.shiftDao().activeShift(STORE_ID, session.deviceId, active.userId)
        if (own == null && !PermissionPolicy.allows(active.profile, Permission.SHIFT_VIEW)) {
            val last = database.shiftDao().recentForCashierDevice(active.userId, session.deviceId, 1).firstOrNull()
            return last?.let { dashboardFor(it) } ?: emptyDashboard()
        }
        return own?.let { dashboard(sessionId, it.id) } ?: emptyDashboard()
    }

    suspend fun recentShifts(sessionId: String, limit: Int = 20): List<CashierShiftEntity> {
        require(limit in 1..100)
        val active = requireNotNull(authentication.requireActiveSession(sessionId)) { "Session is no longer active." }
        if (PermissionPolicy.allows(active.profile, Permission.SHIFT_VIEW)) {
            return database.shiftDao().recentStoreShifts(STORE_ID, limit)
        }
        val session = requireNotNull(database.authDao().session(sessionId))
        return database.shiftDao().recentForCashierDevice(active.userId, session.deviceId, limit)
    }

    suspend fun openShift(sessionId: String, openingFloatCentavos: Long): ShiftDashboard {
        require(openingFloatCentavos >= 0) { "Opening cash float cannot be negative." }
        val actor = authorizer.require(sessionId, Permission.SHIFT_OPEN_CLOSE_OWN, "SHIFT_OPEN", null, "Cashier opened shift")
        val id = UUID.randomUUID().toString()
        val at = clock().toEpochMilli()
        database.withTransaction {
            val active = requireNotNull(authentication.requireActiveSession(sessionId)) { "Session is no longer active." }
            check(active.userId == actor.userId)
            active.profile.requirePermission(Permission.SHIFT_OPEN_CLOSE_OWN)
            val session = requireNotNull(database.authDao().session(sessionId)) { "Session is no longer active." }
            check(database.shiftDao().activeShift(STORE_ID, session.deviceId, active.userId) == null) {
                "This cashier already has an open shift on this device."
            }
            val user = requireNotNull(database.authDao().findUserById(active.userId)) { "Cashier account does not exist." }
            val shift = CashierShiftEntity(
                id = id, storeId = STORE_ID, deviceId = session.deviceId, cashierUserId = active.userId,
                cashierDisplayName = user.displayName, status = ShiftStatus.OPEN, openedAtUtcMillis = at,
                openingFloatCentavos = openingFloatCentavos,
            )
            database.shiftDao().insertShift(shift)
            database.shiftDao().insertClaim(ActiveShiftClaimEntity(STORE_ID, session.deviceId, active.userId, id))
            authorizer.recordApplicationAudit(sessionId, "SHIFT_OPENED", id, "Cashier opened shift", null,
                "openingFloatCentavos=$openingFloatCentavos;deviceId=${session.deviceId};storeId=$STORE_ID")
        }
        return dashboard(sessionId, id)
    }

    suspend fun recordCashInOut(sessionId: String, amountCentavos: Long, type: ShiftCashMovementType, reason: String): ShiftDashboard {
        require(type == ShiftCashMovementType.CASH_IN || type == ShiftCashMovementType.CASH_OUT) { "Only cash-in and cash-out movements can be entered here." }
        require(amountCentavos > 0) { "Cash movement amount must be greater than zero." }
        requireReason(reason)
        val actor = authorizer.require(sessionId, Permission.SHIFT_OPEN_CLOSE_OWN, "SHIFT_${type.name}", null, reason)
        val session = requireNotNull(database.authDao().session(sessionId)) { "Session is no longer active." }
        val shiftId = database.withTransaction {
            val active = requireNotNull(authentication.requireActiveSession(sessionId)) { "Session is no longer active." }
            check(active.userId == actor.userId)
            val shift = requireNotNull(database.shiftDao().openShift(STORE_ID, session.deviceId, active.userId)) {
                "Open a cashier shift before recording cash movement."
            }
            val movement = ShiftCashMovementEntity(
                id = UUID.randomUUID().toString(), shiftId = shift.id, type = type,
                amountCentavos = amountCentavos, occurredAtUtcMillis = clock().toEpochMilli(),
                recordedByUserId = active.userId, reason = reason.trim(),
            )
            database.shiftDao().insertMovement(movement)
            authorizer.recordApplicationAudit(sessionId, "${type.name}_RECORDED", movement.id, reason,
                null, "shiftId=${shift.id};amountCentavos=$amountCentavos")
            shift.id
        }
        return dashboard(sessionId, shiftId)
    }

    suspend fun requestClose(
        sessionId: String,
        actualCashCentavos: Long,
        denominationCounts: Map<Long, Long>?,
        varianceNote: String?,
    ): ShiftCloseResult {
        require(actualCashCentavos >= 0) { "Actual cash count cannot be negative." }
        val encodedDenominations = denominationCounts?.let { DenominationCountCodec.encode(it, actualCashCentavos) }
        val actor = authorizer.require(sessionId, Permission.SHIFT_OPEN_CLOSE_OWN, "SHIFT_CLOSE_REQUEST", null, "Cashier requested shift close")
        val session = requireNotNull(database.authDao().session(sessionId)) { "Session is no longer active." }
        lateinit var result: ShiftCloseResult
        database.withTransaction {
            val active = requireNotNull(authentication.requireActiveSession(sessionId)) { "Session is no longer active." }
            check(active.userId == actor.userId)
            val shift = requireNotNull(database.shiftDao().openShift(STORE_ID, session.deviceId, active.userId)) {
                "There is no open shift for this cashier on this device."
            }
            val totals = calculateTotals(shift)
            val expected = Math.subtractExact(
                Math.addExact(Math.addExact(shift.openingFloatCentavos, totals.cashSales), totals.cashIn),
                Math.addExact(totals.cashOut, totals.cashRefunds),
            )
            val variance = Math.subtractExact(actualCashCentavos, expected)
            val note = varianceNote?.trim()?.takeIf(String::isNotEmpty)
            require(variance == 0L || !note.isNullOrBlank()) { "Enter a note for a non-zero cash variance." }
            val policy = database.shiftDao().latestVariancePolicy()
            val requiresApproval = if (policy == null) variance != 0L else outsideThreshold(variance, policy.thresholdCentavos)
            val requestedAt = clock().toEpochMilli()
            val status = if (requiresApproval) ShiftStatus.CLOSING_PENDING else ShiftStatus.CLOSED
            val closedAt = if (requiresApproval) null else requestedAt
            check(database.shiftDao().recordClose(
                shiftId = shift.id, status = status, requestedAt = requestedAt, actual = actualCashCentavos,
                expected = expected, variance = variance, denominations = encodedDenominations, note = note,
                closedAt = closedAt,
            ) == 1) { "Shift close state changed; reload and try again." }
            if (!requiresApproval) database.shiftDao().releaseClaim(shift.id)
            authorizer.recordApplicationAudit(
                sessionId, if (requiresApproval) "SHIFT_CLOSE_APPROVAL_REQUESTED" else "SHIFT_CLOSED",
                shift.id, note ?: "Cashier closed shift", "status=${shift.status};expectedCashCentavos=$expected",
                "status=$status;actualCashCentavos=$actualCashCentavos;varianceCentavos=$variance;denominations=${encodedDenominations ?: "none"}",
            )
            val closed = requireNotNull(database.shiftDao().shiftById(shift.id))
            result = ShiftCloseResult(dashboardFor(closed), requiresApproval)
        }
        return result
    }

    suspend fun pendingClosures(sessionId: String): List<CashierShiftEntity> {
        val active = requireNotNull(authentication.requireActiveSession(sessionId)) { "Session is no longer active." }
        active.profile.requirePermission(Permission.SHIFT_VARIANCE_APPROVE)
        authorizer.recordApplicationAudit(sessionId, "SHIFT_VARIANCE_QUEUE_VIEW", STORE_ID, "Pending shift variance review", null, null)
        return database.shiftDao().pendingClosures()
    }

    suspend fun latestVarianceThreshold(): Long? = database.shiftDao().latestVariancePolicy()?.thresholdCentavos

    suspend fun decideVariance(sessionId: String, shiftId: String, approve: Boolean, reason: String, freshSecret: CharArray): ShiftDashboard {
        requireReason(reason)
        val actor = authorizer.require(sessionId, Permission.SHIFT_VARIANCE_APPROVE,
            if (approve) "SHIFT_VARIANCE_APPROVED" else "SHIFT_VARIANCE_REJECTED", shiftId, reason, freshSecret)
        database.withTransaction {
            val active = requireNotNull(authentication.requireActiveSession(sessionId)) { "Session is no longer active." }
            check(active.userId == actor.userId)
            val shift = requireNotNull(database.shiftDao().shiftById(shiftId)) { "Shift does not exist." }
            require(shift.status == ShiftStatus.CLOSING_PENDING) { "Shift is not waiting for variance approval." }
            require(shift.cashierUserId != active.userId) { "A cashier cannot approve their own shift variance." }
            val at = clock().toEpochMilli()
            if (approve) {
                check(database.shiftDao().approveClose(shiftId, at, active.userId, at) == 1)
                database.shiftDao().releaseClaim(shiftId)
            } else {
                check(database.shiftDao().recordClose(
                    shiftId, ShiftStatus.OPEN, null, null, null, null, null, null, null,
                ) == 1)
            }
            authorizer.recordApplicationAudit(sessionId, if (approve) "SHIFT_VARIANCE_APPROVED" else "SHIFT_VARIANCE_REJECTED",
                shiftId, reason, "varianceCentavos=${shift.varianceCentavos};cashier=${shift.cashierUserId}",
                if (approve) "status=CLOSED;supervisor=${active.userId}" else "status=OPEN;close_request_returned_to_cashier=true")
        }
        return dashboardFor(requireNotNull(database.shiftDao().shiftById(shiftId)))
    }

    suspend fun configureVarianceThreshold(sessionId: String, thresholdCentavos: Long, reason: String, freshSecret: CharArray): ShiftVariancePolicyEntity {
        require(thresholdCentavos >= 0) { "Variance threshold cannot be negative." }
        requireReason(reason)
        val actor = authorizer.require(sessionId, Permission.SHIFT_VARIANCE_CONFIGURE, "SHIFT_VARIANCE_POLICY_CHANGE", STORE_ID, reason, freshSecret)
        return database.withTransaction {
            val active = requireNotNull(authentication.requireActiveSession(sessionId)) { "Session is no longer active." }
            check(active.userId == actor.userId)
            active.profile.requirePermission(Permission.SHIFT_VARIANCE_CONFIGURE)
            val previous = database.shiftDao().latestVariancePolicy()
            val next = ShiftVariancePolicyEntity(
                id = UUID.randomUUID().toString(), version = ((previous?.version?.toIntOrNull() ?: 0) + 1).toString(),
                thresholdCentavos = thresholdCentavos, approvedByUserId = active.userId,
                approvedAtUtcMillis = clock().toEpochMilli(), reason = reason.trim(),
            )
            database.shiftDao().insertPolicy(next)
            authorizer.recordApplicationAudit(sessionId, "SHIFT_VARIANCE_POLICY_CHANGED", next.id, reason,
                previous?.let { "version=${it.version};thresholdCentavos=${it.thresholdCentavos}" },
                "version=${next.version};thresholdCentavos=${next.thresholdCentavos}")
            next
        }
    }

    /** F09 calls this within its authorized reversal workflow; QR reversals are never cash refunds. */
    suspend fun recordCashRefund(sessionId: String, shiftId: String, amountCentavos: Long, reversalReference: String, reason: String, freshSecret: CharArray) {
        require(amountCentavos > 0) { "Cash refund amount must be greater than zero." }
        require(reversalReference.isNotBlank()) { "A linked reversal reference is required." }
        requireReason(reason)
        val actor = authorizer.require(sessionId, Permission.FINALIZED_SALE_VOID_OR_REVERSE, "SHIFT_CASH_REFUND", reversalReference, reason, freshSecret)
        database.withTransaction {
            val active = requireNotNull(authentication.requireActiveSession(sessionId)) { "Session is no longer active." }
            check(active.userId == actor.userId)
            val session = requireNotNull(database.authDao().session(sessionId))
            val shift = requireNotNull(database.shiftDao().shiftById(shiftId)) { "Refund shift does not exist." }
            require(shift.storeId == STORE_ID && shift.deviceId == session.deviceId && shift.status == ShiftStatus.OPEN) {
                "Cash refund must be linked to an open shift on this device."
            }
            require(database.shiftDao().openShift(STORE_ID, session.deviceId, shift.cashierUserId)?.id == shiftId) {
                "Cash refund must use the cashier's active shift."
            }
            val movement = ShiftCashMovementEntity(UUID.randomUUID().toString(), shiftId, ShiftCashMovementType.CASH_REFUND,
                amountCentavos, clock().toEpochMilli(), active.userId, reason.trim(), reversalReference)
            database.shiftDao().insertMovement(movement)
            authorizer.recordApplicationAudit(sessionId, "CASH_REFUND_RECORDED", movement.id, reason,
                null, "shiftId=$shiftId;amountCentavos=$amountCentavos;reversal=$reversalReference")
        }
    }

    suspend fun addAdjustment(sessionId: String, shiftId: String, amountCentavos: Long, reason: String, freshSecret: CharArray): ShiftAdjustmentEntity {
        require(amountCentavos != 0L) { "Adjustment must change the recorded amount." }
        requireReason(reason)
        val actor = authorizer.require(sessionId, Permission.SHIFT_ADJUST, "SHIFT_ADJUSTMENT", shiftId, reason, freshSecret)
        return database.withTransaction {
            val active = requireNotNull(authentication.requireActiveSession(sessionId)) { "Session is no longer active." }
            check(active.userId == actor.userId)
            val shift = requireNotNull(database.shiftDao().shiftById(shiftId)) { "Shift does not exist." }
            require(shift.status == ShiftStatus.CLOSED) { "Adjustments may be recorded only against a closed shift." }
            val adjustment = ShiftAdjustmentEntity(UUID.randomUUID().toString(), shiftId, amountCentavos,
                clock().toEpochMilli(), active.userId, reason.trim())
            database.shiftDao().insertAdjustment(adjustment)
            authorizer.recordApplicationAudit(sessionId, "SHIFT_ADJUSTMENT_RECORDED", adjustment.id, reason,
                null, "shiftId=$shiftId;amountCentavos=$amountCentavos")
            adjustment
        }
    }

    suspend fun dashboard(sessionId: String, shiftId: String): ShiftDashboard {
        val active = requireNotNull(authentication.requireActiveSession(sessionId)) { "Session is no longer active." }
        val shift = requireNotNull(database.shiftDao().shiftById(shiftId)) { "Shift does not exist." }
        val session = requireNotNull(database.authDao().session(sessionId))
        if (shift.cashierUserId != active.userId || shift.deviceId != session.deviceId) {
            active.profile.requirePermission(Permission.SHIFT_VIEW)
        }
        return dashboardFor(shift)
    }

    private suspend fun dashboardFor(shift: CashierShiftEntity): ShiftDashboard {
        val totals = calculateTotals(shift)
        val expected = Math.subtractExact(
            Math.addExact(Math.addExact(shift.openingFloatCentavos, totals.cashSales), totals.cashIn),
            Math.addExact(totals.cashOut, totals.cashRefunds),
        )
        return ShiftDashboard(
            shift = shift, grossSalesCentavos = totals.grossSales, netSalesCentavos = totals.netSales,
            cashSalesCentavos = totals.cashSales, qrSalesCentavos = totals.qrSales,
            cashRefundsCentavos = totals.cashRefunds, cashInCentavos = totals.cashIn,
            cashOutCentavos = totals.cashOut, expectedCashCentavos = expected,
            movements = database.shiftDao().movements(shift.id), adjustments = database.shiftDao().adjustments(shift.id),
        )
    }

    private suspend fun calculateTotals(shift: CashierShiftEntity): Totals {
        val sales = database.shiftDao().salesForShift(shift.id)
        val movements = database.shiftDao().movements(shift.id)
        fun sum(values: Iterable<Long>) = values.fold(0L, Math::addExact)
        return Totals(
            grossSales = sum(sales.map { it.grossCentavos }), netSales = sum(sales.map { it.amountDueCentavos }),
            cashSales = sum(sales.filter { it.settlementMethod.name == "CASH" }.map { it.amountDueCentavos }),
            qrSales = sum(sales.filter { it.settlementMethod.name == "QR" }.map { it.amountDueCentavos }),
            cashRefunds = sum(movements.filter { it.type == ShiftCashMovementType.CASH_REFUND }.map { it.amountCentavos }),
            cashIn = sum(movements.filter { it.type == ShiftCashMovementType.CASH_IN }.map { it.amountCentavos }),
            cashOut = sum(movements.filter { it.type == ShiftCashMovementType.CASH_OUT }.map { it.amountCentavos }),
        )
    }

    private fun outsideThreshold(variance: Long, threshold: Long): Boolean = variance > threshold || variance < -threshold

    private fun emptyDashboard() = ShiftDashboard(null, 0, 0, 0, 0, 0, 0, 0, null, emptyList(), emptyList())
    private data class Totals(
        val grossSales: Long, val netSales: Long, val cashSales: Long, val qrSales: Long,
        val cashRefunds: Long, val cashIn: Long, val cashOut: Long,
    )

    private fun requireReason(reason: String) = require(reason.isNotBlank() && reason.length <= 300 && reason.none(Char::isISOControl)) {
        "A reason of 1-300 printable characters is required."
    }

    companion object {
        const val STORE_ID = "SINGLE_STORE"
    }
}

data class ShiftCloseResult(val dashboard: ShiftDashboard, val approvalPending: Boolean)

object DenominationCountCodec {
    fun encode(counts: Map<Long, Long>, expectedTotalCentavos: Long): String {
        require(counts.isNotEmpty()) { "Enter at least one denomination count or leave the denomination list blank." }
        require(counts.all { (value, count) -> value > 0 && count >= 0 }) { "Denominations must be positive and counts cannot be negative." }
        val total = counts.entries.fold(0L) { sum, (value, count) -> Math.addExact(sum, Math.multiplyExact(value, count)) }
        require(total == expectedTotalCentavos) { "Denomination totals must equal the actual cash count." }
        return "v1|" + counts.toSortedMap().entries.joinToString(",") { (value, count) -> "$value:$count" }
    }

    fun decode(encoded: String?): Map<Long, Long> {
        if (encoded.isNullOrBlank()) return emptyMap()
        require(encoded.startsWith("v1|")) { "Unsupported denomination snapshot version." }
        val body = encoded.removePrefix("v1|")
        if (body.isBlank()) return emptyMap()
        return body.split(',').associate { pair ->
            val pieces = pair.split(':')
            require(pieces.size == 2)
            pieces[0].toLong() to pieces[1].toLong()
        }
    }
}
