# Build and test

## Prerequisites

Use Java 17+ and the checked-in Gradle wrapper. Production compiles with Java 17,
`-Xlint:all` and `-Werror`. Tests can run on a newer LTS with
`-PtestJavaVersion=21`.

Two dependency modes are intentional:

- `local-composite` is for development. It substitutes `../blue-bex-java` and
  `../blue-repository-java`, or paths supplied with
  `-PblueBexCompositePath` and `-PblueRepositoryCompositePath`.
- `published-artifact` is the clean consumer/release path. It runs no Git
  commands and never reads sibling checkouts.

## Coordination gates

```bash
./gradlew test -PblueDependencyMode=local-composite
./gradlew integrationTest consumerTest scenarioTest \
  -PblueDependencyMode=local-composite
./gradlew releaseCheck -PblueDependencyMode=local-composite
./gradlew stageRelease -PblueDependencyMode=local-composite
```

The release-owned suites have distinct responsibilities:

- `test` exercises public value contracts, internal atomic primitives and
  retained workflow/BEX processor semantics.
- `integrationTest` exercises exact append, engine-selected drain, entry-frame
  ordering, document-local atomic retry, embedded-only storage, `paths` and
  `collectionPaths`, synchronized catch-up, reattachment and ownership.
- `consumerTest` compiles against the built production JAR, never main source
  output or test fixtures, and verifies the supported public API as a real
  consumer sees it.
- `scenarioTest` runs NBA admission-order/multi-game convergence and the
  complete large-host/PayNote lifecycle.

`releaseCheck` runs all four suites. It also enforces minimum suite depth,
validates the production class/line budget and small application API boundary,
scans the production JAR, validates POM scopes and versions, and checks legal,
documentation, source and Javadoc artifacts. The current verified status is
recorded in the RC report; commands listed here are gates to run, not claims
that a changed source snapshot has passed them.
`stageRelease` creates a Maven Central-shaped repository at
`build/staging-deploy`.

To prove external dependency availability:

```bash
./gradlew dependencyPreflight -PblueDependencyMode=published-artifact
```

That command is expected to fail closed until every pinned prerequisite has
been published.

## Historical performance evidence

`../blue-basic` is deliberately outside the library's correctness and release
gate. It retains historical step timings, percentile campaigns and comparative
metrics so performance investigations remain reproducible without coupling the
published library to a sibling checkout. Run it only when collecting or
comparing performance evidence:

```bash
./gradlew publishToMavenLocal -PblueDependencyMode=local-composite
../blue-basic/gradlew -p ../blue-basic performanceTest runtimeCampaign
```

The runtime campaign is deliberately slower: it collects repeated samples so
percentile comparisons are not based on one noisy run. A missing or failing
`../blue-basic` checkout cannot make `releaseCheck` pass or fail.

See [test strategy](test-strategy.md) for the behavior-to-suite map and the
rules that prevent release verification from drifting back into a demo module.

## Lock files

Regenerate the appropriate dependency lock only after an intentional version
change:

```bash
./gradlew dependencies --write-locks -PblueDependencyMode=local-composite
./gradlew dependencies --write-locks -PblueDependencyMode=published-artifact
```

Review the entire lock diff. Never hand-wave an unexpected transitive version.
