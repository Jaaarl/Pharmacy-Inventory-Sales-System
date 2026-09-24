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
import java.time.ZoneId
import java.util.UUID

enum class InventoryMovementType { OPENING_BALANCE, RECEIPT, SALE, SALE_REVERSAL, RETURN_SELLABLE, RETURN_DAMAGED, ADJUSTMENT_IN, ADJUSTMENT_OUT, EXPIRED, BUNDLE_ASSEMBLY_IN, BUNDLE_ASSEMBLY_OUT }

@Entity(tableName = "inventory_movements", indices = [Index("productId"), Index("lotId"), Index("occurredAtUtcMillis")], foreignKeys = [ForeignKey(ProductEntity::class, ["id"], ["productId"], onDelete = ForeignKey.RESTRICT), ForeignKey(InventoryLotEntity::class, ["id"], ["lotId"], onDelete = ForeignKey.RESTRICT)])
data class InventoryMovementEntity(
    @PrimaryKey val id: String,
    val productId: String,
    val lotId: String?,
    val type: InventoryMovementType,
    /** Signed SKU base-unit quantity. Outgoing movement types use negative values. */ val quantity: String,
    val unit: String,
    val expiryDate: String?,
    val costCentavos: Long?,
    val sourceReference: String?,
    val actorUserId: String,
    val occurredAtUtcMillis: Long,
    val reason: String,
)

data class LotBalance(val lotId: String, val lotNumber: String, val expiryDate: String?, val onHand: BigDecimal, val unitCostCentavos: Long?)
data class LotAllocation(val lotId: String?, val quantity: BigDecimal)
data class StockReceiptDraft(val productId: String, val quantity: BigDecimal, val lotNumber: String? = null, val expiryDate: String? = null, val unitCostCentavos: Long? = null, val supplierReference: String? = null, val receivedInPacks: Boolean = false)
data class InventoryProductStatus(val productId: String, val sku: String, val name: String, val unit: String, val onHand: BigDecimal, val reorderLevel: BigDecimal, val lots: List<LotBalance>, val recentMovements: List<InventoryMovementEntity>, val isActive: Boolean, val requiresLotExpiry: Boolean, val packSize: BigDecimal?) {
    val isLowStock: Boolean get() = onHand <= reorderLevel
}
enum class InventoryAlertType { LOW_STOCK, EXPIRED, NEAR_EXPIRY }
data class InventoryAlert(val type: InventoryAlertType, val productId: String, val sku: String, val productName: String, val unit: String, val quantity: BigDecimal, val lotNumber: String? = null, val expiryDate: LocalDate? = null)

@Dao interface InventoryDao {
    @Query("SELECT quantity FROM inventory_movements WHERE productId = :productId") suspend fun quantities(productId: String): List<String>
    @Query("SELECT l.id AS lotId, l.lotNumber AS lotNumber, l.expiryDate AS expiryDate, m.quantity AS quantity FROM inventory_lots l LEFT JOIN inventory_movements m ON m.lotId = l.id WHERE l.productId = :productId") suspend fun lotMovementRows(productId: String): List<LotMovementRow>
    @Query("SELECT * FROM inventory_lots WHERE productId = :productId AND lotNumber = :lotNumber AND ((expiryDate IS NULL AND :expiryDate IS NULL) OR expiryDate = :expiryDate) LIMIT 1") suspend fun lot(productId: String, lotNumber: String, expiryDate: String?): InventoryLotEntity?
    @Query("SELECT * FROM inventory_lots WHERE id = :lotId LIMIT 1") suspend fun lotById(lotId: String): InventoryLotEntity?
    @Query("SELECT * FROM inventory_movements WHERE productId = :productId ORDER BY occurredAtUtcMillis, id") suspend fun movements(productId: String): List<InventoryMovementEntity>
    @Query("SELECT * FROM inventory_movements WHERE productId = :productId ORDER BY occurredAtUtcMillis DESC, id DESC LIMIT 20") suspend fun recentMovements(productId: String): List<InventoryMovementEntity>
    @Query("SELECT lotId, quantity, costCentavos FROM inventory_movements WHERE productId = :productId AND lotId IS NOT NULL") suspend fun lotCostRows(productId: String): List<LotCostRow>
    @Query("SELECT * FROM products ORDER BY active DESC, name COLLATE NOCASE, sku") suspend fun products(): List<ProductEntity>
    @Insert suspend fun insertLot(value: InventoryLotEntity)
    @Insert suspend fun insertMovement(value: InventoryMovementEntity)
}
data class LotMovementRow(val lotId: String, val lotNumber: String, val expiryDate: String?, val quantity: String?)
data class LotCostRow(val lotId: String?, val quantity: String, val costCentavos: Long?)

