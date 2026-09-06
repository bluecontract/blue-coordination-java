# Causal processing model

> **Status:** selected semantic contract; Phase1/2 handoff synchronized · **Design baseline:** 15.12
> [Independent lineages](18-independent-lineage-processing.md) · [API](05-poc-api.md) · [Catch-up](13-deterministic-catch-up-repair.md)

## What is decided, and what must be proved

[18 — Independent lineages](18-independent-lineage-processing.md) defines the source/consumer boundary.
Agreement commits once; ordinary observing Orders consume retained receipts independently. It
supersedes r14–r15.3's all-observer live invocation, immutable global application partition and
root-wide progress barrier. The inspected original libraries were the baseline to change,
not an obligation to preserve their whole-fanout rollback/gas/identity scope.

This chapter defines the algorithm's causal laws. [22](22-processing-kernel.md) has highest precedence
and selects grouping, visibility, lifecycle, feedback scope, canonical origin and failed-input alignment.
[24](24-phase-1-library-summary.md) records the implemented refinements and verified scope; the
reference sketch is not runtime authority. Phase1/2 are ready for integration, while the general
adapter and end-to-end validation remain [Phase3](25-phase-3-integration-plan.md) work.

## 1. The unit of semantic execution

One defined local operation has one exact cause, closed input, execution policy, complete environment,
InvocationId, ordered internal work/events, gas meter, rollback boundary and complete terminal result.
The host never divides that result into independently published partial steps. Instead, the owning
libraries must define source production and each ordinary consumer application as separate operations.

Coordination maintains ordered per-lineage histories and exact cross-lineage producer/consumer
dependencies. Independent siblings may commit in either physical order. A consumer failure does not
undo the source or successful siblings. Caches, retries and scheduling cannot alter their semantic
histories, identities, gas or causal order. Physical batches cannot select operation granularity.

Inline content stays inside its owner's operation. The owned workflow is the complete SCC at the
logical cut, expanded monotonically for newly created return dependencies; ordinary connectivity
does not place every acyclic observer in that scope.
Gas bounds internal work, not an arbitrarily long chain of new operations. Operational pauses retain
unfinished work and never invent semantic success, termination or gas failure.

## 2. Three different orders

| Order | Authority | What it decides |
|---|---|---|
| External causes | canonical microseconds, exact entry-BlueId text tie-break and complete provider frontier | relevant input order for each lineage |
| Lineage history and causal dependencies | exact local predecessor, producer receipts, occurrence cursors and complete dependency frontiers | eligible local operations; independent siblings need no total physical order |
| Local work/event occurrences | reviewed Contracts queues, event-time local topology and constructor ordinals | execution within the defined operation |

A timestamp does not prove that all earlier relevant dependency work has completed. A database
sequence, lease winner or provider name is not semantic order. A source's own eligibility can be
complete while a one-way observer remains behind; it need not await that observer's reaction.

Retained sourceOrder is original-event provenance, not a monotonic lineage clock. It may repeat or
decrease across successive receipts. Continuity uses DocumentId, retained position/epoch,
predecessor/current-representation evidence and receipt identity. The producing operation,
relationship history and causal position establish historical visibility separately.

Two controls are mandatory:

- K@100 attaches historical S to C. Importing S's E@10 receipt can produce a later C receipt with
  sourceOrder10. It must not appear in a cutoff50 view merely because the provenance is old.
- Initially authored B embeds C, reflects C's T10 event during reconstruction under cutoff40, and
  initially authored A later reconstructs under cutoff60. A's original T20 read must see B's
  reconstructed T10 result. Physical admission time cannot hide that history.

Neither a sourceOrder-only filter nor an admission-cutoff-only filter proves both. Reconstructing
target history is not permission to backdate new work in an already authoritative source.

Platform counters, gas, epochs, timestamps and ordinals keep their exact safe domains/sentinels.
Signed declaration order is different: -1,0,1 are valid ordered values. Do not round microseconds,
replace the exact canonical tie-break or treat all numeric fields as interchangeable.

## 3. Freeze local intent; prove dependencies and delivery coverage

Persist immutable input/admission intent, selected boundary, policies and exact local delivery
identities. Agreement commits its retained result plus durable discovery basis without precomputing
all observer applications. Per-occurrence lifecycle history determines which source receipts are due.

