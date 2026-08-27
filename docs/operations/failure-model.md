# Failure and retry model

This page distinguishes the published Contracts 1.0 SDK, the unpublished rc.5
retained managed-epoch source profile, and the earlier low-level temporal
compatibility profile. None of the in-memory profiles is a fresh-process
durability claim.

## Contracts 1.0 SDK boundary

Append and connected affected-closure publication have separate atomic
boundaries. The engine does not copy and roll back the entire environment as
one transaction. One appended entry can also affect disconnected closures,
which retain independent results and may commit independently.

Timeline append advances sequence numbers and the logical clock only after the
exact Timeline Entry is valid and journaled. Retrying a rejected append
therefore produces the same coordinates and BlueId as an equivalent fresh
engine.

Every committed delivery has a receipt written with the state transition. When
the engine resumes or reconciles the same retained journal entry after state CAS
succeeds, the commit companion prevents another frozen PROCESS invocation or
revision. Duplicate admission of the identical complete provider entry is
similarly journal-idempotent by its exact BlueId.

That guarantee does not make a newly constructed targeted operation call an
application-level retry of the old entry. `operations().on(...)` builds a new
Timeline Entry with a new engine timestamp; recreating it after an ambiguous
`execute()` exception can apply the business intent again. Use `submit()` when
the caller needs the `EntryHandle` before processing, and do not rebuild a call
after a timeout or lost response until application state or an application-owned
idempotency key proves that doing so is safe. The current SDK has no durable
idempotency-key or cross-process result-lookup service.

The Contracts closure-publication store seam has a stronger, deliberately
narrow boundary for one affected closure. PROCESS checks every selected
epoch/BlueId head and the occurrence-inventory/component-index generations.
All-new `ADMIT_CLOSURE` instead fences every member as expected absent. The
resulting sessions or revisions, complete occurrence inventory, affected
component states/proofs, graph generations, subscriptions, public outbox,
checkpoint receipts, and typed publication receipt are built off-store and
become visible by one state-reference swap. Any stale CAS or injected pre-swap
failure publishes none of them.

A committing admission can durably swap the store immediately before an
in-memory route-cache publication fails. The durable admission receipt remains
authoritative; an exact retry rebuilds the missing route rows from retained
sessions and reports `ALREADY_PUBLISHED` without repeating Contracts. A
`NeedsResources` or non-committing result creates no receipt or state mutation.
Mixed existing/new admission and an all-present closure without the exact
receipt fail closed.

Operation-created managed expansion uses the same closure-publication seam.
The parent result, every new head, complete occurrence inventory, component
state, routes, checkpoints, events, and receipt become visible together. A
terminal validation or processing failure leaves no partial child or topology
expansion.

Normal SDK calls distinguish validation from processing outcomes. Invalid
owner combinations, incomplete managed-draft evidence, malformed arguments,
and unsupported activation can throw `IllegalArgumentException` or
`UnsupportedOperationException` before append and consume no journal sequence.
After append, processing state is represented by `EntryResult`,
`EntryDisposition`, and an optional stable `Diagnostic`. `APPLIED`, `NO_MATCH`,
`STALE`, `REJECTED`, and deterministic limit failures are terminal decisions;
`NEEDS_RESOURCES` and `BLOCKED` report that the lane has not reached a terminal
result. Branch on diagnostic code, not message text.

## Retained managed-epoch source profile (rc.5)

This profile is present in `3.0.0-rc.5` source only. It is staged,
unpublished, non-production, and depends on the verified immutable
Language/Contracts rc.23 stage. The published rc.4 JAR does not expose these
semantics.

An operation that installs a proven historical managed value commits its graph
change before catch-up. The consumer therefore has separate committed and READY
heads while an occurrence-specific plan advances from the admitted position to
the source frontier. Ordinary reads stay on the READY head; `auditDocument()`
and `auditManagedDocumentReadiness()` expose the committed head and active
barrier to operators. Later genuine source commits extend every active plan for
that source before the consumer can become READY. A dependent direct consumer
entry cannot overtake the barrier, while disconnected lanes may progress.

Before every managed PROCESS call, Coordination opens the exact public source
receipt and its typed Contracts transition receipt and cross-checks document,
epoch, before/after BlueIds, original cause, complete duplicate-preserving event
occurrences, admitted gas, and receipt identities. Failure is classified before
PROCESS:

- a missing source epoch produces a same-cursor `WAITING_FOR_HISTORY` plan and
  barrier with `MANAGED_EPOCH_RECEIPT_MISSING`;
- when the source after-state is a cyclic member, a separately retained complete
  successor proof is mandatory. A definitive proof miss produces
  `WAITING_FOR_HISTORY` with `MANAGED_EPOCH_CYCLIC_PROOF_MISSING`, and a
  temporary provider failure produces `WAITING_FOR_HISTORY` with
  `MANAGED_EPOCH_CYCLIC_PROOF_UNAVAILABLE`;
- a cyclic proof that is present but does not authenticate the claimed member
  identity and exact after-body produces `BLOCKED` with
  `MANAGED_EPOCH_CYCLIC_PROOF_INVALID`;
- a missing transition receipt, or mismatched source, epoch, before/after
  state, cause, event, gas, or receipt identity, produces a same-cursor
  `BLOCKED` plan and barrier with its typed evidence code; and
- none of these evidence failures changes consumer heads or emits an
  application receipt. The failed consumer is removed from due selection so
  independent consumers can continue; same-consumer barrier siblings cannot
  overtake it.

