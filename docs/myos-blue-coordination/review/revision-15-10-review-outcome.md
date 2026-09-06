# Revision15.10: dynamic joining and a bounded first-POC scope

> **Status:** documentation/API-plan refinement; no runtime implementation approval or conformance claim

This revision addresses the agreed dynamic same-origin coupling and host quota questions. It does
not reopen the whole design or add a general cancellation feature before the first implementation.
[22](../22-processing-kernel.md) remains the algorithm authority.

## Decisions

- **Dynamic join before publication.** Initially independent A/B targeted by the same entry may
  discover a returning dependency. Close relevant pending producer work, not only committed SCCs.
  Freeze the pre-origin direct-seed schedule; preserve actual continuation-site views. Invalidate
  affected candidates and reconstruct one canonical anchor, invocation, meter and atomic result.
  All joined work fences participate; an old candidate cannot publish later after a crash.
- **Canonical execution, not speculative footprint.** Only canonically reached edges establish
  ownership. A later B seed cannot supply its final state to an earlier A read. Speculative extra
  edges and instructions after canonical gas exhaustion cannot alter scope, IDs or charges.
- **Independent work stays independent.** Same origin alone is not an atomic boundary. One-way
  Agreement→observer processing retains independent commits and gas. A later entry creating a cycle
  cannot retroactively join earlier committed operations.
- **Keep operation gas.** A real synchronous loop remains one gas-limited operation. Legitimate
  successive attachment/import operations may have separate meters and may continue indefinitely.
  The earlier suggestion of mandatory inherited cross-operation fuel is not adopted.
- **Host quotas contain work.** Account/document allowance, bounded workers/reads and fair scheduling
  pause work without changing semantic policy or consuming input. Needed80/limit100/allowance50 means
  pause, not GAS_LIMIT_EXCEEDED at50. Replenishment/retry preserves exact results and one settlement.
- **Explicit repair limit.** Host pause does not make a later repair input eligible through an
  endless earlier lane. General semantic interruption/cancellation is outside this first POC and
  is not a new design gate. Required finite gas-failure→later-detach recovery remains in scope.

## Current source versus proposed change

The local `ContractsClosureAdapter.capture` partitions one entry into independently executable
cohorts. Existing managed expansion extends one invocation but retains `original.directDeliveries()`
(`ContractsClosureAdapter.java`, expansion around1847). `ContractsRootFeederCoordinator.process`
iterates frozen tickets; the current stale-head/generation checks protect old inputs, but these paths
do not constitute the selected same-origin joining protocol.

`myos-simple`'s `DynamicCycleDetachIntegrationTest` begins with A already embedding B, then creates
B→A. It asserts shared rollback on gas failure and later detach. It is valuable within-closure
evidence, not a test of merging initially independent same-entry candidates. No new behavioral
test was run for this revision; a possible stale-candidate interruption is not presented as an
executed proof of divergent committed histories.

`ContractsExecutionPolicy` already defines an explicit shared limit for one invocation. This revision
does not replace it with an account-dependent or import-chain meter. The source baseline and earlier
experimental results are unchanged; no new remote freshness claim is made.

## Planned proof and delivery

The [scenario cards](../09-scenarios.md) add three witness labels under existing J/L families, not
new catalog IDs: `J-DYNAMIC-SAME-ORIGIN`, `J-AUTOMATIC-REATTACHMENT-CHAIN`, `L-QUOTA-PAUSE`.
They specify core outcomes, durable post-state, forbidden outcomes, schedules and budgets.
[Validation](../11-validation-plan.md) maps them to library and host checks. The Java file remains
a documentation sketch: typed scope evidence and joined-work fences are proposed boundary fields,
not implemented constructors or a new public distributed planner.

The first POC may serialize overlapping candidate planning/reconciliation and replay canonically.
It does not need a distributed merge service or all-observer execution barrier. Preserve the sequence:
library foundation → target-shaped PostgreSQL/Timeline MyOS host → integrated correctness and
performance → measured iterations. Only the user decides when runtime implementation begins.

Structural QA checks links, schemas, Java sketch and archive consistency. It cannot establish
determinism, correctness or performance. The retained r15.2 experiment still reports3 passing
attachment controls and4 failing settled-history cases; G1 is not accepted.

Structural QA for this refinement passed:47 active Markdown/map/outcome documents,396 local links,
47 anchor links and three Draft2020-12 schemas. The Java sketch compiles with Java17 targeting and
all lint warnings enabled. All14 existing Mermaid blocks and19 historical review/experiment files
match the previous archive; diagrams were not re-rendered. A content hash over runtime/test/build
files is unchanged, including the retained pre-existing build patch. Processing/host tests and
benchmarks were not run. The package manifest and new archive are checked against the final payload.
