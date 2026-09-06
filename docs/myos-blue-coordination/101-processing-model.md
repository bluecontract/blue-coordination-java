# Processing model — optional technical bridge

> **Status:** Phase1/2 handoff synchronized; integration pending · **Design baseline:** 15.12  
> Read the [beginner tutorial](tutorial/README.md) first. This is a compact bridge to the engineering documents.

[The selected processing kernel](22-processing-kernel.md) has highest
semantic precedence. The libraries and host foundation are implemented in the scoped
[handoff](24-phase-1-library-summary.md); the general application path and measurements remain
[Phase3](25-phase-3-integration-plan.md) work.

## 1. The ordinary case is independently committed source and consumers

The target remains one Agreement source calculation reused by independently progressing Orders.
Agreement must not load or await 1,000 parent payloads to commit its genuinely independent result.
Each Order must still perform its own required reactions, retain exact progress and publish only
complete logical results. A failed Order does not compensate an already independent source or sibling.

Two changes must not be confused:

- Physical factorization: lazy reads and source reuse must preserve the logical processing.
- Independent ownership: choosing which source/consumer operations can commit and fail separately
  uses the directed-dependency SCC boundary in the selected kernel. It is not implied by putting a
  child in a separate row; this explicitly changes current connected-closure ownership.

Ordinary Contracts processing remains the reference for observations, update/event order, lifecycle
and atomic workflow gas. Phase1 replaces the baseline managed one-hop/final-state-only shortcuts.
Its conformance controls do not replace full durable integration evidence. A candidate eager
interpreter agreeing with its own lazy version is insufficient.

One atomic workflow completes its required reactions or fails under its shared gas and rollback
before a later Timeline input overtakes it. Cyclic hops do not receive new budgets or become new
independent commits for scheduling convenience. This is not a demand to transact over all unrelated
observers. Follow dependencies toward embedded documents, not all reverse observers. A real return
path puts its component in one owned workflow; a newly created return path expands that same
tentative scope and budget before anything commits.

## 2. Keep identities, views and progress distinct

DocumentId is the exact initial authored BlueId, not a host allocation. Equal authored values select
the same intended lineage. Current exact state, occurrence placement/activation and accepted history
basis are distinct coordinates; cyclic identity needs its owning authenticated construction.

| Input/evidence | Meaning |
|---|---|
| Authored uninitialized value | input to the fixed source-local canonical initialization |
| Cached initialization result | non-authoritative reusable calculation; no document birth by cache hit |
| Authoritatively initialized value | the same canonical source operation already published with evidence |
| Source result evidence | enough authenticated observations to reuse completed source work |
| Successful source cursor | source history actually imported successfully at this placement |
| Terminal delivery progress | work handled with an exact outcome, including a noncommitting failure |

Warm/cold execution, eviction and physical rebuild must agree for the same accepted basis. Reuse must
not erase required initialization observations, alter gas-limit outcomes, or manufacture a second
birth. Source initialization and parent consumption have separate fixed budgets even on a cold host;
source80 and parent20 each fit limit90. Source gas settles once at authorized source publication.

The source has canonical FULL_HISTORY. FROM_NOW/FROM_FRONTIER select an observer's history and
initial view, not another source origin. With E15, source X includes E15 whether the first Order
appears at T10 or T20; FROM_NOW@T20 simply omits E15 delivery to that observer. First SQL publication
cannot choose the source result. Failed introducing work publishes no orphan new source and does
not erase already authoritative X.

## 3. Source-ahead progress does not expose the future

Agreement commits count 1 at T10 and 2 at T20. An original Order T15 read must see 1 even if the
latest row contains 2. A delayed occurrence retains its original activation; a genuine later
attachment imports selected older history at its own causal position.

Within one source operation, a final snapshot can also be too late:

- Source patches x 0→1→2; parent Document Update observations must retain the required two changes.
- Source emits E1/E2; its own Triggered handlers set x1/x2; parent Embedded handlers observe [1,2].
  Permanently exposing source-after2 would incorrectly produce [2,2].
- Parent(E1) emits P1; source(E2) emits F2. Pre-enqueuing a flat source batch can move F2 before P1.

These are hand-derived controls, not newly executed tests. [Tutorial 13](tutorial/13-equivalence-and-reuse.md)
explains them. One handler's buffered patches-before-emissions control may correctly expose its
final value twice; the actual processing boundaries decide, not a universal emission-time snapshot.

Do not confuse two Timeline inputs with two internal events emitted by ONE input. The simple
two-input case gives Root separate historical Parent views1 then2. In the one-input FIFO case,
Parent(E1) sets1/emits F1 and Parent(E2) sets2/emits F2; queue E1,E2,F1,F2 makes a document read at
F1 see2 while F1's frozen payload can remain1. Internal events do not create epochs per emission.

The retained observation program records exact visible updates, event enqueue/delivery sites and
source views. The consumer substitutes those completed actions while executing its own reactions
under the same continuation/FIFO rules. It neither reruns Source business code nor preloads final
source events. Frames reference shared exact content, not every VM instruction or the resolved graph.

