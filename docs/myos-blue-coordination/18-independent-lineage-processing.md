# Independent lineages and deterministic receipt delivery

> **Design baseline:** 15.12 · **Status:** Phase1 ownership implemented; general durable integration pending
> [Tutorial](tutorial/README.md) · [Causal model](17-causal-processing-model.md) · [API](05-poc-api.md)

## Decision and precedence

[22 — Selected processing kernel](22-processing-kernel.md) has highest semantic precedence. It
selects operation scope, observation-program replay, placement order, canonical origin and failure
alignment. Older all-pins/flat-replay/mandatory-relay shortcuts are not implementation defaults.

An Agreement shared by many Orders remains the primary target: compute its source work once and
let genuinely independent Orders consume retained evidence and commit their own reactions. A slow
or failing independent observer must not undo an independently committed source or healthy siblings.
The owning library must establish when that independence is valid. Physical factorization alone
does not create a new logical invocation, gas meter or rollback boundary.

This objective revises r14–r15.3's all-observer coupling, but does not authorize splitting an atomic
Contracts workflow or changing ancestor reads/events. Independent source/consumer ownership is an
explicit library-contract change implemented in Phase1; its verified scope and remaining integration
proof are recorded in [24](24-phase-1-library-summary.md). Same-logical-operation
eager/lazy implementations must agree; an unchanged final-receipt-only oracle is insufficient.
[19 — Edge contracts](19-semantic-edge-contracts.md) and [20 — Diagnostic decision traces](20-decision-traces.md)
record the selected evidence, grouping, birth and recovery traces and their conformance obligations.
They are not semantic profiles.

## 1. Semantic boundary

For the proposed independently owned case, the parent owns an occurrence observing exact source
history, not the authoritative source lineage. Reading it need not join all observers into one
transaction. That ownership rule must preserve the logical read/update/event observations; neither
one-hop event routing nor an always-final source pin follows from it.

The common Order → Agreement case has one-way dependency: Orders read Agreement and update their
own state. Agreement does not read those Orders or await their reactions. The library must support
this case with independent commits and bounded source-side work through 1,000/10,000/100,000 Orders. Independence
is proved from the permitted write/routing scope and dependency frontier, not guessed because no
feedback happened in one test. An attempted write through a managed source path cannot silently
mutate that source inside an allegedly local observer operation.

One logical Contracts operation remains atomic: its tentative changes, caused work/events, gas,
exact result and lifecycle effects succeed or fail together, including a real synchronous cyclic
workflow. Ordinary inline content remains inside that logical scope. The independent case needs
the complete SCC/readiness rule in22, not a generic transaction-group API. A first-creation companion
may idempotently promote canonical source candidates with a successful creator. Source initialization
has its own fixed identity/gas basis; a failed introducing operation publishes no new source authority,
events or charges, and cannot undo already independently authoritative X.

## 2. Local processing steps

1. **Process source.** Select Agreement's next eligible input from its complete ordered input and
   dependency evidence. Execute its local operation without running the observing Orders.
2. **Commit source.** At a proved independent boundary, atomically store its state/history,
   authenticated observable transition evidence or supported eventless result, outgoing event
   occurrences, and a durable delivery basis referring to that
   retained history. A lost notification must not lose the work. This transaction need not load
   Order bodies, enumerate all future reactions, or write N application definitions.
3. **Select consumer work.** For one Order and logical reaction position, identify the exact next
   due source evidence and complete eligible placements. Authenticate required intermediate/final
   views, original event/update provenance, producing operation and every occurrence cursor.
   A live lagging occurrence is not a new attachment.
4. **Process consumer.** Advance its observed source view and execute the required Order reactions
   against the correct chronological local pre-state. Reuse the source result; do not execute the
   Agreement's original business operation again. Preserve distinct paths and event occurrences.
5. **Commit consumer.** Atomically store Order's complete local result, its receipt/cursor and any
   outgoing obligations. Retrying the same operation cannot apply it twice. Other Orders commit
   separately; physical batching or worker order cannot change semantic grouping or gas.

