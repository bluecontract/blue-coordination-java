# Performance evidence

Performance claims for the engine must be based on reproducible physical
evidence while preserving identical semantic results. A passing correctness
suite, an in-memory read count, or a microbenchmark score alone is not evidence
that a production database workload meets its target.

This guide describes what to measure; it does not declare the current engine a
public release candidate. Exact immutable Repository required-closure blockers
remain separate release evidence. Autonomous-document fan-out belongs to the
host layer and has separate myOS correctness and work evidence; it is not
silently attributed to one-session engine measurements.

## Semantic invariants first

Before comparing locality policies or storage adapters, fix the same:

- session snapshot and epoch;
- Root and event BlueIds;
- external event order;
- indexed occurrence order or compatibility evidence;
- runtime registration and environment identity;
- quota and gas schedule identities.

Then prove that the runs agree on status, resulting Root, epoch behavior,
emitted Root events, subscription update, scope transitions, total gas, and
commit-plan identity. Prefetch is physical only. A faster run that changes any
of those values is a correctness failure, not an optimization.

## Built-in locality observations

`CoordinationTransition.locality()` exposes `LocalityDiagnostics`:

- requested BlueIds;
- backend-loaded BlueIds;
- initial batch count;
- fallback-read count;
- loaded bytes;
- prefetched-but-unused BlueIds;
- causally selected BlueIds;
- forbidden-read count.

`LoadedProcessingBundle` separately records the exact initial batch, preferred
identities, batch count, and loaded bytes. The binary-compatible default
callbacks on `CoordinationProcessingEngineObserver` expose successful-stage
nanosecond durations for:

- the complete public `plan` call;
- the configured request-local bundle load;
- the single public Contracts PROCESS call;
- subscription projection plus fragment-transition planning;
- the complete public `commit` call;
- the complete `processAndCommit` convenience call.

The receipt calls the fourth duration `fragment-transition` and documents that
it includes subscription projection. Callbacks are emitted only after their
stage succeeds. Observer failures are isolated and must not affect semantics.

The reference in-memory store also exposes single/batch read counters and
physical fragment count. Use those for deterministic tests, not as a proxy for
database latency.

## Required receipt matrix and protocol

The built-in scenario adapter runs a strict 9 × 3 × 2 matrix: nine real
Coordination scenarios, three execution modes, and cold/warm cache state, for
exactly 54 unique cells.

The scenarios are:

1. simple Root event;
2. selected depth two;
3. deep A25 event;
4. Composite Channel event;
5. All Timelines Channel event;
6. Document Update cascade;
7. Triggered Event cascade;
8. collection-member add, remove, and re-add; and
9. ten consecutive deep events.

The execution modes are:

- `FRAGMENT_NATIVE_INDEXED`: the measured engine run receives the exact
  ordered indexed candidates. Its current-Root compatibility oracle is
  derived outside the measured interval.
- `CURRENT_ROOT_COMPATIBILITY`: the measured engine run derives delivery from
  the exact current Root through the public Contracts compatibility service.
- `FULL_INLINE_CONTROL`: the measured control invokes the public Contracts
  services with exact inline Root and event values, without the engine's
  fragment and persistence orchestration.

A cold sample has no earlier PROCESS in its immutable runtime generation. A
warm sample is primed outside the measured interval: engine modes prime an
equivalent independent session, while inline control primes the same runtime
generation and immutable store. The measured document state and event sequence
remain identical across cache states. Multi-event scenarios measure the whole
sequence; repeated phase observations are accumulated rather than overwritten.

The default profile performs one warmup and five measured iterations per cell.
Report cold and warm p50/p95/p99 separately for plan, bundle load, PROCESS,
fragment transition, commit, and end-to-end time wherever that phase belongs
to the mode. The receipt retains every raw sample used by its nearest-rank
percentiles.

Every sample carries a dataset digest and semantic fingerprint. The collector
rejects the whole comparison if status, final Root, Root events, gas, named
trace, checkpoints, or subscription delta differs across a scenario's modes,
cache states, or iterations. It uses nearest-rank p50/p95/p99 and retains the
raw samples used for each percentile.

Every phase and metric is either an authoritative non-negative value or one
explicit unavailable reason. Missing values are never converted to zero.

`selected-body-count` means executable Handler-body execution occurrences. It
is the measured delta of Language's `HANDLERS_EXECUTED` counter as observed by
`BexProcessingMetrics`; executing the same body repeatedly counts repeatedly.
It is not a distinct-BlueId count and must never be populated from
`causallySelectedBlueIds`.

