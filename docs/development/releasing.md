# Releasing

The dynamic-evolution downstream handoff is documented in
[`immutable-staged-coordination.md`](immutable-staged-coordination.md). It is
separate from Maven Central staging and is intentionally non-overwriting.

## Current decision

`3.0.0-rc.8` is authorized as a bounded external-pilot release candidate
only after all required gates pass on the exact published dependency tuple.
It is not stable or production-ready. The exact scope and non-claims are in the
[rc.8 release decision](../releases/3.0.0-rc.8.md).

The release consumes only Maven Central artifacts:

| Component | Version |
| --- | --- |
| Language | `3.1.0-rc.25` |
| BEX core/contracts | `1.1.0-rc.6` |
| Repository | `3.0.0-rc.22` |
| Coordination | `3.0.0-rc.8` |

Coordination directly owns the complete Language rc.25 graph. BEX and
Repository Language transitive edges remain excluded, and the generated POM
publishes the same exclusions.

The complete current release gate and focused rc.8 capability inventory are
the required semantic evidence for this release. Their documentation does not
claim that they have passed. Historical rc.4, rc.5, and rc.6 receipts remain evidence
for their original releases only.

## Before merging to `next`

From a clean feature branch:

Keep `.cz.toml` at the preceding released version in the feature PR. The Build
workflow prepares rc.8 and seals a local verification commit before running
these Gradle gates. For local `verifyRcReadiness`, reproduce that preparation
in an isolated validation checkout; do not commit the prepared version to the
feature branch. The release workflow owns the final version commit and tag.

```bash
node --test .github/scripts/*.test.js
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

A push to `next` starts `.github/workflows/release-rc.yml`. Manual dispatch is
also restricted to `next`; a selected feature branch cannot enter the release
job. The workflow:

1. checks out the complete history and tags;
2. pins Temurin 17.0.19+10 for the canonical build and Temurin
   21.0.11+10.0.LTS for compatibility verification;
3. validates release credentials and the wrapper;
4. prepares the version authorized by `docs/releases/3.0.0-rc.8.md`;
5. creates the annotated tag locally and verifies push permissions;
6. resolves the exact published dependency graph;
7. runs the complete Java 21 release gate before any staging;
8. runs `stageRelease` on Java 17, including the complete release and rc.8
   gates;
9. pushes the verified release commit to `next` without publishing tags; a
   competing change to `next` rejects this push before any external deployment;
10. deploys the signed bundle to Maven Central;
11. pushes only the release tag after deployment succeeds;
12. archives JARs, source distribution, reports, test results, staging output,
    and JReleaser evidence.

The tag is intentionally absent while Maven Central publication is pending.
A failed gate or deployment leaves the remote tag untouched.
The verified release commit is already on the remote before deployment starts.
If `next` advances during deployment, the final tag-only push leaves the newer
branch head in place and still identifies the exact published commit. A failed
deployment can therefore leave a verified release commit on `next` without a
published tag; the commit message alone is not evidence of publication.

The separate Build workflow independently repeats `releaseCheck` on Java 17
and Java 21 and runs `verifyRcReadiness` on the canonical Java 17 lane.

All three workflows select four independent test JVMs, including the
extracted-source smoke. The runtime matrix and all release gates remain in
place. Test execution-scope receipts and rooted gas evidence are included in
the uploaded evidence.

### Repeated post-merge verification

The current workflow structure performs more work than the release requires:
the merge push starts both the Build matrix and Release RC, Release RC runs
its two Java gates sequentially, and the release commit starts the Build
matrix again. For the RC6 release on September 7, 2026, the release job spent
77m 04s in the Java 21 gate, 78m 05s in Java 17 staging, and 14m 45s publishing.
The independent merge Build took 80m 55s; the release-commit Build took
98m 37s. See the [release run](https://github.com/bluecontract/blue-coordination-java/actions/runs/34151341442).

Parallel test execution reduces the test work's elapsed time in every path.
Removing the repeated workflow executions is a separate release orchestration
change: prepare one exact release commit, verify that commit on both Java
versions concurrently, and publish the Java 17 staged artifacts only after
both gates succeed. Such a handoff must bind the source commit, dependency
identities, artifact hashes, and verification receipts. A PR result, a release
commit message, or an unbound copied report cannot substitute for that proof.

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
performance exception is rc.1-specific and is not part of rc.8 or any future
stable release.
