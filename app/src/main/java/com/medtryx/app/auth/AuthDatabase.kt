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
    @Insert suspend fun insertUser(user: UserEntity)
    @Insert suspend fun insertCredential(credential: CredentialEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSession(session: SessionEntity)
    @Insert suspend fun insertAttempt(attempt: AuthenticationAttemptEntity)
    @Insert suspend fun insertAudit(event: AuditEventEntity)
    @Query("UPDATE sessions SET revokedAtUtcMillis = :at WHERE userId = :userId AND revokedAtUtcMillis IS NULL") suspend fun revokeSessionsForUser(userId: String, at: Long)
    @Query("UPDATE sessions SET revokedAtUtcMillis = :at WHERE id = :sessionId AND revokedAtUtcMillis IS NULL") suspend fun revokeSession(sessionId: String, at: Long)
    @Query("UPDATE sessions SET lastActivityAtUtcMillis = :at, lockedAtUtcMillis = NULL WHERE id = :sessionId") suspend fun touchSession(sessionId: String, at: Long)
    @Query("UPDATE sessions SET lockedAtUtcMillis = :at WHERE id = :sessionId") suspend fun lockSession(sessionId: String, at: Long)
    @Query("UPDATE users SET isActive = :active, updatedAtUtcMillis = :at WHERE id = :userId") suspend fun setUserActive(userId: String, active: Boolean, at: Long)
    @Query("UPDATE users SET role = :role, updatedAtUtcMillis = :at WHERE id = :userId") suspend fun setRole(userId: String, role: Role, at: Long)
    @Query("UPDATE credentials SET hash = :hash, salt = :salt, iterations = :iterations, algorithm = :algorithm, changedAtUtcMillis = :at WHERE userId = :userId") suspend fun replaceCredential(userId: String, hash: ByteArray, salt: ByteArray, iterations: Int, algorithm: String, at: Long)
}

@Database(
    entities = [UserEntity::class, CredentialEntity::class, UserPermissionGrantEntity::class, SessionEntity::class, AuthenticationAttemptEntity::class, AuditEventEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(AuthConverters::class)
abstract class MedtryxDatabase : RoomDatabase() {
    abstract fun authDao(): AuthDao
}
