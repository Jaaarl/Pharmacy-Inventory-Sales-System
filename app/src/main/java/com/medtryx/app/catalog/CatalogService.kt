package com.medtryx.app.catalog

import androidx.room.withTransaction
import com.medtryx.app.auth.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

data class CatalogProductSnapshot(
 val product: ProductEntity,
 val sellingCentavos: Long,
 val costCentavos: Long?,
 val priceValidFrom: String,
 val taxClass: TaxClass,
 val taxSource: String,
 val taxValidFrom: String,
 val taxValidTo: String?,
 val benefitEligibility: BenefitEligibility,
 val benefitValidFrom: String,
 val prescriptionClass: PrescriptionClass,
 val barcodes: List<String>,
)

class CatalogService(private val db: MedtryxDatabase, private val authorizer: ProtectedActionAuthorizer, private val auth: AuthenticationService? = null, private val clock: () -> Long = System::currentTimeMillis) {
 suspend fun products(): List<ProductEntity> = db.catalogDao().allProducts()
 suspend fun productSnapshots(): List<CatalogProductSnapshot> = products().mapNotNull { product -> productSnapshot(product.id) }
 suspend fun productSnapshot(productId: String): CatalogProductSnapshot? {
  val dao = db.catalogDao(); val product = dao.productById(productId) ?: return null
  val price = dao.latestPrice(productId) ?: return null; val tax = dao.latestTax(productId) ?: return null; val benefit = dao.latestBenefit(productId) ?: return null
  return CatalogProductSnapshot(product, price.sellingCentavos, price.costCentavos, price.effectiveFrom, tax.taxClass, tax.source, tax.effectiveFrom, tax.effectiveTo, benefit.eligibility, benefit.effectiveFrom, product.prescriptionClass, dao.barcodesForProduct(productId).map { it.barcode })
 }

