# Registered managed-application gas in SDK drain summaries

## Defect and scope

`DrainResult.stats()` promises aggregate semantic work performed by that drain
call. Before this correction, `SdkDrainResultMapper.aggregateDrainStats` summed
external-entry and root-local result statistics but omitted registered
`ManagedEpochApplicationAttempt` results. A successful registered application
therefore exposed its exact positive gas in the typed attempt while reporting
zero aggregate gas. This was inherited reporting behavior, not a change to
Contracts execution or its gas tariff.

The omission reaches MyOS command summaries: `SemanticCommandExecutor` forwards
drain statistics, `JournalDrainAggregation` sums them, and `ApiCommandResponses`
exposes the aggregate. MyOS's exact managed-application evidence already retains
the typed result's correct gas. Correcting the SDK aggregation fixes the shared
public contract rather than applying a second host-specific calculation.

## Correction

Aggregate gas is read once from completed, non-replayed engine attempts in each
of the three exclusive execution lanes: external entry, root-local history, and
registered managed application. The engine removes a root-local or joined
managed execution from the external lane when constructing its drain receipt.
Mapped result statistics and the additional application receipt are projections
of that execution, not additional charges.

- A completed committing or rolling-back attempt contributes its exact
  `ClosureProcessResult.totalGas()`.
- A replay contributes zero new call gas. Its individual retained result still
  reports the original gas, trace, identity, and receipt.
- An incomplete resource-demand attempt has no completed result gas and adds
  zero, preserving the existing reporting convention for suspension.
- Two genuine failed executions both count even if deterministic retry produces
  the same invocation and gas-trace identities. There is no identity-based
  deduplication.

Here replay means retrieval of an already-published result. Fresh-process MyOS
reconstruction that actually executes PROCESS again is not such a retrieval:
its newly executed attempts still report their complete deterministic gas.

The gas correction changes no execution, publication, retry, readiness,
selection, gas-limit, receipt, or public API shape. It leaves other aggregate
fields unchanged; the separate revision-count correction below addresses the
already documented transition budget. Structural counters, opened-document
counts and step-order mapping retain their existing scope.

## Focused controls and qualification boundary

`RootedManagedDrainGasReportingTest` adds four controls:

1. An actual registered historical application contributes its exact positive
   result gas once, with unchanged application receipt and committed state.
2. A reporting-only replay envelope around a genuine published attempt preserves
   its original result gas but reports zero new aggregate gas; an explicit typed
   resource-demand envelope reports zero without inventing a completed result.
3. Two actual gas-exhausted historical retries leave histories unchanged and
   contribute both charged amounts despite identical deterministic identities.
4. Actual external, root-local, and registered executions are combined for a
   mapper-only mixed-lane check. Their exact gas amounts sum once; replay mapping
   retains every per-result amount while adding no new call gas.

Replay/suspension envelopes and the combined receipt are test-only mapper inputs,
never submitted for execution. They do not establish runtime replay or cold
restart behavior. The separate `RootedJoinPublicationSafetyTest` exercises real
response-loss recovery and restart at the publication boundary.

Status: **4/4 PASS**, with no failures, errors or skipped cases, in the
parent-owned `unified-cycle-01` batch (10.034 seconds for this owner). Its full
batch was **17/20**, with three unrelated cycle-fixture assertion failures;
this is not a passing whole-cycle or full-library receipt. The tested mapper
remains unchanged, SHA-256
`5e5a6d82c4d0b8d7e8dfd84c7a966445749133455b14ab54220d9b768cbdee79`.

Raw source and reports are preserved in
`legal-detached-retarget-evidence.fKnxrU/unified-cycle-01.tar.gz`, SHA-256
`b5a67e06dab21e6eaa6dc4f29ed78be7f0875ab165a6c4742909af622ea92bec`.
The eight adjacent SDK mapping controls subsequently passed in
`unified-cycle-04`: `SdkDrainResultMapperTest` (4), `SdkEdgeResultTest` (2), and
`ManagedEpochSdkSurfaceTest` (2). Complete library gates and final MyOS acceptance
remain pending.

## Separate owned-revision count correction

The same call-level review found a different engine accounting error. The
registered managed branch in `DefaultCoordinationEngine.executeRootSelection`
reported one committed transition for any newly published application. The
public rooted drain contract instead counts every newly committed owned
PROCESS transition receipt; an atomic invocation can exceed the remaining budget, but
the next invocation must wait. The existing external/root-local path already
implements that rule through `RootedResultScope.processTransitionCount`.

"Owned revision" here does not mean only an increment of the Coordination
epoch. `ManagedDocumentTransitionReceipt` deliberately has no Coordination
epoch; Language emits it for an exact state change or Root events. The released
helper therefore also counts an owned same-epoch representation change with
such a receipt. The maintained `RootedCheckpointRepresentationSafetyTest`
explicitly retains an owned parent transition while its epoch stays zero.
Unowned dependency receipts and old/replayed publications remain excluded;
unchanged-state, eventless bookkeeping has no such receipt. The new regression
uses exactly this ownership filter, not an epoch-advancement test.

In `unified-cycle-04`, `RootedAutomaticJoinSchedulingTest` compared each call
with the new retained terminal publication identities and their actual owned
`managedTransitionReceipts`. The terminal and nonterminal three-node cases
each proved **3 actual revisions versus 1 reported**; the four-node chain
proved **4 versus 1**. This counts actual processor-issued revision receipts,
not acquired owners, previous prefix publications, or replayed results.

The narrow correction reuses `RootedResultScope.processTransitionCount` in the
managed branch, retaining its existing `published && !replayed` guard. Failed,
suspended and recovered publications add zero. Gas, PROCESS results, event and
receipt identities, publication ownership, and atomicity remain unchanged.
A MyOS-only summary correction would not fix the engine's own between-invocation
budget decision, so this belongs in Coordination.

The exact four existing automatic scenarios provide the regression without
manufacturing results or weakening their business/gas/trace checks.
`unified-cycle-04` as a whole was **12/17**:
the three accounting failures above and separate diamond/full-ring failures
remain recorded, not converted into a passing receipt. Its archive is
`legal-detached-retarget-evidence.fKnxrU/unified-cycle-04.tar.gz`, SHA-256
`7cca5d4f35ed3fed9b4b0585c2f7581f1af45f58a494a6c684e79cbffe4df0ac`.

The fixed engine's `unified-cycle-05` rerun is **4/5**, not a complete pass.
All three former accounting failures pass with the new receipt-derived count
checks and their original business/gas/trace/restart assertions. The automatic
owner totals **3/4**, 235.206 seconds including its still-blocked diamond case.
The complete fourteen-input SDK ring/chord/duplicate/detach/reconnect owner
also passes, including restart, in 396.525 seconds: the reconnect reaches
**A4/B2/C3**, then the final input reaches **A4/B2/C4**. This does not yet
qualify the separate original packaged MyOS HTTP/restart owner.

The remaining diamond has both receiving reactions but cannot publish its
terminal join while each receiver's frozen peer differs from the independently
advanced peer. Its owner fences remain intact; no oracle or CAS guard is
weakened. This is a separate unresolved scheduling/causal-view case, not a
remaining transition-count mismatch. Source and reports are archived in
`legal-detached-retarget-evidence.fKnxrU/unified-cycle-05.tar.gz`, SHA-256
`c127cc4789e07859fc4423c1e9d2cba9691d11b0f425665e70cdd5d09ae8302a`.

Implementation and tests remain in the isolated
`rooted-import-forwarding-reproduction` worktree. Recording this note in the
central fixes registry does not itself port or qualify that runtime candidate.
