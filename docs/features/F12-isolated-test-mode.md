# F12 — Isolated Test Mode

**Phase:** 2 — Controls and reporting  
**Status:** MVP specification  
**Master reference:** [Section 19](../Medtryx_Product_and_Technical_Specification.md#19-feature-f12--isolated-test-mode)

## Purpose

Allow staff to practice and validate workflows without changing live sales, stock, customers, shifts, reports, backups, or transaction sequences.

## Isolation Requirements

- Use a physically separate database, not a boolean column in live tables.
- Use a visibly distinct internal transaction-ID prefix.
- Require protected authorization to switch modes.
- Close/reopen repositories and clear sensitive in-memory state when switching.
- Never share live inventory, shifts, customers, reports, backup packages, or sequences.
- Mark every Test Mode screen and export prominently.
- Test Mode outputs state that they are synthetic and not live internal records/invoices.

## Required Scenarios

- Regular VATable and catalog VAT-exempt sale.
- Zero-rated sale when enabled.
- SC and PWD sale for VATable and already-exempt items.
- Mixed tax and mixed eligibility carts.
- Ineligible-line and double-benefit attempt.
- Bundle with differently treated components.
- Cash and QR declarations.
- Void, partial/full/damaged return.
- Inventory deduction/reversal and shift turnover.
- Restart/reboot recovery.
- Backup/restore using test-only artifacts.
- Database migration and CSV import/export.

## Acceptance Criteria

- No test action changes any live count, record, file, report, or sequence.
- Mode is unmistakable in UI and exports.
- A user cannot switch mode with a draft sale, open protected operation, or uncommitted transaction.
- Test backup/restore cannot select or overwrite live storage by default.
- Test results reproduce the same domain calculations as live mode.

## Required Tests

- Snapshot live counts/sequences, perform every supported Test Mode mutation, and prove the snapshots are unchanged.
- Attempt cross-database entity IDs and references; all must fail.
- Test mode switch during draft checkout, import, backup, and restore.
- Test process death/restart and verify the selected mode is safe and visible.
- Run F03 calculation tests against both live/test repository configurations.

## Dependencies

- F01 authorization.
- Separate database construction/migrations for all persisted features.
- F05 transaction-ID prefix.
- F10 export labels.
- F11 test-only backup separation.

