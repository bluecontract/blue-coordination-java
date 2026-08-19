# Build and test

## Prerequisites

Use Java 17+ and the checked-in Gradle wrapper. Production compiles with Java 17,
`-Xlint:all` and `-Werror`. Tests can run on a newer LTS with
`-PtestJavaVersion=21`.

`local-composite` is the default implementation mode for the coordinated
Contracts 1.0 source tree. It substitutes `../blue-language-java`,
`../blue-bex-java`, and `../blue-repository-java`, or paths supplied with
`-PblueLanguageCompositePath`, `-PblueBexCompositePath`, and
`-PblueRepositoryCompositePath`. It is source-backed implementation evidence,
not evidence that external consumers can resolve published artifacts.
The Language substitution is an aligned source graph: model, core, mapping,
IPFS, the runtime aggregate, and Contracts all map to their projects in the
same included build. Mixing a source-built Contracts kernel with published
Language runtime jars is rejected by `verifyLocalCompositeDependencies`.

Run the focused local wiring proof with:

```bash
./gradlew verifyLocalCompositeDependencies \
  -PblueDependencyMode=local-composite \
  -PblueLanguageCompositePath=/absolute/path/to/blue-language-java
```

`verifyLocalSourceInputs` checks the explicitly configured Language and BEX
checkouts against their base commits and framed tracked/untracked production
workspace fingerprints. Dirty, intentional workspaces are supported without
weakening provenance. The extracted-source smoke forwards the same absolute
paths, so its temporary extraction directory cannot accidentally change which
sibling checkouts are selected.

Use the isolated published-artifact lane explicitly:

```bash
./gradlew verifyPublishedDependencyIsolation dependencyPreflight \
  -PblueDependencyMode=published-artifact
```

This mode includes no sibling builds and runs no local-source Git checks. It is
the resolution-isolation proof. `verifyPublishedArtifactDependencies` adds a
real production compile and is the release-compatibility gate once matching
Contracts 1.0 artifacts exist. Until then it fails honestly even though the
older pinned coordinates resolve.

The extracted source archive runs the resolution-isolation proof in this mode
without reaching any sibling checkout. Its current receipt marks focused tests
`NOT_EXECUTED` and published compatibility `NOT_VERIFIED`; it is configuration
and packaging evidence, not a substitute for the compile gate.

## Coordination gates

```bash
./gradlew test
./gradlew integrationTest consumerTest scenarioTest
./gradlew releaseCheck
./gradlew stageRelease -PblueDependencyMode=published-artifact
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
./gradlew verifyPublishedDependencyIsolation dependencyPreflight \
  -PblueDependencyMode=published-artifact
```

That command fails closed unless every pinned prerequisite resolves externally.
Before release, also run `verifyPublishedArtifactDependencies`; it compiles the
current source against that isolated graph and fails on stale published APIs.

## Historical performance evidence

`../blue-basic` is deliberately outside the library's correctness and release
gate. It retains historical step timings, percentile campaigns and comparative
metrics so performance investigations remain reproducible without coupling the
published library to a sibling checkout. Run it only when collecting or
comparing performance evidence:

```bash
./gradlew publishToMavenLocal
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
./gradlew dependencies --write-locks
```

Review the entire lock diff. Never hand-wave an unexpected transitive version.
Refresh the Language and BEX source locks only for an intentional coordinated
workspace snapshot. Both locks bind a base commit plus the framed fingerprint
of tracked and untracked production changes; a dirty workspace is valid only
when its fingerprint matches exactly.
