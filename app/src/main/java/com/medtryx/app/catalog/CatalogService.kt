package com.medtryx.app.catalog

import androidx.room.withTransaction
import com.medtryx.app.auth.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.util.UUID

class CatalogService(private val db: MedtryxDatabase, private val authorizer: ProtectedActionAuthorizer, private val clock: () -> Long = System::currentTimeMillis) {
 suspend fun createProduct(sessionId: String, draft: ProductDraft, reason: String): String {
  val actor = authorizer.require(sessionId, Permission.PRODUCT_MANAGE, "PRODUCT_CREATE", draft.sku, reason)
  val errors = ProductValidator.validate(draft); require(errors.isEmpty()) { errors.joinToString { "${it.field}: ${it.message}" } }
  return db.withTransaction { persist(draft, actor.userId, reason) }
 }
 suspend fun deactivateProduct(sessionId: String, productId: String, reason: String) {
  authorizer.require(sessionId, Permission.PRODUCT_MANAGE, "PRODUCT_DEACTIVATE", productId, reason); db.catalogDao().deactivate(productId, clock())
 }
 internal suspend fun persist(d: ProductDraft, actor: String, reason: String): String {
  val dao = db.catalogDao(); val sku=d.sku.trim().uppercase(); check(dao.productBySku(sku)==null) { "Duplicate SKU: $sku" }; d.barcodes.forEach { check(dao.barcode(it.trim())==null) { "Duplicate barcode: $it" } }
  val id=UUID.randomUUID().toString(); val now=clock(); val start=d.taxValidFrom.toString(); val end=d.taxValidTo?.toString()
  dao.insertProduct(ProductEntity(id,sku,d.name.trim(),d.genericName,d.brand,d.strength,d.dosageForm,d.unit.trim(),d.packSize?.stripTrailingZeros()?.toPlainString(),true,d.reorderLevel.stripTrailingZeros().toPlainString(),d.requiresLotExpiry,now,now))
  dao.insertBarcodes(d.barcodes.map { ProductBarcodeEntity(id,it.trim()) })
  dao.insertPrice(ProductPriceVersionEntity(UUID.randomUUID().toString(),id,centavos(d.sellingPrice),d.unitCost?.let(::centavos),start,end,actor,reason))
  dao.insertTax(TaxClassVersionEntity(UUID.randomUUID().toString(),id,d.taxClass,d.taxSource.trim(),start,end,actor,reason))
  dao.insertBenefit(BenefitRuleVersionEntity(UUID.randomUUID().toString(),id,d.benefitEligibility,start,end,actor,reason))
  dao.insertLots(d.openingLots.map { InventoryLotEntity(UUID.randomUUID().toString(),id,it.lotNumber.trim(),it.expiryDate?.toString(),it.quantity.stripTrailingZeros().toPlainString(),it.supplierReference) }); return id
 }
 private fun centavos(value: BigDecimal)=value.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
 fun checksum(csv: String)=MessageDigest.getInstance("SHA-256").digest(csv.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
