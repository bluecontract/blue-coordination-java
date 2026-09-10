# Build and test

## Requirements

Use the checked-in Gradle wrapper and JDK 17 or newer. Production classes are
compiled with `--release 17`; CI executes the complete suite on Java 17 and
Java 21.

## Dependency graph

`blueDependencyMode=published-artifact` is the rc.7 build and release default.
It resolves exact Maven Central artifacts and rejects sibling composites,
Maven Local, flat/unverified repositories, and mutable checkout substitution.

| Modules | RC7 release lane |
| --- | --- |
| `blue.language:*` | Maven Central `3.1.0-rc.25` |
| `blue.bex:blue-bex-core`, `blue-bex-contracts` | Maven Central `1.1.0-rc.7` |
| `blue.repo:blue-repo-java` | Maven Central `3.0.0-rc.22` |

Coordination directly owns the complete Language rc.25 graph, retains the
Repository and BEX transitive exclusions in its published POM, and locks the
exact graph in `gradle/published-artifact.lockfile`.

The `immutable-staged-contracts` and `immutable-development-contracts` lanes
remain available only for non-published upstream handoffs. They are not public
release authority for rc.7. The development lane is valid
candidate-verification evidence when every input and the Coordination version
are commit-bound. It can bind separate, immutable Language/Contracts and BEX
repositories; it never obtains either dependency from a sibling checkout or
Maven Local.

The staged lane accepts only the non-overwriting repository exported by the
Contracts release gate. Its absolute path and exact manifest identity are both
required:

```bash
./gradlew --no-daemon verifyActiveDependencyLane dependencyPreflight \
  -PblueDependencyMode=immutable-staged-contracts \
  -PblueContractsVersion=3.1.0-rc.25 \
  -PblueContractsRepository=/absolute/path/to/contracts-maven-repository \
  -PblueContractsManifestSha256=sha256:<64-lowercase-hex>
```

`artifact-manifest.json` must use
`blue-staged-dependency-repository/1.0` and bind the exact source commit,
Contracts specification, fixture package, release identity, seven
`blue.language` modules, all artifact bytes, and every checksum companion. The
manifest SHA-256 property is the invocation pin. The non-overwriting repository
must be created for this invocation, outside this source tree, and treated as
read-only input. This build never invokes the Contracts build, reads a live
Contracts checkout, or copies its source.

In staged mode, Gradle uses repository-exclusive content routing for the
`blue.language` group. Maven Central remains available only for BEX, Repository,
Gradle plugins, and third-party dependencies. There is no fallback if a staged
Language artifact is absent or different.

For rc.7, verify fresh remote availability and the
conflict-free Maven Central graph with:

```bash
./gradlew --no-daemon dependencyPreflight --refresh-dependencies
./gradlew --no-daemon verifyPublishedDependencyIsolation
```

For a non-published development handoff, use the manifest-pinned command above;
do not reuse a warmed dependency cache as substitute evidence.

For a coordinated Language/Contracts and BEX development handoff, bind both
commit-addressed repositories explicitly:

```bash
./gradlew --no-daemon verifyActiveDependencyLane \
  dependencyPreflight writeDevelopmentResolvedDependencies \
  -PblueDependencyMode=immutable-development-contracts \
  -PblueContractsVersion=3.1.0-dev.<40-lowercase-commit> \
  -PblueContractsRepository=/absolute/path/to/contracts-development-repository \
  -PblueContractsManifestSha256=sha256:<64-lowercase-hex> \
  -PblueContractsSourceCommit=<40-lowercase-commit> \
  -PblueBexVersion=1.1.0-dev.<40-lowercase-commit> \
  -PblueBexRepository=/absolute/path/to/bex-development-repository \
  -PblueBexManifestSha256=sha256:<64-lowercase-hex> \
  -PblueBexSourceCommit=<40-lowercase-commit> \
  -PblueDevelopmentVersion=3.0.0-dev.<coordination-commit>
```

The BEX repository manifest must use
`blue-bex-development-repository/1.0`, bind its version to its source commit,
and bind the exact selected Language version, source commit, and repository
manifest identity. Both development manifests record `DEVELOPMENT`,
`releaseReadinessClaimed=false`, JDK 17, a 40-hex commit and tree, and a clean
source state; dirty/tree-version aliases are not accepted by the handoff. BEX
contains runtime, POM, sources, and Javadoc artifacts
for `blue-bex-core`, `blue-bex-contracts`, and `blue-bex-java`, with a checksum
companion for every payload and no unlisted files or symbolic links.
Coordination resolves only `blue-bex-core` and `blue-bex-contracts`, then
compares the resolved JAR bytes with the BEX manifest. The canonical resolved
dependency report is written to
`build/reports/development-stage/resolved-dependencies.json`.

## Verification

RC7 uses the Maven-Central-only release gate:

```bash
./gradlew --no-daemon --no-build-cache clean releaseCheck \
  -PtestJavaVersion=17
```

Repeat the command with `-PtestJavaVersion=21`. Passing these gates is required
evidence, but does not itself publish rc.7 or make it production-ready.

