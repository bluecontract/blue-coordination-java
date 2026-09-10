# Releasing

The dynamic-evolution downstream handoff is documented in
[`immutable-staged-coordination.md`](immutable-staged-coordination.md). It is
separate from Maven Central staging and is intentionally non-overwriting.

## Current decision

`3.0.0-rc.7` is authorized as a bounded external-pilot release candidate
only after all required gates pass on the exact published dependency tuple.
It is not stable or production-ready. The exact scope and non-claims are in the
[rc.7 release decision](../releases/3.0.0-rc.7.md).

The release consumes only Maven Central artifacts:

| Component | Version |
| --- | --- |
| Language | `3.1.0-rc.25` |
| BEX core/contracts | `1.1.0-rc.7` |
| Repository | `3.0.0-rc.22` |
| Coordination | `3.0.0-rc.7` |

Coordination directly owns the complete Language rc.25 graph. BEX and
Repository Language transitive edges remain excluded, and the generated POM
publishes the same exclusions.

The complete current release gate and focused rc.7 capability inventory are
the required semantic evidence for this release. Their documentation does not
claim that they have passed. Historical rc.4, rc.5, and rc.6 receipts remain evidence
for their original releases only.

## Before merging to `next`

From a clean feature branch:

Keep `.cz.toml` at the preceding released version in the feature PR. The Build
workflow prepares rc.7 and seals a local verification commit before running
these Gradle gates. For local `verifyRcReadiness`, reproduce that preparation
in an isolated validation checkout; do not commit the prepared version to the
feature branch. The release workflow owns the final version commit and tag.

```bash
node --test .github/scripts/prepare-rc-release.test.js
./gradlew --no-daemon dependencyPreflight --refresh-dependencies
./gradlew --no-daemon --no-build-cache clean releaseCheck \
  -PtestJavaVersion=17
./gradlew --no-daemon --no-build-cache clean releaseCheck \
  -PtestJavaVersion=21
./gradlew --no-daemon --no-build-cache verifyRcReadiness \
  -PtestJavaVersion=17
git diff --check
```

Confirm that the branch contains the current `next` tip, has no unresolved
merge entries, and has no uncommitted changes. Do not create the release tag
manually.

## Automated RC workflow

A push to `next` starts `.github/workflows/release-rc.yml`. It:

1. checks out the complete history and tags;
2. pins Temurin 17.0.19+10 for the canonical build and Temurin
   21.0.11+10.0.LTS for compatibility verification;
3. validates release credentials and the wrapper;
4. prepares the version authorized by `docs/releases/3.0.0-rc.7.md`;
5. creates the annotated tag locally and verifies push permissions;
6. resolves the exact published dependency graph;
7. runs the complete Java 21 release gate before any staging;
8. runs `stageRelease` on Java 17, including the complete release and rc.7
   gates;
9. deploys the signed bundle to Maven Central;
10. pushes the release commit, if any, and tag only after deployment succeeds;
11. archives JARs, source distribution, reports, test results, staging output,
    and JReleaser evidence.

The tag is intentionally absent while Maven Central publication is pending.
A failed gate or deployment leaves the remote tag untouched.

The separate Build workflow independently repeats `releaseCheck` on Java 17
and Java 21 and runs `verifyRcReadiness` on the canonical Java 17 lane.

## Manual diagnostics

These commands are read-only with respect to remote Git and Maven Central:

```bash
./gradlew dependencyPreflight --refresh-dependencies
./gradlew verifyPublishedDependencyIsolation verifyPublicationPom
./gradlew verifyTestArchitecture
```

`stageRelease` writes only `build/staging-deploy`; remote deployment is
owned by JReleaser in CI.

## Release tier and limitations

The candidate supports one JVM, in-memory state, sequential drain,
public-Root-scope closures, bounded cyclic components, and new exact
`FROM_NOW` operation-produced lineages. It does not claim process-restart
recovery, durable provider completeness, provider-backed Mandates,
parallel/distributed scheduling, production MyOS operations, or a stable
latency SLA.

`verifyRcReadiness` proves the current semantic bounded-pilot profile and
produces fresh artifact hashes after executing the complete
published-dependency build.

The rc.1 Round 13 reports and schemas are immutable historical evidence. Their
performance exception is rc.1-specific and is not part of rc.7 or any future
stable release.
