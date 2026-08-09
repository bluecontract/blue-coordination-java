# Coordination `basicTest` Round 9 implementation report

## Result

Round 9 is implemented and closed. The permanent branch passes compilation,
the six-test smoke gate, 22 correctness tests, two realistic scenarios, four
strict performance tests, and the required 30-sample campaign with zero
failures, errors, or skips. All declared hard performance gates pass.

## Source and baseline

| Item | Value |
|---|---|
| Source commit | `d2ccb3b8074560bfa49906a8e8accdde44efca4e` |
| Branch | `feature/graph-focused-approach` |
| Kit archive SHA-256 | `8320fbbc30c5a9f0f54ffa03e7c4aa60e649ab846f423e0a4d4674e6b6626e0d` |
| OS | Darwin 25.5.0, arm64 |
| Gradle wrapper | 9.6.0 |
| JVM | OpenJDK 26.0.1; source compatibility 17 |
| Baseline engine | 35 classes, 5,196 Java lines |
| Round 9 engine | 35 classes, 5,198 Java lines |
| New production classes | 0 |

The checkout was already dirty when Round 9 began. It contained prior-round
`basicTest` work plus unrelated `Archive.zip`, `BasicCounterTest`, and
`src/coordinationTestSupport/.../CoordinationTestRuntime.java` changes. Round 9
preserved those changes and did not modify `src/main`, `src/myosDemoTest`, or
the frozen sibling repositories.

Frozen sibling state at final audit:

| Repository | Commit | Status |
|---|---|---|
| `blue-language-java` | `c3d58561220e6de6be6e302cb16799c1a1b5159f` | clean |
| `blue-bex-java` | `3ebd2d93be7f24ce44840f0aba02b1c40c27f5f8` | clean |
| `blue-repository-java` | `63be6b7d8d2752b5a8c90f38e672859e9b3949a1` | 1,581 pre-existing status lines |

## Round 9 files

Permanent implementation and diagnostics:

```text
gradle/basic-tests.gradle
src/basicTest/java/blue/coordination/basic/SameDocumentInitialIdentityTest.java
src/basicTest/java/blue/coordination/basic/WholeObjectFailureHygieneTest.java
src/basicTest/java/blue/coordination/basic/engine/BasicDocumentProcessor.java
src/basicTest/java/blue/coordination/basic/engine/EmbeddedOnlyLayoutBuilder.java
```

Documentation and reports:

```text
docs/basic-test-engine.md
docs/basic-test-current-state.md
docs/FROZEN_AUTONOMOUS_OWNERSHIP_GAP.md
ROUND9_IMPLEMENTATION_REPORT.md
ROUND9_RUNTIME_BEFORE_AFTER.md
ROUND9_TEST_RESULTS.json
```

The four main-patch paths match the kit candidate files byte for byte.

## Main hardening patch

- `basicSmokeTest` now covers architecture/complexity, Counter, whole-request
  parity, autonomous Root isolation, and processor-managed journal rollback.
- `SameDocumentInitialIdentityTest` proves that an existing progressed child is
  reused only when the attachment supplies its exact original initial state.
- Companion retirement now resolves the established interval from the active
  map and requires the exact stable route to match; a shared occurrence key is
  not sufficient.
- The private child processing helper is explicitly named
  `autonomousOwnershipProjection`.

## Same-document identity result

The regression starts and progresses one child, catches parent A up from the
exact original child, and then makes parent B attach the same `DocumentId` with
a conflicting initial BlueId. Parent B fails atomically: its epoch and links
roll back, the child history is unchanged, and parent A remains consistent.

The broader same-document matrix is green: original-state reuse, rejection of
current processed state, shared child/two parents, concurrent unseen creation,
detach/reattach cursor resume, replacement, and cycle rollback.

## Exact semantic-Root experiment

The isolated experiment made `processingRoot BlueId == semanticRoot BlueId`.
The required focused task ran six tests; five failed with:

```text
InvalidExecutionEvidenceException:
Retained active External Channel surface does not match the exact Root
(omitted=1, extra=0)
```

At the exact probe, both Root BlueIds were
`DxuR4ZFzD9YvC63Eboyf7pDmdU5evWBh6ET47kfidayZ`, the selected child occurrence
was `/child`, and the exact surface included
`//coordinationEmbeddedChannel` and `//ownerChannel`. The parent does not own
the child's external channel, so frozen delivery derivation rejected the
processor-managed revision before a second frozen call.