Multiple placements update sequentially in canonical occurrence order: /left's synchronous update
can still read /right0; a later event sees1 after both advance. New placements do not join an already
frozen event. Their initialization/selected initial view is synchronous; historical source epochs
are staged as separate ordered operations. Creator reads cannot invent a caught-up shadow field.
Original Source E reaches valid authored ancestors without requiring Parent to re-emit it.

## 4. Input completeness and historical relationship closure

Canonical microsecond order, exact tie-breaks, strict provider completeness and complete local ingress
prove which input is next. Leases, database order and wall-clock arrival cannot choose that history.
Order with Timeline TO embedding Agreement with TA needs their relevant prefixes. Agreement does
not need reverse-observer TO: a stopped Order provider must not block Agreement. A genuine aggregate
embedding10000 Orders does need those members' relevant prefixes, maintained through indexed
membership/frontier aggregates and incremental merging rather than full rescans per entry.

A source-ahead receipt cannot close delivery from current parent rows if a lagging admitted owner
may still process an earlier attachment/removal. Retain sufficient source evidence, semantic
activation/history selection and recoverable discovery progress. Complete hashes of incomplete query
answers are not proof. Processing and membership-discovery frontiers are different obligations.

A short fenced registration of retained source position h and continuing relationship avoids a gap
between historical backfill and live delivery. It does not hold the source lock through replay or
authorize consuming receipts before their logical position. Relevant accepted but not yet materialized
relationships count toward coverage; later admissions retain their own authorized historical work.

## 5. Causal order, feedback and failure

Newly produced work follows its actual causal owner and atomic workflow, not its oldest remembered
source timestamp. If K100 imports old E10 and produces F, E10 provenance survives while the new
reaction belongs to K100. Do not backdate F into an already settled source history.

Passive cyclic representation finalization remains distinct from business feedback: no synthetic
business epoch, event, receipt echo or gas reset. Real cyclic reactions retain one atomic workflow's
shared gas and rollback; a global visited set cannot discard valid repeated work.
One original event follows vertex-simple routes: a DocumentId is not repeated on that route, so
its transport does not echo back to its emitter. Distinct alias routes remain distinct. A new
handler emission starts new routes under the same meter; genuine feedback can still exhaust gas.

A missing exact resource is not an absent value or a deterministic business failure. Restart the
affected uncommitted operation using valid evidence; do not publish a partial queue. A source result
already independently committed remains authoritative and reusable.

The current live external gas control rolls back state/epochs, retains a terminal input result, then
accepts a later detach and new input. This inspected test is not a new execution claim. Preserve this
positive recovery requirement. Failed managed import currently leaves its successful cursor unchanged.
The selected extension accounts for that failure, then selects the next canonical operation. For
consumer0, failed r1 and source r2:1→2, r2 first performs an ordinary containing alignment0→1, then
its own1→2 observations. Alignment and replay share one gas/rollback result; original r1 events are
not replayed. An early r2 event reads1, while an intervening local input before r2 still reads0.
Alignment can itself fail again. Later D30 may detach after preceding terminal outcomes; no physical
page/range is abandoned because one operation failed. Nonterminal missing evidence still waits.

Publication has zero business projections for no-delivery metadata, normally one for an independent
operation, and all required existing members for a coupled operation. One commit/receipt covers its
typed projections and stream predecessors; unrelated Orders never join merely because they observe.

## 6. Equality and performance

First prove ordinary-to-factored observations and the explicit ownership/gas mapping using actual
Contracts and independent small traces. Then compare eager/lazy, warm/cold, paging, worker permutations,
restarts and PostgreSQL-backed execution. Neither test suite may normalize away a real difference.

Measure source executions, retained evidence, consumer reactions, receipt verification, semantic gas
and physical I/O separately. Physical reuse can save CPU without deleting logical gas charges.
Measure N independent consumers, P placements in one consumer and nested depth separately; bounded
reads do not prove bounded total work. Count copied/hashed/serialized evidence and retry amplification.

The host maintains transactionally consistent temporal-dependency, ready-work and reverse-wait
indexes; normal selection queries the relevant ranges instead of all documents/history. Validate
PostgreSQL query plans with millions of unrelated documents and thousands active per tenant.
Measure independent source commit and consumer throughput/lag separately. Required1k/10k/100k
reactions and their gas may grow with N; memory/transactions/concurrency stay bounded and scheduling
must be fair under overload. Healthy small groups target seconds, not every possible fanout size.
Whole-fanout completion is optional derived host/test reporting; any all-complete claim requires
closed discovery and exact outcomes. It is not a global commit, gas charge or source-progress gate.

Next: [202 — durable host bridge](202-durable-host-integration.md). Engineering context:
[18 — independent lineages](18-independent-lineage-processing.md), [05 — API](05-poc-api.md),
[20 — decision traces](20-decision-traces.md), with [22](22-processing-kernel.md)
controlling the selected algorithm. The three implementation phases and later
iterations remain planned work, not authorized or completed by this revision.
