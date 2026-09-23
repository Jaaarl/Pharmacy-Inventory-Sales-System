package com.medtryx.app.catalog

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import com.medtryx.app.auth.MedtryxDatabase
import com.medtryx.app.auth.Permission
import com.medtryx.app.auth.ProtectedActionAuthorizer
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

enum class InventoryMovementType { OPENING_BALANCE, RECEIPT, SALE, SALE_REVERSAL, RETURN_SELLABLE, RETURN_DAMAGED, ADJUSTMENT_IN, ADJUSTMENT_OUT, EXPIRED, BUNDLE_ASSEMBLY_IN, BUNDLE_ASSEMBLY_OUT }

@Entity(tableName = "inventory_movements", indices = [Index("productId"), Index("lotId"), Index("occurredAtUtcMillis")], foreignKeys = [ForeignKey(ProductEntity::class, ["id"], ["productId"], onDelete = ForeignKey.RESTRICT), ForeignKey(InventoryLotEntity::class, ["id"], ["lotId"], onDelete = ForeignKey.RESTRICT)])
data class InventoryMovementEntity(
    @PrimaryKey val id: String,
    val productId: String,
    val lotId: String?,
    val type: InventoryMovementType,
    /** Signed base-unit quantity. */ val quantity: String,
    val unit: String,
    val expiryDate: String?,
    val costCentavos: Long?,
    val sourceReference: String?,
    val actorUserId: String,
    val occurredAtUtcMillis: Long,
    val reason: String,
)

data class LotBalance(val lotId: String, val lotNumber: String, val expiryDate: String?, val onHand: BigDecimal)
data class StockReceiptDraft(val productId: String, val quantity: BigDecimal, val lotNumber: String? = null, val expiryDate: String? = null, val unitCostCentavos: Long? = null, val supplierReference: String? = null)

@Dao interface InventoryDao {
    @Query("SELECT quantity FROM inventory_movements WHERE productId = :productId") suspend fun quantities(productId: String): List<String>
    @Query("SELECT l.id AS lotId, l.lotNumber AS lotNumber, l.expiryDate AS expiryDate, m.quantity AS quantity FROM inventory_lots l LEFT JOIN inventory_movements m ON m.lotId = l.id WHERE l.productId = :productId") suspend fun lotMovementRows(productId: String): List<LotMovementRow>
    @Query("SELECT * FROM inventory_lots WHERE productId = :productId AND lotNumber = :lotNumber AND ((expiryDate IS NULL AND :expiryDate IS NULL) OR expiryDate = :expiryDate) LIMIT 1") suspend fun lot(productId: String, lotNumber: String, expiryDate: String?): InventoryLotEntity?
    @Insert suspend fun insertLot(value: InventoryLotEntity)
    @Insert suspend fun insertMovement(value: InventoryMovementEntity)
}
data class LotMovementRow(val lotId: String, val lotNumber: String, val expiryDate: String?, val quantity: String?)

class InventoryService(private val db: MedtryxDatabase, private val authorizer: ProtectedActionAuthorizer, private val clock: () -> Long = System::currentTimeMillis, private val today: () -> LocalDate = LocalDate::now) {
    suspend fun onHand(productId: String): BigDecimal = db.inventoryDao().quantities(productId).fold(BigDecimal.ZERO) { sum, quantity -> sum + BigDecimal(quantity) }.stripTrailingZeros()
    suspend fun availableLots(productId: String): List<LotBalance> = db.inventoryDao().lotMovementRows(productId).groupBy { it.lotId }.map { (id, rows) -> LotBalance(id, rows.first().lotNumber, rows.first().expiryDate, rows.mapNotNull { it.quantity }.fold(BigDecimal.ZERO) { sum, quantity -> sum + BigDecimal(quantity) }) }.filter { it.onHand > BigDecimal.ZERO }.sortedWith(compareBy<LotBalance> { it.expiryDate == null }.thenBy { it.expiryDate }.thenBy { it.lotId })
    suspend fun allocateEarliestExpiry(productId: String, quantity: BigDecimal): List<Pair<String, BigDecimal>> {
        require(quantity > BigDecimal.ZERO) { "Quantity must be greater than zero." }
        var remaining = quantity
        val allocation = buildList {
            for (lot in availableLots(productId).filter { it.expiryDate == null || !LocalDate.parse(it.expiryDate).isBefore(today()) }) {
                if (remaining <= BigDecimal.ZERO) break
                val allocated = lot.onHand.min(remaining)
                add(lot.lotId to allocated); remaining -= allocated
            }
        }
        check(remaining == BigDecimal.ZERO) { "Insufficient stock." }
        return allocation
    }
    suspend fun receive(sessionId: String, receipt: StockReceiptDraft, reason: String): String {
        val actor = authorizer.require(sessionId, Permission.INVENTORY_ADJUST, "INVENTORY_RECEIPT", receipt.productId, reason)
        require(receipt.quantity > BigDecimal.ZERO && receipt.quantity.scale() <= 4) { "Receipt quantity must be greater than zero with at most four decimals." }
        return db.withTransaction {
            val product = db.catalogDao().productById(receipt.productId) ?: error("Product does not exist.")
            if (product.requiresLotExpiry) require(!receipt.lotNumber.isNullOrBlank() && receipt.expiryDate != null) { "Medicine receipts require lot/batch and expiry." }
            val lot = if (receipt.lotNumber.isNullOrBlank()) null else db.inventoryDao().lot(receipt.productId, receipt.lotNumber.trim(), receipt.expiryDate) ?: InventoryLotEntity(UUID.randomUUID().toString(), receipt.productId, receipt.lotNumber.trim(), receipt.expiryDate, "0", receipt.supplierReference).also { db.inventoryDao().insertLot(it) }
            val id = UUID.randomUUID().toString()
            db.inventoryDao().insertMovement(InventoryMovementEntity(id, receipt.productId, lot?.id, InventoryMovementType.RECEIPT, receipt.quantity.stripTrailingZeros().toPlainString(), product.unit, lot?.expiryDate, receipt.unitCostCentavos, receipt.supplierReference, actor.userId, clock(), reason))
            id
        }
    }
    suspend fun adjust(sessionId: String, productId: String, lotId: String?, quantityChange: BigDecimal, reason: String): String {
        val actor = authorizer.require(sessionId, Permission.INVENTORY_ADJUST, "INVENTORY_ADJUSTMENT", productId, reason)
        require(quantityChange.signum() != 0 && quantityChange.scale() <= 4) { "Adjustment quantity must be non-zero with at most four decimals." }
        return db.withTransaction {
            val product = db.catalogDao().productById(productId) ?: error("Product does not exist.")
            val resultingOnHand = onHand(productId) + quantityChange
            check(resultingOnHand >= BigDecimal.ZERO) { "Negative stock is blocked." }
            val id = UUID.randomUUID().toString()
            db.inventoryDao().insertMovement(InventoryMovementEntity(id, productId, lotId, if (quantityChange > BigDecimal.ZERO) InventoryMovementType.ADJUSTMENT_IN else InventoryMovementType.ADJUSTMENT_OUT, quantityChange.stripTrailingZeros().toPlainString(), product.unit, lotId?.let { db.inventoryDao().lotMovementRows(productId).firstOrNull { row -> row.lotId == it }?.expiryDate }, null, null, actor.userId, clock(), reason))
            id
        }
    }
}
