# Concurrency, scope and security

> **Status:** Phase1/2 foundation implemented; integrated concurrency/performance proof is Phase3 · **Semantics:** r15.12 · **Updated:** 2026-09-06  
> [Storage](06-storage-and-transactions.md) · [Decision traces](20-decision-traces.md) · [Scenarios](09-scenarios.md)

## 13. Deterministic local histories

Agreement and its observing Orders are independent authoritative lineages in the ordinary one-way
managed-embedding case. Agreement commits once; each Order independently consumes the exact source
receipt. Independent sibling order may vary physically without changing any lineage's semantic
history, result, gas, occurrence identity or receipt.

[22](22-processing-kernel.md) selects the algorithm and ownership model described in
[18](18-independent-lineage-processing.md). It replaces the one-active-root,
immutable all-parent partition and global committed-prefix rules that would make every Order part
of Agreement's transaction/barrier. The library now returns independently owned prepared groups,
including real dynamic join/failure evidence, and the host commits a verified plan under scoped
controls. Actual library tests and the 66-test PostgreSQL foundation pack cover these separate
boundaries; seven actual-library/PG smoke cases cover the thin handshake. The general graph adapter
and multi-worker application story set remain [Phase3](25-phase-3-integration-plan.md), not a claim
that the legacy MyOS application has already changed. See [readiness](implementation/phase-1-2-readiness.md).

A worker lease only grants permission to attempt work. Coordination first proves the local semantic
predecessor, chronological input coverage, exact occurrence cursor and causal dependencies. A CAS
winner cannot choose the order of noncommuting operations. Provider completeness alone does not
prove required source processing or temporal subscription membership has completed.

