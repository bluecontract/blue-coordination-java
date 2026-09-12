# Terminal-peer full-gate preparation

This qualification-only successor starts from `a26c713fad60f530bc70724f7ed0e705cd1eacc6`. The frozen export and focused-test worktrees remain untouched. No production source, release binding, semantic fixture, topology artifact, execution limit, or test assertion is changed here. No Gradle build or test execution is claimed by this preparation commit.

## Exact production-shape ledger

The maintained shape limits were last updated by `ef3965bd87b39f3dcb047aaaf0e6a5db3a285137`. Both its actual inventory and its limits were 251 production Java sources, 73,475 source lines, and 87 public API/SDK source types. The accepted runtime changes accumulated afterward; the unchanged limits would therefore reject the current source before qualification.

Compared with that exact anchor, `a26c713` adds 835 lines and removes 104: a net 731 lines. Its actual inventory is 254 production sources, 74,206 lines, and the same 87 public source types. The shape limits now equal those measured values with no spare allowance. The retained-managed-epoch per-source limit remains 1,166; the largest inventoried source is `ManagedEpochApplicationExecutor.java` at 1,025 lines.

All three new production sources are package-private internals:

| Source | Current lines | Introduction | Bounded responsibility |
| --- | ---: | --- | --- |
| `ManagedRepresentationVerificationMemo.java` | 98 | `ab7757d0c9f0a33446179c7996122e69df40a88c` | Reuse pure transition proofs only for the owning store's exact durable publications; current authority checks remain outside the memo. |
| `RootedJoinScheduling.java` | 118 | `cddb5b4ed3f978a30c050ca9f572537c54b153aa` | Select the actual same-cause receiving-root prefix and joint terminal through the established owner and current-head checks. |
| `RootedTerminalPeerAcquisition.java` | 106 | `bbd06a0b5ef1f2c3db021f02406b892c2676ce86` | Authenticate same-cause peer prefixes before fresh terminal witness selection; preserve calculating owners, fixed source anchors, and unsupported-inventory blocking. |

The new sources total 322 lines. The other 409 net lines belong to these 17 existing sources, all relative to `ef3965b`:

| Existing source | Net lines |
| --- | ---: |
| `ContractsClosureAdapter.java` | +109 |
| `ContractsClosureProfile.java` | 0 |
| `ContractsRootFeederCoordinator.java` | +16 |
| `DefaultCoordinationEngine.java` | +9 |
| `InMemoryDocumentStore.java` | +34 |
| `ManagedEpochApplicationExecutor.java` | +10 |
| `ManagedEpochInvocationCapturer.java` | +49 |
| `ManagedEpochSourceEvidenceVerifier.java` | -12 |
| `ManagedOccurrenceResolver.java` | +13 |
| `ManagedRepresentationHistory.java` | -1 |
| `RootedAttachmentCapture.java` | -27 |
| `RootedCheckpointDriver.java` | +39 |
| `RootedJoinEligibility.java` | +86 |
| `RootedLocalHistory.java` | +37 |
| `RootedSourceDiscoveryCoordinator.java` | +2 |
| `RootedTerminalEvidence.java` | +30 |
| `SdkDrainResultMapper.java` | +15 |

The abandoned speculative `RootedJoinPeerPrefixes` helper is absent from both the anchor and this candidate; it is not a fourth addition. No source under `blue.coordination.api` changed relative to the shape anchor. The only SDK source change in that comparison is the existing package-private `SdkDrainResultMapper`. This ledger documents already reviewed changes; it does not newly approve runtime semantics or alter any protocol, closure, history, gas, or publication limit.

## Test-phase formatting

Eleven existing tests lacked the exact meaningful Given/When/Then phases required by `verifyTestArchitecture`. Four full-bodied methods receive comments only. The four automatic-join and two prefix-interleaving wrappers now construct their original fixture in Given, execute the same helper in When, and immediately run the helper's existing final verification block in Then before the fixture closes. The final block is returned as a `Runnable`; it contains the original assertions, including the original publication/restart checks where present. The dormant-reconnect wrapper binds its unchanged `ORIGINAL_NAMESPACE` constant to a local in Given and passes that same value to its existing call in When. No processor call, assertion, schedule, fixture value, gas policy, or failure expectation is removed, added, or reordered. This is not a checker exemption and adds no dummy success assertion.

The affected owners are `ManagedEpochIndirectComponentRebindTest`, `RootedAutomaticJoinSchedulingTest`, `RootedDiamondAcquisitionDiagnosticTest`, `RootedDiamondOriginalDriverDiagnosticTest`, `RootedJoinPrefixInterleavingTest`, `RootedJoinSccEntrypointTest`, and `RootedDormantPeerReconnectTest`. `RootedDiamondPeerSchedulingTest` remains byte-identical to `a26c713`; the dormant owner has only the equivalent namespace binding described above. The active `a26c713` focused run is not edited or relabeled by this successor.

