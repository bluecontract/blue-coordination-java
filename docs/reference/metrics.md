# Metrics reference

`CoordinationEngine.metrics()` returns one cumulative immutable snapshot.
Capture a baseline and subtract later values when measuring one append, drain,
entry frame, or catch-up barrier. Timers are nanoseconds; `millis(name)` is a
convenience conversion.

## Public phase timers

- `append.total`: exact-node admission times its complete validation,
  journal-commit, and rejected-attempt rollback boundary. Convenience builders
  time the journal append after request construction; surrounding builder and
  frontier-validation work is outside that narrower measurement.
- `temporal.drain`: environment-selected processing to the requested safe
  frontier.
- `process.routeLookup`: every real indexed Channel target derivation, including
  live drain, historical selection, and diagnostic lookup.
- `process.hostBeforeFrozen`: host preparation before Contracts.
- `process.frozenContractsOnce`: direct external-entry frozen execution.
- `process.embeddedFrozen`: processor-owned child-epoch frozen application.
- `process.frozen`: the aggregate of both; subtract this phase—not only the
  external phase—when calculating the non-frozen drain residual.
- `process.hostAfterFrozen`: host delta validation and commit preparation.
- `layout.compileFrozenCatalog`: Process Embedded catalog compilation.
- `layout.retainEmbeddedOnly`: whole-object layout retention.

The non-frozen residual for one drain is temporal drain wall time minus aggregate
frozen semantic time; it is derived, not an independently timed host phase.
Report append, source selection, frozen external
PROCESS, frozen embedded PROCESS, explicit host phases, unattributed residual,
and total wall time separately. These are diagnostics, not distributed traces.

## Closed counter vocabulary

The public map contains every `CoordinationMetrics.Counter` name, including
valid untouched counters at zero, and contains no implementation diagnostics.
Unknown constructor keys and string lookups fail instead of reading as zero.

- `ENTRIES_STORED_WHOLE`, `ROUTE_INDEX_LOOKUPS`, and
  `DOCUMENT_INITIALIZATIONS` account for journal admission, indexed routing,
  and managed document initialization.
- `GRAPH_SNAPSHOTS_REUSED` and `GRAPH_RECONCILIATIONS` account for incremental
  Process Embedded topology work.
- `EXTERNAL_PROCESS_CALLS`, `EMBEDDED_EPOCH_PROCESS_CALLS`,
  `CHILD_EPOCHS_COMMITTED`, and `PARENT_EPOCH_APPLICATIONS` account for exact
  document processor work and committed epoch propagation. Successful external
  and parent-application counts are recorded at durable document commit, so a
  lost response and receipt reconciliation neither loses nor duplicates them.
- `HISTORICAL_WINDOWS_OPENED`, `HISTORICAL_ENTRIES_REPLAYED`,
  `CATCH_UP_BARRIERS_CREATED`, and `CATCH_UP_BARRIERS_COMPLETED` account for
  attachment-exclusive history and synchronized catch-up.
- `UNRELATED_DOCUMENT_READS`, `REQUEST_FRAGMENTS`,
  `TIMELINE_ENTRY_FRAGMENTS`, `ORDINARY_NODE_FRAGMENTS`,
  `FULL_ENVIRONMENT_SCANS`, `SOURCE_REPLAYS_PER_PARENT`, and
  `POST_PROCESS_FULL_PROJECTIONS`, `PARENT_PROCESS_RERUNS_ON_GRAPH_RETRY`, and
  `CHILD_PROCESS_RERUNS_ON_PARENT_RETRY` are structural release gates and
  remain zero only while those forbidden work classes remain absent.

Detailed implementation counters and phase timers used for integration-test
deltas are exposed only by the test-fixture `CoordinationTestControl`; they are
not part of `CoordinationEngine.metrics()`. In particular,
`process.deliveryPlanDerivation` and `process.platformCommit` are nested inside
`process.frozen`, `process.embeddedInputPreparation` is nested inside
`process.hostBeforeFrozen`, and `process.applyCommitCompanionDelta` is nested
inside `process.hostAfterFrozen`. Parent and nested timers are non-additive.

Structural release gates require zero request fragments, Timeline Entry
fragments, ordinary-node fragments, full environment scans, per-parent source
replay, unrelated-document reads, and whole post-PROCESS projections. A metric
named in an acceptance gate must have a production producer; tests must not
silently turn an unknown measurement into a passing zero.

Gauges report managed documents, route rows, journal entries, retained whole
objects and the logical clock. Metrics are release and operational evidence,
not a billing or durable audit contract.
