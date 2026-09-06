# Minimal POC API contract

> **Status:** IMPLEMENTED LOCAL POC BOUNDARY + HOST INTEGRATION REQUIREMENTS · **Revision:** 15.12, synchronized 2026-09-06
>
> [Architecture](04-target-architecture.md) · [Rationale](05-api-contract.md) · [Implementation summary](24-phase-1-library-summary.md) · [Verification](implementation/phase-1-2-readiness.md) · [Java reference](reference-api/00-poc-minimal-boundary.java)

## Status and intended ownership

[22 — Selected processing kernel](22-processing-kernel.md) defines operation ownership, observation
program interpretation, placement/routing order, canonical initialization and gap alignment. It
supersedes earlier candidates; a Java usage example is not implementation or conformance proof.

Phase1 libraries are implemented in `worktrees/coordination-external-state`; Phase2 host primitives
and durable witnesses are in `../myos-simple` on `feat/coordination-with-external-state`. Both are
locally verified; see the linked handoff and verification record. They are ready for the Phase3 integration described in
[25](25-phase-3-integration-plan.md), not released, production-ready, API-frozen, or a formal pass of
every acceptance-catalog scenario. The concrete surface below is the implemented API. Later host
protocol sections preserve design requirements; their capitalized conceptual names are not claims
that corresponding Java classes or methods exist. One evolving local stack remains authoritative.

[18](18-independent-lineage-processing.md) defines the ordinary managed-embedding model. Agreement
commits one local source transition; its Orders independently consume that retained transition.
One failing or unavailable Order does not roll back Agreement or stop independent healthy Orders.
Any aggregate completion/error report remains honest about every required consumer; such a report
is optional host/test bookkeeping, not a mandatory global terminal receipt or a source-progress gate.

This changes the Contracts ownership boundary. It is not a host optimization over an
unchanged invocation that privately includes Agreement and all Orders. The library must construct
the correct local invocation and exact read pins. The host never slices a private Contracts queue,
commits partial invocation effects, or shares one gas ledger across independent consumer calls.

## Core interface

The implemented class is `blue.coordination.external.CoordinationCore`. Its constructor is
`CoordinationCore(DocumentProcessor, ClosureEnvironment, ExecutionPolicy)`: there is no public
`SemanticConfiguration` constructor or getter. Public roots and the selected scope arrive in
`EvaluationEvidence.snapshot()`, not a fourth constructor argument. The core retains no lineage
state, cursor, receipt store or publication authority.

The public entry points all return `CoordinationCore.EvaluationResult`:

| Entry point | Implemented purpose |
|---|---|
| `evaluate(WorkIntent, EvaluationEvidence)` | Canonical `INITIALIZATION` or next `EXTERNAL_INPUT`; the default attachment policy is empty. |
| `evaluate(WorkIntent, EvaluationEvidence, SameOriginAttachmentPolicy)` | External evaluation with explicit per-occurrence FULL_HISTORY/FROM_NOW/FROM_FRONTIER creation choices. Nonempty choices require an external origin. |
| `evaluate(ManagedImportSelection, EvaluationEvidence)` | One authenticated producing reaction position for the exact due occurrence lanes. It does not advance the consumer's external Timeline cursor. |

`WorkIntent` contains a Language closure `DocumentId` and `OperationKind`. The enum has exactly
`INITIALIZATION`, `EXTERNAL_INPUT`, and `MANAGED_RECEIPT_IMPORT`; a bare managed work intent returns
a named selection need. It does not replace `ManagedImportSelection`. Preparing source history uses
the same external-input kind, not a separate `HISTORICAL_EXTERNAL_REPLAY` enum.

| Actual result variant | Meaning and publication boundary |
|---|---|
| `NeedEvidence(keys, resourceDemands)` | Named selection/resource needs. Interpreter demands retain their actual `ClosureResourceDemand` values; not every selection key has a typed demand. No partial proposal, tentative state, queue or gas settlement is returned. |
| `Idle` | No selected work at the supplied cut, or initialization already established. It is not a global fan-out completion certificate. |
| `MetadataProgress` | Exact handled-input bookkeeping without a target processor invocation, business epoch or output stream; carries its input, fences and source-operation dependencies. |
| `ManagedProgress` | Exact failed-source lane accounting with no observer business invocation and no external Timeline advance. |
| `PreparedOperation` | One complete initialization or managed-reaction proposal, including exact disposition, result, projections, dependencies and, where applicable, lane deltas. |
| `PreparedOperations` | Dependency-first `PreparedGroupOperation` values from a complete same-origin evaluation, plus optional `targetProgress()` when the requested lineage has only metadata progress. |

