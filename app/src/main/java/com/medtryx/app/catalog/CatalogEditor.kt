package com.medtryx.app.catalog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medtryx.app.auth.AuthenticatedSession
import java.math.BigDecimal
import java.time.LocalDate

@Composable
fun CatalogEditorScreen(session: AuthenticatedSession, products: List<ProductEntity>, message: String?, onSave: (ProductDraft, String) -> Unit, onDeactivate: (String, String) -> Unit, onBack: () -> Unit) {
    var sku by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }; var generic by remember { mutableStateOf("") }; var brand by remember { mutableStateOf("") }; var strength by remember { mutableStateOf("") }; var dosageForm by remember { mutableStateOf("") }; var unit by remember { mutableStateOf("") }; var packSize by remember { mutableStateOf("") }; var price by remember { mutableStateOf("") }; var cost by remember { mutableStateOf("") }; var taxClass by remember { mutableStateOf("VATABLE") }; var taxSource by remember { mutableStateOf("") }; var taxFrom by remember { mutableStateOf(LocalDate.now().toString()) }; var taxTo by remember { mutableStateOf("") }; var benefit by remember { mutableStateOf("NONE") }; var prescription by remember { mutableStateOf("OTC") }; var reorder by remember { mutableStateOf("0") }; var requiresLotExpiry by remember { mutableStateOf(false) }; var barcodes by remember { mutableStateOf("") }; var openingLots by remember { mutableStateOf("") }; var reason by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Product catalog", style = MaterialTheme.typography.headlineSmall)
        Text("All tax, benefit, unit, and lot fields are explicit per SKU. Use one opening-lot line as: lot | YYYY-MM-DD | quantity | supplier (multiple lines allowed).")
        CatalogField("SKU *", sku) { sku = it }; CatalogField("Product name *", name) { name = it }; CatalogField("Generic name", generic) { generic = it }; CatalogField("Brand", brand) { brand = it }; CatalogField("Strength", strength) { strength = it }; CatalogField("Dosage form", dosageForm) { dosageForm = it }
        CatalogField("Inventory / sales unit *", unit) { unit = it }; CatalogField("Pack conversion", packSize) { packSize = it }; CatalogField("VAT-inclusive selling price *", price) { price = it }; CatalogField("Unit cost", cost) { cost = it }
        EnumField("Tax class *", taxClass, TaxClass.entries.map { it.name }) { taxClass = it }; CatalogField("Tax authority/source *", taxSource) { taxSource = it }; CatalogField("Tax valid from *", taxFrom) { taxFrom = it }; CatalogField("Tax valid to", taxTo) { taxTo = it }
        EnumField("Benefit eligibility *", benefit, BenefitEligibility.entries.map { it.name }) { benefit = it }; EnumField("Prescription class *", prescription, PrescriptionClass.entries.map { it.name }) { prescription = it }; CatalogField("Reorder level *", reorder) { reorder = it }
        Row { Checkbox(requiresLotExpiry, { requiresLotExpiry = it }); Text("Medicine: require lot/batch and expiry") }
        CatalogField("Barcodes (separate with |)", barcodes) { barcodes = it }; CatalogField("Opening lots", openingLots, minLines = 3) { openingLots = it }; CatalogField("Reason for audit *", reason) { reason = it }
        if (message != null) Text(message)
        Button(onClick = {
            runCatching {
                ProductDraft(sku, name, generic.ifBlank { null }, brand.ifBlank { null }, strength.ifBlank { null }, dosageForm.ifBlank { null }, unit, packSize.ifBlank { null }?.let(::BigDecimal), BigDecimal(price), cost.ifBlank { null }?.let(::BigDecimal), TaxClass.valueOf(taxClass), taxSource, LocalDate.parse(taxFrom), taxTo.ifBlank { null }?.let(LocalDate::parse), BenefitEligibility.valueOf(benefit), PrescriptionClass.valueOf(prescription), BigDecimal(reorder), requiresLotExpiry, barcodes.split("|").map(String::trim).filter(String::isNotBlank).toSet(), parseLots(openingLots))
            }.onSuccess { onSave(it, reason) }
        }, enabled = sku.isNotBlank() && name.isNotBlank() && unit.isNotBlank() && price.isNotBlank() && taxSource.isNotBlank() && reason.isNotBlank()) { Text("Save product and opening stock") }
        HorizontalDivider(); Text("Catalog products", style = MaterialTheme.typography.titleLarge)
        products.forEach { product -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${product.sku} — ${product.name} (${if (product.active) "active" else "inactive"})"); if (product.active) TextButton(onClick = { onDeactivate(product.id, reason) }, enabled = reason.isNotBlank()) { Text("Inactivate") } } }
        OutlinedButton(onClick = onBack) { Text("Back") }
    }
}

@Composable private fun CatalogField(label: String, value: String, minLines: Int = 1, onValueChange: (String) -> Unit) = OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth(), label = { Text(label) }, minLines = minLines)
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun EnumField(label: String, value: String, options: List<String>, onChange: (String) -> Unit) { var expanded by remember { mutableStateOf(false) }; ExposedDropdownMenuBox(expanded, { expanded = !expanded }) { OutlinedTextField(value, {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text(label) }); ExposedDropdownMenu(expanded, { expanded = false }) { options.forEach { option -> DropdownMenuItem({ Text(option) }, { onChange(option); expanded = false }) } } } }
private fun parseLots(raw: String): List<OpeningLotDraft> = raw.lines().filter(String::isNotBlank).mapIndexed { index, line -> val columns = line.split("|").map(String::trim); require(columns.size in 3..4) { "Opening lot line ${index + 1} must have lot, expiry, quantity, and optional supplier." }; OpeningLotDraft(columns[0], columns[1].ifBlank { null }?.let(LocalDate::parse), BigDecimal(columns[2]), columns.getOrNull(3)?.ifBlank { null }) }
