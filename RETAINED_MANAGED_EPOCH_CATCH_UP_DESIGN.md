# Retained managed-epoch catch-up design

## Scope and invariants

This candidate starts from the published `blue.language` `3.1.0-rc.22` and
`blue.coordination` `3.0.0-rc.4` sources. It adds a Contracts processor receipt
surface and Coordination receipt-driven catch-up. It does not change the Blue
Language value model, mapping, BlueId calculation, BEX, Repository semantics,
or MyOS.

The source managed document has one stable `DocumentId`, one session, and one
ordered epoch history. Catch-up never reruns source initialization, source
Timeline Entries, source Operations, source handlers, or the source public
outbox. It neither republishes the source's original public event nor asks a
host to push content directly into a consumer process. It applies immutable
source epoch receipts through independently versioned Process Embedded
occurrences.

Process Embedded remains the only dependency graph. Every catch-up step uses
the ordinary affected-closure processor, including SCC planning, cyclic proof
verification, gas accounting, and atomic rollback.

## Evidence layers

Contracts owns generic transition truth:

- `ManagedDocumentTransitionReceipt` authenticates one accepted managed
  transition, its before/after exact identities, complete ordered Root-boundary
  occurrences, original cause, and admitted gas.
- `ManagedRootEventOccurrence` preserves exact event value, event BlueId,
  invocation-global occurrence ordinal and identity, per-transition ordinal,
  and whether the source Root was public.
- `ClosureProcessResult` and `ClosureCommitCompanion` bind the canonical ordered
  receipt aggregate. Rollback exposes no receipts.
- `ManagedRevisionCause` carries an authenticated source transition receipt and
  delivers its events through exactly one target occurrence. When the source
  successor is a cyclic member it also carries the separately acquired complete
  successor proof. Imported source events do not become public output; only new
  consumer-Root reactions do.

Coordination owns stable source history:

- one immutable `ManagedEpochReceipt` for every committed source epoch;
- exact epoch number and revision kind;
- exact after value plus optional before value;
- original cause/order evidence;
- Contracts transition receipt and commit-companion identities;
- complete duplicate-preserving events and gas.

The `ManagedEpochReceipt` does not embed cyclic provider proof. A cyclic
`afterBlueId` additionally requires an authenticated complete `CyclicSetProof`
retained with the exact successor member body at the provider-completeness
boundary. The proof does not change the established receipt/cause identities;
it is immutable invocation evidence that Contracts independently verifies.

An event-emitting accepted transition advances the Coordination epoch even when
its before and after BlueIds are equal. A failed or rolled-back transition
creates no epoch and no receipt.

## Identity rules

All new semantic identities use closed, versioned, domain-separated RFC 8785
JSON constructors and SHA-256. No identity is constructed by concatenating
fields. Host-supplied receipt identities are recomputed and verified.

The constructors bind, at minimum:

- managed epoch receipt: source `DocumentId`, epoch, kind, before/after exact
  identities, original cause/order, Contracts receipt identity, companion
  identity, ordered complete events, and gas;
- stable catch-up-plan definition: consumer, target
  occurrence/path/generation, source, admitted epoch/state, and barrier cause;
- catch-up-plan snapshot: stable plan and barrier identities, next-source
  cursor, required frontier, status, and wait evidence;
- stable barrier definition: consumer and cause order;
- barrier snapshot: stable barrier identity, canonical member-plan identities,
  status, and wait evidence;
- work: plan, source receipt, source epoch, consumer, target occurrence,
  activation generation, expected committed head, and graph generation;
- application receipt: work identity, Contracts invocation/result/companion,
  committed consumer revision, and resulting cursor.

The stable plan and barrier identities deliberately exclude mutable progress,
frontier, membership, status, and wait fields. Those fields are authenticated by
`snapshotIdentity`; extending a frontier, advancing a cursor, adding a plan to a
barrier, or changing a wait/status therefore changes the snapshot without
renaming the stable plan or barrier.

## Matching

