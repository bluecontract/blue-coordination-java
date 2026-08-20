# Cyclic-topology SDK freeze final receipt

Generated: `2026-08-20T04:18:22Z`

Receipt state: `FINAL_EXTERNAL_PILOT_RC_EVIDENCE`

This receipt closes the bounded cyclic-topology and Coordination SDK freeze. The
exact final Language, BEX, Repository, and Coordination candidates were assembled
in order into one local Maven repository, and the Coordination candidate passed
the complete staged-artifact release corpus on Java 17 and Java 21. The staged
artifacts execute the exact final fixture corpus, so
`implementationConformanceClaimed=true`.

The candidate is suitable for a bounded external pilot, not for public or
production release. It is local-only, single-JVM, in-memory, sequential, and has
no fresh-process durable recovery, provider-completeness adapter, Mandate
resolver, parallel scheduler, or stable latency SLA.

## Decision

| Claim | Value |
| --- | --- |
| Implementation conformance | **true** |
| External-pilot ready | **true** |
| Public release ready | **false** |
| Production/MyOS ready | **false** |
| Stable latency SLA | **false** |

The `implementationConformanceClaimed` flag is intentionally narrower than a
production-readiness claim. It says that the exact clean staged candidates passed
the exact final semantic corpus. It does not claim persistence, provider
completeness, Mandates, parallel execution, operational backpressure, tenant
isolation, or an SLA.

## Source bindings

All four implementation repositories were clean at their artifact/evidence
snapshots and use the bound branch names below.

| Component | Branch | Commit | Snapshot clean |
| --- | --- | --- | --- |
| Language | `feature/cyclic-topology` | `e0dfc897ea7d158895325fae2bf84e103b8c1989` | yes |
| BEX | `feature/cyclic-topology` | `23e9e62feb36bf14a579912bcfa80da84f5ee85f` | yes |
| Coordination | `feature/cyclic-topology` | `fb3aca034953035a46038e76f028cf882267a98d` | yes |
| Repository input | `feat/current-repository-api` | `2fcf29bf060ed114c971194adb6f8b747899aee2` | yes; source lock bound |
| Repository staging build logic | `codex/coordination-sdk-staging-repository` | `d305821bd813e77d46b7e559f03c0c6c902353f2` | yes; build-only staging override |
| Specification input | detached evidence clone | `5dc8096276652156e248c9c018a0850fcd8dbdbb` | yes |

The Coordination commit above is the parent evidence head for this receipt. The
receipt cannot bind its own resulting commit hash; that hash is reported in the
external handoff after commit.

The Repository staging commit changes only `build.gradle` over primary runtime
source `2fcf29bf060ed114c971194adb6f8b747899aee2`; it supplies the isolated local
staging override and does not replace the source-lock implementation commit.

## Frozen semantic identities

| Identity | SHA-256 |
| --- | --- |
| Language specification | `01b038b64e3f0a9a11f3f70d544a63ff78a01d5169f1a03f8b8629cf73645a7d` |
| Contracts and Processor specification | `dfb444962a5a17b3a6519e8d148c2bf4a975a921b1fcb1277710052caaecd930` |
| BEX specification | `1725878bcb59f2d2a60bae2ada582a18dc964f4dbc61377aaae195a773765f92` |
| Contracts release | `7e6c3717bc28d21ebadec9f81725913e944bb3b9b70094531f19f10510a10e50` |
| Contracts fixture package | `071cecb68e1c4dcec2dbb0895de928629281d2b0a18f3e8a83a41a720e621bfa` |
| Contracts gas manifest | `03219c42eb3696ef8727fe8ae226c8a5eb4a6126859ba744f571d892c409626a` |
| Cyclic finalizer implementation | `0b4bd3bbe4380faa52d14bc6baf8bb0a6dbc01acc576985676155ea0115969b4` |
| Cyclic proof verifier implementation | `eb0501a25ec5ac6a18fc86584c0afb6ecc2e6c1201c723f28ec56c80a2ae3bc5` |
| BEX fixture package | `a1b7bb2b3687389409bc9d0aa450c734f7856d2bcb818c95f4d7ecb19095d20e` |
| BEX gas manifest | `41247c820d91a12fdfc17fd9e787a5d8d668d8acc5954fdcb131715bf9e6147d` |

