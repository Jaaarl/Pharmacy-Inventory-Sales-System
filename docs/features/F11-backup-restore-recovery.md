# F11 — Backup, Restore, and Recovery

**Phase:** 2 — Controls and reporting  
**Status:** MVP specification  
**Master reference:** [Section 18](../Medtryx_Product_and_Technical_Specification.md#18-feature-f11--backup-restore-and-recovery)

## Purpose

Create encrypted, verifiable, restorable backup packages without exposing the raw database.

## Backup Workflow

- Require a permitted authenticated user.
- Use the tablet system file picker for a user-selected folder visible in the file manager.
- Suggest `Documents/Medtryx Backups` where HarmonyOS supports it.
- Encrypt the complete package with an independent recovery passphrase/key.
- Include a manifest with schema/app version, store ID, creation time, record counts, and cryptographic checksums.
- Verify the written package before reporting success.
- Audit actor, time, destination descriptor, manifest/checksum, and outcome.

The iPad may request backup creation, but the tablet creates and stores it.

## Restore Workflow

- Require fresh protected authentication.
- Select the package with the system file picker.
- Validate header, checksum, encryption, store, schema, and compatibility before mutation.
- Display a preview with source store/date/version and record counts.
- Create and verify a safety backup of current data.
- Restore atomically or return to the unchanged current database.
- Run integrity checks and show a clear result.
- Audit the request and outcome without logging secrets.

## Rules

- Never export an unencrypted raw database.
- Never store the recovery passphrase in the backup or normal logs.
- A same-tablet backup is supported but does not protect against loss/theft/storage failure.
- Preserve the ability to copy encrypted packages to a separate trusted device or drive.
- Do not delete old backups automatically until a retention policy is decided.

## Acceptance Criteria

- A verified package restores an equivalent database.
- Wrong-passphrase, corrupted, truncated, incompatible, and wrong-store packages cannot change live data.
- Interrupted backup/restore cannot leave a false success or partially restored database.
- Safety backup exists before replacement.
- Backup/restore is permission protected and audited.

## Required Tests

- Round trip: seed all feature data, back up, restore into a clean test environment, and compare counts, totals, lots, sequences, users/roles, settings, and audit history.
- Test wrong passphrase, bit corruption, truncation, unsupported schema, wrong store, insufficient storage, cancelled picker, and process interruption.
- Verify checksums and encryption; scan output to ensure recognizable raw database/customer values are absent.
- Test restore migration from every supported prior schema.
- Complete a physical-tablet file-manager backup and restore drill.

## Open Configuration

- Automatic schedule, retention duration, recovery-passphrase custodian, and off-device copy destination remain undecided.

## Dependencies

- F01 protected permission and fresh authentication.
- All persisted feature schemas.
- F12 separate Test Mode backup behavior.
- F13 authorized remote request.

