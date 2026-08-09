# Metrics reference

`CoordinationEngine.metrics()` returns one cumulative immutable snapshot.
Capture a baseline and subtract later values when measuring a single operation.
Timers are nanoseconds; `millis(name)` is a convenience conversion.

## Phase timers

- `append.total`: exact request/Timeline Entry construction and journal commit.
- `process.routeLookup`: indexed autonomous-root selection.
- `process.hostBeforeFrozen`: host preparation before Contracts.
- `process.frozenContractsOnce` and `process.frozen`: frozen semantic execution.
- `process.hostAfterFrozen`: host delta validation and preparation after
  Contracts.
- `layout.compileFrozenCatalog`: embedded-boundary catalog compilation.
- `layout.retainEmbeddedOnly`: whole-object layout retention.
- `process.total`: complete dispatched root processing.

Coordination host time for one dispatch is approximately
`process.total - process.frozen`; use wall-clock timings for user-visible latency.
These are diagnostic cumulative timers, not a distributed tracing API.

## High-value counters

- `journal.entriesStoredWhole`, `requestsStoredWhole`: whole admission proof.
- `routing.lookups`, `routing.targetsSelected`: indexed dispatch work.
- `process.frozenContractsInvocations`: semantic calls; normally one per selected
  autonomous root.
- `process.duplicateEntriesSkipped`: idempotent retry/replay skips.
- `deliveryReceiptsCommitted`, `revisionApplicationReceiptsCommitted`: committed
  idempotency evidence.
- `catchUp.childEntriesProcessed`, `catchUp.parentRevisionApplications`: catch-up
  work.
- `embedding.childSessionsCreated`, `embedding.childSessionsReused`: autonomous
  admission behavior.
- `layout.splitterCreatedEdges`: embedded document cuts. Despite the historical
  name, each edge is one whole autonomous document—not generic node fragments.
- `journal.rollbacks`, `transactionRetries`: failure/retry activity.

Gauges report managed documents, route rows, journal entries, retained whole
objects and the logical clock. Counter names are diagnostic in this RC; do not
use them as a billing or durable audit contract.
