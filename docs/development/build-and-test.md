# Build and test

## Requirements

Use the checked-in Gradle wrapper and JDK 17 or newer. Production classes are
compiled with `--release 17`; CI executes the complete suite on Java 17 and
Java 21.

## Dependency graph

`blueDependencyMode=published-artifact` is the rc.5 build and release default.
It resolves exact Maven Central artifacts and rejects sibling composites,
Maven Local, flat/unverified repositories, and mutable checkout substitution.

| Modules | Rc.5 release lane |
| --- | --- |
| `blue.language:*` | Maven Central `3.1.0-rc.23` |
| `blue.bex:blue-bex-core`, `blue-bex-contracts` | Maven Central `1.1.0-rc.4` |
| `blue.repo:blue-repo-java` | Maven Central `3.0.0-rc.21` |

Repository rc.21 advertises `blue-language-java:3.1.0-rc.20`. Coordination
excludes that stale transitive edge, directly owns Language rc.23, records the
same exclusion in its published POM, and locks the exact graph in
`gradle/published-artifact.lockfile`.

The `immutable-staged-contracts` and `immutable-development-contracts` lanes
remain available only for non-published upstream handoffs. They are not
release evidence once rc.23 is published.

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

For rc.5, verify fresh remote availability and the
conflict-free Maven Central graph with:

```bash
./gradlew --no-daemon dependencyPreflight --refresh-dependencies
./gradlew --no-daemon verifyPublishedDependencyIsolation
```

For a non-published development handoff, use the manifest-pinned command above;
do not reuse a warmed dependency cache as substitute evidence.

## Verification

Rc.5 uses the Maven-Central-only release gate:

```bash
./gradlew --no-daemon --no-build-cache clean releaseCheck \
  -PtestJavaVersion=17
```

Repeat the command with `-PtestJavaVersion=21`. Passing these gates is required
evidence, but does not itself publish rc.5 or make it production-ready.
The suites remain separate because each protects a different boundary:

| Task | Boundary | Execution policy |
| --- | --- | --- |
| `test` | SDK, compiler, immutable values, and compact internals | `check` and `releaseCheck` |
| `integrationTest` | In-memory engine behavior, retries, topology, and atomicity | `check` and `releaseCheck` |
| `consumerTest` | Compilation and execution against the built production JAR | `check` and `releaseCheck` |
| `scenarioTest` | Slow complete-lifecycle, convergence, and scale scenarios | `releaseCheck` |

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

`verifyRcReadiness` is the rc.5 release-readiness gate:

```bash
./gradlew --no-daemon --no-build-cache verifyRcReadiness \
  -PtestJavaVersion=17
```

The task includes `releaseCheck` and `dependencyPreflight`, validates the rc.5
release authority and explicit non-claims, then records the
fresh artifact hashes in
`build/reports/release/3.0.0-rc.5-readiness.json`. It validates version
`3.0.0-rc.5`, the published-artifact lane, and the focused rc.5 capability
inventory in addition to the complete current suite.

The source distribution and checksum can be built independently with:

```bash
./gradlew coordinationSourceArchive coordinationSourceArchiveChecksum
./gradlew verifyExtractedSourceArchive
```

The extracted archive resolves the same Maven Central graph and never reaches
an adjacent checkout or Maven Local.

For a downstream development handoff after the rc.5 gates pass on a clean
committed source tree, export a separate
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
[3.0.0-rc.5 decision](../releases/3.0.0-rc.5.md).
