package com.medtryx.app.shifts

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.TypeConverter
import com.medtryx.app.auth.UserEntity

enum class ShiftStatus { OPEN, CLOSING_PENDING, CLOSED }
enum class ShiftCashMovementType { CASH_IN, CASH_OUT, CASH_REFUND }

@Entity(
    tableName = "cashier_shifts",
    indices = [Index("cashierUserId"), Index("deviceId"), Index("storeId"), Index("openedAtUtcMillis"), Index("status")],
    foreignKeys = [ForeignKey(UserEntity::class, ["id"], ["cashierUserId"], onDelete = ForeignKey.RESTRICT)],
)
data class CashierShiftEntity(
    @PrimaryKey val id: String,
    val storeId: String,
    val deviceId: String,
    val cashierUserId: String,
    val cashierDisplayName: String,
    val status: ShiftStatus,
    val openedAtUtcMillis: Long,
    val openingFloatCentavos: Long,
    val closeRequestedAtUtcMillis: Long? = null,
    val actualCashCentavos: Long? = null,
    val expectedCashCentavos: Long? = null,
    val varianceCentavos: Long? = null,
    /** Versioned `centavos:count` pairs; this contains no customer information. */
    val denominationCounts: String? = null,
    val varianceNote: String? = null,
    val closedAtUtcMillis: Long? = null,
    val supervisorUserId: String? = null,
    val supervisorAcknowledgedAtUtcMillis: Long? = null,
)

@Entity(
    tableName = "active_shift_claims",
    primaryKeys = ["storeId", "deviceId", "cashierUserId"],
    indices = [Index(value = ["shiftId"], unique = true)],
    foreignKeys = [ForeignKey(CashierShiftEntity::class, ["id"], ["shiftId"], onDelete = ForeignKey.CASCADE)],
)
data class ActiveShiftClaimEntity(
    val storeId: String,
    val deviceId: String,
    val cashierUserId: String,
    val shiftId: String,
)

