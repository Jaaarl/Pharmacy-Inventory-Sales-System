# MVP Implementation Priorities

## Immediate work: finish F02

1. Build protected catalog editing for price, cost, tax class/source/effective dates, benefit eligibility, reorder levels, barcode management, and inactivation. Preserve versions and full audit old/new values.
2. Build the tablet catalog/manual-entry and CSV-preview UI, including row-and-column errors and explicit reviewed-subset confirmation.
3. Complete CSV safeguards and tests: duplicate values within a file, malformed/empty/large input, injected database failure rollback, manifest/audit evidence, and manual-entry/import equivalence.
4. Add F02 migration tests for referenced products.

## MVP delivery order after F02

1. **F06 — Inventory, lots, and expiry:** turn opening stock into append-only inventory movements; enforce lot/expiry and negative-stock controls.
2. **F03 — Tax, money, and discounts:** implement exact-centavo VAT, SC/PWD, rounding, and disabled-by-default BNPC calculations.
3. **F04/F05 — Checkout and sales finalization:** perform qualified-line selection, settlement declaration, internal transaction IDs, and atomic sales/inventory finalization.
4. **F08 — Cashier shifts and turnover:** deliver opening/closing cash accountability.
5. **F09–F13:** reversals, reports, backups, Test Mode, and the local iPad dashboard.

## Deferred validation

Before pilot or production use, perform the physical MatePad checks for keystore/storage protection, restart/reboot session behavior, and HarmonyOS lifecycle handling. Do not mark these checks as passed from local JVM tests.
