# Test strategy

Blue Coordination keeps release correctness inside this repository. The
library does not depend on a demo project, a metrics campaign or an adjacent
consumer checkout to prove that it works.

## Verification layers

| Suite | Boundary | Primary guarantees |
| --- | --- | --- |
| `test` | SDK, types, compiler and compact internals | Immutable SDK values, authored closure compilation, exact targeting/results, closed inputs, graph/cursor immutability, exact event occurrences, workflow state and BEX accounting |
| `integrationTest` | In-memory engine with public operations | Append/process separation, engine-selected drain, entry-frame ordering, admission, collection paths, catch-up barriers, identity, ownership, atomic retry and removal/re-addition |
| `consumerTest` | Built production JAR only | SDK compilation without compiled main, `testFixtures`, or test-support outputs; runtime dependency completeness and representative managed-document behavior |
| `scenarioTest` | Complete business lifecycles | Multi-order NBA convergence and the large host/PayNote lifecycle |

These are execution boundaries, not buckets sized by test count. `consumerTest`
is intentionally small because its distinct value is compiling and running
against the built JAR without compiled production output on its compile
classpath. `scenarioTest` stays separate because complete lifecycle,
convergence, and scale cases form the slow acceptance lane.

The suites intentionally overlap at important boundaries. Atomicity has focused
integration coverage and is exercised again by realistic scenarios. The
consumer suite repeats representative behavior because compilation and
execution against the JAR catch packaging and dependency mistakes that
source-based tests cannot. The complete graph resolves from Maven Central in
every suite and CI repeats the release gate on Java 17 and Java 21.

`./gradlew check` runs `test`, `integrationTest`, and `consumerTest` for the
standard development gate. `releaseCheck` adds `scenarioTest` and is the
required final gate. This keeps slow scenarios out of routine checks without
weakening release evidence.

Parallel execution uses separate test JVMs, controlled by
`-PtestMaxParallelForks`; methods inside a class remain serial. The PayNote
completion, cancellation, adjustment, and late-refusal cases each own a
separate test class and a fresh campaign. Shared scenario helpers contain no
executable tests and do not share mutable runtime state between campaigns.

The execution-scope gates discover classes from compiled test output through
JUnit, then compare them with the executed XML inventory. They fail on missing
or unexpected classes, failures, skips, empty suites, or filtered/excluded test
tasks. The reports retain each executed case, including parameterized
invocations. This complements source architecture and conformance checks;
it does not replace any scenario, public API, artifact, or release gate.

Shared engine fixtures live in `src/testSupport`. This private, non-executable
source set contains no `@Test` methods, is available to `integrationTest` and
`scenarioTest`, and is not published. In particular, scenarios do not compile
against integration-test output; the two suites depend independently on the
same support layer.

## Given/When/Then structure

Every `@Test` has exactly one meaningful lowercase sequence:

```java
// given

// when

// then
```

Setup belongs under `given`, the behavior being exercised under `when`, and
observable outcomes under `then`. Exception tests may prepare an `Executable`
under `when` and assert it under `then`. `verifyTestArchitecture` rejects
missing, duplicated, or misordered markers across all four executable test
suites; `testSupport` contains no executable tests.

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
- full-history and exact-frontier top-level admission, including initialized
  READY state before the later replay drain and strict frontier exclusion;
- an operation-produced Order draft admitted as a new `FROM_NOW` lineage;
- five effective occurrences mapped to three new lineages, including duplicate
  lineage reuse and declaration-order permutations;
- sequential nested growth in which an applied operation-created parent later
  creates its own managed child;
- managed-draft preflight, including cyclic-target and cycle-crossing rejection
  before append, exact-path/value completeness, atomic rollback, and
  deterministic retry failure matrices;
- immutable exact values, owner-bound handles and drafts, and a consumer
  compiled from the built JAR;
- the canonical developer-guide narrative compiled against the built JAR:
  a complete provider entry against an initially known cyclic closure, plus
  sequential operations from several Timelines, atomic Receipt draft attachment
  from the acyclic Payment member, and a later operation on the newly active
  Receipt lineage.

Operation-result managed admission is deliberately limited to new `FROM_NOW`
lineages. Acceptance tests prove that a known imported epoch and every
historical/frontier/attach-current/passive activation request fail before
append, without partial document or topology mutation. The final conformance
decision is rechecked by the complete published-dependency acceptance and
fixture corpus; a focused source-suite pass alone is insufficient.

## Recovered topology evidence

The recovered cyclic-topology branch is verified with the bounded focused
campaign: A-B-A, A-B-C-A, five-member shared-A, disconnected cycles, detach and
split, post-detach termination, remove/re-add, 1,000-unrelated locality, and
the short topology smoke. The old long percentile campaign is not rerun for the
SDK delta. Its retained receipts are historical evidence and remain unchanged.

The committed topology identity artifact is checked against current runtime
evidence. Complete Gradle runs record evidence from each of its 20 original
JUnit contributors, then compare the ordered JSON and Markdown after all test
workers finish. Every contributor is required exactly once; all 32 scenario
identities and the original fresh-engine repeat counts remain mandatory.
Focused exporter runs retain the direct campaign. Collection changes when the
existing cases record evidence; it does not share engines or checkpoints
between cases. See [build and test](build-and-test.md#topology-identity-evidence)
for the finalizer and report locations.

The 1,000-public-root managed catch-up locality fixture uses small independent
admission batches. All 1,000 distinct documents are still admitted and
initialized through the public path before measuring the same catch-up
counters. The other 1,000-document topology fixtures retain their original
admission batches and public-root declarations. Gas budgets, scale, structural
assertions, and retry/restart matrices remain unchanged.

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