## Exact fixture and test results

The final Language release report records 3,156 passing tests, zero failures and
zero skips. The exact release-conformance corpus is:

| Corpus | Passed | Required | Failed | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Language fixtures | 153 | 153 | 0 | 0 |
| Ordinary Contracts fixtures | 167 | 167 | 0 | 0 |
| Closure Contracts fixtures | 67 | 67 | 0 | 0 |
| Total fixtures | 387 | 387 | 0 | 0 |

The final BEX staged-candidate report records 910 passing tests, zero failures,
and zero skips; 105/105 behavior fixtures, 30/30 gas microfixtures, 60/60
normative vectors, and 86/86 operators pass.

The BEX report retains `releaseReady=false` because public hosted
standalone/local-composite equivalence and independent public-release build pairs
are not applicable to this staged candidate, not merely because its coordinate
is staged. The staged SDK verification separately proves exact Language rc.21
artifact selection. This receipt makes no public-release claim.

The recovered public topology inventory is the exact 15-class, 57-test set
already recorded in the prior topology receipt. It passes in both final staged
Coordination lanes: `BlueRuntimeProviderMeterTest` (1),
`Contracts10AuthoredFacadeParityTest` (1), `Contracts10ScenarioBuilderTest` (5),
`ContractsClosureAdmissionAdapterTest` (10),
`ContractsClosureExecutionMetricsObserverTest` (2),
`ContractsPublicBranchingCollectionCycleTest` (4),
`ContractsPublicComponentMergeSplitTest` (5),
`ContractsPublicCycleDetachmentTest` (2),
`ContractsPublicInitializationTopologyTest` (5),
`ContractsPublicLoopAndIsolationTest` (2),
`ContractsPublicNestedScopeBoundaryTest` (2),
`ContractsPublicOrderingAcceptanceTest` (3),
`ContractsPublicThreeMemberCycleTest` (5),
`CyclicTopologyIdentityEvidenceTest` (1), and `OperationRouteIndexTest` (9).

The final SDK suite passes all 34 tests across `SdkAcceptanceTest` (12),
`SdkManagedDraftAcceptanceTest` (5), `SdkEdgeResultTest` (2),
`SdkOperationRuntimeTest` (9), and `SdkValueModelTest` (6). Acceptance cases
1–15 all pass, including managed draft creation, five occurrences over three
managed lineages, rollback, submit/drain parity, precise edge-result mappings,
and the extracted consumer compiled only against staged JARs.

## Staged graph and artifacts

The immutable stage is
`/Users/piotr/data/blue-staged-repository/cyclic-topology-rc3`. Its final manifest
contains 365 files and has SHA-256
`9418d0ea89049e44003b76f4280d7a39651768f882652eced0b093d6ca21ee4c`.
The exact graph is Language `3.1.0-rc.21`, BEX `1.1.0-rc.4`, Repository
`3.0.0-rc.21`, and Coordination `3.0.0-rc.3`.

The complete per-coordinate JAR, POM, Gradle module metadata, sources JAR, and
Javadoc JAR hashes are in `final-receipt.json`. The source ZIP identities are:

| Component | Source archive | SHA-256 |
| --- | --- | --- |
| Language | `blue-language-java-3.1.0-rc.21-source-release.zip` | `f09cef599388b8ec65229cf5423f343d9a59671f0269037ce1d95a19adc01272` |
| BEX | `blue-bex-java-1.1.0-rc.4-source-release.zip` | `cb6dedf515219a23cf31ab9843bc76705ec53849c28892003bbf73bde2483a17` |
| Coordination | `blue-coordination-java-3.0.0-rc.3-source.zip` | `899624139437febee3f61c2e1af13956969efc2d8a7ac99417a5fa7b1cf0eddf` |

Repository did not produce a separate source ZIP in this lane; its bound staged
sources JAR is recorded with its coordinate.

## Exact release commands

Language clean build — PASS in 8m09s; 137 tasks, 132 executed, 5 up-to-date:

```text
env PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home BLUE_RELEASE_CHANNEL=rc SOURCE_DATE_EPOCH=1787193278 ./gradlew --no-daemon --max-workers=1 clean build -PreleaseVersion=3.1.0-rc.21 -PblueSpecRoot=/Users/piotr/data/blue-spec-release-5dc8096/latest --no-parallel --no-build-cache --console=plain
```

