# F02 Verification Notes

## Completed automated checks

- Exact-decimal SKU, unit, price, cost, date, pack-conversion, barcode, tax/benefit, and medicine lot/expiry validation.
- Protected catalog create, inactivation, price change, and tax change boundaries.
- CSV preview, Unicode and quoted-comma parsing, row validation, all-or-nothing behavior, and explicitly reviewed valid-subset commit.
- Import checksum, manifest, row-result persistence, and transactional database import path.

## Dependent checks not claimed complete

- F03 supplies the final versioned tax/benefit rule semantics and exact checkout calculation snapshots.
- F06 must convert opening lots into append-only opening inventory movements and provide stock/expiry allocation behavior.
- F10 owns export CSV formula-injection safeguards and report/export audit views.