class InventoryService(private val db: MedtryxDatabase, private val authorizer: ProtectedActionAuthorizer, private val clock: () -> Long = System::currentTimeMillis, private val today: () -> LocalDate = { LocalDate.now(ZoneId.of("Asia/Manila")) }) {
    suspend fun onHand(productId: String): BigDecimal = db.inventoryDao().quantities(productId).fold(BigDecimal.ZERO) { sum, quantity -> sum + BigDecimal(quantity) }.normalized()

    suspend fun availableLots(productId: String): List<LotBalance> {
        val costsByLot = db.inventoryDao().lotCostRows(productId).groupBy { it.lotId }.mapValues { (_, rows) ->
            val costedPositiveQuantity = rows.filter { it.costCentavos != null && BigDecimal(it.quantity) > BigDecimal.ZERO }
            val quantity = costedPositiveQuantity.fold(BigDecimal.ZERO) { total, row -> total + BigDecimal(row.quantity) }
            if (quantity.signum() == 0) null else costedPositiveQuantity.fold(BigDecimal.ZERO) { total, row -> total + BigDecimal(row.quantity).multiply(BigDecimal(row.costCentavos!!)) }
                .divide(quantity, 0, java.math.RoundingMode.HALF_UP).longValueExact()
        }
        return db.inventoryDao().lotMovementRows(productId).groupBy { it.lotId }.map { (id, rows) ->
            LotBalance(id, rows.first().lotNumber, rows.first().expiryDate, rows.mapNotNull { it.quantity }.fold(BigDecimal.ZERO) { sum, quantity -> sum + BigDecimal(quantity) }.normalized(), costsByLot[id])
        }.filter { it.onHand > BigDecimal.ZERO }.sortedWith(compareBy<LotBalance> { it.expiryDate == null }.thenBy { it.expiryDate }.thenBy { it.lotId })
    }

    suspend fun status(nearExpiryThrough: LocalDate): List<InventoryProductStatus> {
        require(!nearExpiryThrough.isBefore(today())) { "Near-expiry cutoff cannot be before today." }
        return db.inventoryDao().products().map { product ->
            InventoryProductStatus(product.id, product.sku, product.name, product.unit, onHand(product.id), BigDecimal(product.reorderLevel), availableLots(product.id), db.inventoryDao().recentMovements(product.id), product.active, product.requiresLotExpiry, product.packSize?.let(::BigDecimal))
        }
    }

    suspend fun alerts(nearExpiryThrough: LocalDate): List<InventoryAlert> = status(nearExpiryThrough).flatMap { product ->
        buildList {
            if (product.isLowStock) add(InventoryAlert(InventoryAlertType.LOW_STOCK, product.productId, product.sku, product.name, product.unit, product.onHand))
            product.lots.forEach { lot ->
                val expiry = lot.expiryDate?.let(LocalDate::parse) ?: return@forEach
                when {
                    expiry.isBefore(today()) -> add(InventoryAlert(InventoryAlertType.EXPIRED, product.productId, product.sku, product.name, product.unit, lot.onHand, lot.lotNumber, expiry))
                    !expiry.isAfter(nearExpiryThrough) -> add(InventoryAlert(InventoryAlertType.NEAR_EXPIRY, product.productId, product.sku, product.name, product.unit, lot.onHand, lot.lotNumber, expiry))
                }
            }
        }
    }.sortedWith(compareBy<InventoryAlert> { it.type.ordinal }.thenBy { it.expiryDate }.thenBy { it.sku })

