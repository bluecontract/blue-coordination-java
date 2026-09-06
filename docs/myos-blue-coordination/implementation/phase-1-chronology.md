# Phase 1: executable attachment and history traces

> **Revision:** 15.2 · **Date:** 2026-09-05  
> **Prototype requirement gate: FAIL. G1 is not accepted.**

**Current-stage clarification (r15.3):** this report preserves an earlier exploratory experiment.
The project is in design review; the user has not authorized the planned implementation phase.
The existing test/build files and raw results are retained unchanged as auxiliary evidence. Their
presence is not permission to write more runnable tests or begin a runtime repair. Future work below
is a proposal subject to explicit approval.

## What was implemented

This is the first isolated test slice, not a new scheduler implementation. It runs the actual
Coordination SDK and bundled Contracts with in-memory adapters, content-derived DocumentIds,
exact authored documents and explicit microsecond Timeline entries. No PostgreSQL, mocked
processor, alternate specification, release or migration is involved.

The source baseline is Coordination `20fca9fd9934f367612c27348b8b6532d4029672`, with the existing
locked `published-artifact` dependency mode: Language/Contracts `3.1.0-rc.23`, BEX `1.1.0-rc.4`
and Repository `3.0.0-rc.21`. Local dependency HEADs still match the package provenance; this run
does not substitute sibling source builds for the locked dependency artifacts.

Runtime code is unchanged. Added files are two executable test classes and two shared authored
YAML fixtures. The Gradle addition gives unfinished semantic requirements an explicit independent
lane, using the existing locked test classpaths. It does not disable or weaken existing tests.

## The readable oracle

`T1`, `T3`, etc. mean one fixed microsecond base plus 1, 3, etc. They are not worker wall times.
The test fixtures use the literal marker `Unset` for a value not yet observed; this is not a new
production state type or a requirement to represent absence with a string.

| Trace | At A's T3 observation | At A's T7 observation | Eventually |
|---|---|---|---|
| A attaches B at T1; B becomes 5 at T5 | Unset | 5 | counterB=5 |
| B becomes 5 at T5; A attaches B at T10 | Unset | Unset | counterB=5 after attachment catch-up |

The two histories differ because the attachment input differs. Later reconstruction must preserve
each history; it cannot move an attachment or a reaction to the worker's execution time. The T3
control rejects premature import of B@5 while replaying attachment T1.

The initial-embedding oracle is separate: B becomes 1 at T10, A observes at T20, B becomes 2 at T30.
Reconstructing A later must yield observed=1 and eventual counterB=2. B's already committed source
history must not be recomputed or changed by target replay. These tests check unchanged committed
source receipts; they do not measure physical source recomputation. They also require exactly one
original T20 observation and exactly one application of E to each of R2 and R3, so idempotent final
values cannot hide duplicate replay.

## Results

| Executable case | Result | Evidence / observed discrepancy |
|---|---|---|
| Live attachment T10 after B@5 | PASS | A changes after attachment; earlier T7 observation remains Unset; inherited source cause/order is retained. |
| Live attachment T1 before B@5 | PASS | A changes on B@5; T7 observes 5; A/B receipts share the committing companion. |
| Bounded versus uninterrupted late catch-up | PASS | Ordered before/after BlueIds, epochs, revision kinds, gas, source entries, public events and receipt identities agree. |
| Late FULL_HISTORY reconstruction of attachment T1 | FAIL | A's already-settled original history is not replayed; counterB/observed remain Unset. |
| Late FULL_HISTORY reconstruction of attachment T10 | FAIL | The already-settled attachment is not replayed; counterB remains Unset. |
| Initially embedded B10 / A20 / B30 | FAIL | Retained B import reaches counterB=2, but A@20 is never executed: observed remains Unset instead of 1. |
| E live on R1, then sequential replay on R2/R3 | FAIL | Both new Roots stay at counter=0 instead of replaying E to reach 5; R1's receipts remain unchanged. |

These failures are positive correctness assertions. They are not disabled tests, expected-failure
passes, or golden fixtures changed to match incorrect output. `pocSemanticTest` returns a nonzero
exit code. Passing the ordinary test selection cannot substitute for this requirement lane.

The final rerun retained [attachment JUnit evidence](evidence/attachment-junit.xml) (3 passed,
0 failed/skipped) and [history JUnit evidence](evidence/history-junit.xml) (4 failed, 0 skipped).
The evidence is deliberately red where the requirement is not implemented.

A wider selection also passed 46 existing tests in ContractsClosureAdapterTest,
ManagedSameStateEpochPublicationTest, OperationRouteIndexTest, SourceSurfaceIdentityTest,
SdkRetainedManagedEpochCatchUpTest, SdkManagedEpochCycleAcceptanceTest and
SdkStaticRetainedManagedEpochAdmissionTest. Those regressions do not prove the missing replay
behavior. The final rerun separately repeated the three strengthened attachment controls and
the four new replay requirements against the final test sources.

## Why the previous history example could miss this

The existing `SdkAcceptanceTest.fullHistoryAdmissionIsReadyBeforeALaterDrainReplaysHistory` submits
entries but does not settle them before admission. A subsequent global live drain can process
those entries for the newly present Root. That is useful existing coverage, but it does not prove
target-scoped replay after the global feeder has already passed them.

