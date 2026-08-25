# Releasing

## Current decision

`3.0.0-rc.3` is authorized as a bounded external-pilot release candidate.
It is not stable or production-ready. The exact scope and non-claims are in the
[rc.3 release decision](../releases/3.0.0-rc.3.md).

The release consumes only Maven Central artifacts:

| Component | Version |
| --- | --- |
| Language | `3.1.0-rc.21` |
| BEX core/contracts | `1.1.0-rc.4` |
| Repository | `3.0.0-rc.21` |
| Coordination | `3.0.0-rc.3` |

Repository rc.21's stale Language rc.20 transitive edge is excluded; the direct
Language rc.21 pin is authoritative and the generated POM publishes the same
exclusion.

The dynamic-contract-evolution evidence round is deliberately separate from
this retained rc.3 publication authority. It consumes the exact unpublished
Contracts checkpoint through `blueDependencyMode=immutable-staged-contracts`,
`blueContractsRepository`, and `blueContractsManifestSha256`. That lane permits
only manifest-bound `blue.language` bytes from the immutable handoff repository;
it has no Maven Local, included-build, source-copy, or Maven Central fallback.
The resulting Coordination candidate must receive its own receipt and release
authority before publication.

## Before merging to `next`

From a clean feature branch:

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
4. prepares the version authorized by `docs/releases/3.0.0-rc.3.md`;
5. creates the annotated tag locally and verifies push permissions;
6. resolves the exact published dependency graph;
7. runs the complete Java 21 release gate before any staging;
8. runs `stageRelease` on Java 17, including the complete release and rc.3
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

The retained local rc.3 receipt proves the semantic bounded-pilot profile. Its
local artifact hashes are historical and are not compared with Maven Central
bytes. `verifyRcReadiness` produces fresh artifact hashes after executing the
current published-dependency build.

The rc.1 Round 13 reports and schemas are immutable historical evidence. Their
performance exception is rc.1-specific and is not part of rc.3 or any future
stable release.
