# Internal design guide

The engine has one mutation owner: `DefaultCoordinationEngine`. Calls are
synchronized because the stated product boundary is deterministic,
single-process coordination—not parallel publication.

The append path builds and retains one exact request and one exact Timeline
Entry, then commits its journal coordinates and logical clock. It does not scan
documents or encode a target document.

The dispatch path uses `OperationRouteIndex` to select autonomous roots. Each
root is prepared once, crosses frozen Contracts once, and produces immutable
state/revision deltas. The engine publishes document state, links, route rows,
receipts, cursors, processor-created entries and logical time under one rollback
boundary.

`EmbeddedOnlyLayoutBuilder` cuts only active `Process Embedded` fields. Ordinary
content remains inline; autonomous children are stored as whole exact objects.
Historical catch-up consumes a child revision stream in source order through a
captured frontier. It does not replay an already managed child.

Types in `blue.coordination.internal` are package-private except the concrete
engine factory target. Applications must depend on `blue.coordination.api`.
Test-only inspection and failure injection live in the test-fixtures artifact,
never the main JAR.