 /** Updates one SKU atomically, with a separate permission check for each protected field group. */
 suspend fun updateProduct(sessionId: String, productId: String, expectedSnapshot: CatalogProductSnapshot, draft: ProductDraft, effectiveFrom: LocalDate, reason: String) {
  val errors = ProductValidator.validate(draft); require(errors.isEmpty()) { errors.joinToString { "${it.field}: ${it.message}" } }
  val date = effectiveFrom.toString(); val dayBefore = effectiveFrom.minusDays(1).toString()
  val dao = db.catalogDao(); val oldProduct = dao.productById(productId) ?: error("Product does not exist.")
  val oldPrice = dao.latestPrice(productId) ?: error("Product price does not exist.")
  val oldTax = dao.latestTax(productId) ?: error("Product tax does not exist.")
  val oldBenefit = dao.latestBenefit(productId) ?: error("Product benefit rule does not exist.")
  require(productSnapshot(productId) == expectedSnapshot) { "Catalog data changed while this edit was open. Reload it and review the latest values." }
  require(expectedSnapshot.product.id == productId) { "The edit snapshot belongs to another product." }
  require(draft.sku.trim().uppercase(Locale.ROOT) == oldProduct.sku) { "SKU is immutable after creation." }
  val newSelling = centavos(draft.sellingPrice); val newCost = draft.unitCost?.let(::centavos)
  val generalChanged = oldProduct.name != draft.name.trim() || oldProduct.genericName != draft.genericName || oldProduct.brand != draft.brand || oldProduct.strength != draft.strength || oldProduct.dosageForm != draft.dosageForm || oldProduct.unit != draft.unit.trim() || oldProduct.packSize != draft.packSize?.stripTrailingZeros()?.toPlainString() || oldProduct.reorderLevel != draft.reorderLevel.stripTrailingZeros().toPlainString() || oldProduct.requiresLotExpiry != draft.requiresLotExpiry || oldProduct.prescriptionClass != draft.prescriptionClass
  val oldBarcodes = dao.barcodesForProduct(productId).map { it.barcode }.toSet(); val newBarcodes = draft.barcodes.map(String::trim).toSet()
  val barcodesChanged = oldBarcodes != newBarcodes
  val priceChanged = oldPrice.sellingCentavos != newSelling || oldPrice.costCentavos != newCost
  val taxChanged = oldTax.taxClass != draft.taxClass || oldTax.source != draft.taxSource.trim() || oldTax.effectiveTo != draft.taxValidTo?.toString()
  val benefitChanged = oldBenefit.eligibility != draft.benefitEligibility
  require(generalChanged || barcodesChanged || priceChanged || taxChanged || benefitChanged) { "No catalog changes to save." }
  require(draft.unit.trim() == oldProduct.unit) { "The inventory unit cannot be changed after product creation." }
  val auditService = requireNotNull(auth) { "Catalog edits require the audit service." }
  var actorUserId: String? = null
  if (generalChanged || barcodesChanged) actorUserId = authorizer.require(sessionId, Permission.PRODUCT_MANAGE, "PRODUCT_DETAILS_CHANGE", productId, reason).userId
  if (priceChanged) actorUserId = authorizer.require(sessionId, Permission.PRICE_CHANGE, "PRODUCT_PRICE_CHANGE", productId, reason).userId
  if (taxChanged) actorUserId = authorizer.require(sessionId, Permission.TAX_CONFIGURATION_CHANGE, "PRODUCT_TAX_CHANGE", productId, reason).userId
  if (benefitChanged) actorUserId = authorizer.require(sessionId, Permission.BENEFIT_ELIGIBILITY_CHANGE, "PRODUCT_BENEFIT_CHANGE", productId, reason).userId
  val actorId = requireNotNull(actorUserId)
  if (taxChanged) require(draft.taxValidTo == null || !draft.taxValidTo.isBefore(effectiveFrom)) { "Tax end date cannot precede the effective date." }
  if (priceChanged) require(date > oldPrice.effectiveFrom) { "Price changes must start after the current price version." }
  if (taxChanged) require(date > oldTax.effectiveFrom) { "Tax changes must start after the current tax version." }
  if (benefitChanged) require(date > oldBenefit.effectiveFrom) { "Eligibility changes must start after the current eligibility version." }

  db.withTransaction {
    check(productSnapshot(productId) == expectedSnapshot && dao.productById(productId) == oldProduct && dao.latestPrice(productId) == oldPrice && dao.latestTax(productId) == oldTax && dao.latestBenefit(productId) == oldBenefit && dao.barcodesForProduct(productId).map { it.barcode }.toSet() == oldBarcodes) {
    "Catalog data changed while this edit was open. Reload it and review the latest values."
   }
   if (oldProduct.requiresLotExpiry != draft.requiresLotExpiry) {
    val onHand = db.inventoryDao().quantities(productId).fold(BigDecimal.ZERO) { sum, quantity -> sum + BigDecimal(quantity) }
    require(onHand.signum() == 0) { "Lot/expiry requirements cannot change while stock remains on hand." }
   }
   val now = clock()
   if (generalChanged) {
    val updated = oldProduct.copy(name = draft.name.trim(), genericName = draft.genericName, brand = draft.brand, strength = draft.strength, dosageForm = draft.dosageForm, unit = draft.unit.trim(), packSize = draft.packSize?.stripTrailingZeros()?.toPlainString(), reorderLevel = draft.reorderLevel.stripTrailingZeros().toPlainString(), requiresLotExpiry = draft.requiresLotExpiry, prescriptionClass = draft.prescriptionClass, updatedAt = now)
    dao.updateProduct(updated)
    auditService.recordApplicationAudit(sessionId, "PRODUCT_DETAILS_CHANGED", productId, reason, productDetails(oldProduct), productDetails(updated))
   }
   if (barcodesChanged) {
    (oldBarcodes - newBarcodes).forEach { dao.deleteBarcode(productId, it) }
    val additions = newBarcodes - oldBarcodes
    additions.forEach { barcode -> check(dao.barcode(barcode) == null) { "Barcode already belongs to another product: $barcode" } }
    dao.insertBarcodes(additions.map { ProductBarcodeEntity(productId, it) })
    auditService.recordApplicationAudit(sessionId, "PRODUCT_BARCODES_CHANGED", productId, reason, oldBarcodes.sorted().joinToString("|"), newBarcodes.sorted().joinToString("|"))
   }
   if (priceChanged) {
    val priorEnds = closePrior(oldPrice.effectiveTo, dayBefore)
    priorEnds?.let { dao.closePriceVersion(oldPrice.id, it) }
    dao.insertPrice(ProductPriceVersionEntity(UUID.randomUUID().toString(), productId, newSelling, newCost, date, null, actorId, reason))
    auditService.recordApplicationAudit(sessionId, "PRODUCT_PRICE_CHANGED", productId, reason, "sellingCentavos=${oldPrice.sellingCentavos};costCentavos=${oldPrice.costCentavos};effectiveFrom=${oldPrice.effectiveFrom};effectiveTo=${oldPrice.effectiveTo}", "sellingCentavos=$newSelling;costCentavos=$newCost;effectiveFrom=$date;priorVersionEnds=${priorEnds ?: oldPrice.effectiveTo}")
   }
   if (taxChanged) {
    val priorEnds = closePrior(oldTax.effectiveTo, dayBefore)
    priorEnds?.let { dao.closeTaxVersion(oldTax.id, it) }
    dao.insertTax(TaxClassVersionEntity(UUID.randomUUID().toString(), productId, draft.taxClass, draft.taxSource.trim(), date, draft.taxValidTo?.toString(), actorId, reason))
    auditService.recordApplicationAudit(sessionId, "PRODUCT_TAX_CHANGED", productId, reason, "taxClass=${oldTax.taxClass};source=${oldTax.source};effectiveFrom=${oldTax.effectiveFrom};effectiveTo=${oldTax.effectiveTo}", "taxClass=${draft.taxClass};source=${draft.taxSource.trim()};effectiveFrom=$date;effectiveTo=${draft.taxValidTo};priorVersionEnds=${priorEnds ?: oldTax.effectiveTo}")
   }
   if (benefitChanged) {
    val priorEnds = closePrior(oldBenefit.effectiveTo, dayBefore)
    priorEnds?.let { dao.closeBenefitVersion(oldBenefit.id, it) }
    dao.insertBenefit(BenefitRuleVersionEntity(UUID.randomUUID().toString(), productId, draft.benefitEligibility, date, null, actorId, reason))
    auditService.recordApplicationAudit(sessionId, "PRODUCT_BENEFIT_CHANGED", productId, reason, "eligibility=${oldBenefit.eligibility};effectiveFrom=${oldBenefit.effectiveFrom};effectiveTo=${oldBenefit.effectiveTo}", "eligibility=${draft.benefitEligibility};effectiveFrom=$date;priorVersionEnds=${priorEnds ?: oldBenefit.effectiveTo}")
   }
  }
 }
 suspend fun createProduct(sessionId: String, draft: ProductDraft, reason: String): String {
  val actor = authorizer.require(sessionId, Permission.PRODUCT_MANAGE, "PRODUCT_CREATE", draft.sku, reason)
  val errors = ProductValidator.validate(draft); require(errors.isEmpty()) { errors.joinToString { "${it.field}: ${it.message}" } }
  return db.withTransaction {
   val id = persist(draft, actor.userId, reason)
   auth?.recordApplicationAudit(sessionId, "PRODUCT_CREATED", id, reason, null, catalogAuditValue(draft))
   id
  }
 }
 suspend fun deactivateProduct(sessionId: String, productId: String, reason: String) {
  authorizer.require(sessionId, Permission.PRODUCT_MANAGE, "PRODUCT_DEACTIVATE", productId, reason)
  db.withTransaction {
   val product = db.catalogDao().productById(productId) ?: error("Product does not exist.")
   if (product.active) {
    db.catalogDao().deactivate(productId, clock())
    auth?.recordApplicationAudit(sessionId, "PRODUCT_DEACTIVATED", productId, reason, "active=true", "active=false")
   }
  }
 }
 suspend fun changePrice(sessionId: String, productId: String, sellingPrice: BigDecimal, cost: BigDecimal?, effectiveFrom: String, reason: String) {
  val actor = authorizer.require(sessionId, Permission.PRICE_CHANGE, "PRODUCT_PRICE_CHANGE", productId, reason)
  require(sellingPrice.scale() <= 2 && sellingPrice.signum() >= 0 && (cost == null || cost.scale() <= 2 && cost.signum() >= 0)) { "Invalid exact price/cost." }
  val start = LocalDate.parse(effectiveFrom)
  db.withTransaction {
   val dao = db.catalogDao(); val old = dao.latestPrice(productId) ?: error("Product price does not exist.")
   require(start.toString() > old.effectiveFrom) { "Price changes must start after the current price version." }
   val priorEnds = closePrior(old.effectiveTo, start.minusDays(1).toString())
   priorEnds?.let { dao.closePriceVersion(old.id, it) }
   dao.insertPrice(ProductPriceVersionEntity(UUID.randomUUID().toString(), productId, centavos(sellingPrice), cost?.let(::centavos), start.toString(), null, actor.userId, reason))
   auth?.recordApplicationAudit(sessionId, "PRODUCT_PRICE_CHANGED", productId, reason, "sellingCentavos=${old.sellingCentavos};costCentavos=${old.costCentavos};effectiveFrom=${old.effectiveFrom};effectiveTo=${old.effectiveTo}", "sellingCentavos=${centavos(sellingPrice)};costCentavos=${cost?.let(::centavos)};effectiveFrom=$start;priorVersionEnds=${priorEnds ?: old.effectiveTo}")
  }
 }
 suspend fun changeTaxClass(sessionId: String, productId: String, value: TaxClass, source: String, effectiveFrom: String, reason: String) {
  val actor = authorizer.require(sessionId, Permission.TAX_CONFIGURATION_CHANGE, "PRODUCT_TAX_CHANGE", productId, reason)
  require(source.isNotBlank()) { "Tax source is required." }; val start = LocalDate.parse(effectiveFrom)
  db.withTransaction {
   val dao = db.catalogDao(); val old = dao.latestTax(productId) ?: error("Product tax does not exist.")
   require(start.toString() > old.effectiveFrom) { "Tax changes must start after the current tax version." }
   val priorEnds = closePrior(old.effectiveTo, start.minusDays(1).toString())
   priorEnds?.let { dao.closeTaxVersion(old.id, it) }
   dao.insertTax(TaxClassVersionEntity(UUID.randomUUID().toString(), productId, value, source.trim(), start.toString(), null, actor.userId, reason))
   auth?.recordApplicationAudit(sessionId, "PRODUCT_TAX_CHANGED", productId, reason, "taxClass=${old.taxClass};source=${old.source};effectiveFrom=${old.effectiveFrom};effectiveTo=${old.effectiveTo}", "taxClass=$value;source=${source.trim()};effectiveFrom=$start;priorVersionEnds=${priorEnds ?: old.effectiveTo}")
  }
 }
 suspend fun changeBenefitEligibility(sessionId: String, productId: String, value: BenefitEligibility, effectiveFrom: String, reason: String) {
  val actor = authorizer.require(sessionId, Permission.BENEFIT_ELIGIBILITY_CHANGE, "PRODUCT_BENEFIT_CHANGE", productId, reason)
  val start = LocalDate.parse(effectiveFrom)
  db.withTransaction {
   val dao = db.catalogDao(); val old = dao.latestBenefit(productId) ?: error("Product benefit rule does not exist.")
   require(start.toString() > old.effectiveFrom) { "Eligibility changes must start after the current eligibility version." }
   val priorEnds = closePrior(old.effectiveTo, start.minusDays(1).toString())
   priorEnds?.let { dao.closeBenefitVersion(old.id, it) }
   dao.insertBenefit(BenefitRuleVersionEntity(UUID.randomUUID().toString(), productId, value, start.toString(), null, actor.userId, reason))
   auth?.recordApplicationAudit(sessionId, "PRODUCT_BENEFIT_CHANGED", productId, reason, "eligibility=${old.eligibility};effectiveFrom=${old.effectiveFrom};effectiveTo=${old.effectiveTo}", "eligibility=$value;effectiveFrom=$start;priorVersionEnds=${priorEnds ?: old.effectiveTo}")
  }
 }
 suspend fun changeReorderLevel(sessionId: String, productId: String, reorderLevel: BigDecimal, reason: String) {
  authorizer.require(sessionId, Permission.PRODUCT_MANAGE, "PRODUCT_REORDER_LEVEL_CHANGE", productId, reason)
  require(reorderLevel.signum() >= 0 && reorderLevel.scale() <= 4) { "Reorder level must be non-negative with at most four decimals." }
  db.withTransaction {
   val old = db.catalogDao().productById(productId) ?: error("Product does not exist.")
   val updated = reorderLevel.stripTrailingZeros().toPlainString()
   db.catalogDao().updateReorderLevel(productId, updated, clock())
   auth?.recordApplicationAudit(sessionId, "PRODUCT_REORDER_LEVEL_CHANGED", productId, reason, old.reorderLevel, updated)
  }
 }
 suspend fun addBarcode(sessionId: String, productId: String, barcode: String, reason: String) {
  authorizer.require(sessionId, Permission.PRODUCT_MANAGE, "PRODUCT_BARCODE_ADD", productId, reason)
  val normalized = barcode.trim(); require(normalized.matches(Regex("[^\\p{Cntrl}]{1,128}"))) { "Barcode must be 1-128 printable characters." }
  db.withTransaction {
   check(db.catalogDao().productById(productId) != null) { "Product does not exist." }
   check(db.catalogDao().barcode(normalized) == null) { "Duplicate barcode: $normalized" }
   db.catalogDao().insertBarcodes(listOf(ProductBarcodeEntity(productId, normalized)))
   auth?.recordApplicationAudit(sessionId, "PRODUCT_BARCODE_ADDED", productId, reason, null, normalized)
  }
 }
 suspend fun removeBarcode(sessionId: String, productId: String, barcode: String, reason: String) {
  authorizer.require(sessionId, Permission.PRODUCT_MANAGE, "PRODUCT_BARCODE_REMOVE", productId, reason)
  val normalized = barcode.trim()
  db.withTransaction {
   require(db.catalogDao().deleteBarcode(productId, normalized) == 1) { "Barcode does not exist for this product." }
   auth?.recordApplicationAudit(sessionId, "PRODUCT_BARCODE_REMOVED", productId, reason, normalized, null)
  }
 }
 internal suspend fun persist(d: ProductDraft, actor: String, reason: String): String {
  val dao = db.catalogDao(); val sku=d.sku.trim().uppercase(Locale.ROOT); check(dao.productBySku(sku)==null) { "Duplicate SKU: $sku" }; d.barcodes.forEach { check(dao.barcode(it.trim())==null) { "Duplicate barcode: $it" } }
  val id=UUID.randomUUID().toString(); val now=clock(); val start=d.taxValidFrom.toString(); val end=d.taxValidTo?.toString()
  dao.insertProduct(ProductEntity(id,sku,d.name.trim(),d.genericName,d.brand,d.strength,d.dosageForm,d.unit.trim(),d.packSize?.stripTrailingZeros()?.toPlainString(),true,d.reorderLevel.stripTrailingZeros().toPlainString(),d.requiresLotExpiry,d.prescriptionClass,now,now))
  dao.insertBarcodes(d.barcodes.map { ProductBarcodeEntity(id,it.trim()) })
  dao.insertPrice(ProductPriceVersionEntity(UUID.randomUUID().toString(),id,centavos(d.sellingPrice),d.unitCost?.let(::centavos),start,null,actor,reason))
  dao.insertTax(TaxClassVersionEntity(UUID.randomUUID().toString(),id,d.taxClass,d.taxSource.trim(),start,end,actor,reason))
  dao.insertBenefit(BenefitRuleVersionEntity(UUID.randomUUID().toString(),id,d.benefitEligibility,start,null,actor,reason))
  val lots = d.openingLots.map { InventoryLotEntity(UUID.randomUUID().toString(),id,it.lotNumber.trim(),it.expiryDate?.toString(),it.quantity.stripTrailingZeros().toPlainString(),it.supplierReference) }
  dao.insertLots(lots)
  lots.forEach { lot -> db.inventoryDao().insertMovement(InventoryMovementEntity(UUID.randomUUID().toString(), id, lot.id, InventoryMovementType.OPENING_BALANCE, lot.openingQuantity, d.unit.trim(), lot.expiryDate, d.unitCost?.let(::centavos), lot.supplierReference, actor, now, reason)) }
  return id
 }
 internal suspend fun recordImportAudit(sessionId: String, manifest: ImportManifestEntity, resultingIds: List<String>, reason: String) {
  auth?.recordApplicationAudit(sessionId, "CATALOG_IMPORT_COMMITTED", manifest.id, reason, null, "checksum=${manifest.checksum};accepted=${manifest.acceptedCount};rejected=${manifest.rejectedCount};subset=${manifest.subsetCommitted};productIds=${resultingIds.joinToString(",")}")
 }
 private fun centavos(value: BigDecimal)=value.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
 private fun closePrior(existingEnd: String?, proposedEnd: String): String? = when {
  existingEnd == null -> proposedEnd
  existingEnd > proposedEnd -> proposedEnd
  else -> null
 }
 private fun productDetails(p: ProductEntity) = "name=${p.name};generic=${p.genericName};brand=${p.brand};strength=${p.strength};dosageForm=${p.dosageForm};unit=${p.unit};packSize=${p.packSize};reorderLevel=${p.reorderLevel};requiresLotExpiry=${p.requiresLotExpiry};prescriptionClass=${p.prescriptionClass}"
 private fun catalogAuditValue(draft: ProductDraft) = "sku=${draft.sku.trim().uppercase(Locale.ROOT)};name=${draft.name.trim()};unit=${draft.unit.trim()};sellingCentavos=${centavos(draft.sellingPrice)};costCentavos=${draft.unitCost?.let(::centavos)};tax=${draft.taxClass};benefit=${draft.benefitEligibility};effectiveFrom=${draft.taxValidFrom};effectiveTo=${draft.taxValidTo}"
 fun checksum(csv: String)=MessageDigest.getInstance("SHA-256").digest(csv.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
