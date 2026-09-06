# Phase1/2 implementation readiness

> Final integration-readiness record, 2026-09-06. **Phase1/2 ready for Phase3 in the documented scope.**

Runtime workspaces and starting branches are recorded in
[the execution plan](../23-first-implementation-execution-plan.md). Changes were local and uncommitted
during verification; the subsequent [source commit map](source-commits.md) identifies their Git snapshots.
This record distinguishes executed library behavior from independent host-storage
tests. Green focused tests are not a claim that Phase3 has been implemented.

The known implementation/regression failures were repaired and their affected checks passed.
The final real-library/PostgreSQL handshake also passed. This is readiness to begin the general
application integration, not a production release, throughput guarantee or formal all-catalog
scenario acceptance. Phase3 remains planned, not implemented.

## Capability-to-test map

Test classes refer to the isolated runtime worktrees, not the old chronology experiment.

| Capability | Executable witnesses | Current evidence |
|---|---|---|
| Independent source/observer operations; retained observation order | `DefaultClosureProcessorTest`, `RetainedOriginIdentityCodecTest`, `SameOriginReceiptCodecTest` | Actual fresh/retained execution, operation identities, gas and cold receipts passed in the final affected regression. |
| Genuine feedback, joined budgets and atomic rollback | `SameOriginAttemptCoordinatorTest`, `GasMeterAdmittedObserverTest`, actual cycle/dynamic join cases in `DefaultClosureProcessorTest`, `SameOriginJoinedReceiptCodecTest` | Actual finite/looping and rejected-join controls passed, including the final affected regression. Scoped sparse failure preserves unowned bodyless authority. |
| Canonical initialization independent of physical first creation | `SourceInitializationTest`, `CanonicalInitializationIdentityTest` | Canonical cause validation and actual authored cyclic initialization through either requested member passed. |
| Initialization at the actual creator site | `SourceInitializationAttachmentTest`, `SourceNestedInitializationRuntimeTest`, queued-source controls in `DefaultClosureProcessorTest` | Actual create/read, cold reuse, rollback, unused offer, old alias, two creation sites, retirement, downstream cold replay, nested borrowed initialization and queued mixed initialization/external contexts passed, including the final affected regression. |
| Existing occurrence target under equal-content lineage collisions | `ProcessEmbeddedSurfaceReconcilerTest`, `SdkManagedDraftAcceptanceTest` | Two new owning tests first reproduced the wrong target. After the precedence repair, reconciler **10/10** and full SDK draft **10/10** passed, including both automatic-expansion variants without feeder reselection. No acceptance assertion was weakened. |
| Exact intermediate source topology | `SourceObservationRecorderTest`, `SourcePatchTopologyCodecTest`, nested initialization runtime tests | Actual nested runtime, 14 recorder tests and three patch-topology codec tests passed, including final regression. A patch retains only its own reconciled outgoing rows before component-reference remapping; final topology is not substituted for intermediate topology. |
| Historical import as separate consumer operations | `CanonicalSourceHistoryTest`, `ManagedImportLaneTest`, `ManagedProgressReceiptCodecTest` | Real source/consumer failure, one-operation disposition, subsequent progress and cold cursor checks passed in focused packs. |
| Explicit frontier attachment | `SourceFrontierSelectionTest`, `SourceFrontierAttachmentRuntimeTest`, `FrontierCreatorIntegrationTest` | Actual creator preview vs installed-view controls, F=C empty suffix, failed-terminal frontier at the prior successful view, cold acceptance and exact checkpoint-domain acquisition through subsequent consumer epochs passed. A real K100-produced operation with E10 provenance rejects F50. |
| Sparse managed bodies and exact lazy reads | `ReusableComponentAuthorityTest`, `ReusableComponentAuthorityCodecTest`, `ManagedDocumentOverlayRefreshTest`, `PendingCyclicReadTest`, sparse actual cases in `DefaultClosureProcessorTest` | Actual A-only residency, staged needs for B then C, exact hydration, cyclic proof headers, pending historical cyclic reads and warm/cold identities passed. |
| Cache-independent program inventory | `SameOriginResultReadInventoryTest`, `CanonicalObservedSourceReceiptTest`, `SameOriginMixedObservationReceiptTest`, `CoordinationCoreTest` | Active/inactive source-head and cache variations, cold replay, named exact needs and four scoped sparse inventory controls passed. Actual fresh/cached success and failure receipts match in full. Unused successful/failed producer inventory does not change metadata progress. An actual joined failure preserves distinct A/S0 and C/S1 pins through cold receipt restoration. |
| Directed Timeline membership and cold routing metadata | `DirectedTimelineMembershipTest`, `ReceiptReadCutTest`, `CoordinationCoreTest` | Complete directed cut, inherited headers, inactive candidate exclusion and an unaligned historical-header need passed. A cached source post-state cannot erase its removed child's Timeline when selecting the source's earlier input; the exact predecessor cut still requires that Timeline's completeness. |
| Bounded evidence transport | `FrozenNodeEvidenceCodecTest`, `SourceObservationProgramDagCodecTest`, receipt codecs | Fragment hashing, budgets, borrowed-DAG deduplication and cold reconstruction tested. The additional mutable-byte isolation and operational depth guards passed; full fragment-on-demand execution is not claimed. |
| Fresh processing termination and retained terminal observations | `FreshTerminationObservationTest`, `TerminalSourceSelectionTest`, `SdkManagedEpochSourceTerminationTest` | Actual lifecycle/cutoff/rollback/codec **8/8**, SDK **2/2**, and Core later-input selection **1/1** passed, including the final affected regression. Real termination publishes a terminal epoch; cold replay preserves marker timing and skipped-work identities without fake execution steps. Later input has no terminal target delivery or new epoch. |
| PostgreSQL authority and target-shaped scheduling | MyOS Simple `durableHostTest` | Latest unchanged host candidate: **66/66**, no skips, 44 s. Includes restart, fences, uncertain commits, waits, temporal discovery, prefixes and indexed scale probes. |
| Thin actual-library/host handshake | MyOS Simple `durable-library-smoke` | Final candidate **7/7**, no failures/errors/skips, 22 s, using bounded JDBC pools. Actual classpath, hashes, compiled classes and reports retained in `durable-library-smoke/build/readiness-evidence/final-seven-pooled-20260906-e7zJ5O/`. This is not the general graph adapter. |
| M1 commits while selected M2 execution data is absent | MyOS Simple `RealManagedImportFixture`, `RealManagedImportRestartProbe` | Passed within the final seven-case run (8.465 s). M1 committed and published while selected M2 execution data was missing; corrupt data was rejected; supplying exact data let a new JVM finish M2 once without replaying M1 or advancing the unrelated external cursor. The separate missing-selection control stayed waiting. |