The experiment was completely reverted. No probe, experimental method, or
alternate planner remains. The missing public capability and full counters are
documented in `docs/FROZEN_AUTONOMOUS_OWNERSHIP_GAP.md`.

## Whole-object failure hygiene

Twenty identical dispatch attempts were failed after frozen PROCESS and before
publication. The whole-object counts were:

```text
before       6
attempts     [6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
              6, 6, 6, 6, 6, 6, 6, 6, 6, 6]
afterCommit  9
```

Each failure kept the epoch at zero, links empty, journal size and coordinates
unchanged, and clock restored. Counters recorded 20 frozen calls, 20
transaction retries, and 20 journal rollbacks. A successful dispatch followed
by an exact duplicate invoked frozen processing only once and produced the
expected state. Because identical failures stabilize immediately, no store
transaction or full-map copy was added.

## Verification

| Task | Wall time | Tests | Failures/errors/skips | Result |
|---|---:|---:|---:|---|
| Baseline `compileBasicTestJava` | 2 s | compile | 0 | PASS |
| Baseline `basicTest` | 36 s | 20 | 0 | PASS |
| Baseline `basicScenarioTest` | 55 s | 2 | 0 | PASS |
| Baseline `basicPerformanceTest` | 54 s | 4 | 0 | PASS |
| Permanent `compileBasicTestJava` | 2 s | compile | 0 | PASS |
| `basicSmokeTest` | 7 s | 6 | 0 | PASS |
| Permanent `basicTest` | 42 s | 22 | 0 | PASS |
| Permanent `basicScenarioTest` | 55 s | 2 | 0 | PASS |
| Permanent `basicPerformanceTest` | 53 s | 4 | 0 | PASS |
| `basicRuntimeCampaign` (30 samples) | 9 m 18 s | 1 | 0 | PASS task |
| Rejected exact-Root focused experiment | 9 s | 6 | 5 failures | REJECTED/REVERTED |

The campaign deliberately retains three red legacy diagnostic targets for late
admission, nested catch-up, and two-parent fan-out. They are not Round 9 hard
gates, existed in Round 8, and changed by -3.2%, -3.4%, and +3.7%
respectively—well inside the experiment's 20% no-regression limit.

## Performance result

Round 9 p95 values include 0.120 ms tiny append, 0.096 ms PayNote append,
0.023 ms one-Root routing, 0.846 ms Counter host overhead, and 2.798 ms host
delta from one to 61 workflows. Strict large-document host values are 12.145
ms warm host, 48.258 ms PayNote authorization #1, 31.531 ms restaurant
confirmation, and 647.403 ms attachment/initialization. Full before/after data
is in `ROUND9_RUNTIME_BEFORE_AFTER.md`.

No request, Timeline Entry, or ordinary nested value was split, and no complete
post-PROCESS subscription projection ran.

## Latency ownership

User-visible latency remains dominated by the frozen semantic stack. PayNote
authorization #1 takes 5,374.147 ms end to end: 5,288.068 ms frozen PROCESS,
31.516 ms append, 5.576 ms layout, and 48.258 ms Coordination host. Attachment
takes 8,619.464 ms, of which 7,934.261 ms is frozen and 647.403 ms is host.
These are multi-second operations; the host-only figures must not be presented
as total latency.

## Remaining limitations

- Exact semantic-Root processing cannot exclude autonomous child-owned
  subscription surfaces through the current frozen public API.
- Same-route companion intervals retain established evidence because the live
  invocation-local replacement is rejected by the next frozen verifier.
- Historical completeness is local to the in-memory journal.
- Distinct failed results may leave unreachable immutable cache objects.
- Dispatch is synchronized; durable/distributed transactions are out of scope.
- Dynamic parent membership, `Process Embedded` collections, and inferred
  arbitrary history frontiers fail closed.

## Stop recommendation

Stop after Round 9. The main patch is green, the exact-Root question has an
evidence-backed frozen-API answer, immutable retry hygiene is bounded for
identical failures, and every hard host-performance gate passes. Further work
on semantic identity belongs behind a new frozen Contracts ownership API, not
inside another Coordination planner, projector, cache, or processor.
