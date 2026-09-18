# Releasing

The dynamic-evolution downstream handoff is documented in
[`immutable-staged-coordination.md`](immutable-staged-coordination.md). It is
separate from Maven Central staging and is intentionally non-overwriting.

## Current decision

`3.0.0-rc.13` is authorized as a bounded external-pilot release candidate
only after all required gates pass on the exact published dependency tuple.
It is not stable or production-ready. The exact scope and non-claims are in the
[rc.13 release decision](../releases/3.0.0-rc.13.md).

The release consumes only Maven Central artifacts:

| Component | Version |
| --- | --- |
| Language | `3.1.0-rc.32` |
| BEX core/contracts | `1.1.0-rc.6` |
| Repository | `3.0.0-rc.22` |
| Coordination | `3.0.0-rc.13` |

Coordination directly owns the complete Language rc.32 graph. BEX and
Repository Language transitive edges remain excluded, and the generated POM
publishes the same exclusions.

The complete current release gate and focused rc.13 capability inventory are
the required semantic evidence for this release. Their documentation does not
claim that they have passed. Historical rc.4, rc.5, and rc.6 receipts remain evidence
for their original releases only.

## Before merging to `next`

From a clean feature branch:

Keep `.cz.toml` at the authorized, not-yet-tagged candidate (`3.0.0-rc.13`)
so PR Build also executes `verifyRcReadiness` during staging. Build seals a local
verification commit without a release tag or publication. The release workflow
owns the final version commit and tag; it retains RC13 while its tag is absent
and rejects a later candidate until its release authority is explicitly updated.

```bash
node --test .github/scripts/*.test.js
./gradlew --no-daemon dependencyPreflight --refresh-dependencies
./gradlew --no-daemon --no-build-cache clean releaseCheck \
  -PtestJavaVersion=17
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
job. RC and stable releases share `.github/workflows/release-candidate.yml`.
The stable entry point remains restricted to `main` and a stable version.
The shared workflow:

1. checks out complete history and tags and sets up Java 17;
2. validates credentials and prepares the RC authorized by
   `docs/releases/3.0.0-rc.13.md`, with a local verification commit and tag;
3. exports and restores that exact source using a SHA-256-bound Git bundle;
4. runs three groups of complete test classes and an independent extracted-source
   check on separate Java 17 runners (two JVMs × two JUnit threads per test runner);
5. requires all four jobs to succeed, validates complete test inventory, topology,
   source/run/attempt-bound receipts and archive hashes, then runs the remaining
   release and RC13 readiness gates and stages the Java 17 artifacts;
6. seals the verified artifacts and checks the handoff in the publication job;
7. atomically reserves the exact RC commit on `next` and its annotated tag;
8. deploys the signed bundle, waits separately for Maven Central `PUBLISHED`,
   and retains artifacts, reports and JReleaser diagnostics.

A failed or cancelled verification cannot start publication. The publish job
uses `jreleaserDeploy -x stageRelease` only after handoff validation, deploying
the already verified bytes. Normal local Gradle commands retain the complete
suite and normal deployment still depends on `stageRelease`. The CI shard
aggregation does not rerun the tests and does not omit their evidence.

The remote RC tag reserves a verified version before upload; it is not proof
that Maven Central publication succeeded. A failed verification gate creates no
remote reservation. A failed deployment or publication wait leaves the reserved
commit and tag in place, so a later preparation selects a higher RC instead of
reusing a potentially uploaded version. The exact release-authority check still
applies: a higher RC requires an explicitly updated release decision. Automation
does not rewrite that authority or remove reservations to retry a used version.
Automatic `chore: release ...` pushes enter a separate skip concurrency group;
actual and manually dispatched RC runs retain the shared serialized queue.

Core verification archives retain JUnit XML on both success and failure so
individual test durations can be examined alongside aggregate timing receipts.

The separate Build workflow independently repeats `releaseCheck` and
`verifyRcReadiness` on Java 17.

The workflows run on Java 17 with two test JVMs and two concurrent test
methods per JVM, including the extracted-source smoke. Test execution-scope
receipts and rooted gas evidence are included in the uploaded evidence.

### Historical baseline: repeated post-merge verification

The following measurements describe the earlier Java 17/21 workflow, not the
current Java 17 configuration.

The merge push still starts both the Build matrix and Release RC, and the
release commit starts the Build matrix again. These independent Build runs
are unchanged by the parallel release gates. For the RC6 release on September 7,
2026, the previous sequential release job spent
77m 04s in the Java 21 gate, 78m 05s in Java 17 staging, and 14m 45s publishing.
The independent merge Build took 80m 55s; the release-commit Build took
98m 37s. See the [release run](https://github.com/bluecontract/blue-coordination-java/actions/runs/34151341442).

The completed PR #21 jobs took 52m 15s on Java 17 and 49m 05s on Java 21,
including verification and setup. Running the release gates concurrently
should remove approximately one 48–50 minute gate from the release path,
less the cost of preparing and transferring the handoff. This is an estimate
based on the [PR run](https://github.com/bluecontract/blue-coordination-java/actions/runs/34540487291),
not a measured publication duration. The release duration is now approximately
preparation + the slower JDK gate + handoff/publication, instead of the sum of
the JDK gates. No test class rebalancing or further test-fixture optimization
is part of this change.

Removing repeated Build workflow executions remains a separate change. A PR
result, a release commit message, or an unbound copied report cannot substitute
for verification of the actual release candidate.

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

The candidate supports resident processing and host-backed external-state
engine/SDK adapters, including cold reopen from coherently published retained
state. Rooted processing semantics and bounded cyclic components are unchanged.
The host owns atomic persistence, fences, scheduling and durable work delivery;
Coordination adds no database implementation. It does not provide an
authoritative Timeline-provider completeness service, provider-backed Mandates,
distributed scheduling, production MyOS operations, arbitrary-history resource
bounds, or a stable latency SLA. See the RC13 decision for the exact storage
contract and remaining exclusions.

`verifyRcReadiness` proves the current semantic bounded-pilot profile and
produces fresh artifact hashes after executing the complete
published-dependency build.

The rc.1 Round 13 reports and schemas are immutable historical evidence. Their
performance exception is rc.1-specific and is not part of rc.13 or any future
stable release.
