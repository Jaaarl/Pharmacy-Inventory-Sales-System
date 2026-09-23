# F07 — Product Bundles

**Phase:** 2 — Controls and reporting  
**Status:** MVP specification  
**Master reference:** [Section 11](../Medtryx_Product_and_Technical_Specification.md#11-feature-f07--product-bundles)

## Purpose

Sell time-limited promotions such as an umbrella plus paracetamol without losing component-level tax, benefit, cost, or inventory behavior.

## MVP Bundle Model

Bundles are virtual by default. A bundle contains:

- unique code and name;
- component SKUs and quantities;
- active start/end timestamps;
- active/inactive state;
- promotional price or discount-allocation method;
- optional quantity/customer limit; and
- approval/audit metadata.

At checkout, expand the bundle into component sale lines.

## Rules

- Preserve each component's catalog tax class and benefit eligibility.
- Apply SC/PWD treatment only to eligible selected components.
- Deduct inventory from component lots/SKUs.
- Allocate a bundle-level discount proportionally by pre-discount component price unless a different approved rule is configured.
- Block a bundle when any required component lacks stock.
- Respect activation dates and store timezone.
- Do not classify a bundle based on only one component.
- If physical pre-packing is added later, consume/create stock only through explicit assembly movements.

## Data

- `Bundle`
- `BundleComponent`
- `BundlePriceVersion`
- `BundleActivationWindow`

## Acceptance Criteria

- A mixed-tax bundle reports correct line-level tax totals.
- Only eligible bundle components receive SC/PWD treatment.
- Component stock and costs reconcile after a bundle sale/reversal.
- Expired or inactive bundles cannot be added.
- Historical bundle sales retain original composition and allocation.

## Required Tests

- Test before/start/end/after activation boundaries in Asia/Manila time.
- Test inactive, missing component, insufficient quantity, and quantity-limit cases.
- Test VATable plus VAT-exempt and eligible plus ineligible components.
- Verify proportional allocation totals exactly equal the intended bundle discount after rounding.
- Test sale, void, and return inventory movements for component lots.

## Dependencies

- F02 component definitions.
- F03 discount allocation and tax calculations.
- F05 checkout/finalization.
- F06 component stock.
- F09 reversal.
- F10 bundle reporting.

