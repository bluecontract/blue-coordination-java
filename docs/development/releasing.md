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
- The one canonical Round 13 report and JSON evidence use the Round 13
  Playground schema. `releaseCheck` may validate an honest `INTERIM` record
  whose unrun outcomes remain unset. `verifyRound13Readiness` permits staging
  only for complete `FINAL`, clean-commit evidence, the seven exact proof rows,
  eight measured zero counters, the 30-sample same-machine campaign, final
  artifact hashes, a valid detached source-archive checksum sidecar, and
  published-mode evidence; no duplicate canonical report or JSON surface
  remains. The tested implementation commit may precede the clean evidence
  commit, but it must be an ancestor and the current main-source manifest must
  still match exactly.
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

## Round 13 A/B evidence import

After the interleaved runner has written exactly 30 numbered JSON rows under
each of `/private/tmp/round13-ab/baseline` and `candidate`, assemble and verify
the canonical receipts with:

```bash
node scripts/round13-ab-evidence.mjs \
  --raw /private/tmp/round13-ab \
  --baseline /private/tmp/round13-baseline-project \
  --candidate /Users/piotr/data/blue-contract-java \
  --archive /Users/piotr/data/blue-contract-java/Archive.zip \
  --runner /private/tmp/run-round13-same-machine-ab-local.sh \
  --output /Users/piotr/data/blue-contract-java/docs/releases/evidence/round13
```

The importer validates every raw row, preserves raw and canonical hashes,
records the odd A/B and even B/A sequence, and binds Java 17, the wrapper,
locks, fixtures, composite dependency commits, the exact baseline archive, and
the candidate production manifest. It recomputes p50, p95, maximum, and the
preferred/hard append, route, Coordination-host, and total gates. The tracked
source-archive evidence intentionally leaves its digest `null`; the generated
detached `.sha256` sidecar is the checksum authority.