## Parent-owned gate order

All steps must preserve the exact Language, BEX, and Repository manifest/version/source arguments from the verified `development-terminal-peer.PuGzPs` bundle. Only the Coordination development version, source commit, and source epoch follow the final clean qualification commit. The prior export's Coordination `0f533` identity must not be relabeled as that successor.

1. Run the inexpensive maintained owners first: `validateProductionShape`, `verifyTestArchitecture`, `verifyCoordinationConformanceCoverage`, `verifyPublicApiBoundary`, and `verifySdkPublicApiBoundary`. The latter checks inspect compiled signatures; no API baseline capture or reference replacement is needed.
2. Before the final source freeze, regenerate only the maintained topology artifact through `BLUE_CYCLIC_TOPOLOGY_IDENTITY_ARTIFACT_MODE=WRITE ./gradlew test --tests blue.coordination.internal.CyclicTopologyIdentityEvidenceTest` with the exact development arguments. This standalone exporter executes its original contributor campaign; it does not select the slow fourteen-input SDK ring. Review every changed JSON/Markdown leaf, retain historical profile labels and all scenario/gas checks, and commit only the justified generated output. This preparation commit intentionally does not generate or change that artifact.
3. Unset write mode. On the final clean source, run `clean releaseCheck dependencyPreflight writeDevelopmentResolvedDependencies` with `-PtestJavaVersion=17`, then archive all reports before the Java 21 clean run. Repeat `clean releaseCheck dependencyPreflight` with `-PtestJavaVersion=21`. Keep `--no-daemon --no-build-cache`, the selected fork count, and exact dependency pins. The resolved-development report is a JDK-17 task. These remain unsigned DEVELOPMENT qualification gates, not publishing or release authority.

`releaseCheck` includes the complete `test`, `integrationTest`, `consumerTest`, and `scenarioTest` suites, the source/API/artifact/documentation/conformance gates, exact test-execution-scope checks, and the maintained extracted-source smoke. Each root suite task runs once in that graph. The original fourteen-input SDK ring is already in `test`; a separate adjacent campaign would repeat it unnecessarily. The extracted-source smoke intentionally runs its existing seven filtered owners in a separate checkout; that list does not include the fourteen-input ring.

A complete `test` run records the 20 original topology contributors while they execute. `verifyCyclicTopologyIdentityEvidence` then combines their fragments and compares all 32 scenarios and repeat counts without rerunning those contributors. Its comparison is a finalizer and must not be excluded. A filtered run cannot satisfy full execution scope. A preceding full unfiltered test may be reused by `releaseCheck` only when all Gradle inputs and outputs still match; `clean`, Java-version changes, changed source/dependency bytes or fork settings, and `--rerun-tasks` prevent that assumption.

## Executed preparation and reviewed artifact refresh

The parent-owned five-task preflight on clean `9233c252` passes in 29.881 seconds:
production shape, test architecture, conformance coverage, public API and SDK API.
Archive `legal-detached-retarget-evidence.fKnxrU/coordination-qualification-preflight-01-complete.tar.gz`,
SHA-256 `56662572e5803a69ad754ac7789bc4615eff4258c933a662f912ad02ed17bd91`.

The maintained standalone topology exporter then passes in 93.576 seconds on
the same source and exact Language `05bb` bundle. Its single JUnit method runs
the 20 maintained contributors and records all 32 required scenarios. The
full-suite finalizer is deliberately not claimed: it is skipped for this
filtered generation and must pass in the complete gate.

Independent before/after review compares all 33,797 scalar leaves, object keys
and array positions. Only three active input pins and the seven P6 scenario
identity chains change; the other 25 scenarios remain byte-for-value equal.
All business results, states, events, order, counts, statuses and numeric gas
values remain identical. All 60 old/new changed work records independently
rehash from their invocation and unchanged work operands. Admission publication
and gas-trace identities depend on that invocation/work chain. The artifact
does not expose individual raw charge rows, so this is not an independent
reconstruction of the entire per-counter ledger. Markdown's 32 JSON blocks
match the corresponding scenario objects; other prose changes only the three
input pins. Historical profile labels and limitations remain unchanged.

Complete before/after evidence:
`legal-detached-retarget-evidence.fKnxrU/coordination-topology-generation-01-complete.tar.gz`,
SHA-256 `391b9996f10eb80ac87af1a55bbf7deb6aabea9dfc5d8e9dd6376c5d926f05a3`.
Independent review: `coordination-topology-generation-01-review.md`, SHA-256
`a10ea00a35ed253b2a1dfc97fcd6e90b261120e49a360c4387689865d8cd1bc0`.
These two justified generated files and documentation are committed together;
no production, test or API bytes change in this artifact-refresh successor.
Final frozen-source release gates and full MyOS acceptance are still pending.
