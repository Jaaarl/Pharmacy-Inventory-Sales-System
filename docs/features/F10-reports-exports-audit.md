# F10 — Reports, CSV Exports, and Audit Reports

**Phase:** 2 — Controls and reporting  
**Status:** MVP specification  
**Master reference:** [Section 14](../Medtryx_Product_and_Technical_Specification.md#14-feature-f10--reports-exports-and-audit-reports)

## Purpose

Produce reproducible operational, tax, discount, inventory, shift, and audit views from immutable source ledgers and stored calculation snapshots.

## Daily and Monthly Reports

Include at minimum:

- gross/total sales;
- VATable sales and VAT;
- VAT-exempt sales, separated where useful by catalog versus statutory treatment;
- zero-rated sales;
- SC and PWD sales/discounts separately;
- BNPC and other discounts when enabled;
- net sales/amount due;
- quantity, cost of goods, and margin when cost exists;
- cash and QR-declared sales;
- voids, returns, and corrections;
- Medtryx transaction ID;
- inventory remaining, low stock, and near expiry; and
- shift cash variance.

## CSV Export

- Export UTF-8 CSV with stable versioned columns.
- Use ISO 8601 timestamps and Asia/Manila report boundaries.
- Quote/escape commas, quotes, newlines, and Unicode correctly.
- Neutralize spreadsheet formula prefixes in user-entered text: `=`, `+`, `-`, and `@`.
- Support date range and report-type selection.
- Audit actor, time, parameters, output checksum, row count, and destination/result.
- Treat `.xlsx` as optional; CSV is required.

## Audit Reports

Provide protected reports for price/tax/eligibility changes, discount overrides, voids/returns, stock adjustments, authentication/admin events, backups/restores, exports, and failed logins.

## Rules

- Aggregate from stored sale-line snapshots and ledgers, never recalculate history using current rules.
- Store timestamps in UTC and apply Asia/Manila boundaries at reporting time.
- Redact customer ID values unless the role and report purpose require access.
- Internal transaction views/exports must say `INTERNAL SALES RECORD — NOT AN INVOICE` where document-like.

## Acceptance Criteria

- Report totals reconcile to source lines and ledgers to the centavo.
- Day/month boundaries remain correct across UTC conversion.
- A current product/rule edit cannot change a historical report.
- Exports open correctly in Excel-compatible tools without executing user-supplied formulas.
- Unauthorized users cannot access sensitive exports/audit reports.

## Required Tests

- Seed VATable, exempt, zero-rated, SC, PWD, mixed, bundle, void, return, cost, cash, and QR records and independently reconcile every report total.
- Test empty, single-row, high-volume, and historical-rule datasets.
- Test UTC timestamps around Asia/Manila midnight and month end.
- Test CSV commas, quotes, newlines, Unicode, leading zeros, formula-like text, and stable schema versions.
- Test report/export permission and ID redaction.

## Dependencies

- F01 report permissions.
- F03 stored calculations.
- F05/F09 financial records.
- F06 inventory ledger.
- F07 bundle snapshots.
- F08 shifts.
- F13 dashboard views.

