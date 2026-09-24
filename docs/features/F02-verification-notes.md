# F02 Verification Notes

## Automated test coverage

- Exact-decimal SKU, unit, price, cost, date, pack-conversion, barcode, tax/benefit, and medicine lot/expiry validation.
- Protected catalog create, edit, inactivation, price/tax/eligibility version changes, audit records, stale edits, authorization, and rollback on duplicate barcode (`CatalogEditingTest`).
- CSV preview, Unicode and quoted-comma parsing, row validation, all-or-nothing behavior, and explicitly reviewed valid-subset commit (`CsvCatalogImporterTest`, `CsvCatalogImportSafetyTest`).
- Import checksum, manifest, row-result persistence, and transactional database import path.
- Additive version 4 to 5 migration preserving existing products and assigning legacy prescription class `OTHER` (`CatalogMigrationTest`).
- Multi-lot opening stock imported with corresponding inventory ledger movements.

These tests were added or updated for F02. Verification passed on 2026-09-24 with `:app:testDebugUnitTest`: 32 tests, 0 failures, 0 errors, 0 skipped. The run used Temurin JDK 21.0.12.1 and a temporary Java truststore containing the Avast HTTPS-scanning root already trusted by Windows. The Android SDK installed API 36 and Build Tools 35 for the run. Android Studio's bundled JDK 25 is incompatible with the current Robolectric bytecode instrumenter; use JDK 21 for these unit tests.

## Dependent checks not claimed complete

- F03 supplies the final versioned tax/benefit rule semantics and exact checkout calculation snapshots.
- F06 still owns receiving, adjustments, stock/expiry state, and sale allocation behavior beyond F02 opening movements.
- F10 owns export CSV formula-injection safeguards and report/export audit views.
