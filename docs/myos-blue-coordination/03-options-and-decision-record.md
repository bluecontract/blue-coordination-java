# Decision record: a durable Coordination boundary

> **Revision:** 15.12 · **Status:** design direction; Phase1/2 implementation authorized

[Overview](../myos-blue-coordination-host-architecture.md) · [Causal model](17-causal-processing-model.md)

## Decision

Keep Contracts responsible for each complete semantic invocation. Put deterministic cross-invocation
planning in Coordination. Give MyOS a small trusted persistence/acquisition boundary.

Use independently committed source and consumer operations for ordinary managed embedding.
Agreement commits once; Orders consume exact retained receipts under their own ordered history,
occurrence cursors and dependency frontiers. A slow or failed Order cannot roll back the source or
hold independent siblings. See [18](18-independent-lineage-processing.md) and the semantic constraints in
[21](21-semantic-equivalence-and-source-reuse.md).

This replaces the earlier all-observer live invocation, immutable global application partition and
root-wide progress barrier, in addition to r14's blanket source freeze and universal phase ordering.
Local chronology and causal laws remain in [17](17-causal-processing-model.md).
[22](22-processing-kernel.md) has highest precedence for the selected kernel and source reuse.
Managed storage is not permission to change logical scope/event behavior. Preserve current ordinary
ancestor observation, update continuation and FIFO laws under an explicit mapping, or identify the
owning-library discrepancy to correct. Final state plus a flat emission list is not sufficient proof.

Withdraw r15.6's selected all-pins-first, full-batch replay, mandatory Parent relay and unconditional
post-commit new-placement rules. Use22's observation program, causal enqueue interpreter, ordered
placement updates and explicit initial-view/history-lane distinction. Source initialization is
canonical, FULL_HISTORY and independently metered; observer FROM_* policies no longer choose source
birth. These are explicit library changes, not proof of equivalence to old combined gas/rollback.

Graph connectivity alone does not make 1000 independent Orders atomic. Conversely actual cyclic
workflow feedback remains within its required atomic operation and shared gas. Library-owned
failure handling must allow later eligible repair without fabricating a successful failed epoch.

Revision15.9 selects terminal managed gas-failure consumption and explicit continuation from the
actual rollback consumer state: source continuity and terminal delivery outcomes are verified
separately from successful view progress; a successor explicitly aligns to its source-before through
normal update reactions within its own meter/rollback. Phase1 implements this Coordination/Contracts
extension; [24](24-phase-1-library-summary.md) records verification and remaining integration scope.
Normal successful catch-up and real atomic feedback are unchanged.

Revision15.11 extends that managed rule to recognized deterministic RUNTIME_FATAL and all lawful
consumed-gap evidence, including composite external-policy outcomes. Unknown exceptions, host IO,
timeouts and internal bugs must never be classified as consumed semantic failures; the owning-library
exception boundary is part of Phase1. Failed initialization still cannot create a usable source.
Dynamic return-edge admission now occurs before ownership changes: charge the check locally, require
the distinct current meters to fit their identical fixed limit, then join without reset. A rejected
join fails only the initiating already-owned group. Stable seed-local event IDs survive later joins;
the r15.10 final-anchor/shared-meter reconstruction rule is replaced, not retained as a second mode.

MyOS owns durable execution of every due reaction, including terminal failure accounting. Its
transactionally maintained temporal-dependency, ready-work and reverse-wait indexes are the normal
selection path. Efficient selection at millions of documents and thousands active per tenant is
an architectural requirement; concrete PostgreSQL index/query plans belong to Phase2. Source commit
does not enumerate/wait for all Orders. Necessary1k/10k/100k reactions may take proportional time;
bounded resources, fairness/backpressure and isolation matter, not a universal fanout-seconds limit.
Aggregate completion is optional nonblocking host/test reporting, not another semantic operation.

## Alternatives

