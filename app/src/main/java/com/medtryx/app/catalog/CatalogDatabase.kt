package com.medtryx.app.catalog

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "products", indices = [Index(value = ["sku"], unique = true)])
data class ProductEntity(@PrimaryKey val id: String, val sku: String, val name: String, val genericName: String?, val brand: String?, val strength: String?, val dosageForm: String?, val unit: String, val packSize: String?, val active: Boolean, val reorderLevel: String, val requiresLotExpiry: Boolean, val createdAt: Long, val updatedAt: Long)
@Entity(tableName = "product_barcodes", primaryKeys = ["productId", "barcode"], indices = [Index(value = ["barcode"], unique = true)], foreignKeys = [ForeignKey(ProductEntity::class, ["id"], ["productId"], onDelete = ForeignKey.CASCADE)])
data class ProductBarcodeEntity(val productId: String, val barcode: String)
@Entity(tableName = "product_price_versions", indices = [Index("productId")], foreignKeys = [ForeignKey(ProductEntity::class, ["id"], ["productId"], onDelete = ForeignKey.CASCADE)])
data class ProductPriceVersionEntity(@PrimaryKey val id: String, val productId: String, val sellingCentavos: Long, val costCentavos: Long?, val effectiveFrom: String, val effectiveTo: String?, val approvedBy: String, val reason: String)
@Entity(tableName = "tax_class_versions", indices = [Index("productId")], foreignKeys = [ForeignKey(ProductEntity::class, ["id"], ["productId"], onDelete = ForeignKey.CASCADE)])
data class TaxClassVersionEntity(@PrimaryKey val id: String, val productId: String, val taxClass: TaxClass, val source: String, val effectiveFrom: String, val effectiveTo: String?, val approvedBy: String, val reason: String)
@Entity(tableName = "benefit_rule_versions", indices = [Index("productId")], foreignKeys = [ForeignKey(ProductEntity::class, ["id"], ["productId"], onDelete = ForeignKey.CASCADE)])
data class BenefitRuleVersionEntity(@PrimaryKey val id: String, val productId: String, val eligibility: BenefitEligibility, val effectiveFrom: String, val effectiveTo: String?, val approvedBy: String, val reason: String)
@Entity(tableName = "inventory_lots", indices = [Index("productId")], foreignKeys = [ForeignKey(ProductEntity::class, ["id"], ["productId"], onDelete = ForeignKey.CASCADE)])
data class InventoryLotEntity(@PrimaryKey val id: String, val productId: String, val lotNumber: String, val expiryDate: String?, val openingQuantity: String, val supplierReference: String?)
@Entity(tableName = "import_manifests")
data class ImportManifestEntity(@PrimaryKey val id: String, val checksum: String, val actorUserId: String, val createdAt: Long, val acceptedCount: Int, val rejectedCount: Int, val subsetCommitted: Boolean)
@Entity(tableName = "import_row_results", indices = [Index("manifestId")], foreignKeys = [ForeignKey(ImportManifestEntity::class, ["id"], ["manifestId"], onDelete = ForeignKey.CASCADE)])
data class ImportRowResultEntity(@PrimaryKey val id: String, val manifestId: String, val rowNumber: Int, val accepted: Boolean, val errors: String?, val resultingProductId: String?)

@Dao interface CatalogDao {
 @Query("SELECT * FROM products WHERE sku = :sku") suspend fun productBySku(sku: String): ProductEntity?
 @Query("SELECT * FROM product_barcodes WHERE barcode = :barcode") suspend fun barcode(barcode: String): ProductBarcodeEntity?
 @Query("SELECT COUNT(*) FROM products") suspend fun count(): Int
 @Query("SELECT * FROM product_price_versions WHERE productId = :id ORDER BY effectiveFrom DESC LIMIT 1") suspend fun latestPrice(id:String): ProductPriceVersionEntity?
 @Query("SELECT * FROM tax_class_versions WHERE productId = :id ORDER BY effectiveFrom DESC LIMIT 1") suspend fun latestTax(id:String): TaxClassVersionEntity?
 @Query("SELECT * FROM benefit_rule_versions WHERE productId = :id ORDER BY effectiveFrom DESC LIMIT 1") suspend fun latestBenefit(id:String): BenefitRuleVersionEntity?
 @Insert suspend fun insertProduct(value: ProductEntity)
 @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertBarcodes(values: List<ProductBarcodeEntity>)
 @Insert suspend fun insertPrice(value: ProductPriceVersionEntity)
 @Insert suspend fun insertTax(value: TaxClassVersionEntity)
 @Insert suspend fun insertBenefit(value: BenefitRuleVersionEntity)
 @Insert suspend fun insertLots(values: List<InventoryLotEntity>)
 @Insert suspend fun insertManifest(value: ImportManifestEntity)
 @Insert suspend fun insertResults(values: List<ImportRowResultEntity>)
 @Query("UPDATE products SET active = 0, updatedAt = :at WHERE id = :id") suspend fun deactivate(id: String, at: Long)
}
