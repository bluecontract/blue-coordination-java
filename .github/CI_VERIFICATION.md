# CI verification and publication

Build and validate, Release RC and Release stable use Java 17 and the shared
`verification.yml` workflow. Preparation seals one exact source bundle. The full
test inventory is split into three complete-class groups, alongside independent
extracted-source archive tests. Each group discovers current compiled classes;
historical timing weights only balance assignment and new classes are included.
The final core job requires all groups and the archive to succeed, validates their
source/run/attempt bindings and file hashes, then imports XML and generated evidence.
It runs the complete topology comparison and remaining release/artifact gates,
without repeating the test groups. Scope receipts explicitly identify delegated
execution; normal local Gradle commands retain their full unfiltered test graph.
Colliding XML/topology ownership or conflicting semantic evidence is rejected.
Parameterized testcase multiplicity is retained. This preserves class-coverage
checks; exact testcase parity against the prior full run is also checked during rollout.

RC and stable share `release-candidate.yml`. The publishing job restores only
verified staged artifacts, then runs these steps in order:

1. Reserve the verified next commit and RC tag atomically (RC only), before upload.
   An existing tag or changed branch prevents publication; failed publication does
   not make the reserved version available for reuse.
2. Publish to Maven Central: sign/upload/validate and submit publication using
   JReleaser. `-PmavenCentralSeparateWait=true` stops its final wait after Central
   acknowledges publishing. Local JReleaser invocations still wait by default.
3. Wait for Maven Central publication: read the deployment ID from this invocation's
   `build/jreleaser/output.properties` and call only Central's status endpoint until
   `PUBLISHED`. Missing IDs, rejected deployments, malformed responses or timeout
   fail the step. It does not upload or submit publication again.

The wait receipt is archived in `build/jreleaser/maven-central-publication.json`.
A green release still means Maven Central confirmed publication. Splitting the
steps improves visibility; it does not shorten Central's processing time.

Experimental timing/topology workflows are not retained in the production PR.
Historical paired measurements and their limitations remain in PR #26 and its
linked run artifacts. Production receipt/resource helpers remain covered by
Python tests; Node tests protect candidate/handoff and release ref ordering.

Fast checks (no publication):

```sh
node --test .github/scripts/*.test.js
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s .github/scripts -p 'test_*.py'
actionlint .github/workflows/*.yml
```

## Assigning tests to CI groups

Developers add tests normally; no shard annotation or manual group assignment is
required. Each CI job discovers the current compiled JUnit classes and computes
the same deterministic plan. Whole classes are sorted longest-first and assigned
to the group with the smallest accumulated weight; ties use stable suite/class
names and group ids. Test methods and assertions are unchanged.

Weights in `.github/scripts/ci-test-weights.json` are JUnit XML class elapsed
seconds from [Build 35264124428](https://github.com/bluecontract/blue-coordination-java/actions/runs/35264124428).
They are scheduling hints, not a test inclusion list. A newly discovered class
without a measurement receives weight 1 and still runs. Removed classes are not
selected. Weights are currently refreshed explicitly from successful CI reports;
CI does not commit weight updates automatically. Refreshing these data affects
only CI group assignment; normal local Gradle commands remain unchanged.

To refresh weights explicitly from a successful sharded Build (requires `gh`
authentication), run from the repository root:

```sh
python3 .github/scripts/refresh-test-weights.py --run 35264124428
git diff -- .github/scripts/ci-test-weights.json
```

Use the chosen successful run ID. The helper downloads all three shard artifacts,
checks their run/attempt binding, complete class coverage and report hashes, then
updates only the weights file. It refuses failed/incomplete runs and never commits,
pushes, starts workflows or publishes packages. Use `--output /tmp/weights.json`
to inspect measurements without changing the repository. Updating weights does
not guarantee a faster run; compare actual group durations before retaining them.
