# Start here

1. Use Java 17 or newer.
2. Resolve the local-only `3.0.0-rc.2` candidate from the explicit staged file
   repository. It is not available from Maven Central or Maven Local.
3. Create `BlueCoordination.inMemory()` in a try-with-resources block. This is
   the one normal default and uses the bundled Contracts 1.0 identities.
4. Register each Timeline with `blue.timelines().local(...)` or
   `register(timelineId, accountId)`.
5. Admit an authored public Root with `ManagedDocument...publicRoot()` or admit
   a complete authored closure with `ManagedClosure`. Choose an activation
   policy explicitly.
6. Submit target-aware operations with `blue.operations().on(document)`. Use
   `blue.events()` only for deliberate broadcasts; callers never name final
   recipients.
7. Call `submit()` for append-only behavior and `blue.processing().drain()` for
   a later canonical drain, or call `execute()` to append and drain through the
   submitted entry without overtaking older eligible work.
8. Inspect `EntryDisposition` and `Diagnostic`, then read coherent application
   state through the READY-only `DocumentHandle.snapshot()`. Reserve
   `blue.advanced()` for host integration and operational diagnostics.

The older `CoordinationEngine` surface is an advanced/legacy compatibility
boundary. Its plain `inMemory()` factory retains the earlier acyclic profile;
it does not share the SDK default's Contracts semantics. New application code
should stay in `blue.coordination.sdk`.

The rc.2 SDK does not yet admit a managed child produced by an operation.
`request.managed(...)` and `expectOccurrence(...)` fail before append with
`UNSUPPORTED_MANAGED_DRAFT_ADMISSION`; no partial journal or document mutation
is allowed. This unresolved host-invocation bridge keeps the implementation
conformance claim false.

The runtime is deliberately single-process and sequential. Each document
transition atomically commits its exact state, epoch, events, graph and
subscription deltas, progress cursor, receipt, and commit companion. A child
commit can therefore survive a later parent failure; retry resumes the missing
parent application instead of reprocessing the child. The bundled host is
in-memory, so process-restart durability and provider-backed completeness remain
explicit release gates rather than implied guarantees.

The normal SDK does not expose the advanced `DrainBudget` boundary. Hosts that
need deterministic work budgets can use `blue.advanced().rawEngine()` during
migration. A work budget does not preempt one frozen PROCESS invocation or
epoch-zero INITIALIZE and is not a wall-clock timeout.

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
- [SDK migration and ownership ledger](docs/reference/sdk-migration-and-ownership.md)
- [Metrics reference](docs/reference/metrics.md)
- [Failure and retry model](docs/operations/failure-model.md)
- [Build and test](docs/development/build-and-test.md)
- [Test strategy](docs/development/test-strategy.md)
- [Release process](docs/development/releasing.md)
- [Current Contracts/SDK verification boundary](docs/releases/contracts-1.0-current-verification.md)
- [Historical 3.0.0-rc.1 readiness](docs/releases/3.0.0-rc.1.md)