One inherited reaction origin/position × owned consumer scope is the selected operation. Compose
converging source/parent projections and any own direct seed before settlement; receipt arrival order
does not create extra operations. Multiple placements retain identity, cursors and routing multiplicity.
The22 interpreter and per-placement synchronous updates, not SQL pages, determine observable order.
[20A](20-decision-traces.md#a-one-receipt-two-placements-all-read-pins-first) now retains the alias case
as a diagnostic and withdraws the all-pins barrier. [20B](20-decision-traces.md#b-creating-a-placement-while-consuming-a-receipt)
withdraws unconditional post-commit new-placement processing: frozen direct external recipients do
not exclude initialization or caused work required inside the introducing invocation. Exact queue,
dispatch-freeze, constructor/gas and ordinary acyclic fan-in rules must preserve the logical reference.

## 3. Order and visibility

Each authoritative lineage has an ordered committed history. The cross-lineage evidence is a graph
of producer/consumer dependencies, not one global sequence chosen by database commit timing.
Independent Orders may finish in either physical order with identical individual histories and
identities. Semantic publication has the same per-stream order and causal prerequisites; a global
physical append sequence is not a new portable event comparator.

An Order's next operation requires complete evidence that no earlier relevant direct input or source
consequence is missing. Use canonical microsecond Timeline order and its exact tie-break, source
receipt continuity, the relationship's semantic activation history and causal dependencies together.
Provider completeness alone does not prove that a dependency has been processed through that point.

Example: Agreement is 1 at T10 and 2 at T20; Order reads it at T15. Even if Agreement has committed
T20, Order must import only the source history due before its T15 read, observe 1, and apply T20
later. If Agreement has not yet established the required prefix, Order waits for that prefix, not
for every observer or an endlessly moving latest head. A source change without a matching event
still updates the pinned view; it does not invent a parent shadow-field assignment.

A final revision plus a flat event list is not sufficient observation evidence in general. If
Source Triggered E1 sets x=1 and E2 sets x=2, ancestor reads can be [1,2]; a permanent final2 pin
changes them to [2,2]. Immediate Document Updates also retain intermediate changes, including
0→1→0. Conversely one Handler may finish its patches to2 before draining both events, legitimately
producing [2,2]. Preserve actual Contracts transition boundaries, not universal emission-time
snapshots. See [21 §3](21-semantic-equivalence-and-source-reuse.md#3-a-final-receipt-is-not-complete-observation-evidence).

For the one-way case, Agreement may process its next input while old Order reactions are outstanding.
It retains the old revisions needed by those Orders. This advance is legal because there is no
returning dependency on their work, not because an unavailable worker was ignored. Noncommuting
inputs to the same target require canonical ordering and completeness before publication.

For one external cause targeting source and parent, preserve the current canonical seed order and
complete caused work before the next seed. Equal origin does not collapse distinct deliveries.
Do not replace that queue with independently sorted receipt batches or select an arbitrary DAG
path order. The factorized execution must prove its mapping to those logical positions, including
source Triggered work and ancestor observations. This is not a managed-before-all-history phase:
T20 visibility cannot precede the parent's original T15 event. Feedback remains caused work of its
logical operation, not a later input selected by whichever worker finishes first.

## 4. Delivery completeness and lifecycle

A source commit creates a durable obligation to make its retained result discoverable and process
all eligible reactions or record their specified terminal failure. **The host owns delivery liveness;
Coordination owns its semantic predicates.** MyOS maintains authoritative temporal dependency indexes
in the same transaction as document/occurrence history and the durable work basis. Efficient indexed
selection is an integration requirement, not a best-effort cache or a history-reconstruction fallback.

This matters when Agreement has reached T30 but an Order has not yet processed its own T20 removal
or attachment. The index is consistent with committed history, but it also needs explicit target/
lifecycle completeness to account for that earlier pending operation. A current-links-only list
cannot prove temporal eligibility. Query maintained activation/retirement, occurrence and progress
indexes instead of scanning history. Candidates may be queued provisionally, but no consumer
application commits until the required temporal eligibility is proved.

The durable delivery basis names the source receipt/range and lifecycle/discovery scope needed to
finish that proof. Distinguish two frontiers: a **processing frontier** proves a target's next action
has no missing earlier input/dependency; a **discovery frontier** proves all eligible lifecycle
decisions in the completion scope are accounted for. The latter includes accepted admissions or
reservations whose documents/occurrences are not materialized yet. These reservations and their
progress are indexed authoritative facts too; absence is not inferred from missing document rows.
A genuinely later admission is excluded by a proved closed cut,
not by worker arrival time. Selection uses the reaction's logical position; K100 importing E10 is
not a live E10 delivery through an occurrence that did not exist then.

Use an incremental closed-prefix/lifecycle index over that accepted scope, including unresolved
reservations and their exact releasers. Reuse coverage across source receipts; do not scan every
root, all documents of a user, or load all bodies on each append. Large scopes can require O(R) metadata coverage eventually;
report that cost instead of hiding it in an opaque witness. Source commit is not gated on completing
global discovery. The coverage proof waits for earlier lifecycle decisions, not the delivery whose
own eligibility it is proving; otherwise the scheduler would create a circular wait.

For a known occurrence, the proposed history/live handoff is a short scoped-fenced metadata operation:

1. Read retained source tail h and its append authority.
2. Under the same authority, atomically register occurrence/activation, immutable history selection,
   backfill through h, continuing subscription after h, and a durable lifecycle-log/wakeup entry.
3. If append raced with registration, validation/retry puts the receipt on exactly one side of h.
   Deduplicate overlap by exact delivery/group identities, not event values. No processing runs
   while holding this fence.

h is an acquisition boundary, not a semantic timestamp or portable identity. Backfilled future
receipts remain ineligible until their logical position. A mutable-table keyset scan alone can miss
a late registration behind its cursor: use the append-only lifecycle log plus a fenced closed range
or an equivalent audited range-generation protocol. Persist page registration and progress together;
after an ACK loss reconcile their existing receipt/fence, never emit a new semantic event per page.
The concrete completeness query and append/registration interleavings remain required proof vectors.

An occurrence can be logically subscribed yet physically behind. Caught-up-to-T is a proved prefix,
not a permanent READY flag or equality with the source's latest head. Later attachment retains its
own selection policy and causal position; lagging T1 attachment does not become a T10 attachment.
Removal/retargeting at T20 retires only the old occurrence's not-yet-due work. It preserves valid
earlier deliveries and other consumers, and creates a new identity/basis for a replacement.

If all-recipients completion is reported, it requires complete scope/lifecycle coverage through the
relevant cut and an exact disposition for every required reaction. A global terminal receipt/report
is optional host/test bookkeeping, not a Contracts step, epoch, gas charge or independent-commit gate.
Durable due-work obligations, local outcomes and no-gap discovery remain mandatory. An empty queue,
a drained current page, or 1000 acknowledgments without complete membership is not sufficient to
claim completion. Unresolved topology may keep that optional report open while independent work proceeds.
A genuinely later admission creates its own replay obligations; it cannot reopen an already proved
earlier live recipient set simply because it reconstructs older authored history.

Original provenance and completion ownership are distinct. Historical E10 consumed by a new K100
attachment may emit new work owned by K100 while preserving E10 provenance; it cannot reopen E10.
Every new obligation retains that explicit owner and its exact producer dependencies.

For nested embedding, preserve required ancestor observations, original provenance, composed paths
and queue order. [20C](20-decision-traces.md#c-nested-relay-is-not-a-new-emission) records the current
ordinary/managed gap and withdraws the mandatory intermediate-result relay. An original occurrence
can own its frozen deliveries without being re-emitted or recursively re-enqueued at each ancestor.
The22 observation program retains authenticated transition/admission sites and inherited reaction
origin. A final Parent revision plus its own emissions is not sufficient evidence for Root.

The retained source receipt and required exact versions cannot be collected while a live cursor,
pending discovery, allowed replay or recovery still needs them. The first POC keeps retained history;
an eviction/retention optimization needs a separately proved safe frontier, not a latest-state shortcut.

### Indexed selection at platform scale

MyOS chooses the concrete queues, temporal/index structures, partitioning and batching. The target
is millions of documents, potentially thousands active per user. Source-to-consumer indexes select
recipient pages; reverse dependency/waiter indexes select work released by a prefix/resource change;
ready/due-work indexes select runnable candidates and recover lost notifications or expired leases.
All are updated atomically with their authoritative effects. Normal scheduling must not iterate
over all documents, every active document of a user, or reconstruct complete histories.

Committing one Agreement transition persists resumable fanout authority without writing 100,000
jobs upfront. Later expansion and execution remain bounded and restartable. PostgreSQL query plans,
rows examined, lock contention and wake amplification are part of the POC evidence, including large
unrelated/dormant populations. See [06](06-storage-and-transactions.md#1002-indexed-work-selection-is-a-host-contract).
Coordination must expose the predicates and incremental effects needed for these access patterns;
the host must not invent business eligibility to make its queue efficient.

## 5. Failure, gas and identity

Agreement's successful local operation is durable independently of Order reactions. If Order 17
exhausts gas, retain its specified failure and unchanged local pre-state. Do not roll back Agreement,
erase successful siblings, retry a deterministic failure as a transient outage, or report successful
whole-cause settlement. Dependent work needs the defined terminal disposition; independent branches
remain eligible. Failure cannot mean permanent lineage death: authorized corrective work needs its own
scoped eligibility. Consumed external input, successful source cursor, business epoch and
representation-only rebind are different progress coordinates. [19](19-semantic-edge-contracts.md#2-failure-has-several-independent-progress-coordinates)
preserves the current external loop/detach recovery control and specifies the selected proposed
failed-import extension. A terminal r1 GAS_LIMIT_EXCEEDED leaves the successful source view unchanged but records
that delivery's handled outcome. Due r2 is then processed from the actual rollback state using the
continuous source history and intervening terminal dispositions. In the simple source0→1→2 example,
the consumer first aligns0→1 inside r2 with normal update reactions, then interprets r2's original1→2
program. Failed r1 events are not replayed and no source0→2 receipt is invented. Alignment can itself
fail and roll back r2; ordinary successful catch-up is not collapsed. This selected22 rule requires
owning-library implementation/conformance; the current import check is not enough.
Non-reading detach D@T30 follows earlier due r1/r2 terminal outcomes, not an implicit cancellation.
See [20E](20-decision-traces.md#e-failed-r1-pending-r2-later-detach).
This selected consumption covers gas and recognized deterministic semantic RUNTIME_FATAL, never
arbitrary exceptions or operational outages. Authenticate all lawful consumed gap outcomes, including
composite external-policy failures; other statuses retain their exact operation-kind rules. Required
finite recovery positives cannot pass on a safety hold. General in-band repair of an endless series
of valid imports remains deliberately outside this POC; host containment does not skip pending work.

Gas belongs to the logical Contracts operation, including all its synchronous caused work. Neither
pages nor receipt fragments nor cyclic hops may reset that meter. Genuinely independent consumer
operations need an explicit gas-ownership contract; total source/import/reaction gas is still
reported. Moving a cyclic workflow to fresh-meter operations is not an allowed optimization.

For the same authorized history/environment, initialization and semantic gas cannot depend on cache
misses, reconstruction or the first row insert. For genuinely independently committed source work,
its retained gas is not charged again as consumer execution. Reusing source computation inside the
same logical invocation must still preserve that invocation's required source charges; no cache
discount is implied. Different FROM_NOW bounds select different observer histories, not different
source births. The selected22 canonical FULL_HISTORY source origin and separately metered initialization
explicitly change current NEW_AUTHORED-in-parent ownership. Candidate publication is idempotent under
source identities and requires successful authorization; implementation must pass the selected
[BIRTH trace](20-decision-traces.md#d-initialization-policy-and-publication-birth-selection-remains-open).

Stable application, invocation and event identity must bind the semantic owner, exact cause/receipt,
occurrence/activation where applicable, local predecessor, defined scope and environment. They must
exclude unrelated sibling commits, database row IDs, scan page numbers and ambient global graph heads.
Current Contracts constructors bind closure membership and invocation-derived event identities;
their locality must be reviewed and changed in the owning library where needed, not wrapped in
host-invented replacement BlueIds. An availability retry cannot create another committed operation.

Whole-cause completion, when requested, is an aggregate proof over required causal work, not a
mandatory terminal receipt, transaction enclosing that work or permission gate for independent
progress. Report success, failure and unresolved work separately. Source commit latency, per-consumer
lag, fanout throughput/backlog, aggregate outcomes and publication lag are different measurements.

## 6. Feedback and cycles

An Order emission that really affects Agreement is caused source work, not replay of its old
operation. When it belongs to the same logical Contracts workflow, complete it under that invocation's
queue, shared gas and rollback before later external input. Do not let physical source progress
overtake an unresolved returning dependency, then backdate the effect or move it to an arbitrary
later round. The independent one-way case must not pay a barrier without that dependency.

There are two distinct issues: processing cycles, and exact cyclic representation/SCC finalization.
Neither licenses a global visited shortcut. Neither proves that every observing Order belongs in
one transaction. A required cyclic exact-value finalization may have a coupled scope whose proof
must be preserved; arbitrary per-member commits without valid BlueIds are not acceptable.

The22 scope starts with the complete SCC at the logical cut. Section22 §2.1 admits a new returning
same-origin dependency before its edge mutation: charge the check locally, then require the distinct
current group ledgers to fit their identical frozen limit. Accepted ownership is monotone with no
gas reset; rejection fails only the initiating current group with recognized semantic RUNTIME_FATAL.
First touch is readiness, not a join. Freeze seed order, preserve exact continuation-site views and
stable seed-local event IDs; never replay final members under retroactively shared gas. Fence all
affected candidate work before publication. Worker speculation does not define scope; one-way
observers remain independent. Vertex-simple routes
bound transport of one original event; new handler emissions create fresh routes and may form a
gas-exhausting business loop. Mixed historical/current cyclic representation must pass conformance;
neither a DAG test nor restoring all-observer atomicity substitutes for that proof.

## 7. Evidence and performance

Current Coordination's active observing-parent selection is in `ContractsClosureAdapter.java`
(`capture`, `connectedSelection`, `hasTypedIncomingDemand`). Current Contracts' complete invocation
shares gas/rollback (`ExecutionPolicy`, `DefaultClosureProcessor`, `ClosureRollbackResultAssembler`).
`ManagedEpochInvocationCapturer` already builds separate imports from retained source results, but
currently requires an inactive historically pending occurrence. These are inspected source facts,
not evidence that asynchronous live delivery is already implemented.

The planned library work must prove the independent-lineage path and reconcile scoped reads,
registration/activation, observable transition evidence, failure/gas ownership, event routing and identity constructors.
Preserve correct in-memory use of the same revised semantics. PostgreSQL supplies persistence and
recovery; it must not emulate the change by splitting a current closure transaction after execution.

Required controls include source commit before any consumer, crash after 17/1000 consumer commits,
one unavailable or gas-failing Order, reversed sibling completion order, T15 historical reads with
source already at T20, source-ahead lifecycle changes, and registration/append races. Measure source
processor calls independently of parent count, per-attempt memory, topology rows, fanout discovery,
consumer throughput and requested full-cause lag. Total necessary reactions remain O(N); source execution and
memory must not require all N parent bodies. N=1,000/10,000/100,000 does not carry a universal
seconds-scale total-drain requirement: prompt source commit is relative to its own required work,
while many real Order reactions may take longer. Bounded transactions, memory, acquisitions and
concurrency plus backpressure, fairness and tenant isolation keep that backlog stable. Vary P
placements in one consumer separately from N consumers; measure decode/hash/copy work in
addition to source calls. An indivisible group exceeding a physical cap names an actual capacity
releaser; unchanged retry is not progress. Numerical budgets come from declared POC runs, not prose.

Passive reference-only cycles must reuse joint exact-value finalization and same-epoch rebind rules,
not create synthetic business receipt echoes. A representation-only companion is authenticated in
the triggering complete result/delta, with unchanged business epochs/source positions and no new
business receipt, event/fanout or gas reset; the receipt/fanout rule has this explicit exception. Coupled representation publication does not by itself
merge SCC business handlers/gas/rollback. See [19](19-semantic-edge-contracts.md#5-passive-cyclic-representation-is-not-a-business-loop).

Phase1/2 implementation is authorized under [23](23-first-implementation-execution-plan.md).
This design revision itself makes no executable-test, benchmark or runtime-acceptance claim.
