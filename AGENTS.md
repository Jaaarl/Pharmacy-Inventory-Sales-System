# AGENTS.md

## Purpose

This repository contains **Medtryx**, a local-first pharmacy sales and inventory application for one Philippine pharmacy store.

The product requirements and technical source of truth are in:

- `docs/Medtryx_Product_and_Technical_Specification.md`
- `docs/features/README.md` links the standalone `F01`–`F13` implementation specifications.

Read the master document completely before making architectural, financial, tax, inventory, security, or workflow changes. Before implementing a feature, also read its complete file under `docs/features/`. The master document remains primary. If code, a feature file, and the master specification disagree, do not silently choose one. Preserve data safety and resolve the mismatch. An intentional feature change must update both the master specification and its standalone feature file.

## Confirmed MVP Context

- One pharmacy store.
- Primary device: HUAWEI MatePad 11.5, model `BTKR-W09`.
- Device resources: 8 GB RAM and 256 GB total storage.
- Operating system: HarmonyOS 4.2.0.
- Preferred client stack: Kotlin and Jetpack Compose, subject to a successful device compatibility spike.
- Store tax status: VAT-registered.
- Retail prices are VAT-inclusive.
- Medtryx records cash or QR as a cashier-declared settlement method but does not process or verify payment.
- Official invoices are issued manually outside Medtryx.
- Never ask a cashier to enter a manual invoice number.
- Medtryx automatically generates an internal transaction ID. Never label it as an invoice or receipt number.
- All customer-facing or exported transaction summaries must say `INTERNAL SALES RECORD — NOT AN INVOICE`.
- SC/PWD checkout verifies the physical ID and stores customer name, ID type, ID number, and a checked confirmation. Do not store an ID image.
- BNPC 5% support is included behind an administrator-only feature switch and remains off until policy data is configured.
- Medicine inventory includes individual SKU units, lots/batches, and expiry dates.
- Starting catalog and opening inventory support both manual entry and protected CSV import.
- Backups are encrypted and exported through the tablet's system file picker to a folder visible in its file manager.
- The iPad dashboard is read-only except for authorized export and backup requests by default.

## Product Boundaries

Medtryx is an internal operational tool. For the MVP, do not implement:

- official invoice generation or numbering;
- manual invoice-number entry or reconciliation;
- BIR EIS submission;
- payment gateway, e-wallet, QR verification, or card processing;
- e-prescribing, patient medical records, diagnosis, or medical advice;
- automatic legal/tax classification inferred only from a product name or category;
- multi-branch cloud synchronization; or
- destructive editing of finalized financial or inventory history.

Do not describe internal summaries, exports, PDFs, or screen views as official invoices.

## Delivery Order

Implement in this order unless the specification is intentionally revised:

1. Device compatibility spike.
2. Project scaffolding and automated test foundation.
3. Authentication, roles, and local database.
4. Product catalog, CSV import, lots, expiry, and inventory ledger.
5. Pure Kotlin money, VAT, and discount engine.
6. Checkout, internal transaction IDs, and atomic finalization.
7. Cashier shifts and turnover.
8. Voids, returns, reversals, and audit trail.
9. Reports and CSV export.
10. Bundles, encrypted backup/restore, and isolated Test Mode.
11. Secure local iPad dashboard.

Do not build the complete app before proving on the physical MatePad that Compose, Room/SQLite, foreground services, local socket binding, system file export, keystore access, and restart behavior work.

Use the stable feature IDs `F01`–`F13` in the specification for issues, tests, and implementation notes. A phase is not complete when coding stops; it is complete only when the corresponding feature tests in Section 20.1 and the phase test/exit gate in Section 21 pass.

## Architecture

Use an offline-first, single-source-of-truth design:

```text
Compose UI
    -> application/use-case layer
        -> domain services
            -> repositories
                -> Room/SQLite

Local HTTPS dashboard
    -> the same application/use-case layer
```

Do not duplicate pricing, tax, discount, inventory, or authorization logic in Compose screens or browser JavaScript.

Recommended modules:

```text
app-android
core-model
core-money
core-tax
core-discount
core-inventory
core-database
core-security
feature-auth
feature-catalog
feature-checkout
feature-shifts
feature-reports
feature-settings
local-server
web-dashboard
```

Keep domain modules plain Kotlin wherever practical. UI, database, Android services, and networking should depend on the domain layer, not the reverse.

## Financial Calculation Rules

Never use `Float` or `Double` for money. Use integer centavos or `BigDecimal` with explicit scale and rounding.

Calculate with at least four internal decimal places. Store/display monetary results to two decimals using the approved system-wide rounding policy. Persist the rule version and enough calculation inputs to reproduce each finalized result.

For a regular VATable line with VAT-inclusive prices:

```text
gross            = unitPrice × quantity
vatExclusiveBase = gross ÷ 1.12
vatAmount         = gross - vatExclusiveBase
amountDue         = gross
```

