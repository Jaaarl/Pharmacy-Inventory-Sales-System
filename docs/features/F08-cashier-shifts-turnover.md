# F08 — Cashier Shifts and Cash Turnover

**Phase:** 1 — Offline core  
**Status:** Implemented in app; JVM and physical-device gates tracked in the roadmap
**Master reference:** [Section 12](../Medtryx_Product_and_Technical_Specification.md#12-feature-f08--cashier-shifts-and-cash-turnover)

## Purpose

Attribute sales to individual cashier shifts and reconcile expected physical cash at turnover.

## Shift Opening

- Require an authenticated cashier.
- Record opening cash float, device/store, user, and timestamp.
- Prevent overlapping open shifts for the same cashier/device/store under the MVP policy.

## Shift Closing

```text
expectedCash = openingFloat
             + finalizedCashSales
             + cashIn
             - cashRefunds
             - cashOut

variance = actualCashCount - expectedCash
```

- QR and other non-cash declarations do not enter expected cash.
- Support actual cash count and optional denomination entry.
- Require a note for non-zero variance.
- Require supervisor approval above the configured variance threshold.
- Closed shifts are immutable; corrections use adjustments.

## Turnover Report

Include cashier/shift IDs, open/close times, opening float, sales totals, cash, QR, refunds, cash movements, expected/actual cash, variance, voids/reversals, and cashier/supervisor acknowledgement.

## Acceptance Criteria

- Every live cashier sale belongs to the correct active shift.
- Expected cash follows the formula exactly.
- QR never increases expected physical cash.
- Unauthorized users cannot close another cashier's shift or approve a protected variance.
- A closed shift cannot be edited or reopened directly.

## Required Tests

- Test opening float, cash sale, QR sale, cash in/out, cash refund, actual count, and variance.
- Test zero, positive, negative, and threshold-boundary variances.
- Test denomination totals and mismatch handling.
- Test duplicate shift opening, wrong-cashier close, interrupted close, and immutable closed shifts.
- Reconcile turnover report totals to F05 and F09 records.

## Open Configuration

- The variance amount requiring supervisor approval remains undecided.
- Until configured by an authorized user, every non-zero variance requires a different supervisor's approval. Threshold changes require fresh authentication and an audit record.

## Implementation Notes (2026-09-24)

- Room schema 7 adds shifts, active-shift claims, append-only cash movements, versioned variance policies, and post-close adjustments. Migration 6→7 leaves historical sales with a null shift assignment.
- New sales require the active cashier/device/store shift and snapshot its ID. Checkout cannot open or finalize without that shift.
- Shift opening, cash-in/out, cash/QR totals, cash refunds, denomination reconciliation, exact expected-cash calculation, threshold review, fresh-PIN supervisor acknowledgement, and immutable close are implemented in the local UI and application service.
- Protected threshold changes and post-close adjustments require fresh authentication; authorizations and state changes are audited.
- F09 still needs to connect real approved void/return records to turnover. F10 still owns formal reports and exports. MatePad lifecycle verification remains a release gate.

## Dependencies

- F01 cashier and supervisor permissions.
- F05 settlement declarations and sale attribution.
- F09 cash refunds/voids.
- F10 turnover reporting.

