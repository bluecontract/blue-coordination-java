# Compact engine architecture

The supported runtime has three layers:

1. `blue.coordination.api` is the immutable 16-type application boundary.
2. `blue.coordination.internal` owns one exact in-memory journal, whole-object
   store, document store, operation route index, embedded graph, processor, and
   atomic publication boundary.
3. `blue.coordination.processor` retains the semantic Contracts/BEX workflow
   closure used by the compact runtime and advanced processor registration.

An append resolves or retains one exact request, structurally builds one exact
Timeline Entry, stores it once, and publishes its Timeline/global coordinates
only after success. It does not know the target documents.

Dispatch performs an exact operation/channel/Timeline/actor index lookup. Each
selected autonomous root is prepared once and crosses frozen Contracts once.
All new document states, revisions, embedded links, route rows, receipts,
catch-up cursors, processor-managed journal entries, and logical time are then
published together. A pre-publication failure restores the prior state; a lost
response after publication is reconciled from the delivery receipt.

Ordinary nodes and requests are never sent through a generic splitter. The
layout compiler retains one whole root shell and one whole object for each
effective `Process Embedded` child. The semantic root remains exact and can be
reconstructed from those content-addressed whole objects.
