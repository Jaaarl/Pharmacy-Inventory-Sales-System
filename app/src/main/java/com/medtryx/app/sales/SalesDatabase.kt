package com.medtryx.app.sales

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.medtryx.app.auth.UserEntity
import com.medtryx.app.catalog.InventoryMovementEntity
import com.medtryx.app.catalog.ProductEntity
import com.medtryx.app.financial.CustomerBenefit
import com.medtryx.app.financial.Money
import com.medtryx.app.financial.TaxResult
import com.medtryx.app.shifts.CashierShiftEntity
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

enum class SaleStatus { FINALIZED }
enum class SettlementMethod { CASH, QR }
enum class BenefitIdType { SENIOR_CITIZEN_ID, PWD_ID }

@Entity(
    tableName = "sales",
    indices = [Index(value = ["humanTransactionId"], unique = true), Index(value = ["idempotencyKey"], unique = true), Index("createdAtUtcMillis"), Index("cashierUserId"), Index("shiftId")],
    foreignKeys = [
        ForeignKey(UserEntity::class, ["id"], ["cashierUserId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(CashierShiftEntity::class, ["id"], ["shiftId"], onDelete = ForeignKey.RESTRICT),
    ],
)
data class SaleEntity(
    @PrimaryKey val id: String,
    val humanTransactionId: String,
    val idempotencyKey: String,
    val status: SaleStatus,
    val cashierUserId: String,
    val cashierDisplayName: String,
    /** Null only on pre-F08 historical rows; new finalized sales always require a shift. */
    val shiftId: String?,
    val createdAtUtcMillis: Long,
    val businessDateManila: String,
    val customerBenefit: CustomerBenefit?,
    val customerNameEncrypted: String?,
    val benefitIdType: BenefitIdType?,
    val benefitIdNumberEncrypted: String?,
    val benefitIdLastFour: String?,
    val physicalIdChecked: Boolean,
    val settlementMethod: SettlementMethod,
    val qrReference: String?,
    val customerShowedQrSuccess: Boolean,
    val grossCentavos: Long,
    val vatableSalesCentavos: Long,
    val vatCentavos: Long,
    val vatExemptSalesCentavos: Long,
    val zeroRatedSalesCentavos: Long,
    val vatExemptionAdjustmentCentavos: Long,
    val statutoryDiscountCentavos: Long,
    val promotionalDiscountCentavos: Long,
    val amountDueCentavos: Long,
    val lineCount: Int,
)

@Entity(
    tableName = "sale_lines",
    indices = [Index(value = ["saleId", "lineNumber"], unique = true), Index("productId")],
    foreignKeys = [
        ForeignKey(SaleEntity::class, ["id"], ["saleId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(ProductEntity::class, ["id"], ["productId"], onDelete = ForeignKey.RESTRICT),
    ],
)
data class SaleLineEntity(
    @PrimaryKey val id: String,
    val saleId: String,
    val lineNumber: Int,
    val productId: String,
    val sku: String,
    val productName: String,
    val unit: String,
    val quantity: String,
    val unitPriceCentavos: Long,
    val unitCostCentavos: Long?,
    val taxResult: TaxResult,
    val grossCentavos: Long,
    val vatableSalesCentavos: Long,
    val vatCentavos: Long,
    val vatExemptSalesCentavos: Long,
    val zeroRatedSalesCentavos: Long,
    val vatExemptionAdjustmentCentavos: Long,
    val statutoryDiscountCentavos: Long,
    val promotionalDiscountCentavos: Long,
    val amountDueCentavos: Long,
    /** Versioned binary F03 snapshot encoded as base64. */
    val calculationSnapshot: String,
)

@Entity(
    tableName = "sale_line_allocations",
    indices = [Index("saleLineId"), Index(value = ["inventoryMovementId"], unique = true)],
    foreignKeys = [
        ForeignKey(SaleLineEntity::class, ["id"], ["saleLineId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(InventoryMovementEntity::class, ["id"], ["inventoryMovementId"], onDelete = ForeignKey.RESTRICT),
    ],
)
data class SaleLineAllocationEntity(
    @PrimaryKey val id: String,
    val saleLineId: String,
    val lotId: String?,
    val quantity: String,
    val inventoryMovementId: String,
)

@Entity(tableName = "transaction_sequences")
data class TransactionSequenceEntity(@PrimaryKey val businessDateManila: String, val lastSequence: Long)

@Entity(
    tableName = "rounding_rule_approvals",
    indices = [Index("approvedAtUtcMillis"), Index("approvedByUserId")],
    foreignKeys = [ForeignKey(UserEntity::class, ["id"], ["approvedByUserId"], onDelete = ForeignKey.RESTRICT)],
)
data class RoundingRuleApprovalEntity(
    @PrimaryKey val id: String,
    val version: String,
    val mode: String,
    val approvedByUserId: String,
    val approvedAtUtcMillis: Long,
    val reason: String,
)

class SalesConverters {
    @TypeConverter fun saleStatusToString(value: SaleStatus): String = value.name
    @TypeConverter fun stringToSaleStatus(value: String): SaleStatus = SaleStatus.valueOf(value)
    @TypeConverter fun settlementToString(value: SettlementMethod): String = value.name
    @TypeConverter fun stringToSettlement(value: String): SettlementMethod = SettlementMethod.valueOf(value)
    @TypeConverter fun benefitToString(value: CustomerBenefit?): String? = value?.name
    @TypeConverter fun stringToBenefit(value: String?): CustomerBenefit? = value?.let(CustomerBenefit::valueOf)
    @TypeConverter fun benefitIdToString(value: BenefitIdType?): String? = value?.name
    @TypeConverter fun stringToBenefitId(value: String?): BenefitIdType? = value?.let(BenefitIdType::valueOf)
    @TypeConverter fun taxResultToString(value: TaxResult): String = value.name
    @TypeConverter fun stringToTaxResult(value: String): TaxResult = TaxResult.valueOf(value)
}

@Dao
interface SalesDao {
    @Query("SELECT * FROM sales WHERE idempotencyKey = :key LIMIT 1") suspend fun saleByIdempotencyKey(key: String): SaleEntity?
    @Query("SELECT * FROM sales WHERE id = :id LIMIT 1") suspend fun saleById(id: String): SaleEntity?
    @Query("SELECT * FROM sales ORDER BY createdAtUtcMillis DESC, humanTransactionId DESC LIMIT :limit") suspend fun recentSales(limit: Int): List<SaleEntity>
    @Query("SELECT * FROM sale_lines WHERE saleId = :saleId ORDER BY lineNumber") suspend fun linesForSale(saleId: String): List<SaleLineEntity>
    @Query("SELECT * FROM sale_line_allocations WHERE saleLineId = :saleLineId ORDER BY id") suspend fun allocationsForLine(saleLineId: String): List<SaleLineAllocationEntity>
    @Query("SELECT lastSequence FROM transaction_sequences WHERE businessDateManila = :date LIMIT 1") suspend fun sequenceFor(date: String): Long?
    @Query("SELECT * FROM rounding_rule_approvals ORDER BY approvedAtUtcMillis DESC, id DESC LIMIT 1") suspend fun latestRoundingRule(): RoundingRuleApprovalEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSale(value: SaleEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSaleLines(values: List<SaleLineEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAllocations(values: List<SaleLineAllocationEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSequence(value: TransactionSequenceEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSequence(value: TransactionSequenceEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRoundingRule(value: RoundingRuleApprovalEntity)
}

data class CheckoutLineDraft(val productId: String, val quantity: BigDecimal, val qualifiedForBenefit: Boolean = false)
data class CheckoutDraft(
    val idempotencyKey: String,
    val lines: List<CheckoutLineDraft>,
    val customerBenefit: CustomerBenefit? = null,
    val customerName: String? = null,
    val benefitIdType: BenefitIdType? = null,
    val benefitIdNumber: String? = null,
    val physicalIdChecked: Boolean = false,
    val settlementMethod: SettlementMethod,
    val qrReference: String? = null,
    val customerShowedQrSuccess: Boolean = false,
)

data class CheckoutLinePreview(
    val productId: String,
    val sku: String,
    val productName: String,
    val unit: String,
    val quantity: BigDecimal,
    val unitCostCentavos: Long?,
    val taxClass: com.medtryx.app.catalog.TaxClass,
    val benefitEligibility: com.medtryx.app.catalog.BenefitEligibility,
    val calculation: com.medtryx.app.financial.CalculationSnapshot,
)

data class CheckoutPreview(val sourceDraft: CheckoutDraft, val lines: List<CheckoutLinePreview>, val totalDue: Money)
data class SaleSummary(
    val saleId: String,
    val humanTransactionId: String,
    val createdAtUtcMillis: Long,
    val businessDateManila: String,
    val cashierDisplayName: String,
    val customerBenefit: CustomerBenefit?,
    val maskedBenefitIdNumber: String?,
    val settlementMethod: SettlementMethod,
    val qrReference: String?,
    val customerShowedQrSuccess: Boolean,
    val amountDue: Money,
    val lines: List<CheckoutLinePreview>,
    val documentLabel: String = "INTERNAL SALES RECORD — NOT AN INVOICE",
)
