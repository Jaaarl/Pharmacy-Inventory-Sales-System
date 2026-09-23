package com.medtryx.app.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {
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
}