The host maintains transactionally consistent temporal subscription indexes and queries their
relevant ranges directly. A current-only subscriber table is insufficient, but rebuilding all
history per request is not the alternative. Source-ahead execution still requires explicit
activation/retirement and coverage boundaries plus a gap-free registration/history-to-live handoff.
Unknown membership keeps optional aggregate completion reporting open, not the source operation
tentative. Ready-work/reverse-wait indexes avoid platform-wide or tenant-wide polling; see06/18.

Prove each local input's required deliveries, exact read versions and synchronous scope before it
commits. This includes actual managed reads, rebinds, writes and required SCC evidence discovered
during evaluation. New one-way observers create their own obligations, not a merge of prior commits.
A genuinely later admission has its own replay; it does not retroactively reopen a proved live cut.

Current execution facts are coherent for this operation, not one global snapshot across all Orders.
A sibling commit cannot enter its InvocationId or force replanning solely by changing a host-global
revision. Genuine changes to its local predecessor, binding, authorization or dependency invalidate
the attempt. Re-read only the necessary scope; no stale-global-head retry loop.

Two Orders may attach the same exact Agreement without joining a shared atomic cohort. A new actual
feedback/shared-write or cyclic representation dependency must be detected and handled under a
reviewed rule. Do not silently publish an undefined scope or fabricate a Contracts failure.

An operation cannot overtake earlier relevant input or unmet dependencies. Admission reserves its
affected lifecycle/history scope, not all observers. In the one-way case Agreement can advance while
an Order waits; returning feedback requires a separate completeness rule before source advancement.

