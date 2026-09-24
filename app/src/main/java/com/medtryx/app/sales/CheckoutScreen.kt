package com.medtryx.app.sales

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medtryx.app.auth.AuthenticatedSession
import com.medtryx.app.catalog.CatalogProductSnapshot
import com.medtryx.app.catalog.BenefitEligibility
import com.medtryx.app.financial.CustomerBenefit
import com.medtryx.app.financial.TaxResult
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

@Composable
fun CheckoutScreen(
    session: AuthenticatedSession,
    products: List<CatalogProductSnapshot>,
    roundingDescription: String?,
    preview: CheckoutPreview?,
    message: String?,
    saleSummary: SaleSummary?,
    onPreview: (CheckoutDraft) -> Unit,
    onFinalize: (CheckoutDraft) -> Unit,
    onClose: () -> Unit,
    onNewSale: () -> Unit,
) {
    var checkoutAttemptId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var cart by remember { mutableStateOf(emptyList<CheckoutLineDraft>()) }
    var benefit by remember { mutableStateOf<CustomerBenefit?>(null) }
    var customerName by remember { mutableStateOf("") }
    var benefitIdType by remember { mutableStateOf<BenefitIdType?>(null) }
    var benefitIdNumber by remember { mutableStateOf("") }
    var physicalIdChecked by remember { mutableStateOf(false) }
    var settlement by remember { mutableStateOf(SettlementMethod.CASH) }
    var qrReference by remember { mutableStateOf("") }
    var customerShowedSuccess by remember { mutableStateOf(false) }
    var confirmationOpen by remember { mutableStateOf(false) }
    if (saleSummary != null) {
        SaleConfirmationScreen(saleSummary, onClose) {
            checkoutAttemptId = UUID.randomUUID().toString()
            cart = emptyList(); benefit = null; customerName = ""; benefitIdType = null; benefitIdNumber = ""
            physicalIdChecked = false; settlement = SettlementMethod.CASH; qrReference = ""; customerShowedSuccess = false
            confirmationOpen = false
            onNewSale()
        }
        return
    }
    val canUseCheckout = roundingDescription != null

    fun draft() = CheckoutDraft(
        idempotencyKey = checkoutAttemptId,
        lines = cart,
        customerBenefit = benefit,
        customerName = customerName.takeIf { benefit != null },
        benefitIdType = benefitIdType.takeIf { benefit != null },
        benefitIdNumber = benefitIdNumber.takeIf { benefit != null },
        physicalIdChecked = physicalIdChecked,
        settlementMethod = settlement,
        qrReference = qrReference.takeIf { settlement == SettlementMethod.QR },
        customerShowedQrSuccess = customerShowedSuccess && settlement == SettlementMethod.QR,
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Checkout", style = MaterialTheme.typography.headlineMedium)
        Text("Cashier: ${session.profile.role}")
        if (!canUseCheckout) Text("A protected user must approve the store rounding rule before checkout.")
        else Text("Store rounding rule: $roundingDescription")
        if (message != null) Text(message, color = MaterialTheme.colorScheme.error)

        Text("Add products", style = MaterialTheme.typography.titleMedium)
        products.filter { it.product.active }.forEach { item ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${item.product.name} · ${item.product.sku}")
                    Text("${money(item.sellingCentavos)} / ${item.product.unit} · ${item.taxClass} · ${item.benefitEligibility}")
                }
                Button(onClick = {
                    val existing = cart.indexOfFirst { it.productId == item.product.id && !it.qualifiedForBenefit }
                    cart = if (existing >= 0) cart.mapIndexed { index, line -> if (index == existing) line.copy(quantity = line.quantity + BigDecimal.ONE) else line }
                    else cart + CheckoutLineDraft(item.product.id, BigDecimal.ONE)
                }) { Text("Add") }
            }
        }

        if (cart.isNotEmpty()) {
            Text("Cart", style = MaterialTheme.typography.titleMedium)
            cart.forEachIndexed { index, line ->
                val product = products.firstOrNull { it.product.id == line.productId }
                if (product != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${product.product.name} · ${line.quantity.stripTrailingZeros().toPlainString()} ${product.product.unit}")
                            Text("${product.taxClass} · eligible: ${product.benefitEligibility}")
                        }
                        if (benefit != null && product.benefitEligibility == BenefitEligibility.SC_PWD_20) {
                            Checkbox(checked = line.qualifiedForBenefit, onCheckedChange = { selected ->
                                cart = cart.mapIndexed { row, value -> if (row == index) value.copy(qualifiedForBenefit = selected) else value }
                            })
                            Text("Apply benefit")
                        }
                        TextButton(onClick = {
                            if (line.quantity > BigDecimal.ONE) cart = cart.mapIndexed { row, value -> if (row == index) value.copy(quantity = value.quantity - BigDecimal.ONE) else value }
                            else cart = cart.filterIndexed { row, _ -> row != index }
                        }) { Text("−") }
                        TextButton(onClick = { cart = cart.mapIndexed { row, value -> if (row == index) value.copy(quantity = value.quantity + BigDecimal.ONE) else value } }) { Text("+") }
                    }
                }
            }
        }

        Text("Customer benefit")
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = benefit == null, onClick = {
                benefit = null; customerName = ""; benefitIdType = null; benefitIdNumber = ""; physicalIdChecked = false
                cart = cart.map { it.copy(qualifiedForBenefit = false) }
            })
            Text("No discount")
            RadioButton(selected = benefit == CustomerBenefit.SENIOR_CITIZEN, onClick = {
                benefit = CustomerBenefit.SENIOR_CITIZEN; benefitIdType = BenefitIdType.SENIOR_CITIZEN_ID
                benefitIdNumber = ""; physicalIdChecked = false
                cart = cart.map { it.copy(qualifiedForBenefit = false) }
            })
            Text("Senior citizen")
            RadioButton(selected = benefit == CustomerBenefit.PERSON_WITH_DISABILITY, onClick = {
                benefit = CustomerBenefit.PERSON_WITH_DISABILITY; benefitIdType = BenefitIdType.PWD_ID
                benefitIdNumber = ""; physicalIdChecked = false
                cart = cart.map { it.copy(qualifiedForBenefit = false) }
            })
            Text("PWD")
        }
        if (benefit != null) {
            OutlinedTextField(customerName, { customerName = it.take(200) }, label = { Text("Customer name") }, modifier = Modifier.fillMaxWidth())
            Text("ID type: $benefitIdType")
            OutlinedTextField(benefitIdNumber, { benefitIdNumber = it.take(100) }, label = { Text("Benefit ID number") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(physicalIdChecked, { physicalIdChecked = it })
                Text("I checked the physical ID")
            }
            Text("ID images and prescription details are not collected.")
        }

        Text("Settlement declaration")
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = settlement == SettlementMethod.CASH, onClick = { settlement = SettlementMethod.CASH })
            Text("Cash")
            RadioButton(selected = settlement == SettlementMethod.QR, onClick = { settlement = SettlementMethod.QR })
            Text("QR")
        }
        if (settlement == SettlementMethod.QR) {
            Text("Payment is declared by the cashier and is not verified by Medtryx.")
            OutlinedTextField(qrReference, { qrReference = it.take(128) }, label = { Text("Optional QR reference") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(customerShowedSuccess, { customerShowedSuccess = it })
                Text("Customer showed success screen (unverified)")
            }
        }

        Button(enabled = canUseCheckout && cart.isNotEmpty(), onClick = { onPreview(draft()) }) { Text("Calculate sale") }
        preview?.takeIf { it.sourceDraft == draft() }?.let { calculation ->
            Text("Tax and discount breakdown", style = MaterialTheme.typography.titleMedium)
            calculation.lines.forEach { line ->
                Text("${line.productName}: ${line.calculation.taxResult}; due ${money(line.calculation.amounts.amountDue.centavos)}")
                Text("  VAT ${money(line.calculation.amounts.regularVat.centavos)} · statutory discount ${money(line.calculation.amounts.statutoryDiscount.centavos)} · promotion ${money(line.calculation.amounts.promotionalDiscount.centavos)}")
            }
            Text("Amount due: ${money(calculation.totalDue.centavos)}", style = MaterialTheme.typography.titleLarge)
            Button(enabled = canUseCheckout, onClick = { confirmationOpen = true }) { Text("Confirm sale") }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onClose) { Text("Cancel checkout") }
    }

    if (confirmationOpen) {
        AlertDialog(
            onDismissRequest = { confirmationOpen = false },
            title = { Text(if (benefit != null) "SC/PWD DISCOUNT WILL BE APPLIED. CONFIRM?" else "Confirm sale?") },
            text = { Text("Medtryx records an internal sales record and does not issue an official invoice or verify payment.") },
            confirmButton = {
                TextButton(onClick = { confirmationOpen = false; onFinalize(draft()) }) { Text("Confirm") }
            },
            dismissButton = { TextButton(onClick = { confirmationOpen = false }) { Text("Review") } },
        )
    }
}

