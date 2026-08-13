# Releasing

## Candidate prerequisites

An RC is releasable only when all exact coordinates in `build.gradle` resolve
from Maven Central. In particular, 3.0.0-rc.1 requires Repository rc.21 and BEX
rc.3. Local composite success is semantic evidence, but it is not proof that an
external consumer can resolve the release.

Repository rc.21 is the first pinned release containing the Repository surface
required by this Coordination candidate.

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

Release artifacts have one canonical producer: GitHub's Ubuntu 24.04 `x64`
runner with Eclipse Temurin 17.0.19+10. The workflow disables Gradle toolchain
auto-discovery/download and the build cache while generating and checking the
published bytes. The pull-request Java 17 lane uses that same producer and runs
`verifyRound13Readiness`, so toolchain or artifact-hash drift is rejected before
merge instead of first appearing in the post-merge release job. The Java 21
lane uses Eclipse Temurin 21.0.11+10 for test execution while production
artifacts continue to be compiled by the canonical Java 17 toolchain.

## Stable workflow

Stable release is manual, restricted to `main`, and requires an exact
`MAJOR.MINOR.PATCH` version in `.cz.toml`. It follows the same dependency
preflight, staging, signing and publication path as an RC.

## Verification checklist

- `releaseCheck` passes all library-owned unit, integration, built-JAR consumer
  and end-to-end scenario suites without `../blue-basic`.
- The one canonical Round 13 report and JSON evidence use the Round 13
  Playground schema. For 3.0.0-rc.1 only, `verifyRound13Readiness` accepts
  `FINAL` evidence with policy mode
  `RC_WITH_KNOWN_PERFORMANCE_LIMITATION` when the release workflow explicitly
  opts in. The verdict, public-RC status, and latency status must be
  `PASS_WITH_KNOWN_PERFORMANCE_LIMITATION`; the current campaign and performance
  proof remain `PENDING_VERIFICATION`. This exception never waives the clean-
  commit binding, Java 17/21 lanes, six non-performance proof rows, eight
  measured zero counters, final artifact hashes, detached source-archive
  verification, published-mode evidence, POM metadata, checksums, or signatures.
  The tested implementation commit may precede the clean evidence commit, but
  it must be an ancestor and the current main-source manifest must still match
  exactly.
- Java 17 and Java 21 CI jobs pass, including the Java 17 pre-merge staging-
  readiness check.
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

## 3.0.0-rc.1 known-performance-limitation policy

The 3.0.0-rc.1 workflow has one narrow exception so the release candidate can
be published for external evaluation:

- `mode`: `RC_WITH_KNOWN_PERFORMANCE_LIMITATION`
- `exactRelease`: `3.0.0-rc.1`
- `decision`: `PASS_WITH_KNOWN_PERFORMANCE_LIMITATION`
- `performanceReleaseBlocking`: `false`
- `stableReleaseEligible`: `false`
- `nonPerformanceGatesRequired`: `true`
- `explicitWorkflowOptInRequired`: `true`

The workflow must opt in explicitly; a normal local staging call, another RC,
or a stable release cannot inherit the exception. Every non-performance gate
listed above remains fail-closed.

The retained historical campaign remains `FAIL`: append p95 was 18.680667 ms
against a 1.000000 ms hard limit, and Coordination-host p95 was 872.356126 ms
against 250.000000 ms. Route and total passed their hard limits, but all four
preferred targets were missed. The old Markdown, JSON, and provenance receipts
remain unchanged as audit evidence. Their temporary `Archive.zip` input is not
a release artifact, is not needed to build or publish 3.0.0-rc.1, and must not be
reintroduced as a staging prerequisite. The current published-artifact campaign
and performance proof remain `PENDING_VERIFICATION`; no latency pass is claimed.

Performance remediation and a passing campaign are required before any stable
release. The tracked source-archive evidence intentionally leaves its digest
`null`; the generated detached `.sha256` sidecar remains the checksum authority.
