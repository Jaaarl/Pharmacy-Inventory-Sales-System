# F06 — Inventory, Lots, and Expiry

**Phase:** 1 — Offline core  
**Status:** MVP specification  
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
- Medicine receipts require lot/batch and expiry.
- Negative stock is blocked.
- Finalized sales deduct inventory in the same F05 transaction.
- Use earliest-expiry-first allocation when operationally possible.
- Warn for low stock, expired stock, and near-expiry lots.
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
- Historical movement cost and units remain stable after catalog changes.

## Required Tests

- Unit-test every movement type and on-hand derivation.
- Test SKU-specific pack conversion and rounding-free quantity behavior.
- Integration-test receipt, sale, failure rollback, return, reversal, damage, adjustment, and expiry disposal.
- Test earliest-expiry-first allocation with multiple lots and equal expiry dates.
- Run concurrent attempts to sell the last available unit.
- Reconcile seeded sales and returns to movements and displayed inventory.

## Dependencies

- F01 protected adjustment permission.
- F02 SKU/unit/lot configuration and opening-stock import.
- F05 atomic sale finalization.
- F07 component stock.
- F09 reversal/return disposition.
- F10 inventory reports.