Processing completeness and delivery/discovery completeness are separate. Discovery covers the
accepted admission/reservation scope, including not-yet-materialized lifecycle decisions; it is not
proved by the current occurrence table. [18](18-independent-lineage-processing.md#4-delivery-completeness-and-lifecycle)
defines the incremental lifecycle frontier and scoped retained-tail/live-registration handoff.
Neither global recipient discovery nor whole-cause completion gates a correctly eligible source.

Completion ownership is also separate from provenance. E10 already closed and then imported by K100
does not reopen E10; newly generated work belongs to K100. Input-derived semantic transition identity
must precede a receipt that references it, never hash itself recursively. See [19](19-semantic-edge-contracts.md#4-provenance-does-not-own-completion).

## 4. FULL_HISTORY means chronological observation

For the supported FULL_HISTORY reconstruction, initially embedded sources must be observed at the
correct historical point:

- S initially has count 0.
- S changes to 1 at t10.
- P's event at t20 copies its embedded S count into observed.
- S changes to 2 at t30.
- P is admitted under cutoff C40.

Required: P records observed=1. Its subsequently settled embedded state may be 2. Importing S through
C40 before replaying P's t20 event is incorrect for this policy.

Direct reads and event-derived parent values are separate observables. In the A/B fixture, assert
the value read through `/child/counter` from the historical exact binding, independently of A's
`/counterB` populated by an Embedded Event handler. A shadow field can be correct while the resolver
exposes a future child. Include a child-state-only change without the matching shadow-update event.
Each observation has its own oracle; the shadow is not required to equal the child without that event.
Also compare the complete ordered original A entry identities and per-entry dispositions/counts:
all applicable entries receive exactly their reference-defined applications/deliveries, and skipped
candidates have explicit no-delivery cursor decisions. One correct read or final assignment cannot
hide a missing T7 entry, reordered original causes or duplicate application. Keep managed imports
distinct from original Timeline applications in this trace.

The initial admission/reconstruction history therefore uses a chronological replay cursor over exact
relevant original causes and their source receipts. It does not run every managed receipt in the
entire admission window before the next parent event. No receipt attributable to a later original
cause may become observable early merely because its bytes/current source head are available.

For one original cause targeting both source and parent, timestamp merging alone is insufficient:
direct seed grouping, receipt reuse, internal ordering, gas and parent multiplicity must match the
reference. One inherited reaction origin/position × owned scope composes all converging producer
projections and the target's own direct seed in one operation. Complete due placements and per-route
cursors are required, but page boundaries cannot choose scope. Do not impose all-pins-first or
preload-all-events: they can hide intermediate updates or reorder generated events.
A new placement does not join the already frozen original direct recipient set; its initialization
and installed-view observations are synchronous. Selected historical epochs use a separate ordered
attachment lane; a creator cannot await its own commit. See [22](22-processing-kernel.md) and EQ1–EQ8.

A historical attachment introduced later at cause T is different. Its declared historical selection
establishes what must be imported for that occurrence before a later dependent cause can run. It must
not be silently interpreted as an initially embedded FULL_HISTORY relationship. FROM_FRONTIER and
FROM_NOW retain their explicitly chosen boundaries; none means "whatever head the worker sees."

For a concrete pair, B emits `CounterChanged(5)` at T5 and A has a matching handler assigning
`counterB`. If A attaches B at T10 and imports that event, A's counter is unset before T10 and becomes
5 as part of the attachment's causal catch-up. If A attaches at T1, its counter becomes 5 in response
to B's T5 event through the existing relationship. Equal final values do not make those histories
equal. The imported event retains its T5 provenance; A's T10 attachment does not create an A reaction
back at T5. T10 is a causal attachment position, not a new managed-cause timestamp or a promise of
one atomic catch-up invocation. Running reconstruction physically later must preserve whichever
attachment history was authored, not replace T1 with the worker's current time.

The historical read may occur while the occurrence remains inactive/pending against a newer
authoritative B head. Eligibility must prove that exact per-use historical view without setting
global READY or allowing unrelated live work. Current Contracts retains historical exact read
bindings, while Coordination's ordinary feeder has a blanket CATCHING_UP-member gate; a planned
scope-specific replay correction is required. Neither fact proves arbitrary mutation support.

The intended first scope includes ordinary same-scope removal and retargeting, not read-only replay.
With B@10 imported and B@30 retained but not yet due, let A's original E20 remove `/child` and its
effective declaration, or retarget the path to exact C. E20 must not import future B@30 to make
activation convenient. Its successful terminal delta must retire the old activation and not-yet-due
old-occurrence historical suffix, preserve prior imports and B's source receipts/other consumers, prevent
old queued B deliveries from targeting the removed/replaced occurrence, and establish C's exact new
binding/activation and E20-selected historical basis where applicable. Reattachment cannot revive
retired work by reusing a path. Failure publishes none of that tentative lifecycle change.
Retirement does not cancel work or event-time frozen deliveries validly owned by the current
Contracts invocation; its complete queue, routing, gas and rollback rules remain authoritative.

Current Contracts explicitly restricts external retargeting of pending historical values; the
[source-backed distinction and planned correction](13-deterministic-catch-up-repair.md#historical-reads-removal-and-retargeting-while-catch-up-is-pending)
are not a newly executed failure or implemented fix. Removal's retained-row/plan behavior still
needs its conformance trace. Retirement/rebind constructors must implement22's cut, frozen-work and
complete old/new obligation laws. Do not replace a genuine Contracts failure with fabricated success.
Ordinary sharing does not merge independent observers; actual return paths expand the owned SCC
before publication. Earlier independent commits remain authoritative.

## 5. Initial historical basis versus continuing source progress

The immutable selection basis records occurrence/activation, declared history policy, admitted source
position, accepted boundary and exact provenance. Continuing cursor progress is separate. Ordinary
live lag does not create a new attachment or change its original semantic position.

The source may advance independently in the one-way case. Consumers select only the contiguous
receipts due at their own chronological position; an authentic available future receipt is not yet
eligible. The source retains old versions instead of forcing every consumer to its latest head.
Caught-up-to-T is a proved prefix, not equality with an indefinitely moving current source epoch.

A consumer's genuine outgoing event may create new source work. Under the revised boundary, this is
an explicit producer/receiver dependency, not an implicit mutation inside the old-receipt import.
It cannot be inserted into already committed source history. Competing feedback versus later direct
source input needs a complete causal frontier and canonical ordering independent of workers.

The reviewed target-extension/activation rule must preserve the original selection, producer evidence,
receipt continuity, every required event and final/nonfinal feedback. Current Contracts' coupled
activation/source-change behavior is a source fact to reconcile, not the new independent algorithm.
Same-root provenance alone neither proves eligibility nor licenses ambient-future reads.

Supported eventless receipts retain full owning application/result/companion/state/gas evidence.
A before==after wrapper or latest snapshot cannot substitute for an authenticated historical operation.

Nested observation must preserve original provenance, frozen ancestor paths and actual admission
order. Current ordinary PROCESS and managed immediate-binding routing need conformance repair.
A Parent-result relay and final Parent-after state alone are not a proved representation. Retain
the minimum authenticated observable views/updates/admission evidence required by the logical trace;
do not duplicate original delivery or mistake it for a new Parent emission.

## 6. Normative next-action law

A selection certificate explains a derivation; it is not a host attestation or a hash-based proof
of completeness. Trusted queries are audited independently against authored state and retained history.

For each candidate target operation, derive and verify:

1. Immutable intent, target-local predecessor, occurrence cursor and exact causal producers.
2. Complete next-candidate evidence for relevant original inputs and due source consequences.
3. Which prerequisites are due here; a later source receipt must not jump a past parent event.
4. Exact historical read/scope/initialization eligibility. Every wait names its actual releaser.
5. A canonical next local action or its honest hold/failure. Independent targets can be eligible
   together; competing noncommuting target actions cannot be ordered by claims or commit timing.
6. A terminal delta consuming the action once, preserving local continuity, accounting for exact
   cursor/lifecycle changes and adding every required outgoing obligation without losing siblings.
7. Whole-cause completion only with complete lifecycle/discovery coverage and all required outcomes.
   This aggregate is not a transaction or a barrier around independent local publication.

For the same-cause source+parent case, [18](18-independent-lineage-processing.md) requires an explicit
mapping of the reference's direct-seed and caused-work/drain order. Source production, receipt import
and parent direct input are not a universally selected three-phase sequence. Independently derive
grouping, multiplicity, constructors and gas; physical batching cannot reorder the logical trace.

Successor authority differs from successor payload. A complete current operation may commit its
proved cursor/obligation and exact successor references while execution-only successor bytes are
unavailable. Missing evidence needed to prove the current result or obligation projection still
blocks it. No unowned speculative planner or private mid-invocation continuation is introduced.

Certificates and progress are bounded deltas over immutable definitions. Do not repeatedly embed
the entire global work graph or prefix. Necessary larger coverage must be explicit and measured.

## 7. Scope, locality and host authority

The revised local operation rules determine required content, routing evidence and any coupled cyclic
finalization. Ordinary observing Orders are consumers, not members of Agreement's invocation.
Complete necessary SCC evidence is retained without making every ancestor globally atomic.

Prospective initialization uses22's canonical source origin/FULL_HISTORY and source-local gas policy.
FROM_NOW/FROM_FRONTIER select observer history, an explicit change from current source-birth policy.
Candidate promotion requires successful authorization and is idempotent; failed creators publish no
new source authority/events/charges and cannot erase existing independently admitted sources.
Independent source and consumer have distinct ledgers cold and warm. Eviction is not rebirth, and
the first INSERT cannot choose history or gas.
See [19](19-semantic-edge-contracts.md#1-initialization-is-not-a-cache-miss).
Outside-config Timeline subscription is an explicit supported-scope hold, never a skipped
completeness source. Demand-discovered actual dependencies cannot be ignored as an optimization.

Each mutable semantic writer participates in target/occurrence/index fences. A short host commit
mutex is possible, but not a root-wide eligibility gate or portable predecessor. Unrelated sibling
writes must not repeatedly restart source processing. No lock spans execution or resource waits.

One terminal transaction records the complete local result, settlement, state/cursor/obligation
delta, per-stream outbox append and commit receipt. Publication preserves the stream and causal
order, not a global physical commit sequence. Durable level-triggered needs survive lost wakeups.

Consumed external input, business epoch, successful managed-import cursor and representation-only
rebind are separate projections. Selected managed gas/recognized-runtime-failure continuation leaves r1's successful
cursor/state unchanged but terminally accounts its delivery. Subsequent r2 verifies actual consumer
pre-state, original contiguous source history and all lawfully consumed intervening dispositions,
including composite external-policy failures. Operational errors never supply such evidence. It first
aligns the actual pin0→r2.before1 with metered synchronous update reactions, then interprets r2's
original program1→2. No r1 event replay or fabricated source receipt. Alignment can fail too;
if both imports exceed gas, later D30 can detach after their terminal outcomes without overtaking them.
Existing external loop/detach remains a separate positive control. The new managed constructor and
conformance are still planned library work, not a host-only relaxation. See [19](19-semantic-edge-contracts.md#2-failure-has-several-independent-progress-coordinates).

For initialization, the same canonical source result can be published by standalone admission or
successful creator promotion without changing its origin, events or gas. Parent attachment remains
a distinct logical operation. Reuse cannot introduce cache-dependent gas, missing events or rebirth.
Successful source cursor, handled-input frontier and business epoch remain distinct; failed-import
next-step continuity must allow the required repair without pretending failed state committed.
See [21](21-semantic-equivalence-and-source-reuse.md). No implementation is authorized by this plan.

## 8. Mandatory implementation-spike traces

| Trace | Required proof |
|---|---|
| Initial FULL_HISTORY S@10, Pread@20, S@30 | observed=1; later source state cannot leak backward |
| Direct historical child read versus parent shadow | exact `/child/counter` observation separately from event-derived `/counterB`; state-only child change cannot produce a false positive |
| Complete original A history | exact ordered entry identities and application/delivery counts, including T7; explicit cursor-only non-applicable entries and separately identified managed imports |
| Same original cause targets S and P | independently derived grouping, one source computation, exact parent multiplicity/order/gas |
| Attachment created at T versus initial embedding | distinct declared semantics and correct historical observation |
| A attaches B at T10 versus T1; B emits 5 at T5 | same final counter, different exact A histories; T5 provenance never backdates the T10 attachment reaction |
| E20 removes or retargets pending B before retained B@30 | no future import to activate; exact retirement, old-suffix ownership and new occurrence/basis; no stale queued delivery or resurrection |
| Final-step source feedback | activation plus new source receipt/event preserved |
| Nonfinal-step source feedback | no stuck old target, receipt/event loss or ambient frontier import |
| R1 attachment, later R2 source application, sibling consumers | deterministic legal trace; no phase/gate deadlock |
| Disjoint sibling topology changes | local identities/history unchanged; no stale global fence retry loop |
| New shared authored child / existing READY X join | one lineage, independent observers; detect real feedback/cyclic requirements rather than reject ordinary sharing |
| Equal/decreasing source provenance | two E-origin imports remain distinct; K@100-produced E@10 effect is absent at cutoff 50 |
| Nested initial FULL_HISTORY reconstructed later | C@10 is reflected in reconstructed B and A's T20 read despite later admissions; pair with the K100 negative and distinguish new authoritative source WORK |
| Successor body unavailable | M1 commits exact selection authority; M2 suspends; body mismatch rejects; missing necessary selection proof still holds |
| Signed declaration order | -1,0,1 survive noncommutative routing/codec/PG replay; invalid negative platform counters reject |
| Authenticated eventless transitive replay | exact current application/result evidence accepted; forged absence rejected |
| Looping workflow versus separate future operations | one logical feedback workflow reaches its shared gas/limit outcome; only genuinely separate future operations use the operational-pause control |
| Live shared Agreement fanout | source commits before observers; independent local results/cursors/gas; no parent-body dependency in source execution |
| Source ahead, observer delayed | T10=1/T20=2 and Order T15 reads1; preserve original attachment time |
| Source commit, then crash after17/1000 Orders | retain completed steps and every pending discovery/delivery obligation |
| One failing/unavailable Order | source and independent siblings progress; real dependents block; no false whole-cause success |
| Stale lifecycle index and registration race | no retired-edge delivery or missed attachment; complete coverage before settlement |
| Reversed independent commits | identical per-lineage history, event identities and gas; no global physical-order identity input |
| Warm/cold/rebuilt/concurrent initialization | same fixed authorized input/history preserves initialization identity/gas and observations; distinguish prior admission from materialization; no orphan or first-winner source history |
| External failure/repair versus failed receipt r1 | baseline detach/new-input recovery; selected r2 continuation from actual rollback state and terminal outcomes, no r1 replay or invented successful cursor |
| EQ1–EQ5 observation and routing | triggered [1,2] versus buffered [2,2]; per-patch updates; FIFO admission; original ancestor delivery and historical T15 read1 |
| Closed E10, later K100 import emits F | E10 stays closed; K100 owns new work and F keeps exact provenance |
| Passive cyclic reference update | same-epoch representation finalization, no synthetic business-receipt echo |

These are planned requirements, not new passing tests. Review the algorithm questions in
[18](18-independent-lineage-processing.md) before implementation approval. Afterwards, hand-derived
traces, actual owning-library tests and eager/lazy runs of the same revised model verify them.
The existing r15.2 experiment is unchanged and does not prove independent live fanout.
