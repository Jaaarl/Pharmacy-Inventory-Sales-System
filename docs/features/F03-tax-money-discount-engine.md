# F03 — Tax, Money, and Discount Engine

**Phase:** 1 — Offline core  
**Status:** MVP specification  
**Master reference:** [Section 7](../Medtryx_Product_and_Technical_Specification.md#7-feature-f03--tax-money-and-discount-engine)

## Purpose

Produce deterministic, reproducible, line-level Philippine VAT and discount results without floating-point error or whole-cart tax leakage.

## Money Rules

- Use integer centavos or `BigDecimal`; never persist or calculate money with `Float` or `Double`.
- Use at least four internal decimal places and approved two-decimal output rounding.
- Store calculation inputs, unrounded basis, rounded outputs, rates, and rule-set version.
- The store setting is `VAT_REGISTERED` and protected from cashier changes.
- Prices are VAT-inclusive.

## Required Line Inputs

- SKU, unit price, quantity, and captured catalog tax class.
- Captured benefit eligibility.
- Selected customer benefit and whether the line is qualified.
- Rule/rate version.
- Authorized promotion or other discount when applicable.

## Required Line Outputs

- Gross amount.
- VAT-exclusive base.
- Regular VAT amount.
- VAT exemption adjustment for qualified SC/PWD treatment.
- Statutory and promotional discount amounts.
- Amount due.
- Applied tax and discount result/reason.

## Formulas

Regular VATable line:

```text
gross            = unitPrice × quantity
vatExclusiveBase = gross ÷ 1.12
vatAmount         = gross - vatExclusiveBase
amountDue         = gross
```

SC/PWD-qualified catalog-VATable line:

```text
gross                  = unitPrice × quantity
discountBase           = gross ÷ 1.12
vatExemptionAdjustment = gross - discountBase
statutoryDiscount      = discountBase × 20%
amountDue               = discountBase - statutoryDiscount
```

SC/PWD-qualified already VAT-exempt line:

```text
gross                  = unitPrice × quantity
discountBase           = gross
vatExemptionAdjustment = 0
statutoryDiscount      = discountBase × 20%
amountDue               = discountBase - statutoryDiscount
```

## Invariants

- Calculate tax and benefits per line.
- Never make the whole cart exempt because one line is exempt/qualified.
- Never remove VAT twice.
- Never stack SC and PWD on the same line.
- Do not silently stack statutory and promotional discounts.
- `OTHER` discounts require configured rates, dates, authorization, and stacking policy.
- BNPC is separate from SC/PWD and off until configured/enabled.
- A rule change never recalculates a finalized historical sale.

## Acceptance Criteria

- All approved examples match to the centavo.
- Mixed-cart totals equal the sum of their independently calculated lines.
- Historical calculations are reproducible from persisted snapshots.
- No floating-point type exists in the persisted calculation path.
- Disabled BNPC cannot be applied through UI or direct use-case/API calls.

## Required Tests

- Table-driven tests for VATable, VAT-exempt, zero-rated, SC, PWD, already-exempt plus 20%, promotion comparison, quantity, and mixed-cart cases.
- Boundary tests around half-cent and rounding transitions.
- Property tests for non-negative totals, sum consistency, order independence under the approved rounding rule, and no double VAT removal.
- Historical rule-version test after current configuration changes.
- Serialization/database round-trip tests for calculation snapshots.

## Dependencies

- F02 product tax and eligibility snapshots.
- F04 benefit selection.
- F05 atomic finalization.
- F10 report aggregation.

