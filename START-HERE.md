# Start here

1. Use Java 17 or newer.
2. Depend on `blue.coordination:blue-coordination-java:3.0.0-rc.1`.
3. Create an in-memory `CoordinationEngine` in a try-with-resources block.
4. Register each Timeline with its exact Timeline and actor identities.
5. Admit each managed document with a stable `DocumentId`, authored initial
   YAML, and—when historical data exists—an explicit top-level admission policy.
6. Append exact Timeline Entries without recipients, then call `drain()` or
   `drainThrough(cutoff)`. The environment, not the caller, selects canonical
   processing order and direct targets.
7. Use `drain(DrainBudget)` when a host must pause after a deterministic amount
   of PROCESS work; resume with another drain call.
8. Read coherent application state with READY-only `document(id)`. Reserve
   `auditDocument(id)` for explicit recovery/diagnostic inspection, and never
   retain mutable internal nodes.

The runtime is deliberately single-process and sequential. Each document
transition atomically commits its exact state, epoch, events, graph and
subscription deltas, progress cursor, receipt, and commit companion. A child
commit can therefore survive a later parent failure; retry resumes the missing
parent application instead of reprocessing the child. The bundled host is
in-memory, so process-restart durability and provider-backed completeness remain
explicit release gates rather than implied guarantees.

The work budget does not preempt one frozen PROCESS invocation or epoch-zero
INITIALIZE, so it is not a wall-clock timeout. Public phase metrics and the
standalone performance campaign separate Coordination scheduling from frozen
Language/Contracts/BEX time.

Read next:

- [Compact engine](docs/architecture/compact-engine.md)
- [Process Embedded documents](docs/semantics/process-embedded-documents.md)
- [Historical catch-up](docs/semantics/historical-catch-up.md)
- [Identity and revisions](docs/semantics/identity-and-revisions.md)
- [Initialization causality](docs/semantics/initialization-causality.md)
- [Shared NBA Game lifecycle](docs/examples/nba-shared-game-lifecycle.md)
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
