# F09 — Voids, Returns, and Corrections

**Phase:** 2 — Controls and reporting  
**Status:** MVP specification; return policy configuration remains open  
**Master reference:** [Section 13](../Medtryx_Product_and_Technical_Specification.md#13-feature-f09--voids-returns-and-corrections)

## Purpose

Correct operational errors without deleting or rewriting finalized financial and inventory history.

## Workflows

- Void before completion/settlement where the sale state permits it.
- Full return.
- Partial return.
- Damaged/non-sellable return.
- Data correction through linked reversal and replacement sale.

## Rules

- Require fresh authentication by a permitted non-cashier role.
- Require a managed reason plus optional notes.
- Preserve requester and approver separately where policy requires.
- Never delete or directly edit the original sale, line, calculation, inventory movement, shift, or audit event.
- Create linked opposite financial/tax/report entries.
- Restore inventory only through F06 movement types and the selected sellable/damaged disposition.
- Preserve the original internal transaction ID and link replacement/supporting records.
- Product/rule changes after the original sale do not alter reversal of the original snapshots.

## Data

- `Return`
- `ReturnLine`
- `Reversal`
- `Approval`
- `CorrectionReason`
- linked `InventoryMovement` and `AuditEvent`

## Acceptance Criteria

- Every correction traces to its original transaction and approver/reason.
- Original finalized records remain byte/logically immutable.
- Financial, tax, settlement, shift, inventory, and report effects reverse correctly.
- Unauthorized or stale-authentication attempts fail and are audited.
- Damaged returns never increase sellable stock.

## Required Tests

- Integration-test allowed void, full return, partial return, damaged return, and replacement sale.
- Test returns across different shifts/report dates while preserving original dates.
- Test unauthorized, missing-reason, excessive-quantity, duplicate, and already-returned attempts.
- Reconcile financial and inventory ledgers before and after every workflow.
- Verify current catalog/rule changes do not affect reversal amounts.

## Open Configuration

- Return eligibility, time window, excluded products, and approving roles remain undecided.
- One-sale protected price override versus product-master update remains undecided.

## Dependencies

- F01 protected approval.
- F03 original calculation snapshots.
- F05 original sale linkage.
- F06 disposition movements.
- F08 shift/cash effects.
- F10 reporting.