The existing `ManagedLineageIndex` remains authoritative. Resolution is bounded
to exact index buckets and follows this precedence:

1. Verify an explicit stable `DocumentId` selector when present.
2. Reject cross-lineage ambiguity.
3. For one lineage, a unique current-state match wins even if that BlueId also
   occurred historically.
4. Otherwise evaluate authored initial (`-1`), initialized epoch zero, and
   retained epochs.
5. Select one unique retained epoch.
6. Reject repeated historical matches as `AMBIGUOUS_MANAGED_EPOCH` unless an
   exact typed selector supplies the epoch.
7. With no known lineage, a complete authored pre-initialization value may
   create a new lineage.
8. With no known lineage, an initialized/progressed value is
   `UNPROVEN_MANAGED_HISTORY`.

Inline, pure-reference, and verified partial materializations are equivalent
after resolving to the same exact BlueId. Missing exact content is demanded
before lineage choice. `ManagedEpochSelector` is an additive advanced SDK value
binding source `DocumentId`, source epoch, expected state BlueId, and target
occurrence path; normal callers do not author binding arrays.

## Attachment, plans, and barriers

When a graph-changing transition activates an occurrence at a historical state,
one publication atomically commits:

- the consumer transition and new committed head;
- the exact occurrence lineage and activation generation;
- a `ManagedOccurrenceCatchUpPlan` with occurrence-specific cursor;
- an extendable `ManagedCatchUpBarrier` for every plan created by the cause;
- consumer status `CATCHING_UP` and exact wait evidence.

The consumer ready head stays at the previously ready epoch. Normal reads use
that ready head; advanced audit may inspect committed-but-not-ready state.
When no catch-up is required, committed and ready advance together.

A plan records admitted source epoch (`-1` is the authored-initial selection
sentinel, never a receipt epoch), admitted state, next source epoch,
required-through epoch, target occurrence identity/path/generation, source and
consumer IDs, cause, status, and optional waiting code/message. Durable receipts
start at initialization epoch `0`. Closed statuses are `PENDING`, `RUNNING`,
`WAITING_FOR_HISTORY`, `BLOCKED`, `COMPLETE`, and
`CANCELLED_OCCURRENCE_RETIRED`.

One cause may add several children. Its barrier prevents readiness until every
required plan is terminal. A later source epoch extends active plans before a
dependent consumer entry can overtake it.

## One catch-up step

The normative unit is one source epoch receipt applied through one exact active
occurrence. The scheduler:

1. Reads the plan and exactly the next source receipt.
2. Verifies receipt continuity, identity, source lineage, target generation,
   expected target state, consumer committed head, and graph generation. For a
   cyclic successor it also opens the separately retained complete proof and
   authenticates it against the claimed member and exact after-body.
3. Constructs a Contracts managed-revision invocation with the complete current
   affected closure and frozen processing policy.
4. Contracts replaces the occurrence state, creates the normal embedded update,
   injects each source event through that occurrence in exact order and
   multiplicity, and runs ordinary closure/cyclic processing. Typed occurrence
   demands raised by a consumer handler use the same bounded automatic
   resolution/retry loop as an ordinary publication.
5. One transaction commits resulting consumer revisions, occurrence inventory,
   graph/component changes, application receipt, cursor, plan/barrier status,
   committed/ready heads, and public events newly emitted by consumer Roots.

The resulting consumer revision kind is `EMBEDDED_REVISION_APPLICATION`. It is
retained even when the final consumer BlueId is unchanged. The source receipt
may be reused through many occurrences, but each application has a distinct
canonical work identity and application receipt.

An eventless same-state cyclic application therefore creates a real later
source epoch even though its before/after BlueIds are equal. A downstream
occurrence may consume that epoch only with the same retained complete proof;
the earlier source is still never processed again.

## Deterministic scheduling and locality

Due work is totally ordered by registered semantic keys:

1. barrier cause order;
2. source external/causal order;
3. source `DocumentId`;
4. source epoch;
5. consumer `DocumentId`;
6. target absolute path;
7. activation generation.

