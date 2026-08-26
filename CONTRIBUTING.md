# Contributing

Blue Coordination deliberately has one compact architecture. Changes should
strengthen that architecture instead of introducing another engine, planner,
fragmentation layer, scheduler or cache hierarchy.

## Development baseline

- Java 17 or newer; production bytecode is compiled with `--release 17`.
- Use the checked-in Gradle wrapper.
- Keep all dependency versions exact and commit lock-file changes.
- Resolve Language, Repository, and BEX from Maven Central; do not add sibling
  composites, Maven Local, or file-repository fallbacks.
- Structure every `@Test` with one meaningful lowercase `// given`, `// when`,
  `// then` sequence.

Run the focused gate while developing:

```bash
./gradlew dependencyPreflight
./gradlew releaseCheck
```

The gate runs unit, integration, built-JAR consumer and end-to-end scenario
tests. It must remain independent of `../blue-basic`; that sibling exists only
for historical timing and percentile comparisons and is not a release input.

Before opening a pull request, follow
[build and test](docs/development/build-and-test.md), update relevant docs and
the changelog, and run `git diff --check`.

## Design rules

- Keep `blue.coordination.sdk` immutable and application-focused.
- Keep `blue.coordination.api` as the explicit low-level host and compatibility
  boundary; never present its plain `inMemory()` profile as the SDK default.
- Never expose `blue.coordination.internal` in a normal application signature.
- Append never chooses recipients or invokes PROCESS; the environment owns
  canonical entry and closure selection.
- Preserve the copy-on-write atomic boundary for one connected affected
  Contracts closure. Do not add a whole-engine rollback snapshot or collapse
  disconnected closure outcomes.
- Keep managed occurrence lineage, active/inactive topology generations, and
  historical progress as distinct exact evidence.
- Unsupported semantics fail with a `CoordinationException` and stable error
  code at the low-level boundary or a documented SDK validation exception or
  `Diagnostic` at the facade boundary; never silently approximate them.
- Every semantic guarantee or fixed regression needs an executable test.
- Performance work must report frozen semantic and Coordination host time
  separately.

Commit messages should use Conventional Commits. Breaking changes must be
called out explicitly.
