# Build and test

## Prerequisites

Use Java 17+ and the checked-in Gradle wrapper. Production compiles with Java
17, `-Xlint:all`, and `-Werror`. Tests can run on Java 21 with
`-PtestJavaVersion=21`.

The canonical specification and fixtures come from `../blue-spec/latest`.
Override that clean checkout only with
`-PblueSpecRoot=/absolute/path/to/blue-spec/latest`; do not restore archived
copies under this repository's `docs/` tree.

## Source-backed development

`local-composite` is the default implementation mode. It substitutes
`../blue-language-java`, `../blue-bex-java`, and `../blue-repository-java`, or
paths supplied with `-PblueLanguageCompositePath`, `-PblueBexCompositePath`,
and `-PblueRepositoryCompositePath`.

The Language substitution is an aligned source graph: model, core, mapping,
IPFS, runtime aggregate, and Contracts all come from one included build. Mixing
a source Contracts kernel with published Language runtime JARs is rejected.

```bash
./gradlew verifyLocalCompositeDependencies \
  -PblueDependencyMode=local-composite \
  -PblueLanguageCompositePath=/absolute/path/to/blue-language-java
```

`verifyLocalSourceInputs` binds explicitly configured Language and BEX
checkouts to their base commits and framed tracked/untracked production
fingerprints. The extracted-source smoke forwards the same absolute paths.
This lane is development evidence; it is not a staged-JAR consumer proof.

## Local-only SDK freeze lane

The candidate coordinate is exactly
`blue.coordination:blue-coordination-java:3.0.0-rc.3`. Its exact prerequisite
order is:

```text
Language 3.1.0-rc.21
  -> BEX 1.1.0-rc.4 and Repository 3.0.0-rc.21
  -> Coordination 3.0.0-rc.3
```

Stage Language first. BEX and Repository must both resolve that staged
Language repository rather than a sibling build or Maven Local:

```bash
# Language worktree
./gradlew stagePublications verifyPublishedRepository \
  -PreleaseVersion=3.1.0-rc.21

mkdir -p /absolute/path/to/blue-sdk-staged-repository
rsync -a --checksum build/staging-deploy/ \
  /absolute/path/to/blue-sdk-staged-repository/

# BEX staging worktree
./gradlew publish bexSdkStageVerify \
  -PblueLanguageRepository=/absolute/path/to/language/build/staging-deploy \
  -PbexLocalStageVersion=1.1.0-rc.4 \
  -PbexSdkStagingRepository=/absolute/path/to/blue-sdk-staged-repository

# Repository staging worktree
./gradlew repositorySdkStageVerify \
  -PblueLanguageRepository=/absolute/path/to/language/build/staging-deploy \
  -PrepositoryLocalStageVersion=3.0.0-rc.21 \
  -PrepositorySdkStagingRepository=/absolute/path/to/blue-sdk-staged-repository
```

The `rsync` step seeds the unified repository with the verified Language bytes.
The BEX command must include `publish`: `bexSdkStageVerify` is a verification
gate and does not itself write BEX artifacts. BEX and Repository then append
only their locally staged coordinates. Before running Coordination, the
unified repository must contain real JAR, POM, and Gradle module metadata for
every coordinate.

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

The gates mean:

- `sdkFreezePrepublicationCheck` runs the SDK facade, acceptance, public
  signature, Javadoc, built-JAR consumer, documentation, and artifact
  prerequisites.
- `stageSdkFreezeCandidate` refuses an effective Coordination version other
  than rc.3. Only `staged-artifact` selects that override; `.cz.toml` remains
  the historical rc.1 authority for unchanged `stageRelease` behavior.
- `verifySdkStagedDependencyGraph` requires module components at the exact
  versions above and rejects project/composite substitutions.
- `verifySdkStagedCandidateRepository` checks the locally staged Coordination
  rc.3 POM, module metadata, main/sources/Javadoc JARs, and required SDK/release
  manifest entries before a consumer can use them.
- `verifyExtractedSdkConsumerJava17` and
  `verifyExtractedSdkConsumerJava21` compile and run
  `staged-sdk-consumer/` against the staged repository only.
- `verifyExtractedSdkConsumer` aggregates the two consumer runtimes.
- `sdkFreezeArtifactCheck` is the final local artifact aggregate.

`staged-artifact` includes no sibling builds, does not consult Maven Local, and
does not deploy remotely. These commands do not push a commit or tag. Passing
the lane proves consistency of the local candidate bytes; it does not claim
remote availability or implementation conformance.

## Coordination suites

```bash
./gradlew test
./gradlew integrationTest consumerTest scenarioTest
./gradlew releaseCheck
./gradlew sdkFreezePrepublicationCheck \
  -PblueDependencyMode=staged-artifact \
  -PblueStagingRepository=/absolute/path/to/blue-sdk-staged-repository
```

The repository-owned suites have distinct responsibilities:

- `test` covers SDK immutable values and authored compilation as well as public
  API values, atomic internals, and retained processor semantics. Its SDK
  acceptance cases exercise the public facade without casts to engine
  internals or hand-built closure proof values, including the from-now
  operation-produced Order draft and five-occurrence/three-lineage cases.
- `integrationTest` covers exact append, engine-selected drain, entry-frame
  ordering, closure admission/publication, embedded topology, catch-up,
  ownership, and atomic retry.
- `consumerTest` compiles against the built production JAR, never main source
  output or test fixtures. Its SDK case imports the SDK boundary as a real
  consumer sees it.
- `scenarioTest` runs complete NBA and large-host/PayNote lifecycles.

`releaseCheck` runs the historical four-suite release surface and its retained
rc.1 gates. The SDK freeze aggregate adds SDK-specific signature, staging, and
consumer gates without rewriting the historical Round 13 tasks or receipts.
Commands listed here are gates to run, not claims that an arbitrary changed
worktree has passed.

## Historical remote and performance lanes

`published-artifact`, `stageRelease`, and the rc.1 GitHub publication workflows
are retained for historical compatibility. They are not part of the local-only
rc.3 SDK freeze. Likewise, the older `blue-basic` performance workflow used
Maven Local; do not run it for this candidate. Its receipts remain unchanged as
audit evidence, and a missing `../blue-basic` checkout cannot affect
`sdkFreezeArtifactCheck`.

Do not rerun the old long performance campaign merely to validate this SDK
delta. Use the recovered short topology smoke and keep its evidence separate
from the historical Round 13 latency receipts.

## Lock files

Regenerate a dependency lock only after an intentional version change:

```bash
./gradlew dependencies --write-locks
```

Review the complete lock diff. Refresh Language and BEX source locks only for
an intentional coordinated snapshot. A dirty workspace is valid only when its
framed fingerprint matches exactly.

See [test strategy](test-strategy.md) for the behavior-to-suite map.
