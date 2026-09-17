# CI verification and publication

Build and validate, Release RC and Release stable use Java 25 and the shared
`verification.yml` workflow. Preparation seals one exact source bundle. The full
core verification and extracted-source archive tests run in parallel. The core
checks the archive receipt and exact source/archive identity instead of running
those tests a second time. Full suite coverage, topology evidence and staged
artifact hashes remain required before publication.

RC and stable share `release-candidate.yml`. The publishing job restores only
verified staged artifacts, then runs these steps in order:

1. Publish to Maven Central: sign/upload/validate and submit publication using
   JReleaser. `-PmavenCentralSeparateWait=true` stops its final wait after Central
   acknowledges publishing. Local JReleaser invocations still wait by default.
2. Wait for Maven Central publication: read the deployment ID from this invocation's
   `build/jreleaser/output.properties` and call only Central's status endpoint until
   `PUBLISHED`. Missing IDs, rejected deployments, malformed responses or timeout
   fail the step. It does not upload or submit publication again.
3. Push the published release tag (RC only), after successful confirmation.

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
