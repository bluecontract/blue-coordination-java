# Start here

1. Use Java 17 or newer.
2. Depend on `blue.coordination:blue-coordination-java:3.0.0-rc.1`.
3. Create an in-memory `CoordinationEngine` in a try-with-resources block.
4. Register each Timeline with its exact Timeline and actor identities.
5. Start autonomous documents with a stable `DocumentId` and authored initial
   YAML.
6. Append an `Operation`, then dispatch its returned exact `TimelineEntry`, or
   use `appendAndDispatch`.
7. Read state through immutable `DocumentSnapshot` and `DocumentRevision`
   values; never retain mutable internal nodes.

The runtime is deliberately in-memory and single-process. Its journal,
document sessions, route index, receipts, embedded links, catch-up cursors, and
logical clock publish under one synchronized rollback boundary. Durable storage
and distributed commit are host responsibilities that are not implemented in
this release.

Read next:

- [Compact engine](docs/architecture/compact-engine.md)
- [Autonomous documents](docs/semantics/autonomous-documents.md)
- [Historical catch-up](docs/semantics/historical-catch-up.md)
- [Identity and revisions](docs/semantics/identity-and-revisions.md)
- [Host vs frozen time](docs/performance/host-vs-frozen-time.md)
- [Known limitations](docs/limitations.md)
- [Migration from 2.x](docs/migration-from-2.x.md)
- [Public API reference](docs/reference/public-api.md)
- [Metrics reference](docs/reference/metrics.md)
- [Failure and retry model](docs/operations/failure-model.md)
- [Build and test](docs/development/build-and-test.md)
- [Test strategy](docs/development/test-strategy.md)
- [Release process](docs/development/releasing.md)
- [3.0.0-rc.1 readiness](docs/releases/3.0.0-rc.1.md)
