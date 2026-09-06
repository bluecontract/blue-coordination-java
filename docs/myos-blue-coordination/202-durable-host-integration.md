# Durable MyOS integration — optional technical bridge

> **Status:** Phase1/2 integration-readiness handoff; Phase3 remains planned · **Semantics:** r15.12 · **Updated:** 2026-09-06  
> After the [tutorial](tutorial/README.md) and [processing bridge](101-processing-model.md), use this as an implementation orientation.

[Document 22](22-processing-kernel.md) controls the processing algorithm and source reuse.
[Storage](06-storage-and-transactions.md) and the [API boundary](05-poc-api.md) implement that
contract, not a second interpretation. This update documents completed Phase1/2 work and the
remaining [Phase3 plan](25-phase-3-integration-plan.md); it starts no new runtime work.

## What is implemented, and what is not

The isolated libraries implement typed independent/feedback-group results, source observation and
failure evidence, canonical initialization, attachment lanes and exact cold restoration. MyOS
Simple's `blue.myos.mini.durable` implements PostgreSQL authority, scoped transactions, retained
plans, indexed work/discovery/waits, prefix activation, local Timeline mechanics and outbox recovery.
The final evidence is 66 host tests plus seven actual-library/PG smoke cases, not seven production
application flows. See [readiness](implementation/phase-1-2-readiness.md) for reports and limitations.

The narrow single-lineage acyclic initialization adapter is injected into the real host loop in
`durable-library-smoke`. Source-prefix, failure and M1/M2 cases use explicit test bridges. No general
adapter service is installed. `durableHostRun` bootstraps the database and loads an adapter via
`ServiceLoader`; without one it fails explicitly, never falls back to RAM. Its one recovery pass
and work drain are not an always-on ingress/discovery/publication service. The default application
is unchanged. Wiring those pumps and the general result/evidence mapping is Phase3's first slice.

## 1. Ownership and trusted state

| Layer | Responsibility |
|---|---|
| MyOS Timeline adapter | exact entries, microsecond order, predecessor/completeness evidence and local ingress |
| MyOS worker | indexed ready work and reverse waiters, durable execution of every due reaction/outcome, scoped acquisition, fenced commit and recovery |
| Coordination | next logical work, historical selection, complete dependencies and source-evidence reuse |
| Contracts/BEX | observable updates/events, reads, lifecycle, atomic workflow gas/rollback and result |
| PostgreSQL adapter | authoritative temporal dependency indexes and complete scoped queries; atomic state/index/work/publication updates |

PostgreSQL is the mutable authority; exact immutable content is identity-verified. A cache, host
timeout or platform page size cannot become semantic policy. There are no alternative specification
profiles. The adapter must provide complete queries: a hash cannot prove an omitted row never existed.
These indexes are transactionally maintained authority, not caches checked by rebuilding history on
each operation. Their concrete layout is a host decision; complete efficient work selection is a
contract of this integration, including millions of documents and thousands active per user.

