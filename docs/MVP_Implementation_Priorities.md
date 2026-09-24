# Medtryx MVP Delivery Roadmap

This roadmap turns the delivery plan in [the master specification](Medtryx_Product_and_Technical_Specification.md#21-delivery-plan) into an ordered set of work packages, dependencies, and exit gates. The feature specifications and Section 20 test matrix remain the requirements source of truth.

## How to read this roadmap

- Work packages are ordered. A later package may start early only when its required interface or decision is already agreed and the work cannot put live data at risk.
- A feature is not complete when code exists. Its required Section 20 tests, relevant migration and regression checks, documentation, and applicable device checks must pass.
- Phase gates are release gates. A failed gate means the phase remains open; record the failure and corrective work before proceeding to the next dependent phase.
- Physical-device results must be recorded separately from emulator/JVM results. Do not claim a MatePad or iPad check passed without using that device.
- Legal, tax, return, retention, and authorization questions in Section 22 remain explicit decisions. Use the documented safe defaults until an authorized decision is recorded.

## Current position from repository evidence

The repository contains a Kotlin/Compose Android compatibility scaffold, Room databases, F01 authentication/authorization, F02 catalog/import code, and F06 inventory workflows. F02 has a connected create/edit/inactivate UI, protected and effective-dated field changes, CSV preview and reviewed-subset commit, opening-lot ledger movements, and an additive prescription-class migration. F06 has ledger-derived stock and lot balances, protected receiving and lot-scoped adjustments, exact SKU-specific pack conversion, signed outgoing movements, expired-lot exclusion and disposal, low-stock/expiry warnings with an explicit near-expiry horizon, cost snapshots, audit evidence, and an inventory screen with recent movements. `:app:testDebugUnitTest` passed on 2026-09-24 with 40 tests and no failures for the then-implemented scope. F03 now has a pure Kotlin line-level calculation engine with centavo money, explicit approved rounding and versioned rate inputs, effective-dated catalog snapshots, VAT/SC/PWD treatment, explicit promotion selection/stacking, mixed-cart aggregation, and BNPC disabled until a complete approved capped policy is supplied. `:app:compileDebugKotlin` passed on 2026-09-24. F03's required table-driven, property, serialization, and historical-version acceptance tests remain outstanding; calculation snapshots also await F05 persistence/checkout integration. F03 is therefore implemented but not verified. The next work is F03 verification and F05 integration. F06 sale-deduction/concurrency remains with F05, component stock with F07, return/reversal integration with F09, and inventory reports with F10.

The owner reports that the physical-device compatibility checks have been run successfully, so Phase 0 is treated as **passed by owner confirmation**. The detailed device results are not recorded in this repository, so this status is based on that confirmation rather than independently reviewed device evidence. This status update is not a fresh device or automated test run.

## Phase map

| Phase | Outcome | Feature coverage | Gate |
| --- | --- | --- | --- |
| 0. Device and policy gate | Prove the target platform can support the app and record setup decisions | Platform foundation for F01–F13 | Physical BTKR-W09 spike passes, or an approved device/architecture decision is recorded |
| 1. Offline operational core | A cashier can securely maintain stock, calculate a sale, finalize it atomically, and close a shift offline | F01–F06, F08 | Automated, migration, rollback, and MatePad smoke checks pass |
| 2. Controls and recovery | Corrections, bundles, reporting, backup/restore, and isolated practice are safe and reconcilable | F07, F09–F12 | Reconciliation, reversal, restore, and isolation gates pass; Phase 1 regression is green |
| 3. Local iPad dashboard | An authorized iPad can read and request permitted outputs over a protected local session | F13, remote F10/F11 actions | Actual MatePad/iPad security and lifecycle checks pass |
| 4. Pilot and production rollout | Staff, configuration, recovery, and operational procedures are ready for controlled live use | F01–F13 | Acceptance, reconciliation, recovery drill, and owner go-live approval complete |

## Phase 0 — Device and policy gate

**Purpose:** Resolve platform feasibility before expanding the full Android application. The exact target is HUAWEI MatePad 11.5 BTKR-W09 running HarmonyOS 4.2.0.

**Work packages**

1. Record the physical model, OS/build, available storage, battery settings, and app distribution/update method.
2. Install a minimal signed Kotlin/Compose APK and verify launch, background/foreground, lock/unlock, force-stop/relaunch, and reboot behavior.
3. Verify Room/SQLite create/read/update/migration/close/reopen behavior and preservation after app/device restart.
4. Verify encryption/decryption with a keystore-protected test key before and after restart. Record any HarmonyOS-specific limits.
5. Verify foreground-service start from a visible action, persistent notification, local socket binding, stop action, and reachability from the intended iPad on the pharmacy Wi-Fi.
6. Verify system file picker export and reopen from a file-manager-visible folder. Check camera/barcode access if that input path is selected.
7. Measure basic memory/storage behavior and record failures, workarounds, and evidence. Do not treat local tests as a substitute for these checks.
8. Record approved deployment decisions that are prerequisites to implementation: VAT/rounding setup, manual invoice boundary, SC/PWD ID procedure, and BNPC remaining disabled until its current policy is configured.

**Exit gate:** Every required capability in master Section 15.4 and this phase is demonstrated on the target device, or the owner records an approved replacement device/architecture decision. Do not promise always-on hosting; lifecycle limits must be recorded.

**If the gate fails:** Stop work that depends on the failing platform capability. Keep domain logic portable, document the limitation, and resolve the replacement approach before continuing Android/HarmonyOS-dependent implementation.

## Phase 1 — Offline operational core

Build in this order. Work may overlap only where the dependency is an agreed domain contract and no duplicate business logic is introduced.

### 1A. F01 — Authentication, roles, and local security foundation

- Complete unique users, credential hashing, session lifecycle, throttling, inactivity lock, protected-action authorization, and audit evidence.
- Establish the production database/migration conventions and keystore boundary verified in Phase 0.
- Keep permissions in use cases and repositories, not just Compose visibility.

**Gate:** F01 Section 20.1 tests pass; unauthorized direct use-case calls fail; sensitive data and secrets are not exposed in logs; migration behavior is covered.

### 1B. F02 + F06 — Catalog, starting stock, and inventory ledger

- Finish catalog manual entry/edit/inactivation for all required SKU fields and protected audit changes.
- Complete CSV preview, row-and-column errors, duplicate detection, checksum/manifest evidence, all-or-nothing import, and explicit reviewed-subset flow.
- Make opening inventory and medicine lot/expiry data append-only inventory movements; derive on-hand from the ledger.
- Implement receiving, adjustment, low-stock/expiry state, negative-stock block, and approved lot allocation behavior. The current service/UI covers these operations; atomic sale deduction and returns are completed with F05 and F09.
- Keep catalog UI and import behavior aligned with F02; opening-stock imports must create F06 movements transactionally.

**Dependencies:** F01 authorization. Agree the tax/benefit types needed by F02 with F03; use F06 movement contracts for opening stock and lots. F10 owns export-specific CSV safety and export audit behavior.

**Gate:** F02 and F06 Section 20.1 tests pass; manual and imported catalog data are equivalent; invalid imports cannot partially commit; movement totals reconcile to on-hand; migration and rollback checks pass.

### 1C. F03 — Exact money, VAT, and discount engine

- Implement deterministic, versioned calculations using integer centavos or `BigDecimal`; no floating-point money.
- Cover VATable, catalog-exempt, zero-rated, SC/PWD on eligible selected lines, mixed carts, rounding boundaries, and promotion comparison.
- Keep BNPC modeled as a distinct rule and disabled until its policy is configured and approved.
- Store sufficient line inputs, unrounded basis, rounded results, and rule version for historical reproduction.
- Current state: the pure calculation service and snapshot contract are implemented. The required F03 automated acceptance tests and F05 atomic snapshot persistence remain open; do not mark F03 verified until both are complete.

**Dependencies:** F02 tax/benefit classifications and approved store settings. No checkout UI should implement its own calculation.

**Gate:** Table-driven tests match approved examples to the centavo; invariants/property and serialization checks pass; changing current rules cannot change historical snapshots; F05 persists the snapshot atomically. Engine compilation alone does not pass this gate.

### 1D. F04 + F05 — Checkout and atomic sale finalization

- Build regular and mixed-cart checkout, SC/PWD identity/check confirmation, explicit eligible-line selection, and required confirmation.
- Record cash/QR settlement as an unverified declaration; keep payment processing out of scope.
- Generate a unique internal transaction ID automatically. Every internal summary says `INTERNAL SALES RECORD — NOT AN INVOICE`.
- Atomically persist sale, line snapshots, settlement, transaction ID, inventory movements, and audit events. Roll back all of them on any failure.

**Dependencies:** F01, F02, F03, and F06. Use F08 shift context where needed, without making shift totals part of the sale calculation.

**Gate:** F04/F05 tests pass; exact totals reconcile; no invoice-number entry exists; concurrent/restart/reboot ID checks pass; injected finalization failures leave no partial records or stock movements.

### 1E. F08 — Cashier shifts and turnover

- Implement one-open-shift rules, opening float, cash movements, cash/QR separation, denomination count, variance reasons, and approval threshold configuration.
- Keep closed shifts immutable; corrections use adjustment records.

**Dependency:** F01 identities and finalized F05 settlement records.

**Gate:** F08 tests pass; the documented expected-cash formula reconciles exactly; QR never changes expected physical cash; authorization and closed-shift rules pass.

### Phase 1 exit gate

Run all Section 20.1 checks for F01–F06 and F08, database migration and transaction rollback checks, critical Compose flow tests, and an offline MatePad smoke test including app restart and device reboot. Reconcile sales and stock. No open critical data-integrity or authorization defect may remain.

## Phase 2 — Controls, reporting, and recovery

### 2A. F07 — Virtual bundles

- Expand bundles to component sale lines; retain component tax/benefit treatment and deduct component inventory separately.
- Support configured active window, quantity limits, and approved proportional allocation.

**Dependencies:** F02, F03, F05, and F06.

**Gate:** F07 tests pass for differing component tax/eligibility, timing, stock shortage, limits, and discount allocation.

### 2B. F09 — Voids, returns, and corrections

- Add fresh non-cashier authorization, reason, requester/approver identity, linked financial/inventory reversals, and immutable originals.
- Implement void-before-settlement, full/partial/damaged return, and replacement paths without deleting finalized records.

**Dependencies:** F01, F05, F06, and F08. Return-policy details stay configurable/open until decided in Section 22.

**Gate:** F09 tests pass; reversal links and inventory/tax effects reconcile; unauthorized actions fail; original records remain unchanged.

### 2C. F10 — Reports, exports, and audit reports

- Produce daily/monthly reports using Asia/Manila boundaries and historical calculation snapshots.
- Export stable versioned UTF-8 CSV with formula-injection protection; audit each export and protect customer-level data.
- Include bundles, reversals, settlements, inventory, and shift variance after their source features are stable.

**Dependencies:** F03, F05–F09. Define shared export contracts before F13 exposes remote export requests.

**Gate:** F10 reports reconcile to seeded source ledgers to the centavo; timezone, CSV safety, permissions, and export audit tests pass.

### 2D. F11 — Encrypted backup, restore, and recovery

- Create encrypted packages through the system file picker with manifest, checksum, post-write verification, and audit record.
- Require protected authentication and compatibility checks for restore; take a safety backup before replacing current data.
- Verify round-trip restoration and document a separate-copy operating procedure.

**Dependencies:** Stable schema/migrations, F01 authorization, and F10 export/security conventions. A raw database file is never a backup artifact.

**Gate:** F11 round-trip and failure tests pass; restored records/totals/audit/stock match; invalid packages cannot modify live data.

### 2E. F12 — Isolated Test Mode

- Use a separate database and transaction-ID sequence/prefix. Clearly label screens, exports, and artifacts.
- Prove sales, stock, customers, shifts, reports, and backups cannot cross into live data.

**Dependencies:** Stable schemas and complete core workflows F01–F11.

**Gate:** F12 isolation attempts pass across all live-data surfaces; Phase 1 regression remains green.

### Phase 2 exit gate

Run F07 and F09–F12 Section 20.1 checks; reconcile reports/exports against seeded sales, reversal, bundle, and shift data; complete a restore drill; prove Test Mode isolation; rerun Phase 1 checks. No critical/high financial, data-loss, or authorization defect may remain.

## Phase 3 — Secure local iPad dashboard

### F13 — Local hosting and browser dashboard

- Start/stop hosting only through visible tablet actions with a foreground notification.
- Pair using short-lived, single-use credentials; provide HTTPS, role-scoped sessions, rate limits, and revocation on logout/stop/unsafe network change/timeout.
- Keep dashboard data read-only except protected report export and backup requests executed by the tablet.
- Reuse application/use-case logic; do not duplicate price, tax, discount, inventory, or permission rules in browser code.

**Dependencies:** Phase 0 local networking/TLS feasibility; F01 sessions and authorization; F10 export/report endpoints; F11 backup request behavior.

**Gate:** F13 tests pass on actual MatePad and iPad, including certificate trust, direct API authorization, LAN exposure, Wi-Fi/IP change, screen lock, sleep, stop, reboot, session revocation, and concurrent viewing during a sale. Run Phase 1 and 2 data/financial regressions.

## Phase 4 — Pilot and production rollout

1. Reconfirm current tax/invoicing/privacy requirements with the pharmacy's qualified advisers. This is operational review, not a software determination.
2. Approve production rounding and rule versions; configure the initial SKU tax/eligibility basis. Keep unresolved settings at documented safe defaults.
3. Load production users, catalog, opening lots/stock, and backup destination through reviewed workflows.
4. Train cashiers and protected-role users; rehearse outage, restore, shift close, and correction procedures.
5. Run scripted user acceptance and parallel reconciliation against independent reference calculations for an agreed trial period.
6. Run release-build clean install/upgrade, operational soak, low-storage, interrupted-power/restart, and non-production restore checks.
7. Confirm the separate manual invoice process is operational and all Medtryx summaries retain the internal-record label.
8. Record unresolved limitations, owner acceptance, recovery instructions, and release authorization.

**Exit gate:** Complete release-level criteria in master Section 20.2, close critical/high defects, demonstrate recovery and financial/stock reconciliation, obtain staff acceptance, and record owner go-live authorization.

## Open decisions that affect the roadmap

The questions listed in master Section 22.2 remain open unless explicitly answered and recorded. They do not block building configurable safe defaults, but they do block enabling the related production behavior. In particular: BNPC stays off; unconfigured `OTHER` discounts are unavailable; negative stock stays blocked; return policy and variance thresholds are not invented; QR remains an unverified declaration; and backup retention is not silently imposed.

## Progress tracking

For each work package, record `Not started`, `In progress`, `Implemented`, or `Verified`. `Verified` requires its feature acceptance criteria, Section 20 tests, and any applicable physical-device evidence. Record the evidence link/date and unresolved failures next to the status. Do not mark a whole phase complete until its exit gate passes.
