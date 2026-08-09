# Coordination `basicTest` Round 8 report

Generated from the integrated checkout on 2026-08-08. Every `Actual after`
value below was measured from this source tree. No missing timing or allocation
value is estimated.

## Outcome

Round 8 is integrated and green across correctness, both realistic scenarios,
strict focused performance, and the required 30-sample runtime campaign.
The post-PROCESS complete subscription projection has been removed: every
successful frozen invocation consumes one verified commit companion, and no
request, Timeline Entry, or ordinary node is split.

All declared Round 8 hard host gates pass. Multi-second large-document latency
remains user-visible, but it is overwhelmingly inside frozen
Language/Contracts/BEX processing rather than Coordination-owned work.

## Source identity and scope

| Item | Value |
|---|---|
| Source commit | `d2ccb3b8074560bfa49906a8e8accdde44efca4e` |
| Branch | `feature/graph-focused-approach` |
| OS | Darwin 25.5.0, arm64 |
| Gradle wrapper | 9.6.0 |
| Launcher/test JVM | OpenJDK 26.0.1; Java source compatibility remains 17 |
| Engine budget | 35 classes, 5,196 Java lines |
| New production classes | 0 |

Round 8 changed only the supplied `src/basicTest/**` integration surface and
this report. `gradle/basic-tests.gradle`, `src/main/**`, and `src/myosDemoTest/**`
were not changed by this round. The checkout was already dirty from preceding
rounds; unrelated `Archive.zip` and
`src/coordinationTestSupport/.../CoordinationTestRuntime.java` changes were
preserved.

Net source lines relative to the Round 7 baseline represented by the supplied
patch:

| File | Net lines |
|---|---:|
| `AutonomousRootIsolationTest.java` | 0 |
| `BasicCounterTest.java` | 0 |
| `BasicEngineTestSupport.java` | +2 |
| `BasicRuntimeCampaignTest.java` | -1 |
| `FailureRetryAtomicityTest.java` | +61 |
| `LargeHostEmbeddedPayNotePerformanceTest.java` | 0 |
| `WorkflowInheritanceScalingTest.java` | +8 |
| `BasicCoordinationEngine.java` | +6 |
| `BasicDocumentProcessor.java` | -29 |
| `FrozenBlueRuntime.java` | -29 |
| `InMemoryTimelineJournal.java` | +52 |
| **Total Java** | **+70** |

The four engine-file changes net to zero lines, keeping the engine at the same
5,196-line size as Round 7. `git diff --check` is clean.

## Implemented mechanics

- Admission performs exactly one initial subscription projection against the
  same autonomous ownership Root representation later passed to PROCESS.
- The former complete post-PROCESS projection and evidence-refresh helpers are
  deleted. Per-transition host work now verifies the frozen
  `PlatformCommitCompanion`, validates its subscription membership delta, and
  retains the established active intervals for paired unchanged routes.
- Dynamic parent subscription membership remains unsupported in the compact
  lane and fails closed. Entries beneath autonomous `Process Embedded`
  boundaries remain owned by the child session and are ignored by the parent.
- Exact whole requests and exact Timeline Entries are retained once. There is
  no initial splitting, generic fragmentation, ordinary-node fragmentation, or
  state-only history shortcut.
- Dispatch rollback now snapshots and restores the document graph, receipts,
  embedded coordinator, exact journal frontier, and logical clock as one unit.
  Processor-managed revision events therefore cannot leak from a failed
  attempt or consume timestamp/sequence coordinates.

### Live companion compatibility correction

The supplied candidate assumed that every `subscriptionDelta().added()` entry
could be installed verbatim. The live frozen API disproves that assumption for
ordinary state changes: for the same exact processed Root BlueId, the companion
can emit invocation-local checkpoint/dependency identities that differ from a
fresh projection of that Root. Passing those identities into the next frozen
call fails with:

```text
InvalidExecutionEvidenceException:
Retained active subscription interval header mismatch at //aliceChannel
```

