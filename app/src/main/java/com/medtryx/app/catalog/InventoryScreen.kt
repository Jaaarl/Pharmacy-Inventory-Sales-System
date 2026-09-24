package com.medtryx.app.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import java.math.BigDecimal
import java.time.LocalDate

private sealed interface InventoryDialog {
    data class Receive(val product: InventoryProductStatus) : InventoryDialog
    data class Adjust(val product: InventoryProductStatus) : InventoryDialog
    data class Dispose(val product: InventoryProductStatus, val lot: LotBalance) : InventoryDialog
}

@Composable
fun InventoryScreen(
    session: AuthenticatedSession,
    products: List<InventoryProductStatus>,
    alerts: List<InventoryAlert>,
    message: String?,
    nearExpiryDays: String,
    onNearExpiryDaysChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onReceive: (String, StockReceiptDraft, String) -> Unit,
    onAdjust: (String, String, String?, BigDecimal, String) -> Unit,
    onDispose: (String, String, BigDecimal, String) -> Unit,
    onBack: () -> Unit,
) {
    var dialog by remember { mutableStateOf<InventoryDialog?>(null) }
    Column(Modifier.padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Inventory", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text("On-hand is calculated from the append-only movement ledger. Stock changes require an authorized role and a reason.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = nearExpiryDays,
            onValueChange = onNearExpiryDaysChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Near-expiry warning window (days)") },
            supportingText = { Text("Enter the store's approved warning window. Leave blank to show low-stock and expired alerts only.") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRefresh) { Text("Refresh stock") }
        }
        if (message != null) Text(message, color = MaterialTheme.colorScheme.primary)
        if (alerts.isNotEmpty()) {
            Text("Stock warnings (${alerts.size})", style = MaterialTheme.typography.titleMedium)
            alerts.forEach { alert ->
                val label = when (alert.type) {
                    InventoryAlertType.LOW_STOCK -> "LOW STOCK: ${alert.quantity} ${alert.unit} on hand (reorder level reached)"
                    InventoryAlertType.EXPIRED -> "EXPIRED: ${alert.lotNumber} · ${alert.expiryDate} · ${alert.quantity} ${alert.unit}"
                    InventoryAlertType.NEAR_EXPIRY -> "NEAR EXPIRY: ${alert.lotNumber} · ${alert.expiryDate} · ${alert.quantity} ${alert.unit}"
                }
                Text("${alert.sku} · ${alert.productName}: $label", color = if (alert.type == InventoryAlertType.EXPIRED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(products, key = { it.productId }) { product ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${product.name} · ${product.sku}", style = MaterialTheme.typography.titleMedium)
                        Text("On hand: ${product.onHand.toPlainString()} ${product.unit} · Reorder at: ${product.reorderLevel.toPlainString()} ${product.unit}")
                        product.packSize?.let { Text("Configured receipt pack: $it ${product.unit} per pack") }
                        if (!product.isActive) Text("INACTIVE SKU", color = MaterialTheme.colorScheme.error)
                        product.lots.forEach { lot ->
                            val expired = lot.expiryDate?.let { runCatching { LocalDate.parse(it).isBefore(LocalDate.now(java.time.ZoneId.of("Asia/Manila"))) }.getOrDefault(false) } == true
                            Text("Lot ${lot.lotNumber}: ${lot.onHand.toPlainString()} ${product.unit}${lot.expiryDate?.let { " · expires $it" }.orEmpty()}", color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            if (expired && lot.onHand > BigDecimal.ZERO && PermissionForInventory.canAdjust(session)) {
                                TextButton(onClick = { dialog = InventoryDialog.Dispose(product, lot) }) { Text("Dispose expired stock") }
                            }
                        }
                        if (product.recentMovements.isNotEmpty()) {
                            Text("Recent movements", style = MaterialTheme.typography.titleSmall)
                            product.recentMovements.forEach { movement ->
                                Text("${java.time.Instant.ofEpochMilli(movement.occurredAtUtcMillis)} · ${movement.type} ${movement.quantity} ${movement.unit} · ${movement.reason}", style = MaterialTheme.typography.bodySmall)
                                Text("By ${movement.actorUserId}${movement.sourceReference?.let { " · Ref $it" }.orEmpty()}${movement.costCentavos?.let { " · Cost ₱${BigDecimal(it).divide(BigDecimal(100)).toPlainString()}" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (PermissionForInventory.canAdjust(session) && product.isActive) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { dialog = InventoryDialog.Receive(product) }) { Text("Receive") }
                                OutlinedButton(onClick = { dialog = InventoryDialog.Adjust(product) }) { Text("Adjust") }
                            }
                        }
                    }
                }
            }
        }
    }
    dialog?.let { current ->
        when (current) {
            is InventoryDialog.Receive -> StockActionDialog("Receive stock · ${current.product.sku}", current.product, null, onDismiss = { dialog = null }) { quantity, lot, expiry, cost, _, reason, inPacks ->
                val costCentavos = cost.takeIf(String::isNotBlank)?.let { (BigDecimal(it) * BigDecimal(100)).longValueExact() }
                onReceive(session.sessionId, StockReceiptDraft(current.product.productId, BigDecimal(quantity), lot.ifBlank { null }, expiry.ifBlank { null }, costCentavos, receivedInPacks = inPacks), reason); dialog = null
            }
            is InventoryDialog.Adjust -> StockActionDialog("Stock adjustment · ${current.product.sku}", current.product, null, onDismiss = { dialog = null }) { quantity, _, _, _, lotId, reason, _ ->
                onAdjust(session.sessionId, current.product.productId, lotId, BigDecimal(quantity), reason); dialog = null
            }
            is InventoryDialog.Dispose -> StockActionDialog("Dispose expired · ${current.product.sku}", current.product, current.lot, onDismiss = { dialog = null }) { quantity, _, _, _, _, reason, _ ->
                onDispose(session.sessionId, current.lot.lotId, BigDecimal(quantity), reason); dialog = null
            }
        }
    }
}

@Composable
private fun StockActionDialog(title: String, product: InventoryProductStatus, fixedLot: LotBalance?, onDismiss: () -> Unit, onSubmit: (String, String, String, String, String?, String, Boolean) -> Unit) {
    var quantity by remember { mutableStateOf("") }
    var lotNumber by remember { mutableStateOf(fixedLot?.lotNumber.orEmpty()) }
    var expiry by remember { mutableStateOf(fixedLot?.expiryDate.orEmpty()) }
    var cost by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var selectedLotId by remember { mutableStateOf(fixedLot?.lotId) }
    var inPacks by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var validation by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(quantity, { quantity = it; validation = null }, label = { Text(if (fixedLot == null && title.startsWith("Stock adjustment")) "Signed quantity change (e.g. -2 or 3)" else "Quantity") })
                if (title.startsWith("Receive") && product.packSize != null) {
                    Row {
                        Checkbox(checked = inPacks, onCheckedChange = { inPacks = it })
                        Text("Enter pack count (${product.packSize} ${product.unit} per pack)")
                    }
                }
                when {
                    fixedLot != null -> Text("Lot ${fixedLot.lotNumber} · expiry ${fixedLot.expiryDate} · available ${fixedLot.onHand} ${product.unit}")
                    title.startsWith("Receive") -> {
                        OutlinedTextField(lotNumber, { lotNumber = it }, label = { Text(if (product.requiresLotExpiry) "Lot / batch (required)" else "Lot / batch (optional)") })
                        OutlinedTextField(expiry, { expiry = it }, label = { Text("Expiry date (YYYY-MM-DD)") })
                        OutlinedTextField(cost, { cost = it }, label = { Text("Unit cost in pesos (optional)") })
                    }
                    product.lots.isNotEmpty() -> {
                        OutlinedButton(onClick = { menuOpen = true }) { Text(product.lots.firstOrNull { it.lotId == selectedLotId }?.let { "Lot ${it.lotNumber}" } ?: "Choose lot (optional)") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) { product.lots.forEach { lot -> DropdownMenuItem(text = { Text("${lot.lotNumber} · ${lot.onHand} ${product.unit} · ${lot.expiryDate ?: "no expiry"}") }, onClick = { selectedLotId = lot.lotId; menuOpen = false }) } }
                    }
                }
                OutlinedTextField(reason, { reason = it }, label = { Text("Reason (required)") }, minLines = 2)
                validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = {
            runCatching {
                val parsed = BigDecimal(quantity)
                require(parsed.signum() != 0 && parsed.scale() <= 4) { "Enter a non-zero quantity with at most four decimals." }
                require(reason.isNotBlank()) { "A reason is required." }
                if (title.startsWith("Receive")) {
                    require(parsed > BigDecimal.ZERO) { "Received quantity must be positive." }
                    if (product.requiresLotExpiry) require(lotNumber.isNotBlank() && expiry.isNotBlank()) { "Lot and expiry are both required for medicine stock." }
                    else if (expiry.isNotBlank()) require(lotNumber.isNotBlank()) { "An expiry date requires a lot/batch number." }
                    if (expiry.isNotBlank()) LocalDate.parse(expiry)
                    if (cost.isNotBlank()) require(BigDecimal(cost).signum() >= 0 && BigDecimal(cost).scale() <= 2) { "Cost must be zero or greater and use at most two decimals." }
                }
                if (title.startsWith("Stock adjustment") && product.requiresLotExpiry) require(selectedLotId != null) { "Choose the medicine lot being adjusted." }
                if (fixedLot != null) require(parsed > BigDecimal.ZERO) { "Disposal quantity must be positive." }
                onSubmit(quantity, lotNumber, expiry, cost, selectedLotId, reason, inPacks)
            }.onFailure { validation = it.message ?: "Check the entered values." }
        }) { Text("Save movement") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private object PermissionForInventory {
    fun canAdjust(session: AuthenticatedSession) = com.medtryx.app.auth.PermissionPolicy.allows(session.profile, com.medtryx.app.auth.Permission.INVENTORY_ADJUST)
}