Timeline coverage is over the exact directed operation-relevant set, not every authorized Timeline
in the causality domain. Order embedding Agreement ordinarily depends on TO and TA; Agreement needs
TA until its own directed dependencies introduce TO. A lagging reverse-only observer's proof cannot
become a hidden all-observer barrier. Missing any genuinely relevant member still holds the affected
candidate. See [15](15-myos-timeline-foundation.md#directed-membership-dynamic-topology-and-scope-readiness).

## 13.1 Parallelism and scoped fencing

Independent ready consumers may be processed concurrently, or serially as a host implementation
choice. Their logical identities and eligibility cannot depend on that choice. A slow/failed Order
does not impose a pure-fanout barrier on Agreement or healthy Orders. A real returning dependency,
shared write or earlier relevant input can impose a barrier on the lineage it affects.

Use coherent scoped row/absence/temporal-predicate and actual control fences described in
[06](06-storage-and-transactions.md#1001-trusted-completeness-and-a-concrete-host-fence). Every
overlapping writer participates. A short global PostgreSQL lock may serialize physical writes, but
no universal expected domain head may make independent consumers repeatedly invalidate each other.
Such global serialization is only a correctness baseline, not proof of the scale target. Host queues
and indexes must admit fair bounded work without scanning all platform/user documents.

Exact source read pins remain valid when the source advances to another revision. Mutable target
predecessors, occurrence generation, readiness, handoff and authorization are still checked.
Accepted completeness prefixes survive stronger proofs/unrelated ingress; this is not permission
to accept partial local ingress or ignore a changed actual prerequisite.

The relevant membership identity binds the selected topology/lifecycle basis. A relevant earlier
dependency change can introduce another Timeline or return path; establish that earlier prefix and
rederive membership before declaring the later operation independent. A committed membership change
updates indexes and invalidates affected unprocessed backlog selection, not committed history or
unrelated operations. Current-invocation topology work remains in its actual core scope and must not
wait for its own unpublished result as if it were an earlier independent prerequisite.

Lock all participating work rows, primary and joined, in canonical key order before the remaining
fixed-family/canonical-key targets across publication, registration/replay, retirement, fanout
registration and reconciliation. No worker locks its own row first by privilege. Never hold write
locks during Contracts, acquisition or
an N-consumer scan. Two-worker same-application tests must commit once; reverse sibling commit
tests must produce identical local identities, not merely equivalent final counters.

The concrete host uses canonical work-key locks followed by scoped read/write controls; shared
read fences do not advance revisions and invalidate independent readers. Reconciliation checks the
retained attempt again under these locks before invalidation. The worker's semaphore bounds calls;
the current bootstrap only performs one recovery pass and a serial drain. A continuously running,
fair recovery/discovery/outbox service is still Phase3 wiring, not supplied by setting concurrency=4.

Canonical source-local initialization plus FULL_HISTORY fixes source origin under the frozen
environment/source policy. FROM_NOW/FROM_FRONTIER select observer attachment history and initial
view, not a new source birth. Independent source and parent consumption use separate fixed gas scopes:
source80 and parent20 with limits90 pass cold/warm. Actual coupled workflows retain shared gas.
This changes old creator-combined ownership explicitly, not as a cache discount.

Preparation supplies non-authoritative canonical evidence. Successful scoped promotion publishes
the complete required candidate closure and source-owned result/gas/initial stream authority; failure
or create-then-retire leaves no orphan. Prior authoritative X survives an unrelated parent failure.
Concurrent promoters agree on the same canonical origin/result, not first-worker ownership. Source
gas settles once; reconstruction after eviction is not rebirth/recharge. Parent logical costs are
cache-independent. No global all-observer transaction follows from this publication protocol.

## 13.2 Causal DAG, not whole-graph atomicity

Durable dependencies name producing operations and required semantic facts. They distinguish a
local canonical predecessor, a source prefix, an occurrence lifecycle boundary and a genuine
returning dependency. Durable branch obligations/outcomes are mandatory; optional whole-cause
reporting does not grant a blanket lock over all branches or gate independent publication.

Ordinary readonly embedding pins a source revision; it does not make that source or every observer
a WORK member. Inline content remains inside its local invocation. The selected managed owned scope
is the directed dependency SCC at the operation's logical cut; acyclic source dependencies provide
exact evidence, and reverse-only observers stay outside. A new in-operation return path requires
atomic scope admission before its edge is installed, under22 §2.1. An admitted join extends shared
ownership irrevocably; a rejected join is not a generic host-selected transaction group.

One atomic group keeps its complete gas, rollback and result; there is no portable saved private
queue. The host never cuts it into committed route/document fragments. `PreparedOperations` may
contain several complete groups, each with its own operation/receipt and independently fenced
plan. A joined group may publish that same operation/receipt for multiple owned source lineages.
Host continuation between groups must survive restart without replaying earlier commits or
discarding target metadata progress. The current one-plan loop does not provide that general mapping.

One source receipt can give rise to N required reactions. The source receipt and fanout basis commit
first; application definitions are discovered/registered in bounded pages. Neither page order nor a
full global recipient vector is the canonical local operation order.

One source operation/reaction position has one complete reaction per owned consumer scope, including
its eligible placements and composed routes. Apply each patch by canonical authored occurrence path,
finishing its synchronous continuation before the next placement and revalidating lifecycle. Interpret
authenticated source-program frames and exact enqueue sites through the common FIFO. SQL pages cannot
merge positions or split atomic gas/rollback scope;1000 independent Orders remain separate consumers.

New placements synchronously install canonical initialization or the selected initial view and
lifecycle effects without joining earlier frozen events. Historical epochs use the separate ordered
fixed-cut attachment lane. No fabricated caught-up field, creator self-wait or rollback-created work.

Nested S → P → Root recursively composes original events and P's own emissions/observable views;
do not substitute a flattened final-P batch. Cyclic containment uses vertex-simple original-event
routes, not global event/lineage deduplication. New emissions remain new occurrences under the same
gas-limited workflow. Shared immutable program/route prefixes reduce storage, not required observations.

Completion ownership follows semantic work intent, not provenance or worker order. A new K100
attachment importing E10 owns its new consequences under K100 while E10 remains closed. The producing
transition reference is derived before receipt/result wrappers; no identity preimage contains itself.

## 13.3 Temporal membership, registration and history

Authoritative temporal indexes remain transactionally consistent with committed lifecycle history.
Source-ahead execution does not justify stale indexes: Agreement@30 can coexist with an Order that
has not yet processed attachment/removal@20, so the index must also expose the relevant incomplete
processing/lifecycle frontier. Routing uses indexed occurrence identity/activation/retirement and
progress; it does not blindly freeze a current-links-only list or reconstruct every document history.

The source transaction records an open durable discovery basis and retains source events. Reporting
closed fanout, when requested, needs certified membership/frontier coverage and disposition of every required reaction.
An empty queue or drained physical page proves neither. Unknown topology can leave aggregate
completion open while independently ready source/consumer operations proceed.

Registration/replay shares authority with concurrent source publication and temporal index updates.
In a short scoped metadata transaction it captures source tail h, selected-history backfill through h,
tail acquisition after h and a lifecycle change/wake record. h is not semantic activation or processing
progress. Heavy reads/Contracts stay outside that lock. Publication-first belongs to backfill and
registration-first to the tail; overlaps deduplicate exact occurrence/receipt/reaction-position keys.
An append-only lifecycle change log or equivalent scoped generation protocol catches registrations
appearing behind a scan key. Current-row pagination alone cannot establish this guarantee.

Membership/registration completeness closes relevant lifecycle decisions independently of the
delivery processing it authorizes. It cannot wait on the very import waiting for its proof. A
particular consumer may become eligible before aggregate membership closes; no source/all-parent
barrier is introduced merely to obtain that later proof.

Initial FULL_HISTORY interleaves local original input and due source history. Active live lag is not
a new attachment. A parent at T15 reads the correct T10 pin even if Agreement@20 already committed.
Removal/rebind@20 cannot import future source history first or erase a different occurrence's work.
Tombstone obsolete future inter-operation work without an unbounded queue rewrite. Already valid
frozen local deliveries obey Contracts' event-specific validity rules; E1 retirement is neither a
blanket reason to drop E2 nor authority to force E2 through an invalid activation.
Actual pending-operation and retirement witnesses cover the library behavior; the general host
maps their verified lifecycle deltas rather than guessing from a changed reference.

Every lifecycle/state writer atomically updates the affected temporal dependency indexes and durable
work/wakeup basis. Source-to-recipient, reverse-dependency/waiter and ready/due-work indexes support
bounded queries. Readiness is maintained incrementally: scheduler priority chooses which eligible
candidate to attempt, never its semantic next input. Phase2 validated these PostgreSQL access
paths with synthetic metadata, including one million dormant lineages and 8,000 ready documents
for one account; see [06](06-storage-and-transactions.md#1002-indexed-work-selection-is-a-host-contract).
This does not prove equivalent throughput for authored graphs or real consumer invocations.

## 13.4 Conflict ordering, feedback and cycles

Dynamic same-origin joining follows22 §2.1, not merely an SCC query over committed rows. Freeze the
pre-origin direct-seed order and account for unresolved same-origin dependencies before publication.
A later seed's final state cannot replace an earlier continuation-site view. Read-only first access
and one-way dependencies preserve independent source commit; they do not themselves join groups.

Before an edge would create genuine returning dependency, atomically admit its required scope using
the groups' exact canonical admitted gas prefixes under one identical fixed semantic policy/limit L;
an incompatible policy is unsupported join input, not permission to choose a new budget. Charge the
canonical admission-check cost c first to the initiating group. If c cannot fit there, ordinary
GAS_LIMIT_EXCEEDED fails that current group without joining. Otherwise sum each distinct group's
admitted prefix, including c once, and check against L. Success installs the edge and combines
the admitted prefixes into one continuing meter and one irrevocable rollback/settlement group.
Subsequent failure affects that whole admitted group; later detach does not refund gas or shed members.
These are canonical prefix charges, not accumulated physical attempts or speculative executions.

If the checked sum exceeds L, do not install the edge. Fail only the currently initiating group
with typed deterministic RUNTIME_FATAL category AtomicScopeGasAdmissionFailure, preserving its actual
admitted gas rather than reporting fake exhaustion at L. Other groups were never joined and retain
independent authority after accounting for the failed group's outcome. If an independent consumer
tentatively observed that group's effects, discard them and reconstruct against its authenticated
failure without changing the original attempted-prefix admission decision. Missing evidence waits.
No cache hit, worker winner or physical completion order chooses admission. Replay follows the same
prefix/admission sequence; it cannot
retroactively re-meter earlier independent work to undo the decision. Stable execution/event seed
identities remain unchanged and separate from the selected settlement-group identity.

For the first POC, a single coordinator may serialize overlapping candidate planning/reconciliation.
This is not a global all-observer completion barrier. Invalidation and commit must check every affected
work reservation, including crashed/stale pre-join workers. Retained joined plans and superseded-work
aliases lead reconciliation to the same winning CommitKey/receipt; see06 §10.7. No long SQL lock
surrounds execution/I/O.
A later entry introducing a cycle does not reopen previously committed independent operations.

Same-cause order follows22's exact seed/dependency selection and observation-program continuation/FIFO.
Source-reaction predecessors and direct input cannot be reordered by receipt completion or workers.
Implement selected placement/owned-scope constructors with independent traces; CAS is never semantic
order. Scope expansion cannot remove already executed work or refund gas after a later detach.

A consumer emission affecting Agreement is new work with an exact producing operation and receiver
dependency frontier. It cannot be backdated into an already committed source prefix or selected by
which Order finished first. Agreement may have to wait for a genuine returning dependency; one-way
fanout must not pay that cost without such a dependency.

Processing cycles and exact cyclic representation/finalization are separate concerns. Vertex-simple
per-route containment is not a global visited shortcut and never joins all observers. Do not
commit per-member values without required valid cyclic identity proof. Hold an unsupported/unresolved
scope before effects rather than silently invent ordering, gas, BlueIds or success.

Feedback that belongs to one core operation keeps its complete atomic scope and shared gas. It must
not be turned into an endless chain of fresh-budget host jobs. Its gas failure is terminal with exact
rollback and original-input progress, not a reason to retry forever or block all later eligible work.
A genuinely new independent operation is different, but requires semantic authority—not a worker
continuation label. General liveness still requires finite supported work and eventual evidence.

Legitimate repeated attachment/import operations can form an unbounded sequence without exceeding any
one operation's gas. Host account/document quotas and fair bounded scheduling contain this sequence;
the first POC adds no inherited cross-operation fuel. A quota pause retains all pending obligations
and cannot admit later repair ahead of them. General semantic cancellation/interruption is deferred,
not a missing requirement for this first POC. Required finite gas-failure→detach recovery remains.

## 13.5 Local failure and recovery

A deterministic failure is durably recorded with its exact local pre-state, status/gas and
operation-specific progress. Only original external-input processing and its historical replay use
the frozen external consumption policy; consuming a failed input creates no successful state epoch.
A managed GAS_LIMIT_EXCEEDED or verified deterministic semantic RUNTIME_FATAL import retains all
successful receipt cursors and the failed group's rollback pre-state while durably terminating that
delivery with its exact status. Composite external+managed failure follows its frozen external policy:
all selected lanes are consumed together under one settlement or none are consumed. The selected r2
continuation accepts every lawfully consumed intervening delivery, including such composite failures,
using actual rollback state, continuous source evidence and exact status/policy authority. Align consumer0→1
to r2's exact before-view with ordinary synchronous update/lifecycle/gas effects, then interpret its
program1→2. Event-before-patch reads1; eventless/net-zero cases preserve alignment/observations.
Failure anywhere in r2 rolls back to0. There is no failed-event replay, forged receipt or skipped
unattempted range. This is the implemented owning-library extension. Initialization failure publishes no
initialized lineage. Independent consumer failure does not roll back committed Agreement or healthy siblings; a
single core-owned feedback/creation operation still rolls back its full owned scope. Retain failed/pending
branches under their exact completion owner; never report false full success.

Other processor statuses retain their exact operation/status-specific progress law. RUNTIME_FATAL
is consumable only when verified as a deterministic semantic failure, not merely returned by a broad
RuntimeException catch. Unknown implementation faults, host cancellation/timeouts and unavailable
resources remain operational failures/waits and cannot settle input. Require failure-boundary and
portable-diagnostic conformance tests before relying on this distinction. Failed initialization has
no usable initialized source; its rejection does not permit ordinary source processing to continue.

Actual managed-import tests prove the rule against Contracts views/events/gas, including cold
per-consumer/source observations when co-owned consumers have different pins. D30 follows earlier
due r1/r2 lawful terminal outcomes; it does not
silently cancel owed work. Missing resources remain waits, not manufactured terminal failures.
The general MyOS adapter has not yet wired this positive library path. Independently eligible later inputs
and healthy consumers continue under their own authority.

Provider outage, missing execution payload, retry expiry and cancellation are operational only.
Preserve exact protected local cursors/obligations and handoff reservations. A cancelled attempt
does not skip an input. Genuine downstream dependencies wait; unrelated ready branches need not.

Quota exhaustion has the same operational-only boundary. With fixed limit100, needed80 and account
allowance50, pause rather than fabricate GAS_LIMIT_EXCEEDED at50. Resumption uses the same semantic
policy and exact work; attempts/retries do not create additional logical gas settlements.

`PostgresWorkStore` persists operational `PAUSED` reasons rather than busy-looping on the same work;
`resumePaused` releases repaired capacity holds and replenishment releases quota pauses only.
Account selection filters actually due ready work before its bounded candidate page, so accounts
with future-only work do not starve a later ready account. These are tested scheduling primitives,
not a measured multi-user application service guarantee.

Work-fenced CommitUnknown reconciliation and durable level-triggered needs close duplicate-commit
and lost-wake races. Supply-before-register, crash-after-supply and stale waiter cleanup remain
mandatory. This does not create a portable mid-invocation continuation.

## 13.6 Publication and isolation

One complete result projects an exact typed list: zero business appends for metadata-only no-delivery,
normally one lineage for independent work, every owned affected existing lineage for coupled work.
Each actual terminal lineage append may be empty; the shared receipt authenticates every result,
before/after and stream predecessor, rejecting omissions/extras. Publish each stream's exact
successor/bounded page after required causal predecessors. Sink deduplication
precedes ACK, then the stream cursor advances. Unrelated tail growth or independent sibling streams
do not impose a global physical publication order.
Cross-stream predecessor requirements concern distinct operations, not event-by-event network
interleaving inside one atomic result. Preserve canonical A1,B1,A2 in the result while allowing
per-lineage batches A[A1,A2]/B[B1] to arrive separately; no cyclic same-operation prerequisites.

Creation may add canonical initial source projections/batches under that same transaction receipt;
no orphan prepared source event can escape before successful authorized promotion.
For prepared-prefix ranges, trusted OutboxPort.readNext verifies each batch's exact membership and
activation internally using bounded immutable proof/index paths. Range endpoints or a self-hashed
batch alone are insufficient; no extra public proof DTO is introduced. Audit substitutions, wrong
roots/receipts and unactivated staged rows before sink publication, as specified in06 §10.6.
Prefix staging is page/byte bounded; activation requires a sealed root before reading its source
summary and fences at most 1,000 dependency sources within the plan's control budget. Larger real
fan-in is a capacity boundary, not permission to omit a relevant source. Prefix/live readers use
authoritative tails and explicit causal dependencies, never infer exhaustion from a short page.
Verified representation-only companions use the triggering operation's required component fences,
preserve business epoch/receipt positions, and create no synthetic business receipt/event/fanout or
gas reset. Coupled representation publication does not merge all SCC business work or all observers.

Initial isolation remains authenticated tenant/environment/domain. Reject cross-domain graph/work/
Timeline references. Dynamic relocation and distributed transactions are deferred. The trusted
Timeline universe validates provider/type/identity and authorization; it is not a bootstrap list of
all concrete Timelines. Complete initial/result subscription proofs permit new authorized same-provider
Timelines without a stack reset and reject unsupported/unauthorized references before publication.
Coordination derives the exact relevant set for selection from directed dependencies and lifecycle
evidence. New or removed members trigger the corresponding backlog/membership transition; they are
never silently excluded from completeness or replaced by the whole-domain cohort.

Authorization precedes evidence/cache access and is rechecked through current commit controls.
Trusted query adapters are independently audited against raw state; hashes do not prove coverage.
BEX keys include authorized universe and the full actual compiler/environment key. The public
cache remains get/put; bounded weighted caching/singleflight at the host level must be separately
wired and measured if used, not claimed as a Phase1 addition. Diagnostics remain observational and safe.

The local PostgreSQL Timeline provider uses caller-supplied HMAC credentials and a closed canonical
payload bound to exact Timeline identity and scope. This is a concrete POC trust boundary, not full
wire/API interoperability with the existing MyOS provider or a production key-rotation design.
Database bootstrap credentials authorize the operator; no new public plan-submission or grant API
is exposed. Production HA, distributed transactions and legacy data migration remain out of scope.

## 13.7 Performance target and proof scope

For ordinary shared Agreement, source processor work and source-side payload memory must not grow
by replaying/hydrating N Orders. Its commit does not await fanout enumeration/completion. Total
necessary consumer reactions remain O(N), including at 1,000/10,000/100,000 Orders. Measure bounded
discovery/index work, source calls, per-consumer calls/gas, physical retries, WAL and peak materialized
bytes separately.

Measure source commit, per-consumer lag, successful whole-cause completion and publication lag as
different endpoints, with aggregate completion optional reporting. A failed consumer must not look like improved throughput; a held consumer must
not prevent healthy progress in the independent fixture. Seconds-scale small-graph completion is a
POC target to verify, not a deadline for all fanout sizes or permission to weaken chronology or hide unresolved work.

Sustained fanout must become controlled backlog, not resource exhaustion. Bound resident work,
transaction size, concurrent invocations/acquisitions and publication batches; apply admission
backpressure and fair service across users/workloads. A user with thousands of active documents or
one large fanout must not monopolize the platform. Report consumer throughput/lag, queue age, resource
ceilings, lock contention and recovery after overload. Concrete indexes, partitions and queues are
host choices; stable service, complete indexed work selection and unchanged semantic order/gas are
integration requirements. Necessary consumer execution is metered work, whereas cache misses, ACK
loss and physical retries cannot choose a different logical charge.