A complete but non-committing Contracts attempt also leaves the consumer
revision, plan cursor, barrier, and application receipt unchanged. The exact
work remains retryable; the consumer is excluded only for the remainder of that
drain. Cyclic affected closures use the ordinary Contracts cyclic processor and
the same deterministic gas/convergence failures. Catch-up never retries source
INITIALIZE, a source Timeline Entry, a source Operation/local handler, or source
PROCESS/public-outbox work.

The complete cyclic proof is provider-completeness evidence alongside, not
inside, `ManagedEpochReceipt`. This includes a cyclic same-state eventless
application epoch later consumed as source. Missing, unavailable, and invalid
proof outcomes are detected before the PROCESS counter advances; they preserve
consumer history/receipts and the exact plan cursor through same-live
`restartFromStores()` reconstruction.

A suspended managed application can retain several exact resource demands.
`ManagedEpochApplicationAttempt.automaticRetryCount()` records the bounded
automatic expansion already consumed, and each
`ManagedOccurrenceResolutionIssue` binds one demand identity to a closed
matching status. Persist those typed values and treat diagnostic text as
display-only. Missing exact content may be registered through the host's
immutable exact-node provider before retrying the same work; ambiguity,
unproven history, selector mismatch, and invalid authored content are not
uploadable-content waits. Supplying content never authorizes a direct consumer
patch or a replacement operation.

The supported same-epoch source representation change is bounded to the
eventless, finite two-member cycle formed by the managed application. The exact
Contracts result and commit companion must authenticate the distinct source
member's shared cyclic representation with unchanged source epoch and route
surface. It rewrites no source revision, receipt, or event. A merge with an
already cyclic multi-member source component fails closed if it would require a
source epoch to advance or be reinterpreted. Direct targets, eventful changes,
wrong invocation evidence, missing receipt proof, malformed component
transitions, and larger unproven merges reject the whole publication and leave
the prior representation authoritative.

A successful managed application atomically writes its consumer revision,
complete epoch evidence, plan cursor, barrier/readiness state, closure
publication receipt, and `ManagedEpochApplicationReceipt`. If the state swap
succeeds but route publication loses its response, the next attempt recognizes
the committed work, reconstructs the routes, and reports a replayed application
without calling PROCESS again. The same invariant holds across control-plane
reconstruction in one live engine. It does not hold after losing the in-memory
stores; a durable adapter must persist the complete record set atomically. For
cyclic source successors, that record set includes the authenticated complete
proof and exact member body, and startup must restore that provider evidence
before making retained work runnable.

`drain(DrainBudget)` pauses at deterministic selected-entry and committed-
transition boundaries. A paused drain is not a failure and a later drain
continues from the retained plan cursor. See
[Retained managed-epoch catch-up](../semantics/retained-managed-epoch-catch-up.md)
for matching, plan, ordering, receipt, and audit details.

## Earlier low-level temporal compatibility boundary

The paragraphs below describe the earlier document-local temporal coordinator
using `EmbeddingBinding` and `EmbeddedEpochCursor`. They are relevant only to
an explicit low-level compatibility or migration integration, not the normal
`BlueCoordination.inMemory()` Contracts publication model or the rc.5 managed
receipt/plan/barrier profile.

Child and parent synchronization are intentionally separate commits. If a
child epoch commits and parent application fails, the child remains committed,
the parent cursor remains behind, and the parent stays `CATCHING_UP` or
`BLOCKED`. Retry applies the existing child epoch only; it does not reprocess
the source Timeline Entry. Required unfinished synchronization prevents later
dependent entries from overtaking it.

An attachment-specific admission plan is staged outside document state and is
consumed only inside the graph-publication savepoint. Failure before publication
restores it; failure after a durable document transition retains the committed
state and reconciles only the missing publication/cursor work.

A document transition that may change its managed `Process Embedded` graph is
committed as `CATCHING_UP`, not `READY`. The session records separate committed,
ready, and graph-published epochs. Graph and route publication may then succeed,
pause, or fail without making the new state application-readable. `document()`
requires all of the following: `READY`, ready epoch equal to committed epoch,
graph-published epoch equal to committed epoch, no open barrier, no pending
admission, exact graph/state agreement, and every child cursor at the child
current epoch. `auditDocument()` remains the explicit way to inspect a committed
but not-yet-ready state.

`drain(DrainBudget)` uses the same retained state as failure recovery. Reaching
a selected-entry or committed-PROCESS limit is a normal paused receipt, not a
failure. The receipt owns only outcomes committed during that call, and a later
drain resumes the open frame. INITIALIZE and an individual frozen PROCESS call
remain atomic and cannot be interrupted to meet a wall-clock deadline.

Low-level failures use `CoordinationException` and a machine-readable error
code. Treat the message as diagnostic text; branch on the code. Preserve
attached details in logs while applying normal data-redaction policy.

The bundled host supports control-plane reconstruction inside the same live
engine while its document, journal, and scheduler state remain in memory.
Reconstruction discards transient outcomes and publication savepoints, rebuilds
route rows from exact document state, validates retained graph/cursor/barrier
state, and resumes a retained entry frame. Tests cover both
child-committed/parent-pending and parent-committed/cursor-pending recovery
without repeating initialization or source PROCESS. This test-only seam does
not construct a fresh engine instance or reload serialized state.

## Shared process boundary

A process crash still loses the bundled in-memory stores in every profile.
This is therefore not a cross-process durability or exactly-once claim. A
durable adapter must persist the same typed document, journal, graph, barrier,
cursor, entry-frame, receipt, and commit-companion records and pass the
restart/store gate before a host can treat the engine as a system of record.
It must also persist and restore exact provider-completeness evidence, including
each cyclic successor's complete proof; `restartFromStores()` does not exercise
that fresh-process restoration boundary.
