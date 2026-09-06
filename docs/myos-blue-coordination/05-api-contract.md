# API rationale and decisions

> **Status:** IMPLEMENTED POC RATIONALE + PHASE3 HOST REQUIREMENTS · **Revision:** 15.12, synchronized 2026-09-06
>
> [Actual boundary](05-poc-api.md) · [Implementation summary](24-phase-1-library-summary.md) · [Verification](implementation/phase-1-2-readiness.md) · [Java reference](reference-api/00-poc-minimal-boundary.java)

## The implemented operation model

[22 — Selected processing kernel](22-processing-kernel.md) is the highest algorithm authority.
It names deliberate owning-library changes and preserves unchanged Contracts continuation/FIFO laws.
The following rationale does not introduce competing candidate semantics.

The ordinary managed-embedding model is independent lineage processing: Agreement commits once,
then each Order deterministically consumes its exact retained source receipt. It is not a single
atomic Agreement-plus-all-Orders invocation. Shared content identifies the same lineage, but sharing
does not automatically imply shared write scope, gas, rollback or progress barriers.

This is a deliberate library/API change, now implemented in the local external-state boundary.
The owning libraries expose group-local operations, exact managed reads and source-program
substitution. A host cannot split an already-started private invocation or derive write ownership
from every member of a read snapshot.