@Composable
private fun SaleConfirmationScreen(summary: SaleSummary, onClose: () -> Unit, onNewSale: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(summary.documentLabel, style = MaterialTheme.typography.headlineSmall)
        Text("Medtryx Transaction ID: ${summary.humanTransactionId}")
        Text("Cashier: ${summary.cashierDisplayName} · ${summary.businessDateManila}")
        Text("Settlement declaration: ${summary.settlementMethod}${if (summary.settlementMethod == SettlementMethod.QR) " — unverified by Medtryx" else ""}")
        summary.maskedBenefitIdNumber?.let { Text("Benefit ID: $it") }
        summary.lines.forEach { line ->
            Text("${line.productName} · ${line.quantity.stripTrailingZeros().toPlainString()} ${line.unit} · ${line.calculation.taxResult}")
            Text("  Gross ${money(line.calculation.amounts.gross.centavos)} · VAT ${money(line.calculation.amounts.regularVat.centavos)} · due ${money(line.calculation.amounts.amountDue.centavos)}")
        }
        Text("Amount due: ${money(summary.amountDue.centavos)}", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onNewSale) { Text("New sale") }
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
    }
}

@Composable
fun RoundingApprovalScreen(
    currentRule: String?,
    message: String?,
    onApprove: (RoundingMode, String) -> Unit,
    onClose: () -> Unit,
) {
    var mode by remember { mutableStateOf(RoundingMode.HALF_UP) }
    var reason by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(RoundingMode.values().indexOf(mode)) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Approve store rounding rule", style = MaterialTheme.typography.headlineSmall)
        Text("Current rule: ${currentRule ?: "not configured"}. The rule is recorded with approver and time and applies to future sales.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                selection = (selection + 1) % RoundingMode.values().size
                mode = RoundingMode.values()[selection]
            }) { Text("Rounding mode: ${mode.name} · tap to change") }
        }
        OutlinedTextField(reason, { reason = it.take(300) }, label = { Text("Approval reason") }, modifier = Modifier.fillMaxWidth())
        if (message != null) Text(message, color = MaterialTheme.colorScheme.error)
        Button(enabled = reason.isNotBlank(), onClick = { onApprove(mode, reason.trim()) }) { Text("Approve this rule") }
        TextButton(onClick = onClose) { Text("Back") }
    }
}

private fun money(centavos: Long): String = "₱${BigDecimal.valueOf(centavos, 2).setScale(2).toPlainString()}"
