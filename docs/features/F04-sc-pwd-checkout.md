# F04 — SC/PWD Checkout

**Phase:** 1 — Offline core  
**Status:** MVP specification  
**Master reference:** [Section 8](../Medtryx_Product_and_Technical_Specification.md#8-feature-f04--scpwd-checkout)

## Purpose

Apply SC or PWD treatment only to explicitly selected eligible lines while recording the minimum approved customer/ID evidence.

## Workflow

1. Cashier creates the cart.
2. Cashier selects `NO_DISCOUNT`, `SENIOR_CITIZEN`, `PWD`, or another enabled benefit.
3. For SC/PWD, require customer name, ID type, ID number, and confirmation that the physical ID was checked.
4. Show each line's tax class and benefit eligibility.
5. Allow selection only of qualified lines unless an authorized override policy exists.
6. Recalculate gross, VATable sales, VAT, VAT-exempt sales, zero-rated sales, VAT adjustment, discount, and amount due.
7. Display `SC/PWD DISCOUNT WILL BE APPLIED. CONFIRM?` as a blocking confirmation.
8. Finalize through F05 without requesting a manual invoice number.

## Privacy Boundary

- Store customer name, ID type, ID number, and checked confirmation.
- Do not store an ID image.
- Do not capture prescription, booklet, diagnosis, birth date, ID-expiry, or representative data in the MVP.
- Redact ID numbers in ordinary views and reports.

## Rules

- SC and PWD remain separate transaction/report types.
- A customer qualifying for both receives only one statutory benefit on a line.
- Only selected eligible lines receive the benefit.
- An unrelated retail item remains regular even when another line qualifies.
- Product tax class and benefit eligibility remain separate.
- Cashiers cannot override an ineligible line without an explicitly authorized workflow.

## Acceptance Criteria

- The app blocks SC/PWD completion until required customer/ID fields and confirmation are present.
- Mixed carts retain correct line-level results.
- SC/PWD stacking is impossible.
- No prohibited image or extra personal-data field is captured.
- Stored evidence and calculation snapshots can support the separate SC/PWD reports.

## Required Tests

- UI-test customer name, ID type/number, ID confirmation, line selection, and blocking confirmation.
- Test eligible, ineligible, mixed, already VAT-exempt, no-selected-line, and multiple-quantity carts.
- Test attempted SC/PWD stacking and direct unauthorized eligibility override.
- Verify logs, reports, exports, and ordinary screens redact full ID values.
- Test app/process restart before and after finalization without leaking draft personal data.

## Dependencies

- F01 authorization.
- F02 product eligibility.
- F03 calculation engine.
- F05 finalization and internal IDs.
- F10 SC/PWD reports.