@Entity(
    tableName = "shift_cash_movements",
    indices = [Index("shiftId"), Index("occurredAtUtcMillis"), Index("recordedByUserId")],
    foreignKeys = [
        ForeignKey(CashierShiftEntity::class, ["id"], ["shiftId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(UserEntity::class, ["id"], ["recordedByUserId"], onDelete = ForeignKey.RESTRICT),
    ],
)
data class ShiftCashMovementEntity(
    @PrimaryKey val id: String,
    val shiftId: String,
    val type: ShiftCashMovementType,
    val amountCentavos: Long,
    val occurredAtUtcMillis: Long,
    val recordedByUserId: String,
    val reason: String,
    val sourceReference: String? = null,
)

@Entity(
    tableName = "shift_variance_policies",
    indices = [Index("approvedAtUtcMillis"), Index("approvedByUserId")],
    foreignKeys = [ForeignKey(UserEntity::class, ["id"], ["approvedByUserId"], onDelete = ForeignKey.RESTRICT)],
)
data class ShiftVariancePolicyEntity(
    @PrimaryKey val id: String,
    val version: String,
    val thresholdCentavos: Long,
    val approvedByUserId: String,
    val approvedAtUtcMillis: Long,
    val reason: String,
)

@Entity(
    tableName = "shift_adjustments",
    indices = [Index("shiftId"), Index("createdAtUtcMillis"), Index("createdByUserId")],
    foreignKeys = [
        ForeignKey(CashierShiftEntity::class, ["id"], ["shiftId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(UserEntity::class, ["id"], ["createdByUserId"], onDelete = ForeignKey.RESTRICT),
    ],
)
data class ShiftAdjustmentEntity(
    @PrimaryKey val id: String,
    val shiftId: String,
    /** Signed adjustment retained separately from the immutable cash count and variance. */
    val amountCentavos: Long,
    val createdAtUtcMillis: Long,
    val createdByUserId: String,
    val reason: String,
)

class ShiftConverters {
    @TypeConverter fun statusToString(value: ShiftStatus): String = value.name
    @TypeConverter fun stringToStatus(value: String): ShiftStatus = ShiftStatus.valueOf(value)
    @TypeConverter fun movementTypeToString(value: ShiftCashMovementType): String = value.name
    @TypeConverter fun stringToMovementType(value: String): ShiftCashMovementType = ShiftCashMovementType.valueOf(value)
}

@Dao
interface ShiftDao {
    @Query("SELECT s.* FROM cashier_shifts s INNER JOIN active_shift_claims c ON c.shiftId = s.id WHERE c.storeId = :storeId AND c.deviceId = :deviceId AND c.cashierUserId = :cashierUserId LIMIT 1")
    suspend fun activeShift(storeId: String, deviceId: String, cashierUserId: String): CashierShiftEntity?

    @Query("SELECT s.* FROM cashier_shifts s INNER JOIN active_shift_claims c ON c.shiftId = s.id WHERE c.storeId = :storeId AND c.deviceId = :deviceId AND c.cashierUserId = :cashierUserId AND s.status = 'OPEN' LIMIT 1")
    suspend fun openShift(storeId: String, deviceId: String, cashierUserId: String): CashierShiftEntity?

    @Query("SELECT * FROM cashier_shifts WHERE id = :shiftId LIMIT 1") suspend fun shiftById(shiftId: String): CashierShiftEntity?
    @Query("SELECT * FROM cashier_shifts WHERE cashierUserId = :cashierUserId AND deviceId = :deviceId ORDER BY openedAtUtcMillis DESC LIMIT :limit") suspend fun recentForCashierDevice(cashierUserId: String, deviceId: String, limit: Int): List<CashierShiftEntity>
    @Query("SELECT * FROM cashier_shifts WHERE storeId = :storeId ORDER BY openedAtUtcMillis DESC LIMIT :limit") suspend fun recentStoreShifts(storeId: String, limit: Int): List<CashierShiftEntity>
    @Query("SELECT * FROM cashier_shifts WHERE status = 'CLOSING_PENDING' ORDER BY closeRequestedAtUtcMillis, id") suspend fun pendingClosures(): List<CashierShiftEntity>
    @Query("SELECT * FROM shift_cash_movements WHERE shiftId = :shiftId ORDER BY occurredAtUtcMillis, id") suspend fun movements(shiftId: String): List<ShiftCashMovementEntity>
    @Query("SELECT * FROM shift_adjustments WHERE shiftId = :shiftId ORDER BY createdAtUtcMillis, id") suspend fun adjustments(shiftId: String): List<ShiftAdjustmentEntity>
    @Query("SELECT * FROM sales WHERE shiftId = :shiftId ORDER BY createdAtUtcMillis, id") suspend fun salesForShift(shiftId: String): List<com.medtryx.app.sales.SaleEntity>
    @Query("SELECT * FROM shift_variance_policies ORDER BY approvedAtUtcMillis DESC, id DESC LIMIT 1") suspend fun latestVariancePolicy(): ShiftVariancePolicyEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertShift(value: CashierShiftEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertClaim(value: ActiveShiftClaimEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertMovement(value: ShiftCashMovementEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertPolicy(value: ShiftVariancePolicyEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAdjustment(value: ShiftAdjustmentEntity)
    @Query("DELETE FROM active_shift_claims WHERE shiftId = :shiftId") suspend fun releaseClaim(shiftId: String)
    @Query("UPDATE cashier_shifts SET status = :status, closeRequestedAtUtcMillis = :requestedAt, actualCashCentavos = :actual, expectedCashCentavos = :expected, varianceCentavos = :variance, denominationCounts = :denominations, varianceNote = :note, closedAtUtcMillis = :closedAt, supervisorUserId = :supervisorId, supervisorAcknowledgedAtUtcMillis = :supervisorAt WHERE id = :shiftId AND status IN ('OPEN', 'CLOSING_PENDING')")
    suspend fun recordClose(shiftId: String, status: ShiftStatus, requestedAt: Long?, actual: Long?, expected: Long?, variance: Long?, denominations: String?, note: String?, closedAt: Long?, supervisorId: String? = null, supervisorAt: Long? = null): Int
    @Query("UPDATE cashier_shifts SET status = 'CLOSED', closedAtUtcMillis = :closedAt, supervisorUserId = :supervisorId, supervisorAcknowledgedAtUtcMillis = :supervisorAt WHERE id = :shiftId AND status = 'CLOSING_PENDING'")
    suspend fun approveClose(shiftId: String, closedAt: Long, supervisorId: String, supervisorAt: Long): Int
}

data class ShiftDashboard(
    val shift: CashierShiftEntity?,
    val grossSalesCentavos: Long,
    val netSalesCentavos: Long,
    val cashSalesCentavos: Long,
    val qrSalesCentavos: Long,
    val cashRefundsCentavos: Long,
    val cashInCentavos: Long,
    val cashOutCentavos: Long,
    val expectedCashCentavos: Long?,
    val movements: List<ShiftCashMovementEntity>,
    val adjustments: List<ShiftAdjustmentEntity>,
)
