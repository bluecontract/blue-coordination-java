# Internal design guide

The engine has one mutation owner: `DefaultCoordinationEngine`. Calls are
synchronized because the supported boundary is deterministic, single-process
coordination rather than parallel publication.

The append path validates and retains one exact request and Timeline Entry,
then commits its journal coordinates and logical clock. It does not scan
documents, encode a target document, or invoke PROCESS.

The drain path uses an ordered journal cursor and `OperationRouteIndex` to
select the canonical next entry and its direct document targets. A frozen graph
snapshot supplies the ancestor closure. `SequentialDrainCoordinator` closes one
child-first entry frame before selecting another external entry; append order
and caller choice are not semantic order.

Route publication is exact-key incremental: a staged document surface is
diffed against its published `RouteKey` rows, unchanged rows retain identity,
and only changed keys are removed or inserted. Graph publication compares
already-cached direct occurrences; unchanged topology opens no reconciliation
or publication savepoint. A topology change replaces only the affected parent
bucket, changed child reverse buckets, and changed binding records, while all
unchanged bucket lists and binding objects remain structurally shared.

`EmbeddedOnlyLayoutBuilder` cuts only active `Process Embedded` fields. Ordinary
content remains inline; managed children are stored as whole exact objects.
Both explicit `paths` and direct stable-key members under `collectionPaths`
produce bindings. Binding topology is immutable; per-occurrence epoch cursors
are persisted separately.

Historical catch-up is an iterative feeder, not a precomputed list. After every
committed historical step it refreshes subscriptions, graph bindings, nested
barriers, and completeness evidence before selecting the next candidate. Parent
synchronization uses exact processor-owned `EmbeddedEpochInput`; the host never
pre-replaces the child field and never fabricates an internal Timeline Entry.

Atomicity is document-local. A successful child epoch remains committed if a
later parent application fails. Receipts and cursors publish with the document
transition they describe, and commit companions reconcile an uncertain return.
Do not reintroduce a whole-engine snapshot/restore transaction.

Types in `blue.coordination.internal` are package-private except the concrete
engine factory target. Applications must depend on `blue.coordination.api`.
Test-only inspection and failure injection live in the test-fixtures artifact,
never the main JAR.
