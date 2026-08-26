# Failure and retry model

This page distinguishes the normal Contracts 1.0 SDK from the retained legacy
temporal compatibility profile. Application code uses the first boundary unless
it explicitly chooses the low-level legacy engine.

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

## Legacy temporal compatibility boundary

The paragraphs below describe the earlier document-local temporal coordinator,
including child-then-parent catch-up and `DrainBudget`. They are relevant only
to an explicit low-level compatibility or migration integration, not the
normal `BlueCoordination.inMemory()` Contracts publication model.

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

A process crash still loses the bundled in-memory stores in either profile.
This is therefore not a cross-process durability or exactly-once claim. A
durable adapter must persist the same typed document, journal, graph, barrier,
cursor, entry-frame, receipt, and commit-companion records and pass the
restart/store gate before a host can treat the engine as a system of record.
