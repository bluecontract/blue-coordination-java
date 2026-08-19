# Test strategy

Blue Coordination keeps release correctness inside this repository. The
library does not depend on a demo project, a metrics campaign or an adjacent
consumer checkout to prove that it works.

## Verification layers

| Suite | Boundary | Primary guarantees |
| --- | --- | --- |
| `test` | SDK, types, compiler and compact internals | Immutable SDK values, authored closure compilation, exact targeting/results, closed inputs, graph/cursor immutability, exact event occurrences, workflow state and BEX accounting |
| `integrationTest` | In-memory engine with public operations | Append/process separation, engine-selected drain, entry-frame ordering, admission, collection paths, catch-up barriers, identity, ownership, atomic retry and removal/re-addition |
| `consumerTest` | Built production JAR only | SDK compilation without main-source output or test fixtures, runtime dependency completeness and representative managed-document behavior |
| `scenarioTest` | Complete business lifecycles | Multi-order NBA convergence and the large host/PayNote lifecycle |
| extracted SDK consumer | Staged JAR/POM/module graph only | Exact rc.2 dependency graph and standalone SDK execution on Java 17 and Java 21 without composites or Maven Local |

The suites intentionally overlap at important boundaries. Atomicity has focused
integration coverage and is exercised again by realistic scenarios. The
consumer suite repeats representative behavior because compilation and
execution against the JAR catch packaging and dependency mistakes that
source-based tests cannot.

## SDK freeze acceptance

SDK acceptance tests stay in `blue.coordination.sdk`, use public facade values,
and never construct `ClosureInvocationInput`, component snapshots, occurrence
bindings, cyclic proofs, or an internal evidence factory. The current suite
proves:

- counter `+3/-1` through exact targeted operations;
- target isolation from an unrelated PayNote;
- terminal broadcast `NO_MATCH` and precise missing-target `REJECTED`;
- finite two-, three-, and five-member cyclic shapes with exact step order,
  epochs, BlueIds, gas, changes, and public events;
- two disconnected affected closures retained as independent results;
- shared-gas loop rollback and deterministic retry evidence;
- detach followed by a terminating call;
- remove/re-add with fresh authenticated cyclic identities;
- append-only `submit()` parity with `execute()`;
- immutable owner-bound values and a consumer compiled from the built JAR.

Managed-child creation is a characterized unsupported boundary, not a passing
semantic claim. The test verifies that `request.managed(...)` plus
`expectOccurrence(...)` fails before append with
`UNSUPPORTED_MANAGED_DRAFT_ADMISSION` and leaves state unchanged. The requested
Order-draft and five-child/duplicate-lineage acceptance scenarios remain open
until a real Contracts host-invocation bridge exists. The conformance receipt
must list those gates as unresolved and keep
`implementationConformanceClaimed=false`.

The extracted `staged-sdk-consumer/` is a second consumer boundary, not a
duplicate source test. It resolves only the staged file repository and runs on
both Java 17 and Java 21. A source-composite pass cannot substitute for it.

## Recovered topology evidence

The recovered cyclic-topology branch is verified with the bounded focused
campaign: A-B-A, A-B-C-A, five-member shared-A, disconnected cycles, detach and
split, post-detach termination, remove/re-add, 1,000-unrelated locality, and
the short topology smoke. The old long percentile campaign is not rerun for the
SDK delta. Its retained receipts are historical evidence and remain unchanged.

## Historical Round 10.1 semantic gates

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
- copy-on-write multi-head success, stale-CAS rejection, pre-swap injected
  failure rollback, and disconnected transaction isolation at the unwired
  closure-publication store seam;
- known current/older states, divergent-state rejection and independent equal
  BlueIds under different DocumentIds;
- both `paths` and direct stable-key `collectionPaths` discovery;
- top-level `FULL_HISTORY`, `FROM_FRONTIER`, and `FROM_NOW` admission;
- shared-child/multi-parent reuse, removal/re-addition, cycles, NBA and
  Wadowice-shaped convergence;
- zero generic fragmentation, unrelated reads, full scans, per-parent source
  replay, and whole post-PROCESS projections.

The exact same-source results, counts, skips, runtime evidence and structural
counters for that historical candidate belong in the
[rc.1 test report](../releases/3.0.0-rc.1-test-report.md).
A test-count floor is only a regression tripwire; it is not proof that the
requirements above pass.

## Historical metrics

`../blue-basic` is a performance laboratory retained for historical comparison.
It owns step timings, repeated percentile campaigns and before/after reports.
It is neither compiled nor executed by `releaseCheck`. Every correctness
regression must receive a release-owned test even when a matching metrics
scenario exists there.
