package com.medtryx.app.catalog

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
fun CatalogEditorScreen(
    session: AuthenticatedSession,
    products: List<CatalogProductSnapshot>,
    message: String?,
    importPreview: CatalogImportPreview?,
    importCsv: String?,
    onSave: (String?, CatalogProductSnapshot?, ProductDraft, LocalDate, String, () -> Unit) -> Unit,
    onDeactivate: (String, String) -> Unit,
    onChooseCsv: (Uri) -> Unit,
    onCommitImport: (String, CatalogImportPreview, String, Set<Int>?) -> Unit,
    onBack: () -> Unit,
) {
    var editingProductId by remember { mutableStateOf<String?>(null) }
    var expectedSnapshot by remember { mutableStateOf<CatalogProductSnapshot?>(null) }
    var sku by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }; var generic by remember { mutableStateOf("") }; var brand by remember { mutableStateOf("") }; var strength by remember { mutableStateOf("") }; var dosageForm by remember { mutableStateOf("") }; var unit by remember { mutableStateOf("") }; var packSize by remember { mutableStateOf("") }; var price by remember { mutableStateOf("") }; var cost by remember { mutableStateOf("") }; var taxClass by remember { mutableStateOf("VATABLE") }; var taxSource by remember { mutableStateOf("") }; var taxFrom by remember { mutableStateOf(LocalDate.now().toString()) }; var versionFrom by remember { mutableStateOf(LocalDate.now().toString()) }; var taxTo by remember { mutableStateOf("") }; var benefit by remember { mutableStateOf("NONE") }; var prescription by remember { mutableStateOf("OTC") }; var reorder by remember { mutableStateOf("0") }; var requiresLotExpiry by remember { mutableStateOf(false) }; var barcodes by remember { mutableStateOf("") }; var openingLots by remember { mutableStateOf("") }; var reason by remember { mutableStateOf("") }
    var showImportPreview by remember { mutableStateOf(false) }
    var selectedRows by remember { mutableStateOf(emptySet<Int>()) }
    var formError by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }

    val openForEdit: (CatalogProductSnapshot) -> Unit = { row ->
        editingProductId = row.product.id; expectedSnapshot = row; formError = null
        sku = row.product.sku; name = row.product.name; generic = row.product.genericName.orEmpty(); brand = row.product.brand.orEmpty(); strength = row.product.strength.orEmpty(); dosageForm = row.product.dosageForm.orEmpty()
        unit = row.product.unit; packSize = row.product.packSize.orEmpty(); price = BigDecimal.valueOf(row.sellingCentavos, 2).toPlainString(); cost = row.costCentavos?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty()
        val today = LocalDate.now(); val nextVersion = maxOf(LocalDate.parse(row.priceValidFrom), LocalDate.parse(row.taxValidFrom), LocalDate.parse(row.benefitValidFrom)).plusDays(1)
        taxClass = row.taxClass.name; taxSource = row.taxSource; taxFrom = row.taxValidFrom; versionFrom = maxOf(today, nextVersion).toString(); taxTo = row.taxValidTo.orEmpty(); benefit = row.benefitEligibility.name; prescription = row.prescriptionClass.name
        reorder = row.product.reorderLevel; requiresLotExpiry = row.product.requiresLotExpiry; barcodes = row.barcodes.joinToString("|"); openingLots = ""; reason = ""
    }
    val clearForm = {
        editingProductId = null; expectedSnapshot = null; sku = ""; name = ""; generic = ""; brand = ""; strength = ""; dosageForm = ""; unit = ""; packSize = ""; price = ""; cost = ""
        taxClass = TaxClass.VATABLE.name; taxSource = ""; taxFrom = LocalDate.now().toString(); versionFrom = LocalDate.now().toString(); taxTo = ""; benefit = BenefitEligibility.NONE.name; prescription = PrescriptionClass.OTC.name; reorder = "0"
        requiresLotExpiry = false; barcodes = ""; openingLots = ""; reason = ""; formError = null
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Product catalog", style = MaterialTheme.typography.headlineSmall)
        Text("Create or edit an individual SKU. SKU identity and inventory unit are fixed after creation. Opening lot lines use: lot | YYYY-MM-DD | quantity | optional supplier.")
        CatalogField("SKU *", sku, readOnly = editingProductId != null) { sku = it }
        CatalogField("Product name *", name) { name = it }; CatalogField("Generic name", generic) { generic = it }; CatalogField("Brand", brand) { brand = it }; CatalogField("Strength", strength) { strength = it }; CatalogField("Dosage form", dosageForm) { dosageForm = it }
        CatalogField("Inventory / sales unit *", unit, readOnly = editingProductId != null) { unit = it }; CatalogField("Pack conversion", packSize) { packSize = it }; CatalogField("VAT-inclusive selling price *", price) { price = it }; CatalogField("Unit cost", cost) { cost = it }
        EnumField("Tax class *", taxClass, TaxClass.entries.map { it.name }) { taxClass = it }; CatalogField("Tax authority/source *", taxSource) { taxSource = it }
        if (editingProductId == null) CatalogField("Tax valid from *", taxFrom) { taxFrom = it } else CatalogField("Change effective from *", versionFrom) { versionFrom = it }
        CatalogField("Tax valid to", taxTo) { taxTo = it }
        EnumField("Benefit eligibility *", benefit, BenefitEligibility.entries.map { it.name }) { benefit = it }; EnumField("Prescription class *", prescription, PrescriptionClass.entries.map { it.name }) { prescription = it }; CatalogField("Reorder level *", reorder) { reorder = it }
        Row { Checkbox(requiresLotExpiry, { requiresLotExpiry = it }); Text("Medicine: require lot/batch and expiry") }
        CatalogField("Barcodes (separate with |)", barcodes) { barcodes = it }
        if (editingProductId == null) CatalogField("Opening lots", openingLots, minLines = 3) { openingLots = it }
        CatalogField("Reason for audit *", reason) { reason = it }
        if (message != null) Text(message)
        if (formError != null) Text(formError!!, color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            runCatching {
                val barcodeValues = barcodes.split("|").map(String::trim).filter(String::isNotBlank)
                require(barcodeValues.size == barcodeValues.toSet().size) { "Remove duplicate barcode values before saving." }
                val draft = ProductDraft(sku, name, generic.ifBlank { null }, brand.ifBlank { null }, strength.ifBlank { null }, dosageForm.ifBlank { null }, unit, packSize.ifBlank { null }?.let(::BigDecimal), BigDecimal(price), cost.ifBlank { null }?.let(::BigDecimal), TaxClass.valueOf(taxClass), taxSource, LocalDate.parse(taxFrom), taxTo.ifBlank { null }?.let(LocalDate::parse), BenefitEligibility.valueOf(benefit), PrescriptionClass.valueOf(prescription), BigDecimal(reorder), requiresLotExpiry, barcodeValues.toSet(), if (editingProductId == null) parseLots(openingLots) else emptyList())
                ProductValidator.validate(draft).also { errors -> require(errors.isEmpty()) { errors.joinToString { "${it.field}: ${it.message}" } } }
                Triple(draft, LocalDate.parse(if (editingProductId == null) taxFrom else versionFrom), editingProductId)
            }.onSuccess { (draft, effectiveFrom, id) -> formError = null; onSave(id, expectedSnapshot, draft, effectiveFrom, reason) { clearForm() } }
                .onFailure { formError = it.message ?: "Catalog values are invalid." }
        }, enabled = sku.isNotBlank() && name.isNotBlank() && unit.isNotBlank() && price.isNotBlank() && taxSource.isNotBlank() && reason.isNotBlank()) {
            Text(if (editingProductId == null) "Create product and opening stock" else "Save catalog changes")
        }
        if (editingProductId != null) OutlinedButton(onClick = clearForm) { Text("Cancel edit") }

        HorizontalDivider(); Text("Starting catalog CSV", style = MaterialTheme.typography.titleLarge)
        Text("UTF-8 CSV supports an optional opening_lots cell. Put one lot per line inside a quoted cell: lot | YYYY-MM-DD | quantity | optional supplier.")
        val csvPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onChooseCsv) }
        OutlinedButton(onClick = { csvPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain")) }) { Text("Choose catalog CSV") }
        if (importPreview != null) {
            Text("Preview: ${importPreview.validRows.size} valid rows, ${importPreview.rejectedRows.size} rows with errors")
            Button(onClick = { showImportPreview = true; selectedRows = emptySet() }) { Text("Review import rows") }
        }

        HorizontalDivider(); Text("Catalog products", style = MaterialTheme.typography.titleLarge)
        CatalogField("Search SKU, name, generic, brand, or barcode", search) { search = it }
        val query = search.trim().lowercase()
        val matchingProducts = products.filter { row -> query.isEmpty() || listOfNotNull(row.product.sku, row.product.name, row.product.genericName, row.product.brand).any { it.lowercase().contains(query) } || row.barcodes.any { it.lowercase().contains(query) } }
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
          items(matchingProducts, key = { it.product.id }) { row ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${row.product.sku} — ${row.product.name} (${if (row.product.active) "active" else "inactive"})")
                Text("Price ₱${BigDecimal.valueOf(row.sellingCentavos, 2).toPlainString()} • ${row.taxClass} • ${row.benefitEligibility} • ${row.prescriptionClass}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (row.product.active) TextButton(onClick = { openForEdit(row) }) { Text("Edit") }
                    if (row.product.active) TextButton(onClick = { onDeactivate(row.product.id, reason) }, enabled = reason.isNotBlank()) { Text("Inactivate") }
                }
                HorizontalDivider()
            }
          }
        }
        OutlinedButton(onClick = onBack) { Text("Back") }
    }

    if (showImportPreview && importPreview != null) {
        AlertDialog(
            onDismissRequest = { showImportPreview = false },
            title = { Text("Review catalog import") },
            text = {
                Column(Modifier.heightIn(max = 480.dp)) {
                    Text("${importPreview.validRows.size} valid rows; ${importPreview.rejectedRows.size} rows with errors. Select valid rows only if you are committing a reviewed subset.")
                    LazyColumn(Modifier.weight(1f)) {
                        items(importPreview.rows, key = { it.rowNumber }) { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Checkbox(checked = row.rowNumber in selectedRows, onCheckedChange = { checked -> selectedRows = if (checked) selectedRows + row.rowNumber else selectedRows - row.rowNumber }, enabled = row.errors.isEmpty())
                                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                    Text("Row ${row.rowNumber}: ${row.draft?.sku ?: "Invalid row"}${row.draft?.let { " — ${it.name}" }.orEmpty()}")
                                    row.draft?.let { Text("${it.unit} • ₱${it.sellingPrice.toPlainString()} • ${it.taxClass} • ${it.benefitEligibility} • ${it.prescriptionClass} • ${it.openingLots.size} opening lot(s)", style = MaterialTheme.typography.bodySmall) }
                                    row.errors.forEach { Text("${it.field}: ${it.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                if (importPreview.rejectedRows.isEmpty()) TextButton(onClick = { importCsv?.let { onCommitImport(it, importPreview, reason, null) }; showImportPreview = false }, enabled = importCsv != null && reason.isNotBlank()) { Text("Commit all rows") }
                else TextButton(onClick = { importCsv?.let { onCommitImport(it, importPreview, reason, selectedRows) }; showImportPreview = false }, enabled = selectedRows.isNotEmpty() && reason.isNotBlank() && importCsv != null) { Text("Commit reviewed selection") }
            },
            dismissButton = { TextButton(onClick = { showImportPreview = false }) { Text("Cancel") } },
        )
    }
}

@Composable private fun CatalogField(label: String, value: String, minLines: Int = 1, readOnly: Boolean = false, onValueChange: (String) -> Unit) = OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth(), label = { Text(label) }, minLines = minLines, readOnly = readOnly)
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun EnumField(label: String, value: String, options: List<String>, onChange: (String) -> Unit) { var expanded by remember { mutableStateOf(false) }; ExposedDropdownMenuBox(expanded, { expanded = !expanded }) { OutlinedTextField(value, {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text(label) }); ExposedDropdownMenu(expanded, { expanded = false }) { options.forEach { option -> DropdownMenuItem({ Text(option) }, { onChange(option); expanded = false }) } } } }
private fun parseLots(raw: String): List<OpeningLotDraft> = raw.lines().filter(String::isNotBlank).mapIndexed { index, line -> val columns = line.split("|").map(String::trim); require(columns.size in 3..4) { "Opening lot line ${index + 1} must have lot, expiry, quantity, and optional supplier." }; OpeningLotDraft(columns[0], columns[1].ifBlank { null }?.let(LocalDate::parse), BigDecimal(columns[2]), columns.getOrNull(3)?.ifBlank { null }) }