For an SC/PWD-qualified line whose catalog tax class is VATable:

```text
gross                  = unitPrice × quantity
discountBase           = gross ÷ 1.12
vatExemptionAdjustment = gross - discountBase
statutoryDiscount      = discountBase × 20%
amountDue               = discountBase - statutoryDiscount
```

For an SC/PWD-qualified line already VAT-exempt in the product catalog:

```text
gross                  = unitPrice × quantity
discountBase           = gross
vatExemptionAdjustment = 0
statutoryDiscount      = discountBase × 20%
amountDue               = discountBase - statutoryDiscount
```

Required invariants:

- Tax treatment is calculated per sale line, never blindly for the whole cart.
- Product tax class and customer discount eligibility are separate fields.
- SC and PWD are separate reporting types but cannot be stacked on the same line.
- Do not remove VAT twice from a catalog VAT-exempt item.
- Do not apply SC/PWD treatment to unrelated retail lines in a mixed cart.
- `OTHER` discounts must have configured rates, authorization, validity, and stacking rules.
- BNPC is a separate versioned rule, not a variation of the SC/PWD 20% calculation.
- Changing a current product or rule must never recalculate historical sales.

Financial logic changes require table-driven unit tests with exact centavo expectations.

## Product and Inventory Rules

Every product is an individual SKU with its own:

- unit;
- selling price and optional cost;
- VAT classification;
- benefit eligibility;
- prescription/OTC classification;
- reorder level; and
- optional SKU-specific pack conversion.

Do not hard-code `medicine = VAT-exempt` or apply a tax class from the product category alone.

Use an append-only inventory movement ledger. On-hand stock is derived from movements rather than freely edited.

Finalizing a sale must atomically save:

- the sale;
- its sale lines and calculation snapshots;
- the generated internal transaction ID;
- inventory movements;
- settlement declaration; and
- audit events.

If any part fails, roll back all parts.

Negative stock is blocked by default. Medicine receipts require lot/batch and expiry. Prefer earliest-expiry-first lot allocation when operationally possible.

Bundles are virtual by default. Expand a bundle into component sale lines, preserve each component's tax and discount treatment, and deduct component inventory separately.

## Finalized Records, Voids, and Returns

Finalized sales are immutable.

Never delete or directly edit a finalized sale, sale line, calculation, inventory movement, closed shift, or audit event. Corrections use linked reversals and replacement records.

Voids and returns require:

- fresh authorization by a permitted non-cashier role;
- a reason;
- requester and approver identity;
- linked opposite financial/inventory movements; and
- preservation of the original record.

## Roles and Authorization

Use unique user accounts. Do not implement shared cashier credentials.

Cashiers may perform ordinary checkout and their own shift workflow. Cashiers cannot:

- change prices, tax classes, or benefit eligibility;
- adjust inventory directly;
- change global rounding or tax settings;
- enable BNPC;
- void, reverse, or alter finalized sales without approval;
- restore backups; or
- export unrestricted customer-level data.

Protected permissions may be assigned to owner, administrator, pharmacist, or supervisor roles. Enforce permissions in the application/use-case layer, not only by hiding UI elements.

Every protected action must record actor, timestamp, old value, new value, reason, and relevant entity/reference.

## Internal Transaction IDs

Use an immutable UUID as the database identity and generate a unique human-readable Medtryx transaction ID for staff searches.

The human-readable ID must:

- work without internet;
- remain unique for the store;
- survive app restarts and device reboots;
- never be reused after void or reversal; and
- be labeled `Medtryx Transaction ID`.

Do not label it `Invoice No.`, `Receipt No.`, or any equivalent term.

## CSV Import and Export

Catalog/opening-stock import must provide:

- protected-role authorization;
- preview before commit;
- duplicate SKU and barcode detection;
- required-field and enum validation;
- row-level error messages;
- all-or-nothing commit unless the user explicitly chooses a reviewed valid subset; and
- an audit record containing file checksum, counts, actor, and time.

Exports use UTF-8 CSV with stable, versioned columns. Protect against spreadsheet formula injection in user-entered fields beginning with `=`, `+`, `-`, or `@`.

## Security and Privacy

Treat customer names and SC/PWD ID numbers as personal information.

- Minimize collected data.
- Do not store ID or prescription images in the MVP.
- Encrypt sensitive data at rest with keys protected by the device keystore.
- Hash credentials with a password-specific algorithm and unique salts.
- Redact IDs in ordinary screens and reports.
- Auto-lock after inactivity.
- Rate-limit authentication.
- Do not log secrets, credentials, full customer IDs, or sensitive exports.
- Require fresh authentication for destructive or high-risk actions.

The local dashboard must use authenticated, role-scoped sessions and production HTTPS. Never add a trust-all certificate implementation. Plain HTTP is permitted only in Test Mode with synthetic data.