Language final release aggregate — PASS in 4m08s; 200 tasks, 85 executed, 115
up-to-date:

```text
env PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home BLUE_RELEASE_CHANNEL=rc SOURCE_DATE_EPOCH=1787193278 ./gradlew --no-daemon --max-workers=1 finalQualityVerify rcVerify stagePublications verifyPublishedRepository sourceReleaseArchive -PreleaseVersion=3.1.0-rc.21 -PblueSpecRoot=/Users/piotr/data/blue-spec-release-5dc8096/latest --no-parallel --no-build-cache --console=plain
```

BEX clean staged verification — PASS in 23s; 76 tasks, 70 executed, 6
up-to-date:

```text
/usr/bin/env -i HOME=/Users/piotr USER=piotr LOGNAME=piotr TMPDIR=/var/folders/sr/1zpz2mjs2jg6vs80zcffx3h80000gn/T LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home/bin:/usr/bin:/bin:/usr/sbin:/sbin ./gradlew --no-daemon clean bexSdkStageVerify -PblueLanguageRepository=/Users/piotr/data/blue-staged-repository/cyclic-topology-rc3 -PbexSdkStagingRepository=/Users/piotr/data/blue-staged-repository/cyclic-topology-rc3 -PbexLocalStageVersion=1.1.0-rc.4
```

BEX publication into the isolated stage — PASS in 11s; 79 tasks, 40 executed,
39 up-to-date:

```text
/usr/bin/env -i HOME=/Users/piotr USER=piotr LOGNAME=piotr TMPDIR=/var/folders/sr/1zpz2mjs2jg6vs80zcffx3h80000gn/T LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home/bin:/usr/bin:/bin:/usr/sbin:/sbin ./gradlew --no-daemon publish -PblueLanguageRepository=/Users/piotr/data/blue-staged-repository/cyclic-topology-rc3 -PbexSdkStagingRepository=/Users/piotr/data/blue-staged-repository/cyclic-topology-rc3 -PbexLocalStageVersion=1.1.0-rc.4
```

Coordination Java 17 staged SDK freeze — PASS in 21m26s after a separate 3s
clean; 46 tasks executed, none up-to-date. It ran 378 unit + 91 integration + 7
consumer + 14 scenario = 490 tests, with zero failures, errors, or skips:

```text
env PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home ./gradlew --no-daemon --max-workers=1 sdkFreezeArtifactCheck -PblueDependencyMode=staged-artifact -PblueStagingRepository=/Users/piotr/data/blue-staged-repository/cyclic-topology-rc3 -PblueSpecRoot=/Users/piotr/data/blue-spec-release-5dc8096/latest -PtestJavaVersion=17 --no-parallel --no-build-cache --console=plain
```

Coordination Java 21 test-toolchain staged release check — PASS in 20m06s. The
Gradle launcher used Java 17 and `-PtestJavaVersion=21` selected the Java 21
toolchain for tests and the source archive. All 35 tasks executed, none were
up-to-date. It independently reran the same 490 tests with zero failures, errors,
or skips:

```text
env PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home ./gradlew --no-daemon --max-workers=1 releaseCheck --rerun-tasks -PblueDependencyMode=staged-artifact -PblueStagingRepository=/Users/piotr/data/blue-staged-repository/cyclic-topology-rc3 -PblueSpecRoot=/Users/piotr/data/blue-spec-release-5dc8096/latest -PtestJavaVersion=21 --no-parallel --no-build-cache --console=plain
```

The extracted consumer reports independently bind Java runtime 17 and 21 to the
same Coordination JAR SHA-256
`f86de40a65a4cf32181583196049da6d012aed53c4ed4168f8deff5da93aa7b3`
and the exact staged dependency graph.

## Performance evidence classification

No long performance campaign was rerun. The historical 20-warmup/50-sample
campaign remains retained evidence: 420 iterations and 490 operations in
1h13m24s. Its semantic and gas equality checks pass, but its broad-state traversal
gate fails and raw-BEX cold/warm equality is unobservable at the public boundary.
Those facts prohibit a stable latency or zero-global-traversal claim; they do not
negate final semantic implementation conformance.

