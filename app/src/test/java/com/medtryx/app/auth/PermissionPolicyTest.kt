package com.medtryx.app.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionPolicyTest {
    @Test fun every_role_has_the_required_baseline_and_no_implicit_protected_permissions() {
        assertEquals(setOf(Permission.CHECKOUT_CREATE, Permission.SHIFT_OPEN_CLOSE_OWN), PermissionPolicy.effectivePermissions(AccessProfile(Role.CASHIER)))
        assertEquals(PermissionPolicy.effectivePermissions(AccessProfile(Role.CASHIER)), PermissionPolicy.effectivePermissions(AccessProfile(Role.PHARMACIST)))
        assertEquals(PermissionPolicy.effectivePermissions(AccessProfile(Role.CASHIER)), PermissionPolicy.effectivePermissions(AccessProfile(Role.SUPERVISOR)))
        assertTrue(PermissionPolicy.allows(AccessProfile(Role.ADMINISTRATOR), Permission.USER_MANAGE))
        assertTrue(PermissionPolicy.allows(AccessProfile(Role.OWNER), Permission.BACKUP_RESTORE))
        assertTrue(PermissionPolicy.allows(AccessProfile(Role.AUDITOR), Permission.REPORTS_VIEW))
        assertFalse(PermissionPolicy.allows(AccessProfile(Role.AUDITOR), Permission.CHECKOUT_CREATE))
    }
    @Test fun cashier_cannot_change_protected_product_data() {
        val cashier = AccessProfile(Role.CASHIER)
        assertFalse(PermissionPolicy.allows(cashier, Permission.PRICE_CHANGE))
        assertFalse(PermissionPolicy.allows(cashier, Permission.TAX_CONFIGURATION_CHANGE))
        assertFalse(PermissionPolicy.allows(cashier, Permission.INVENTORY_ADJUST))
    }

    @Test fun protected_permission_can_be_explicitly_granted_to_supervisor() {
        val supervisor = AccessProfile(Role.SUPERVISOR, setOf(Permission.INVENTORY_ADJUST))
        assertTrue(PermissionPolicy.allows(supervisor, Permission.INVENTORY_ADJUST))
    }

    @Test fun auditor_is_read_only() {
        val auditor = AccessProfile(Role.AUDITOR)
        assertTrue(PermissionPolicy.allows(auditor, Permission.AUDIT_VIEW))
        assertFalse(PermissionPolicy.allows(auditor, Permission.USER_MANAGE))
    }

    @Test fun cashiers_and_auditors_cannot_receive_elevated_grants() {
        try {
            PermissionPolicy.validateExplicitGrants(Role.CASHIER, setOf(Permission.BACKUP_RESTORE))
            throw AssertionError("Expected grant validation to fail")
        } catch (_: IllegalArgumentException) { }
        assertFalse(PermissionPolicy.allows(AccessProfile(Role.AUDITOR, setOf(Permission.PRICE_CHANGE)), Permission.PRICE_CHANGE))
    }
}