Independent ownership uses directed managed dependencies: a strongly connected component owns one
workflow; acyclic source/consumer steps are separate. A new return path expands the tentative
operation only after the canonical gas-admission check in [22 §2.1](22-processing-kernel.md#21-dynamic-joining-for-the-same-origin);
rejecting that check does not acquire the other independent scope. Separate database rows alone do not establish this boundary.
One genuinely independent source need not transact over 1,000 reverse observers.

## 2. Two instances of the same complete local loop

For each operation whose independent boundary has been established:

1. Select a bounded ready-work candidate through an index and claim its exact durable work;
   the lease/queue priority alone gives no semantic permission.
2. Read its intent, chronological prerequisites, source-observation evidence and scoped state.
3. Coordination selects and verifies the actual logical operation, then calls Contracts.
4. If evidence is missing, acquire it and restart the uncommitted operation with valid evidence.
5. Prepare and verify the complete result and persistence projection outside the write transaction.
6. Fence the exact state/semantic basis and commit all permitted state, progress, obligations and
   publication records atomically.

One Core evaluation can return several dependency-ordered `PreparedGroupOperation` values inside
`PreparedOperations`. Each complete group gets its own plan/commit; a genuinely joined group owns
all its lineages atomically. The wrapper is not a global transaction or a fabricated legacy closure
result. The concrete `DurableHost.Complete` holds one `Plan`, so the general adapter must retain
remaining group/target-progress authority durably between commits and recover it without redoing
already committed groups. Pure `MetadataProgress` and `ManagedProgress` have different cursor laws.

The source loop calculates Agreement once and retains its authenticated observation program.
Consumer loops substitute completed source actions at their recorded update/enqueue/delivery
boundaries while running their own continuations and FIFO. This preserves intermediate views and
ancestor observations without rerunning Source's business rules or preloading its final event list.

Eligibility must establish independence from actual read/write, routing and dependency authority.
A dynamic returning dependency cannot be ignored because the fixture was initially one-way.
A first host can serialize short commits; it must not hold transactions through execution, network
waits or catch-up, nor use first-committer order as business order.

## 3. What each terminal transaction contains

Depending on the actual operation and outcome:

- the complete typed owned-operation receipt, gas and authenticated semantic observation evidence;
- exact input/application settlement and operation identity;
- permitted document/history/occurrence/subscription changes and matching temporal index updates;
- successful source cursor changes only when justified by successful import;
- separately justified terminal handled-input/delivery progress, including failure outcomes;
- retained source evidence, durable discovery/outgoing work, reverse waiter/readiness changes and lifecycle consequences;
- exact completion/commit evidence and required publication records.

No fake epoch, success cursor or gas result is created for a host-only progress decision. A failed
operation publishes its specified terminal evidence and rollback projection, not its tentative effects.
Immutable definitions and evidence should be retained once and referenced by bounded progress deltas.
The projection list may contain zero business lineages for no-delivery, one independent lineage,
or several already-existing members of an owned cycle. One atomic receipt authenticates every
required stream append and predecessor; new canonical source authority is separately identified.
No member is omitted or disguised as newly created to fit a single-lineage interface.

Do not serialize a whole private runtime/result graph. `OperationReceiptCodec` authenticates exact
owned projections and separately fragmented state/program/failure evidence. Source records retain
four distinct identities: semantic operation, authenticated receipt-content key, actual publishing
commit, and optional staged-prefix provenance. Explicit `SUCCESS` has a program; `FAILURE` has a
verified failure capability and no successful program; `METADATA` has no new semantic operation.
Failed sources preserve their successful state/epoch while retaining terminal semantic progress.

Initialization has three distinct states: authored input, a non-authoritative cached calculation,
and an authoritative initialized lineage. Canonical source-local initialization plus FULL_HISTORY
defines one source basis for authored X and the frozen environment. Source initialization and parent
consumption use separate fixed gas scopes, cold or warm; source gas settles once on publication.

FROM_NOW/FROM_FRONTIER/FULL_HISTORY choose observer attachment history and its installed initial view,
not X's source origin. Exact views may need fetching before attachment can finish. A failed creator
publishes no orphan source; a later failed Order cannot erase already authoritative X. Promoters use
the same canonical source result, and neither first INSERT nor physical materialization time chooses it.

## 4. Discovery, activation and historical reads

A source can be ahead of a consumer's processed topology. Its indexes remain consistent with committed
history; explicit lifecycle/processing frontiers represent earlier unprocessed attachments/removals.
Retain source evidence and use indexed temporal occurrence authority to discover due work without
requiring all parent payloads or N jobs at source commit. A current-links-only list, empty queue or
notification ACK does not establish closed delivery coverage.

In PostgreSQL, source-to-consumer queries page due recipients, reverse dependency/waiter queries
find work released by new evidence, and ready/due-work queries drive execution and recovery. No
normal step scans all platform documents, every document of a user, or rebuilds document histories.
Actual query/index plans, atomic maintenance and bounds must be demonstrated in the POC rather than
left behind an opaque completeness object. See [06's access patterns](06-storage-and-transactions.md#1002-indexed-work-selection-is-a-host-contract).

Registration briefly fences source position h with the continuing occurrence. Selected historical
work comes through h; subsequent delivery follows the tail. This avoids lost/duplicate handoff work
without holding a lock through backfill. The boundary is not permission to expose later history early.

For source values 1 at T10 and 2 at T20, the Order's original T15 read observes 1. The same principle
applies inside a source operation when the reference exposes intermediate updates or event contexts.
Latest-head reads and permanent receipt-after pins are insufficient substitutes.
After a terminal failed import the actual consumer pin is still its rollback pin; a later local read
uses that exact value until a subsequent successful operation changes it. A completeness proof is
not authority to silently install Source's newer state.

Multiple placements need complete discovery and separate progress. Update in canonical occurrence
order, completing each synchronous continuation before the next placement; /left may therefore read
/right's old value. Revalidate retired/rebound occurrences before updating them. Pages cannot split
the complete reaction, reset gas, suppress valid frozen deliveries or choose this order.

A newly created placement is not added to an already frozen event. Initialization and its selected
initial view are available at the actual creation/installation site; historical source epochs form a
separate ordered attachment lane. A later handler sees the installed value. A workflow's earlier
buffered preview read is still before installation and must not be relabeled as an installed-view
read. There is no fabricated caught-up parent shadow. Do not wait
inside the creator for historical work that can start only after its commit.

For Root → Parent → Source, preserve original Source event provenance, composed paths, required
ancestor observations and actual queue admission order. Do not require Parent to re-emit Source E
or impose an immediate-Parent final-state relay. Compose upstream observation programs sharing the
same reaction origin into the complete downstream reaction, including exact terminal outcomes.

Historical topology changes invalidate obsolete unprocessed work under the actual lifecycle rule;
they do not erase already committed history or automatically cancel valid current-invocation work.
Accepted but not yet materialized owners count toward discovery completeness. Proven closure is also
needed before garbage-collecting source evidence still owed to a delayed consumer.

Canonical history can be staged in immutable, verified pages of at most 128 rows. A complete sealed
root activates with final pointers and bounded source registration cuts; the current maximum is
1,000 dependency sources, also subject to plan/control and byte budgets. It does not rewrite every
historical row or enumerate all recipients in one transaction. Readers verify root/receipt membership
and later continue into the live suffix using authoritative tails, not short-page assumptions.
Larger real fan-in is an explicit operational capacity boundary to measure, not demonstrated support.

The local Timeline provider ports MyOS clock/lock/strict-microsecond rules and uses a closed HMAC
proof bound to exact typed Timeline identity and scope. It is not the entire MyOS provider wire API.
The general adapter must translate that exact host identity to Core's verified raw-locator fields
and authenticated directed membership. Required metadata remains complete even when bodies are
absent; reverse-only Orders are not source-input prerequisites. See [15](15-myos-timeline-foundation.md).

## 5. Recovery and publication

Missing exact resources export no half-Contracts continuation in the implemented boundary.
Restart only uncommitted logical work and reconcile uncertain commits before rerunning. Reuse already
authoritative source evidence; do not recalculate source rules to compensate a lost consumer response.

Current live gas failure can leave document state/epochs unchanged while retaining a terminal
input result; later ordered detach/new inputs can recover. Preserve that positive control. An unchanged
successful import cursor does not necessarily mean the delivery remains forever unhandled.

For managed r1 ending in GAS_LIMIT_EXCEEDED or recognized deterministic RUNTIME_FATAL, record its
terminal failure without changing successful state/view or creating an epoch. The owning library
must certify the semantic category/context and actual admitted gas; AtomicScopeGasAdmissionFailure
is one such specified category, not a claim that the local meter reached its limit.
Due r2 validates original source continuity and intervening outcomes,
aligns actual consumer0→r2-before1 through a normal containing update, then interprets r2's own1→2
program. One gas/rollback scope includes both phases; neither r1's original source events nor its
failed private queue is replayed. An early r2 event can read1; alignment can itself fail and roll back.
Later D30 detach follows earlier due terminal outcomes. SQL pages/history ranges do not stop or
discard their suffix after first failure; their fixed semantic obligations are accounted for in order.
Actual library and cold-receipt tests prove this behavior; the general application adapter still
must wire it. It is not a host cursor workaround. Failed observation evidence is keyed by
consumer/source pair, not one ambient pin shared by all consumers.
Pure managed continuation is not blanket consumption of every status. A composite operation with
its own direct external seed follows its frozen external-input policy for all selected external and
managed lanes together, under one settlement. Gap continuity accepts every authenticated lawfully
consumed outcome, including such composite external-policy failures, after verifying the original
kind, classification/policy, shared settlement and exact lanes. A nonterminal or unhandled gap rejects.

Timeouts, cancellation, I/O, unknown commits and unclassified implementation exceptions are operational
failures, not consumable runtime-fatal results; they create no consumed input, epoch or semantic gas
settlement. Missing or unproved evidence does not grant a skip. Initialization remains distinct:
failed initialization supplies no usable initialized source and cannot publish an initialized lineage.
[22 §6.2](22-processing-kernel.md#62-semantic-failure-classification-is-an-owning-library-contract)
requires owning-library typed semantic-failure mappings before this continuation is enabled.

The actual E2b PostgreSQL case now demonstrates M1 committing while selected M2 execution data is
absent. A fresh JVM restores M1's receipt and lane, rejects corrupt M2 bytes, acquires the exact
fragment and commits only M2. A separate missing-selection case remains waiting with no outcome.
This closes that thin durability witness; it does not deploy a general historical acquisition loop.

A cyclic reaction inside one atomic workflow retains one shared gas/rollback boundary until success
or failure. Persistence cannot turn its hops into fresh operations. Passive cyclic representation
finalization is separate: preserve authenticated same-epoch rebinds without business receipt echoes,
synthetic events or gas resets.

Level-triggered durable rechecks recover missed wakeups for resources and discovery. `WAITING`
registers exact needs; a capacity `PAUSED` state requires repair and explicit release, not immediate
reclaim. Quota replenishment does not release unrelated capacity holds. Notifications
accelerate them but are not the authority. Test supply before wait registration, commit before
notification, and crash after only a subset of independent consumers finishes.

Outbox publication follows the exact authorized semantic stream, not worker commit order. Read a
successor or bounded page; receiver-side deduplication is needed when ACK loss must not duplicate an
observable effect. Equal event payloads do not collapse distinct semantic occurrences.

Whole-cause reporting is optional host/test bookkeeping, not a mandatory global receipt, Contracts
operation, epoch/gas charge or gate for independent commits. If reported, success requires accounted
required work and closed delivery discovery; local durable outcomes and open obligations remain
mandatory regardless. Source commit latency is not aggregate completion. If K100 imports old E10 and generates F, retain E10 provenance but keep
the new reaction in K100's logical completion, rather than reopening old settled computation.

## 6. Verification and measurement

Phases1/2 are ready in the documented scope: actual library witnesses and target-shaped PostgreSQL
primitives passed, followed by the final pooled seven-case handshake. This is not formal acceptance
of every scenario catalog row or a measured production workload. The remaining Phase3 sequence is:

1. Install one runnable general adapter and bounded ingress/recovery/discovery/outbox pumps.
2. Extend that same path from a counter to small graph/attachment/failure stories, then perturb
   commit, publication, acquisition and restart boundaries with independently specified traces.
3. After correctness, run representative examples and preregistered scale measurements, then plan
   the next owning-library/host iteration. Phase3 is not presumed to finalize Coordination.

Run the affected tests and small story after a change; run impacted regression once at a stable
slice boundary. Reuse unchanged host66 evidence rather than rebuilding every suite for each story.
Serialize included-library builds, preserve exact candidate hashes/reports, and retain failed or
inconclusive measurements instead of replacing their oracles.

Compare the proved reference with eager/lazy, cache, paging, restart and worker-order variants.
Two new implementations sharing the same wrong assumption are not independent proof.

Measure source calculations, logical gas, consumer work, evidence verification, bytes, metadata,
copied/hashed/serialized controls, retry amplification, WAL and heap separately. Cover N consumers,
P placements per consumer and depth. Reusable evidence may use immutable shared prefixes; this is not
permission to retain or fetch the full resolved graph for every observation.

Keep a warm working set where useful, but never let it change semantic gas or history. Report source,
consumer and requested aggregate latency honestly, including failures and unfinished consumers.
Healthy small groups target seconds. At 1,000/10,000/100,000 Orders, prompt Agreement commit is relative
to its own required work; total fanout drain may grow with N. Required reactions are real metered
work, not a constant-time target. Avoidable copying/source reruns/global scans are separate overhead.

Test bounded memory, transactions, acquisition and worker concurrency, backpressure, fair service
and tenant isolation. Large fanout must become controlled backlog, not destabilize unrelated users.
Check ready-work and reverse-wait lookup plans with millions of dormant/unrelated documents and
thousands active for one user. Tune queues, indexes, partitions and batching from measured results
without changing per-document semantic order or charging extra logical gas for technical retries.

Timeline membership is directed: Order's TO must not block Agreement's TA merely because Order
observes Agreement. A real aggregate embedding10000 Orders does require their relevant completeness.
Maintain and measure shared frontier/membership aggregates and incremental merging, separating initial
index construction from unchanged-topology next-entry cost; never optimize by omitting relevant inputs.

See [22](22-processing-kernel.md) for the selected rules,
[20](20-decision-traces.md) for diagnostic traces, and [16](16-scenario-run-manifest.md) for planned
run evidence. A safety hold is not proof that the required positive feature works.