Round 8 therefore treats the exact companion as authoritative for commit
binding and membership change, but retains the already-established interval
for a paired same-route retire/add. This is not a hidden projection: the
post-PROCESS projection count remains zero. `BasicCounterTest` proves four such
retained replacements across two events, and a real second PROCESS call proves
the resulting evidence is accepted by the frozen verifier.

## Verification tasks

| Command | Wall time | Tests | Result |
|---|---:|---:|---|
| `./gradlew compileBasicTestJava --rerun-tasks --no-build-cache` | 2 s | compile | PASS |
| focused Counter + failure atomicity task | 7 s | 4 | PASS |
| `./gradlew basicTest --rerun-tasks --no-build-cache` | 37 s | 20 | PASS |
| `./gradlew basicScenarioTest --rerun-tasks --no-build-cache` | 56 s | 2 | PASS |
| `./gradlew basicPerformanceTest --rerun-tasks --no-build-cache` | 55 s | 4 | PASS |
| `./gradlew basicRuntimeCampaign -PbasicRuntimeDocumentSamples=30 --rerun-tasks --no-build-cache` | 9 m 30 s | 1 | PASS |
| strengthened journal/clock rollback regression | 5 s | 1 | PASS |

There were zero failures, errors, or skips in every completed task. JFR was not
recorded because no declared Round 8 hard Coordination host gate remained
failed.

The machine-readable campaign evidence is at
`build/reports/basicTest/runtime-comparison.json`; its Markdown companion is
`build/reports/basicTest/runtime-comparison.md`.

## Fast paths

`Current` is the integrated Round 7 evidence from the supplied source report.

| Scenario | Current | Round 8 gate | Actual after |
|---|---:|---:|---:|
| Tiny exact append p95 | 0.109 ms | <= 5 ms | **0.128 ms PASS** |
| PayNote-sized exact append p95 | 0.085 ms | <= 15 ms | **0.134 ms PASS** |
| PayNote/tiny append p95 ratio | 0.782x | <= 5x | **1.044x PASS** |
| One-Root route lookup p95 | 0.025 ms | <= 1 ms | **0.023 ms PASS** |
| Generic request fragments | 0 | 0 | **0 PASS** |
| Generic Timeline Entry fragments | 0 | 0 | **0 PASS** |
| Ordinary nested fragments | 0 | 0 | **0 PASS** |

## Coordination-owned host work

| Scenario | Round 7 host | Preferred / hard | Actual after | Result |
|---|---:|---:|---:|---|
| Counter PROCESS p95 | 25.611 ms | 10 / 25 ms | **0.877 ms** | PASS preferred |
| 1-vs-61 workflow host p95 delta | 236.381 ms | 30 / 60 ms | **2.571 ms** | PASS preferred |
| Large host, cold | 674.686 ms | 100 / 175 ms | **12.653 ms** | PASS preferred |
| Large host, warm | 675.946 ms | 100 / 175 ms | **12.460 ms** | PASS preferred |
| PayNote authorization #1 + parent | 907.419 ms | 150 / 250 ms | **49.098 ms** | PASS preferred |
| PayNote authorization #2 + parent | 886.903 ms | 150 / 250 ms | **32.664 ms** | PASS preferred |
| Restaurant confirmation + parent | 897.047 ms | 150 / 250 ms | **31.234 ms** | PASS preferred |
| Attach + initialize PayNote | 1,851.942 ms | 750 / 1,000 ms | **673.730 ms** | PASS preferred |

The warm large-host companion-delta application itself took 0.074 ms and the
embedded-only structural-sharing layout took 5.218 ms. The host budget above
is dispatch minus frozen PROCESS and layout, matching the strict test gate.

## User-visible total and frozen semantic floor

