# Semantic equivalence and source reuse

> **Design baseline:** 15.12 · **Status:** retained semantic laws; Phase1/2 handoff synchronized
> [Independent lineages](18-independent-lineage-processing.md) · [Edge contracts](19-semantic-edge-contracts.md) · [Decision traces](20-decision-traces.md)

[22 — Selected processing kernel](22-processing-kernel.md) now closes the previously open algorithm
choices and has highest precedence. This chapter records the invariants and contrasting controls.
These are library contracts and hand-derived expectations, not a test-results report. Implemented
refinements and verification limits are in [24](24-phase-1-library-summary.md).
The [source-evidence appendix](review/revision-15-7-source-evidence.md) records the inspected baseline.

## 1. One logical Contracts semantics

The same semantic inputs must produce the same histories, observable reads/updates, event order and
multiplicity, identities, gas and failures regardless of caches, storage layout, workers or retries.

Current ordinary PROCESS delivers an emitted occurrence to frozen ancestor scopes. The existing
three-level control gives Leaf E to both Middle and Root, with Root's composed /mid/leaf path.
The inspected baseline PROCESS_CLOSURE selected directly containing managed bindings and could lose Root's original-E
observation. The Root-only execution profile does not justify changing authored observation rules.
Phase1 repairs that gap in the owning library; requiring Middle to invent another emission is not a fix.

Use ordinary PROCESS controls for unchanged local handler, synchronous Document Update and FIFO
laws. Use22's explicit independent-ownership reference for the changed commit/gas/rollback boundary.
Comparing only two managed adapters is insufficient; claiming identical old whole-closure failure
behavior while allowing independent Source commits is also incorrect.

## 2. Source reuse is not cache-dependent semantics

One Agreement should be processed once and reused by many independently progressing Orders.
Agreement does not wait for N observer reactions, their enumeration or their unrelated Timelines.
The directed dependency/owned-component rule in22 decides independence; a real returning workflow
has one shared gas limit and atomic result. A database row does not define an operation.

Necessary N consumer reactions remain O(N) work; source execution must not become O(N). MyOS owns
durable complete discovery and execution through transactionally consistent temporal dependency,
ready-work and reverse-wait indexes. Source commits retain a durable fan-out basis. Aggregate
completion is optional reporting, never a new semantic receipt or global commit barrier.

### Initialization and authorized history

Source origin is selected: the initial authored DocumentId, frozen environment and source execution
policy determine canonical initialization and FULL_HISTORY. The first Order or SQL INSERT does not
start that history. X's E15 remains in X whether first materialized through OrderT10 or OrderT20.

For this integration, FROM_NOW/FROM_FRONTIER explicitly select observer history and initial view,
not a competing source birth. Phase1 implements this change from baseline temporal-admission composition
in the external-state boundary; it is not a claim that every legacy SDK entry point uses that boundary. Different observer selections
can have different Order histories without changing X's history. See [22 §5](22-processing-kernel.md#5-canonical-origin-source-history-is-not-born-at-the-first-order).

Canonical source initialization has its own fixed meter and source-local context. Each parent pays
its own verification/view/update/reaction work identically cold and warm. Source80 plus parent20
under separate limits90 succeeds in both cases, unlike the old combined100 invocation: the ownership
change is explicit. Physical rebuilding is not a second logical birth or a second source settlement.

