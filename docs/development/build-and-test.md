# Build and test

## Requirements

Use the checked-in Gradle wrapper and JDK 17 or newer. Production classes are
compiled with `--release 17`; CI executes the complete suite on Java 17 and
Java 21.

## Published dependency graph

Maven Central is the only live dependency source. The default and only accepted
`blueDependencyMode` is `published-artifact`; the property may be omitted.
Sibling composite builds, Maven Local, and file-based staging repositories are
rejected.

| Modules | Version |
| --- | --- |
| `blue.language:*` | `3.1.0-rc.21` |
| `blue.bex:blue-bex-core`, `blue-bex-contracts` | `1.1.0-rc.4` |
| `blue.repo:blue-repo-java` | `3.0.0-rc.21` |

Repository rc.21 advertises `blue-language-java:3.1.0-rc.20`. The project
excludes that one stale transitive edge and directly owns Language rc.21. The
same exclusion is published in the Coordination POM. The exact graph is locked
in `gradle/published-artifact.lockfile`.

Verify fresh remote availability and the conflict-free graph with:

```bash
./gradlew --no-daemon dependencyPreflight --refresh-dependencies
./gradlew --no-daemon verifyPublishedDependencyIsolation
```

## Verification

The complete local release gate is:

```bash
./gradlew --no-daemon --no-build-cache clean releaseCheck \
  -PtestJavaVersion=17
```

Repeat with `-PtestJavaVersion=21` before release. The suites remain separate
because each protects a different boundary:

| Task | Boundary | Execution policy |
| --- | --- | --- |
| `test` | SDK, compiler, immutable values, and compact internals | `check` and `releaseCheck` |
| `integrationTest` | In-memory engine behavior, retries, topology, and atomicity | `check` and `releaseCheck` |
| `consumerTest` | Compilation and execution against the built production JAR | `check` and `releaseCheck` |
| `scenarioTest` | Slow complete-lifecycle, convergence, and scale scenarios | `releaseCheck` |

Run the standard development gate with:

```bash
./gradlew --no-daemon check
```

It deliberately omits the slow scenario lane. `releaseCheck` always adds that
lane, so a release never relies only on the shorter development gate.

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

For the current bounded external-pilot candidate, run:

```bash
./gradlew --no-daemon --no-build-cache verifyRcReadiness \
  -PtestJavaVersion=17
```

This task includes `releaseCheck` and `dependencyPreflight`, validates the
rc.3 release authority and explicit non-claims, then records the freshly built
artifact hashes in
`build/reports/release/3.0.0-rc.3-readiness.json`.

The source distribution and checksum can be built independently with:

```bash
./gradlew coordinationSourceArchive coordinationSourceArchiveChecksum
./gradlew verifyExtractedSourceArchive
```

The extracted archive resolves the same Maven Central graph and never reaches
an adjacent checkout.

## Focused development

Use focused Gradle test filters while iterating, but finish with
`releaseCheck`. Tests compiled against the built JAR must not import
`blue.coordination.internal`, processor implementations, integration
fixtures, Language, or BEX types.

The optional `../blue-basic` checkout is historical performance tooling. It
is not read by the build and is not release evidence.

See [Test strategy](test-strategy.md),
[Releasing](releasing.md), and the
[3.0.0-rc.3 decision](../releases/3.0.0-rc.3.md).