    /** Returns an allocation plan only; F05 must recheck and persist deductions in its sale transaction. */
    suspend fun allocateEarliestExpiry(productId: String, quantity: BigDecimal): List<LotAllocation> {
        require(quantity > BigDecimal.ZERO && quantity.scale() <= 4) { "Quantity must be positive with at most four decimals." }
        val product = db.catalogDao().productById(productId) ?: error("Product does not exist.")
        if (!product.requiresLotExpiry) {
            var remaining = quantity
            val lots = availableLots(productId)
            val untracked = (onHand(productId) - lots.fold(BigDecimal.ZERO) { total, lot -> total + lot.onHand }).max(BigDecimal.ZERO)
            val allocation = buildList {
                for (lot in lots.filter { it.expiryDate == null || !LocalDate.parse(it.expiryDate).isBefore(today()) }) {
                    if (remaining <= BigDecimal.ZERO) break
                    val allocated = lot.onHand.min(remaining)
                    add(LotAllocation(lot.lotId, allocated.normalized())); remaining -= allocated
                }
                if (remaining > BigDecimal.ZERO && untracked > BigDecimal.ZERO) {
                    val allocated = untracked.min(remaining)
                    add(LotAllocation(null, allocated.normalized())); remaining -= allocated
                }
            }
            check(remaining == BigDecimal.ZERO) { "Insufficient unexpired stock." }
            return allocation
        }
        var remaining = quantity
        val allocation = buildList {
            for (lot in availableLots(productId).filter { it.expiryDate != null && !LocalDate.parse(it.expiryDate).isBefore(today()) }) {
                if (remaining <= BigDecimal.ZERO) break
                val allocated = lot.onHand.min(remaining)
                add(LotAllocation(lot.lotId, allocated.normalized())); remaining -= allocated
            }
        }
        check(remaining == BigDecimal.ZERO) { "Insufficient unexpired stock." }
        return allocation
    }