| Option | Decision |
|---|---|
| Replace H2 with PostgreSQL but retain a resident global runtime | Insufficient: still split RAM/database publication and replay-all startup. |
| Add a generic map/repository callback to the existing engine | Insufficient: does not define bounded reads, completeness, identity or atomic durable settlement. |
| Deterministic Coordination core plus typed host adapter | Selected: keeps one semantic authority and tests storage independently. |
| Separate Coordination service/RPC | Deferred: deployment distribution is not required to validate the semantics. |
| Persist a Java runtime or private Contracts continuation | Rejected for this POC: current Contracts exports no semantic continuation. |
| Keep all observing Orders in Agreement's live transaction | Rejected for ordinary one-way embedding; couples failures, gas, source latency and memory to all consumers. |
| Split an already executed Contracts closure in MyOS | Incorrect: changes rollback/identities without a defined library semantic boundary. Change the owning libraries first. |
| Full event-log rebuild as the ordinary recovery path | Rejected: history is retained for audit/replay, but unrelated state must not be hydrated at startup. |

## Non-negotiable boundaries

- Each defined local operation is atomic across its state, history, cursor, obligations and outputs.
- Graph connectivity alone does not define a synchronous transaction or shared gas/rollback scope.
- Separate workflow invocations retain their own gas and rollback. Prior commits cannot be compensated away.
- Content-derived MyOS DocumentId is the exact initial authored BlueId, never a work-slot allocation.
- Exact-node availability and managed-occurrence evidence have different retry identity consequences.
- Timeline provider guarantees close input arrival; Coordination decides canonical eligibility.
- A chronological history view is not a copy of the source's current head.
- SQL claim order, leases, page boundaries and caches never determine semantic order.
- The trusted adapter implements complete queries; hashing its answer does not prove completeness.
- Operational absence, capacity limits and cancellation retain semantic obligations.
- Per-invocation gas does not prove that all cross-invocation causal computations terminate.

## Deliberate simplifications

The first PostgreSQL adapter uses scoped target/occurrence/lifecycle fences and short commit-time
locking. Every covered writer participates. A host-wide commit mutex may simplify the first adapter,
but cannot select semantics or invalidate unrelated sibling/source execution. No lock spans execution
or resource waits, and host revisions never replace exact semantic predecessor/component evidence.

Immutable definitions and receipts are stored once. Mutable progress is small and independently
auditable. Outbox publication follows the next committed predecessor-chain position, not an
entire moving tail. Durable condition rechecking closes resource-wakeup races.

The API listing is an implementation-spike sketch. Do not implement unused proof hierarchies or
publish every internal record as public API. Existing in-memory capabilities remain available.

## Remaining decisions and subsequent executable evidence

Before implementing dependent paths, define authorized source origin/admission separately from
physical materialization; prove initialization/reuse gas and identities for the same logical input;
and specify sufficient observation evidence, grouping and ordinary/managed event correspondence.
Verify the selected failed-import continuation against unchanged consumer state and bounded atomic
feedback. Source origin and observation construction remain algorithm obligations, not arbitrary
product preferences or SQL decisions; selected recovery still needs library conformance evidence.

After explicit implementation approval, and before accepting the library boundary, implement and
independently review the small examples for:

1. Initial source history interleaved with parent FULL_HISTORY.
2. One original cause addressing both a source and its parent.
3. Final and nonfinal catch-up steps that legally react into the source.
4. Independent live fanout, failed/slow consumers and shared source progress without a root-wide barrier.
5. A disjoint committed topology change followed by the next invocation.
6. Same authored child discovered twice, legal sharing and actual feedback/cyclic dependency discovery.
7. Affected-topology locality, not just small content payloads.
8. Source-ahead lifecycle changes and gap-free registration/retained-history delivery.
9. Local gas/failure and event identity invariant under reversed independent commit order.
10. Intermediate reads, per-patch updates and FIFO interleaving, with buffered-effects controls.
11. Three-level original ancestor delivery without a required Parent re-emission.
12. Creator-local initialization versus genuinely later attachment work.

Do not invent a successful result for an unproved case or silently exclude it from acceptance.
The intended behavior is a design target until the Phase-1 fixture and boundary support it.

## Delivery and iteration

1. Library semantics and the smallest boundary.
2. PostgreSQL-backed target-shaped MyOS Mini and reused Timeline mechanics.
3. Integrated correctness/examples/faults/performance, then changes in either preceding layer.

There is no release, migration or compatibility negotiation. Deliberate changes reset isolated POC
state. API stabilization follows real integrated iterations, not completion of the first loop.
