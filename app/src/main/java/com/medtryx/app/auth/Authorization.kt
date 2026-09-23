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
        baselinePermissions(profile.role) + profile.grantedPermissions

    fun allows(profile: AccessProfile, permission: Permission): Boolean =
        permission in effectivePermissions(profile)

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