### Parallel test execution

Use independent test JVMs to reduce elapsed time:

```bash
./gradlew --no-daemon --no-build-cache --max-workers=4 clean releaseCheck \
  -PtestJavaVersion=17 -PtestMaxParallelForks=4
```

`testMaxParallelForks` defaults to `1`, must be a positive integer, and is
capped by Gradle's worker limit. CI selects `4` for Build, RC release, and
stable release. Each test JVM retains the existing 2 GiB heap limit and
executes methods serially. The four PayNote acceptance cases have separate
classes and create independent fixtures. No fixture preparation, gas budget,
assertion, or repeatability scenario is removed by this scheduling change.

The extracted-source smoke receives the same effective fork limit and still
executes its complete existing focused inventory. Keep the same fork setting
for subsequent readiness, staging, and publishing commands so Gradle can
reuse valid results for the same inputs.

`verifyCheckTestExecutionScope` and `verifyReleaseTestExecutionScope` compare
JUnit-discovered compiled test classes with the JUnit XML results. They reject
missing or unexpected classes, empty execution, failures, skipped tests, and
filtered or excluded suite tasks. Parameterized methods participate in class
discovery, and every executed invocation is recorded in the receipt. Filtered
`test --tests ...` runs remain available for development; they cannot satisfy
the complete `check` or `releaseCheck` gate.

Receipts and discovered inventories are written under
`build/reports/test-execution-scope/`. CI also archives `build/rooted-evidence/`
alongside the existing reports and test results. Consumer test compilation and
execution retain their JAR-only boundary; discovery runs in a separate JVM.

### Topology identity evidence

A complete `test` run captures topology identities during the 20 original JUnit
contributor cases. Each invocation uses its own recorder and writes a distinct
fragment under `build/rooted-evidence/topology-fragments/`. After the workers
finish, `verifyCyclicTopologyIdentityEvidence` combines those fragments in the
original scenario order and compares the generated JSON and Markdown exactly
with the committed artifacts. The finalizer runs for `test`, `check`, and
`releaseCheck`; missing contributors, changed identities, and incorrect repeat
counts fail verification.

This removes the exporter's second execution of the same 20 cases. All 32
scenarios, six additional finite-ring repetitions, and the additional rollback
repetition remain required. The successful comparison and generated artifacts
are saved under `build/reports/topology-identity-evidence/`. Current-input Gradle
reuse retains the fragments and repeats the inexpensive comparison.

Filtered Gradle runs and IDE execution retain the standalone exporter. For
example, `test --tests '*CyclicTopologyIdentityEvidenceTest'` executes its full
original contributor campaign directly. Explicit artifact regeneration still
uses `BLUE_CYCLIC_TOPOLOGY_IDENTITY_ARTIFACT_MODE=WRITE`.

### Suite boundaries

The suites remain separate because each protects a different boundary:

| Task | Boundary | Execution policy |
| --- | --- | --- |
| `test` | SDK, compiler, immutable values, and compact internals | `check` and `releaseCheck` |
| `integrationTest` | In-memory engine behavior, retries, topology, and atomicity | `check` and `releaseCheck` |
| `consumerTest` | Compilation and execution against the built production JAR | `check` and `releaseCheck` |
| `scenarioTest` | Slow complete-lifecycle, convergence, and scale scenarios | `releaseCheck` |

For a clean commit-bound Coordination candidate against commit-bound Language
and BEX artifacts, run the same complete gate with the exact development lane:

```bash
./gradlew --no-daemon --no-build-cache clean releaseCheck \
  dependencyPreflight \
  -PtestJavaVersion=17 \
  -PblueDependencyMode=immutable-development-contracts \
  -PblueContractsVersion=3.1.0-dev.<language-commit> \
  -PblueContractsRepository=/absolute/path/to/contracts-development-repository \
  -PblueContractsManifestSha256=sha256:<64-lowercase-hex> \
  -PblueContractsSourceCommit=<language-commit> \
  -PblueBexVersion=1.1.0-dev.<bex-commit> \
  -PblueBexRepository=/absolute/path/to/bex-development-repository \
  -PblueBexManifestSha256=sha256:<64-lowercase-hex> \
  -PblueBexSourceCommit=<bex-commit> \
  -PblueDevelopmentVersion=3.0.0-dev.<coordination-commit>
```

Repeat with `-PtestJavaVersion=21`. Generate the canonical resolved-dependency
report once under JDK 17 with `writeDevelopmentResolvedDependencies`. The
`blueDevelopmentVersion=3.0.0-dev.<coordination-commit>` property is mandatory
and must identify the clean Coordination `HEAD`; it has no default. The
extracted-source smoke receives the same version, repositories, manifest
identities, and source commits and therefore cannot fall back to the published
Language or BEX artifacts. This validates a development candidate; it does not
change either upstream manifest's `releaseReadinessClaimed: false`, authorize
`stageRelease`, or create public-release evidence.

The standard development gate is:

```bash
./gradlew --no-daemon check
```

