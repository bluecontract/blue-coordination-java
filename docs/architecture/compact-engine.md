# Compact engine architecture

The supported runtime has three layers:

1. `blue.coordination.api` is the small immutable application boundary.
2. `blue.coordination.internal` owns one exact journal, whole-object store,
   document store, Channel route index, Process Embedded inventories, Root
   feeder, processor, and copy-on-write closure publication boundary.
3. `blue.coordination.processor` retains the semantic Contracts/BEX workflow
   closure used by the compact runtime and advanced processor registration.

Append validates the Timeline/provider/actor envelope, establishes one exact
Timeline Entry BlueId, stores the entry once, and publishes its journal
coordinates only after success. It records no recipients and invokes no
document processor.

The Contracts 1.0 path has one ordered feeder/window for each public Root. Its
source surface is the union of that Root Timeline and every active embedded
Timeline reachable from the Root, but exact route selection still identifies
the directly selected documents. One event may select several disconnected
Root lanes. A `NeedsResources` result holds only its own lane, so another public
Root can advance without overtaking work in the blocked lane.

The only dependency topology is derived from effective `Process Embedded.paths`
and `Process Embedded.collectionPaths`. One complete immutable
`ManagedOccurrenceInventory` retains the Contracts-owned active and inactive
typed rows. All authoritative rows determine the affected publication cohort;
only active rows project into the cycle-capable
`ProcessEmbeddedComponentIndex`. Coordination never reimplements occurrence,
binding, component, or invocation identities.

Every selected managed document crosses the same frozen Contracts processor
boundary as Root. A document receives its own state and exact work evidence; it
receives no containing-document, parent-path, or reverse-containment context.
Acyclic documents and members of cyclic components use the same document
processing function and closure execution context. Contracts alone schedules
active SCCs and validates their convergence.

One connected closure result publishes as one copy-on-write transaction. The
transaction CAS-fences every selected per-document head and the relevant
topology generations, then swaps copied session images together with
Contracts-owned occurrence rows, component states, subscription projection,
routes, outbox, checkpoints, and publication receipt. Each document retains its
own durable head and epoch. Disconnected cohorts share no document-head fence
and may commit independently.

Ordinary nodes and requests are never sent through a generic splitter. The
layout compiler retains one whole root shell and one whole object for each
effective managed Process Embedded document. The semantic root remains exact
and can be reconstructed from those content-addressed whole objects.

## Legacy compatibility path

`DefaultCoordinationEngine.create()` preserves the earlier acyclic temporal
profile for compatibility. Its `SequentialDrainCoordinator`,
`EmbeddingBinding`, `EmbeddedEpochCursor`, private `EmbeddedEpochInput`, and
child-then-parent document-local commits describe that legacy path only. They
do not define Contracts 1.0 closure execution. New Contracts hosts opt in with
`CoordinationEngine.inMemoryContracts10(Contracts10Configuration)`, supplying
exact final Language and Contracts artifact identities and the public Root
lineages.