Wall clock, thread arrival, hash iteration, database sequence, and session UUID
are not tie-breakers. Work from all plans in one barrier is merged under this
order. Scheduling candidates expand through the impacted consumer index to all
of that consumer's active plans; this is a bounded indexed read, not a global
scan. A `WAITING_FOR_HISTORY` or `BLOCKED` sibling gates new due work for that
consumer, while unrelated consumers continue.

Bounded drains retain a fair turn between canonical external work and managed
epoch work. When both lanes remain runnable, repeated `DrainBudget(1, 1)` calls
alternate committed opportunities; an empty managed lane falls through to the
external lane. Genuine source commits therefore extend active frontiers while
catch-up cursors also make bounded progress. A finite source stream eventually
settles and permits READY; an indefinitely continuing stream remains truthfully
`CATCHING_UP`. Cohorts containing the catching-up consumer stay in their
lane-local feeder window, so direct consumer work never overtakes. A consumer
whose managed attempt rolls back is excluded only for the remainder of that
drain call; other consumers continue.

Indexes are maintained by source receipt `(DocumentId, epoch)`, plan identity,
consumer, source, and target occurrence. A catch-up selection opens no unrelated
session or history; the 1,000-document locality fixture asserts zero unrelated
document scans. Producer-backed counters separately report exact receipt, plan,
barrier, and application-receipt rows opened; combined source evidence opens
the public epoch receipt and its Contracts transition from one physical row.

## Readiness and no overtaking

Coordination exposes committed head, ready head, status, wait reason, and active
barrier identities. While required catch-up is incomplete:

- later direct consumer entries remain ordered behind due catch-up;
- later managed inputs for that consumer remain behind the same barrier;
- unrelated sources and consumers continue;
- application reads observe only the ready head.

When all required plans complete, one atomic promotion advances ready to the
committed head and sets `READY`. Missing receipt evidence produces
`WAITING_FOR_HISTORY`; invalid/tampered evidence blocks fail-closed.

## Failure, restart, and response loss

Gas, runtime, portable-limit, or proof failure leaves source state, consumer
heads, occurrence cursor, plan frontier, and receipt stores unchanged. Retry
reuses the same receipt, work identity, and frozen policy.
`ManagedEpochApplicationAttempt` is returned with the drain even when the
attempt rolls back, so callers can compare the exact frozen work and Contracts
failure evidence across retries. A failed consumer does not prevent unrelated
parent plans from committing.

Cyclic proof acquisition is classified before PROCESS with a closed vocabulary:

- `NOT_FOUND` is `WAITING_FOR_HISTORY` /
  `MANAGED_EPOCH_CYCLIC_PROOF_MISSING`;
- `UNAVAILABLE` is `WAITING_FOR_HISTORY` /
  `MANAGED_EPOCH_CYCLIC_PROOF_UNAVAILABLE`; and
- `INVALID_EVIDENCE` is `BLOCKED` /
  `MANAGED_EPOCH_CYCLIC_PROOF_INVALID`.

Each outcome leaves the cursor, frontier, consumer head, source/consumer
history and receipts, application registry, and PROCESS counter unchanged.

A suspended attempt also retains its automatic retry count and one typed
`ManagedOccurrenceResolutionIssue` for every unresolved managed occurrence
demand. Each issue is joined to the processor resource demand by exact demand
identity and uses a closed status vocabulary; diagnostic text is display-only.
One application may demand several exact BlueIds. A host persists the complete
set, registers each verified immutable value through its exact-node provider,
and retries the same work only after the required set is available. Supplying
content does not create a new operation, mutate a cursor, or bypass ordinary
closure processing. Ambiguous lineage/epoch and explicit-state mismatches are
not treated as uploadable missing content.

The publication receipt stores the exact Contracts terminal attempt and
companion identity. After a store swap followed by response loss, restart finds
the application receipt and marks work complete without invoking Contracts or
source PROCESS again. Recovery reconstructs plans, indexes, barriers, and
readiness from the accepted deterministic ledger/store state and verifies every
identity before exposing READY state.