| Operation | Round 7 total | Actual total | Actual frozen | Actual Coordination host* |
|---|---:|---:|---:|---:|
| Counter frozen PROCESS p95 | ~284 ms | ~264.863 ms | 263.986 ms | 0.877 ms |
| Large host, cold | 4,162.634 ms | 3,550.451 ms | 3,499.820 ms | 12.653 ms |
| Large host, warm | 4,134.757 ms | 3,561.825 ms | 3,511.577 ms | 12.460 ms |
| PayNote authorization #1 + parent | 6,326.019 ms | 5,575.373 ms | 5,486.169 ms | 49.098 ms |
| PayNote authorization #2 + parent | 6,069.980 ms | 5,378.096 ms | 5,306.593 ms | 32.664 ms |
| Restaurant confirmation + parent | 6,321.259 ms | 5,527.959 ms | 5,458.129 ms | 31.234 ms |
| Attach + initialize PayNote | 10,048.504 ms | 8,969.291 ms | 8,257.531 ms | 673.730 ms |

`*` Append and embedded-only layout are reported separately by the test and are
not folded into the strict host gate. These figures make the remaining limit
explicit: Coordination overhead is now small, but frozen semantic processing
still dominates user-visible latency.

## Thirty-sample campaign

| Scenario | n | p50 | p95 | p99 | max | Gate |
|---|---:|---:|---:|---:|---:|---|
| Counter frozen PROCESS | 30 | 256.605 | 263.986 | 265.348 | 265.348 | frozen floor |
| Counter host overhead | 30 | 0.694 | 0.877 | 1.308 | 1.308 | PASS hard/preferred |
| Existing child, 20 revisions | 30 | 38.663 | 41.297 | 44.486 | 44.486 | PASS |
| Late child, 20 source entries | 30 | 103.625 | 106.820 | 107.592 | 107.592 | diagnostic miss |
| Nested Root -> Emb1 -> Emb2 | 30 | 82.600 | 85.860 | 87.142 | 87.142 | diagnostic miss |
| NBA catch-up, five revisions | 30 | 90.548 | 93.645 | 93.738 | 93.738 | PASS hard/preferred |
| Live child fan-out to two parents | 30 | 31.094 | 32.333 | 34.872 | 34.872 | diagnostic miss |

All values are milliseconds. The late-child, nested, and fan-out targets are
legacy diagnostic targets, not Round 8 hard gates; they remain explicitly red
in the generated report rather than being relabelled as passes.

## Work-shape evidence

The warmed 30-call Counter campaign observed:

```text
frozen PROCESS calls                           30
commit companion deltas consumed               30
post-PROCESS complete projections               0
concrete ownership Root inputs                 30
reference-only event inputs                    30
retained subscription intervals                60
layout total                                1.456 ms
companion-delta handling total              1.531 ms
request split calls                             0
Timeline Entry split calls                      0
ordinary-node split calls                       0
```

The strict large-host run likewise observed zero post-PROCESS projections and
one companion-delta application per frozen call for attach, host, PayNote, and
nested restaurant operations.

## Failure atomicity proof

`failedProcessorManagedParentRevisionRestoresJournalFrontier` injects failure
after the child revision has been applied but before dispatch publication. It
proves:

```text
failed attempt journal delta                    0
journal rollback counter                        1
parent epoch after failure                      0
embedded links after failure                    0
retry committed internal journal delta          1
parent epoch after retry                        2
duplicate redispatch journal delta              0
next timestamp             attachment + 2 exactly
next global sequence       attachment + 2 exactly
```

The last two assertions prove that both the logical clock and journal sequence
frontier were restored, not merely that leaked entries were hidden.

## Frozen siblings

The frozen siblings were not modified:

| Repository | Commit | Status |
|---|---|---|
| `blue-language-java` | `c3d58561220e6de6be6e302cb16799c1a1b5159f` | clean |
| `blue-bex-java` | `3ebd2d93be7f24ce44840f0aba02b1c40c27f5f8` | clean |
| `blue-repository-java` | `63be6b7d8d2752b5a8c90f38e672859e9b3949a1` | 1,581 pre-existing dirty lines |
