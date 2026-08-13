# Host time versus frozen semantic time

User-visible drain time contains two materially different costs:

- frozen semantic time: Contracts resolution, workflow execution, and BEX;
- Coordination host time: ordered source selection, exact routing, immutable
  object retention, layout updates, revision publication, cursors, barriers,
  receipts, and catch-up orchestration.

Metrics report these phases separately. A multi-second PayNote entry frame may be
dominated by frozen semantics while measured Coordination host phases remain tens
of milliseconds. Performance gates therefore evaluate append, route lookup,
direct external frozen PROCESS, embedded parent frozen PROCESS, the non-frozen
drain residual, the unattributed portion within that residual, and total time.
The harness subtracts the aggregate `process.frozen` timer; subtracting only
`process.frozenContractsOnce` would incorrectly label embedded frozen execution
as host work. Test-fixture-only delivery-plan derivation and platform commit are
nested frozen diagnostics; embedded-input and companion-delta diagnostics are
nested in host phases. Public layout timers are reported separately and may
also cover admission work outside those host phases. Nested timers are never
added to their parent timers.

The standalone `blue-basic` project retains historical Counter, whole-request,
1-vs-61 workflow, Wadowice PayNote and NBA timing campaigns. Its percentile
campaign uses 200 samples for append/routing micro-paths and 30 for
processing/catch-up paths. These metrics are diagnostic evidence only. The
library's own integration and scenario suites assert the corresponding semantic
invariants, including zero generic fragments and embedded-only cuts, and are
the suites enforced by `releaseCheck`.

## 2026-08-10 artifact-bound diagnostic

One run bound by the companion provenance record to the exact Coordination JARs,
test-source manifest, dependency lock, and producer runtime produced the following
wall-clock decomposition. These are single-run diagnostics, not portable latency
promises.

| Operation | Append ms | Drain ms | Frozen ms | Non-frozen residual ms | Of which unattributed ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Attach PayNote and initialize its two managed products | 27.799 | 19,698.022 | 18,501.099 | 1,196.924 | 967.805 |
| Warm host operation | 31.396 | 3,327.171 | 3,307.633 | 19.538 | 3.798 |
| Warm PayNote plus parent propagation | 31.929 | 5,318.566 | 5,264.847 | 53.720 | 6.790 |
| Restaurant child through PayNote and host | 33.078 | 6,314.884 | 6,253.343 | 61.541 | 6.936 |

Values are rounded independently from nanosecond measurements; displayed
rounded operands may therefore differ by 0.001 ms.

For the warm PayNote frame, the frozen lanes were 1,027.128 ms for the PayNote
and 4,237.718 ms for the parent. Nested frozen diagnostics attributed 2,483.260
ms to delivery-plan derivation and 2,781.245 ms to platform commit; the wrapper
remainder was 0.341 ms. Cached canonical embedded-input shape reuse kept warm
input preparation to 0.287 ms. The one-time attach now admits the PayNote plus
two real managed product documents; its residual includes their initialization,
barrier, and scheduler work outside the steady PROCESS phase model.

The steady-state distinction is decisive: frozen work was 99.41% of the warm
host drain and 98.99% of the warm PayNote-plus-parent drain. Coordination's
non-frozen residual was respectively 0.59% and 1.01%. The scheduler therefore
passes its host-work budget, but the complete operation does **not** meet an
interactive-latency objective. This release evidence must not be summarized as
"fast" merely because the host lane is fast.

## Round 11 hot-path audit

The occurrence-admission, exact-epoch, READY-read, and bounded-drain corrections
do not add a history scan or graph scan to a steady unchanged-topology PROCESS:

- occurrence-specific admission plans are consulted only when a topology delta
  introduces an embedded occurrence;
- exact state-to-epoch provenance is updated by one constant-time map operation
  per committed revision and queried only during admission;
- READY-only versus audit reads change the read boundary, not processing;
- an unlimited drain uses a null budget sentinel, so it does not populate the
  bounded-drain selected-entry set and performs only predictable early-return
  checks at scheduling boundaries.

A bounded drain limits committed PROCESS transitions and selected entries at
deterministic continuation points. It is a work bound, not a wall-clock timeout:
one already-started frozen PROCESS remains atomic and can still take seconds.
Epoch-zero initialization is also atomic. Bounded continuation improves
fairness and recoverability; it does not hide or cure frozen semantic latency.

The library-owned `CoreBehaviorIntegrationTest` independently asserts that
aggregate frozen time equals its external and embedded lanes, and that the
delivery-plan plus platform-commit diagnostics are positive and bounded by the
frozen parent. Those assertions passed for both direct and child-to-parent
PROCESS. The standalone numbers above are the regenerated artifact-bound
release record for the corrected three-document PayNote graph.

## Where the seconds are

`process.deliveryPlanDerivation` currently includes complete active-surface
validation, one authoritative selection evaluation, and an independent
determinism replay. `process.platformCommit` then revalidates the supplied,
Root/event/revision/order-bound plan through the core verifier before semantic
execution. Those checks deliberately reject forged, stale, incomplete, or
non-deterministic execution evidence. Coordination must not bypass them.

The warm PayNote frame placed 5,264.505 ms of its 5,264.847 ms frozen lane in
those two upstream boundaries. That is 99.99% of frozen time and leaves only
0.341 ms in the surrounding wrapper. A safe improvement therefore needs an
upstream Language/Contracts/BEX change with the following acceptance contract:

1. retain identical resulting state, ordered events, portable gas, and commit
   companion evidence;
2. retain independent rejection tests for forged, stale, incomplete, and
   non-deterministic delivery plans;
3. add subphase timers for authoritative selection, determinism replay, commit
   verification, exact-resource establishment, and BEX execution;
4. key any reuse by the complete immutable Root, event, revision, order,
   active-interval, runtime-generation, and contribution identities;
5. preserve cold/warm semantic parity and bounded cache ownership.

Caching a plan only by document type, DocumentId, or workflow is invalid: every
committed transition changes at least the revision-bound evidence, and commonly
the exact Root identity. Reusing compiled BEX by exact body identity and reusing
verified immutable resolution products inside the upstream invocation are more
promising than a Coordination-side plan cache.

A one-shot local experiment supplied the ownership Root as a pure BlueId
reference instead of the retained exact representation. The large-host scenario
runtime moved from 31.881 s to 32.319 s, so the provider merely rematerialized
the same Root later and no latency was removed. That experiment was reverted;
the figures are engineering diagnostics, not a benchmark comparison or release
claim.

The final repeated runtime campaign observed p95 values of 99.234 ms for
existing-child catch-up, 157.939 ms for late-child admission, 79.914 ms for
nested catch-up, and 87.689 ms for NBA catch-up. Existing-child catch-up passes
its hard 150 ms gate but remains above the preferred 80 ms target. All nine
enforced rows pass; three diagnostic-only rows remain `OBSERVE` because no
release threshold is assigned. No authoritative historical baseline is claimed.
The companion `blue-basic` `runtime-provenance.json` binds those results to the
exact test-source and dependency-lock manifests, artifact and JSON/Markdown
report hashes, benchmark JVM, separate Gradle producer runtime, and configured
sample counts.
