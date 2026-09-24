# F06 — Inventory, Lots, and Expiry

**Phase:** 1 — Offline core  
**Status:** Receiving, adjustment, exact pack conversion, ledger-derived inventory view, stock alerts, expiry allocation/disposal, and audit evidence implemented and verified. Sale deduction/concurrency (F05), component stock (F07), return/reversal disposition (F09), and inventory reporting (F10) integrate when those features are implemented.
**Master reference:** [Section 10](../Medtryx_Product_and_Technical_Specification.md#10-feature-f06--inventory-lots-and-expiry)

## Purpose

Maintain auditable on-hand inventory from append-only movements while tracking medicine lot/batch and expiry.

## Movement Ledger

Required movement types:

- `OPENING_BALANCE`
- `RECEIPT`
- `SALE`
- `SALE_REVERSAL`
- `RETURN_SELLABLE`
- `RETURN_DAMAGED`
- `ADJUSTMENT_IN`
- `ADJUSTMENT_OUT`
- `EXPIRED`
- `BUNDLE_ASSEMBLY_IN`
- `BUNDLE_ASSEMBLY_OUT`

Each movement records SKU, signed quantity, unit, lot/expiry when applicable, cost snapshot, source/reference, user, timestamp, and reason.

## Rules

- Derive on-hand stock from movements; do not maintain a freely editable total.
- Track every product by its SKU-specific unit.
- Optional pack conversion is configured per SKU, never globally.
- A receipt entered as packs multiplies by that SKU's configured units-per-pack and records the exact base-unit quantity; reject missing conversion data or results requiring rounding.
- Medicine receipts require lot/batch and expiry.
- Reject receipt of an already expired medicine lot. Keep expired on-hand visible until a protected disposal is recorded.
- Negative stock is blocked.
- Finalized sales deduct inventory in the same F05 transaction.
- Use earliest-expiry-first allocation when operationally possible.
- Allocation excludes expired lots; non-lot SKUs allocate against their aggregate ledger balance.
- A stock adjustment is lot-scoped for medicine SKUs. Outgoing quantities are negative ledger entries and cannot exceed either the SKU or selected-lot balance.
- Expired disposal is an explicit negative `EXPIRED` movement, limited to the selected expired lot's balance.
- Protected receiving, adjustments, and expired disposal require a non-empty reason and audit actor/time/reference/old/new evidence.
- Warn for low stock, expired stock, and near-expiry lots.
- Near-expiry alerts use a store-selected horizon; do not silently assume a number of days.
- Show ledger-derived on-hand, lot balances, warnings, and recent movement details in the inventory view.
- Voids/returns restore stock only through F09-linked movements and only to the appropriate sellable/damaged state.
- Protected roles and reasons are required for adjustments.

## Data

- `InventoryLot`
- `InventoryMovement`
- `StockReceipt`
- `StockAdjustment`
- `ExpiryDisposition`

## Acceptance Criteria

- Displayed on-hand stock always reconciles to the movement ledger.
- Finalization cannot save a sale without its stock movement.
- No operation creates negative stock.
- Medicine stock cannot be received without required lot and expiry.
- Expired stock cannot be sold under the normal workflow.
- Expired lots are excluded from allocation and their disposal is represented by an `EXPIRED` movement.
- Wrong-SKU lots, per-lot overselling, negative stock, missing reasons, and unauthorized inventory mutations are rejected.
- Low-stock and expiry warnings are derived from ledger quantities and the configured near-expiry horizon.
- Historical movement cost and units remain stable after catalog changes.

## Required Tests

- Unit-test every movement type and on-hand derivation.
- Test SKU-specific pack conversion and rounding-free quantity behavior.
- Integration-test receipt, sale, failure rollback, return, reversal, damage, adjustment, and expiry disposal.
- Test earliest-expiry-first allocation with multiple lots and equal expiry dates.
- Test that allocation skips expired stock, non-lot SKUs use aggregate on-hand, and insufficient unexpired stock is rejected.
- Test low-stock threshold and expired/near-expiry alert boundaries with an injected date and explicit horizon.
- Test authorized receipt, lot-scoped adjustment, expired disposal, movement signs, audit evidence, and wrong-product lot rejection.
- Run concurrent attempts to sell the last available unit.
- Reconcile seeded sales and returns to movements and displayed inventory.

## Dependencies

- F01 protected adjustment permission.
- F02 SKU/unit/lot configuration and opening-stock import.
- F05 atomic sale finalization.
- F07 component stock.
- F09 reversal/return disposition.
- F10 inventory reports.

F05 must consume the allocation contract and persist the resulting sale movements in its own atomic transaction. Inventory allocation alone is a read-only plan and does not reserve stock.

