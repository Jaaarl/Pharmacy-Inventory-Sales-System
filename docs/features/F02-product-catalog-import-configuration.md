# F02 — Product Catalog, CSV Import, and Configuration

**Phase:** 1 — Offline core  
**Status:** MVP specification  
**Master reference:** [Section 6](../Medtryx_Product_and_Technical_Specification.md#6-feature-f02--product-catalog-import-and-configuration)

## Purpose

Maintain independently configurable pharmacy and retail SKUs without inferring tax or benefit treatment from broad product categories.

## Product Requirements

Each SKU includes:

- immutable internal ID and unique SKU code;
- optional multiple barcodes;
- product, generic, and brand names;
- strength and dosage form where applicable;
- SKU-specific inventory/sales unit;
- optional pack conversion for that SKU;
- VAT-inclusive selling price and optional unit cost;
- catalog tax class: `VATABLE`, `VAT_EXEMPT`, or `ZERO_RATED`;
- tax authority/source and effective dates;
- benefit eligibility: `SC_PWD_20`, `BNPC_5`, `OTHER`, or `NONE`;
- prescription/OTC classification;
- active/inactive state and reorder level; and
- lot/expiry requirements.

Do not hard-code `medicine = VAT-exempt`. Tax and eligibility are separate, versioned fields approved per SKU.

## Manual Entry and CSV Import

- Support individual product and opening-stock entry.
- Support protected CSV import with preview before commit.
- Detect duplicate SKU and barcode values.
- Validate required fields, exact decimals, allowed enum values, dates, units, pack conversions, and lot/expiry requirements.
- Report errors by row and column without silently changing input.
- Default import behavior is all-or-nothing. A reviewed valid subset may be committed only after explicit confirmation.
- Audit the file checksum, actor, timestamp, accepted/rejected counts, and resulting entity IDs.

## Authorization and History

- Cashiers cannot change price, cost, tax class, or eligibility.
- Owner, administrator, pharmacist, or supervisor may receive approval permission.
- Store old/new values, actor, reason, and effective date for protected changes.
- Referenced products are inactivated, never deleted.
- Historical sale snapshots never change when current catalog data changes.

## Data

- `Product`
- `ProductBarcode`
- `ProductPriceVersion`
- `TaxClassVersion`
- `BenefitRuleVersion`
- `InventoryLot`
- `ImportManifest`
- `ImportRowResult`

## Acceptance Criteria

- Manual entry and valid CSV import create equivalent product records.
- Each SKU can have independent unit, tax, eligibility, and pricing rules.
- Invalid imports cannot partially corrupt the catalog or opening stock.
- Historical transactions retain their original product/calculation snapshots.
- Cashiers cannot edit protected catalog fields.

## Required Tests

- Unit-test SKU, barcode, unit, price, cost, enum, date, and pack-conversion validation.
- Test empty, duplicate, malformed, Unicode, very large, and mixed-validity CSV files.
- Test rollback when any database write in a full import fails.
- Migration-test catalog schema changes with existing referenced products.
- Test protected-field authorization below the UI.

## Dependencies

- F01 permissions.
- F03 tax/eligibility types.
- F06 opening stock, lots, and expiry.
- F10 shared CSV safety conventions.

