# Lazy loading, retry and recovery

> **Status:** Phase1/2 implemented and verified in the documented scope; Phase3 integration planned · **Semantics:** r15.12 · **Updated:** 2026-09-06  
> [Storage](06-storage-and-transactions.md) · [Decision traces](20-decision-traces.md) · [Validation](11-validation-plan.md)

## 11. Lazy-loading model

The source and each consumer reopen independently from durable local authority. Agreement publishes
its exact revision and fanout basis without hydrating all Orders. One Order loads its own predecessor,
proved local scope/cursors, source receipt and required semantic-observation evidence; it does not
reconstruct the whole graph or rerun Agreement. Physical pages do not define the semantic import
boundary. [22](22-processing-kernel.md) selects owned scope, recursive observation programs,
placement/routing order and failure alignment; actual library witnesses now cover those boundaries.

This is the implemented library-owned model in [18](18-independent-lineage-processing.md).
The [readiness record](implementation/phase-1-2-readiness.md) separates library witnesses, 66 passing
PostgreSQL foundation tests and seven passing real-library/PG smoke cases from the missing general
MyOS adapter. The default application still uses its legacy path; [Phase3](25-phase-3-integration-plan.md)
must wire evidence acquisition, result dispatch and runnable recovery/discovery/publication loops.
For a non-API explanation, start with the [beginner tutorial](tutorial/README.md).

An attempt loads only the selected operation and evidence it needs:

- Exact configuration, logical operation, local predecessor and owned content.
- Pinned source revisions, authenticated receipts/events and occurrence lifecycle/cursors.
- Relevant original-input, source-prefix and conflict-order coverage.
- Actual managed access/write dependencies and required cyclic identity proof.
- Scoped temporal subscription, registration/replay and fanout progress evidence.
- Current work, reservation, authorization and exact acquisition needs.

Queries are bounded and normalized independently of physical page size. The trusted adapter supplies
complete coherent scoped facts under [06](06-storage-and-transactions.md). Hashing returned rows cannot
prove no omitted match. The host maintains authoritative temporal dependency and ready-work indexes
transactionally with their source state. Independent history reconstruction is a test audit, not a
normal prerequisite for each lookup. Physical page cursors, SQL revisions, cache keys and sibling commit order
never enter semantic identities.

The cache is bounded and non-authoritative. Verified exact content can be shared safely; mutable
authority is scoped/versioned. Eviction adds reads, not semantic changes. A fresh JVM reopens one
operation from immutable references and small cursors without reading every completed operation.
Required topology can exceed loaded bodies; count both rather than claiming constant-time discovery.

The current codecs retain exact immutable fragments rather than private runtime heaps.
`OperationReceiptCodec` reads receipt metadata without restoring every state body; its restore
methods authenticate states, source programs/failures, managed lanes and read-cut authority through
that enclosing receipt. `FrozenNodeEvidenceCodec` and `ReusableComponentAuthorityCodec` preserve
bodyless headers and cyclic proof authority. Missing metadata is not an empty subscription surface.
One selected source program and its borrowed DAG are currently decoded within physical budgets:
this is not a fully streaming action interpreter. Recording intermediate observations also has a
capture-time memory cost that transport limits alone do not bound.

Canonical source-local initialization plus FULL_HISTORY fixes source origin independently of promoter
or physical admission. FROM_NOW/FROM_FRONTIER select observer history/initial view, never another
source birth. Source initialization and parent consumption have separate fixed meters when independent:
source80 and parent20 with limits90 pass cold/warm. Real coupled work retains its common gas scope.
Retained authority survives byte eviction; its loss is data loss, not an equivalent cold-cache test.

Preparation supplies authenticated canonical candidates but no authoritative publication or source
gas settlement. Scoped successful promotion installs the complete required candidate closure; failed
promotion/create-then-retire publishes no orphan, and prior authoritative X survives parent failure.
Source gas settles once, not again on rebuild; parent logical verification/update/handler costs are
cache-independent. Record physical work separately. Use the implemented owning capabilities and
receipts, not a host shortcut or old creator-combined-gas comparison.

