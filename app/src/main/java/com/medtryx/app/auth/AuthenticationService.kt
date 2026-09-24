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

/** F01 application boundary. Session state is always reloaded from Room, never trusted from UI. */
class AuthenticationService(
    private val database: MedtryxDatabase,
    private val passwordHasher: PasswordHasher,
    private val deviceId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun bootstrapOwner(username: String, displayName: String, secret: CharArray): AuthenticatedSession {
        val normalized = normalizeUsername(username)
        require(displayName.isNotBlank()) { "Display name is required." }; validateSecret(secret)
        return database.withTransaction {
            check(database.authDao().userCount() == 0) { "Initial owner already exists." }
            val now = clock(); val user = UserEntity(UUID.randomUUID().toString(), normalized, displayName.trim(), Role.OWNER, true, now, now)
            database.authDao().insertUser(user); database.authDao().insertCredential(passwordHasher.hash(secret).toEntity(user.id, now))
            val session = newSession(user, now); database.authDao().upsertSession(session)
            audit(user.id, session.id, "OWNER_BOOTSTRAPPED", AuditResult.SUCCESS, user.id, newValue = "role=OWNER")
            session.toAuthenticated(user, emptyList())
        }
    }

    suspend fun createUser(actorSessionId: String, username: String, displayName: String, role: Role, secret: CharArray, explicitPermissions: Set<Permission> = emptySet(), reason: String): String {
        val actor = requireProtectedSession(actorSessionId, Permission.USER_MANAGE, "USER_CREATE", username, reason)
        return database.withTransaction {
        val normalized = normalizeUsername(username); require(displayName.isNotBlank()) { "Display name is required." }; validateSecret(secret)
        PermissionPolicy.validateExplicitGrants(role, explicitPermissions)
        check(database.authDao().findUser(normalized) == null) { "Username is already in use." }
        val now = clock(); val user = UserEntity(UUID.randomUUID().toString(), normalized, displayName.trim(), role, true, now, now)
        database.authDao().insertUser(user); database.authDao().insertCredential(passwordHasher.hash(secret).toEntity(user.id, now))
        database.authDao().upsertPermissionGrants(explicitPermissions.map { UserPermissionGrantEntity(user.id, it) })
        audit(actor.userId, actor.sessionId, "USER_CREATED", AuditResult.SUCCESS, user.id, reason, null, "role=$role; permissions=${explicitPermissions.sortedBy { it.name }}")
        user.id }
    }

    suspend fun login(username: String, secret: CharArray): LoginResult {
        val normalized = normalizeUsername(username); val now = clock()
        return database.withTransaction {
            val dao = database.authDao()
            if (dao.failuresSince(normalized, now - FAILURE_WINDOW_MILLIS) >= MAX_FAILURES) {
                dao.insertAttempt(attempt(normalized, now, AuthenticationAttemptResult.THROTTLED)); audit(null, null, "LOGIN_THROTTLED", AuditResult.REJECTED, normalized)
                return@withTransaction LoginResult.Throttled
            }
            val user = dao.findUser(normalized); val credential = user?.let { dao.credentialFor(it.id)?.toPasswordHash() }
            if (user == null || credential == null || !passwordHasher.matches(secret, credential)) return@withTransaction invalid(normalized, now)
            if (!user.isActive) {
                dao.insertAttempt(attempt(normalized, now, AuthenticationAttemptResult.DISABLED_ACCOUNT)); audit(user.id, null, "LOGIN_DISABLED_ACCOUNT", AuditResult.REJECTED, user.id)
                return@withTransaction LoginResult.DisabledAccount
            }
            val session = newSession(user, now); dao.upsertSession(session); dao.insertAttempt(attempt(normalized, now, AuthenticationAttemptResult.SUCCESS))
            audit(user.id, session.id, "LOGIN", AuditResult.SUCCESS, user.id)
            LoginResult.Success(session.toAuthenticated(user, dao.permissionGrantsFor(user.id)))
        }
    }

    suspend fun logout(sessionId: String) = database.withTransaction {
        val session = database.authDao().session(sessionId); database.authDao().revokeSession(sessionId, clock()); audit(session?.userId, sessionId, "LOGOUT", AuditResult.SUCCESS, sessionId)
    }

    /** Called by the Android shell on process restart and by F13 when its local server stops. */
    suspend fun revokeDeviceSessions(event: String) = database.withTransaction {
        database.authDao().revokeSessionsForDevice(deviceId, clock())
        audit(null, null, event, AuditResult.SUCCESS, deviceId)
    }

    suspend fun lockForInactivity(sessionId: String) = database.withTransaction {
        val session = database.authDao().session(sessionId); database.authDao().lockSession(sessionId, clock()); audit(session?.userId, sessionId, "SESSION_LOCKED_INACTIVITY", AuditResult.SUCCESS, sessionId)
    }

    suspend fun requireActiveSession(sessionId: String): AuthenticatedSession? = database.withTransaction { activeSession(sessionId, true) }

    suspend fun disableUser(actorSessionId: String, targetUserId: String, reason: String) {
        val actor = requireProtectedSession(actorSessionId, Permission.USER_MANAGE, "USER_DISABLE", targetUserId, reason)
        database.withTransaction {
        check(actor.userId != targetUserId) { "You cannot disable your own account." }
        val target = database.authDao().findUserById(targetUserId) ?: error("User does not exist."); require(target.isActive) { "User is already disabled." }
        database.authDao().setUserActive(targetUserId, false, clock()); database.authDao().revokeSessionsForUser(targetUserId, clock())
        audit(actor.userId, actor.sessionId, "USER_DISABLED", AuditResult.SUCCESS, targetUserId, reason, "isActive=true", "isActive=false") }
    }

    suspend fun setUserEnabled(actorSessionId: String, targetUserId: String, enabled: Boolean, reason: String) {
        val actor = requireProtectedSession(actorSessionId, Permission.USER_MANAGE, "USER_ENABLE_CHANGE", targetUserId, reason)
        database.withTransaction {
        val target = database.authDao().findUserById(targetUserId) ?: error("User does not exist.")
        if (target.isActive != enabled) {
            database.authDao().setUserActive(targetUserId, enabled, clock()); database.authDao().revokeSessionsForUser(targetUserId, clock())
            audit(actor.userId, actor.sessionId, if (enabled) "USER_ENABLED" else "USER_DISABLED", AuditResult.SUCCESS, targetUserId, reason, "isActive=${target.isActive}", "isActive=$enabled")
        } }
    }

    suspend fun changeRole(actorSessionId: String, targetUserId: String, role: Role, reason: String) {
        val actor = requireProtectedSession(actorSessionId, Permission.USER_MANAGE, "USER_ROLE_CHANGE", targetUserId, reason)
        database.withTransaction {
        check(actor.userId != targetUserId) { "You cannot change your own role." }
        val target = database.authDao().findUserById(targetUserId) ?: error("User does not exist.")
        database.authDao().setRole(targetUserId, role, clock()); database.authDao().clearPermissionGrants(targetUserId); database.authDao().revokeSessionsForUser(targetUserId, clock())
        audit(actor.userId, actor.sessionId, "USER_ROLE_CHANGED", AuditResult.SUCCESS, targetUserId, reason, "role=${target.role}", "role=$role; explicit permissions cleared") }
    }

    suspend fun setExplicitPermissions(actorSessionId: String, targetUserId: String, permissions: Set<Permission>, reason: String) {
        val actor = requireProtectedSession(actorSessionId, Permission.USER_MANAGE, "USER_PERMISSION_CHANGE", targetUserId, reason)
        database.withTransaction {
        val target = database.authDao().findUserById(targetUserId) ?: error("User does not exist."); PermissionPolicy.validateExplicitGrants(target.role, permissions)
        val old = database.authDao().permissionGrantsFor(targetUserId).sortedBy { it.name }
        database.authDao().clearPermissionGrants(targetUserId); database.authDao().upsertPermissionGrants(permissions.map { UserPermissionGrantEntity(targetUserId, it) }); database.authDao().revokeSessionsForUser(targetUserId, clock())
        audit(actor.userId, actor.sessionId, "USER_PERMISSIONS_CHANGED", AuditResult.SUCCESS, targetUserId, reason, "permissions=$old", "permissions=${permissions.sortedBy { it.name }}") }
    }

    suspend fun changeCredential(sessionId: String, currentSecret: CharArray, newSecret: CharArray) {
        val current = activeSession(sessionId, false) ?: run { audit(null, sessionId, "CREDENTIAL_CHANGE", AuditResult.REJECTED, sessionId, newValue = "inactive session"); throw SecurityException("Session is no longer active.") }; validateSecret(newSecret)
        val credential = database.authDao().credentialFor(current.userId)?.toPasswordHash()
        if (credential == null || !passwordHasher.matches(currentSecret, credential)) { audit(current.userId, current.sessionId, "CREDENTIAL_CHANGE", AuditResult.REJECTED, current.userId); throw SecurityException("Current PIN/password is invalid.") }
        database.withTransaction {
            val now = clock(); val replacement = passwordHasher.hash(newSecret)
            database.authDao().replaceCredential(current.userId, replacement.encodedHash, replacement.salt, replacement.iterations, replacement.algorithm, now); database.authDao().revokeSessionsForUser(current.userId, now)
            audit(current.userId, current.sessionId, "CREDENTIAL_CHANGED", AuditResult.SUCCESS, current.userId)
        }
    }

    /** Authorizes protected application/API operations, requiring fresh credentials where mandated. */
    suspend fun authorizeProtectedAction(sessionId: String, permission: Permission, action: String, entityReference: String?, reason: String, freshSecret: CharArray? = null): AuthenticatedSession {
        val session = requireProtectedSession(sessionId, permission, action, entityReference, reason)
        if (permission in FRESH_AUTH_REQUIRED && freshSecret == null) {
            audit(session.userId, session.sessionId, action, AuditResult.REJECTED, entityReference, reason, newValue = "fresh authentication required"); throw SecurityException("Fresh authentication is required for $action.")
        }
        if (freshSecret != null) {
            val credential = database.authDao().credentialFor(session.userId)?.toPasswordHash()
            if (credential == null || !passwordHasher.matches(freshSecret, credential)) { audit(session.userId, session.sessionId, action, AuditResult.REJECTED, entityReference, reason, newValue = "fresh authentication rejected"); throw SecurityException("Fresh authentication failed.") }
        }
        audit(session.userId, session.sessionId, action, AuditResult.SUCCESS, entityReference, reason); return session
    }

    /** Domain use cases call this after an authorized mutation to retain concrete old/new evidence. */
    suspend fun recordApplicationAudit(sessionId: String, action: String, entityReference: String, reason: String, oldValue: String?, newValue: String?) {
        val session = activeSession(sessionId, true) ?: throw SecurityException("Session is no longer active.")
        audit(session.userId, session.sessionId, action, AuditResult.SUCCESS, entityReference, reason, oldValue, newValue)
    }

    private suspend fun requireProtectedSession(sessionId: String, permission: Permission, action: String, reference: String?, reason: String): AuthenticatedSession {
        val session = activeSession(sessionId, true)
        if (session == null) { audit(null, sessionId, action, AuditResult.REJECTED, reference, reason, newValue = "inactive session"); throw SecurityException("Session is no longer active.") }
        if (reason.isBlank()) { audit(session.userId, session.sessionId, action, AuditResult.REJECTED, reference, newValue = "reason required"); throw IllegalArgumentException("A reason is required.") }
        if (!PermissionPolicy.allows(session.profile, permission)) { audit(session.userId, session.sessionId, action, AuditResult.REJECTED, reference, reason, newValue = "permission=$permission"); throw AccessDeniedException(permission) }
        return session
    }

    private suspend fun activeSession(sessionId: String, touch: Boolean): AuthenticatedSession? {
        val now = clock(); val session = database.authDao().session(sessionId) ?: return null
        if (session.revokedAtUtcMillis != null || session.lockedAtUtcMillis != null || session.expiresAtUtcMillis <= now || session.lastActivityAtUtcMillis + INACTIVITY_TIMEOUT_MILLIS <= now) return null
        val user = database.authDao().findUserById(session.userId)?.takeIf { it.isActive } ?: return null
        if (touch) database.authDao().touchSession(sessionId, now)
        return session.toAuthenticated(user, database.authDao().permissionGrantsFor(user.id))
    }

    private suspend fun invalid(username: String, now: Long): LoginResult { database.authDao().insertAttempt(attempt(username, now, AuthenticationAttemptResult.INVALID_CREDENTIALS)); audit(null, null, "LOGIN_FAILED", AuditResult.REJECTED, username); return LoginResult.InvalidCredentials }
    private suspend fun audit(actor: String?, sessionId: String?, action: String, result: AuditResult, reference: String?, reason: String? = null, oldValue: String? = null, newValue: String? = null) { database.authDao().insertAudit(AuditEventEntity(UUID.randomUUID().toString(), actor, clock(), deviceId, sessionId, action, result, reference, reason, oldValue, newValue)) }
    private fun newSession(user: UserEntity, now: Long) = SessionEntity(UUID.randomUUID().toString(), user.id, deviceId, now, now, now + SESSION_LIFETIME_MILLIS, null, null)
    private fun attempt(username: String, now: Long, result: AuthenticationAttemptResult) = AuthenticationAttemptEntity(UUID.randomUUID().toString(), username, deviceId, now, result)
    private fun CredentialEntity.toPasswordHash() = PasswordHash(hash, salt, iterations, algorithm)
    private fun PasswordHash.toEntity(userId: String, now: Long) = CredentialEntity(userId, encodedHash, salt, iterations, algorithm, now)
    private fun SessionEntity.toAuthenticated(user: UserEntity, grants: List<Permission>) = AuthenticatedSession(id, user.id, AccessProfile(user.role, grants.toSet()))
    private fun normalizeUsername(value: String): String = value.trim().lowercase().also { require(USERNAME.matches(it)) { "Username must be 3-50 lowercase letters, numbers, dots, dashes, or underscores." } }
    private fun validateSecret(value: CharArray) = require(value.size in 4..128) { "PIN/password must be 4-128 characters." }
    private companion object { val USERNAME = Regex("[a-z0-9._-]{3,50}"); val FRESH_AUTH_REQUIRED = setOf(Permission.FINALIZED_SALE_VOID_OR_REVERSE, Permission.SENSITIVE_EXPORT, Permission.BACKUP_RESTORE, Permission.SHIFT_VARIANCE_APPROVE, Permission.SHIFT_VARIANCE_CONFIGURE, Permission.SHIFT_ADJUST); const val MAX_FAILURES = 5; const val FAILURE_WINDOW_MILLIS = 5 * 60 * 1000L; const val INACTIVITY_TIMEOUT_MILLIS = 5 * 60 * 1000L; const val SESSION_LIFETIME_MILLIS = 8 * 60 * 60 * 1000L }
}
