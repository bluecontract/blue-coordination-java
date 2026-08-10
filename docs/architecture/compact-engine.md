# Compact engine architecture

The supported runtime has three layers:

1. `blue.coordination.api` is the small immutable application boundary.
2. `blue.coordination.internal` owns one exact journal, whole-object store,
   document store, Channel route index, Process Embedded graph, sequential
   coordinator, processor, and document-local commit boundary.
3. `blue.coordination.processor` retains the semantic Contracts/BEX workflow
   closure used by the compact runtime and advanced processor registration.

Append validates the Timeline/provider/actor envelope, establishes one exact
Timeline Entry BlueId, stores the entry once, and publishes its journal
coordinates only after success. It records no recipients and invokes no
document processor.

The sequential drain coordinator owns processing order. It obtains source
completeness evidence, selects the next canonical eligible entry, freezes its
pre-entry direct targets from the active Channel index, and processes one
dependency-aware entry frame. Descendants finish their complete entry-caused
epoch segment before an ancestor applies it, and ancestor direct handling runs
only after those applications. No caller-selected entry may jump the queue.

The only dependency topology is derived from effective `Process Embedded.paths`
and `Process Embedded.collectionPaths`. `EmbeddingBinding` is immutable
topology and activation identity. Parent progress is a separate
`EmbeddedEpochCursor`; changing a cursor does not mutate an earlier graph
snapshot or its generation. One attachment transition owns one extendable
catch-up barrier, including nested prerequisites.

Initialization, external handling, and parent synchronization all cross the
frozen processor boundary. Parent synchronization uses an exact private
`EmbeddedEpochInput` carrying old/new child identity and indexed event
occurrences; it is never appended as a synthetic Timeline Entry. One document
transition atomically publishes state, epoch, events, graph/subscription deltas,
delivery or cursor progress, idempotency receipt, and commit companion. A child
and its parents are separate commits, so retry can resume a missing parent
application without rerunning the child.

Ordinary nodes and requests are never sent through a generic splitter. The
layout compiler retains one whole root shell and one whole object for each
effective managed Process Embedded document. The semantic root remains exact
and can be reconstructed from those content-addressed whole objects.
