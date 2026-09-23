package com.medtryx.app.catalog

import androidx.room.withTransaction
import com.medtryx.app.auth.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.util.UUID

class CatalogService(private val db: MedtryxDatabase, private val authorizer: ProtectedActionAuthorizer, private val auth: AuthenticationService? = null, private val clock: () -> Long = System::currentTimeMillis) {
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
 suspend fun changePrice(sessionId:String, productId:String, sellingPrice:BigDecimal, cost:BigDecimal?, effectiveFrom:String, reason:String) {
  val actor=authorizer.require(sessionId,Permission.PRICE_CHANGE,"PRODUCT_PRICE_CHANGE",productId,reason); require(sellingPrice.scale()<=2 && sellingPrice.signum()>=0 && (cost==null || cost.scale()<=2&&cost.signum()>=0)) { "Invalid exact price/cost." }
  db.withTransaction { val old=db.catalogDao().latestPrice(productId)?:error("Product price does not exist."); db.catalogDao().insertPrice(ProductPriceVersionEntity(UUID.randomUUID().toString(),productId,centavos(sellingPrice),cost?.let(::centavos),effectiveFrom,null,actor.userId,reason)); auth?.recordApplicationAudit(sessionId,"PRODUCT_PRICE_CHANGED",productId,reason,"selling=${old.sellingCentavos};cost=${old.costCentavos}","selling=${centavos(sellingPrice)};cost=${cost?.let(::centavos)}") }
 }
 suspend fun changeTaxClass(sessionId:String, productId:String, value:TaxClass, source:String, effectiveFrom:String, reason:String) {
  val actor=authorizer.require(sessionId,Permission.TAX_CONFIGURATION_CHANGE,"PRODUCT_TAX_CHANGE",productId,reason); require(source.isNotBlank()) { "Tax source is required." }; db.withTransaction { val old=db.catalogDao().latestTax(productId)?:error("Product tax does not exist."); db.catalogDao().insertTax(TaxClassVersionEntity(UUID.randomUUID().toString(),productId,value,source,effectiveFrom,null,actor.userId,reason)); auth?.recordApplicationAudit(sessionId,"PRODUCT_TAX_CHANGED",productId,reason,"${old.taxClass}:${old.source}","$value:$source") }
 }
 suspend fun changeBenefitEligibility(sessionId:String, productId:String, value:BenefitEligibility, effectiveFrom:String, reason:String) {
  val actor=authorizer.require(sessionId,Permission.BENEFIT_ELIGIBILITY_CHANGE,"PRODUCT_BENEFIT_CHANGE",productId,reason)
  db.withTransaction { val old=db.catalogDao().latestBenefit(productId)?:error("Product benefit rule does not exist."); db.catalogDao().insertBenefit(BenefitRuleVersionEntity(UUID.randomUUID().toString(),productId,value,effectiveFrom,null,actor.userId,reason)); auth?.recordApplicationAudit(sessionId,"PRODUCT_BENEFIT_CHANGED",productId,reason,old.eligibility.name,value.name) }
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
  val dao = db.catalogDao(); val sku=d.sku.trim().uppercase(); check(dao.productBySku(sku)==null) { "Duplicate SKU: $sku" }; d.barcodes.forEach { check(dao.barcode(it.trim())==null) { "Duplicate barcode: $it" } }
  val id=UUID.randomUUID().toString(); val now=clock(); val start=d.taxValidFrom.toString(); val end=d.taxValidTo?.toString()
  dao.insertProduct(ProductEntity(id,sku,d.name.trim(),d.genericName,d.brand,d.strength,d.dosageForm,d.unit.trim(),d.packSize?.stripTrailingZeros()?.toPlainString(),true,d.reorderLevel.stripTrailingZeros().toPlainString(),d.requiresLotExpiry,now,now))
  dao.insertBarcodes(d.barcodes.map { ProductBarcodeEntity(id,it.trim()) })
  dao.insertPrice(ProductPriceVersionEntity(UUID.randomUUID().toString(),id,centavos(d.sellingPrice),d.unitCost?.let(::centavos),start,end,actor,reason))
  dao.insertTax(TaxClassVersionEntity(UUID.randomUUID().toString(),id,d.taxClass,d.taxSource.trim(),start,end,actor,reason))
  dao.insertBenefit(BenefitRuleVersionEntity(UUID.randomUUID().toString(),id,d.benefitEligibility,start,end,actor,reason))
  val lots = d.openingLots.map { InventoryLotEntity(UUID.randomUUID().toString(),id,it.lotNumber.trim(),it.expiryDate?.toString(),it.quantity.stripTrailingZeros().toPlainString(),it.supplierReference) }
  dao.insertLots(lots)
  lots.forEach { lot -> db.inventoryDao().insertMovement(InventoryMovementEntity(UUID.randomUUID().toString(), id, lot.id, InventoryMovementType.OPENING_BALANCE, lot.openingQuantity, d.unit.trim(), lot.expiryDate, d.unitCost?.let(::centavos), lot.supplierReference, actor, now, reason)) }
  return id
 }
 internal suspend fun recordImportAudit(sessionId: String, manifest: ImportManifestEntity, resultingIds: List<String>, reason: String) {
  auth?.recordApplicationAudit(sessionId, "CATALOG_IMPORT_COMMITTED", manifest.id, reason, null, "checksum=${manifest.checksum};accepted=${manifest.acceptedCount};rejected=${manifest.rejectedCount};subset=${manifest.subsetCommitted};productIds=${resultingIds.joinToString(",")}")
 }
 private fun centavos(value: BigDecimal)=value.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
 private fun catalogAuditValue(draft: ProductDraft) = "sku=${draft.sku.trim().uppercase()};name=${draft.name.trim()};unit=${draft.unit.trim()};sellingCentavos=${centavos(draft.sellingPrice)};costCentavos=${draft.unitCost?.let(::centavos)};tax=${draft.taxClass};benefit=${draft.benefitEligibility};effectiveFrom=${draft.taxValidFrom};effectiveTo=${draft.taxValidTo}"
 fun checksum(csv: String)=MessageDigest.getInstance("SHA-256").digest(csv.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
