# Failure and retry model

All public mutations are atomic inside one in-memory engine instance. Admission
prepares state before publishing. Dispatch snapshots mutable engine structures,
performs semantic work, then commits all deltas together. A pre-publication
failure restores document state, revision history, embedded links, route rows,
journal additions, receipts, catch-up cursors and logical time.

Timeline append advances sequence numbers and the logical clock only after the
exact Timeline Entry is valid and journaled. Retrying a rejected append therefore
produces the same coordinates and BlueId as an equivalent fresh engine.

Every committed delivery has a receipt. If state commits but the caller loses
the response, retry detects the receipt and does not invoke frozen PROCESS or
publish another revision. Duplicate journal admission is similarly idempotent.

Failures use `CoordinationException` and a machine-readable error code. Treat the
message as diagnostic text; branch on the code. Preserve the attached details in
logs while applying normal data-redaction policy.

This guarantee ends at the process boundary. A crash loses the in-memory journal
and receipts. There is no write-ahead log, distributed transaction, external
frontier import or cross-process exactly-once claim. Hosts needing durability
must persist authenticated inputs and define recovery before treating this RC as
a system of record.