That reconstruction statement covers only the same live in-memory engine whose
whole-object/proof store survived. It is not a fresh-process persistence claim.
A durable host must atomically retain every cyclic successor proof with its
exact member body and restore verified provider completeness before re-enabling
the associated work.

An evidence failure removes the work identity from the pending-plan and due
indexes but retains its immutable registered-work audit row. When exact evidence
is repaired, re-registering that same unapplied identity atomically restores
both scheduling indexes without replacing the audit row. A partial index or a
slot pointing at another work identity fails closed.

## Graph evolution

- Detach retires the occurrence generation and atomically marks each incomplete
  plan, including `BLOCKED`, `CANCELLED_OCCURRENCE_RETIRED`; no later receipt is
  delivered through it. An already `COMPLETE` plan and snapshot remain unchanged
  immutable audit evidence.
- Re-add allocates a fresh generation and plan.
- Retarget retires the old plan and resolves the new source from its supplied
  exact state; cursors do not migrate.
- A handler-created historical occurrence for an existing lineage uses the typed
  demand/resolution loop and extends its owning barrier only after exact
  evidence resolves. The new plan, barrier extension, consumer revision,
  occurrence cursor, graph/components, and epoch/application receipts share the
  same atomic publication.
- Creating a genuinely new authored lineage from a nested handler while catch-up
  is running is not supported by this candidate; it remains fail-closed.
- Catch-up treats an already authenticated source-termination receipt as the
  final retained epoch and completes after application. The executable source
  fixture stages that authenticated terminal receipt internally; it does not
  claim end-to-end coverage of the production operation-time termination lane.
  A separate production characterization proves that lane currently rejects
  atomically with `RUNTIME_EXECUTION_FAILURE` because rc.4 requires the
  lifecycle/marker batch lane, which remains outside this catch-up round.
- Cycle formation, merge, split, and dissolution use the normal affected-closure
  planner and proof/finalization path, with no catch-up-specific shortcut.

### Same-epoch component representation rebind

The supported positive boundary is the finite two-member cycle formed by one
retained application: the consumer advances normally while Contracts may
finalize the distinct, indirectly reached source member into the shared cyclic
representation at that source's unchanged local epoch. Coordination accepts
that representation-only update only when the exact Contracts result and commit
companion authenticate an eventless transition with different before/after
BlueIds and an unchanged route surface. The transaction atomically updates the
source's current component representation, component state, lineage current
BlueId, and continuation cursor. It does not append or rewrite a source
revision, source epoch, managed epoch receipt, or source event.

This is not authority to rewrite arbitrary indirect peers. In particular, a
retained application that would merge its consumer with an already cyclic,
multi-member source component and would require a source member's semantic
epoch to advance or be reinterpreted fails closed with no publication. Direct
Timeline targets, eventful transitions, unverified transitions, malformed
component evidence, and larger merges outside the proven representation-only
boundary remain rejected.

## Public and audit surfaces

`DocumentRevision` additively exposes an optional complete
`ManagedEpochReceipt`; the value is present for revisions retained as managed
semantic epochs, and absence is not permission to reconstruct evidence.
Advanced SDK audit provides immutable values for source receipts, individual
plans, occurrence catch-up, consumer plan lists, application receipts,
barriers, and committed/ready readiness evidence. `DrainResult`, `EntryResult`,
and closure/document snapshots expose typed status and progress without parsing
exception text. In particular, every managed application attempt exposes the
frozen work, processor attempt, publication/replay flags, optional committed
receipt, automatic retry count, and typed unresolved-demand issues. This is the
host-persistable resume surface for several BlueIds in retained work. Ordinary
`EntryResult` closure demands carry the same closed matching classification and
automatic retry count, so hosts can use one typed resource-demand persistence
path for entry processing and managed applications.

This feature is not Timeline-provider catch-up, does not replay source Timeline
Entries, does not reprocess the source document, and does not reconstruct the
consumer in the historical past.
