# Test strategy

Blue Coordination keeps release correctness inside this repository. The
library does not depend on a demo project, a metrics campaign or an adjacent
consumer checkout to prove that it works.

## Verification layers

| Suite | Boundary | Primary guarantees |
| --- | --- | --- |
| `test` | Types and compact internals | Immutable public values, validation, typed failures, metrics concurrency, whole-object storage, timeline projection/checkpoints, runtime registrations, plan caches, workflow state and BEX accounting |
| `integrationTest` | In-memory engine with public operations | Routing, admission atomicity, retry hygiene, embedded-only cuts, identity, ownership, concurrent attachment, historical catch-up, removal and reattachment |
| `consumerTest` | Built production JAR only | Published API usability, runtime dependency completeness, ordinary whole requests, embedded PayNote, shared children and NBA catch-up |
| `scenarioTest` | Complete business lifecycles | Four NBA admission orders converge; large host/PayNote authorization and restaurant flow converges |

The suites intentionally overlap at important boundaries. Atomicity has focused
integration tests and is exercised again by realistic scenarios. The consumer
suite repeats representative behavior because compilation and execution against
the JAR catch packaging and dependency mistakes that source-based tests cannot.

## Protected invariants

`verifyTestArchitecture` prevents accidental collapse back to a token suite. It
enforces per-layer test floors, proves that the consumer compiler uses the built
JAR instead of main source output, rejects internal API imports from consumer
tests and rejects any build dependency on `../blue-basic`. Test count is only a
structural tripwire; the assertions and behavior map above are the substantive
quality evidence.

The legacy 2.x fragmentation/planning tests were not copied mechanically because
their production architecture was removed. Semantics retained by the compact
engine were rewritten at the new public and atomic boundaries: exact whole
objects, embedded-only cuts, route selection, catch-up, ownership, rollback,
retry and workflow/BEX accounting. This avoids testing deleted implementation
details while preserving the behavior that the 3.x library promises.

## Historical metrics

`../blue-basic` is a performance laboratory retained for historical comparison.
It owns step timing tables, repeated percentile campaigns and before/after
reports. It is useful when diagnosing latency, but it is neither compiled nor
executed by `releaseCheck`. Correctness regressions must always receive a test in
one of the four library-owned suites, even if a matching metrics scenario exists
there.
