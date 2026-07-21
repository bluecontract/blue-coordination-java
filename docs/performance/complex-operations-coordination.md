# Complex operations: Coordination verification

Evidence refreshed on 2026-07-21.

## Dependency baseline

Coordination uses released artifacts from Maven Central:

```text
blue.language:blue-language-java:3.1.0-rc.16
blue.repo:blue-repo-java:3.0.0-rc.10
blue.bex:blue-bex-java:1.1.0-rc.2
```

The build pins the Blue Language version. It does not provide a source-composite,
custom-repository, or version override for that dependency. The staged
Coordination POM must contain the same rc16 coordinate.

## Implemented processing path

The optimized path preserves observable ordering and identity:

```text
eligible Timeline Entry
  -> channel and handler matching
  -> cached SequentialWorkflowPlan
  -> one workflow-owned WorkingDocument
  -> ordered Compute / Update Document / Trigger Event steps
  -> frozen patch preview and handoff
  -> Language validation, gas, routing, handlers, and termination
  -> strict published snapshot, events, gas, markers, and final BlueId
```

The implementation includes:

- bounded access-order workflow and Compute plan caches;
- revisioned, read-only workflow result views instead of whole-prefix copies;
- immutable static Update Document templates;
- frozen Compute and Update Document patch handoff;
- failure-safe plan publication and explicit ownership/close paths;
- generic bounded Language metrics with immutable snapshots;
- deterministic differential, fixture, memory, artifact, and bytecode checks.

## Required invariants

The release-oriented checks require:

- exact final BlueIds, status, gas, and event counts for representative static,
  multi-patch Compute, PayNote, and Mandate scenarios;
- no mutable patch values frozen by built-in Coordination paths;
- no frozen patch-value materialization after handoff;
- frozen values accepted by Language equal frozen values handed off by
  Coordination for the measured scenario delta;
- no singleton Language transactions, stale preview fallbacks, suffix rebases,
  dropped metric names, or materialized workflow document views;
- Java 8 class-file compatibility and public binary compatibility;
- deterministic metrics artifacts and a reproducible source archive.

## Verification commands

```bash
./gradlew clean build \
  workflowPlanDifferentialTest \
  complexFixtureIntegrationTest \
  memoryIntegrationTest \
  languageAdoptionMetricsArtifactTest \
  sourceArchive \
  jmhClasses \
  stageLocalMaven \
  --rerun-tasks --no-daemon --no-parallel

./gradlew dependencyInsight \
  --dependency blue-language-java \
  --configuration runtimeClasspath \
  --no-daemon

git diff --check
```

## Evidence locations

```text
build/reports/tests/
build/reports/language-adoption/scenario-metrics.json
build/reports/language-adoption/scenario-metrics.csv
build/reports/binary-compatibility/blue-coordination-java.txt
build/reports/bytecode/java8-bytecode.txt
build/staging-deploy/
build/distributions/
```

Generated build output, caches, recordings, databases, logs, dumps, and ZIP
inputs are excluded from the source archive.

## Current result

The 2026-07-21 clean JDK 25 validation resolved
`blue.language:blue-language-java:3.1.0-rc.16` as an external Maven module and
completed with no test failures:

| Suite | Tests | Failures |
| --- | ---: | ---: |
| Main test suite | 368 | 0 |
| Workflow-plan differential suite | 124 | 0 |
| Complex-fixture integration suite | 55 | 0 |
| Memory integration suite | 17 | 0 |
| Language-adoption metrics artifact | 1 | 0 |

The four representative artifact scenarios produced these deterministic
semantic results:

| Scenario | Status | Gas | Events | Final BlueId |
| --- | --- | ---: | ---: | --- |
| Mandate authority confirmation | SUCCESS | 9001 | 1 | `3HBTSrB2AZk9RjR4auvjkn9cdz8r6kH6SduBP4atq1co` |
| Multi-patch Compute | SUCCESS | 234 | 0 | `4LbNZzaixg8mw3g5U7rKknkAvmLkM8zZR7LP7XKEEJxi` |
| PayNote resale fixture | SUCCESS | 2268 | 3 | `HF2mzumBAMQSCfnSvdcziwkmu2jX1gf8KVMoBZrcxC8N` |
| Static Update Document | SUCCESS | 174 | 0 | `BEh1MRkKWKg2sG3c7JXDzX1LyMkiS2LEryfVjBrevYqG` |

Every measured scenario had frozen workflow views, zero view misses, zero
singleton Language transactions, zero suffix rebases, zero stale-preview
fallbacks, zero Language patch-value materializations, zero dropped metric
names, and equal frozen patch values accepted and handed to Language. The
published rc16 strict-canonical counter was present for every scenario.

Binary compatibility passed with 26 baseline and 26 current public API
classes. All 84 class files remained Java 8 compatible (maximum class-file
major version 52). The staged POM records rc16 with compile scope. Two
consecutive metrics-artifact and source-archive generations matched
byte-for-byte.
