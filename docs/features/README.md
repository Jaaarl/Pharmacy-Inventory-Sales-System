# Medtryx Feature Specifications

These files divide the Medtryx MVP into independently implementable and testable feature areas.

The primary product source of truth remains [`Medtryx_Product_and_Technical_Specification.md`](../Medtryx_Product_and_Technical_Specification.md). If a feature document conflicts with the master specification, stop and resolve the mismatch rather than silently choosing one. Intentional feature changes must update both documents.

| ID  | Feature                                         | Phase   | Specification                                      |
| --- | ----------------------------------------------- | ------- | -------------------------------------------------- |
| F01 | Users, authentication, and permissions          | Phase 1 | [F01](F01-users-authentication-permissions.md)     |
| F02 | Product catalog, CSV import, and configuration  | Phase 1 | [F02](F02-product-catalog-import-configuration.md) |
| F03 | Tax, money, and discount engine                 | Phase 1 | [F03](F03-tax-money-discount-engine.md)            |
| F04 | SC/PWD checkout                                 | Phase 1 | [F04](F04-sc-pwd-checkout.md)                      |
| F05 | Sales, settlement, and internal transaction IDs | Phase 1 | [F05](F05-sales-settlement-transaction-ids.md)     |
| F06 | Inventory, lots, and expiry                     | Phase 1 | [F06](F06-inventory-lots-expiry.md)                |
| F07 | Product bundles                                 | Phase 2 | [F07](F07-product-bundles.md)                      |
| F08 | Cashier shifts and turnover                     | Phase 1 | [F08](F08-cashier-shifts-turnover.md)              |
| F09 | Voids, returns, and corrections                 | Phase 2 | [F09](F09-voids-returns-corrections.md)            |
| F10 | Reports, CSV exports, and audit reports         | Phase 2 | [F10](F10-reports-exports-audit.md)                |
| F11 | Backup, restore, and recovery                   | Phase 2 | [F11](F11-backup-restore-recovery.md)              |
| F12 | Isolated Test Mode                              | Phase 2 | [F12](F12-isolated-test-mode.md)                   |
| F13 | Secure local iPad dashboard                     | Phase 3 | [F13](F13-local-ipad-dashboard.md)                 |

## Shared Rules

- Use exact decimal/integer-centavo money calculations, never floating point.
- Preserve immutable finalized financial and inventory history.
- Enforce permissions below the UI layer.
- Keep tax class separate from discount eligibility.
- Never present an internal Medtryx record as an official invoice.
- Test every change in proportion to financial, inventory, security, migration, and data-loss risk.