Focused counts overlap. Do not add them together or call them a complete regression. Assertions
must distinguish actual interpreter results from synthetic bookkeeping or storage fixtures.

The E2b test includes setup, repeated cache-disabled exact decoding/PostgreSQL reads and a fresh
JVM. It is a durability/correctness witness, not an accepted two-reaction latency measurement.
The integrated adapter must measure normal pooled/cached processing separately from cold startup,
reconstruction and fault recovery; no seconds-scale performance pass follows from this test.

The first combined run failed operationally while opening JDBC connections (SSL negotiation EOF),
not at a semantic equality assertion. It is retained separately under
`durable-library-smoke/build/readiness-evidence/full-seven-20260906-feDHcQ/`. The server did not
restart or run out of memory. The test/probe harness was using an unpooled connection per read;
it now uses the existing host's bounded Hikari approach (maximum eight connections). The passing
run explicitly verifies backend reuse in the parent and fresh JVMs, with no relaxed business
assertions or added semantic retries. This is a harness repair, not a new processing rule.

## Source evidence and memory scope

Managed document bodies are not required to be fully resident: exact headers and reusable component
authority can remain while bodies and cyclic proof payloads are absent. An actual path read returns
the next missing exact body, not an ambient/latest document. Failed work must preserve that property.

Source-program transport currently loads one selected program and its authenticated borrowed DAG
within explicit physical budgets. Its node/step fragments are content-addressed and shared, but this
is **not** yet a claim of a streaming interpreter that fetches every action fragment only on demand.
It must not load the whole source history merely to process one selected operation. Phase3 must
measure retained-program size, decode/copy amplification, working-set peaks and fanout reuse
separately from the unavoidable cost of executing N consumer reactions. Physical capacity limits
produce operational holds/needs, never a different semantic gas outcome.

Transport limits do not by themselves bound peak memory while recording a program: the current
recorder retains step input/result evidence and freezes intermediate states. Phase3 must measure
that capture cost as well as cold decoding. Passing sparse-body tests is not evidence of bounded
memory for arbitrarily large observation programs.

## Final verification and reviewed repairs

The final affected Language run passed in **7 min 34 s**: full Contracts **562/562**, root
API/package/architecture checks **43/43**, and full conformance **170/170**, all with zero failures,
errors or skips. It includes the fresh termination/cutoff, initialization, historical view, sparse
state and equal-content target-precedence repairs. Reports and generated API/ownership inventories
are retained in the runtime Language worktree under
`build/readiness-evidence/language-final-affected-20260906-lHuUZg/`. This is the complete Contracts
and conformance tasks plus the affected root checks, not a claim that all root tests were rerun.
The final Coordination regressions and PostgreSQL handshake are recorded below.