## Local Server and iPad Dashboard

The tablet is the server and source of truth. The iPad is a browser client.

- Bind only to a private/local network interface.
- Start hosting from a visible user action.
- Use a foreground service and persistent notification while hosting.
- Provide a visible stop action.
- Pair with a short-lived, single-use token or QR code.
- Revoke sessions on logout, service stop, unsafe network change, or configured timeout.
- Keep the dashboard read-only except for authorized report export and backup requests by default.
- Never expose the service directly to the public internet.

Android/HarmonyOS background restrictions are a design constraint. Do not promise always-on service behavior until it passes physical-device sleep, lock, battery-optimization, reboot, and network-change tests.

## Backup and Restore

Backups must be encrypted packages, not raw database files.

- Use the system file picker for a user-selected file-manager location.
- Suggest `Documents/Medtryx Backups` when supported.
- Include schema version, app version, store ID, timestamp, record counts, and checksum in the backup manifest.
- Verify a backup after writing it.
- Require protected-role authentication and compatibility checks before restore.
- Create a recoverable safety backup before replacing current data.
- Record backup and restore events.
- Test actual restoration before release.

A backup stored only on the same tablet is not sufficient protection from device loss. Preserve support for copying the encrypted package to separate trusted storage.

## Test Mode

Test Mode uses a physically separate database and a visibly different transaction-ID prefix.

Test Mode must never alter live:

- inventory;
- sales or reports;
- customer records;
- shifts;
- backups; or
- transaction sequences.

Mark all Test Mode screens and exports prominently.

## Testing Requirements

At minimum, maintain:

- pure unit tests for money, VAT, SC/PWD, BNPC, promotion, and rounding rules;
- Room migration tests;
- database transaction tests for checkout finalization and rollback;
- inventory movement and reversal tests;
- permission tests at the use-case/API boundary;
- CSV import validation and export-safety tests;
- backup/restore round-trip tests;
- local server authentication and authorization tests;
- UI tests for critical checkout and shift flows; and
- physical MatePad/iPad integration checks for networking and lifecycle behavior.

Every bug involving money, tax, discount, inventory, authorization, migration, or data loss must receive a regression test.

## Coding Practices

- Prefer small, explicit domain types over primitive strings and booleans.
- Use enums or sealed types for tax, benefit, sale, settlement, inventory movement, and reversal states.
- Make state transitions explicit and validated.
- Keep side effects behind interfaces.
- Use database transactions for multi-record business operations.
- Make migrations additive and tested; never use destructive migration for production data.
- Avoid Google Mobile Services dependencies unless the physical MatePad proves support and the requirement is documented.
- Keep user-visible strings in resources and design for tablet layouts and large touch targets.
- Use Asia/Manila for store reporting boundaries while storing timestamps in UTC.
- Do not silently substitute defaults for tax, price, discount, or permission data.

## Unresolved Decisions and Safe Defaults

The remaining questions are tracked in Section 22 of the product specification. Until answered, use these defaults:

- BNPC: implemented but disabled.
- Other discounts: none beyond explicitly configured types.
- Negative stock: blocked.
- Cashier price override: disabled.
- Bundles: virtual.
- iPad dashboard: read-only except authorized export/backup request.
- QR settlement: unverified declaration with optional reference field.
- Local server: user-visible hosting session, not assumed always-on.
- Backups: manual encrypted local backup; do not invent a retention/deletion policy.

Do not resolve legal, tax, return-policy, retention, or authorization ambiguities by silently hard-coding an assumption. Add a configuration point, use the documented safe default, and note the open decision.

## Definition of Done

A change is complete only when:

- it matches the current specification;
- financial and inventory invariants remain intact;
- permissions are enforced below the UI layer;
- relevant automated tests pass;
- migrations and persisted-data effects are considered;
- sensitive data is not added to logs or insecure storage;
- user-facing text does not misrepresent internal records as invoices;
- documentation is updated for intentional behavior changes; and
- the master specification and affected `docs/features/Fxx-*.md` file remain synchronized; and
- the change has been tested in proportion to its risk, including on the physical target device when it affects Android/HarmonyOS behavior.

## Completion Discipline

When the user requests implementation of a feature or implementation-priority document:

- Treat the request as one continuous task.
- Do not stop after a partial subfeature, successful compile, test, or checkpoint commit.
- Do not ask whether to continue unless blocked by missing authority, missing required information, or an unrecoverable technical issue.
- Continue until every applicable requirement, data model, acceptance criterion, test, documentation update, and verification step in the requested scope is complete.
- Make commits only when the user explicitly asks, or when the entire requested feature is complete. Do not use partial commits as a reason to pause work.
- If physical-device or later-feature integration checks cannot be run, document them as deferred and continue all remaining implementable work.
- Final responses must clearly state either the full requested scope is complete and verified, or the exact blocker preventing completion.
