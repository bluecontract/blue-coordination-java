# Releasing

## Candidate prerequisites

An RC is releasable only when all exact coordinates in `build.gradle` resolve
from Maven Central. In particular, 3.0.0-rc.1 requires Repository rc.19 and BEX
rc.3. Local composite success is semantic evidence, but it is not proof that an
external consumer can resolve the release.

Repository rc.18 is not suitable: it is already published against the legacy
Language 3.0.0 API. The modular Repository must use rc.19 or newer.

## RC workflow

1. Merge the candidate to `next`.
2. The RC workflow derives the next version, updates `.cz.toml`, creates a
   release commit and annotated tag locally.
3. `dependencyPreflight` resolves all prerequisites in published-artifact mode.
4. `clean stageRelease` reruns the full release gate and builds the staging
   repository.
5. JReleaser's deploy task verifies, signs, checksums and uploads the staged
   artifacts to Maven Central.
6. Only after successful publication does the workflow push the release commit
   and tag.

The workflow uses GitHub Actions concurrency to serialize releases. Required
secrets are `WORKFLOW_PAT`, Maven Central username/password and the JReleaser GPG
public key, secret key and passphrase.

## Stable workflow

Stable release is manual, restricted to `main`, and requires an exact
`MAJOR.MINOR.PATCH` version in `.cz.toml`. It follows the same dependency
preflight, staging, signing and publication path as an RC.

## Verification checklist

- `releaseCheck` passes all library-owned unit, integration, built-JAR consumer
  and end-to-end scenario suites without `../blue-basic`.
- The one canonical Round 11 report and JSON evidence contain final results;
  `verifyRound11Readiness` permits staging and no duplicate evidence sidecar
  remains.
- Java 17 and Java 21 CI jobs pass.
- POM dependencies and scopes match `docs/reference/public-api.md`.
- Main, sources and Javadoc JAR hashes reproduce across two clean builds.
- Staged POM, checksum and signature inventory is complete.
- Changelog, migration notes, limitations and RC notes are current.
- The external Maven consumer resolves without adjacent sibling repositories.
- The exported source archive contains the authoritative `.cz.toml`, configures
  from its own contents, and excludes nested ZIPs, build output, macOS metadata,
  profiler recordings, and heap dumps.

Historical `blue-basic` metrics may be captured for performance comparison,
but they are not an RC correctness prerequisite and are never substituted for
the library-owned suites.

Never bypass dependency preflight or publish from local composite resolution.
