package com.medtryx.app.catalog

import androidx.room.withTransaction
import com.medtryx.app.auth.MedtryxDatabase
import com.medtryx.app.auth.Permission
import com.medtryx.app.auth.ProtectedActionAuthorizer
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class ImportRowPreview(val rowNumber: Int, val draft: ProductDraft?, val errors: List<ValidationError>)
data class CatalogImportPreview(val checksum: String, val rows: List<ImportRowPreview>) {
    val validRows get() = rows.filter { it.errors.isEmpty() && it.draft != null }
    val rejectedRows get() = rows.filter { it.errors.isNotEmpty() }
}

/** Strict UTF-8 CSV catalog importer. Validation never repairs malformed source values. */
class CsvCatalogImporter(
    private val db: MedtryxDatabase,
    private val catalog: CatalogService,
    private val authorizer: ProtectedActionAuthorizer,
    private val clock: () -> Long = System::currentTimeMillis,
    private val afterProductWrite: suspend () -> Unit = {},
) {
    fun preview(csv: String): CatalogImportPreview {
        if (csv.isEmpty() || csv.length > MAX_CSV_CHARS) return invalidFile(csv, if (csv.isEmpty()) "CSV file is empty." else "CSV file exceeds the 10 MiB import limit.")
        val records = try { parse(csv) } catch (error: IllegalArgumentException) { return invalidFile(csv, error.message ?: "Malformed CSV.") }
        if (records.isEmpty()) return invalidFile(csv, "CSV file is empty.")
        if (records.size == 1) return invalidFile(csv, "CSV must contain a header and at least one data row.")
        if (records.size - 1 > MAX_DATA_ROWS) return invalidFile(csv, "CSV exceeds the $MAX_DATA_ROWS-row import limit.")
        val headers = records.first().map { it.trim().lowercase() }
        val required = setOf("sku", "name", "unit", "selling_price", "tax_class", "tax_source", "tax_valid_from", "benefit_eligibility", "prescription_class", "reorder_level", "requires_lot_expiry")
        val missing = required - headers.toSet()
        val duplicatedHeaders = headers.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return addInFileDuplicateErrors(catalog.checksum(csv), records.drop(1).mapIndexed { index, values -> rowPreview(index + 2, headers, values, missing, duplicatedHeaders) })
    }

    /** Adds conflicts with the current catalog to a preview before it is presented for confirmation. */
    suspend fun validateAgainstCatalog(preview: CatalogImportPreview): CatalogImportPreview {
        val dao = db.catalogDao()
        return preview.copy(rows = preview.rows.map { row ->
            val draft = row.draft ?: return@map row
            val errors = row.errors.toMutableList()
            if (dao.productBySku(draft.sku.trim().uppercase()) != null) errors += ValidationError("sku", "SKU already exists in the catalog.")
            draft.barcodes.forEach { barcode -> if (dao.barcode(barcode.trim()) != null) errors += ValidationError("barcodes", "Barcode already exists in the catalog: $barcode") }
            row.copy(errors = errors)
        })
    }

    suspend fun commit(sessionId: String, preview: CatalogImportPreview, reason: String, reviewedSubset: Set<Int>? = null): List<String> {
        val actor = authorizer.require(sessionId, Permission.PRODUCT_MANAGE, "CATALOG_IMPORT", preview.checksum, reason)
        val validated = validateAgainstCatalog(preview)
        val selected = if (reviewedSubset == null) {
            require(validated.rows.all { it.errors.isEmpty() }) { "Import has validation errors. Review a valid subset explicitly or correct the file." }
            validated.validRows
        } else {
            require(reviewedSubset.isNotEmpty()) { "Reviewed subset is required." }
            val requested = validated.rows.filter { it.rowNumber in reviewedSubset }
            require(requested.map { it.rowNumber }.toSet() == reviewedSubset) { "A selected row is not in this import preview." }
            require(requested.all { it.errors.isEmpty() && it.draft != null }) { "Reviewed subset includes invalid rows." }
            requested
        }
        require(selected.isNotEmpty()) { "No valid rows selected." }
        return db.withTransaction {
            val ids = selected.map { catalog.persist(it.draft!!, actor.userId, reason).also { afterProductWrite() } }
            val manifest = ImportManifestEntity(UUID.randomUUID().toString(), validated.checksum, actor.userId, clock(), ids.size, validated.rows.size - ids.size, reviewedSubset != null)
            db.catalogDao().insertManifest(manifest)
            db.catalogDao().insertResults(validated.rows.map { row ->
                val selectedIndex = selected.indexOfFirst { it.rowNumber == row.rowNumber }
                ImportRowResultEntity(UUID.randomUUID().toString(), manifest.id, row.rowNumber, selectedIndex >= 0, row.errors.joinToString("; ") { "${it.field}: ${it.message}" }.ifBlank { null }, ids.getOrNull(selectedIndex))
            })
            catalog.recordImportAudit(sessionId, manifest, ids, reason)
            ids
        }
    }

    private fun invalidFile(csv: String, message: String) = CatalogImportPreview(catalog.checksum(csv), listOf(ImportRowPreview(1, null, listOf(ValidationError("csv", message)))))

    private fun rowPreview(rowNumber: Int, headers: List<String>, values: List<String>, missing: Set<String>, duplicatedHeaders: Set<String>): ImportRowPreview {
        val errors = mutableListOf<ValidationError>()
        if (missing.isNotEmpty()) errors += ValidationError("header", "Missing: ${missing.sorted().joinToString()}")
        if (duplicatedHeaders.isNotEmpty()) errors += ValidationError("header", "Duplicate: ${duplicatedHeaders.sorted().joinToString()}")
        if (values.size > headers.size) errors += ValidationError("row", "Row has more columns than the header.")
        fun value(key: String) = headers.indexOf(key).takeIf { it >= 0 }?.let { values.getOrElse(it) { "" } } ?: ""
        fun requiredValue(key: String): String { val result = value(key); if (result.isBlank()) errors += ValidationError(key, "This field is required."); return result }
        fun decimal(key: String, required: Boolean = false): BigDecimal? { val raw = if (required) requiredValue(key) else value(key); if (raw.isBlank()) return null; return raw.toBigDecimalOrNull() ?: run { errors += ValidationError(key, "Use an exact decimal number."); null } }
        fun date(key: String, required: Boolean = false): LocalDate? { val raw = if (required) requiredValue(key) else value(key); if (raw.isBlank()) return null; return runCatching { LocalDate.parse(raw) }.getOrElse { errors += ValidationError(key, "Use ISO date YYYY-MM-DD."); null } }
        fun <T : Enum<T>> enum(key: String, enumValues: Array<T>): T? { val raw = requiredValue(key); return enumValues.firstOrNull { it.name == raw } ?: run { errors += ValidationError(key, "Allowed values: ${enumValues.joinToString { it.name }}."); null } }
        val lotRequired = when (val raw = value("requires_lot_expiry")) { "true", "TRUE", "True" -> true; "false", "FALSE", "False" -> false; else -> { errors += ValidationError("requires_lot_expiry", "Use true or false."); false } }
        val barcodeCells = value("barcodes").split("|").filter { it.isNotBlank() }
        if (barcodeCells.size != barcodeCells.map { it.trim() }.toSet().size) errors += ValidationError("barcodes", "Duplicate barcode within this row.")
        val sellingPrice = decimal("selling_price", true)
        // A malformed required money value makes the rest of this row non-actionable; report
        // that precise column rather than emitting misleading follow-on errors.
        if (sellingPrice == null) return ImportRowPreview(rowNumber, null, errors)
        val sku = requiredValue("sku"); val name = requiredValue("name"); val unit = requiredValue("unit")
        val taxClass = enum("tax_class", TaxClass.entries.toTypedArray()); val taxSource = requiredValue("tax_source"); val taxFrom = date("tax_valid_from", true)
        val benefit = enum("benefit_eligibility", BenefitEligibility.entries.toTypedArray()); val prescription = enum("prescription_class", PrescriptionClass.entries.toTypedArray()); val reorder = decimal("reorder_level", true)
        val openingQuantity = decimal("opening_quantity"); val lot = value("lot_number"); val expiry = date("expiry_date")
        if (openingQuantity != null && openingQuantity <= BigDecimal.ZERO) errors += ValidationError("opening_quantity", "Opening quantity must be greater than zero.")
        if (lotRequired && openingQuantity != null && lot.isBlank()) errors += ValidationError("lot_number", "Medicine opening stock requires lot/batch.")
        if (lotRequired && openingQuantity != null && expiry == null) errors += ValidationError("expiry_date", "Medicine opening stock requires expiry date.")
        val draft = if (listOf(taxClass, taxFrom, benefit, prescription, reorder).all { it != null }) ProductDraft(sku, name, value("generic_name").ifBlank { null }, value("brand").ifBlank { null }, value("strength").ifBlank { null }, value("dosage_form").ifBlank { null }, unit, decimal("pack_size"), sellingPrice, decimal("unit_cost"), taxClass!!, taxSource, taxFrom!!, date("tax_valid_to"), benefit!!, prescription!!, reorder!!, lotRequired, barcodeCells.map { it.trim() }.toSet(), openingQuantity?.let { listOf(OpeningLotDraft(lot, expiry, it, value("supplier_reference").ifBlank { null })) } ?: emptyList()) else null
        if (draft != null) errors += ProductValidator.validate(draft)
        // A row with parse/header errors must never be treated as a persistable draft.
        return ImportRowPreview(rowNumber, draft?.takeIf { errors.isEmpty() }, errors)
    }

    private fun addInFileDuplicateErrors(checksum: String, rows: List<ImportRowPreview>): CatalogImportPreview {
        val duplicateSkus = rows.mapNotNull { it.draft?.sku?.trim()?.uppercase() }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val duplicateBarcodes = rows.flatMap { it.draft?.barcodes.orEmpty().map(String::trim) }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return CatalogImportPreview(checksum, rows.map { row -> row.copy(errors = row.errors + buildList { if (row.draft?.sku?.trim()?.uppercase() in duplicateSkus) add(ValidationError("sku", "Duplicate SKU in import.")); row.draft?.barcodes.orEmpty().filter { it.trim() in duplicateBarcodes }.forEach { add(ValidationError("barcodes", "Duplicate barcode in import: $it")) } }) })
    }

    private fun parse(text: String): List<List<String>> {
        val output = mutableListOf<MutableList<String>>(); var row = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var closedQuote = false; var index = 0
        while (index < text.length) { when (val char = text[index]) {
            '"' -> when { !quoted && cell.isEmpty() -> quoted = true; quoted && index + 1 < text.length && text[index + 1] == '"' -> { cell.append('"'); index++ }; quoted -> { quoted = false; closedQuote = true }; else -> throw IllegalArgumentException("Unexpected quote in unquoted CSV field.") }
            ',' -> if (quoted) cell.append(char) else { row += cell.toString(); cell.clear(); closedQuote = false }
            '\n', '\r' -> if (quoted) cell.append(char) else { if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++; row += cell.toString(); cell.clear(); output += row; row = mutableListOf(); closedQuote = false }
            else -> { if (closedQuote) throw IllegalArgumentException("Unexpected character after a quoted CSV field."); cell.append(char) }
        }; index++ }
        if (quoted) throw IllegalArgumentException("Unclosed quoted CSV field.")
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); output += row }
        return output.filterNot { it.size == 1 && it.single().isEmpty() }
    }
    private companion object { const val MAX_CSV_CHARS = 10 * 1024 * 1024; const val MAX_DATA_ROWS = 50_000 }
}
