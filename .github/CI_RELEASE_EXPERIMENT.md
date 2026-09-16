# Complete RC verification experiment, no publication

Branch `codex/ci/coordination-release-experiment`, based on next
`69cd6db22707028abfbaab04741dc9b61961aa19` (RC11). The review branch now migrates Build/Release RC/Release stable to one Java 25
runtime, matching MyOS Java. No production workflow has been dispatched.
Only JDK 25 is installed, including compilation, tests and extracted-source
validation. `options.release`, source/target compatibility and published bytecode
remain Java 17; dropping CI coverage of runtimes 17/21 is intentional.
The handoff validator now requires one complete Java 25 staging receipt and
rejects obsolete JDK receipts. All four full suites, topology and staged-byte
checks remain mandatory. Historical Round 12/13 evidence documents retain their
original runtime records; they do not describe the new CI matrix.

The latest observed complete RC took1:59:48. Its Java17/21 gates already run
concurrently with four test JVM forks. The95min verification gate includes
approximately10min of a nested extracted-source smoke build. This experiment
moves only that smoke build to independent runners while preserving all full
unit/integration/consumer/scenario suites and topology validation in the core.
It does not enable parallel methods or shard tests.

## Matrix and scope

Three independent Ubuntu24.04 jobs (each 4 vCPU, standard public runner):

| Variant | Java 25 |
|---|---|
| baseline | dependencyPreflight; clean stageRelease |
| core | identical, with archive receipt import |
| archive | dependencyPreflight; clean verifyExtractedSourceArchive |

All jobs use4forks/max-workers4 and isolated fresh Gradle homes, the same Temurin 25 release,
no build cache. Wrapper distribution bootstrap is outside timing and retries
only distribution setup. Dependencies/preflight are inside timing.

`stageRelease` writes only `build/staging-deploy` inside the runner. No release
version preparation, tag creation, git push, signing credentials, external Maven
publication, or JReleaser task runs. Token has contents:read/actions:read only;
checkout does not persist credentials. The workflow triggers only this branch,
with the same guard on manual dispatch. Existing RC triggers next only and stable
requires main. Do not dispatch either production release workflow to test this.

## Actual evidence, no skipped tests

The archive job executes the original verifyExtractedSourceArchive task,
including its7 focused test classes from the generated ZIP, checksum and
configuration/dependency isolation checks. It uploads the original proof together
with a successful measured command receipt bound to HEAD/tree/run/attempt/JDK.

Only core uses the experiment init script. It replaces the archive task action
with downloading and validating that actual receipt and comparing the local
source ZIP SHA before installing the original proof. Task exclusions are rejected.
Source archive/checksum and policy prerequisites still execute locally. The
production Test tasks, full-scope validator and topology-fragment validator are
untouched. An absent, failed, stale or wrong-source receipt fails the job.

After every main command, the experiment calls the existing production
`release-handoff.js.inspectBuild`, validating complete cases, artifacts, archive
proof, Java 25 staged bytes/module and RC readiness. The comparison requires the
same baseline/core handoff and identical baseline/core artifact/test inventories.
Maven timestamp metadata is not a reproducible artifact input; production
inspectBuild checks actual JAR/POM/module bytes instead.

## Measurements

Final summary compares the wall window of the baseline job with the window
across core and archive jobs. Windows include staggered measurement
starts and receipt waiting, but exclude tool setup/upload/summary. Individual
command times and process-tree sampled CPU/RSS/PSS are retained in timing.json.
Sampling includes observed detached descendants; very short processes and memory
peaks may be missed. Metrics are observations, not proof of a bottleneck.

Publication/Maven polling remains **unmeasured in this experiment**. The latest
historical RC publication command took22:13 including around20:13polling; do not
subtract this from a new experiment and claim measured full release speedup.
Both variants use the same source and coverage. The first run may show no speedup.

Re-run all jobs together. Attempt-bound receipts deliberately reject reusing an
archive result from an earlier attempt. Never compare results from another SHA.

## Verification

- Local: Python receipt/command/inventory tests, existing Node release tests and the Java 25 workflow contract,
  actionlint, diff inspection/check. No local full build.
- CI: the full baseline gate, full optimized gate, archive smoke
  job and final comparison. Until they complete this is CI_PENDING.
- Production Build and shared RC/stable verification now call `verification.yml`.
  It runs full Java 25 staging and extracted-source verification concurrently.
  The core consumes the actual archive receipt before sealing the release handoff;
  the publication job waits for the reusable workflow, which requires both jobs.
- Build verifies the checked-in version. Only the production RC release workflow
  allocates a new RC version. This prevents ordinary PR validation from advancing
  beyond the checked-in release authority merely because its tag already exists.
- Baseline and optimized measurements both use Java 25 on the same commit.
  Historical 17/21 RC times are context only, not a controlled Java 25 baseline.
- Development-only manifest gates that describe existing dependency builds remain
  unchanged; this experiment uses the published-artifact lane.

## Production topology proof

`verify-production-topology.yml` runs only on the experiment branch and calls the
same source preparation and verification workflows used by Build. It creates an
empty local source commit, bundles it, restores that exact prepared SHA in both
runners, and verifies the final sealed handoff in a third job. It has read-only
permissions, no secrets, no tags, no git pushes, and no external publication.
RC/stable use their existing candidate preparation and the same verification
workflow. Source bundles preserve their candidate tag when the release context
requires it; verification-only bundles require no tag.

Archive receipts are bound to source SHA/tree, workflow run/attempt, Java 25 and
scope (`build`, `rc`, `stable`, `topology`). Wrong-scope or stale receipts cannot
satisfy the core gate. The owner executes the original extracted-source task;
only the core action becomes receipt validation. Both source ZIP bytes and all
seven focused test names are checked before the original archive proof is used.
Full unfiltered test suites, topology evidence, artifact hashes and local staged
bytes are still checked by the existing release handoff inspector.

The first Java 25 run (35114905683) failed before tests because javac25 diagnosed
two unattached documentation comments under the existing `-Werror` policy. The
narrow fix converts those two comments to ordinary comments. Compiler warning
checks remain enabled. No successful build duration is inferred from that run.

Local acceptance includes negative scope/source/attempt tests, an actual prepared
source bundle restore without release tags, and a tiny real Gradle fixture proving
valid receipt import plus rejection of wrong scope, task exclusions and publication.
The full production-topology gate and Java25 A/B measurement remain CI_PENDING
until observed green remotely; no publication is exercised.