The Phase1/2 baseline implementation and local validation are recorded in the detailed
[handoff](24-phase-1-library-summary.md), with scope and limits. Subsequent
[review repairs and changed-candidate verification](implementation/pre-phase-3-review-remediation.md)
have passed the agreed integration-readiness gate. This page explains the actual
boundary and preserves the remaining host protocol requirements. It is not a release/API freeze or
formal acceptance of every catalog scenario. Names retained for host concepts are not invented
public Java interfaces; [05](05-poc-api.md#core-interface) and the updated reference list actual APIs.
[18](18-independent-lineage-processing.md) supersedes the former global-partition/root-prefix premise;
[17](17-causal-processing-model.md) retains chronology cases subject to that ownership boundary.

## Atomicity has an owner

Each library-derived atomic operation/group owns its gas, rollback, complete result and commit.
One Core evaluation can return several such groups in `PreparedOperations`; that container is not
an aggregate transaction. Agreement's
failure can prevent its own source publication. An Order failure cannot roll back a previously
committed Agreement revision or a successful Order. A failed Order's local dependents may remain
blocked without blocking healthy siblings or a source whose next operation is otherwise eligible.

There are two different observations: local source progress and complete causal fanout outcome.
The latter must account for undiscovered eligible recipients, pending/failed deliveries and genuine
new causal consequences. It is optional host/test accounting, not a required global receipt or an
automatic transaction barrier. Empty queues are not proof of completed fanout or successful processing.

Completion ownership is distinct from receipt provenance. E10 can remain closed while a K100
attachment imports its retained receipt and produces new reactions owned by K100. Do not reopen E10,
backdate those new effects, or let whichever replay/discovery worker ran first choose their owner.

A readonly embedding does not authorize WORK on its source. Genuine feedback that belongs to one
core operation retains its complete atomic scope and shared gas, including gas-killed rollback.
Do not turn its queue into an endless sequence of fresh-budget host jobs. Independent one-way
observers need not join that scope. The selected scope is the directed dependency SCC at the logical
cut. A first one-way contact does not join independent meters. Before admitting an actual returning
same-origin dependency, charge the initiating group's normal admission-check cost, then compare the
distinct groups' canonical admitted gas prefixes with their common fixed limit. An affordable join
irreversibly owns their shared remaining gas and rollback; an unaffordable join rejects the edge and
fails only its initiating group with certified AtomicScopeGasAdmissionFailure and actual local gas.
If the local check itself cannot fit, ordinary gas exhaustion occurs without joining. Earlier required
predecessors, canonical seed order and publication readiness remain prerequisites; workers cannot
choose ownership or retrospectively replay away a joined edge. [22 §2.1](22-processing-kernel.md#21-dynamic-joining-for-the-same-origin)
defines this implemented owning-library admission rule.

The selected boundary is an authenticated observation program: Entry/Patch/Enqueue/Delivery/Exit
frames, exact views, original IDs, frozen recipients, causal continuation sites and gas authority.
Interpret source actions, finish the consumer's required synchronous continuations, and admit events
at their recorded enqueue sites. No flat final state/event list or sorted frame address replaces FIFO.
The actual `SourceObservationProgram`, codec and interpreter implement these boundaries; they are
not the sketch's `ObservationFrame` records. Retained termination requests/cutoffs also preserve
real lifecycle steps and marker timing without representing skipped work as an executed step.

One source operation at one reaction position has one complete reaction per owned consumer scope,
including all eligible placements/routes. For each patch, visit canonical authored occurrence paths
in order and finish one placement's synchronous continuation before updating the next. Revalidate
identity/activation and preserve distinct cursors/multiplicity. SQL pages cannot split that scope
or merge distinct operations. Cyclic routes are vertex-simple per original-event route, not global
deduplication; new emitted occurrences still run under the same workflow gas limit.

Creation supplies initialization or the explicitly selected initial view and synchronous lifecycle
effects before a permitted read. It does not join an already frozen event. Historical epochs then
use the ordered fixed-cut attachment lane: no fabricated caught-up field, private same-epoch replay
or creator self-wait. Retirement follows exact frozen-delivery laws; rollback creates no new obligation.

For S → P → Root, preserve the actual observable updates and event sequence, not a mandatory flat
Parent relay batch with one final P view. Original S events remain S events; P's own emissions remain
P's. Intermediate P views, route multiplicity and membership must survive wherever observable.
One-hop discovery, immutable route-prefix sharing and a final-state fast path remain possible only
where the evidence proves their equivalence. Neither a new S cursor nor one P cursor is a universal
substitute for the recursive observation-program interpretation selected in chapter 22.

## Why the durable source descriptor matters

This section is a host protocol requirement, not a claim that Core has an `expandFanout` method or
that a complete application fan-out scheduler shipped in Phase1. Phase2 exercised focused durable
prefix/recovery paths; Phase3 connects the full indexed host integration and measures scale.

The source transaction stores the exact receipt and durable fanout basis. It does not load or freeze
all Orders or create their complete application payloads. Subsequent bounded discovery creates exact
per-occurrence obligations and cursor progress atomically, using stable identities independent of
scan pages.

A source can be ahead of a consumer's processing. The host maintains transactionally consistent
temporal dependency indexes, not merely a current-subscriber cache. Scoped index queries and
registration/frontier coverage establish eligible recipients without reconstructing all history.
The source events remain available while discovery or consumption requires them. Tests audit index
maintenance against semantic history; normal processing does not replace indexed reads with scans.

The no-gap handoff must cover both races: a receipt commits during lookup-and-subscribe, and an
attachment/removal becomes known after a fanout scan passed its range. Closed discovery and retention
release need positive coverage authority, not absence of an outstanding work row. Do not fix this
by making Agreement wait for every Order payload before its own commit.

The concrete POC handoff captures source-log h in a short scoped metadata transaction and records
selected-history backfill through h, tail acquisition after h and a lifecycle change/wake record.
Source append shares that authority. h is only a transport boundary. A late registration behind a
scan cursor must be recovered from the lifecycle log or an equivalent scoped generation protocol.
Registration/membership completeness closes lifecycle decisions, not the very receipt processing
whose eligibility is being proved; otherwise a circular wait is built into the design.

## Deterministic local identity

A logical application binds exact cause/operation kind, target lineage, semantic predecessor,
optional complete receipt-reaction group and environment/policy. Independent sibling completion order is not semantic
input. An immutable all-parent partition, global application ordinal, latest database cut or domain
commit head therefore cannot own application/InvocationId identity.

The implemented constructors distinguish original execution seeds from final group authority and
exclude unrelated mutable inventory from local identity. Merely adding a host logical ID would not
have satisfied the requirement. Conversely, removing actual predecessor/read-pin evidence would
weaken correctness. Reversed independent schedules, fresh/cold source reuse and exact receipt
comparisons are part of the recorded witness coverage.

Identity construction separates stable execution seeds and emitted occurrences from final atomic
settlement. Derive each seed from its exact cause/reaction position, predecessor, applicable placement
selection and environment/policy; emission identity uses that seed and its local continuation address.
Accepted dynamic joining preserves those earlier identities. The final group/settlement separately
authenticates selected seeds, predecessors and the accepted admission sequence; transition/result/
program wrappers derive afterwards. Never hash a wrapper containing its own identity, expose a final
settlement ID to earlier business execution, or re-anchor earlier emissions after joining.
[22 §5](22-processing-kernel.md#5-canonical-origin-source-history-is-not-born-at-the-first-order)
owns the exact preimages. Completion-accounting wrappers, physical page h and sibling commit order
are not substitute semantic seeds.

No recipient is also a real typed decision. The actual `MetadataProgress` carries a selected
handled-input outcome without a target business invocation; `Idle` carries no selected work.
Neither is the sketch's `NoLiveRecipientsAction` or a global fan-out completion certificate.
Ordinary external discovery and managed fan-out cannot equate a presently empty index page with
complete temporal routing authority.

DocumentId remains the initial authored exact BlueId with current cyclic rules. Equal authored
documents intentionally share a lineage; no work-derived allocation ID changes that rule.

Canonical source origin is source-local initialization plus FULL_HISTORY under the frozen environment
and source execution policy. FROM_NOW/FROM_FRONTIER select observer attachment history and initial
view, not a different birth for the shared source. The new explicit attachment boundary must not be
replaced by the older SDK's birth-at-attachment enum conversion.
Promoter, row existence and physical admission time cannot remove an applicable earlier source entry.

Reconstructing a historical source operation must also preserve its own original attachment choices.
The importing observer's policy is not that source input. Original admission authority binds source,
exact cause, predecessors and frozen choices (including empty choices); missing authority pauses
reconstruction. Caller-created candidate bytes or a first-worker choice do not establish this
authority. See the concrete [source preparation API](05-poc-api.md#source-preparation-attachment-and-import).

Canonical independent source initialization and parent consumption have separate fixed gas scopes,
including cold execution. Source80 and parent20 with limits90 pass cold/warm; no target comparison
claims equality to the former combined100 invocation. Source gas settles once on authority; physical
rebuild is not rebirth/recharge. Parent logical verification/update/handler costs are cache-independent.
Real coupled initialization/workflow still owns its shared gas scope. See [05-poc](05-poc-api.md#selected-source-origin-initialization-and-observer-attachment).

`evaluate(WorkIntent(..., INITIALIZATION), evidence)` and `CanonicalSourceHistory.prepareNext`
prepare canonical evidence; `SourceInitialization.fromProgram` validates a usable initialization.
There is no separate Core `prepareInitialization` method. Successful authorized
promotion installs its complete required source closure, canonical source-owned result/gas/batches
and initial registration/cursor dependencies atomically with the introducing result. Failure or
create-then-retire publishes no orphan; prior authoritative X survives unrelated parent failure.
Concurrent promoters agree on the same source authority, not first-worker semantics. Standalone
admission needs no fictitious parent. Actual accepted installation/frontier capabilities bind the
creator site and selected view; surviving-occurrence lane factories reject unrelated authority and
produce no lane for create-then-retire. Their implementation controls are in the verification record.

## Local ordering rather than physical winners

A dependency DAG identifies required semantic predecessors. Worker/lease winners may affect which
independent ready operation runs first, but not which noncommuting operation is next on a lineage.
The selector must prove completeness and canonical conflict ordering before optimistic commit CAS.

External original entries retain exact microseconds and canonical entry-ID tie order. Core's
`timelineId` string is the authenticated entry's `/timeline/timelineId` locator, not the typed
Timeline object's BlueId. Entry and event are the same complete typed Timeline Entry, not a payload
extracted from `/message`. Source receipt
position proves contiguous retained lineage history; sourceOrder is provenance, not necessarily
monotonic. Visibility depends on the producing logical causal position, not physical admission or
the source's latest head. Initial FULL_HISTORY interleaving and later attachment remain distinct.

Historical pure reads and pending external removal/rebind require explicit Contracts per-use and
lifecycle rules. A source already committed at t30 does not authorize a parent t20 read of t30 or
require early import of t30 before removing the old occurrence. Keep nested-positive history and the
K100-importing-E10 negative as paired controls. Imported events are not new source emissions;
genuine feedback is not silently retimestamped into the old source cause.

## Keep the host trusted and bounded

The first host is a trusted PostgreSQL adapter with coherent scoped reads and complete fixed
predicates. Core owns selection and semantic validation; independent raw-state/index tests detect
coherent omissions that self-hashed answers cannot prove away.

Scoped row/absence/temporal-predicate and actual authorization/reservation fences replace the
domain-wide semantic revision as universal preparation authority. Every overlapping writer must
participate. An optional short SQL serialization lock is operational only: it cannot determine
semantic order, enter identities or invalidate unrelated prepared consumers.

The implemented `EvaluationEvidence.operationFences` makes owner attribution explicit. A prepared
group uses its owned mutable fence union; consumed source operation IDs refer to immutable evidence.
B's required publication can therefore precede A without invalidating A on an unrelated B-head CAS.
The host must not drop all fences or infer ownership from opaque fence-key spelling.

Verified accepted completeness/ingress prefixes survive stronger proofs and unrelated tails.
Source/consumer payload availability remains distinct from selection authority. A complete source
or M1 can commit its durable obligations while future execution-only bodies are absent. Unproved
membership, own-result evidence or actual semantic prerequisites can still block the applicable work.

Sparse bodies are implemented, not sparse or guessed semantic topology. The selected directed read
cut still needs complete authenticated headers/occurrence rows and root-channel membership.
`ReusableComponentAuthority` and `RootChannelMetadata` preserve verified unchanged facts;
`AffectedClosureSnapshot.withResidentBody` admits exact hydration. Actual reads/owned work/new SCCs
must acquire their bodies. The selected source program and borrowed DAG are still materialized
within physical budgets, not interpreted as a fully streaming action source. Measure capture-time
peak memory separately from encoded-byte limits.

Store immutable definitions once and normalized local/dependency deltas. A per-step materialization
cap is not a cap on total fanout: N greater than that cap progresses through pages. Count inspected
topology, definitions, retries, bytes and WAL; do not substitute a magic SCC index or warm cache for
a bounded algorithm.

Ready-work selection and reverse lookup from a changed dependency to its waiters are mandatory
host capabilities. Their physical queues, index structures and partitions are host-private, but
efficient scoped queries and atomic maintenance are integration requirements. Validate concrete
PostgreSQL query plans at millions of documents, thousands active per tenant and1k/10k/100k
recipients. Required N reactions/gas may grow; bound memory/transactions/concurrency, prevent tenant
starvation and exercise backpressure/recovery. Independent source commit does not wait for N.

## Recovery, publication and scope

Commit the local result, settlement, heads/cursors, source receipt/fanout basis, dependency/accounting
delta, local outbox and receipt atomically. Work-fenced CommitUnknown reconciliation and durable
level-triggered needs prevent duplicate effects and lost wakeups. Cancellation ends an attempt,
not a semantic cause; failure remains failure in aggregate reporting.

Progress is operation-specific, not a status-to-advance switch. The implemented Core exposes fixed
`CONSUMED`/`BLOCKED` dispositions, not the old configurable `FrozenExternalInputDispositionPolicy`
sketch. Completed same-origin external groups consume their exact selected input; initialization
requires a committing result, and managed import consumes commit/gas/certified-runtime outcomes.
When one operation combines a direct external seed and managed observations, its owning disposition
covers the selected work together under one settlement; the host cannot split its progress. This
does not introduce a configurable Core policy or an additional composite-operation entry point.
A consumed failed result has no successful new epoch.
A failed managed receipt import, live or historical, preserves state and all successful source
cursors. Pure managed GAS_LIMIT_EXCEEDED and recognized deterministic RUNTIME_FATAL record terminal
delivery outcomes; other statuses are not universally consumable. Owning-library classification
must authenticate the semantic reason and actual admitted gas, including AtomicScopeGasAdmissionFailure.
Timeouts, cancellation, I/O, unknown commits and unclassified implementation exceptions cannot consume
input or settle semantic gas. Initialization failure supplies no usable initialized source and publishes
no initialized lineage. These fixed laws do not require a general pluggable progress policy engine.

Core gas exhaustion and certified semantic runtime failure are terminal failed managed operations,
not a mandate for an endless retry or permanent global stall. Preserve their exact failure and use
the applicable operation's terminal progress law; later
independently eligible work can proceed. A failed managed import still cannot fabricate a successful
receipt cursor. After r1 failure at consumer0, r2 aligns0→1 to its exact before-view, running ordinary
update/lifecycle/gas effects, then interprets its original1→2 program. An event before r2's patch
reads1; eventless and net-zero operations preserve alignment/observations. Failure anywhere in r2
rolls the operation back to0. No failed-event replay, forged source receipt or range skip occurs.
Gap validation accepts authenticated consumed gas/certified-runtime failures, including same-origin
external groups that observed a source; verify the original kind, owning classification, per-consumer
source view, shared settlement and exact lanes. A nonterminal or unhandled gap cannot authorize alignment.
If both imports exceed gas, D30 may detach after the earlier outcomes, without overtaking them. Normal successful
catch-up/intra-operation observations are preserved; unavailable evidence is not terminal failure.
This is an implemented Coordination/Contracts extension using `SourceObservationGap`,
`SourceOperationFailure`, exact per-consumer observed-source rows and managed lane deltas;
[22 §6.2](22-processing-kernel.md#62-semantic-failure-classification-is-an-owning-library-contract)
defines the owning typed semantic-failure mappings used by terminal runtime-fatal imports.

Verified representation-only companions belong to their actual triggering operation's delta.
Same-epoch component publication uses exact before/after proof and required coupled representation
fences without new business receipts/events/fanout or a gas reset. Preserve finalization charges;
the source-receipt/fanout law concerns new business source-history revisions, not re-encoding.

Apply-plan and receipt lists cover zero business appends for metadata-only no-delivery, one normal
independent lineage, or all existing lineages owned by coupled work. Initial canonical authority may
join the same transaction; every projection/predecessor/result is authenticated. Publish per
lineage/semantic stream with explicit required causal dependencies. Independent sibling
streams have no global arrival-order promise. Bound successor reads; sink acknowledgement follows
durable stable-key deduplication, then the stream cursor advances.

Preserve the complete eleven-field environment, portable limits, numeric domains, authenticated
eventless receipt path, source occurrence multiplicity and full local gas diagnostics. BEX reuses
its existing exact loading/compiler-cache mechanisms; this work added no new cache API. Owning
failure classification and nonsettling ledger abandonment keep unknown/operational faults from
becoming consumed semantic failures.

No distributed transaction, production migration/GC, specification profiles or mid-invocation
continuation is introduced. Retention nevertheless has a correctness rule in the POC: do not discard
source evidence while unresolved temporal membership, replay or consumer obligations may need it.
Phase1/2 witness coverage includes independent and coupled/feedback paths, sparse/cold recovery,
failure continuation, creation and termination. The linked record distinguishes those results from
remaining Phase3 application-scale and formal acceptance runs; none of those remaining requirements
is satisfied merely by this documentation or a compiling reference example.