### Source-ahead loading and future payloads

An Order reading T15 may need Agreement@T10 while Agreement@T20 is already committed. Fetch the exact
authorized historical pin, not the latest head. A readonly pin remains valid as the source advances;
current target/occurrence/write preconditions must still be validated.

Complete source publication/M1 may commit while future consumer/M2 execution-only bodies are absent.
The current transaction records exact source receipt/fanout basis or remaining local cursor/obligation
authority. M2 acquires and verifies its body before its own complete invocation; it never reruns
committed M1. Missing current-result, temporal membership or selection authority can still hold the
operation that genuinely needs it.

This M1/M2 boundary is now an actual Phase2 PostgreSQL witness, not only a requirement. A real
FULL_HISTORY creator establishes the lane; M1 commits state/cursor/outcome/outbox while M2's selected
execution fragment is absent. A fresh JVM rejects corrupt replacement bytes, acquires the original
fragment and completes only M2. M1 remains exactly once and the ordinary Timeline cursor is unchanged.
A separate actual Core missing-selection control stays waiting. The final seven-case run passed in
22 s; that includes fixture setup/reconstruction and is not an accepted settle-latency measurement.

Fanout discovery can remain open while healthy branches progress. Source events stay retained until
complete temporal membership/frontier and replay coverage account for the applicable consumers.
A current-links-only index or empty page is not a no-recipient proof. Use the transactionally coherent
temporal index and lifecycle/processing frontiers to distinguish complete membership from earlier
unprocessed topology. Registration/replay must cover source publication during lookup-and-subscribe
and delayed topology after a scan passed a range; it must not repair this by scanning all histories.