One evaluator call can produce several independently atomic groups. `PreparedOperations` is not
one aggregate transaction and is not a partial-result escape hatch: an incomplete attempt returns
`NeedEvidence` instead. Each group's `result()` is a real `SameOriginOperationResult`; its exact
owned projection set and consumed source-operation list are validated. The host must retain every
group, publish prerequisites before dependents, and commit all members of each group atomically.
It must not manufacture a whole-closure result, discard a failed prerequisite, or merge meters.
After receiving a prepared result the host does not call Contracts again to interpret it.

### Exact evidence and chronology

`EvaluationEvidence` contains the snapshot, exact relevant Timeline set and prefixes, optional
`handledThrough`, predecessor operation IDs, retained source programs/failures/gaps, offered
initializations/frontier selections, and host fences. Its additive helpers are
`withOperationFences`, `withSourceInitializations`, and `withSourceFrontiers`.

`TimelineInput.timelineId()` is the exact **string locator** stored at
`/timeline/timelineId` of a typed `MyOS/Timeline` inside the typed `Coordination/Timeline Entry`.
It is not the Timeline object's BlueId. `entry` and `event` must identify the same complete exact
Timeline Entry, including actor/message/source; passing only the message payload is invalid.
The timestamp must agree with that entry and satisfy `0 < micros < 2^53-1`.
`TimelineInput.order()` is `[timestampMicros, entry.blueId()]`, with canonical entry-ID text ties.
Every relevant prefix must be complete **strictly after** the selected timestamp. Core derives and
checks the complete directed live Timeline membership from authenticated root-channel metadata;
the supplied set is not permission for the host to omit a quiet dependency or include reverse Orders.

`operationFences` attributes mutable CAS authority to each owned lineage. A group uses only its
owners' fence union. An already consumed source is an immutable operation/receipt dependency, not a
requirement that its mutable head still equal an old snapshot. Thus B can commit before A without
making A stale merely through B's new head. Conflicting revisions for the same exact fence key are
rejected. Legacy undifferentiated fences are not silently divided by guessing keys; missing group
attribution returns a named `group-read-fences` need.

### Source preparation, attachment and import

There is no Core `prepareInitialization`, `prepareSourcePrefixStep`, or `expandFanout` method.
The implemented composition is:

- Initialization: evaluate an `INITIALIZATION` work intent; a successful retained program yields
  `SourceInitialization.fromProgram(...)`. Merely preparing or encoding it is not publication.
- Source prefix: `CanonicalSourceHistory.start`, `prepareNext`, and `resume` retain bounded
  canonical preparation steps. Its results are `Await`, `Step`, `Complete(Boundary)`, or `Blocked`.
  A `Step` retains all fresh prerequisite/group publications; it is not necessarily one operation.
- Attachment: `ObserverAttachmentPlan.select` and `SourceFrontierSelection.fromBoundary` bind
  selected history and a closed canonical boundary. `SourceFrontierView` alone is an exact view,
  not completeness authority. Source initialization/attachment capabilities are activated at their
  actual creation sites, not merely because they appear in the evidence inventory.
- Continued import: `ManagedImportLane` descriptors/cursors/headers/due records and
  `ManagedImportSelection` identify due lanes. Accepted initialization/frontier lane factories
  require actual successful creator evidence and a surviving occurrence; create-then-retire has
  no surviving lane.
- Cold evidence: `OperationReceiptCodec` encodes a prepared operation or group and restores exact
  state, source program/initialization/failure, per-consumer source gap, managed lanes, gas and
  effects. `ManagedProgressReceiptCodec` represents its distinct metadata-only lane outcome.

`SourceObservationProgram` is the actual retained action program: steps, patches with resulting
bindings, enqueues, reference projections, borrowed programs and accepted creation evidence.
Termination requests and authenticated already-accepted work cutoffs are also retained; skipped
work is not a fake executed step. The conceptual Entry/Patch/Enqueue/Delivery/Exit vocabulary below
describes interpreter boundaries, not a released `ObservationFrame` wire DTO.

### Host protocol, not an additional library API

