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
- Enforce a 10 MiB UTF-8 file limit and a 50,000 data-row limit; accept an optional UTF-8 BOM, reject malformed UTF-8, duplicate headers, and rows whose field count does not match the header.
- Detect duplicate SKU and barcode values.
- Validate required fields, exact decimals, allowed enum values, dates, units, pack conversions, and lot/expiry requirements.
- Report errors by row and column without silently changing input.
- Default import behavior is all-or-nothing. A reviewed valid subset may be committed only after explicit confirmation.
- Audit the file checksum, actor, timestamp, accepted/rejected counts, and resulting entity IDs.

Required headers are `sku`, `name`, `unit`, `selling_price`, `tax_class`, `tax_source`, `tax_valid_from`, `benefit_eligibility`, `prescription_class`, `reorder_level`, and `requires_lot_expiry`. Optional columns are `barcodes` (pipe-separated), `generic_name`, `brand`, `strength`, `dosage_form`, `pack_size`, `unit_cost`, `tax_valid_to`, and opening-stock columns. Use either `opening_quantity`, `lot_number`, `expiry_date`, and `supplier_reference` for one lot, or a quoted multiline `opening_lots` cell with one `lot | YYYY-MM-DD | quantity | optional supplier` record per line. The two lot encodings cannot be mixed in one row. Medicine lots require a batch and expiry.

Commit must verify that the selected file checksum is unchanged since preview, rebuild the preview from the source, recheck catalog duplicates inside the database transaction, then commit the complete file or only the explicitly selected valid rows. Opening lots and their ledger movements are part of the same transaction.

## Authorization and History

- Cashiers cannot change price, cost, tax class, or eligibility.
- Owner, administrator, pharmacist, or supervisor may receive approval permission.
- Store old/new values, actor, reason, and effective date for protected changes.
- Price, tax, and benefit edits create effective-dated versions; the prior version ends the day before a later version starts. The SKU and inventory unit do not change during catalog editing. Lot/expiry requirements can change only when no stock is on hand.
- Persist the prescription/OTC class in the product record and migrate legacy products additively to `OTHER`.
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
- Multiple medicine opening lots can be imported for one SKU in one reviewed row.
- Invalid imports cannot partially corrupt the catalog or opening stock.
- Historical transactions retain their original product/calculation snapshots.
- Cashiers cannot edit protected catalog fields.

## Required Tests

- Unit-test SKU, barcode, unit, price, cost, enum, date, and pack-conversion validation.
- Test empty, duplicate, malformed, Unicode, very large, and mixed-validity CSV files.
- Test UTF-8 BOM handling, strict decoder failures, incorrect column counts, changed-after-preview files, and multi-lot opening stock.
- Test rollback when any database write in a full import fails.
- Migration-test catalog schema changes with existing referenced products.
- Test edits for effective-dated versions, old/new audit data, role authorization, stale edit rejection, and rollback on duplicate barcode.
- Test protected-field authorization below the UI.

## Dependencies

- F01 permissions.
- F03 tax/eligibility types.
- F06 opening stock, lots, and expiry.
- F10 shared CSV safety conventions.

