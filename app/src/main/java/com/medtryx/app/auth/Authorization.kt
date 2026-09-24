package com.medtryx.app.auth

enum class Role {
    CASHIER,
    PHARMACIST,
    SUPERVISOR,
    ADMINISTRATOR,
    OWNER,
    AUDITOR,
}

/** Permissions are enforced by application use cases, never by UI visibility alone. */
enum class Permission {
    CHECKOUT_CREATE,
    SHIFT_OPEN_CLOSE_OWN,
    SALES_VIEW,
    REPORTS_VIEW,
    AUDIT_VIEW,
    USER_MANAGE,
    PRODUCT_MANAGE,
    PRICE_CHANGE,
    TAX_CONFIGURATION_CHANGE,
    BENEFIT_ELIGIBILITY_CHANGE,
    INVENTORY_ADJUST,
    ROUNDING_CONFIGURATION_CHANGE,
    BNPC_CONFIGURATION_CHANGE,
    FINALIZED_SALE_VOID_OR_REVERSE,
    SENSITIVE_EXPORT,
    BACKUP_CREATE,
    BACKUP_RESTORE,
    PROTECTED_ACTION_APPROVE,
    CUSTOMER_ID_REVEAL,
}

data class AccessProfile(
    val role: Role,
    val grantedPermissions: Set<Permission> = emptySet(),
)

object PermissionPolicy {
    private val cashierPermissions = setOf(
        Permission.CHECKOUT_CREATE,
        Permission.SHIFT_OPEN_CLOSE_OWN,
    )

    private val auditorPermissions = setOf(
        Permission.SALES_VIEW,
        Permission.REPORTS_VIEW,
        Permission.AUDIT_VIEW,
    )

    fun effectivePermissions(profile: AccessProfile): Set<Permission> =
        baselinePermissions(profile.role) + profile.grantedPermissions.filter { it in assignablePermissions(profile.role) }

    fun allows(profile: AccessProfile, permission: Permission): Boolean =
        permission in effectivePermissions(profile)

    /** Only protected operational roles can be assigned elevated permissions. */
    fun assignablePermissions(role: Role): Set<Permission> = when (role) {
        Role.OWNER, Role.ADMINISTRATOR, Role.PHARMACIST, Role.SUPERVISOR -> Permission.entries.toSet()
        Role.CASHIER, Role.AUDITOR -> emptySet()
    }

    fun validateExplicitGrants(role: Role, permissions: Set<Permission>) {
        require(permissions.all { it in assignablePermissions(role) }) {
            "Only owner, administrator, pharmacist, or supervisor accounts may receive protected permissions."
        }
    }

    private fun baselinePermissions(role: Role): Set<Permission> = when (role) {
        Role.CASHIER -> cashierPermissions
        Role.PHARMACIST, Role.SUPERVISOR -> cashierPermissions
        Role.AUDITOR -> auditorPermissions
        Role.ADMINISTRATOR, Role.OWNER -> Permission.entries.toSet()
    }
}

class AccessDeniedException(permission: Permission) : SecurityException(
    "Permission denied: $permission",
)

fun AccessProfile.requirePermission(permission: Permission) {
    if (!PermissionPolicy.allows(this, permission)) throw AccessDeniedException(permission)
}

/**
 * Boundary used by F02+ use cases and local API handlers.  They must authorize here before
 * changing protected state; Compose visibility is deliberately not an authorization mechanism.
 */
class ProtectedActionAuthorizer(
    private val authenticationService: AuthenticationService,
) {
    suspend fun require(
        sessionId: String,
        permission: Permission,
        action: String,
        entityReference: String?,
        reason: String,
        freshSecret: CharArray? = null,
    ): AuthenticatedSession = authenticationService.authorizeProtectedAction(
        sessionId = sessionId,
        permission = permission,
        action = action,
        entityReference = entityReference,
        reason = reason,
        freshSecret = freshSecret,
    )

    suspend fun recordApplicationAudit(sessionId: String, action: String, entityReference: String, reason: String, oldValue: String?, newValue: String?) =
        authenticationService.recordApplicationAudit(sessionId, action, entityReference, reason, oldValue, newValue)
}
