# F05 — Sales, Settlement, and Internal Transaction IDs

**Phase:** 1 — Offline core  
**Status:** Implemented; 60-test JVM suite passes. Compose UI automation and target-device gates remain open.
**Master reference:** [Section 9](../Medtryx_Product_and_Technical_Specification.md#9-feature-f05--sales-settlement-and-internal-transaction-recording)

## Purpose

Finalize an internally recorded sale atomically, record an unverified settlement declaration, and generate a unique internal reference without participating in official invoice numbering.

## Sale States

```text
DRAFT -> AWAITING_CONFIRMATION -> FINALIZED
                                  -> VOIDED_BY_REVERSAL
                                  -> PARTIALLY_RETURNED
                                  -> FULLY_RETURNED
```

Finalized records are immutable. Later changes use F09 reversal/correction records.

## Settlement Declaration

- Support `CASH`, `QR`, and admin-enabled methods.
- The value is a cashier declaration, not verified payment processing.
- QR may include an optional external reference and `CUSTOMER_SHOWED_SUCCESS` confirmation.
- QR amounts never enter expected physical cash in F08.

## Finalization Transaction

One database transaction must save:

- sale header;
- sale-line product/pricing/tax/eligibility snapshots;
- F03 line calculations and totals;
- settlement declaration;
- F06 inventory movements;
- internal transaction identity/sequence; and
- audit events.

Any failure rolls back all records.

## Internal Transaction IDs

- Use immutable UUIDs for database identity.
- Generate a human-readable, store-unique identifier automatically, such as `MTX-20260923-000123`.
- Work offline and remain unique across restart, reboot, void, and concurrent attempts.
- Never reuse an identifier.
- Always label it `Medtryx Transaction ID`.
- Never label it as an invoice or receipt number.
- Checkout must not contain a manual invoice-number field.

## Internal Summary

Every screen/export resembling a transaction document must state:

> **INTERNAL SALES RECORD — NOT AN INVOICE**

The summary may show item and tax/discount details, transaction ID, cashier, shift, time, and settlement declaration. It must not claim to satisfy official invoice issuance.

## Acceptance Criteria

- Partial finalization is impossible.
- Every finalized sale has one unique internal ID and matching inventory/audit records.
- No manual invoice number is requested, stored, reconciled, or generated.
- Settlement declaration is clearly unverified when appropriate.
- Historical line snapshots remain unchanged after catalog/rule edits.

## Required Tests

- Inject failures before/after every write in finalization and verify total rollback.
- Test cash, QR, optional QR reference, and disabled settlement types.
- Test ID uniqueness during rapid/concurrent attempts, restart, database reopen, and physical-device reboot.
- UI-test that no invoice input exists and every internal summary has the required label.
- Test duplicate submission/idempotency behavior from repeated confirmation taps.

## Dependencies

- F01 authorization and cashier identity.
- F02 snapshots.
- F03 calculations.
- F04 benefit evidence.
- F06 inventory movements.
- F08 active shift rules.
- F09 corrections.

## Implementation Notes

- Room schema version 6 adds sales, immutable line snapshots, lot allocations, daily transaction sequences, and protected rounding approvals through an additive 5→6 migration.
- The checkout use case rechecks active cashier authorization inside the database transaction, calculates against effective-dated catalog versions, generates `MTX-YYYYMMDD-000001` IDs using Asia/Manila dates, and atomically writes sale, lines, F06 movements/allocations, and the finalization audit event.
- Cash and QR are the only settlement values. QR reference and customer-shown-success are recorded as cashier declarations, with no payment verification. No invoice-number field exists; the summary uses `INTERNAL SALES RECORD — NOT AN INVOICE`.
- Repeated confirmation with the same UUID idempotency key returns the existing sale without a second stock deduction. Database triggers protect finalized sale rows, calculation lines, allocations, rounding approvals, and inventory movements from update/delete.
- F03 snapshots use a versioned binary/Base64 codec. Customer name and full SC/PWD ID are encrypted with AES-GCM using Android Keystore; the ordinary summary exposes only the last four ID characters.
- Room/Robolectric tests cover rollback checkpoints, concurrent last-unit checkout, FEFO allocation, QR/CASH, sequential IDs, idempotency, cashier permission boundaries, migration, and snapshot round-trip. Physical MatePad restart/reboot and UI automation evidence remain roadmap gates.

