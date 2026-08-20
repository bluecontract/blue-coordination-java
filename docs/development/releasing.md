# Releasing

## Current decision: local-only SDK freeze candidate

`3.0.0-rc.3` is a prepublication candidate. The authorized workflow stages and
verifies artifacts in an explicit local file repository. It does not upload a
package, publish to Maven Local, push a branch/commit/tag, or create a remote
release.

This distinction is part of the release claim. A successful local staging run
does not make the coordinate available to external consumers and is not a
publication receipt.

## Candidate prerequisites

The coordinated inputs must be exact and clean:

| Component | Candidate | Required source of bytes |
| --- | --- | --- |
| Language | `3.1.0-rc.21` | locally staged JAR/POM/module metadata |
| BEX core/contracts | `1.1.0-rc.4` | locally staged against that Language |
| Repository | `3.0.0-rc.21` | locally staged against that Language |
| Coordination | `3.0.0-rc.3` | this SDK candidate |

The specification and fixtures are read from the clean `../blue-spec/latest`
checkout, not an archived copy under `docs/`. The recovered topology commits,
reports, bundles, and source archives are provenance inputs; they are not
reconstructed from completion notes.

Before artifact staging:

- all SDK source/acceptance tests and the built-JAR consumer pass;
- public SDK signatures contain no low-level closure/proof types;
- the bundled release manifest and required SDK classes are in the production
  JAR and Javadoc;
- the focused recovered topology verification is recorded;
- no unresolved gate is relabeled as a pass.

## Exact local workflow

Stage prerequisites in the order documented in
[Build and test](build-and-test.md): Language first, then BEX and Repository
against those Language bytes, then Coordination. Merge their verified Maven
repository contents into one fresh absolute directory. In the BEX worktree,
run `publish bexSdkStageVerify` with the staging properties; the verification
task alone does not write artifacts. Then run:

```bash
./gradlew sdkFreezePrepublicationCheck \
  -PblueDependencyMode=staged-artifact \
  -PblueStagingRepository=/absolute/path/to/blue-sdk-staged-repository

./gradlew stageSdkFreezeCandidate \
  -PblueDependencyMode=staged-artifact \
  -PblueStagingRepository=/absolute/path/to/blue-sdk-staged-repository

./gradlew verifySdkStagedDependencyGraph \
  verifySdkStagedCandidateRepository \
  verifyExtractedSdkConsumerJava17 \
  verifyExtractedSdkConsumerJava21 \
  sdkFreezeArtifactCheck \
  -PblueDependencyMode=staged-artifact \
  -PblueStagingRepository=/absolute/path/to/blue-sdk-staged-repository
```

`.cz.toml` intentionally remains the historical rc.1 authority for the existing
`stageRelease` workflow. Only `staged-artifact` selects the explicit rc.3 SDK
candidate override; the prepublication and candidate-repository checks require
that effective version and verify that the JAR manifest, POM, and Gradle module
metadata agree. This mode contains no included sibling builds and ignores Maven
Local. The staged dependency graph must contain module components at the exact
table versions. `verifySdkStagedCandidateRepository` also verifies the main,
sources, and Javadoc JAR inventory. The extracted `staged-sdk-consumer/`
resolves only the file repository and must run on both Java 17 and Java 21.

`sdkFreezeArtifactCheck` is the terminal local prepublication gate. Do not
follow it with a JReleaser deploy, Maven publication, Git push, or tag command
under this plan.

## Artifact and evidence checklist

The external evidence directory, not a historical receipt path, must bind:

- exact source commit IDs and clean status for every component;
- staged coordinates and resolved module-component versions;
- SHA-256 for the main, sources, and Javadoc JARs, POM, Gradle module metadata,
  source ZIP, topology bundles, and source archives;
- bundled Language specification, Contracts release, fixture package, gas
  manifest, cyclic finalizer, and proof-verifier identities;
- ordinary/closure fixture totals from the final staged bytes;
- recovered topology test names, counts, durations, document-step order, gas,
  component membership, document BlueIds, and structural counters;
- SDK unit/acceptance, built-JAR consumer, and extracted Java 17/21 consumer
  results;
- the supported from-now managed-draft cases, their malformed-evidence and
  rollback matrix, and the explicit imported/history activation exclusions.

Generate `FINAL_RECEIPT.md`, `final-receipt.json`, and
`changed-files.sha256` only from the final candidate state. Do not edit the
retained rc.1 Round 13 Markdown, JSON, schemas, or provenance files to make
them describe rc.3.

## Conformance decision

The semantic freeze and artifact readiness decisions are independent.
The recovered topology architecture and staged SDK artifacts can be valid while
the implementation-conformance claim remains false.

For rc.3, the public SDK supports the required new-lineage `FROM_NOW`
operation-result lane, including the Order draft and the five-occurrence,
three-lineage duplicate-lineage case. Imported known-epoch drafts and
historical/frontier/attach-current/passive activation remain deliberately
unsupported. The source tree alone does not decide the release claim. Until
the complete final staged acceptance, fixture, artifact, and Java 17/21
consumer corpus passes, the working receipt must retain:

```text
implementationConformanceClaimed = false
```

Only the exact final source commits, immutable staged bytes, complete
acceptance/fixture execution, and artifact-bound Java 17/21 consumers can make
that value eligible for review. A partial source-suite or staging success alone
cannot.

## External-pilot tier

After the supported SDK cases and staged consumer gates pass, the candidate can
be handed to a controlled external pilot as local artifacts with these stated
limits:

- one JVM and in-memory state only;
- no fresh-process durable recovery or serialized publication-store adapter;
- no external provider-completeness adapter;
- no provider-backed Mandate resolver;
- sequential drain and no distributed scheduling;
- public-Root-scope closure profile with bounded cyclic components;
- new from-now managed drafts produced by operations are supported; imported
  state and historical occurrence activation are unsupported;
- no stable latency SLA;
- not a production MyOS durability, tenant-isolation, outbox-recovery,
  backpressure, or operational profile.

Pilot suitability is not production readiness and does not imply the full
Contracts implementation-conformance claim.

## Historical rc.1 workflow and evidence

The existing `published-artifact`, `stageRelease`, JReleaser, Round 13, and
GitHub publication tasks remain bound to the earlier rc.1 workflow. They are
deliberately unchanged by the SDK freeze lane. The retained campaign failed
append and Coordination-host p95 hard limits and claimed no latency pass; its
narrow `PASS_WITH_KNOWN_PERFORMANCE_LIMITATION` policy was rc.1-specific and
cannot be inherited by rc.3 or a stable release.

Historical receipts remain useful audit evidence, but none of them proves the
SDK candidate. Performance remediation, durable production adapters, complete
conformance, and an explicitly authorized remote workflow are separate future
release decisions.
