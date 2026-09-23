# F01 Verification Notes

## Automated checks completed

- Role baselines and explicit-grant restrictions for cashier, pharmacist, supervisor, administrator, owner, and auditor.
- Unique-salt PBKDF2 credential hashing and rejection of incorrect credentials.
- Owner bootstrap, login/logout, lock, throttling, credential changes, disable/enable, role change, session revocation, fresh authentication, and restart/session invalidation.
- Direct use-case authorization attempts by cashier and auditor for all F01-relevant protected operations. Both denial and successful high-risk authorization produce audit evidence with actor, timestamp, device, session, action, result, reason, and entity reference.

## Checks deliberately deferred to dependent features

- F05 checkout and F08 own-shift authorization must invoke `ProtectedActionAuthorizer` at their application boundaries; their behavior is not implemented by F01.
- F09 void/return flows must require `FINALIZED_SALE_VOID_OR_REVERSE` with a fresh credential and a reason.
- F10/F11 must use `SENSITIVE_EXPORT` and `BACKUP_RESTORE` with fresh credentials. Backup/restore remains out of F01 scope.
- F13 local API and server-stop handling must call `ProtectedActionAuthorizer` and `revokeDeviceSessions("LOCAL_SERVER_STOP")`. The local server is out of F01 scope.
- The Phase 0 physical MatePad compatibility checks, including device-keystore-backed at-rest encryption validation and restart behavior on HarmonyOS, require the target hardware and are not represented as passed by local JVM tests.