The scoped handoff captures source-log h with backfill through h, live acquisition after h and a
durable lifecycle change/wake record. h affects acquisition only; source-ahead data still waits for
its semantic eligibility. Follow lifecycle changes to catch registrations appearing behind a scan
key. Membership/registration completeness is separate from processing progress and must not wait on
the very receipt whose eligibility it proves. See [06](06-storage-and-transactions.md#103-no-gap-registration-replay-and-retirement).

### 11.1 Work, demand and cache identities

Immutable work, decisions and contained demands bind the frozen semantic configuration and exact
logical work/action identity. Implemented operation identity binds cause, operation kind, semantic
owner/predecessor, complete reaction group and policy through the owning constructors. Do not add
unrelated host cuts or global commit ordinals to InvocationId. A build fingerprint is operational,
not a semantic configuration override.

CompletionOwnerId is independent of original source provenance: new reactions from K100 importing
E10 belong to K100, not reopened E10. Discovery/backfill origin and physical handoff h cannot choose
that owner or delivery identity. Producing transition references use the acyclic, already-derived
operation/InvocationId seed, never a hash of a result wrapper containing its own origin.

The BEX cache key is the authorized cache universe, full semantic environment identity and actual
engine-derived BexCompiledProgramKey. That key includes program kind/identity, definition identity,
entry name and compile environment: compiler, runtime registry, gas manifest/weights, Language registry
and intrinsics. BexCompiledProgramKey.from(source) alone is not the environment-bound key.

The public BEX cache still exposes get/put; an LRU implementing only those methods does not coalesce
misses. Phase1 reused the existing cache facilities rather than adding a host-wide singleflight cache.
If Phase3 adds that optimization, its loader/compiler wrapper must use this exact authorized key,
bounded waiters and cancellation isolation: cancelling one waiter must not cancel/corrupt others.
Byte-weighted tenant/global limits and oversized-result handling must be measured separately from
semantic gas. Authorization precedes lookup and no cache hit grants cross-tenant access.

### 11.2 Evidence representation and amplification

Structurally share verified immutable blobs or safe slices; preserve exact value equality. Store
fanout/operation definitions once, with bounded page progress and local dependency/cursor deltas.
Do not repeatedly serialize a growing completed prefix, global plan or pending obligation list as
current authority.

Measure unique bytes, peak resident/retained bytes, cumulative copied/decoded/hashed bytes, proposal/
operation/receipt serialization and WAL. Separate host bookkeeping from repeated Contracts
verification/execution. NeedsResources may restart already performed work; structural sharing does
not make every data-dependent retry schedule physically linear.

Batch known evidence before execution without claiming to predict arbitrary Handler dependencies.
Test 10/100/1000 discoveries and finite local/causal steps, plus N=1/10/100/1000/10000/100000 shared consumers.
Count total required reactions separately from source business execution and from physical retries.
Source commit must not require all parent payloads or application definitions.

maxMaterializedApplicationDefinitions limits one bounded read/discovery step, not total N consumers.
More than that cap proceeds through pages; it is not an excuse to reject ordinary fanout or freeze
a partial recipient set. Page retries/cold reopen must expose actual work rather than claim a warm
definition cache removed SQL, identity or WAL cost.

This does not authorize an incomplete or differently metered operation. One source operation at one
reaction position has one complete reaction per owned consumer scope and its eligible placements.
For each patch, visit canonical authored paths, finish one synchronous continuation, then revalidate
the next placement before updating it. The observation program supplies exact views and enqueue sites.
Keep unrelated consumers out of the proved local scope. Count P placements separately from N consumers;
an insufficient operational cap requires an explicit larger resource requirement/releaser, not endless
retry or splitting an atomic gas scope. A true portable limit remains a Contracts result.

New placements synchronously install canonical initialization or the selected initial view and
lifecycle effects. They do not join earlier frozen events. Historical epochs form a committed
fixed-cut attachment lane, retaining exact ownership through waits/restart; no same-epoch history
replay, fabricated caught-up parent field or creator self-wait. Rollback creates no new obligation.

For S → P → Root, reopen the required authenticated semantic observations, including intermediate
exact views where observable, original S/P event identity and route multiplicity. A flattened Parent
batch with only P-after is insufficient. Reopen the selected authenticated Entry/Patch/Enqueue/
Delivery/Exit program at exact causal continuation sites; recursively compose upstream observations
under the common FIFO. Vertex-simple per-route cycle handling does not globally deduplicate events
or aliases. The Phase1 codecs and constructors have actual cold conformance witnesses, not an opaque
EvidenceId shortcut. Share immutable exact values and
route prefixes where valid; do not copy every ancestor history. Measure evidence decode/hash/verify/
copy costs alongside source processing; reuse does not make logical verification free.

Required per-Order observations and reactions are real semantic work. Their total cost and backlog
may grow with N; the target is not seconds-scale completion independent of fanout size. Avoidable
full-history copies, repeated source business execution and global scans are different costs and
must be identified separately. Reuse shared immutable evidence where valid without removing required
observations or changing gas. Bounded caches, paging and fairness keep large fanout from exhausting
the host or monopolizing unrelated users' work.

## 12. Attempt and retry model

The concrete host states include `READY`, `LEASED`, `WAITING`, `PAUSED` and `COMMITTED`.
The first four are operational states. COMMITTED means one exact local
terminal semantic decision was atomically recorded, not that all related documents completed.
Cancellation ends/holds an attempt; it does not complete its logical operation or cause.

### 12.1 Evidence rounds

The implemented `CoordinationCore.evaluate` returns `NeedEvidence`, `Idle`, `MetadataProgress`,
`ManagedProgress`, `PreparedOperation` or `PreparedOperations`. Named needs retain their exact resource
demands; they are not semantic failures. Initialization and typed managed selection use the appropriate
single-operation/progress result. Normal external input returns dependency-ordered prepared groups,
even for a singleton. The owning interpreter determines that complete list; the host must not invent
a global `ClosureProcessResult` or use "one evaluation" to merge independent groups.

Retain each complete group and its verified plan. Do not execute a committed group again because
a later group's evidence is missing. The current `DurableHost.Complete` contains one plan; the
general adapter must durably retain the continuation between independent group commits. A lost
commit acknowledgement requires reconciliation before reevaluation, not blind whole-wrapper replay.

A pure metadata/no-applicable-history result creates no business invocation, gas or settlement for
that target and still requires complete routing authority. Target metadata may coexist with real
independent producer publications; it cannot discard those groups. A historical miss advances only
its justified target cursor. An empty physically visible subscriber page proves neither decision.

There is no exported `CoordinationCore.expandFanout` method. The concrete `PostgresDiscoveryStore`
performs bounded indexed acquisition and atomically registers obligations/page progress. Coordination
still proves semantic eligibility, grouping and completion ownership. Physical page progress creates
no semantic processor application or page-size-dependent publication event.

Canonical initialization uses the initialization work intent; `CanonicalSourceHistory.prepareNext`
builds retained history one justified record at a time. Neither library call commits a source lineage,
event or gas settlement. `Step.publications()` retains every required prepared group, while a target
metadata checkpoint preserves its prior semantic predecessor. Missing admission authority is not
provider outage; deterministic failed
initialization is not a resource demand. Separate source/parent meters are selected even when cold;
reuse cannot change their logical charge or convert physical preparation into a settled operation.

After lost page ACK, reconcile exact expected/next progress and created delivery keys under the same
metadata fence. Later scan progress alone does not prove the page was fully registered. Repeated
discovery never reruns source processing, creates another logical delivery or invents completion work.

Dependencies, not a universal all-managed-before-history phase or all-parents transaction, govern
readiness. Only genuine dependency edges block other work. A healthy Order can continue while a sibling
waits for payload or has a deterministic terminal failure.

### 12.2 Current NeedsResources semantics

Current Contracts exposes Complete or NeedsResources. NeedsResources contains no portable partial
business result, gas, queue, event trace, tentative mutation or reuse-map checkpoint. Re-enter the
whole local invocation from its start.

Availability-only evidence may preserve the closed input. Identity-bearing managed occurrence input
changes require recomputing the reviewed local input/InvocationId under stable logical application
identity. Use the verified owning constructors and exact authority; do not preserve unsupported
behavior through an alternate profile or hidden host wrapper.

No portable mid-invocation RecoveryProgress is introduced. Independent local commits occur only at
the new library-defined operation boundary, never at an arbitrary private delivery or gas position.

### 12.3 Level-triggered acquisition

Persisting `WAITING` plus a waiter does not alone prevent lost wakeups. Supply may precede registration,
or an acquirer may crash after storing the resource but before notification. Persist reverse dependency/
waiter keys with the work transition. Availability/progress changes use that index to select bounded
affected work; an indexed due-needs recovery worker rechecks current availability and requeues through
the exact work fence. It does not repeatedly scan every document, or every active document of a user.

Notifications only reduce latency. Stale waiters cannot reopen completed/superseded work. Persist
resource identity, ownership and retry scheduling; duplicate supply verifies the same exact bytes.
Terminal work closes its owned waiters atomically. Test supply-before/during/after registration,
duplicate notification, crash-after-supply, restart and concurrent terminal completion.

The same durability principle applies to source fanout: notification loss cannot erase the committed
delivery basis. Recovery resumes bounded indexed discovery without re-executing the source or replaying
already committed consumers. Indexed ready-work selection is separate from semantic eligibility:
Coordination still verifies chronology and prerequisites after the host selects a candidate.

### 12.4 Crash matrix

| Crash point | Durable truth | Recovery |
|---|---|---|
| Before current evidence is complete | Work and any needs | Acquire exact missing scoped facts |
| During a local Contracts invocation | No portable effect from that attempt | Re-enter the entire invocation |
| Complete before local commit | No winning application settlement | Recompute/commit under current scoped fences |
| During local commit | Unknown outcome | Work-fenced exact CommitKey reconciliation |
| Source committed before fanout scan | Receipt plus durable open fanout basis | Resume discovery; do not execute source again |
| Initialization prepared, parent not committed | Non-authoritative exact candidate only | Reuse/rebuild candidate; no initialized X or source outbox exists yet |
| Successful embedded creation commit uncertain | Parent and bounded created-source authority are atomic | Reconcile that exact transaction, including each source initial stream batch |
| Proved post-commit attachment continuation | Exact committed continuation authority | Resume only that work; do not invent a universal deferral for synchronous creation |
| Source/P committed before later observation | Result/receipt plus required semantic observations | Preserve exact observable views, order, original IDs and cursor scope; do not flatten without proof |
| Some Orders committed | Those exact local effects/cursors are authoritative | Continue remaining eligible branches |
| During delivery registration/page progress | Atomic registration or no effect | Idempotent exact page/work reconciliation |
| Source publish during registration/replay | One side of the fenced handoff owns the receipt | Resume its exact cursor; deduplicate overlap |
| Resource supplied before notification | Resource and durable need | Level-triggered recheck |

Finite supported computation, eventual evidence and fair recovery must yield the same settled
per-lineage history despite outage duration. This is conditional recovery, not a claim every feedback
program terminates or that slow branches never need to wait on real semantic dependencies.

### 12.5 Operational failure is a hold

UNAVAILABLE, unproved absence, retry expiry, capacity pressure, timeout and cancellation do not create
processor statuses or semantic terminal effects. Preserve the affected operation's local cursor,
required obligations and registration/reservation authority. Do not skip a source receipt, mark
unfinished state caught up, or allow a genuine dependent input to overtake.

The host persists capacity holds as `PAUSED` with a reason; repair and explicit `resumePaused`
release them. Quota replenishment only releases quota pauses. Reclaiming the same held work in an
immediate loop is not recovery. The hold is scoped: it does not automatically block Agreement and healthy Orders in a one-way fanout.
An admin-authored semantic skip/abort would need its own exact authorization and ordering law; it is
outside this POC.

### 12.6 Deterministic local failure and aggregate outcome

A terminal Contracts failure retains exact result/gas/diagnosis and local rollback. Operation kind
determines the permitted progress: original external inputs and their historical replay use the
frozen external-input consumption policy, without fabricating a successful epoch after failure.
A managed GAS_LIMIT_EXCEEDED or recognized semantic RUNTIME_FATAL import, live or historical, leaves
its successful cursors and entire local group's pre-state unchanged while recording terminal failure. Initialization
failure publishes no initialized lineage. These are different orders, not one status-controlled lineage cursor.

After terminal failed r1, r2 uses actual rollback state and authenticated continuous history plus
all lawfully consumed terminal dispositions, including exact composite external-policy failures.
Reject unhandled/nonterminal gaps and verify original kind, classification/policy and shared settlement.
Align consumer0→1 to r2's exact before-view with ordinary synchronous update/
lifecycle/gas effects, then interpret r2's1→2 program. An event-before-patch reads1; eventless r2 still
aligns and net-zero transitions remain observable. Failure anywhere in r2 rolls its full work back
to0. Alignment is not failed-event replay or a fabricated source receipt; ordinary successful
catch-up is not coalesced. Earlier source/sibling commits remain authoritative. The implemented
receipts and source-gap capabilities retain the successful observation per consumer/source pair;
co-owned consumers may have different successful pins to the same source. An ambient head or
optional cached read-pin list is not that authority.

Only gas exhaustion and recognized semantic runtime failures grant pure-managed failed-delivery
consumption. Other statuses retain their exact operation-specific laws. Reviewed exception
classification is an implemented owning-library boundary: unknown internal exceptions, IO/timeout/cancellation,
missing/invalid evidence and unresolved commits never become consumed semantic inputs. See22 §6.2.
Retirement respects per-event baseline-frozen local delivery validity, rather than blanket-dropping
later events or advancing every initial occurrence cursor despite rebind/removal.

Core gas failure is a terminal failed operation, not endless automatic retry or a permanent global
ban on processing. Apply the original input's failure disposition and allow later independently
eligible work. Failed managed r1 does not prove successful import continuity; the new consumption
operation must validate the distinct successful-view and terminal-delivery coordinates. D30 follows
earlier due deliveries only after their ordered terminal outcomes, not by cancelling them. See
[22](22-processing-kernel.md#6-import-failure-consume-one-operation-not-an-arbitrary-range) for the selected alignment and terminal-lane contract.
Feedback inside one core operation retains atomic scope/shared gas rather than fresh-budget jobs.

Verified representation-only companions recover with their actual triggering operation. Preserve
same business epochs/receipt positions and exact component proof, without synthetic business
receipt/event/fanout or gas reset. Finalization costs still count; mixed-cut scope without proof holds.

Whole-cause reporting is optional host/test bookkeeping, not a required global terminal receipt or
processing step. When enabled, it retains failed and unfinished branches plus open discovery. It
cannot report successful full completion merely because the source committed or no worker is runnable.
Durable local outcomes, pending obligations and no-gap discovery remain mandatory whether or not
that aggregate exists. A failed consumer does not force healthy siblings to fail or wait. No report
refresh creates a Contracts epoch/gas charge or grants semantic eligibility.

The exact completion owner retains each new failure/obligation. K100 can remain open after importing
an E10 receipt even though E10 itself is already closed. Replaying retained evidence for recovery
does not reopen an earlier cause or create a new semantic operation.

Keep historical read/activation laws under [22](22-processing-kernel.md)'s selected ownership:
directed dependency SCC at the logical cut, monotone canonical expansion on new return paths,
exact earlier predecessors, and no waiting for the same operation's uncommitted result. Missing
required scope/identity evidence holds before publication, not as a fabricated provider outage.
Reconciliation authenticates zero/one/many typed lineage projections under one commit receipt.
For dynamic joining,22 §2.1 includes pending same-origin producers, not only committed SCCs. Re-enter
with canonical joined authority, discard speculative wrappers and fence every superseded proposal.
Host quota pauses preserve this work; they neither change semantic gas nor enable later input to
overtake an endless earlier attachment lane. General semantic interruption is outside the first POC.

### 12.7 Hot-path performance

Keep JDBC pools, workers and bounded caches warm across local commits. Measure source commit,
per-consumer lag, fanout throughput/backlog and publication lag separately; report aggregate outcomes
when requested by the run. A slow or failed branch remains visible in those metrics while the source/
healthy branches demonstrate progress. Small healthy groups have a measured seconds-scale target;
100,000 independent reactions have no universal seconds-scale drain requirement.

Count source processor calls independently of N and report all physical retries. Total necessary
consumer work remains O(N). Bounded fanout/index/materialization and page progress must be measured;
neither source-side full-parent hydration nor hidden global scans are justified by a fast hot run.
Exercise millions of unrelated/dormant documents and thousands active per user. Check actual scoped
ready-work, reverse-waiter and temporal recipient query plans, rows examined and wake amplification.
Bound transactions, resident bytes, outstanding acquisitions and concurrency; test backpressure, fair
worker service and tenant isolation under sustained fanout. These may alter physical completion times,
never per-document semantic order, required outcomes or deterministic gas.

The actual smoke parent and fresh probes use the durable bootstrap's eight-connection Hikari cap
(test minimum-idle floor one). An earlier unpooled run failed in JDBC SSL negotiation after M2 had
committed; the failure report is retained separately. The final pooled seven cases pass without
relaxed assertions or semantic retries. That operational correction demonstrates why connections,
physical reads and logical gas must be reported separately; it is not a graph-throughput result.

### 12.8 Future continuation seam

A future portable Contracts continuation would need canonical private queues, tentative rollback
scope, gas/charge position and all runtime reuse/verification state. Persisting only gas/FIFO would
not suffice. No current POC record claims to implement such a format.
