package com.medtryx.app.auth

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.medtryx.app.catalog.*

@Entity(tableName = "users", indices = [Index(value = ["username"], unique = true)])
data class UserEntity(
    @PrimaryKey val id: String,
    val username: String,
    val displayName: String,
    val role: Role,
    val isActive: Boolean,
    val createdAtUtcMillis: Long,
    val updatedAtUtcMillis: Long,
)

@Entity(
    tableName = "credentials",
    foreignKeys = [ForeignKey(UserEntity::class, ["id"], ["userId"], onDelete = ForeignKey.CASCADE)],
)
data class CredentialEntity(
    @PrimaryKey val userId: String,
    val hash: ByteArray,
    val salt: ByteArray,
    val iterations: Int,
    val algorithm: String,
    val changedAtUtcMillis: Long,
)

@Entity(
    tableName = "user_permission_grants",
    primaryKeys = ["userId", "permission"],
    foreignKeys = [ForeignKey(UserEntity::class, ["id"], ["userId"], onDelete = ForeignKey.CASCADE)],
)
data class UserPermissionGrantEntity(val userId: String, val permission: Permission)

@Entity(
    tableName = "sessions",
    indices = [Index("userId"), Index("expiresAtUtcMillis")],
    foreignKeys = [ForeignKey(UserEntity::class, ["id"], ["userId"], onDelete = ForeignKey.CASCADE)],
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val deviceId: String,
    val createdAtUtcMillis: Long,
    val lastActivityAtUtcMillis: Long,
    val expiresAtUtcMillis: Long,
    val lockedAtUtcMillis: Long?,
    val revokedAtUtcMillis: Long?,
)

@Entity(tableName = "authentication_attempts", indices = [Index("username"), Index("attemptedAtUtcMillis")])
data class AuthenticationAttemptEntity(
    @PrimaryKey val id: String,
    val username: String,
    val deviceId: String,
    val attemptedAtUtcMillis: Long,
    val result: AuthenticationAttemptResult,
)

enum class AuthenticationAttemptResult { SUCCESS, INVALID_CREDENTIALS, THROTTLED, DISABLED_ACCOUNT }
enum class AuditResult { SUCCESS, REJECTED }

@Entity(tableName = "audit_events", indices = [Index("occurredAtUtcMillis"), Index("actorUserId")])
data class AuditEventEntity(
    @PrimaryKey val id: String,
    val actorUserId: String?,
    val occurredAtUtcMillis: Long,
    val deviceId: String,
    /** Session context is retained without storing a credential or token. */
    val sessionId: String?,
    val action: String,
    val result: AuditResult,
    val entityReference: String?,
    val reason: String?,
    val oldValue: String?,
    val newValue: String?,
)

class AuthConverters {
    @TypeConverter fun roleToString(value: Role): String = value.name
    @TypeConverter fun stringToRole(value: String): Role = Role.valueOf(value)
    @TypeConverter fun permissionToString(value: Permission): String = value.name
    @TypeConverter fun stringToPermission(value: String): Permission = Permission.valueOf(value)
    @TypeConverter fun attemptResultToString(value: AuthenticationAttemptResult): String = value.name
    @TypeConverter fun stringToAttemptResult(value: String): AuthenticationAttemptResult = AuthenticationAttemptResult.valueOf(value)
    @TypeConverter fun auditResultToString(value: AuditResult): String = value.name
    @TypeConverter fun stringToAuditResult(value: String): AuditResult = AuditResult.valueOf(value)
    @TypeConverter fun taxClassToString(value: TaxClass): String = value.name
    @TypeConverter fun stringToTaxClass(value: String): TaxClass = TaxClass.valueOf(value)
    @TypeConverter fun eligibilityToString(value: BenefitEligibility): String = value.name
    @TypeConverter fun stringToEligibility(value: String): BenefitEligibility = BenefitEligibility.valueOf(value)
    @TypeConverter fun inventoryMovementTypeToString(value: InventoryMovementType): String = value.name
    @TypeConverter fun stringToInventoryMovementType(value: String): InventoryMovementType = InventoryMovementType.valueOf(value)
}