The development gate deliberately omits the slow scenario lane.
`releaseCheck` always adds that lane, so release evidence never relies only on
the shorter gate.

`consumerTest` is intentionally small and must remain separate: its compile
classpath contains the built JAR and excludes compiled main-source output and
test helpers. That detects missing classes, invalid public signatures, and
incomplete runtime dependencies that source-based tests cannot detect.

Reusable engine helpers live in private `src/testSupport`. It contains no
executable tests, is consumed independently by `integrationTest` and
`scenarioTest`, and is not published. This prevents the scenario suite from
depending on compiled integration-test classes while keeping fixtures shared.

Every `@Test` must contain exactly one ordered, meaningful lowercase
`// given`, `// when`, `// then` sequence.
`verifyTestArchitecture` enforces that source shape together with suite depth
and built-JAR consumer isolation.

`releaseCheck` also verifies public API boundaries, artifact contents,
publication POM metadata and exclusions, documentation links, dependency
isolation, source-archive hygiene, and an extracted source-archive build.

## RC readiness

`verifyRcReadiness` is the rc.7 release-readiness gate:

```bash
./gradlew --no-daemon --no-build-cache verifyRcReadiness \
  -PtestJavaVersion=17
```

The task includes `releaseCheck` and `dependencyPreflight`, validates the rc.7
release authority and explicit non-claims, then records the
fresh artifact hashes in
`build/reports/release/3.0.0-rc.7-readiness.json`. It validates version
`3.0.0-rc.7`, the published-artifact lane, and the focused rc.7 capability
inventory in addition to the complete current suite.

The Build workflow prepares and seals the exact rc.7 version in its isolated
checkout before this gate. Keep the preceding released version in the feature
branch's `.cz.toml`; the release workflow owns the final version commit. A
local readiness run requires the same preparation in a validation checkout.

The source distribution and checksum can be built independently with:

```bash
./gradlew coordinationSourceArchive coordinationSourceArchiveChecksum
./gradlew verifyExtractedSourceArchive
```

The extracted archive resolves the same selected isolated graph. In the
published lane that is Maven Central; in staged or development mode it is the
same exact manifest-pinned external repository set supplied to the parent
build. It never reaches an adjacent checkout or Maven Local. The distribution
also contains the canonical Coordination specification candidate under
`specifications/`; extracted verification requires that copy to be a regular
file and byte-identical to the canonical source file before the isolated build
starts.

For a downstream development handoff after the rc.7 gates pass on a clean
committed source tree, export a separate
invocation-owned immutable Coordination repository with:

```bash
./gradlew --no-daemon dynamicEvolutionCoordinationHandoff \
  -PblueDependencyMode=immutable-development-contracts \
  -PblueContractsVersion=3.1.0-dev.<language-commit> \
  -PblueContractsRepository=/absolute/path/to/language-development-repository \
  -PblueContractsManifestSha256=sha256:<language-manifest> \
  -PblueContractsSourceCommit=<language-commit> \
  -PblueBexVersion=1.1.0-dev.<bex-commit> \
  -PblueBexRepository=/absolute/path/to/bex-development-repository \
  -PblueBexManifestSha256=sha256:<bex-manifest> \
  -PblueBexSourceCommit=<bex-commit> \
  -PblueDevelopmentVersion=3.0.0-dev.<exact-clean-coordination-head> \
  -PcoordinationSourceCommit=<exact-clean-coordination-head> \
  -PcoordinationStagedRepository=/absolute/path/to/invocation-owned/coordination-repository
```

The target is non-overwriting and outside the source tree. The handoff binds
the clean source commit, resolved dependency identities, artifact bytes, and
both exact upstream manifests, then runs an isolated staged consumer. The
Language and BEX manifest identities are read from the invocation-owned
repositories rather than frozen in this source tree. See [Immutable
Coordination handoff](immutable-staged-coordination.md). It is a downstream
integration stage, not a Maven Central publication.

The staged consumer is a mandatory two-runtime gate. The aggregate
`stagedCoordinationConsumer` task runs
`stagedCoordinationConsumerJava17` and
`stagedCoordinationConsumerJava21`; both compile consumer sources with
`--release 17`, launch tests on the requested Java runtime, and assert the
actual runtime feature version. Before either lane runs, stale lane and
aggregate receipts are deleted. Successful lanes write
`build/reports/dynamic-evolution/staged-consumer-java17.json` and
`staged-consumer-java21.json`; the aggregate writes
`build/reports/dynamic-evolution/staged-consumers.json`. A handoff is not
accepted if either runtime lane or its fresh receipt is missing.

## Focused development

Use focused Gradle test filters while iterating, always with the active lane's
required properties, but finish with `releaseCheck`. Tests compiled against the
built JAR must not import
`blue.coordination.internal`, processor implementations, integration
fixtures, Language, or BEX types.

The optional `../blue-basic` checkout is historical performance tooling. It
is not read by the build and is not release evidence.

See [Test strategy](test-strategy.md),
[Releasing](releasing.md), and the
[3.0.0-rc.7 decision](../releases/3.0.0-rc.7.md).