The new tests first settle the relevant entries. Entries for the absent A are verified NO_MATCH;
B entries are verified APPLIED. Only then is A admitted. This makes the missing replay path visible
and prevents a future test fixture from silently substituting ordinary live processing.

## Confirmed implementation cause

1. `SdkCoordinationRuntime.activationInputs` preserves the FULL_HISTORY policy passed to the engine.
2. `DefaultCoordinationEngine.admitContractsClosure` currently represents it with an admission
   frontier, rather than creating target-owned historical cursor/work.
3. `ContractsClosureAdmissionAdapter` publishes initialized state and any retained managed catch-up
   plans, but does not create the missing target-history replay sequence.
4. `ContractsJournalDrainCoordinator.drainThrough` starts after the global `processedThrough` value.
   A's already-settled entries are therefore never selected for its new historical application.

The initial-embedding result reaching B=2 proves only retained source catch-up. It does not prove
chronological reconstruction of A's own history. No timing delay, PostgreSQL change or cache
optimization can supply that missing semantic work.

## Proposed future library slice — requires approval

Once explicitly authorized, implement the missing behavior in the owning libraries before the MyOS
persistence phase:

1. Create target-owned immutable admission intent/cutoff and explicit historical cursor/work.
   Keep the existing global live frontier and settlements unchanged.
2. Route each historical candidate only to its admitted target and derive a distinct stable
   application settlement. R1 live, R2 replay and R3 replay cannot collide or receive duplicates.
3. Interleave target history with the managed receipts due at that historical point. Preserve
   attachment-time visibility; do not drain every source to its ambient latest head first.
   Historical operation eligibility must differ from the ordinary blanket CATCHING_UP feeder gate.
   Direct reads and external removal/retargeting of a pending embedding need separate tests; the
   latter can require a targeted Contracts change, not merely a Coordination cursor.
4. Prove the above traces, then extend the same slice to nested reconstruction, same-cause grouping,
   final/nonfinal feedback and controlled missing evidence. Do not declare them proved by these
   initial tests or postpone their required semantics until after PostgreSQL integration.

`InMemoryTimelineJournal.nextHistoricalStep` is reusable historical-read machinery, not a complete
replacement scheduler. Resetting the global feeder is not a repair: terminal-entry suppression
still exists, and clearing it would reroute original input through current Roots and risk duplicate
source applications. Flipping a managed-first phase flag is not a chronology algorithm either.

## Run locally

Use the repository's configured Java 17 toolchain. The existing locked dependencies are sufficient.

```sh
./gradlew --offline test --tests blue.coordination.sdk.SdkAttachmentChronologyTest
./gradlew --offline pocSemanticTest
```

The first command runs the three normal regression tests. The second runs four requirement cases
(one parameterized method contributes two) and currently fails. Raw results are in:

- `build/test-results/test/TEST-blue.coordination.sdk.SdkAttachmentChronologyTest.xml`
- `build/test-results/pocSemanticTest/TEST-blue.coordination.sdk.PocHistoryChronologyTest.xml`
- `build/reports/tests/pocSemanticTest/index.html`

Sources:

- `src/test/java/blue/coordination/sdk/SdkAttachmentChronologyTest.java`
- `src/pocTest/java/blue/coordination/sdk/PocHistoryChronologyTest.java`
- `src/test/resources/poc/chronology/source.yaml`
- `src/test/resources/poc/chronology/observer.yaml`

The review archive includes these four source files at their repository-relative paths plus the
[build support patch](build-support.patch). Apply that patch to the recorded Coordination checkout
and use the added source files there; the archive is not a replacement for the complete repository.

## Limits of this evidence

The r15.3 review found two concrete assertion gaps in the unchanged test sources:

- `observe` reads the parent's `/counterB`, not the child's `/child/counter`. The embedded-history
  case establishes the reflected observation but cannot independently rule out future-state leakage
  through a direct child read during the parent's own T20 event.
- The T10-attachment replay case checks the exact T3 observation but not the complete original-entry
  subsequence. Skipping T7, whose expected value is also Unset, could escape the current assertions.

The revised scenario plan therefore requires direct child reads and complete ordered exactly-once
original-event assertions, plus pending-embedding removal/retargeting variants. None was implemented
or run by the r15.3 documentation update. The four existing red cases remain useful counterexamples;
turning them green alone would not satisfy the strengthened acceptance requirements.

This is not the full manifest-driven G1 suite. No complete independent gas oracle, durable restart,
provider completeness conformance, nested positive visibility proof, grouping proof, PostgreSQL
fault test or performance acceptance is claimed. Bounded draining is not a fresh-JVM recovery test.
The YAML/SDK tests use the existing local provider path; Timeline host replacement remains Phase 2.

The documentation also corrects Phase-1 dataset evidence (no PostgreSQL dependency), makes sequential
R1/R2/R3 replay mandatory in CORE, and requires explicit M1 budget joins. There remain 53 schedule
families and now nine closed subcases. Those structural fixes do not turn these red runtime tests
into a G1 pass.
