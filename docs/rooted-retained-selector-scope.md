# Operation selectors do not repeat during root-local history

## Problem and reproduction

A embeds B. B later attaches C with an explicit managed-epoch selector for
`/peers/c`. C already has an eligible retained event. B processes its own
attachment and history successfully. A then observes B's attachment and must
advance its local B/C view using C's retained history.

Before this correction, the first local historical application fails with
`MANAGED_EPOCH_SELECTOR_PATH_MISMATCH: missing=[/peers/c], unexpected=[]`.
The local cause consumes an authenticated receipt; it does not repeat B's
original attachment operation. Therefore it cannot resolve that operation's
prospective selector again. The failure precedes publication and is not a
missing-content or gas-limit outcome.

`RootedLocalHistoryRecoveryTest.retainedCauseDoesNotRepeatItsOriginalOperationSelectors`
reproduces this through the actual SDK, with unchanged source inputs and
processing rules. The corresponding MyOS pending-nested-source scenario fails
at `/c` for the same reason.

## Narrow correction

`ContractsClosureAdapter.managedEpochSelectionPlan` returns no prospective
operation plan for an invocation carrying `rootedEvidence.historicalWork`.
Only `RootedInvocationEvidence.retainedLocal` constructs that evidence; it is
bound to the actual local work, selected view and historical cause.

The original lookup was keyed by the anchoring Timeline entry's BlueId. A local
historical step shares that chronological anchor, but not its LIVE operation.
Selector authority must follow the operation, not merely the shared anchor.

No selector is removed from the stored original operation. Its LIVE processing
still validates the complete declared path set. Independently owned managed
history already uses the resolver overload without an operation-selection plan;
it does not need another change. No public API, tariff, epoch, source-acquisition,
publication or replay policy changes.

## Verification

Base: Coordination `9807ca3901829f9ef965d1c6a8a63bff516081ff`, Language
`7ec0fdaafff41ad8e41387e7e5647d5a800d807c`, BEX `ab72af14ee54c6123e6d80349956af373a887680`,
Catalog `0b68744ba6456ef312d1ba87a27096bd2ac5341f`. Tests use Java 17 and the
verified immutable artifacts in `rooted-retarget-historical-development.zE0Rg1`.

- The unmodified production implementation fails the compact SDK reproduction.
- With the correction, the result equals a fresh materialized calculation:
  exact input/output closure identities, companion, ordered event/checkpoint/
  transition-receipt identities, logical gas and the complete charge trace.
  Only A publishes; independent B/C heads and complete histories stay unchanged.
  Reattachment to the retained stores produces no duplicate transition.
- A separate real LIVE attachment with an unresolved `/peers/not-c` selector
  still throws the SDK's `CoordinationException` carrying the original
  `IllegalArgumentException` path-mismatch cause, with both histories unchanged.
- The broad four-owner run executed 15 tests: 14 passed; the new LIVE negative
  initially expected the inner exception rather than the SDK wrapper. Existing
  local-history recovery/gas/publication-failure, retarget and selector controls
  passed. After changing only that assertion, both new SDK methods pass together
  in 27 seconds. This is not represented as a fresh 15/15 single-run result.

Raw reports and tested source are preserved in
`/Users/kamil/Documents/Projects/Blue/rooted-retained-selector-evidence.HX6IRA`:

| Archive | SHA-256 |
| --- | --- |
| `selector-local-red.tar.gz` | `785d2e4bc6b84c18884ea7b6e66e6aec4b0de84272ef38b6ab1f9b9ef8cd1573` |
| `selector-broad-wrapper-assertion.tar.gz` | `9543ffb3cd59013f45867e130cff95fe4b49bd8dbc66ea1ad7f2a843a15a3229` |
| `selector-positive-negative-pass.tar.gz` | `db624fc3ffa30aa4371e51f6bc0cc07dd10c50f464f8ff7d10b8276a17817344` |

## Rejected alternatives

Do not clear selectors globally, skip LIVE path validation, fabricate a repeated
attachment demand, or change the imported receipt's cause to a new operation.
Those would erase or alter authority instead of keeping it scoped to the step
that actually uses it.