A fresh staged-artifact 0-warmup/1-sample diagnostic smoke ran for 1m06s and
intentionally exited 1 after writing complete evidence. It is non-authoritative
because the 0/1 override cannot establish cold/warm or latency gates. It confirms
`+1000` semantic equality, gas equality, and observable-projection equality, and
reproduces the known broad-state traversal and raw-BEX observability diagnostics.
The copied evidence retains its original linked names: `cyclic-performance.json`
(SHA-256
`8793ec21b7af1bfd807895c260f89700a994e9373830e3e3885632b0e57cf5a6`) and
`cyclic-performance.md` (SHA-256
`154c0f617072a65a03a149b1bdfbc9977562e109a99dfed8de0340e7b9e63a81`).

Erratum: the raw JSON's campaign-local `implementation-conformance-claim`
detail says that the staged/published exact-package lane is disabled by policy.
This smoke actually ran the staged-artifact rc3 lane shown in its command. That
stale campaign-local detail does not describe this final staged lane and does not
itself promote implementation conformance.

## Deterministic checksum scope

`changed-files.sha256` is a self-contained 474-entry SHA-256 manifest. Source
entries hash committed bytes with `git show <head>:<path>`; receipt, raw evidence,
and staged-deliverable entries hash final bytes from disk. Entries are sorted
bytewise by `namespace:path` under `LC_ALL=C`, and the manifest excludes itself.

| Namespace and scope | Root or committed range | Entries |
| --- | --- | ---: |
| `language` committed source | `d4a0379053e1a716395349c40fa403ee993796ff..e0dfc897ea7d158895325fae2bf84e103b8c1989` | 6 |
| `bex` committed source | `821fe877fef5b04a729b7422cdda05a7ace55a1f..23e9e62feb36bf14a579912bcfa80da84f5ee85f` | 14 |
| `coordination` committed source | `f245270c87cbcec80ed81b416c82513a64367ffc..fb3aca034953035a46038e76f028cf882267a98d` | 96 |
| `repository-staging` override source | `2fcf29bf060ed114c971194adb6f8b747899aee2..d305821bd813e77d46b7e559f03c0c6c902353f2` | 1 |
| `coordination` final receipt/evidence | four fixed files under `/Users/piotr/data/blue-contract-java/stabilization/cyclic-topology-rc3-final` | 4 |
| `rc3-evidence` raw evidence | all regular files under `/Users/piotr/data/blue-cyclic-topology-rc3-evidence` | 292 |
| `staged-artifact` deliverables | regular `*.jar`, `*.pom`, and `*.module` files under `/Users/piotr/data/blue-staged-repository/cyclic-topology-rc3` | 61 |
| **Total** |  | **474** |

The `coordination` namespace therefore has 100 entries: 96 committed source
paths plus the four final receipt/evidence files. The one `repository-staging`
entry binds the isolated SDK staging override; it does not replace the primary
Repository source lock at `2fcf29bf060ed114c971194adb6f8b747899aee2`.

The Coordination source range includes the committed prior topology and SDK
receipt manifests, so their earlier scopes remain bound transitively. This final
manifest does not treat those prior manifest contents as implicit direct entries;
instead it directly hashes all 292 rc3 raw-evidence files and all 61 staged
deliverables. The prior topology receipt is additionally anchored in
`final-receipt.json` as Markdown SHA-256
`4daac795b66b94232b220847992131ce793a3c152121b7efaa30051456f5f1f4` and JSON
SHA-256 `2fb6454786b2c012cefd6440df93d2135bd34b33d937226964a312cae0697242`.

## Explicit limitations

- Local isolated stage only; no public repository promotion is claimed.
- Single JVM and in-memory state only.
- No fresh-process durable recovery or production persistence.
- No external provider-completeness adapter.
- No Mandate resolver.
- Sequential drain; no parallel execution claim.
- Root-scope closure profile with bounded cyclic components.
- No tenant isolation, durable outbox recovery, operational backpressure, or
  production observability profile.
- No zero-broad-state-traversal claim and no stable latency SLA.

Within those limits, this is a clean external-pilot RC and the requested SDK,
staged-artifact, cyclic-topology, managed-draft, and implementation-conformance
freeze is complete.
