package com.medtryx.app.catalog

import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

enum class TaxClass { VATABLE, VAT_EXEMPT, ZERO_RATED }
enum class BenefitEligibility { SC_PWD_20, BNPC_5, OTHER, NONE }
enum class PrescriptionClass { PRESCRIPTION, OTC, OTHER }

data class ProductDraft(
    val sku: String, val name: String, val genericName: String? = null, val brand: String? = null,
    val strength: String? = null, val dosageForm: String? = null, val unit: String,
    val packSize: BigDecimal? = null, val sellingPrice: BigDecimal, val unitCost: BigDecimal? = null,
    val taxClass: TaxClass, val taxSource: String, val taxValidFrom: LocalDate,
    val taxValidTo: LocalDate? = null, val benefitEligibility: BenefitEligibility,
    val prescriptionClass: PrescriptionClass, val reorderLevel: BigDecimal,
    val requiresLotExpiry: Boolean, val barcodes: Set<String> = emptySet(),
    val openingLots: List<OpeningLotDraft> = emptyList(),
)
data class OpeningLotDraft(val lotNumber: String, val expiryDate: LocalDate?, val quantity: BigDecimal, val supplierReference: String? = null)
data class ValidationError(val field: String, val message: String)

object ProductValidator {
    private val skuPattern = Regex("[A-Z0-9][A-Z0-9._-]{1,63}")
    private const val MAX_QUANTITY_SCALE = 4
    private val barcodePattern = Regex("[^\\p{Cntrl}]{1,128}")
    fun validate(draft: ProductDraft): List<ValidationError> = buildList {
        if (!skuPattern.matches(draft.sku.trim().uppercase(Locale.ROOT))) add(ValidationError("sku", "Use 2-64 uppercase letters, numbers, dots, dashes, or underscores."))
        if (draft.name.isBlank()) add(ValidationError("name", "Product name is required."))
        if (draft.unit.isBlank() || draft.unit.trim().length > 40 || draft.unit.any(Char::isISOControl)) add(ValidationError("unit", "Unit must be 1-40 printable characters."))
        if (draft.sellingPrice.signum() < 0 || draft.sellingPrice.scale() > 2) add(ValidationError("sellingPrice", "Selling price must be a non-negative exact amount with at most two decimals."))
        if (draft.unitCost != null && (draft.unitCost.signum() < 0 || draft.unitCost.scale() > 2)) add(ValidationError("unitCost", "Cost must be a non-negative exact amount with at most two decimals."))
        if (draft.packSize != null && (draft.packSize <= BigDecimal.ZERO || draft.packSize.scale() > MAX_QUANTITY_SCALE)) add(ValidationError("packSize", "Pack conversion must be greater than zero with at most four decimals."))
        if (draft.reorderLevel.signum() < 0 || draft.reorderLevel.scale() > MAX_QUANTITY_SCALE) add(ValidationError("reorderLevel", "Reorder level must be non-negative with at most four decimals."))
        if (draft.taxSource.isBlank()) add(ValidationError("taxSource", "Tax authority/source is required."))
        if (draft.taxValidTo != null && draft.taxValidTo.isBefore(draft.taxValidFrom)) add(ValidationError("taxValidTo", "End date cannot precede start date."))
        if (draft.barcodes.any { !barcodePattern.matches(it.trim()) }) add(ValidationError("barcodes", "Barcode must be 1-128 printable characters."))
        draft.openingLots.forEachIndexed { index, lot ->
            if (lot.quantity <= BigDecimal.ZERO || lot.quantity.scale() > MAX_QUANTITY_SCALE) add(ValidationError("openingLots[$index].quantity", "Opening quantity must be greater than zero with at most four decimals."))
            if (draft.requiresLotExpiry && (lot.lotNumber.isBlank() || lot.expiryDate == null)) add(ValidationError("openingLots[$index]", "Medicine opening stock requires lot/batch and expiry."))
        }
    }
}