Names such as `DurableFanoutBasis`, `HostWorkSelectionPort`, `CreationPublicationCompanion`,
`CommitReceipt`, `CommitUnknown`, and `RecoveryPort` in the following design discussion denote
host responsibilities. The old Java-shaped versions are not shipped public interfaces. Phase2
implemented indexed PostgreSQL storage, ready/reverse-wait primitives and bounded persistence/recovery
witnesses. General graph-to-store mapping, application work/fan-out pumps and integrated scale
measurements remain Phase3 integration work; this is not an absence of those Phase2 primitives.
Their requirements remain: no all-document scan as the normal scheduler, no SQL page ordering as
semantic ordering, no lost registration wakeup, exact dependency-first publication, and no portable
serialization of a private interpreter continuation.

The implemented progress split is explicit: external handled position, successful state epoch,
terminal source-operation continuity, per-consumer observed source view and managed lane cursor are
not interchangeable. The current core uses fixed consumed/blocked laws; it does not expose the
sketch's configurable `FrozenExternalInputDispositionPolicy` table.

### Sparse reads and the current lazy boundary

`AffectedClosureSnapshot.retainResidentBodies(...)` can retain verified immutable member/component
authority without resident application bodies; `withResidentBody(ManagedReadPin)` hydrates the exact
authorized value. `ReusableComponentAuthority`, `RootChannelMetadata` and their codecs preserve
unchanged component/root-channel facts. A self-hashed host header or a placeholder Node is not an
equivalent capability. An actual read, owned execution or newly owned SCC promotion must acquire
the bodies/proofs it needs, with named noncommitting evidence demands when absent.

The complete authenticated **directed metadata/read cut** is still required. Sparse bodies do not
permit omitting transitive topology, quiet Timeline membership, lifecycle markers or exact selected
pins. Nor may a physical later source head replace the logical prior view: if its topology, channel
surface or markers cannot justify that cut, Core asks for the logical view before selecting work.
Unrelated reverse-only observers are not included just because they share the source.

Source programs and their borrowed DAG are currently decoded/materialized within physical codec
budgets; this is not a fully streaming action interpreter. Capture-time peak memory remains a
measurement obligation, not something byte limits automatically solve. Complete M1 may publish
while an execution-only M2 body is unavailable, but a fact needed to select or finish M1 still holds
M1. Retry re-enters a complete evaluation; no private mid-invocation continuation is portable.

## Local application and identity

Execution seeds are pre-execution authority: exact kind/cause, lineage/predecessor, initial owned
scope, selected occurrence/history inputs and environment/policy. They do not require an unknown
final group identity to begin. `SameOriginOperationResult.originalSeedByMember()` and its
`SameOriginGroupEvidence` retain the distinction between original seeds and completed group
authority. The old `FrozenExecutionSeed`, `FrozenCauseApplication` and `SelectedAction` names were
conceptual sketch types, not additional public constructors. There is no global application ordinal,
all-parent partition or globally committed prefix.
Pending host dependency references use stable intent/action identity, not an unavailable final
operation identity. An authenticated completed producer can be referenced separately. Discovery
must not freeze a predecessor/seed before the target work becomes canonically eligible.

For dynamic same-origin work, stable execution seeds are fixed before evaluation. Each emitted
occurrence binds its seed, lineage and local emission site; a later accepted join cannot rename it.
Final operation/settlement authority binds the selected seeds/predecessors and accepted admissions.
It is a completed wrapper, not an earlier business-code input or a circular admission dependency.
Discard provisional publication wrappers on replan, not canonical seed-local event identities.
Availability-only retries preserve IDs. Scope derivation excludes arbitrary speculative footprints;
final members are not replayed from instruction one under retroactively shared gas.

Completion ownership separately identifies who must account for the operation and its new consequences;
the sketch's `CompletionOwnerId` is not a public Core type.
Provenance is not that ownership: a K100 attachment importing retained E10 keeps E10's source identity
and order, but newly generated work belongs to K100. E10 does not reopen. Neither discovery time nor
the worker that found the receipt chooses the completion owner.

The semantic predecessor is the lineage's exact authored initial state or prior authoritative
transition. Current SQL revision, unrelated source/Order commit, lease generation, worker and page
boundary are not identity inputs. Reversing two independent Order commits must leave their semantic
receipts, InvocationIds and histories unchanged.

Logical identity constructors belong to Contracts/Coordination, not the host. Their selected
preimages are the operation kind, exact semantic cause, owning lineage or complete coupled scope,
exact semantic predecessor(s), selected occurrence/activation and history basis where applicable,
and frozen semantic environment/execution policy. Physical storage cuts, receipt page numbers,
workers and the identity of the parent that first materialized a source are excluded. Current
closure constructors now use the owning operation's semantic authority instead of unrelated mutable
inventory. Ignoring an incorrect identity preimage in a host wrapper would not be sufficient.

