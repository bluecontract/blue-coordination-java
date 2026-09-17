# Cost39: indexed journal authoring observation

Problem (Pro source review E08): MyOS Mini reads the entire selected Timeline and
entire journal before every hosted append to find the predecessor and maximum
timestamp. An unrelated old history therefore adds linear reads per new input.

Solution: additive `AdvancedCoordination.auditTimelinePosition(String)` returning
the selected head, journal-wide maximum timestamp (zero when empty), and revision.
Both coordinates come from one pinned `TimelineJournalStore.ReadView`. The journal
reuses its head and canonical maximum-order indexes and validates selected entry,
predecessor and index bindings. The result is an observation, not an append permit
or provider-completeness certificate. Existing append validation/fences remain.

No new storage schema, Language/BEX changes, semantic cursor, gas rule, timestamp
policy or reaction boundary. Mini keeps the existing `maximum timestamp + 1` rule.
Explicit predecessor overrides and overflow behavior are preserved. The full
audit APIs remain available; this indexed read does not audit unused old rows.

The existing production-shape inventory is updated by exactly two source/public
types and 90 production lines for the core/SDK immutable observations and adapters.
Per-source and architectural prohibitions remain unchanged; this is not a runtime
limit increase or a change to CI workflows.

Rejected alternatives: last appended timestamp (incorrect with cross-Timeline
out-of-order imports); a host-only cached maximum (requires additional durable
authority/recovery); two unpinned reads (can combine different journal states).

Verification: comparison with old scans on 11/101/1,001/10,001 real retained entries,
bounded physical reads, empty and absent Timeline, out-of-order append, duplicate,
rollback, immutable observation, selected-index corruption/physical failures, SDK
parity, cold file-store and PostgreSQL continuation in Mini.

Results, 17 September 2026: Coordination `bd3ea89a` passes 36/36 selected tests
and `validateProductionShape`. The final Mini candidate `3c17728f` passes 24 unit/
projection tests, 47 PostgreSQL integration tests and four packaged HTTP/restart
cases in external-state mode; the same four cases pass in resident mode. Language
artifact `833aa953` is unchanged. These are focused regressions, not the full
acceptance inventory. See Mini's matching Cost39 document for artifact/evidence
pins and the precise scope.

At 11/101/1,001/10,001 retained journal entries the indexed observation makes
14 ReadView calls in every case; the previous two scans make respectively
75/615/6,015/60,015. These counts are storage-port calls, not SQL queries or
constant-time guarantees for index internals. One diagnostic 10,001-entry sample
measured 0.052 ms versus 74.229 ms; no application speedup is claimed from that
single fixture measurement. The original long catch-up was not rerun.

This package does not remove full receipt/public-history projection scans, change
same-epoch representation authentication, or prove multi-worker scalability.
