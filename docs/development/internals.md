# Internal design guide

The engine has one mutation owner: `DefaultCoordinationEngine`. Calls are
synchronized because the supported boundary is deterministic, single-process
coordination rather than parallel publication.

The append path validates and retains one exact request and Timeline Entry,
then commits its journal coordinates and logical clock. It does not scan
documents, encode a target document, or invoke PROCESS.

The Contracts drain path uses exact `OperationRouteIndex` rows to freeze the
direct deliveries for one canonical Timeline Entry. The Root feeder retains one
ordered lane over the union of each public Root and its active embedded
Timelines. `ContractsRootFeederWindow` records progress by exact event, source
order, lane, cohort, and invocation identity. A resource suspension blocks only
that lane; terminal disconnected cohorts are never re-driven.

Route publication is exact-key incremental. A resulting Contracts subscription
surface is reduced to exact active intervals: ADD begins after its triggering
source order, REMOVE disappears, and stable REPLACE retains its interval while
refreshing the header and checkpoint evidence. The route projection and its
generation publish before a cohort receipt becomes terminal.

`ProcessEmbeddedComponentIndex` is the cycle-capable active topology view. It
collapses exact directed SCCs, indexes weakly connected active cohorts,
and orders each condensation target before its sources with `DocumentId`
scalar ordering as the only tie-breaker. `ManagedOccurrenceInventory` is the
complete immutable source: it retains Contracts-owned active and
inactive occurrence rows, verifies loaded identity assertions through the
Contracts factory, and projects only active rows as component-index edges.
Retirement and later activation are separate atomic inventory transitions.
All authoritative rows, including inactive reservations, connect the affected
closure publication cohort. Building the active component index does not alter
that durable all-row cohort boundary.

`InMemoryDocumentStore` exposes the package-internal Contracts publication
seam. One attempt fences every selected document head by durable
epoch and exact BlueId, plus the occurrence-inventory and component-index
generations. It copies only selected `DocumentSession` images, stages the
Contracts-owned inventory, per-document graph generations, components,
subscriptions, routes, outbox, checkpoints, and receipt, then swaps one
immutable store state. A stale fence or staging failure leaves the published
state reference untouched. There is deliberately no global document-head
fence: transactions over disconnected cohorts may commit independently.

`EmbeddedOnlyLayoutBuilder` cuts only active `Process Embedded` fields. Ordinary
content remains inline; managed children are stored as whole exact objects.
Both explicit `paths` and direct stable-key members under `collectionPaths`
produce Contracts-owned occurrence rows. This storage representation is not
ambient processor context: each managed document is captured and processed
from its own exact state as Root.

`ContractsClosureAdapter` captures a connected affected closure, while
`ContractsRootFeederCoordinator` invokes each eligible cohort independently.
Inside an invocation, Contracts executes every selected document as a separate
ordinary work occurrence. Acyclic and cyclic documents use the same processor
and execution context; Coordination neither recurses into containers nor
implements a second cyclic scheduler.

The feeder's durable state retains lane-local resource barriers and terminal
frontiers across coordinator restart. A terminal publication receipt binds the
entry BlueId, source order, cohort/lane, and invocation identity. Restart
rebuilds route state from the durable store before receipt reconciliation, so a
crash after copy-on-write swap cannot execute the committed cohort again.

`CoordinationEngine.inMemoryContracts10(...)` is the public lifecycle boundary;
`DefaultCoordinationEngine.createContracts10(...)` implements it. The factory
requires exact final Language and Contracts SHA-256 artifact identities and
public Root lineages, owns the adapter runtime, and preserves feeder recovery
state when reconstructing coordinators from stores. It never invents release
digest placeholders.

`ContractsClosureAdmissionAdapter` owns the bounded all-new admission lane. It
verifies the exact `ADMIT_CLOSURE` operation, environment, execution policy,
public Roots, and complete member set, executes the real Contracts admission,
then stages expected-absent fences and every new `DocumentSession` in the same
copy-on-write publication as heads at epoch zero, inventory, components and
proofs, graph generations, subscriptions, checkpoints, outbox, and one typed
durable admission receipt. `NeedsResources` and non-committing results return
without mutation. A retry with the exact host publication identity restores
route-cache rows from durable sessions and returns the retained attempt without
executing Contracts again.

This 1.0 lane requires every member to be absent. An all-present request without
the exact receipt is stale, and mixed existing/new membership fails closed
because complete existing-head fences have not been supplied. Contracts-mode
`startDocument` also remains fail-closed: it never seeds a legacy singleton
`DocumentSession`/DAG and presents that state as admitted closure evidence.

## Legacy compatibility internals

`DefaultCoordinationEngine.create()` retains `SequentialDrainCoordinator`, the
acyclic child-first graph, iterative historical catch-up, `EmbeddedEpochInput`,
per-occurrence cursors, and document-local child/parent commits. These classes
remain for the earlier temporal profile and must not be used to infer Contracts
1.0 closure semantics.

Types in `blue.coordination.internal` are package-private except the concrete
engine factory target. Applications must depend on `blue.coordination.api`.
Test-only inspection and failure injection live in the test-fixtures artifact,
never the main JAR.