The constructor dependency is acyclic: stable execution seeds precede seed-local occurrences and
admission prefixes; accepted admissions determine final operation authority and closed InvocationId;
the completed transition/receipt wrapper is derived afterwards. Final
group/InvocationIds cannot determine earlier event IDs or be observed by running business code.
A source event occurrence uses its stable execution seed, source lineage, deterministic local causal
emission site/ordinal and exact event BlueId. Receiver delivery identities also bind occurrence/activation and
logical route. Replaying a producer event preserves its occurrence identity; a handler re-emission
gets a new emission site even for equal payload. These are constructor preimages, not invented
released type BlueIds. Availability-only retries and physical reuse preserve them.

DocumentId remains the initial authored exact BlueId, including current cyclic rules. Identical
authored documents share a lineage in this MyOS path; work IDs do not allocate another document.

### Selected source origin, initialization and observer attachment

One authored DocumentId has one canonical source origin under the frozen semantic environment.
Its initialization precedes its chronological source history; that history uses FULL_HISTORY over
the source's authored Timelines and their subsequent legitimate topology changes. Materialization
at T100 cannot remove an applicable source input at T15. Source history does not begin when the first
Order attaches, a worker finds a missing row, or an initialization candidate is published.

The implemented external-state path separates source origin from the older SDK's
FROM_NOW/BIRTH_AT_ATTACHMENT composition. FULL_HISTORY, FROM_FRONTIER and FROM_NOW select an **observer's attachment history**,
not alternative source births. FROM_NOW is normalized to an explicit accepted logical attachment
cut; FROM_FRONTIER carries its explicit cut. The selection determines which prior source
observations that occurrence imports and its initial authorized source view. It never truncates or
reinitializes the shared source. Different Orders may select different observation histories while
sharing the same authoritative source history. Use the actual attachment plan/selection and accepted
installation factories; the host must not silently reinterpret an older SDK enum conversion.

Canonical initialization has no ambient parent, introducing parent's processing event, attachment
time or cache state. Its constructor preimage is the initialization kind, authored DocumentId/exact
authored value, frozen semantic environment and sourceInitializationExecutionPolicy. Where a real
initialization cycle requires a coupled scope, use the canonical complete scope and its exact
authored members under the same shared-workflow rule, not the first requesting parent as owner.
Initialization establishes the canonical initialized position; subsequent source operations have
their ordinary exact causes/predecessors. The first publisher cannot change those identities.

Initialization is independently metered for a genuinely independent source. The creator/importer
pays its own verification, reference-update and reaction work; it does not inherit the source's
initialization meter. For example, independent source initialization costing80 and parent work20
under their respective limits90 both succeed, cold or warm. This is an explicit change from one
combined invocation costing100; it is not a warm-cache discount. Real coupled workflows retain one
shared limit and cannot obtain this split. Each authoritative source operation retains its own gas
once. Importers do not charge that source execution again, and physical reconstruction creates no
new logical operation or charge.

`SourceInitialization` and any `CanonicalSourceHistory` prepared prefix are non-authoritative
until an authorized publication. A successful introducing operation may atomically publish its own
result and promote the exact required candidates through the host's conceptual creation-publication
companion, not a Core class named `CreationPublicationCompanion`. Promotion
installs the canonical source results, source-owned gas records/batches and registration/work basis
under scoped absence/history authority. It is idempotent under the canonical source operation
identities, not owned semantically by the winning parent. A compatible already-authoritative prefix
is reused; a conflicting prefix is rejected as incompatible evidence, never overwritten.

If that introducing operation fails, none of its newly prepared source authority, source events or
initialization charges become authoritative. The creator's own failure accounting is retained
normally; candidate computation is physical work. A later successful creator promotes the same
canonical origin/history, independent of which earlier attempt failed. An already independently
admitted source and its charges remain untouched. Standalone admission can authorize the same
canonical publication without a parent. Missing reconstructible bytes are not missing authority;
eviction, restart and reconstruction never authorize another birth or charge. These rules concern
semantic accounting, not a choice of billing account or a requirement to put all observers into one
transaction.

