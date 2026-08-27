# Build and test

## Requirements

Use the checked-in Gradle wrapper and JDK 17 or newer. Production classes are
compiled with `--release 17`; CI executes the complete suite on Java 17 and
Java 21.

## Dependency graph

The published `3.0.0-rc.4` artifact and the staged `3.0.0-rc.6` source use
different, explicit lanes. `blueDependencyMode=published-artifact` remains the
build default so the rc.4 Maven Central graph cannot be silently replaced.
The current retained-epoch source must use
`blueDependencyMode=immutable-staged-contracts` on every invocation. Sibling
composite builds, Maven Local, flat/unverified repositories, and mutable
checkout substitution are rejected.

| Modules | Published rc.4 | Staged rc.6 source |
| --- | --- | --- |
| `blue.language:*` | Maven Central `3.1.0-rc.22` | immutable stage `3.1.0-rc.23` |
| `blue.bex:blue-bex-core`, `blue-bex-contracts` | `1.1.0-rc.4` | `1.1.0-rc.4` |
| `blue.repo:blue-repo-java` | `3.0.0-rc.21` | `3.0.0-rc.21` |

Repository rc.21 advertises `blue-language-java:3.1.0-rc.20`. Both lanes
exclude that stale transitive edge and directly own their active Language
version. The published Coordination POM records the rc.22 exclusion and the
exact published graph is locked in `gradle/published-artifact.lockfile`.

The staged lane uses `gradle/immutable-staged-contracts.lockfile` and exact
Blue Language/Contracts `3.1.0-rc.23` candidate artifacts. That candidate is
the exact published `3.1.0-rc.22` source baseline plus the additive
`blue-contracts-core` managed-transition receipt surface; it is not a claim
that `3.1.0-rc.23` has been published.

The staged lane accepts only the non-overwriting repository exported by the
Contracts release gate. Its absolute path and exact manifest identity are both
required:

```bash
./gradlew --no-daemon verifyActiveDependencyLane dependencyPreflight \
  -PblueDependencyMode=immutable-staged-contracts \
  -PblueContractsVersion=3.1.0-rc.23 \
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

On the published rc.4 tag, verify fresh remote availability and the
conflict-free Maven Central graph with:

```bash
./gradlew --no-daemon dependencyPreflight --refresh-dependencies
./gradlew --no-daemon verifyPublishedDependencyIsolation
```

For the staged rc.6 source, use the manifest-pinned command above instead; do
not run it first against Maven Central and then reuse a warmed dependency
cache as substitute evidence.

## Verification

The published rc.4 tag used the Maven-Central-only release gate:

```bash
./gradlew --no-daemon --no-build-cache clean releaseCheck \
  -PtestJavaVersion=17
```

For the staged rc.6 retained-epoch source, the complete local verification
shape carries the immutable Contracts stage on the same invocation:

```bash
./gradlew --no-daemon --no-build-cache clean releaseCheck \
  -PtestJavaVersion=17 \
  -PblueDependencyMode=immutable-staged-contracts \
  -PblueContractsVersion=3.1.0-rc.23 \
  -PblueContractsRepository=/absolute/path/to/invocation-owned/contracts-repository \
  -PblueContractsManifestSha256=sha256:<64-lowercase-hex>
```

Repeat the applicable command with `-PtestJavaVersion=21`. Passing these gates
is required evidence, but does not publish rc.6 or make it production-ready.
The suites remain separate because each protects a different boundary:

| Task | Boundary | Execution policy |
| --- | --- | --- |
| `test` | SDK, compiler, immutable values, and compact internals | `check` and `releaseCheck` |
| `integrationTest` | In-memory engine behavior, retries, topology, and atomicity | `check` and `releaseCheck` |
| `consumerTest` | Compilation and execution against the built production JAR | `check` and `releaseCheck` |
| `scenarioTest` | Slow complete-lifecycle, convergence, and scale scenarios | `releaseCheck` |

On the published rc.4 tag, the standard development gate is:

```bash
./gradlew --no-daemon check
```

On the rc.6 source, add the same four immutable-stage properties shown above to
`check` or to any focused task. The development gate deliberately omits the
slow scenario lane. `releaseCheck` always adds that lane, so release evidence
never relies only on the shorter gate.

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

`verifyRcReadiness` is retained release tooling for the published rc.4 tag:

```bash
./gradlew --no-daemon --no-build-cache verifyRcReadiness \
  -PtestJavaVersion=17
```

On that tag, the task includes `releaseCheck` and `dependencyPreflight`,
validates the rc.4 release authority and explicit non-claims, then records the
fresh artifact hashes in
`build/reports/release/3.0.0-rc.4-readiness.json`. It intentionally validates
version `3.0.0-rc.4` and the published-artifact lane, so it is not an rc.6 gate
and must not be cited as rc.6 evidence.

The source distribution and checksum can be built independently with:

```bash
./gradlew coordinationSourceArchive coordinationSourceArchiveChecksum
./gradlew verifyExtractedSourceArchive
```

For rc.4 the extracted archive resolves the same Maven Central graph. For rc.6,
pass the immutable-stage properties to archive verification; the extracted
build validates that same absolute manifest-pinned repository. Neither lane
reaches an adjacent checkout or Maven Local.

After rc.6 gates pass on a clean committed source tree, export a separate
invocation-owned immutable Coordination repository with:

```bash
./gradlew --no-daemon dynamicEvolutionCoordinationHandoff \
  -PblueDependencyMode=immutable-staged-contracts \
  -PblueContractsVersion=3.1.0-rc.23 \
  -PblueContractsRepository=/absolute/path/to/invocation-owned/contracts-repository \
  -PblueContractsManifestSha256=sha256:<64-lowercase-hex> \
  -PcoordinationSourceCommit=<exact-clean-coordination-head> \
  -PcoordinationStagedRepository=/absolute/path/to/invocation-owned/coordination-repository
```

The target is non-overwriting and outside the source tree. The handoff binds
the clean source commit, resolved dependency identities, artifact bytes, and
the upstream Contracts manifest, then runs an isolated staged consumer. See
[Immutable Coordination handoff](immutable-staged-coordination.md). It is a
downstream integration stage, not a Maven Central publication.

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
[3.0.0-rc.4 decision](../releases/3.0.0-rc.4.md).
