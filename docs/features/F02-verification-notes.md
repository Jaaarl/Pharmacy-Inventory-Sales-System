# F02 Verification Notes

## Automated test coverage

- Exact-decimal SKU, unit, price, cost, date, pack-conversion, barcode, tax/benefit, and medicine lot/expiry validation.
- Protected catalog create, edit, inactivation, price/tax/eligibility version changes, audit records, stale edits, authorization, and rollback on duplicate barcode (`CatalogEditingTest`).
- CSV preview, Unicode and quoted-comma parsing, row validation, all-or-nothing behavior, and explicitly reviewed valid-subset commit (`CsvCatalogImporterTest`, `CsvCatalogImportSafetyTest`).
- Import checksum, manifest, row-result persistence, and transactional database import path.
- Additive version 4 to 5 migration preserving existing products and assigning legacy prescription class `OTHER` (`CatalogMigrationTest`).
- Multi-lot opening stock imported with corresponding inventory ledger movements.

These tests were added or updated for F02. Execution status is pending: `:app:testDebugUnitTest` cannot start because the Gradle wrapper cannot validate the TLS certificate while downloading its pinned Gradle 9.3.0 distribution (`PKIX path building failed`).

## Dependent checks not claimed complete

- F03 supplies the final versioned tax/benefit rule semantics and exact checkout calculation snapshots.
- F06 still owns receiving, adjustments, stock/expiry state, and sale allocation behavior beyond F02 opening movements.
- F10 owns export CSV formula-injection safeguards and report/export audit views.