    suspend fun receive(sessionId: String, receipt: StockReceiptDraft, reason: String): String {
        requireReason(reason)
        require(receipt.quantity > BigDecimal.ZERO && receipt.quantity.scale() <= 4) { "Receipt quantity must be greater than zero with at most four decimals." }
        require(receipt.unitCostCentavos == null || receipt.unitCostCentavos >= 0) { "Unit cost cannot be negative." }
        val actor = authorizer.require(sessionId, Permission.INVENTORY_ADJUST, "INVENTORY_RECEIPT", receipt.productId, reason)
        return db.withTransaction {
            val product = db.catalogDao().productById(receipt.productId) ?: error("Product does not exist.")
            val quantity = if (receipt.receivedInPacks) {
                val unitsPerPack = product.packSize?.let(::BigDecimal) ?: error("This SKU has no pack conversion configured.")
                val converted = receipt.quantity.multiply(unitsPerPack)
                require(converted > BigDecimal.ZERO && converted.scale() <= 4) { "Pack conversion must produce a positive base-unit quantity with at most four decimals; no rounding is applied." }
                converted.normalized()
            } else receipt.quantity.normalized()
            val lotNumber = receipt.lotNumber?.trim()?.takeIf(String::isNotEmpty)
            val expiry = receipt.expiryDate?.let(::parseDate)
            if (product.requiresLotExpiry) {
                require(lotNumber != null && expiry != null) { "Medicine receipts require lot/batch and expiry." }
                require(expiry.isAfter(today())) { "Expired stock cannot be received." }
            } else if (expiry != null) {
                require(lotNumber != null) { "An expiry date requires a lot/batch number." }
            }
            if (expiry != null) require(expiry.isAfter(today())) { "Expired stock cannot be received." }
            val lot = if (lotNumber == null) null else db.inventoryDao().lot(product.id, lotNumber, expiry?.toString()) ?: InventoryLotEntity(UUID.randomUUID().toString(), product.id, lotNumber, expiry?.toString(), "0", receipt.supplierReference?.trim()?.ifEmpty { null }).also { db.inventoryDao().insertLot(it) }
            require(lot == null || lot.productId == product.id) { "Lot does not belong to this product." }
            val id = UUID.randomUUID().toString()
            val oldOnHand = onHand(product.id)
            val cost = receipt.unitCostCentavos ?: db.catalogDao().latestPrice(product.id)?.costCentavos
            db.inventoryDao().insertMovement(InventoryMovementEntity(id, product.id, lot?.id, InventoryMovementType.RECEIPT, quantity.toPlainString(), product.unit, lot?.expiryDate, cost, receipt.supplierReference?.trim()?.ifEmpty { null }, actor.userId, clock(), reason.trim()))
            val conversion = if (receipt.receivedInPacks) "input=${receipt.quantity} packs × ${product.packSize} ${product.unit} = $quantity ${product.unit}; " else "input=$quantity ${product.unit}; "
            recordInventoryAudit(actor, "INVENTORY_RECEIPT_RECORDED", product.id, reason, "onHand=$oldOnHand", "movement=$id; onHand=${oldOnHand + quantity}; ${conversion}lot=${lot?.lotNumber}; expiry=${lot?.expiryDate}; costCentavos=$cost; supplier=${receipt.supplierReference?.trim()?.ifEmpty { null }}")
            id
        }
    }

    suspend fun adjust(sessionId: String, productId: String, lotId: String?, quantityChange: BigDecimal, reason: String): String {
        requireReason(reason)
        require(quantityChange.signum() != 0 && quantityChange.scale() <= 4) { "Adjustment quantity must be non-zero with at most four decimals." }
        val actor = authorizer.require(sessionId, Permission.INVENTORY_ADJUST, "INVENTORY_ADJUSTMENT", productId, reason)
        return db.withTransaction {
            val product = db.catalogDao().productById(productId) ?: error("Product does not exist.")
            val lot = lotId?.let { db.inventoryDao().lotById(it) ?: error("Lot does not exist.") }
            require(lot == null || lot.productId == productId) { "Lot does not belong to this product." }
            if (product.requiresLotExpiry) require(lot != null) { "Medicine inventory adjustments require a lot/batch." }
            if (lot?.expiryDate?.let(::parseDate)?.isBefore(today()) == true && quantityChange > BigDecimal.ZERO) throw IllegalArgumentException("Expired stock cannot be adjusted into inventory.")
            if (quantityChange < BigDecimal.ZERO) {
                val oldOnHand = onHand(productId)
                check(oldOnHand + quantityChange >= BigDecimal.ZERO) { "Negative stock is blocked." }
                if (lot != null) check(lotBalance(productId, lot.id) + quantityChange >= BigDecimal.ZERO) { "Adjustment exceeds the selected lot's stock." }
                else {
                    val totalLotStock = availableLots(productId).fold(BigDecimal.ZERO) { total, balance -> total + balance.onHand }
                    val untrackedStock = (oldOnHand - totalLotStock).max(BigDecimal.ZERO)
                    check(untrackedStock + quantityChange >= BigDecimal.ZERO) { "Choose a lot when reducing lot-tracked stock." }
                }
                if (lot?.expiryDate?.let(::parseDate)?.isBefore(today()) == true) throw IllegalArgumentException("Expired stock must be removed through expired-stock disposal.")
            }
            val id = UUID.randomUUID().toString(); val quantity = quantityChange.normalized(); val oldOnHand = onHand(productId)
            val type = if (quantity.signum() > 0) InventoryMovementType.ADJUSTMENT_IN else InventoryMovementType.ADJUSTMENT_OUT
            val cost = lot?.let { availableLots(productId).firstOrNull { balance -> balance.lotId == it.id }?.unitCostCentavos } ?: db.catalogDao().latestPrice(productId)?.costCentavos
            db.inventoryDao().insertMovement(InventoryMovementEntity(id, productId, lot?.id, type, quantity.toPlainString(), product.unit, lot?.expiryDate, cost, null, actor.userId, clock(), reason.trim()))
            recordInventoryAudit(actor, "INVENTORY_ADJUSTMENT_RECORDED", productId, reason, "onHand=$oldOnHand", "movement=$id; type=$type; onHand=${oldOnHand + quantity}; quantity=$quantity ${product.unit}; lot=${lot?.lotNumber}; costCentavos=$cost")
            id
        }
    }

