# MyOS Mini + Blue Coordination: design map

> **Design baseline:** 15.12 · **Documentation sync:** 2026-09-06 · **Status:** Phase1/2 ready for Phase3 integration

Start with the [tutorial](myos-blue-coordination/tutorial/README.md).
[22 — Processing kernel](myos-blue-coordination/22-processing-kernel.md) defines the selected algorithm;
[21](myos-blue-coordination/21-semantic-equivalence-and-source-reuse.md) explains its semantic controls.
The [outcome](myos-blue-coordination/review/revision-15-12-review-outcome.md) identifies explicit changes
to baseline libraries and the planned proof work. The current
[implementation summary](myos-blue-coordination/24-phase-1-library-summary.md) and
[verification record](myos-blue-coordination/implementation/phase-1-2-readiness.md) supersede its
preimplementation status. The general MyOS adapter is still [Phase3](myos-blue-coordination/25-phase-3-integration-plan.md).

One Agreement has one intrinsic authored lineage and canonical source history. Process it once,
retain authenticated observable actions, and let Orders react independently in their own canonical
histories. Their failure, delay and unrelated Timelines do not block Agreement. A real returning
workflow belongs to one complete directed component with shared gas and atomic rollback.
Dynamic same-origin work preserves frozen seed order and stable seed-local event identities. Before
a returning edge, charge/check the distinct current budgets against their identical frozen limit.
Only an accepted join combines ownership/meters; rejection fails its initiating current group before
mutation. No retroactive shared-meter replay, first-worker ownership or stale half-result can escape.
Ownership is monotone within a valid attempt; failure of an unfinished independent producer
invalidates its whole dependent attempt, including conditional third-party joins and candidate gas.

The interpreter preserves synchronous updates and actual event FIFO, substituting retained source
actions at their original continuation sites. It does not replay a final state and flat event list.
A single reaction origin composes direct/indirect routes and any own direct input into one complete
consumer operation. Distinct authored aliases remain distinct observations.

Initialization, observer history and storage materialization are separate. Canonical source origin
uses initial authored BlueId, environment and fixed source policy, never the first parent/worker.
FROM_NOW/FROM_FRONTIER explicitly select observer history and initial view in the implemented library
change. Source initialization and consumer work have separate fixed meters cold and warm.

Managed gas or recognized deterministic runtime failure rolls back/terminally accounts one operation.
Reviewed library exception classification excludes host IO, timeout, cancellation and internal bugs.
The next receipt validates all lawfully consumed gaps and aligns the actual
consumer view to its exact source-before through normal update reactions, then interprets that
receipt. Alignment can fail too. No historical suffix is silently abandoned, no failed epoch is
invented and no operational absence is terminalized.
Account/document quotas pause work without changing semantic gas or skipping obligations. General
interruption of an endless sequence of successful imports is outside the first POC, not another
pre-implementation gate. No inherited cross-operation fuel is introduced.

MyOS owns transactionally consistent temporal-dependency, ready-work and reverse-wait indexes,
bounded work discovery and durable execution of every due obligation. Publication contains exact
zero, one or many lineage projections: metadata-only, independent, or genuinely coupled. A source
transaction does not enumerate all observers. Aggregate completion is optional reporting.

Agreement needs its own directed Timeline closure, not its reverse observers' timelines. If it
genuinely embeds10,000 Orders, their relevant guarantees do matter; use shared frontier aggregates
and incremental ordered merging. No optimization may ignore a missing relevant completeness proof.

The [three-phase execution plan](myos-blue-coordination/23-first-implementation-execution-plan.md) now stands at:

1. Owning-library contracts implemented and independently tested within the recorded scope.
2. PostgreSQL/Timeline/index foundation implemented, with scoped real-library bridges; default app unchanged.
3. General integration, examples/faults and measurements planned, not started; iterate from results.

Small linked graphs target seconds. N necessary reactions legitimately cost N work and may take
longer; measure independent source latency, consumer throughput/lag, bounded memory, fairness and
overload recovery. Never replace correctness with constant-time claims for arbitrary graphs.

The [package map](myos-blue-coordination/README.md) links the full plan. The retained r15.2 experiment
has3 passing attachment controls and4 failing settled-history cases; G1 remains unaccepted.
This design revision accompanies implementation in separate library worktrees and the MyOS feature
branch. Runtime test evidence and Phase3-readiness acceptance are recorded separately from prose QA.
