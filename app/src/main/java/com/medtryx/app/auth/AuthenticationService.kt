package com.medtryx.app.auth

import androidx.room.withTransaction
import java.util.UUID

data class AuthenticatedSession(val sessionId: String, val userId: String, val profile: AccessProfile)
sealed interface LoginResult {
    data class Success(val session: AuthenticatedSession) : LoginResult
    data object InvalidCredentials : LoginResult
    data object Throttled : LoginResult
    data object DisabledAccount : LoginResult
}

class AuthenticationService(
    private val database: MedtryxDatabase,
    private val passwordHasher: PasswordHasher,
    private val deviceId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun bootstrapOwner(username: String, displayName: String, secret: CharArray): AuthenticatedSession {
        val normalized = normalizeUsername(username)
        require(displayName.isNotBlank()) { "Display name is required." }
        validateSecret(secret)
        return database.withTransaction {
            check(database.authDao().userCount() == 0) { "Initial owner already exists." }
            val now = clock()
            val user = UserEntity(UUID.randomUUID().toString(), normalized, displayName.trim(), Role.OWNER, true, now, now)
            val passwordHash = passwordHasher.hash(secret)
            database.authDao().insertUser(user)
            database.authDao().insertCredential(passwordHash.toEntity(user.id, now))
            val session = newSession(user, now)
            database.authDao().upsertSession(session)
            audit(user.id, "OWNER_BOOTSTRAPPED", AuditResult.SUCCESS, user.id)
            session.toAuthenticated(user, emptyList())
        }
    }

    suspend fun login(username: String, secret: CharArray): LoginResult {
        val normalized = normalizeUsername(username)
        val now = clock()
        return database.withTransaction {
            val dao = database.authDao()
            if (dao.failuresSince(normalized, now - FAILURE_WINDOW_MILLIS) >= MAX_FAILURES) {
                dao.insertAttempt(attempt(normalized, now, AuthenticationAttemptResult.THROTTLED))
                audit(null, "LOGIN_THROTTLED", AuditResult.REJECTED, normalized)
                return@withTransaction LoginResult.Throttled
            }
            val user = dao.findUser(normalized)
            if (user == null || !passwordHasher.matches(secret, dao.credentialFor(user.id)?.toPasswordHash() ?: return@withTransaction invalid(normalized, now))) {
                return@withTransaction invalid(normalized, now)
            }
            if (!user.isActive) {
                dao.insertAttempt(attempt(normalized, now, AuthenticationAttemptResult.DISABLED_ACCOUNT))
                audit(user.id, "LOGIN_DISABLED_ACCOUNT", AuditResult.REJECTED, user.id)
                return@withTransaction LoginResult.DisabledAccount
            }
            val session = newSession(user, now)
            dao.upsertSession(session)
            dao.insertAttempt(attempt(normalized, now, AuthenticationAttemptResult.SUCCESS))
            audit(user.id, "LOGIN", AuditResult.SUCCESS, user.id)
            LoginResult.Success(session.toAuthenticated(user, dao.permissionGrantsFor(user.id)))
        }
    }

    suspend fun logout(sessionId: String) = database.withTransaction {
        database.authDao().revokeSession(sessionId, clock())
        audit(null, "LOGOUT", AuditResult.SUCCESS, sessionId)
    }

    suspend fun lockForInactivity(sessionId: String) = database.withTransaction {
        database.authDao().lockSession(sessionId, clock())
        audit(null, "SESSION_LOCKED_INACTIVITY", AuditResult.SUCCESS, sessionId)
    }

    suspend fun requireActiveSession(sessionId: String): AuthenticatedSession? = database.withTransaction {
        val now = clock()
        val session = database.authDao().session(sessionId) ?: return@withTransaction null
        if (session.revokedAtUtcMillis != null || session.lockedAtUtcMillis != null || session.expiresAtUtcMillis <= now || session.lastActivityAtUtcMillis + INACTIVITY_TIMEOUT_MILLIS <= now) return@withTransaction null
        val user = database.authDao().findUserById(session.userId)?.takeIf { it.isActive } ?: return@withTransaction null
        database.authDao().touchSession(sessionId, now)
        session.toAuthenticated(user, database.authDao().permissionGrantsFor(user.id))
    }

    suspend fun disableUser(actor: AuthenticatedSession, targetUserId: String, reason: String) = database.withTransaction {
        actor.profile.requirePermission(Permission.USER_MANAGE)
        require(reason.isNotBlank()) { "A reason is required." }
        val now = clock()
        database.authDao().setUserActive(targetUserId, false, now)
        database.authDao().revokeSessionsForUser(targetUserId, now)
        audit(actor.userId, "USER_DISABLED", AuditResult.SUCCESS, targetUserId, reason)
    }

    private suspend fun invalid(username: String, now: Long): LoginResult {
        database.authDao().insertAttempt(attempt(username, now, AuthenticationAttemptResult.INVALID_CREDENTIALS))
        audit(null, "LOGIN_FAILED", AuditResult.REJECTED, username)
        return LoginResult.InvalidCredentials
    }

    private suspend fun audit(actor: String?, action: String, result: AuditResult, reference: String?, reason: String? = null) {
        database.authDao().insertAudit(AuditEventEntity(UUID.randomUUID().toString(), actor, clock(), deviceId, action, result, reference, reason, null, null))
    }

    private fun newSession(user: UserEntity, now: Long) = SessionEntity(UUID.randomUUID().toString(), user.id, deviceId, now, now, now + SESSION_LIFETIME_MILLIS, null, null)
    private fun attempt(username: String, now: Long, result: AuthenticationAttemptResult) = AuthenticationAttemptEntity(UUID.randomUUID().toString(), username, deviceId, now, result)
    private fun CredentialEntity.toPasswordHash() = PasswordHash(hash, salt, iterations, algorithm)
    private fun PasswordHash.toEntity(userId: String, now: Long) = CredentialEntity(userId, encodedHash, salt, iterations, algorithm, now)
    private fun SessionEntity.toAuthenticated(user: UserEntity, grants: List<Permission>) = AuthenticatedSession(id, user.id, AccessProfile(user.role, grants.toSet()))

    private fun normalizeUsername(value: String): String = value.trim().lowercase().also { require(USERNAME.matches(it)) { "Username must be 3-50 lowercase letters, numbers, dots, dashes, or underscores." } }
    private fun validateSecret(value: CharArray) = require(value.size in 4..128) { "PIN/password must be 4-128 characters." }

    private companion object {
        val USERNAME = Regex("[a-z0-9._-]{3,50}")
        const val MAX_FAILURES = 5
        const val FAILURE_WINDOW_MILLIS = 5 * 60 * 1000L
        const val INACTIVITY_TIMEOUT_MILLIS = 5 * 60 * 1000L
        const val SESSION_LIFETIME_MILLIS = 8 * 60 * 60 * 1000L
    }
}