@Dao
interface AuthDao {
    @Query("SELECT COUNT(*) FROM users") suspend fun userCount(): Int
    @Query("SELECT * FROM users WHERE username = :username LIMIT 1") suspend fun findUser(username: String): UserEntity?
    @Query("SELECT * FROM users WHERE id = :userId LIMIT 1") suspend fun findUserById(userId: String): UserEntity?
    @Query("SELECT * FROM credentials WHERE userId = :userId LIMIT 1") suspend fun credentialFor(userId: String): CredentialEntity?
    @Query("SELECT permission FROM user_permission_grants WHERE userId = :userId") suspend fun permissionGrantsFor(userId: String): List<Permission>
    @Query("SELECT COUNT(*) FROM authentication_attempts WHERE username = :username AND attemptedAtUtcMillis >= :since AND result = 'INVALID_CREDENTIALS'") suspend fun failuresSince(username: String, since: Long): Int
    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1") suspend fun session(sessionId: String): SessionEntity?
    @Query("SELECT * FROM audit_events ORDER BY occurredAtUtcMillis") suspend fun auditEvents(): List<AuditEventEntity>
    @Insert suspend fun insertUser(user: UserEntity)
    @Insert suspend fun insertCredential(credential: CredentialEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPermissionGrants(grants: List<UserPermissionGrantEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSession(session: SessionEntity)
    @Insert suspend fun insertAttempt(attempt: AuthenticationAttemptEntity)
    @Insert suspend fun insertAudit(event: AuditEventEntity)
    @Query("UPDATE sessions SET revokedAtUtcMillis = :at WHERE userId = :userId AND revokedAtUtcMillis IS NULL") suspend fun revokeSessionsForUser(userId: String, at: Long)
    @Query("UPDATE sessions SET revokedAtUtcMillis = :at WHERE id = :sessionId AND revokedAtUtcMillis IS NULL") suspend fun revokeSession(sessionId: String, at: Long)
    @Query("UPDATE sessions SET revokedAtUtcMillis = :at WHERE deviceId = :deviceId AND revokedAtUtcMillis IS NULL") suspend fun revokeSessionsForDevice(deviceId: String, at: Long)
    @Query("UPDATE sessions SET lastActivityAtUtcMillis = :at, lockedAtUtcMillis = NULL WHERE id = :sessionId") suspend fun touchSession(sessionId: String, at: Long)
    @Query("UPDATE sessions SET lockedAtUtcMillis = :at WHERE id = :sessionId") suspend fun lockSession(sessionId: String, at: Long)
    @Query("UPDATE users SET isActive = :active, updatedAtUtcMillis = :at WHERE id = :userId") suspend fun setUserActive(userId: String, active: Boolean, at: Long)
    @Query("UPDATE users SET role = :role, updatedAtUtcMillis = :at WHERE id = :userId") suspend fun setRole(userId: String, role: Role, at: Long)
    @Query("DELETE FROM user_permission_grants WHERE userId = :userId") suspend fun clearPermissionGrants(userId: String)
    @Query("UPDATE credentials SET hash = :hash, salt = :salt, iterations = :iterations, algorithm = :algorithm, changedAtUtcMillis = :at WHERE userId = :userId") suspend fun replaceCredential(userId: String, hash: ByteArray, salt: ByteArray, iterations: Int, algorithm: String, at: Long)
}

@Database(
    entities = [UserEntity::class, CredentialEntity::class, UserPermissionGrantEntity::class, SessionEntity::class, AuthenticationAttemptEntity::class, AuditEventEntity::class, ProductEntity::class, ProductBarcodeEntity::class, ProductPriceVersionEntity::class, TaxClassVersionEntity::class, BenefitRuleVersionEntity::class, InventoryLotEntity::class, InventoryMovementEntity::class, ImportManifestEntity::class, ImportRowResultEntity::class],
    version = 4,
    exportSchema = true,
)
@TypeConverters(AuthConverters::class)
abstract class MedtryxDatabase : RoomDatabase() {
    abstract fun authDao(): AuthDao
    abstract fun catalogDao(): CatalogDao
    abstract fun inventoryDao(): InventoryDao

    companion object {
        /** Additive migration: audit history remains append-only. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE audit_events ADD COLUMN sessionId TEXT")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS products (id TEXT NOT NULL PRIMARY KEY, sku TEXT NOT NULL, name TEXT NOT NULL, genericName TEXT, brand TEXT, strength TEXT, dosageForm TEXT, unit TEXT NOT NULL, packSize TEXT, active INTEGER NOT NULL, reorderLevel TEXT NOT NULL, requiresLotExpiry INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_products_sku ON products(sku)")
                db.execSQL("CREATE TABLE IF NOT EXISTS product_barcodes (productId TEXT NOT NULL, barcode TEXT NOT NULL, PRIMARY KEY(productId, barcode), FOREIGN KEY(productId) REFERENCES products(id) ON DELETE CASCADE)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_product_barcodes_barcode ON product_barcodes(barcode)")
                db.execSQL("CREATE TABLE IF NOT EXISTS product_price_versions (id TEXT NOT NULL PRIMARY KEY, productId TEXT NOT NULL, sellingCentavos INTEGER NOT NULL, costCentavos INTEGER, effectiveFrom TEXT NOT NULL, effectiveTo TEXT, approvedBy TEXT NOT NULL, reason TEXT NOT NULL, FOREIGN KEY(productId) REFERENCES products(id) ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS tax_class_versions (id TEXT NOT NULL PRIMARY KEY, productId TEXT NOT NULL, taxClass TEXT NOT NULL, source TEXT NOT NULL, effectiveFrom TEXT NOT NULL, effectiveTo TEXT, approvedBy TEXT NOT NULL, reason TEXT NOT NULL, FOREIGN KEY(productId) REFERENCES products(id) ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS benefit_rule_versions (id TEXT NOT NULL PRIMARY KEY, productId TEXT NOT NULL, eligibility TEXT NOT NULL, effectiveFrom TEXT NOT NULL, effectiveTo TEXT, approvedBy TEXT NOT NULL, reason TEXT NOT NULL, FOREIGN KEY(productId) REFERENCES products(id) ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS inventory_lots (id TEXT NOT NULL PRIMARY KEY, productId TEXT NOT NULL, lotNumber TEXT NOT NULL, expiryDate TEXT, openingQuantity TEXT NOT NULL, supplierReference TEXT, FOREIGN KEY(productId) REFERENCES products(id) ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS import_manifests (id TEXT NOT NULL PRIMARY KEY, checksum TEXT NOT NULL, actorUserId TEXT NOT NULL, createdAt INTEGER NOT NULL, acceptedCount INTEGER NOT NULL, rejectedCount INTEGER NOT NULL, subsetCommitted INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS import_row_results (id TEXT NOT NULL PRIMARY KEY, manifestId TEXT NOT NULL, rowNumber INTEGER NOT NULL, accepted INTEGER NOT NULL, errors TEXT, resultingProductId TEXT, FOREIGN KEY(manifestId) REFERENCES import_manifests(id) ON DELETE CASCADE)")
            }
        }
        /** Preserves imported opening lots by materializing their opening quantities in the immutable ledger. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS inventory_movements (id TEXT NOT NULL PRIMARY KEY, productId TEXT NOT NULL, lotId TEXT, type TEXT NOT NULL, quantity TEXT NOT NULL, unit TEXT NOT NULL, expiryDate TEXT, costCentavos INTEGER, sourceReference TEXT, actorUserId TEXT NOT NULL, occurredAtUtcMillis INTEGER NOT NULL, reason TEXT NOT NULL, FOREIGN KEY(productId) REFERENCES products(id) ON DELETE RESTRICT, FOREIGN KEY(lotId) REFERENCES inventory_lots(id) ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_inventory_movements_productId ON inventory_movements(productId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_inventory_movements_lotId ON inventory_movements(lotId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_inventory_movements_occurredAtUtcMillis ON inventory_movements(occurredAtUtcMillis)")
                db.execSQL("INSERT INTO inventory_movements (id, productId, lotId, type, quantity, unit, expiryDate, costCentavos, sourceReference, actorUserId, occurredAtUtcMillis, reason) SELECT 'migration-opening-' || l.id, l.productId, l.id, 'OPENING_BALANCE', l.openingQuantity, p.unit, l.expiryDate, NULL, l.supplierReference, 'MIGRATION', 0, 'F02 opening stock migrated to immutable ledger' FROM inventory_lots l JOIN products p ON p.id = l.productId WHERE l.openingQuantity <> '0'")
            }
        }
    }
}