Candidates are non-authoritative. Successful publication can promote the exact canonical candidate
set once; creator failure publishes no new orphan source. Already independently authoritative X
survives a later failed Order. Incompatible canonical authority is an integrity/configuration error,
not an election. [20D](20-decision-traces.md#d-initialization-policy-and-publication-birth-selection-remains-open)
contains the selected BIRTH trace; its historical anchor is retained for existing links.

## 3. A final receipt is not complete observation evidence

Two separate Timeline inputs I1/I2 produce Source epochs n+1/n+2 and separate ordered Parent
reactions. Root's corresponding F1/F2 observations use historical Parent views1/2, even if the
database already stores a later head. Internal events do not automatically create new epochs.

The following controls instead use one input and its internal FIFO:

| Control | Required observation |
|---|---|
| Source queues E1/E2; Triggered E1 sets x1 and E2 sets x2 | Parent reads1 on E1 and2 on E2. |
| One handler applies both patches before admitting its buffered events, without later self-changes | Both event reads may correctly be2. |
| Parent handles E1/E2 by setting y1/y2 and emitting F1/F2 | Queue is E1,E2,F1,F2; Root's later F1/F2 document reads are2/2, while F1's frozen payload can be1. |
| Parent emits P1 during E1; Source emits F2 during E2 | P1 precedes F2. A preloaded source event list would incorrectly reverse them. |
| Source patches0→1→2, or0→1→0 | Preserve both synchronous nested-path update reactions, even when final business data is unchanged. |

Retain a library-authenticated observation program: exact Entry, Patch, Enqueue, Delivery and Exit
frames with immutable views, original paths, enqueue sites, occurrence identities and complete
result/gas authority. The interpreter substitutes a source's completed actions at their original
continuation sites; it does not rerun its business handlers or preload its final flat event list.
It reconstructs FIFO through actual admissions, including recursively produced Parent work.

This is the selected minimal semantic frame vocabulary, not a public VM instruction trace. Exact
codec/type construction and executable conformance remain library implementation tasks. Shared
immutable nodes/prefixes and verified caches avoid full-history copying. See [22 §3](22-processing-kernel.md#3-reuse-a-source-by-substituting-completed-actions-not-by-replaying-a-flat-event-list).

## 4. Grouping, new placements and nested observation

### Multiple placements

Visit distinct placements in canonical authored occurrence order. Apply /left's update and complete
its synchronous continuation before /right. A /left update handler reads /right's old0; an E handler
after both successful updates reads1 unless preceding work changed it. There is no all-pins barrier.

Retirement/retarget before a later update prevents patching the replacement as the old occurrence.
Already enqueued events keep exact frozen source/ancestor occurrence and activation membership,
with Contracts' normal invalid-target/failure behavior. Their event channels are classified at each
FIFO delivery from the canonical receiver view; channel changes by earlier handlers can affect a
later queued event, without adding new recipients. This differs from document-update dispatch,
which freezes its contract surface before the accepted mutation. SQL pages cannot split one
reaction's gas/rollback scope.

One inherited logical reaction origin at one causal position has one complete reaction per owned
consumer scope. Compose direct/transitive source projections and all eligible placements before
completing it; do not independently replay a flat batch at every graph edge.

### New placements

An existing event's frozen recipients do not gain a newly created /b. Initialization and required
lifecycle/update observations are synchronous. Historical epochs belong to the selected chronological
replay/attachment lane; they are not collapsed into one creator epoch. Creator reads see the installed
view, not a fabricated history-derived parent field. A selected FROM_NOW view must be justified at
its exact activation boundary. The kernel distinguishes these cases without waiting for the creator's
own future commit to satisfy initialization.

### Nested observations

Root can receive Source's original E without a new Parent emission. Parent's genuinely new F keeps
its own producing identity and enqueue site. The source operation's inherited reaction origin binds
the composed consumer execution; source and Parent receipts are not arbitrary competing jobs.

The cyclic extension uses distinct vertex-simple authored occurrence routes for each original event.
Keep alias multiplicity; do not return the same original occurrence to its emitter. A new authored
emission starts fresh routes under the same owned-operation gas limit. This is explicit finite graph
routing, not value deduplication or a rule that suppresses genuine workflow loops.

## 5. Cycles, failure and forward progress

A real returning workflow stays in one owned operation: shared gas, complete success or complete
rollback. Scope expands tentatively for a newly introduced return path; no fresh-meter feedback job
or partial authoritative commit is permitted. Passive cyclic representation rebinds retain their
separate authenticated same-epoch law without synthetic business events or gas resets.

### Selected managed-failure continuation

Terminal GAS_LIMIT_EXCEEDED or recognized deterministic RUNTIME_FATAL consumes the exact consumer
operation/delivery and records its outcome. Unclassified implementation/operational exceptions do not.
It leaves the actual successful view, business state and epoch unchanged. Independent Source and
healthy consumers remain committed. Missing evidence, provider failure and unknown commits remain
nonterminal; other processor statuses retain their exact operation-kind rules.

For failed r1 source0→1 and next r2 source1→2, with consumer still0:

1. Verify original contiguous source authority and terminal disposition of every intervening delivery.
2. Inside r2, explicitly align consumer0→source-before1 through a normal Document Update, its
   synchronous reactions, lifecycle effects, enqueue sites and gas.
3. Interpret r2's own observation program from1, including its1→2 work. Original r1 events are not
   replayed; original r2 payload/receipt remains1→2. No successful r1 import or source0→2 receipt exists.
4. Success publishes the complete actual consumer result. Alignment or r2 failure rolls everything
   back to0 and records r2's terminal failure. Later canonical work remains eligible.

With several producers, align at each source's first canonical Entry/consumption site, never in an
all-source prelude. For actual A0/B0 and next A1→2 before B1→2, both /a update callbacks still see B0;
only B Entry changes B. Do not move Root's own seed ahead of dependencies to manufacture an earlier
read: if canonically ordered after both sources it sees2/2. Alignment emissions use the same actual FIFO.

This replaces r15.8's direct0→2 shortcut. An r2 event before its first update reads1. A direct read
after failed r1 but before r2 still sees consumer0. A problematic update-to1 handler can fail again
during alignment: continued import means ordered attempts/accounting, not guaranteed business success.

Do not abandon the unattempted suffix of an import range. A fixed attachment cut completes with
explicit failures once every obligation is terminal; a SQL page is never a skip boundary. Later
detach/repair cannot overtake unresolved earlier work. Current successful-predecessor importer
constructors must be extended explicitly; the host must not bypass their guard.
See [20E](20-decision-traces.md#e-failed-r1-pending-r2-later-detach) and [22 §6](22-processing-kernel.md#6-import-failure-consume-one-operation-not-an-arbitrary-range).

## 6. Selected rules to verify during implementation

The algorithm choices are in22; the next step is implementing and testing those rules after user
approval, not asking for another selection among the withdrawn mechanisms. Required positives include
the FIFO/update controls, aliases, staged creation/history, canonical BIRTH, coupled feedback,
post-failure alignment, directed Timeline completeness and0/1/N publication.

A safety hold is not a passing positive. Structural documentation/schema checks do not prove the
algorithm. Phase1 library conformance precedes the target-shaped PostgreSQL host and integrated
measurements; physical tuning may change scheduling and storage, never the selected semantic result.
