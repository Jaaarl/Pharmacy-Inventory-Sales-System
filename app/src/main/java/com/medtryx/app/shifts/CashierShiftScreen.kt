package com.medtryx.app.shifts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medtryx.app.auth.AuthenticatedSession
import com.medtryx.app.auth.Permission
import com.medtryx.app.auth.PermissionPolicy
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun CashierShiftScreen(
    session: AuthenticatedSession,
    dashboard: ShiftDashboard,
    recentShifts: List<CashierShiftEntity>,
    pendingClosures: List<CashierShiftEntity>,
    varianceThresholdCentavos: Long?,
    message: String?,
    onOpen: (Long) -> Unit,
    onCashInOut: (Long, ShiftCashMovementType, String) -> Unit,
    onCloseShift: (Long, Map<Long, Long>?, String?) -> Unit,
    onSelectShift: (String) -> Unit,
    onDecideVariance: (String, Boolean, String, String) -> Unit,
    onSetVarianceThreshold: (Long, String, String) -> Unit,
    onAdjustment: (String, Long, String, String) -> Unit,
    onRefresh: () -> Unit,
    onCloseScreen: () -> Unit,
) {
    var openingFloat by remember { mutableStateOf("") }
    var movementAmount by remember { mutableStateOf("") }
    var movementReason by remember { mutableStateOf("") }
    var closeCashCount by remember { mutableStateOf("") }
    var denominationError by remember { mutableStateOf<String?>(null) }
    var denominationText by remember { mutableStateOf("") }
    var varianceNote by remember { mutableStateOf("") }
    var selectedMovement by remember { mutableStateOf(ShiftCashMovementType.CASH_IN) }
    var threshold by remember(varianceThresholdCentavos) { mutableStateOf(varianceThresholdCentavos?.let(::pesos) ?: "") }
    var thresholdReason by remember { mutableStateOf("") }
    var adjustment by remember { mutableStateOf("") }
    var adjustmentReason by remember { mutableStateOf("") }
    var supervisorReason by remember { mutableStateOf("") }
    var supervisorPin by remember { mutableStateOf("") }
    var thresholdPin by remember { mutableStateOf("") }
    var adjustmentPin by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Cashier shift and turnover", style = MaterialTheme.typography.headlineMedium)
        Text("Cashier: ${session.profile.role}")
        if (message != null) Text(message, color = MaterialTheme.colorScheme.error)

        val shift = dashboard.shift
        if (shift == null || shift.status == ShiftStatus.CLOSED) {
            if (shift?.status == ShiftStatus.CLOSED) {
                Text("Last shift closed. Its recorded totals remain immutable.")
            }
            if (PermissionPolicy.allows(session.profile, Permission.SHIFT_OPEN_CLOSE_OWN)) {
                OutlinedTextField(openingFloat, { openingFloat = it }, label = { Text("Opening cash float (PHP)") })
                Button(enabled = cents(openingFloat) != null, onClick = { cents(openingFloat)?.let(onOpen) }) { Text("Open my shift") }
            }
        } else {
            Text("Shift ${shift.id}")
            Text("Status: ${shift.status} · opened ${shift.openedAtUtcMillis}")
            Text("Opening float: ${php(shift.openingFloatCentavos)}")
            Text("Gross sales: ${php(dashboard.grossSalesCentavos)} · net sales: ${php(dashboard.netSalesCentavos)}")
            Text("Cash sales: ${php(dashboard.cashSalesCentavos)}")
            Text("QR-declared sales (excluded from cash): ${php(dashboard.qrSalesCentavos)}")
            Text("Cash in: ${php(dashboard.cashInCentavos)} · cash out: ${php(dashboard.cashOutCentavos)} · cash refunds: ${php(dashboard.cashRefundsCentavos)}")
            Text("Expected physical cash: ${dashboard.expectedCashCentavos?.let(::php) ?: "pending"}", style = MaterialTheme.typography.titleMedium)
            if (shift.status == ShiftStatus.CLOSING_PENDING) {
                Text("Cash count submitted: ${shift.actualCashCentavos?.let(::php)} · variance: ${shift.varianceCentavos?.let(::php)}")
                Text("Waiting for a different authorized supervisor to approve this variance.")
                shift.varianceNote?.let { Text("Cashier note: $it") }
            }
            if (shift.status == ShiftStatus.OPEN && PermissionPolicy.allows(session.profile, Permission.SHIFT_OPEN_CLOSE_OWN)) {
                Text("Cash movement")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { selectedMovement = ShiftCashMovementType.CASH_IN }) { Text(if (selectedMovement == ShiftCashMovementType.CASH_IN) "✓ Cash in" else "Cash in") }
                    OutlinedButton(onClick = { selectedMovement = ShiftCashMovementType.CASH_OUT }) { Text(if (selectedMovement == ShiftCashMovementType.CASH_OUT) "✓ Cash out" else "Cash out") }
                }
                OutlinedTextField(movementAmount, { movementAmount = it }, label = { Text("Amount (PHP)") })
                OutlinedTextField(movementReason, { movementReason = it.take(300) }, label = { Text("Reason") }, modifier = Modifier.fillMaxWidth())
                Button(enabled = cents(movementAmount)?.let { it > 0 } == true && movementReason.isNotBlank(), onClick = {
                    val value = cents(movementAmount) ?: return@Button
                    onCashInOut(value, selectedMovement, movementReason.trim())
                    movementAmount = ""; movementReason = ""
                }) { Text("Record ${selectedMovement.name.lowercase().replace('_', ' ')}") }

                Text("Close shift")
                OutlinedTextField(closeCashCount, { closeCashCount = it }, label = { Text("Actual physical cash count (PHP)") })
                OutlinedTextField(denominationText, { denominationText = it }, label = { Text("Optional denominations: pesos=count, e.g. 1000=2,500=1") }, modifier = Modifier.fillMaxWidth())
                denominationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(varianceNote, { varianceNote = it.take(300) }, label = { Text("Required note when count differs from expected") }, modifier = Modifier.fillMaxWidth())
                Button(enabled = cents(closeCashCount) != null, onClick = {
                    val actual = cents(closeCashCount) ?: return@Button
                    val counts = runCatching { parseDenominations(denominationText, actual) }.getOrElse {
                        denominationError = it.message ?: "Invalid denomination count."; return@Button
                    }
                    denominationError = null
                    onCloseShift(actual, counts, varianceNote.takeIf(String::isNotBlank)?.trim())
                }) { Text("Submit shift count") }
            }
            if (shift.status == ShiftStatus.CLOSED) {
                Text("Actual cash: ${shift.actualCashCentavos?.let(::php)} · variance: ${shift.varianceCentavos?.let(::php)}")
                shift.varianceNote?.let { Text("Cashier note: $it") }
                shift.supervisorUserId?.let { Text("Supervisor acknowledgement: $it") }
            }
        }

        if (PermissionPolicy.allows(session.profile, Permission.SHIFT_VARIANCE_APPROVE) && pendingClosures.isNotEmpty()) {
            Text("Pending variance approvals", style = MaterialTheme.typography.titleLarge)
            pendingClosures.forEach { pending ->
                Text("${pending.cashierDisplayName} · actual ${pending.actualCashCentavos?.let(::php)} · variance ${pending.varianceCentavos?.let(::php)}")
                Text("Shift ${pending.id} · ${pending.varianceNote.orEmpty()}")
                OutlinedTextField(supervisorReason, { supervisorReason = it.take(300) }, label = { Text("Approval/rejection reason") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(supervisorPin, { supervisorPin = it }, label = { Text("Supervisor PIN (fresh authentication)") }, visualTransformation = PasswordVisualTransformation())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = supervisorReason.isNotBlank() && supervisorPin.isNotBlank() && pending.cashierUserId != session.userId, onClick = {
                        onDecideVariance(pending.id, true, supervisorReason.trim(), supervisorPin); supervisorReason = ""; supervisorPin = ""
                    }) { Text("Approve and close") }
                    OutlinedButton(enabled = supervisorReason.isNotBlank() && supervisorPin.isNotBlank() && pending.cashierUserId != session.userId, onClick = {
                        onDecideVariance(pending.id, false, supervisorReason.trim(), supervisorPin); supervisorReason = ""; supervisorPin = ""
                    }) { Text("Return to cashier") }
                }
            }
        }

        if (PermissionPolicy.allows(session.profile, Permission.SHIFT_VARIANCE_CONFIGURE)) {
            Text("Variance approval threshold", style = MaterialTheme.typography.titleMedium)
            Text(varianceThresholdCentavos?.let { "Configured threshold: ${php(it)}" } ?: "Not configured: every non-zero variance requires a separate supervisor approval.")
            OutlinedTextField(threshold, { threshold = it }, label = { Text("Threshold (PHP)") })
            OutlinedTextField(thresholdReason, { thresholdReason = it.take(300) }, label = { Text("Configuration reason") })
            OutlinedTextField(thresholdPin, { thresholdPin = it }, label = { Text("Approver PIN (fresh authentication)") }, visualTransformation = PasswordVisualTransformation())
            Button(enabled = cents(threshold) != null && thresholdReason.isNotBlank() && thresholdPin.isNotBlank(), onClick = {
                onSetVarianceThreshold(cents(threshold) ?: return@Button, thresholdReason.trim(), thresholdPin)
                thresholdReason = ""; thresholdPin = ""
            }) { Text("Approve threshold") }
        }

        if (PermissionPolicy.allows(session.profile, Permission.SHIFT_VIEW)) {
            Text("Recent shifts", style = MaterialTheme.typography.titleLarge)
            recentShifts.forEach { row ->
                TextButton(onClick = { onSelectShift(row.id) }) {
                    Text("${row.cashierDisplayName} · ${row.status} · ${row.openedAtUtcMillis}")
                }
            }
        }
        if (shift?.status == ShiftStatus.CLOSED && PermissionPolicy.allows(session.profile, Permission.SHIFT_ADJUST)) {
            Text("Post-close adjustment (separate from the immutable counted variance)")
            OutlinedTextField(adjustment, { adjustment = it }, label = { Text("Signed adjustment (PHP)") })
            OutlinedTextField(adjustmentReason, { adjustmentReason = it.take(300) }, label = { Text("Adjustment reason") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(adjustmentPin, { adjustmentPin = it }, label = { Text("Approver PIN (fresh authentication)") }, visualTransformation = PasswordVisualTransformation())
            Button(enabled = cents(adjustment)?.let { it != 0L } == true && adjustmentReason.isNotBlank() && adjustmentPin.isNotBlank(), onClick = {
                onAdjustment(shift.id, cents(adjustment) ?: return@Button, adjustmentReason.trim(), adjustmentPin)
                adjustment = ""; adjustmentReason = ""; adjustmentPin = ""
            }) { Text("Record adjustment") }
            dashboard.adjustments.forEach { Text("Adjustment ${php(it.amountCentavos)} · ${it.reason}") }
        }
        dashboard.movements.forEach { Text("${it.type}: ${php(it.amountCentavos)} · ${it.reason}") }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRefresh) { Text("Refresh") }
            OutlinedButton(onClick = onCloseScreen) { Text("Back") }
        }
    }
}

private fun cents(value: String): Long? = runCatching {
    val decimal = BigDecimal(value.trim())
    require(decimal.scale() <= 2 && decimal.signum() >= 0)
    decimal.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
}.getOrNull()

private fun parseDenominations(source: String, total: Long): Map<Long, Long>? {
    if (source.isBlank()) return null
    val entries = source.split(',').map { token ->
        val (denomination, count) = token.trim().split('=', limit = 2).also { require(it.size == 2) }
        val cents = BigDecimal(denomination.trim()).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
        cents to count.trim().toLong()
    }
    require(entries.map { it.first }.distinct().size == entries.size) { "Enter each denomination only once." }
    val counts = entries.toMap()
    DenominationCountCodec.encode(counts, total)
    return counts
}

private fun pesos(centavos: Long) = BigDecimal.valueOf(centavos, 2).setScale(2).toPlainString()
private fun php(centavos: Long) = "₱${pesos(centavos)}"