    suspend fun disposeExpired(sessionId: String, lotId: String, quantity: BigDecimal, reason: String): String {
        requireReason(reason)
        require(quantity > BigDecimal.ZERO && quantity.scale() <= 4) { "Disposal quantity must be positive with at most four decimals." }
        val actor = authorizer.require(sessionId, Permission.INVENTORY_ADJUST, "EXPIRED_STOCK_DISPOSAL", lotId, reason)
        return db.withTransaction {
            val lot = db.inventoryDao().lotById(lotId) ?: error("Lot does not exist.")
            val product = db.catalogDao().productById(lot.productId) ?: error("Product does not exist.")
            val expiry = lot.expiryDate?.let(::parseDate) ?: error("A lot expiry date is required for expired-stock disposal.")
            require(expiry.isBefore(today())) { "Only expired lots can be disposed as expired stock." }
            val balance = lotBalance(product.id, lot.id)
            check(balance >= quantity) { "Disposal exceeds the selected lot's stock." }
            val id = UUID.randomUUID().toString(); val signedQuantity = quantity.negate().normalized()
            val cost = availableLots(product.id).firstOrNull { it.lotId == lot.id }?.unitCostCentavos ?: db.catalogDao().latestPrice(product.id)?.costCentavos
            db.inventoryDao().insertMovement(InventoryMovementEntity(id, product.id, lot.id, InventoryMovementType.EXPIRED, signedQuantity.toPlainString(), product.unit, lot.expiryDate, cost, null, actor.userId, clock(), reason.trim()))
            recordInventoryAudit(actor, "EXPIRED_STOCK_DISPOSED", product.id, reason, "lot=${lot.lotNumber}; onHand=$balance", "movement=$id; disposed=$quantity ${product.unit}; remaining=${balance - quantity}")
            id
        }
    }

    private suspend fun lotBalance(productId: String, lotId: String): BigDecimal = db.inventoryDao().lotMovementRows(productId).filter { it.lotId == lotId }.mapNotNull { it.quantity }.fold(BigDecimal.ZERO) { total, row -> total + BigDecimal(row) }
    private suspend fun recordInventoryAudit(actor: com.medtryx.app.auth.AuthenticatedSession, action: String, reference: String, reason: String, oldValue: String?, newValue: String?) =
        authorizer.recordApplicationAudit(actor.sessionId, action, reference, reason, oldValue, newValue)
    private fun parseDate(value: String): LocalDate = runCatching { LocalDate.parse(value) }.getOrElse { throw IllegalArgumentException("Expiry date must use YYYY-MM-DD.") }
    private fun requireReason(reason: String) = require(reason.isNotBlank()) { "A reason is required." }
    private fun BigDecimal.normalized(): BigDecimal = stripTrailingZeros().let { if (it.scale() < 0) setScale(0) else it }
}
