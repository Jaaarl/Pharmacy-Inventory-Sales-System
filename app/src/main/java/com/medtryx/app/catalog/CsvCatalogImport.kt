package com.medtryx.app.catalog

import androidx.room.withTransaction
import com.medtryx.app.auth.MedtryxDatabase
import com.medtryx.app.auth.Permission
import com.medtryx.app.auth.ProtectedActionAuthorizer
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

data class ImportRowPreview(
    val rowNumber: Int,
    val draft: ProductDraft?,
    val errors: List<ValidationError>,
    val rawSku: String? = null,
    val rawBarcodes: List<String> = emptyList(),
)
data class CatalogImportPreview(val checksum: String, val rows: List<ImportRowPreview>) {
    val validRows get() = rows.filter { it.errors.isEmpty() && it.draft != null }
    val rejectedRows get() = rows.filter { it.errors.isNotEmpty() }
}

object CatalogCsvEncoding {
    const val MAX_BYTES = 10 * 1024 * 1024

    fun decodeUtf8(bytes: ByteArray): String {
        require(bytes.size <= MAX_BYTES) { "CSV file exceeds the 10 MiB import limit." }
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }
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
        if (csv.isEmpty() || csv.toByteArray(Charsets.UTF_8).size > CatalogCsvEncoding.MAX_BYTES) return invalidFile(csv, if (csv.isEmpty()) "CSV file is empty." else "CSV file exceeds the 10 MiB import limit.")
        val records = try { parse(csv) } catch (error: IllegalArgumentException) { return invalidFile(csv, error.message ?: "Malformed CSV.") }
        if (records.isEmpty()) return invalidFile(csv, "CSV file is empty.")
        if (records.size == 1) return invalidFile(csv, "CSV must contain a header and at least one data row.")
        if (records.size - 1 > MAX_DATA_ROWS) return invalidFile(csv, "CSV exceeds the $MAX_DATA_ROWS-row import limit.")
        val headers = records.first().mapIndexed { index, value -> value.removePrefix(if (index == 0) "\uFEFF" else "").trim().lowercase() }
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
            if (dao.productBySku(draft.sku.trim().uppercase(Locale.ROOT)) != null) errors += ValidationError("sku", "SKU already exists in the catalog.")
            draft.barcodes.forEach { barcode -> if (dao.barcode(barcode.trim()) != null) errors += ValidationError("barcodes", "Barcode already exists in the catalog: $barcode") }
            row.copy(errors = errors)
        })
    }

    suspend fun commit(sessionId: String, csv: String, preview: CatalogImportPreview, reason: String, reviewedSubset: Set<Int>? = null): List<String> {
        require(catalog.checksum(csv) == preview.checksum) { "The CSV changed after preview. Preview it again before committing." }
        val actor = authorizer.require(sessionId, Permission.PRODUCT_MANAGE, "CATALOG_IMPORT", preview.checksum, reason)
        return db.withTransaction {
            val validated = validateAgainstCatalog(preview(csv))
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
        if (values.size != headers.size) errors += ValidationError("row", "Row has ${values.size} columns; expected ${headers.size}.")
        fun value(key: String) = headers.indexOf(key).takeIf { it >= 0 }?.let { values.getOrElse(it) { "" } } ?: ""
        fun requiredValue(key: String): String { val result = value(key); if (result.isBlank()) errors += ValidationError(key, "This field is required."); return result }
        fun decimal(key: String, required: Boolean = false): BigDecimal? { val raw = if (required) requiredValue(key) else value(key); if (raw.isBlank()) return null; return raw.toBigDecimalOrNull() ?: run { errors += ValidationError(key, "Use an exact decimal number."); null } }
        fun date(key: String, required: Boolean = false): LocalDate? { val raw = if (required) requiredValue(key) else value(key); if (raw.isBlank()) return null; return runCatching { LocalDate.parse(raw) }.getOrElse { errors += ValidationError(key, "Use ISO date YYYY-MM-DD."); null } }
        fun <T : Enum<T>> enum(key: String, enumValues: Array<T>): T? { val raw = requiredValue(key); return enumValues.firstOrNull { it.name == raw } ?: run { errors += ValidationError(key, "Allowed values: ${enumValues.joinToString { it.name }}."); null } }
        val lotRequired = when (val raw = value("requires_lot_expiry")) { "true", "TRUE", "True" -> true; "false", "FALSE", "False" -> false; else -> { errors += ValidationError("requires_lot_expiry", "Use true or false."); false } }
        val barcodeCells = value("barcodes").split("|").filter { it.isNotBlank() }
        if (barcodeCells.size != barcodeCells.map { it.trim() }.toSet().size) errors += ValidationError("barcodes", "Duplicate barcode within this row.")
        val sellingPrice = decimal("selling_price", true)
        val sku = requiredValue("sku"); val name = requiredValue("name"); val unit = requiredValue("unit")
        val taxClass = enum("tax_class", TaxClass.entries.toTypedArray()); val taxSource = requiredValue("tax_source"); val taxFrom = date("tax_valid_from", true)
        val benefit = enum("benefit_eligibility", BenefitEligibility.entries.toTypedArray()); val prescription = enum("prescription_class", PrescriptionClass.entries.toTypedArray()); val reorder = decimal("reorder_level", true)
        val openingLots = parseOpeningLots(headers, ::value, ::decimal, ::date, lotRequired, errors)
        val draft = if (sellingPrice != null && listOf(taxClass, taxFrom, benefit, prescription, reorder).all { it != null }) ProductDraft(sku, name, value("generic_name").ifBlank { null }, value("brand").ifBlank { null }, value("strength").ifBlank { null }, value("dosage_form").ifBlank { null }, unit, decimal("pack_size"), sellingPrice, decimal("unit_cost"), taxClass!!, taxSource, taxFrom!!, date("tax_valid_to"), benefit!!, prescription!!, reorder!!, lotRequired, barcodeCells.map { it.trim() }.toSet(), openingLots) else null
        if (draft != null) errors += ProductValidator.validate(draft)
        // A row with parse/header errors must never be treated as a persistable draft.
        return ImportRowPreview(rowNumber, draft?.takeIf { errors.isEmpty() }, errors, value("sku").trim().uppercase(Locale.ROOT), barcodeCells.map(String::trim))
    }

    private fun parseOpeningLots(
        headers: List<String>, value: (String) -> String,
        decimal: (String, Boolean) -> BigDecimal?, date: (String, Boolean) -> LocalDate?,
        lotRequired: Boolean, errors: MutableList<ValidationError>,
    ): List<OpeningLotDraft> {
        val encodedLots = value("opening_lots")
        val legacyColumnsPresent = listOf("opening_quantity", "lot_number", "expiry_date", "supplier_reference").any { it in headers && value(it).isNotBlank() }
        if (encodedLots.isNotBlank() && legacyColumnsPresent) {
            errors += ValidationError("opening_lots", "Use opening_lots or the single-lot columns, not both.")
            return emptyList()
        }
        if (encodedLots.isNotBlank()) return encodedLots.lines().filter(String::isNotBlank).mapIndexedNotNull { index, line ->
            val columns = line.split('|').map(String::trim)
            if (columns.size !in 3..4) {
                errors += ValidationError("opening_lots", "Lot ${index + 1} must be lot | YYYY-MM-DD | quantity | optional supplier.")
                return@mapIndexedNotNull null
            }
            val expiry = if (columns[1].isBlank()) null else runCatching { LocalDate.parse(columns[1]) }.getOrElse {
                errors += ValidationError("opening_lots", "Lot ${index + 1} expiry must use YYYY-MM-DD."); null
            }
            val quantity = columns[2].toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: run {
                errors += ValidationError("opening_lots", "Lot ${index + 1} quantity must be a positive exact decimal."); null
            }
            if (lotRequired && columns[0].isBlank()) errors += ValidationError("opening_lots", "Lot ${index + 1} requires a lot/batch number.")
            if (lotRequired && expiry == null) errors += ValidationError("opening_lots", "Lot ${index + 1} requires an expiry date.")
            if (quantity == null || (lotRequired && (expiry == null || columns[0].isBlank()))) null else OpeningLotDraft(columns[0], expiry, quantity, columns.getOrNull(3)?.ifBlank { null })
        }
        val openingQuantity = decimal("opening_quantity", false)
        if (openingQuantity == null) {
            if (legacyColumnsPresent) errors += ValidationError("opening_quantity", "Opening lot details require an opening quantity.")
            return emptyList()
        }
        val lot = value("lot_number").trim(); val expiry = date("expiry_date", false)
        if (openingQuantity <= BigDecimal.ZERO) errors += ValidationError("opening_quantity", "Opening quantity must be greater than zero.")
        if (lotRequired && lot.isBlank()) errors += ValidationError("lot_number", "Medicine opening stock requires lot/batch.")
        if (lotRequired && expiry == null) errors += ValidationError("expiry_date", "Medicine opening stock requires expiry date.")
        if (openingQuantity <= BigDecimal.ZERO || (lotRequired && (lot.isBlank() || expiry == null))) return emptyList()
        return listOf(OpeningLotDraft(lot, expiry, openingQuantity, value("supplier_reference").ifBlank { null }))
    }

    private fun addInFileDuplicateErrors(checksum: String, rows: List<ImportRowPreview>): CatalogImportPreview {
        val duplicateSkus = rows.mapNotNull { it.rawSku?.takeIf(String::isNotBlank) }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val duplicateBarcodes = rows.flatMap { it.rawBarcodes }.filter(String::isNotBlank).groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return CatalogImportPreview(checksum, rows.map { row -> row.copy(errors = row.errors + buildList { if (row.rawSku in duplicateSkus) add(ValidationError("sku", "Duplicate SKU in import.")); row.rawBarcodes.filter { it in duplicateBarcodes }.forEach { add(ValidationError("barcodes", "Duplicate barcode in import: $it")) } }) })
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
    private companion object { const val MAX_DATA_ROWS = 50_000 }
}
