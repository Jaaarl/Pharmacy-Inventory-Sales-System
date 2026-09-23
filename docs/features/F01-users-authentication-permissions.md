# F01 — Users, Authentication, and Permissions

**Phase:** 1 — Offline core  
**Status:** MVP specification  
**Master reference:** [Section 4](../Medtryx_Product_and_Technical_Specification.md#4-feature-f01--users-authentication-and-permissions)

## Purpose

Give every user a unique identity and restrict sensitive financial, inventory, customer, export, and recovery actions by role and explicit permission.

## Roles

### Cashier

- Sign in with an individual account and PIN/password.
- Open and close their own shift.
- Create regular, SC, and PWD sales.
- Select eligible benefit lines and record cash/QR settlement declarations.
- Cannot change protected settings or finalized records.

### Pharmacist or Supervisor

- Perform cashier duties when assigned.
- Approve configured operational exceptions.
- Receive protected permissions explicitly granted by the owner/admin.

### Administrator or Owner

- Manage users, roles, products, settings, inventory, backups, and protected approvals.
- View all reports and audit history.

### Auditor or Read-Only User

- View authorized internal sales records, reports, and audit history.
- Cannot mutate business data.

## Functional Requirements

- Every user has a unique account; shared cashier accounts are prohibited.
- Credentials are hashed with a password-specific algorithm and unique salt.
- Support login, logout, inactivity lock, credential change, disable/enable account, and failed-attempt throttling.
- Require fresh authentication for voids, returns, restore, sensitive export, and other high-risk actions.
- Enforce permissions in the use-case/application layer and local API, not only by hiding UI controls.
- Cashiers cannot change price, cost, tax class, benefit eligibility, stock, global rounding/tax configuration, BNPC state, finalized sales, or backup/restore state.
- Owner, administrator, pharmacist, or supervisor may receive protected permissions.
- Record user, time, device/session, action, result, old value, new value, reason, and entity reference for protected actions.
- Redact customer IDs unless the signed-in role needs them.

## Data

- `User`
- `Role`
- `Permission`
- `Credential`
- `Session`
- `AuthenticationAttempt`
- `AuditEvent`

Do not store plaintext credentials or reusable recovery secrets in logs or exports.

## Acceptance Criteria

- Unauthorized actions fail even when invoked directly below the UI.
- Disabling a user invalidates their active sessions.
- A cashier can complete ordinary checkout and their own shift without protected permissions.
- Every successful or rejected protected action creates the required audit evidence.
- No shared or default production account is required for normal operation.

## Required Tests

- Unit-test every role/permission decision.
- Integration-test login, logout, inactivity lock, failed attempts, account disable, credential change, and fresh authentication.
- Attempt every protected use case and API endpoint as a cashier and read-only user.
- Test session invalidation after logout, account disable, role change, app restart, and server stop.
- Verify secrets and full customer IDs do not appear in logs.

## Dependencies

- Core database and security/keystore support.
- F05 for checkout authorization.
- F09 for approval workflows.
- F10/F11/F13 for export, restore, and dashboard enforcement.