The conformance total includes 18 limit-boundary microchecks: they independently measure generated
limits and verify `ACCEPT`/`REJECT`, but execute no closure work. Their inherited display label is
`UNSUPPORTED limit-micro`. Passing them is not an end-to-end witness of runtime limit enforcement;
it also does not establish that the runtime lacks those capabilities. The full 93-row dynamic
corpus and its completion check passed. Formal scenario acceptance remains a separate obligation.

The first broad Language regression ran through all requested modules and exposed
failures: root tests **2,441 run / 20 failed**, Contracts **546 / 18 failed**, and conformance
**165 / 52 failed**. These are results of that candidate, not counts of independent defects:
several suites report the same underlying discrepancy. Model, core and examples tests passed.
Those findings received focused fixes and controls, and the final affected regression verified
their combined effect:

- Preserve legacy no-op epoch behavior and the frozen selected reference of a retired occurrence.
- Make inline and reference representations use the same logical placement timing and gas.
- Distinguish an intentional routing/metering change from an accidental regression before changing
  any golden expectations; preserve cold/warm and representation parity.
- Use explicit deterministic failure types in deliberate failure fixtures, without classifying
  arbitrary runtime exceptions as semantic failures.
- Refresh exact public-API/module inventories and satisfy existing architecture/style guards via
  cohesive internal extractions, without raising the limits.

The deliberate fixture changes have now been independently reviewed and applied to 36 cases.
The runtime Language worktree records their rationale in `tools/local-poc-fixture-deltas.md` and
binds the exact approved reports in `tools/local-poc-fixture-delta-approval.json`. A separate check
verified unchanged fixture inputs, equality of each updated owning field with its approved actual
result, preservation of custom assertions, and every fixture-inventory hash. The changes include
logical routing/placement metering and a frozen dormant occurrence; an accidental occurrence-hash
ordering regression was fixed in code before approval. This is not permission to accept arbitrary
new golden outputs or a substitute for the full regression.

Seven additional cases were captured separately after package initialization exposed the remainder
of the corpus. Their reviewed changes preserve actual inactive occurrence selections and reconcile
exact route/placement gas plus receipt attribution. The work/document-step/finalization traces and
non-gas receipt fields are unchanged. Their separate approval is
`tools/local-poc-retirement-fixture-delta-approval.json`; installation is complete. An independent
post-installation check passed for all **43 approved fixtures**, unchanged inputs/custom assertions,
all **287 inventory files** and the three canonical manifests. The local fixture identity is now
`sha256:8ab8ffc4e2db1ca2ac27a91f5bc51cdbc93c1f923d0e4a04afefa6f75bf9f296`, and the local release-manifest
identity is `sha256:842c4714529f048c3a4bdfc0e4e74a09d80941be01e524d95f547d6401013bdd`.
Registry/type identity is unchanged; this is not a published release. Final executable conformance
and API-binding checks passed in the run above. No runtime change is justified by those
expected-value deltas.

The broad Coordination regression is also checking existing in-memory compatibility tests. Some
old loop tests assume that the rejected charge must belong to a workflow rather than finalization.
Their replacements must validate the complete rejected-owner variant, actual loop execution, exact
fresh-run parity and full rollback, not merely permit a null work witness. The bounded initialization
API must report its explicit capability gap rather than inventing a deterministic runtime failure.

The same distinction exposed an inherited termination boundary: fresh termination outside ADMISSION
was unsupported by the shared closure runtime. That gap has now been implemented through the owning
lifecycle machinery, with actual execution, exact marker timing, cutoff, rollback and cold-source
replay tests. The obsolete SDK negative is now a positive publication test. Diagnostic skipped-work
coverage remains separate from executable steps and cannot authorize a skip by itself.

The broad Coordination run completed in **29 min 26 s: 667 tests, 14 failures, zero errors/skips**.
The failures comprise four gas-cutoff/capability assertions, their aggregate artifact-test cascade,
the legacy termination-capability negative, seven SDK exact-gas/cutoff assertions and one automatic
extra-occurrence acceptance case requiring diagnosis. `integrationTest`, `consumerTest` and
`scenarioTest` did not run after `test` failed. The runtime worktree retains the complete JUnit/HTML
report, command, actual worker classpath, tested JAR hashes and test-class/resource snapshots under
`build/readiness-evidence/coordination-broad-20260906-UI596Y/`. Source edits made while that JVM ran
are not claimed as tested by it. The corrected reruns and remaining suites are recorded below.

