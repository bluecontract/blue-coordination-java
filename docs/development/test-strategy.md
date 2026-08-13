# Test strategy

Blue Coordination keeps release correctness inside this repository. The
library does not depend on a demo project, a metrics campaign or an adjacent
consumer checkout to prove that it works.

## Verification layers

| Suite | Boundary | Primary guarantees |
| --- | --- | --- |
| `test` | Types and compact internals | Immutable values, closed inputs, graph/cursor immutability, exact event occurrences, ordered journal cursors, plan caches, workflow state and BEX accounting |
| `integrationTest` | In-memory engine with public operations | Append/process separation, engine-selected drain, entry-frame ordering, admission, collection paths, catch-up barriers, identity, ownership, atomic retry and removal/re-addition |
| `consumerTest` | Built production JAR only | Published append/drain API, runtime dependency completeness and representative managed-document behavior |
| `scenarioTest` | Complete business lifecycles | Multi-order NBA convergence and the large host/PayNote lifecycle |

The suites intentionally overlap at important boundaries. Atomicity has focused
integration coverage and is exercised again by realistic scenarios. The
consumer suite repeats representative behavior because compilation and
execution against the JAR catch packaging and dependency mistakes that
source-based tests cannot.

## Round 10.1 semantic gates

No test may choose processing order with a named entry. Tests append all facts,
call `drain()` or `drainThrough(cutoff)`, and assert the environment-selected
order from immutable receipts and document histories.

Release-owned coverage must prove:

- one exact Timeline Entry stored once with no append-time PROCESS or recipient;
- global canonical selection with source completeness, pre-entry document
  targeting, and target-specific Mandate eligibility;
- three-level same-entry child-before-parent entry-frame ordering;
- separate Root/child epoch-zero initialization before historical work and
  attachment-exclusive replay;
- iterative dynamic history, including ordinary owned nested-scope Channel
  changes, and extendable multi-child/nested barriers;
- immutable bindings, separate occurrence cursors and activation generations;
- exact processor-owned parent inputs with indexed event identity;
- document-local failure/retry, commit-companion reconciliation and coordinator
  reconstruction through the same-live-engine retained-state seam;
- known current/older states, divergent-state rejection and independent equal
  BlueIds under different DocumentIds;
- both `paths` and direct stable-key `collectionPaths` discovery;
- top-level `FULL_HISTORY`, `FROM_FRONTIER`, and `FROM_NOW` admission;
- shared-child/multi-parent reuse, removal/re-addition, cycles, NBA and
  Wadowice-shaped convergence;
- zero generic fragmentation, unrelated reads, full scans, per-parent source
  replay, and whole post-PROCESS projections.

The exact same-source results, counts, skips, runtime evidence and structural
counters belong in the [RC test report](../releases/3.0.0-rc.1-test-report.md).
A test-count floor is only a regression tripwire; it is not proof that the
requirements above pass.

## Historical metrics

`../blue-basic` is a performance laboratory retained for historical comparison.
It owns step timings, repeated percentile campaigns and before/after reports.
It is neither compiled nor executed by `releaseCheck`. Every correctness
regression must receive a release-owned test even when a matching metrics
scenario exists there.