`allocation-bytes` is the exact number of bytes allocated on the synchronous
measurement thread according to the HotSpot `ThreadMXBean`. It is available
only when that JVM supports and enables thread-allocation measurement;
otherwise the receipt records one explicit unavailable reason.
`materialized-node-count` remains unavailable because the current runtime has
no non-perturbing authoritative counter. `retained-heap-bytes` remains
unavailable without isolated heap-dump and dominator analysis. Full-inline
control also marks engine-only phases and request-local fragment-provider
metrics unavailable. Those optional unavailable metrics do not become zero
and do not invalidate a cell whose mode-specific required phases and metrics
are authoritative.

## Expected physical properties

These are properties to test, not unconditional benchmark conclusions:

- indexed planning should avoid reconstructing unrelated Root scopes;
- the initial loader should batch required seeds and policy-selected
  preferences;
- forbidden reads should remain zero;
- `MINIMUM_BYTES` should generally load fewer speculative bytes but may cause
  more fallbacks;
- `MINIMUM_ROUND_TRIPS` may load unused fragments to avoid fallbacks;
- equal content across sessions should reuse physical bodies;
- a small change should report reused bodies separately from new bodies;
- one event should cause one public PROCESS invocation and at most one
  authoritative session commit attempt by the engine;
- CAS contention should not multiply Root outbox entries or epochs.

State exceptions explicitly. For example, event fragmentation and immutable
admission happen during planning, compatibility mode intentionally materializes
the full Root, and a CAS loser may leave harmless immutable content.

## Representative embedded-collection scenario

A useful flagship workload contains nested owned collection members, repeated
equal child content at different occurrence keys, a workflow whose event
selects only one deep occurrence, and a second independent session sharing some
bytes. Capture:

- ordered indexed candidates and selected scope chains;
- required seeds and preferred prefetch identities;
- backend-loaded and fallback identities;
- before/after Root and every scope transition;
- new versus reused fragments and inventory identities;
- subscription activation/retirement evidence;
- Root events, epoch receipt, total gas, and commit outcome;
- proof that the second session did not advance.

Run the same semantic event through compatibility planning as a reference and
compare the deterministic transition evidence. This makes the complex embedded
processing walkthrough an executable performance story rather than a final
document snapshot.

## Publishing results

Every published table should include commit hash, JVM and flags, hardware,
operating system, database/version/configuration, dataset generator and seed,
warmup and iteration counts, concurrency, cache state, runtime/environment
identities, and raw result location. Keep correctness assertions enabled in the
evidence run.

Do not label the current engine “all green” or “release-ready” because a focused
locality run passes. Performance evidence, engine correctness, and the separate
Repository required-closure gate are different release dimensions.

Run the receipt-contract lane with:

```bash
./gradlew --offline --no-daemon \
  coordinationProcessingEnginePerformanceEvidence \
  -PtestJfr=false
```

It writes
`build/reports/coordination-engine/performance-same-run.json`. The task selects
`RealCoordinationEnginePerformanceScenarioAdapter` by default. It loads and
runs that adapter only after the prerequisite engine, storage TCK, planning
smoke, repository-independent flagship observation, and linkage lanes have
all executed successfully in the same Gradle invocation. The gate is derived
from those actual task results; no operator-supplied semantic-green flag is
accepted as evidence.

To generate a deliberately fail-closed receipt without running measurements,
use:

```text
-PcoordinationEnginePerformanceSkipMeasurements=true
```

That opt-out receipt contains all 54 declared cells with every phase and
metric explicitly unavailable, zero completed cells, and
`performanceReady = false`. The same fail-closed result is produced when a
same-run prerequisite is not green. A custom adapter remains an expert test
hook through `-PcoordinationEnginePerformanceAdapter=<adapter-class>`; it does
not bypass the same-run semantic gates.

The exact Coordination source-tree digest, resolved dependency-lock digest,
Language and BEX commits, JVM flags, machine profile, dataset-generator
identity, warmups, iterations, raw samples, and semantic fingerprints are
stored in that receipt. The report validator rejects missing cells, duplicate
cells, bad percentiles, mixed available/unavailable values, stale dependency
identity, and any speedup claim.

The public `PlatformProcessInvocation` boundary carries the exact request-local
provider into PROCESS, so completed transitions may publish provider and
locality samples. The engine performance report keeps the bounded deterministic
smoke separate from the 54-cell receipt. The built-in adapter makes the receipt
executable; only the generated same-run receipt says whether its required
measurements were available and semantically equivalent. Even a verified
receipt makes no speedup claim: `speedupClaims` remains empty, and the report
never substitutes zero-duration or zero-read values for a failed, unsupported,
or absent measurement.
