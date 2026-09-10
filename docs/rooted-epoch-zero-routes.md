# Preserve an unchanged historical source's routes

The original MyOS public saved-reference pair and inline-source tests both failed
while applying the source's immutable initialization receipt. The unowned source
remained at the identical epoch-zero BlueId, but the rooted local-view publisher
unconditionally called the route-transition API, which correctly requires a
positive resulting revision. The SDK reproduction failed at the same check.

For an unowned result whose epoch and exact BlueId are both unchanged, retain the
previous active routes after checking the full projected route surface. A changed
result continues through the existing transition and interval verification. This
matches the existing owned unchanged-result handling; it does not invent an epoch,
publish a source change or relax the route-transition validator.

The new SDK test imports the exact saved authored source at epoch zero, restarts
while pending, verifies the single initialization application, preserves the complete
independent source history and processes the next LIVE entry. Additional cases start
both cycle members separately from saved originals, and start an inline saved source
before any Timeline Entry, then close and reattach the cycle. They verify finite
propagation, no initialization/source event duplication and exact store restart.

Executed evidence in the MyOS campaign:

- `sdk-authored-zero-baseline-executed`: one executed failure at the positive-revision check.
- `sdk-authored-zero-unchanged-routes`: six passing tests including historical catch-up,
  collection re-add, gas rollback and the existing cycle-entrypoint/split case.
- `sdk-authored-zero-pair-negatives`: ten passing tests (three SDK sequences and seven
  route checks). Mutation negatives reject missing/extra/changed routes and preserve
  rejection of actual transition attempts at zero or negative revisions.

Dependencies: Language d49ab0ba, BEX 925d7f0, catalog 4ba5f6f. The exact unchanged
MyOS original tests must still be rerun after immutable export/rebinding. This is a
development repair; final component, rooted and product gates remain required.
