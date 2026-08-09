# Migration from 2.x

Version 3 removes the generic engine/planner/fragment-store surface,
subscription-delivery planning, fast paths, myOS demo source set, and the
`basicTest`-hosted runtime. There are no compatibility wrappers.

Replace 2.x session/store/process APIs with `CoordinationEngine`. Register
Timelines explicitly, use `DocumentId` for autonomous identity, represent
requests with `Operation.yaml` or `Operation.exact`, dispatch returned
`TimelineEntry` values, and read immutable `DocumentSnapshot`/`DocumentRevision`
objects.

Production now requires Java 17. Maven coordinates remain under
`blue.coordination`, with the new major version establishing the future binary
compatibility baseline.
