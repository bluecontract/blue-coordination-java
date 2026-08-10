# Failure and retry model

Append and each document transition have separate atomic boundaries. Admission
prepares one document before publication. A document transition atomically
commits its exact new state and epoch, emitted events/outbox, subscription and
`Process Embedded` deltas, external delivery progress or parent cursor,
idempotency receipt, and commit companion. The engine does not copy and roll
back the entire environment as one transaction.

Timeline append advances sequence numbers and the logical clock only after the
exact Timeline Entry is valid and journaled. Retrying a rejected append
therefore produces the same coordinates and BlueId as an equivalent fresh
engine.

Every committed delivery has a receipt written with the state transition. If
state CAS succeeds but the caller loses the response, retry reconciles the
commit companion and does not invoke frozen PROCESS or publish another
revision. Duplicate exact journal admission is similarly idempotent.

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

`drain(DrainBudget)` uses the same retained state as failure recovery. Reaching
a selected-entry or committed-PROCESS limit is a normal paused receipt, not a
failure. The receipt owns only outcomes committed during that call, and a later
drain resumes the open frame. INITIALIZE and an individual frozen PROCESS call
remain atomic and cannot be interrupted to meet a wall-clock deadline.

Failures use `CoordinationException` and a machine-readable error code. Treat
the message as diagnostic text; branch on the code. Preserve attached details
in logs while applying normal data-redaction policy.

The bundled host supports control-plane reconstruction inside the same live
engine while its document, journal, and scheduler state remain in memory.
Reconstruction discards transient outcomes and publication savepoints, rebuilds
route rows from exact document state, validates retained graph/cursor/barrier
state, and resumes a retained entry frame. Tests cover both
child-committed/parent-pending and parent-committed/cursor-pending recovery
without repeating initialization or source PROCESS. This test-only seam does
not construct a fresh engine instance or reload serialized state.

A process crash still loses those in-memory stores. This is therefore not a
cross-process durability or exactly-once claim. A durable adapter must persist
the same typed document, journal, graph, barrier, cursor, entry-frame, receipt,
and commit-companion records and pass the restart/store gate before a host can
treat the engine as a system of record.
