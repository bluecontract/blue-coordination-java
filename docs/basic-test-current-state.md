# Compact `basicTest` engine: current state after Round 9

## Verdict

Round 9 is closed. The compact engine is green across correctness, realistic
scenarios, strict performance, and a 30-sample campaign. It keeps the Round 8
architecture, adds the requested closure checks, and adds no production class
or new planning layer.

| Category | State |
|---|---|
| Whole requests and Timeline Entries | Exact whole objects; no splitting |
| Routing | Direct operation/channel/Timeline/actor index |
| Autonomous documents | One session and revision stream per `DocumentId` |
| Same-document reuse | Original-initial-BlueId proof; conflicts atomic |
| Shared children | One child execution; independent parent cursors |
| Historical/nested/NBA catch-up | Green against immutable journal frontiers |
| Failure/retry | Documents, graph, receipts, journal, and clock atomic |
| Subscription hot path | Exact companion; zero post-PROCESS projection |
| Autonomous semantic identity | Known frozen-API ownership gap documented |
| Engine budget | 35 classes, 5,198 lines, zero new production classes |

## Round 9 changes

- Added `basicSmokeTest` with six fast architecture/correctness checks.
- Added an atomic regression for one `DocumentId` supplied with a conflicting
  original initial BlueId.
- Hardened companion retirement against a mismatched established route.
- Renamed the private child execution projection to
  `autonomousOwnershipProjection`.
- Measured twenty identical failed retries and proved stable immutable-cache
  count plus complete journal/clock rollback.
- Tested and rejected the exact semantic-Root shell without retaining any
  experimental source.

## Runtime position

| Operation | Round 9 Coordination host | Frozen semantic | User-visible total |
|---|---:|---:|---:|
| Counter PROCESS p95 | 0.846 ms | 256.287 ms | about 257.133 ms |
| Large host, warm | 12.145 ms | 3,388.683 ms | 3,437.406 ms |
| PayNote authorization #1 + parent | 48.258 ms | 5,288.068 ms | 5,374.147 ms |
| Restaurant confirmation + parent | 31.531 ms | 5,225.703 ms | 5,294.413 ms |
| Attach and initialize PayNote | 647.403 ms | 7,934.261 ms | 8,619.464 ms |

The distinction is essential: the 5.37-second authorization is not a 48 ms
operation. Frozen semantic processing is 98.4% of that measured total. The
compact Coordination host cannot remove that floor with another route cache or
fragment planner.

## Known limits

The most important semantic limitation is the autonomous ownership projection.
An exact semantic parent Root exposes child-owned channels to the frozen
delivery verifier, but the public invocation API has no ownership-exclusion
mask. The projection avoids duplicate child execution at the cost of exact
processing-Root identity. See
[`FROZEN_AUTONOMOUS_OWNERSHIP_GAP.md`](FROZEN_AUTONOMOUS_OWNERSHIP_GAP.md).

The module is also synchronized and in-memory; local catch-up completeness
comes from its journal; unique failed results may leave unreachable immutable
cache values; and dynamic parent membership, embedded collections, arbitrary
history frontiers, and distributed transactions remain unsupported.

## Recommendation

Stop at Round 9. The closure checks pass, all hard host-performance gates pass,
and the remaining material semantic issue requires a frozen Contracts API
capability rather than more Coordination architecture.
