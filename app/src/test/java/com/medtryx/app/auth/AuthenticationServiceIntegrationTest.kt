package com.medtryx.app.auth

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuthenticationServiceIntegrationTest {
    private lateinit var database: MedtryxDatabase
    private lateinit var service: AuthenticationService
    private var now = 1_700_000_000_000L

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MedtryxDatabase::class.java).allowMainThreadQueries().build()
        service = AuthenticationService(database, PasswordHasher(), "test-device") { now }
    }

    @After fun tearDown() = database.close()

    @Test fun login_logout_lock_throttle_and_restart_invalidate_sessions() = runTest {
        service.bootstrapOwner("owner", "Owner", "1234".toCharArray())
        repeat(5) { assertEquals(LoginResult.InvalidCredentials, service.login("owner", "bad-pin".toCharArray())) }
        assertEquals(LoginResult.Throttled, service.login("owner", "1234".toCharArray()))
        now += 5 * 60 * 1000L + 1
        val session = (service.login("owner", "1234".toCharArray()) as LoginResult.Success).session
        assertNotNull(service.requireActiveSession(session.sessionId))
        service.lockForInactivity(session.sessionId)
        assertNull(service.requireActiveSession(session.sessionId))
        val next = (service.login("owner", "1234".toCharArray()) as LoginResult.Success).session
        service.logout(next.sessionId)
        assertNull(service.requireActiveSession(next.sessionId))
        val last = (service.login("owner", "1234".toCharArray()) as LoginResult.Success).session
        service.revokeDeviceSessions("APP_RESTART_SESSION_INVALIDATION")
        assertNull(service.requireActiveSession(last.sessionId))
    }

    @Test fun disable_role_change_and_credential_change_revoke_sessions() = runTest {
        val owner = service.bootstrapOwner("owner", "Owner", "1234".toCharArray())
        val cashierId = service.createUser(owner.sessionId, "cashier", "Cashier", Role.CASHIER, "5678".toCharArray(), reason = "staff onboarding")
        val cashier = (service.login("cashier", "5678".toCharArray()) as LoginResult.Success).session
        service.disableUser(owner.sessionId, cashierId, "left shift")
        assertNull(service.requireActiveSession(cashier.sessionId))
        assertEquals(LoginResult.DisabledAccount, service.login("cashier", "5678".toCharArray()))
        service.setUserEnabled(owner.sessionId, cashierId, true, "rehired")
        val enabled = (service.login("cashier", "5678".toCharArray()) as LoginResult.Success).session
        service.changeRole(owner.sessionId, cashierId, Role.SUPERVISOR, "promotion")
        assertNull(service.requireActiveSession(enabled.sessionId))
        val supervisor = (service.login("cashier", "5678".toCharArray()) as LoginResult.Success).session
        service.changeCredential(supervisor.sessionId, "5678".toCharArray(), "9999".toCharArray())
        assertNull(service.requireActiveSession(supervisor.sessionId))
        assertEquals(LoginResult.InvalidCredentials, service.login("cashier", "5678".toCharArray()))
        assertTrue(service.login("cashier", "9999".toCharArray()) is LoginResult.Success)
    }

    @Test fun protected_actions_are_denied_at_use_case_boundary_and_audited() = runTest {
        val owner = service.bootstrapOwner("owner", "Owner", "1234".toCharArray())
        val cashierId = service.createUser(owner.sessionId, "cashier", "Cashier", Role.CASHIER, "5678".toCharArray(), reason = "staff onboarding")
        val auditorId = service.createUser(owner.sessionId, "auditor", "Auditor", Role.AUDITOR, "9012".toCharArray(), reason = "audit access")
        val cashier = (service.login("cashier", "5678".toCharArray()) as LoginResult.Success).session
        val auditor = (service.login("auditor", "9012".toCharArray()) as LoginResult.Success).session
        try { service.createUser(cashier.sessionId, "unauthorized", "Unauthorized", Role.CASHIER, "0000".toCharArray(), reason = "direct bypass") ; throw AssertionError("cashier created a user") } catch (_: AccessDeniedException) { }
        val authorizer = ProtectedActionAuthorizer(service)
        val protected = listOf(Permission.USER_MANAGE, Permission.PRICE_CHANGE, Permission.TAX_CONFIGURATION_CHANGE, Permission.BENEFIT_ELIGIBILITY_CHANGE, Permission.INVENTORY_ADJUST, Permission.ROUNDING_CONFIGURATION_CHANGE, Permission.BNPC_CONFIGURATION_CHANGE, Permission.FINALIZED_SALE_VOID_OR_REVERSE, Permission.SENSITIVE_EXPORT, Permission.BACKUP_CREATE, Permission.BACKUP_RESTORE, Permission.CUSTOMER_ID_REVEAL)
        protected.forEach { permission ->
            try { authorizer.require(cashier.sessionId, permission, "DIRECT_$permission", "entity", "test", if (permission in setOf(Permission.BACKUP_RESTORE, Permission.FINALIZED_SALE_VOID_OR_REVERSE, Permission.SENSITIVE_EXPORT)) "5678".toCharArray() else null); throw AssertionError("cashier allowed $permission") } catch (_: AccessDeniedException) { }
            try { authorizer.require(auditor.sessionId, permission, "DIRECT_AUDITOR_$permission", "entity", "test", if (permission in setOf(Permission.BACKUP_RESTORE, Permission.FINALIZED_SALE_VOID_OR_REVERSE, Permission.SENSITIVE_EXPORT)) "9012".toCharArray() else null); throw AssertionError("auditor allowed $permission") } catch (_: AccessDeniedException) { }
        }
        assertEquals(cashierId, cashier.userId); assertEquals(auditorId, auditor.userId)
        val rejected = database.authDao().auditEvents().filter { it.result == AuditResult.REJECTED && it.action.startsWith("DIRECT_") }
        assertTrue(rejected.size >= protected.size * 2)
        assertTrue(rejected.all { it.deviceId == "test-device" && it.sessionId != null && it.action.startsWith("DIRECT_") })
        assertTrue(database.authDao().auditEvents().any { it.action == "USER_CREATE" && it.result == AuditResult.REJECTED && it.reason == "direct bypass" })
    }

    @Test fun fresh_authentication_and_audit_evidence_are_required_for_high_risk_action() = runTest {
        val owner = service.bootstrapOwner("owner", "Owner", "1234".toCharArray())
        try { service.authorizeProtectedAction(owner.sessionId, Permission.BACKUP_RESTORE, "RESTORE", "backup-1", "restore requested") ; throw AssertionError("Expected fresh auth failure") } catch (_: SecurityException) { }
        val accepted = service.authorizeProtectedAction(owner.sessionId, Permission.BACKUP_RESTORE, "RESTORE", "backup-1", "restore requested", "1234".toCharArray())
        assertEquals(owner.userId, accepted.userId)
        val evidence = database.authDao().auditEvents().filter { it.action == "RESTORE" }
        assertEquals(listOf(AuditResult.REJECTED, AuditResult.SUCCESS), evidence.map { it.result })
        assertTrue(evidence.all { it.actorUserId == owner.userId && it.sessionId == owner.sessionId && it.reason == "restore requested" && it.entityReference == "backup-1" })
        assertFalse(evidence.any { (it.oldValue ?: "").contains("1234") || (it.newValue ?: "").contains("1234") })
    }
}
