# Complete RC verification experiment, no publication

Branch `codex/ci/coordination-release-experiment`, based on next
`69cd6db22707028abfbaab04741dc9b61961aa19` (RC11). The existing production
Build/Release RC/Release stable workflows are unchanged.

The latest observed complete RC took1:59:48. Its Java17/21 gates already run
concurrently with four test JVM forks. The95min verification gate includes
approximately10min of a nested extracted-source smoke build. This experiment
moves only that smoke build to independent runners while preserving all full
unit/integration/consumer/scenario suites and topology validation in the core.
It does not enable parallel methods or shard tests.

## Matrix and scope

Six independent Ubuntu24.04 jobs (each4vCPU, standard public runner):

| Variant | JDK17 | JDK21 |
|---|---|---|
| baseline | dependencyPreflight; clean stageRelease | dependencyPreflight; clean releaseCheck |
| core | identical, with archive receipt import | identical, with archive receipt import |
| archive | dependencyPreflight; clean verifyExtractedSourceArchive | same |

All jobs use4forks/max-workers4 and isolated fresh Gradle homes, pinned JDKs,
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
proof, Java17 staged bytes/module and RC readiness. The comparison requires the
same baseline/core handoff and identical cross-JDK artifact/test inventories.
Maven timestamp metadata is not a reproducible artifact input; production
inspectBuild checks actual JAR/POM/module bytes instead.

## Measurements

Final summary compares the wall window across both baseline jobs with the window
across both core and both archive jobs. Windows include staggered measurement
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

- Local: Python receipt/command/inventory tests, existing32 Node release tests,
  actionlint, diff inspection/check. No local full build.
- CI: both full baseline gates, both full optimized gates, both archive smoke
  jobs and final comparison. Until they complete this is CI_PENDING.
- No source production files changed. Experimental workflow is reviewable before
  commit/push; remote execution requires pushing this exact branch.
