# T04 — Preserve Update Document preview failures

Verified on 2026-09-16 against baseline
`c095f0f3321257531096289eb27a5f439c5011fb` (Coordination 3.0.0-rc.10).
Implementation branch: `fix/t04-preserve-update-failures`.

## Verdict and change

PROC-01 is reproduced for unavailable execution evidence and semantic patch
errors. Both `StepExecutionContext` preview wrappers converted the published
processor's typed exceptions into `ProcessorFatalException` with
`RuntimeExecutionFailure`. A valid SDK operation updating `/payload/count`,
where `/payload` is an ordinary reference whose exact content is unavailable,
therefore returned `REJECTED` instead of `NEEDS_RESOURCES`. The same patch
through Compute suspended correctly. Providing the content made both succeed.

The wrappers now use the existing `ComputeStepExecutor.classifiedBoundaryFailure`
policy before translating unexpected exceptions to runtime-fatal diagnostics.
The original typed exception, resource identities, diagnostics and causal
precedence are preserved. Mutable and frozen previews follow the same policy.
The Compute emitter's obsolete comment was updated; its behavior is unchanged.
No dependencies, public interfaces, gas schedules or runtime identities changed.

## Selected profile

The build uses `blueDependencyMode=published-artifact`, Language/Contracts
3.1.0-rc.25, BEX 1.1.0-rc.6 and Repository 3.0.0-rc.22. No local Language
checkout is substituted. SDK tests select the bundled default rooted profile,
Contracts digest
`sha256:e91381c970859a6bafecdd99e46f5115ba033bf0534be84bbd5582531e9e347f`.

Gradle 9.6.0 runs on Temurin 25.0.4.1; the primary test launcher is Temurin
17.0.20.1. The Java 8 convention of the upstream Language repository is not
the launcher for this Coordination root test suite.

## Reproduction and regression evidence

The focused test is
`src/test/java/blue/coordination/processor/workflow/IndependentT04RegressionTest.java`.
Run it from the repository root:

```sh
bash ./gradlew :test --tests blue.coordination.processor.workflow.IndependentT04RegressionTest --no-daemon --max-workers=2 -PtestJavaVersion=17
```

The final 21-case matrix produced 13 expected failures and 8 passing controls
on the untouched baseline. All 21 passed with the fix. The earliest regression
was recorded before production edits: both direct preview methods threw
`ExecutionEvidenceUnavailableException`, while their wrappers threw
`ProcessorFatalException`; the SDK mapped that conversion to
`REJECTED / RUNTIME_EXECUTION_FAILURE`.

Published rc.25 distinguishes retryable provider `UNAVAILABLE` from definitive
`NOT_FOUND`. Direct previews return `ExecutionEvidenceUnavailableException`
for the former and `InvalidExecutionEvidenceException` for the latter or for
mismatched exact content. Tests preserve this distinction, including the
required exact BlueIds. An SDK provider returning mismatched content remains
rejected in both Update and Compute; its existing host-level conversion to a
runtime failure is outside this fix.

Coverage includes mutable/frozen parity, native evidence failures, an actual
`INVALID_PATCH` error, unexpected runtime failures, empty/null patch lists,
failed multi-patch working-state rollback, and typed gas/portable-limit fault
injection. An SDK sequence buffers an event and previews an earlier successful
patch before suspension: no exact state, checkpoint, event, operation route,
managed epoch or transition receipt advances. After providing the content and
restarting from stores, the same retained entry and closure commit one epoch
and one event. Reprocessing does not commit a duplicate.

The successful SDK gas calibration is 561. Budget 560 rejects with 557 admitted
gas. Budget 346 rejects `patchAddOrReplace` with 327 admitted gas. These values
and all three gas trace identities are identical before and after the fix.
Rejected attempts expose no commit companion, rooted publication projection,
checkpoint writes, public events or document changes.

The stronger original gas-preview claim is not reproduced: rc.25 documents
working previews as unmetered, and the natural mutation-charge rejection in
this fixture occurs after preview. Gas and portable-limit preservation inside
the adapter is tested through explicit fault injection, not presented as a
natural SDK reachability proof. Existing sticky gas rejection already prevented
commit on the baseline.

## Validation and retained artifacts

The focused run, including `CoordinationProcessorsTest`, `StaticUpdatePlanTest`,
`ComputeEffectPlanTest` and `RootedGasBoundaryTest`, passed 84 tests with no
failures or skips. The unfiltered Java 17 root suite passed 926 tests in 179
classes, with zero failures, errors or skips, in 10 minutes 8 seconds.
`verifyCyclicTopologyIdentityEvidence`, `dependencyPreflight`,
`verifyPublishedDependencyIsolation` and `verifyTestArchitecture` also passed.
The final focused run verifies the test-formatting cleanup made while the
full suite was running; production code was unchanged throughout those runs.

Commands executed in the fix worktree (logs named below):

```sh
bash ./gradlew :test --tests blue.coordination.processor.workflow.IndependentT04RegressionTest --tests blue.coordination.processor.CoordinationProcessorsTest --tests blue.coordination.processor.workflow.StaticUpdatePlanTest --tests blue.coordination.processor.workflow.ComputeEffectPlanTest --tests blue.coordination.sdk.RootedGasBoundaryTest --no-daemon --max-workers=4
bash ./gradlew :test dependencyPreflight verifyPublishedDependencyIsolation --no-daemon --max-workers=4 -PtestJavaVersion=17 -PtestMaxParallelForks=4
bash ./gradlew verifyTestArchitecture --no-daemon --max-workers=2
bash ./gradlew dependencyInsight --dependency blue-contracts-core --configuration testRuntimeClasspath --no-daemon
bash ./gradlew dependencyInsight --dependency blue-bex-contracts --configuration testRuntimeClasspath --no-daemon
bash ./gradlew :test --tests blue.coordination.processor.workflow.IndependentT04RegressionTest --no-daemon --max-workers=4 -PtestJavaVersion=17
```

Local evidence is retained under `/private/tmp/blue-coordination-t04-evidence/`:

- `baseline-confirmed.log` and `.xml`: pre-edit boundary/SDK failure.
- `baseline-final-matrix.log` and `.xml`: final tests on unchanged production.
- `focused-and-neighbors-final.log` and `focused-xml/`: 84 passing checks.
- `full-java17.log`: unfiltered root suite and dependency verification.
- `full-java17-xml/` and `full-java17-counts.json`: retained complete JUnit
  results, preserved before the final focused run replaced Gradle's report.
- `final-focused-java17.log` and `final-focused-java17.xml`: final regression.
- `test-architecture.log`: repository test-structure verification.
- `contracts-dependency-insight.log` and `bex-dependency-insight.log`: exact
  selected versions and dependency paths.
- `gradle-version.txt`: exact wrapper and launcher versions.
- `runtime-artifacts.json`: coordinates, paths and SHA-256 hashes of the nine
  pinned Blue runtime artifacts, rehashed unchanged after all tests.
  Contracts JAR SHA-256 is
  `528ef7b02fb8b332054d6a1be010714ff3ccb4a0dcd71557afb3f3d0036fc552`.

Baseline worktree: `/private/tmp/blue-coordination-t04-baseline`.
Fix worktree: `/private/tmp/blue-coordination-t04`.

This closes PROC-01's reproduced category-loss seam and the corresponding
PROC-16 regression gap. It does not close all PROC-16 findings or claim full
conformance, a release qualification, upstream fixes, or natural reachability
of every fault-injected exception. Separate integration/consumer/scenario
source sets and a Java 21 release gate were not run for this scoped fix.
