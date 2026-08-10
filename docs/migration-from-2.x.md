# Migration from 2.x

Version 3 removes the generic engine/planner/fragment-store surface,
subscription-delivery planning, fast paths, myOS demo source set, and the
`basicTest`-hosted runtime. There are no compatibility wrappers.

Replace 2.x session/store/process APIs with `CoordinationEngine`. Register
Timelines explicitly, use `DocumentId` for continuing managed identity, and
represent requests with `Operation.yaml` or `Operation.exact`. Append entries
without document recipients, then call `drain()` or `drainThrough(cutoff)`; do
not reproduce the old caller-selected dispatch order. Read immutable
`DocumentSnapshot` and `DocumentRevision` values.

Choose `FULL_HISTORY`, `FROM_FRONTIER`, or `FROM_NOW` explicitly when admitting
a top-level document with existing source history. Model document dependencies
only with effective `Process Embedded.paths` and `collectionPaths`; do not
migrate application links into a second Coordination relationship graph.

Production now requires Java 17. Maven coordinates remain under
`blue.coordination`, with the new major version establishing the future binary
compatibility baseline.