All eleven ClosureEnvironment dependencies and all 33 portable limits remain explicit. Scalar
validation stays field-specific: nonnegative platform fields retain their exact safe ranges and
sentinels, while signed declaration order -1,0,1 and authored Blue Integer content keep their domains.

## Source commit and durable fanout

This section specifies the host integration protocol. `DurableFanoutBasis`, registration handoff,
outbox and commit-receipt names are conceptual responsibilities, not Core methods or shipped DTOs.
Phase3 must connect them to the full application; focused Phase2 persistence witnesses are not a
claim that the complete production-shaped scheduler and fan-out service already exist.

A successful local source invocation atomically publishes:

- its exact local state/result, full gas and create-only application settlement;
- the authenticated retained source receipt, event identities and required semantic-observation evidence;
- a DurableFanoutBasis naming that receipt, temporal subscription query and retention authority;
- local dependency/accounting deltas, outbox batch and CommitReceipt.

It does not need all consumer payloads or a complete vector of N consumer applications. A fanout
basis is durable discoverable work. Transactionally maintained temporal indexes answer the exact
historical query directly; they are not a disposable current-only cache that forces history replay
on each request. Source-ahead execution may precede a consumer's historical
attachment/removal processing. Retain exact source history until temporal membership and replay/
registration coverage can prove the required deliveries have been accounted for.

Topology changes, corresponding index projections and required work/wake basis publish atomically.
Discovery uses bounded source/range lookups with explicit completeness, not a full history scan.
The source transaction stores a resumable basis without synchronously creating N consumer jobs.

The fanout scan uses bounded pages and stable per-occurrence delivery identities. Page progress
and unique obligation creation commit atomically. Physical pages may be retried, split or reordered
without changing delivery identity or semantic order. A materialization cap bounds one page/read,
not total N: cap+1 consumers continue through additional pages instead of holding the source.

Discovery records immutable delivery ownership (receipt, consumer/occurrence, logical reaction
position and completion owner), not an
arbitrary current consumer head. Derive the full predecessor-bound local application only when its
canonical turn is eligible. Otherwise queuing M2 before M1 commits would freeze a stale predecessor
or demand future M1 execution bytes just to register work. Coverage progress references a retained
index; each page must not copy all previously scanned ranges into current authority.

