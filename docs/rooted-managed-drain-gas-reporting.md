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

No execution, publication, retry, readiness, selection, gas-limit, receipt, or
public API shape changes. Other aggregate fields retain their existing mapping;
this correction does not claim new structural metrics for managed attempts.

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
The eight adjacent SDK mapping controls, complete library gates and final
MyOS acceptance remain pending.

Implementation and tests remain in the isolated
`rooted-import-forwarding-reproduction` worktree. Recording this note in the
central fixes registry does not itself port or qualify that runtime candidate.
