# Historical catch-up

An attachment captures exact cause evidence and an exclusive canonical cutoff
`T`. A verified frontier `F` defines the historical interval `F < entry < T`;
the attachment entry itself is never delivered to a newly activated child.
Initialization is a processor-managed prerequisite epoch, not a provider event:
it commits and reaches every containing occurrence before historical replay.

Admission evidence can be bound to one exact `(parent DocumentId, absolute
occurrence path)`. The binding records the child DocumentId, supplied-state
BlueId, admitted epoch when required, activation mode, verified frontier,
proof identity, and attachment-entry identity. A broad child default remains a
convenience only; an exact occurrence plan is authoritative and is consumed
atomically with graph publication.

State identity does not always identify history position. If a child returns to
an earlier exact state, the repeated BlueId can name several epochs. Admission
without an explicit epoch therefore fails closed once recurrence is known;
supplying an epoch validates that exact retained revision in constant time.

Existing children are never reprocessed for epochs already committed: each
parent consumes missing child epochs through its own cursor. Unseen children
process eligible historical source entries exactly once. Entries admitted after
a closed local frontier remain outside it even if their human timestamp is
older; canonical provider order and completeness evidence are authoritative.

Several children introduced by one parent transition share one extendable
barrier. Their initialization and historical work merge by source order, depth,
canonical path, DocumentId, and epoch identity. Replay is iterative: after each
transition the runtime reconciles Timeline subscriptions, `Process Embedded`
bindings, nested barriers, and source completeness before asking for another
candidate. An unmet prerequisite defers progress; it is not automatically a
semantic failure.

A parent becomes `READY` only when every child is initialized or terminated,
every eligible interval is proven complete, all nested barriers are complete,
every child epoch is terminally processed, and every parent cursor and embedded
state agree through the cutoff. A later external entry cannot overtake this
work.

Top-level admission uses the same advancement engine. `FULL_HISTORY` starts at
the complete beginning, `FROM_FRONTIER` requires verified nonzero evidence, and
`FROM_NOW` establishes a birth frontier without replay.

A work-bounded drain may pause an open entry or catch-up frame between frozen
PROCESS commits. The frame, barrier, graph generation, document-local cursors,
and committed receipts remain retained, so continuation does not replay a
completed child or parent transition. The budget is a deterministic work bound,
not a wall-clock timeout and not a preemption point inside PROCESS or INITIALIZE.

The bundled provider proves only its revisioned in-memory journal. Its closed
historical feeder distinguishes an eligible entry, proven complete/empty,
temporarily unavailable, and invalid evidence; unavailable is never interpreted
as an empty interval. Inside the same live engine, the in-memory coordinator can
be reconstructed around retained document, journal, and scheduler state and
resume exact entry-frame/barrier/cursor progress. This is not fresh-engine or
serialized-state recovery. General external frontier import and cross-process
recovery remain pending until a durable provider supplies revision-complete
cursors and passes the restart/store release gates.