The final affected Coordination unit selection ran **80 tests**: all **60 external API tests**,
**14 SDK acceptance tests**, **two termination tests**, the Ordering loop and two collector
validator controls passed. Its only failure was the deliberately unchanged cyclic golden-file
comparison. Independent review has now approved the exact 32-scenario candidate and every gas
delta, including full execution-prefix accounting for both loops. The raw gas sidecar is bound to
the candidate's SHA and retained with the reports in
`build/readiness-evidence/coordination-final-affected-20260906-pECpT9/`; approval rationale is in
`docs/development/local-poc-cyclic-identity-deltas.md`. After installing only the SHA-checked approved
JSON/Markdown, the normal collector passed **3/3** in **3 min 6 s**, including all 32 scenarios,
required repeats, exact stored-file assertions and the two validator controls. Capture/write modes
were disabled. That follow-up is retained in
`build/readiness-evidence/cyclic-final-green-20260906-DCzRDC/`. The first batch's expected oracle
failure is not relabeled as a passing run. The same batch's full **integration suite passed 91/91**,
**built-JAR consumer suite passed 9/9**, and **scenario suite passed 14/14**, with zero
failures/errors/skips. The combined command took **17 min 4 s**. The independent full SDK draft
rerun passed **10/10** after its owning repair.

The final PostgreSQL handshake then passed **7/7**, zero failures/errors/skips, **22 s**, against
these stable library artifacts. Its final archive is listed in the capability map. The unchanged
independent PostgreSQL foundation pack (**66/66**) and earlier unchanged BEX regression (**40 tests**)
were reused rather than rerun for documentation or golden-file edits. Shared composite builds were
serialized. No known implementation blocker remains for beginning the documented Phase3 plan.

No production release, API freeze, remote push, legacy-data migration or production throughput
guarantee follows from this gate. Further Coordination changes based on Phase3 results are expected.

## Phase3 adapter boundary

The MyOS Simple storage foundation has been reviewed against the current library result types.
The isolated `durable-library-smoke` build contains an actual adapter for single-lineage canonical
initialization. Its tests have additional bounded bridges for history and failure behavior; they
are not a general-graph adapter installed in the main application. The existing demonstrator
remains on its old processing path. The new durable bootstrap fails explicitly when no adapter
is configured, rather than falling back to authoritative RAM. Phase3 must connect the existing
primitives as follows:

- Map a dependency-ordered `PreparedOperations` result to separately fenced operation plans, not
  one atomic commit for all independent source/consumer operations. One genuinely owned group
  remains one atomic plan, potentially containing several lineage writes.
- Persist metadata progress and import-lane progress with their distinct cursors. Importing a
  source operation must not advance an unrelated ordinary Timeline cursor.
- Load named exact bodies, checkpoint domains and historical read-cut evidence from committed
  receipt references; register waits without substituting a current document head.
- Derive attachment/history registration from accepted initialization or frontier evidence and
  preserve logical producing order separately from physical scheduling sequence numbers.
- Retain source failure capabilities and consumer/source-specific observation gaps, then wire the
  general adapter and Timeline-provider proof integration into the host service.

These are the intended Phase3 integration work, not missing PostgreSQL foundation implementations.
They still require end-to-end correctness, example and performance tests before acceptance.

## Local verification commands

Run builds sharing the isolated worktrees sequentially. Do not publish development artifacts over
the existing RC coordinates in Maven local.

From the isolated `blue-coordination-java` worktree, a targeted library-integration test uses:

```sh
./gradlew test --include-build ../blue-language-java --include-build ../blue-bex-java --tests '*FrontierCreatorIntegrationTest' --max-workers=2 --console=plain
```

The final Coordination runtime regression command is:

```sh
./gradlew test integrationTest consumerTest scenarioTest --include-build ../blue-language-java --include-build ../blue-bex-java --max-workers=2 --console=plain
```

From MyOS Simple, the independent PostgreSQL host suite is `./gradlew durableHostTest`. The local
database/provider configuration, safe per-run test schemas and startup entry point are described
in `myos-simple/docs/durable-host-phase2.md`. The thin actual-library handshake is:

```sh
./gradlew compileJava processResources --max-workers=2 --console=plain
./gradlew -p durable-library-smoke test --max-workers=2 --console=plain
```

These are reproducible full/targeted entry points. The actual acceptance used an initial broad run,
reviewed focused repairs, final impacted regressions and the joint handshake as recorded above.
It did not repeatedly run the unchanged expensive full compatibility corpus after every edit.