RegistrationReplayHandoff captures retained-source tail h and atomically records the occurrence,
immutable semantic selection, selected-history backfill through h, live acquisition after h and a
registration-change record/wake basis. Source append participates in the same scoped metadata
authority. h is an acquisition cut, not activation time or semantic identity. A lifecycle change log
or equivalent range-generation protocol covers registrations appearing behind a scan key. Bodies
are read outside locks. [06](06-storage-and-transactions.md#103-no-gap-registration-replay-and-retirement)
defines the race/retry laws; membership evidence and processing progress are distinct frontiers.

## Consumer receipt application

The conceptual managed delivery intent covers live continuation, initial chronological replay and
attachment catch-up. These are delivery purposes, not constants in an implemented
`ManagedDeliveryIntent` API. The actual boundary uses `ManagedImportSelection` and `ManagedImportLane`.
The normal active Order path must not depend on marking its occurrence inactive.

The reaction group (represented by actual `ManagedReactionContext` and owning result evidence,
not a public `ManagedReceiptReactionGroup` sketch record) binds the complete relevant producer set at one inherited reaction origin
and logical reaction position to its owned consumer scope, including any same-origin own direct seed.
It is not a separate target operation per upstream receipt. All eligible placements and routes of that origin
belong to that reaction. For each source patch, update placements in canonical authored-path order,
finishing each synchronous containing continuation before the next placement. Revalidate its exact
identity/activation; never patch a replacement as the old occurrence. Distinct source operations or
positions cannot be merged by a SQL page. Already-consumed placements do not replay on new attachment.

The retained `SourceObservationProgram` and its owning result/receipt authenticate:

- observable reference updates and exact intermediate views wherever handlers can observe them;
- deterministic event admission/order and event-specific frozen recipient validity;
- original occurrence identity, provenance, route multiplicity and completion ownership;
- exact logical gas/charge ownership for the selected operation and consumer realization.

Entry/Patch/Enqueue/Delivery/Exit frames bind exact source/result authority, continuity and causal
continuation sites under [22](22-processing-kernel.md#3-reuse-a-source-by-substituting-completed-actions-not-by-replaying-a-flat-event-list).
Substitute authenticated source actions, finish the consumer's required continuations, and admit
events at their actual enqueue sites into the common FIFO. Do not sort frame addresses as event order
or replace the program with final state plus events. Its codec, constructors and verification are
implemented; they are not permission for host-authored opaque proof. Immutable intermediate
views need no independently committed source heads.

Separate Timeline entries and internal events are different controls. In the simple two-entry case,
Root's reactions to successive Parent operations read1 then2 even if Parent is physically ahead.
For internal E1/E2 emitted by ONE input, FIFO can be E1,E2,F1,F2: Parent reaches2 before F1 is
handled. F1's frozen payload can still contain1 while a document read at delivery sees2. Internal
events do not independently create epochs. Preserve the actual operation and observation boundary.

Preserve each required occurrence/route charge and synchronous update/lifecycle semantics. A
final-state/batched fast path is allowed only after observational and gas equivalence is established.
The successful library-owned operation publishes its complete state, cursor and obligation delta;
failure rolls back that owned operation, not independently committed source or healthy observers.

Creation synchronously supplies canonical initialization or the explicitly selected initial source
view and its required lifecycle/update work. It does not add a new placement to an already frozen
event. Historical epochs use a separate ordered fixed-cut catch-up lane; before it runs, reads see
the installed view and actual parent state, not a fabricated caught-up field. The actual managed lane
descriptor/cursor retains that committed continuation; `AttachmentContinuationIntent` was a host
sketch name. Unavailable history suspends the lane, never creates a private
same-epoch catch-up or a creator waiting for its own commit. Rollback leaves no new obligation.

Retain both authenticated source evidence paths: the full current transition receipt and the current
same-state zero-event managed-application evidence through its owning application, complete committing
result/companion, exact state, receipt and gas. A bare state wrapper or absent event list is not proof.
Source event identity, multiplicity and provenance survive import; import itself is not republication.

For S → P → Root, recursively interpret authenticated upstream programs while preserving original
S events, P's own emissions and observable P views. Compose direct/transitive routes before finishing
the consumer reaction; do not replay a duplicate flattened Parent event list. Acyclic containing
scopes are visited nearest first, with canonical occurrence-path order for equal-depth branches.
Cycles use the selected per-route vertex-simple containment rule in chapter 22, not global visited-lineage
deduplication. New emissions are new occurrences in the same shared-gas workflow. Shared immutable
route prefixes are a storage optimization, not a second host event scheduler.

A selected successor's execution-only body may be unavailable while the current complete operation
commits its exact progress and remaining authority. Missing evidence needed for the current result,
temporal eligibility or authoritative successor selection can still hold the current operation.
Do not reapply a committed source or M1 because M2 later needs its body.

## Local chronology and causal dependencies

There is no global all-parent partition or one serial root-wide committed prefix. Coordination
selects local operations from an explicit causal dependency DAG. Independent ready consumers may
commit in either physical order. Conflicting operations on one lineage need a complete canonical
ordering/readiness proof before execution; CAS picks a winner only after semantic selection, not
instead of it.

Timeline timestamps and canonical exact entry-ID ties order original external inputs. Imported
sourceOrder remains provenance and may repeat/decrease; retained source position proves continuity,
and the producing logical causal position determines historical visibility. A nested initially
authored history can be visible before its later physical admission, while K@100 importing E@10
must not backdate K's genuine new effect into cutoff50.

Initial FULL_HISTORY interleaves relevant source and parent histories. Source@10, parent read@20,
source@30 must read the t10 value at t20 even when source@30 is already committed. Later attachment
at T has its own declared selection. The pure-read and pending external removal/rebind requirements
in [17](17-causal-processing-model.md) remain in scope under [18](18-independent-lineage-processing.md):
retire only the old occurrence's obsolete future inter-operation work, preserve already-valid frozen
local deliveries and independent work, and create the exact
new occurrence/history basis without importing future source history first.

The operation owns its directed dependency SCC at the logical cut. Newly touched pending same-origin
work adds readiness evidence, not automatic ownership. Before a returning edge, the revised library
charges the ordinary check cost to the initiating meter and compares distinct current group gas
against their identical frozen limit. A fitting join admits edge and ownership together, without a
reset/refund; rejection leaves ownership unchanged and fails the initiating current group with
recognized RUNTIME_FATAL/AtomicScopeGasAdmissionFailure. Actual charged gas and the prospective
required-scope-sum diagnostic are distinct. Local inability to pay the check is ordinary gas failure.
The implemented `SameOriginGroupEvidence` and owning result authenticate the frozen seeds and
admission decisions; the old `SameOriginScopeEvidence` name was conceptual. See22 §2.1 for the
complete rule. The admission-capable library evaluator is implemented; this is not host-side
splicing of closed Contracts results. One-way sources remain independent. Direct seeds never reorder;
a read before a later seed sees its pre-seed view. Discard failed-producer speculative effects before
publishing a dependent. Missing evidence holds; positive feedback controls cannot pass on that hold.
All participating work fences, including superseded candidates, participate in commit/reconciliation.

An admission also binds its independent-producer assumptions. Ownership is monotone inside a valid
attempt. Failure of an unfinished external producer invalidates the whole dependent attempt,
including third-party admissions, gas, candidate failures and outputs; reconstruct still-due own
seeds, never terminally consume them from invalid evidence. Published work is not undone. The
producer's already determined rejection retains its canonical attempted-prefix explanation and is
not recomputed against cheaper cleanup results. Validate or internalize every assumption before
publishing the final group;22 §2.1 contains the AC61+B51=112 witness.

## Persistence, failure and publication

The actual result variants, `Disposition`, `LineageProjection` and `ManagedImportLane.Delta`
distinguish state/epoch, handled external-input lane and successful source view. The earlier
`OperationProgressProjection` hierarchy is not a shipped API. There is no universal
processor-status-to-lineage-advance rule.

| Operation | Terminal progress rule |
|---|---|
| `EXTERNAL_INPUT`, including canonical source-history preparation | A completed same-origin group is `CONSUMED`; success changes its authorized state, while certified gas/runtime failure retains rollback views. Target-only bookkeeping is `MetadataProgress`, not a fake group. No configurable external-status table is exposed. |
| `MANAGED_RECEIPT_IMPORT`, live or historical | Commit, gas failure or recognized semantic `RUNTIME_FATAL` is consumed under fixed laws; other completed statuses are blocked. Failed observer work retains successful views; `SOURCE_FAILURE` accounting may be `ManagedProgress` without an observer invocation. |
| `INITIALIZATION` | Only a committing result is consumed; failure is `BLOCKED` and creates no usable initialized lineage. |
| No invocation / operational hold | `Idle` has no selected progress. Proved metadata/lane progress carries its exact deltas; `NeedEvidence` or an operational exception has no terminal progress or semantic gas settlement. |

SUCCESS alone can project successful business effects. The core's fixed operation-kind laws are
not a pluggable policy engine; historical source preparation uses the original operation's semantics.
The implemented continuation records managed r1 GAS_LIMIT_EXCEEDED or recognized
semantic RUNTIME_FATAL as terminally
handled without changing the consumer's successful view/epoch. A subsequent r2 uses its original
source receipt/history plus authenticated dispositions of every intervening due delivery and the
actual rollback pre-state. First align the actual pin to r2's exact before-view with ordinary
synchronous update/lifecycle/gas effects, then interpret r2's program. Consumer0 therefore sees
alignment0→1 followed by r2's1→2; an event before r2's first patch reads1. An eventless r2 still
aligns and a net-zero r2 retains its updates. Alignment is not successful r1 replay or a source
receipt; if it or r2 fails, the entire new operation rolls back to0. `SourceObservationGap`,
`SourceOperationFailure`, per-consumer observed-source receipt rows and managed lane cursors/deltas
make the distinct successful-view and handled-delivery positions explicit. They replace the
sketch's `ManagedFailureContinuationEvidence`/`ManagedDeliveryOutcome` names.
In a multi-producer reaction, bind each alignment to that source's first canonical Entry/consumption
site, not a global all-pin prelude. Earlier own direct work sees actual rollback pins. A Entry aligns
and interprets A before later B Entry changes B, preserving per-placement synchronous continuations,
FIFO emissions and retirement. The source program is reused once; distinct occurrences still align.
Gap evidence covers authenticated consumed gas/certified-runtime failures, including same-origin
external groups that observed a source. Verify original operation kind, owning classification,
per-consumer source view and shared settlement, not a configurable host status policy. An
unhandled/nonterminal gap rejects; a gas-only filter must not strand a lawfully consumed composite lane.

Normal successful catch-up remains uncoalesced and preserves all intra-operation observations.
If r2 also fails terminally, a later D30 detach can execute on the real rollback state once earlier
due work has terminal dispositions. It does not overtake or retroactively cancel r1/r2. Missing
evidence, unavailable providers and uncertain commits remain nonterminal waits/reconciliation.
Existing exact-predecessor checks stay intact for ordinary import; the failure-continuation
constructor is an implemented Coordination/Contracts extension with focused cold/failure controls.
Other statuses retain their specified laws; this is not a universal skip-on-error rule. RUNTIME_FATAL
means a recognized deterministic semantic failure under the exact runtime/input/environment/policy.
Unknown exceptions, internal bugs, IO/timeout/cancellation and missing evidence are operational
failures/waits, never consumed semantic inputs. Owning typed classification and fault-injection
controls replaced the former broad exception wrapping. Failed initialization
still cannot establish a usable source. See22 §6.2 for classification and portable diagnostic rules.
Source and healthy independent siblings continue. Real coupled workflow failure still rolls back its
whole owned scope/shared gas. Optional aggregate reporting keeps failures and unfinished work visible.

AuthenticatedRepresentationCompanion carries the triggering operation's verified same-epoch component
rebinds in its local delta, with exact before/after representation and unchanged business receipt
position. Apply its required coupled representation fences. It creates no new business application,
receipt, synthetic event/fanout or gas reset; existing finalization charges remain. The receipt/fanout
publication requirement applies to new business source-history revisions, not this representation-only
projection. Unproved mixed-cut components hold before publication.

Use trusted PostgreSQL with coherent scoped row, absence, temporal-predicate and control fences.
Every overlapping writer participates. A short host-wide SQL lock may serialize writes, but an
unrelated sibling commit must not invalidate a prepared local operation merely by changing a global
revision. Execution/resource reads stay outside short write transactions. Accepted completeness/
ingress prefixes survive stronger proofs/unrelated tail growth; current reservations, authorization,
scope and actual mutable write preconditions remain checked.

The apply plan and reconciliation receipt contain exact typed lineage projections and stream
appends: zero for metadata-only no-delivery, normally one for an independent operation, and every
owned affected existing lineage for coupled work. Canonical initialization can add initial source
authority to that same atomic transaction. Check every before/after, kind, result and predecessor;
reject omitted/extra projections. Publication order is per semantic lineage/stream plus explicit required causal predecessors, not a
global SQL commit sequence. Read the immediate successor or bounded page; tail growth does not
invalidate it. Stable message keys, durable sink deduplication and ACK-before-cursor advance preserve
logical delivery under retries. No total physical arrival order is promised for independent siblings.

CommitUnknown retains its exact operation/work query. Receipt absence permits retry only when the
old generation is invalidated under the same work lock, or a valid newer invalidation is proved.
Operational holds retain exact needs and cursors; durable level-triggered rechecks close lost wakes.
They do not create processor statuses, semantic skips or successful completion.

Account/document quotas are host controls, separate from the fixed operation execution policy. Use
an operational hold for insufficient allowance: needed80/limit100/allowance50 cannot become semantic
gas failure at50. Replenishment preserves identity, history and one settlement. No new cross-operation
fuel API or general semantic cancellation API is part of this POC. A nonterminating series of valid
reattachment imports can be safely paused without making a later blocked repair input eligible;
22 §6.1 states this explicit liveness boundary.

## Proof record and remaining measurement obligations

The [verification record](implementation/phase-1-2-readiness.md) distinguishes implemented witness
coverage from the full future acceptance protocol. Retain the local ownership/identity controls:
Agreement commits before Orders, an unavailable/failing Order leaves healthy peers progressing,
active receipt application, reversed sibling order/restart, source-ahead attachment/removal,
registration/publication races, complete local scope and intermediate-observation equivalence,
operation-kind failure/cursor rules, same-operation cold/warm initialization and distinct admission controls,
acyclic identities, K100-owned E10-derived work, noncommuting same-target inputs and feedback scope.

Source-commit and consumer latency/throughput are separate acceptance workloads. Whole-fanout drain
time is useful optional reporting, not a universal seconds-scale guard independent of recipient N.
Test1k/10k/100k recipients, millions of unrelated documents and thousands active per tenant; require
bounded indexed work, memory/transactions/concurrency, fairness and overload recovery. Necessary N
semantic reactions and their gas may grow with N; avoid repeated source execution and unnecessary
infrastructure amplification. Also measure discovery/index rows, physical source/consumer calls,
Contracts retry work, serialization/WAL and memory. Warm workers/caches are encouraged; a cache
cannot hide a repeated source computation or a full-graph scan. The first integrated POC is evidence
for further library and host iterations, not a final API freeze.
