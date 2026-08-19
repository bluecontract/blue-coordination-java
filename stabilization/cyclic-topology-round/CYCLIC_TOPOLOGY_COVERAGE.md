# Cyclic topology coverage audit

Date: 2026-08-19  
Coordination branch: `codex/cyclic-topology-coordination`  
Frozen Contracts release identity: `sha256:7e6c3717bc28d21ebadec9f81725913e944bb3b9b70094531f19f10510a10e50`  
Frozen fixture package identity: `sha256:071cecb68e1c4dcec2dbb0895de928629281d2b0a18f3e8a83a41a720e621bfa`  
Frozen Blue Language specification SHA-256: `01b038b64e3f0a9a11f3f70d544a63ff78a01d5169f1a03f8b8629cf73645a7d`  
Closure fixture inventory: 67 files  
Normative fixture changes in this round: none

## Conclusion

No new normative Contracts fixture is required. Every successful scenario in this round is a larger-cardinality or composed public-host proof of portable laws already present in the frozen Contracts corpus. The two requested positive cases that the current affected-closure profile cannot express are recorded as blockers instead of being converted into invented fixtures or API seams:

- Phase 6: first reciprocal-cycle formation and collection-member activation during initialization require the conformance runtime's initialization-patch seam. The fixed public Coordination composition has no equivalent seam.
- Phase 7: Contracts 1.0 affected-closure work and direct seeds are deliberately Root-scoped. Positive nested-scope closure work would require a future profile/specification change.

Consequently every row in the audit matrix has `normative fixture required = no`. The existing release, fixture package, 67-file closure inventory, and all fixture hashes remain unchanged.

This is a semantic coverage audit, not a performance report. It makes no final latency, locality-instrumentation, Java-version, staged-artifact, or implementation-conformance claim.

## Direction legend

The distinction below is essential when reading every graph:

```text
S --contains[/path]--> T
    authored Process Embedded edge: document S contains managed document T

T ==event==> S
    event delivery direction: an event emitted by embedded child T is offered
    to authored containing-document reactions in S

entry --> A
    direct external seed selected by the frozen public route snapshot
```

Strongly connected components are calculated from the authored containment graph. Business-event flow across a containment edge runs in the reverse direction. No test supplies a caller-selected internal target, reverse-container binding, ambient parent, or second graph.

## Exact graph catalogue

### Phase 2: three-member ring

Literal requested containment graph:

```text
A --contains[/b]--> B --contains[/c]--> C --contains[/a]--> A

containment SCC: {A, B, C}
direct seed: entry --> A
actual cross-document event flow: A ==> C ==> B ==> A
exact work order: [A, C, B, A]
```

The literal graph is not relabelled. `Process Embedded` is containment, so the public characterization records the actual reverse event flow.

Reverse-containment graph used to realize the requested business reaction:

```text
B --contains[/a]--> A
C --contains[/b]--> B
A --contains[/c]--> C

containment SCC: {A, B, C}
direct seed: entry --> A
business event flow: A ==> B ==> C ==> A
exact work order: [A, B, C, A]
```

Canonical discovery repeats this same graph with document orders `A,B,C`, `C,B,A`, and `B,A,C`, reversed occurrence rows, reversed body-map insertion, and verified materialized references. All compared semantic evidence is exactly equal.

Three direct seeds use the same reverse-containment ring:

```text
entry --> A    closes A ==> B
entry --> B    closes B ==> C
entry --> C    closes C ==> A

direct-seed order: [A, B, C]
exact work order: [A, B, B, C, C, A]
changed documents: [A, B, C]
```

The loop graph is also the same ring:

```text
entry --> A ==> B ==> C ==> A ==> ...

result: GAS_LIMIT_EXCEEDED, complete rollback
rejected counter: internalEventEnqueued
rejected owner: exact next WORK occurrence
```

The test proves identical fresh-engine gas-trace shape, rejected work identity, rejected charge identity, heads, MASTER, and rollback state. It does not assert a hard-coded ordinal; the ordinal is required only to be positive and identical between the two runs.

### Phase 3: collection-backed branching topology

Shared-anchor containment graph:

```text
                         /branches/b1
                    +--------------------> B1 --contains[/child]--> C1
                    |                                           |
                    |                                           | /root
                    |                                           v
                    A <------------------------------------------+
                    ^
                    |                                           /root
                    |                                           |
                    +--------------------> B2 --contains[/child]--> C2
                         /branches/b2

A's two outgoing edges are generated from Process Embedded.collectionPaths
at /branches. The graph is one SCC: {A, B1, B2, C1, C2}.
```

It is not two cycles: every branch member reaches the other branch through A. The exact finite work order is:

```text
[A, C1, B1, A, C2, B2, A]
```

The five public events are:

```text
[branch-start-1, branch-ack, branch-start-2, branch-ack, branching-done]
```

The two `branch-ack` event values have equal event BlueIds but different occurrence identities. No value-based deduplication occurs.

Locality shape:

```text
{A, B1, C1, B2, C2}       U0000  U0001 ... U0999
one affected SCC           1,000 unrelated singleton components

entry --> A
semantic work remains inside the five-member SCC
```

The public test proves equal result/gas evidence and zero unrelated managed opens, work dequeues, and member finalizations. Final structural scan claims belong to the performance deliverable, not this audit.

Genuinely disjoint cycles:

```text
A1 --contains[/b]--> B1       A2 --contains[/b]--> B2
 ^                    |        ^                    |
 |------[/a]----------+        |------[/a]----------+

partition: [{A1, B1}, {A2, B2}]
both-target work: [A1, B1, A2, B2]
first-only work:  [A1, B1]
```

When only A1 is directly targeted, A2/B2 retain their exact heads and epoch zero.

### Phase 4: detachment, dissolution, and reactivation

Initial graph and partition:

```text
A --> B1 --> C1 --> A
A --> B2 --> C2 --> A

partition generation 1: [{A, B1, B2, C1, C2}]
```

Partial detach removes only `C1 --contains[/root]--> A`:

```text
A --> B1 --> C1
A --> B2 --> C2 --> A

canonical target-before-source partition:
[{C1}, {B1}, {A, B2, C2}]
graph generation: 2
component generations: A=2, B1=2, B2=2, C1=2, C2=2
direct seeds/work: [C1, C2]
changed documents: [A, B1, B2, C1, C2]
```

Full detach later removes `C2 --contains[/root]--> A`:

```text
A --> B1 --> C1
A --> B2 --> C2

canonical target-before-source partition:
[{C1}, {B1}, {C2}, {B2}, {A}]
graph generation: 3
component generations: A=3, B1=2, B2=3, C1=2, C2=3
work: [C2]
changed documents: [A, B2, C2]
all five identities: ordinary; no MASTER or cyclic proof
```

Gas behavior before and after full detach:

```text
before: entry --> A ==> C1/C2 ==> ... return paths exist
        GAS_LIMIT_EXCEEDED; no transition commits

after:  entry --> A; no C1/C2 return edge exists
        work [A]; success below the shared limit; quiescent
```

Re-add only C1's exact `/root` reference:

```text
A --> B1 --> C1 --> A       A --> B2 --> C2

partition: [{C2}, {B2}, {A, B1, C1}]
graph generation: 4
component generations: A=4, B1=4, B2=3, C1=4, C2=3
reformed probe work: [A, C1]
```

Re-add lineage nuance:

```text
active generation 1:    occurrence O1, binding R1
retired successor g2:   occurrence O2, binding R2, inactive
re-added successor g2:  occurrence O2, binding R3, active
```

The generation-2 successor is created and committed at removal. Re-add activates that exact inactive successor; it does not invent generation 3. O2 is fresh relative to retired O1, R3 is fresh relative to both R1 and R2, and no old work identity is reused.

Frozen edge removal:

```text
initial: A <--> B
one B work freezes two deliveries to A
first A delivery removes A --> B
second already-frozen A delivery still runs once

exact work: [B, A, A]
contracts: [frozenChannel, aRetireFromB, zObserveFromB]
later occurrence work after retirement: [B]
final partition: [{A}, {B}]
```

### Phase 5: component merge and split

Two cycles merge:

```text
before:  A <--> B       C <--> D       plus D --> A
patch:   add A --> C
after:   {A, B, C, D} one four-member SCC

partition: [{A,B}, {C,D}] -> [{A,B,C,D}]
component generation: 1 -> 2
```

One four-member cycle splits into two cycles:

```text
before: one SCC {A,B,C,D}
remove: A --> C and A --> D
after:  A <--> B       C <--> D       with C --> A crossing SCCs

partition: [{A,B,C,D}] -> [{A,B}, {C,D}]
both result component generations: 2
```

One pair splits into ordinary singletons:

```text
before: A <--> B
remove: A --> B
after:  B --> A

partition: [{A,B}] -> [{A}, {B}]
both ordinary component generations: 2
```

Self-cycle dissolution:

```text
before: A --contains[/self]--> A
remove: /self
after:  A ordinary singleton

partition: [{A}] cyclic -> [{A}] acyclic
component generation: 1 -> 2
```

Late failure after split staging:

```text
entry direct seeds: [A, B]
A stages removal and repartition
B's later Handler fails

work reached: [A, B]
result: RUNTIME_FATAL, atomic rollback to original {A,B}
published semantic deltas: none
```

The failure trace proves `patchRemove < componentPartitionChanged < second work`. Document heads, graph/component/occurrence generations, bindings, subscriptions, outbox, checkpoints, and the old cyclic component projection remain exact.

### Phase 6: initialization and dynamic topology

Static three-member admission:

```text
A --> B --> C --> A

ADMIT_CLOSURE only; no Timeline Entry
partition: [{A,B,C}]
work kinds:
[INIT(A), LIFECYCLE(A), INIT(B), LIFECYCLE(B), INIT(C), LIFECYCLE(C)]
one INITIALIZATION_BATCH finalization
one atomic publication after all members
```

Declared and reversed document/occurrence input produce exactly equal output closure identity, component-state identity, final member BlueIds, work targets, and work kinds.

Dynamic reciprocal formation requested during initialization:

```text
initial intended graph: A --> B
initialization would add: B --> A
desired final graph: A <--> B

normative portable proof: C-CLO-08 through its conformance runtime
public Coordination composition: BLOCKED
```

The public composition has no injectable initialization-patch runtime. An event is not an admissible substitute and `ADMIT_CLOSURE` must not fabricate a Timeline Entry. The exact C-CLO-08-shaped public input therefore fails closed with `RUNTIME_FATAL`, publishes nothing, and leaves no route or document state.

Two collection members plus a reciprocal path requested during initialization:

```text
already-active graph: A --> seedB, A --> seedC, B --> A, C --> A
inactive candidates staged by initialization:
  A --contains[/reciprocal]--> B
  A --contains[/members/b]--> B
  A --contains[/members/c]--> C

public result: SUBSCRIPTION_SURFACE_INVALID, atomic rollback
```

The first topology patch reaches tentative finalization, then the fixed public subscription-surface boundary rejects the unsupported activation. Declared/reversed input produces identical failure evidence. This is blocker characterization, not successful dynamic initialization coverage.

Late-member initialization failure:

```text
A init -> lifecycle -> B init -> lifecycle -> C init -> lifecycle failure

result: RUNTIME_FATAL
all initialized markers and member bodies roll back
document count, routes, components, occurrences, receipts, Timeline entries: 0
```

### Phase 7: nested contract scope

Ordinary `PROCESS` positive control:

```text
managed document D
  nested local scope /nested (child id CHILD)

entry --> D:/nested
ordinary outcome order: [CHILD, D]
$document = D's isolated managed document
$scope = /nested
```

Contracts 1.0 affected-closure boundary:

```text
cyclic managed graph: D <--> PEER

nested entry --> D:/nested   route targets: 0; no closure seed
root entry   --> D:/         route targets: 1; closure work at D:/

every closure document step:
executionRootDocumentId = targetDocumentId
scopePath = /
executionMode = ISOLATED_DOCUMENT
ambientContainingDocumentIds = []
```

This is an explicit specification/profile blocker. Contracts 1.0 §2.2.1 and closure HARNESS §5 require Root scope and rejection of non-Root direct deliveries, work occurrences, channel occurrences, and subscriptions. A positive nested cyclic closure test cannot be added without broadening the normative profile.

### Phase 8: benchmark graph reuse (semantic mapping only)

The six requested performance shapes introduce no additional semantic graphs:

```text
P8.1  two-member finite cycle             A <--> B
P8.2  three-member ring                    graph P2.1b
P8.3  five-member branching SCC            graph P3.1
P8.4  two disjoint two-member SCCs         graph P3.3a
P8.5  branching SCC + 1,000 unrelated      graph P3.2
P8.6  detach and full dissolution          graph sequence P4.1 -> P4.2
```

Their fixture mapping is included below, but latency distributions, producer-backed structural counters, machine/JVM evidence, and gate results are deliberately deferred to `cyclic-performance.json` and `cyclic-performance.md`.

### Phase 10: authored-document facade parity

The test-only authored boundary uses one ordinary two-member containment graph:

```text
A --contains[/peer]--> B
A <--contains[/peer]-- B

partition: [{A,B}]
cause: ADMIT_CLOSURE
direct deliveries: []
```

The authored test facade derives exact documents, active occurrence rows, the verified component proof, public Root set, cause, policy, and frozen environment. An independently assembled expert `ClosureInvocationInput` deliberately uses reversed document/body/row insertion. The two paths produce exactly equal invocation, affected-closure, occurrence-binding-set, document, occurrence, component, public-Root, cause, policy, environment, and empty direct-snapshot identities. This is test-only characterization; it does not add a production API or allow callers to supply SCCs or recipients.

## Identity, occurrence, and publication assertions

Exact hashes are derived and verified at runtime, not copied into expectations from the implementation under test. The tests assert these relations:

| Scenario | Exact identity relation asserted |
|---|---|
| Three-member admission/process | All three final member BlueIds have one shared final MASTER; a complete three-body proof independently recalculates the same member set and MASTER. Each changed member advances from epoch 0 to 1. |
| Three-member canonical variants | Admission publication, entry, invocation, closure, component/state, MASTER, proof bodies, member mapping, final BlueIds, work IDs, gas trace, public events, epochs, and gas are byte-for-byte/equality identical. |
| Branching five-member SCC | All five final member IDs share one MASTER and one complete proof. Baseline and reversed-materialized representations have equal final IDs, component/state/proof identity, work, gas, events, and output closure. |
| Partial detach | Old five-member MASTER is absent from all references. B1/C1 have independently verified direct BlueIds; A/B2/C2 share a different verified MASTER. |
| Full detach | Both former MASTERs are absent. Every member has an independently verified ordinary BlueId; every surviving containing reference names the exact current ordinary target ID. |
| Re-add | Reformed `{A,B1,C1}` MASTER differs from both prior cyclic MASTERs. The generation-2 occurrence O2 is reused from its inactive committed successor, while its target-bound revision changes from R2 to R3. |
| Merge/split/dissolve | Each cyclic result has a complete independently checked proof; every acyclic result has a direct BlueId and no MASTER/proof. Outside containing references are rewritten to exact current target IDs. |
| Failed loop, failed split, failed initialization | Output closure equals input closure, no commit companion exists, and all durable heads, graph, occurrence, subscription, checkpoint, outbox, and marker state remain at the input boundary. |

Successful topology changes publish exactly one atomic receipt. Merge/split/dissolve success also asserts one ordered `controlChannel` checkpoint write, a non-empty ordered subscription replacement, exact graph-change ordinals, one occurrence-inventory generation advance, one component-index generation advance, and one epoch advance for every changed resulting document.

## Coverage matrix

“Existing Java” names tests in Blue Language / `blue-contracts-core` / `blue-conformance`. Every C-CLO fixture below is additionally executed by the 67-row `DynamicClosureCorpusConformanceTest` and `FullClosureCorpusConformanceTest`.

| ID | New scenario and outcome | Existing Contracts fixture(s) | Existing Java test(s) | Existing public Coordination coverage | New public Coordination coverage | Normative fixture required? |
|---|---|---|---|---|---|---|
| P2.1a | Literal A→B→C→A containment; actual work `[A,C,B,A]` | C-CLO-02, C-CLO-16, C-CLO-30, C-CLO-34 | `SelfReferenceTest.shouldKeepThreeDocumentCycleStableAcrossPermutationsAndFetchByFinalSuffix`; `Cclo34FullResultConformanceTest` | Two-member finite cycle in `ContractsClosureAdmissionAdapterTest` | `ContractsPublicThreeMemberCycleTest.literalContainmentRingRoutesChildEventsToContainingDocuments` | **No** — cardinality and containment direction compose existing laws. |
| P2.1b | Reverse containment realizes `[A,B,C,A]` | C-CLO-02, C-CLO-16, C-CLO-30, C-CLO-34 | same as P2.1a | Two-member finite and ordinary acyclic chain | `finiteReverseContainmentRingExecutesRequestedBusinessFlow` | **No** — no new portable ordering rule. |
| P2.2 | Six authored/materialization permutations are identical | C-CLO-01 parity, C-CLO-02 parity, C-CLO-24, C-CLO-30 | `SccPartitionerTest.shouldBeInvariantToNodeAndBindingInputOrder`; `ComponentFinalizationKernelTest.shouldBeInvariantToBodyAndBindingInputOrder` | `ContractsPublicOrderingAcceptanceTest.canonicalResultIgnoresEverySupportedConstructionOrder` | `canonicalAdmissionAndDiscoveryIgnoreEveryAuthoredOrderVariant` | **No** — canonicalization/parity are already normative. |
| P2.3 | One entry directly seeds A/B/C; work `[A,B,B,C,C,A]` | C-CLO-05, C-CLO-19 | `ExternalClosureFixtureMatrixTest.shouldAdmitPriorityExternalFixtureShapesWithoutExpectedProjection`; `ClosureDirectSeedPlannerTest.shouldSeparateRawSnapshotOrderFromComponentExecutionOrder` | `sameEntryUsesCanonicalDirectSeedOrderAndClosesCausedWork` | `sameEntryUsesCanonicalDirectSeedsAndClosesEachContinuation` | **No** — three seeds extend the existing stable direct-seed law. |
| P2.4 | Three-member LOOP rejects next `internalEventEnqueued` and rolls back | C-CLO-04, C-CLO-25, C-CLO-34 | `DefaultClosureProcessorTest.rollsBackTentativeMutationAndPublishesExactRejectedWorkCharge`; `DocumentStepBoundaryTest.shouldUseOneProcessorFunctionForAcyclicAndCyclicTargets` | `ContractsPublicLoopAndIsolationTest.sameEventLoopRollbackIsIdenticalAcrossFreshEngineRuns` | `threeMemberLoopRollbackIsIdenticalAcrossFreshEngineRuns` | **No** — same shared-gas law at larger cardinality. |
| P3.1 | `collectionPaths` shared anchor is one five-member SCC; duplicate equal event values remain occurrences | C-EMB-08, C-EMB-13, C-CLO-06, C-CLO-16, C-CLO-27, C-CLO-29, C-CLO-30, C-CLO-34 | ordinary Contracts corpus; `SccPartitionerTest`; `ManagedOccurrenceTargetVerifierTest` | Two-member and dynamic public cycle tests | `ContractsPublicBranchingCollectionCycleTest.sharedAnchorCollectionCycleConvergesOnceInCanonicalOrder` | **No** — collection expansion, SCC transitivity, occurrence multiplicity, and proof laws already exist. |
| P3.2 | Five-member result with 1,000 unrelated singleton documents is semantically identical | C-CLO-18 | `AdmissionClosureFixtureExecutionTest.shouldGenuinelyExecuteReleasedStaticAndBoundedAdmissions` | Disconnected public-Root isolation | `oneThousandUnrelatedDocumentsPerformZeroSemanticWork` | **No** — exact locality fixture already exists. |
| P3.3a | Two disjoint cycles, one entry targets both | C-CLO-05, C-CLO-19, C-CLO-27 | `SccPartitionerTest.shouldReturnMultipleComponentsTargetBeforeSource`; `ClosureDirectSeedPlannerTest` | `ContractsClosureAdapterTest.partitionsOneFrozenRouteSelectionByConnectedCohort` | `disjointCyclesRemainSeparateForBothAndSingleTargetEntries` | **No** — multiple SCC/cohort behavior is already normative. |
| P3.3b | Target only A1; A2/B2 untouched | C-CLO-18, C-CLO-27 | `SccPartitionerTest`; full closure corpus | Disconnected public-Root isolation | same method as P3.3a | **No** — affected-closure locality is already normative. |
| P4.1 | Partial break gives `[{C1},{B1},{A,B2,C2}]` | C-CLO-11, C-CLO-28, C-CLO-33, C-CLO-34 | `ComponentGenerationTransitionTest.shouldAdvanceEveryResultOnSplit`; `ComponentFinalizationKernelTest.shouldRebuildTheCompleteContainingSpineTargetBeforeSource` | Dynamic two-member formation/repartition | `ContractsPublicCycleDetachmentTest.splitDissolveAndReaddChangeRealCausalityAndLineage` | **No** — mixed split is a composition of split, mixed-result, spine, and retirement laws. |
| P4.2 | Full break gives five ordinary components and no MASTER | C-CLO-10, C-CLO-28 | `ComponentFinalizationKernelTest.shouldApplyClo10StyleCycleSplitGenerationTransition` | Existing two-member behavior | same detachment method | **No** — complete dissolution is already normative. |
| P4.3a | Same loop before detach reaches shared gas and rolls back | C-CLO-04, C-CLO-25 | gas rejection tests | Existing public loop rollback | same detachment method | **No** — existing shared-gas law. |
| P4.3b | Same logical start after detach succeeds with work `[A]` | C-CLO-10, C-CLO-12, C-CLO-34 | closure corpus and document-step boundary tests | Ordinary acyclic compatibility | same detachment method | **No** — success follows from retired-edge causality; no new gas semantic. |
| P4.4 | Frozen old target runs exactly once; later occurrence omits it | C-CLO-12 | dynamic/full closure corpus | None exact | `retiredEdgeStillServesItsAlreadyFrozenSecondDelivery` | **No** — direct public proof of the existing exact fixture. |
| P4.5 | Re-add generation-2 inactive successor reforms `{A,B1,C1}` | C-CLO-24, C-CLO-33, C-CLO-35 | `DefaultClosureProcessorTest.rebindsOnlyNonHistoricalInactiveRowsAcrossCyclicChurn`; `ManagedOccurrenceBindingFactoryTest` | `ContractsClosureAdmissionAdapterTest.retiresThenLaterReactivatesExactInactiveSuccessorAcrossRestart` | detachment method | **No** — remove/re-add lineage is already explicitly normative. |
| P5.1 | Two 2-member cycles merge to one 4-member cycle | C-CLO-09, C-CLO-27, C-CLO-30 | `ComponentGenerationTransitionTest.shouldAdvanceFromMaximumContributorOnMerge` | None exact through public engine | `ContractsPublicComponentMergeSplitTest.twoTwoMemberCyclesMergeIntoOneFourMemberCycle` | **No** — direct public proof of C-CLO-09. |
| P5.2 | One 4-member cycle splits into two 2-member cycles | C-CLO-11, C-CLO-28, C-CLO-30 | `ComponentGenerationTransitionTest.shouldAdvanceEveryResultOnSplit` | None exact | `oneFourMemberCycleSplitsIntoTwoTwoMemberCycles` | **No** — direct public proof of C-CLO-11. |
| P5.3 | One 2-member cycle splits into two ordinary singletons | C-CLO-10, C-CLO-28 | `ComponentFinalizationKernelTest.shouldApplyClo10StyleCycleSplitGenerationTransition` | None exact | `oneTwoMemberCycleSplitsIntoTwoOrdinarySingletons` | **No** — direct public proof of C-CLO-10. |
| P5.4 | Self-cycle dissolves to ordinary A | C-CLO-07, C-CLO-10 | `SccPartitionerTest.shouldPartitionSelfCycle`; `ComponentFinalizationKernelTest.shouldFinalizeSelfCycleThroughTheLanguageOraclePath` | None exact | `selfCycleDissolvesIntoOneOrdinaryDocument` | **No** — self-cycle and dissolution laws already exist. |
| P5.5 | Later Handler failure rolls back staged split | C-CLO-20 plus C-CLO-10 | `DefaultClosureProcessorTest.convertsDeterministicHandlerFailureToRuntimeFatalRollback` | Admission rollback tests | `laterHandlerFailureRollsBackAlreadyStagedSplitExactly` | **No** — atomic late-failure rollback is already normative. |
| P6.1 | Static 3-member cyclic admission initializes once and publishes atomically | C-CLO-01, C-CLO-16, C-CLO-21, C-CLO-30, C-CLO-32 | `ClosureAdmissionExecutionTest.initializesEveryCyclicMemberAsAnIndependentRootAndCommitsOneMarkerBatch` | `ContractsClosureAdmissionAdapterTest.admitsC01CycleAtomicallyReplaysReceiptAndDrainsAfterAdmission` | `ContractsPublicInitializationTopologyTest.staticThreeMemberCycleInitializesOnceInCanonicalOrderAndPublishes` | **No** — static admission and batch publication are already normative. |
| P6.2 | Static initialization order/identities ignore reversed input | C-CLO-01 parity, C-CLO-24, C-CLO-30 | finalizer/SCC order-invariance tests | Public ordering acceptance | `staticInitializationOrderAndIdentitiesIgnoreInputPermutation` | **No** — canonical input-order independence already exists. |
| P6.3 | Reciprocal edge first formed during initialization | C-CLO-08, C-CLO-21 | `AdmissionClosureFixtureExecutionTest.shouldFormCycleDuringOrdinaryInitializationPatches` | No fixed public runtime seam | `cClo08FirstFormationNeedsItsConformanceRuntimeAndAnEventBridgeFailsClosed` (**blocker characterization**) | **No** — C-CLO-08 is already the normative proof; adding a fixture cannot create the missing public host seam. |
| P6.4 | Reciprocal + two collection members staged during initialization | C-CLO-08; C-EMB-08, C-EMB-10, C-EMB-13 | admission fixture execution plus ordinary collection corpus | No fixed public runtime seam | `dynamicTopologyPatchInsideCycleFailsAtSubscriptionBoundary` (**blocker characterization**) | **No** — underlying portable laws exist; public success needs an API/runtime capability, not a duplicate fixture. |
| P6.5 | Initialization multiplicity and containing reactions | C-CLO-08, C-CLO-21, C-CLO-34; C-EMB-08/13 | admission/document-step tests | Static admission only | static evidence proves six ordered work occurrences; dynamic containing reaction remains blocked | **No** — success-side dynamic reaction is already normative in C-CLO-08 but unavailable in this composition. |
| P6.6 | Later C initialization failure rolls back every marker/member/publication | C-CLO-20, C-CLO-21, C-CLO-32 | admission rollback tests | Admission failure rollback | `laterMemberInitializationFailureRollsBackEveryMarkerAndPublication` | **No** — batch rollback is already normative. |
| P6.7 | No member publishes before complete initialization batch | C-CLO-08, C-CLO-21, C-CLO-32 | `ClosureAdmissionExecutionTest` | Atomic admission receipt path | static success and late-failure methods | **No** — atomic admission publication is already normative. |
| P7.1 | Ordinary non-Root scope remains supported outside affected closure | C-EMB-01, C-EMB-02, C-EMB-03 | ordinary Contracts corpus | Existing ordinary public engine | `ContractsPublicNestedScopeBoundaryTest.ordinaryPublicEngineExecutesTheNestedScopeNormally` | **No** — ordinary behavior already has fixtures. |
| P7.2 | Contracts closure direct seeds/steps remain exactly Root-only | C-CLO-34 and HARNESS §5 | `Cclo34FullResultConformanceTest`; `DocumentStepBoundaryTest` | Existing public cycle steps | `contractsDirectSeedsAndManagedStepsRemainPreciselyRootOnly` (**profile blocker**) | **No** — a positive nested closure fixture would contradict Contracts 1.0 §2.2.1. |
| P8.1 | Benchmark shape: two-member finite cycle | C-CLO-02, C-CLO-34 | Dynamic/full closure corpus | `ContractsClosureAdmissionAdapterTest.publicDrainProcessesFiniteCycleInExactAThenBThenAOrder` | Dedicated performance task (results deferred) | **No** — exact semantic shape already exists. |
| P8.2 | Benchmark shape: three-member ring | Same as P2.1b | Same as P2.1b | Same as P2.1b | Dedicated performance task (results deferred) | **No** — benchmark repetition adds measurement, not semantics. |
| P8.3 | Benchmark shape: five-member branching SCC | Same as P3.1 | Same as P3.1 | Same as P3.1 | Dedicated performance task (results deferred) | **No** — benchmark repetition adds measurement, not semantics. |
| P8.4 | Benchmark shape: two disjoint two-member SCCs | Same as P3.3a | Same as P3.3a | Same as P3.3a | Dedicated performance task (results deferred) | **No** — benchmark repetition adds measurement, not semantics. |
| P8.5 | Benchmark shape: five-member SCC plus 1,000 unrelated | C-CLO-18 and P3.2 mapping | Same as P3.2 | Same as P3.2 | Dedicated performance task (results deferred) | **No** — exact locality fixture already exists. |
| P8.6 | Benchmark shape: detachment and dissolution | C-CLO-10/11/12/28 and P4 mapping | Same as P4.1/P4.2 | Same as P4.1/P4.2 | Dedicated performance task (results deferred) | **No** — timing the existing transition adds no portable law. |
| P10.1 | Authored documents derive the exact expert-level admission identity | C-CLO-01, C-CLO-21, C-CLO-29, C-CLO-30 | `ClosureEvidenceFactoryTest.shouldDeriveACompleteHostInvocationWithoutCallerHashing`; `ClosureIdentityServiceTest` | Low-level `admitContractsClosure(...)` public tests | `Contracts10AuthoredFacadeParityTest.authoredDocumentsDeriveTheExactLowLevelAdmissionIdentity` | **No** — this is host developer-experience parity over existing identity laws, not a portable semantic addition. |

## Public-host blockers and remaining limitations

### BLOCKER-P6-INIT-PATCH

- Requested capability: introduce a reciprocal edge and collection members through ordinary initialization inside `ADMIT_CLOSURE`.
- Normative proof: C-CLO-08 executes through the conformance runtime's initialization-patch mechanism.
- Fixed public Coordination result: an event cannot bridge admission, and the public composition exposes no injectable initialization patch processor. The C-CLO-08-shaped input fails `RUNTIME_FATAL`; the multi-path activation reaches `SUBSCRIPTION_SURFACE_INVALID` and rolls back.
- Required future work: a deliberate high-level authored-document admission capability or composition seam, designed separately. Do not fabricate a Timeline Entry or bypass closure processing.

### BLOCKER-P7-NONROOT-CLOSURE

- Requested capability: closure work at a nested local pointer inside a cyclic managed document.
- Normative restriction: Contracts 1.0 §2.2.1 and HARNESS §5 require `scopePath=/`, activation generation 0, Root channel/subscription identities, and rejection of non-Root closure addresses.
- Current public result: ordinary `PROCESS` handles nested scope; affected closure does not select it as a direct seed. Every accepted closure step remains an isolated Root step with no ambient containing context.
- Required future work: a separately specified non-Root affected-closure profile. It is outside this bounded round.

### Instrumentation boundary

The Phase 3 locality test's zero semantic-work assertions are valid for gas-evidenced managed opens, work dequeues, and member finalizations. Whether every host-side scan/read counter is fully producer-backed is a Phase 8 instrumentation question. This coverage audit intentionally does not convert those counters into a final performance claim.

### Re-add wording

“New activation generation” means the already-created inactive generation-2 successor, not a new generation created at re-add. “New occurrence lineage” is true relative to retired active generation 1, but the re-add correctly preserves the committed inactive successor's generation-2 occurrence identity as required by C-CLO-35.

## Fixture decision record

The prompt listed five likely fixture candidates. None defines a new portable law:

| Candidate | Decision | Reason |
|---|---|---|
| Three-member finite ring | Do not add | Existing finite caused-work, arbitrary ring admission, cyclic identity/proof, and isolated-step fixtures already define it; three members change only cardinality. |
| Shared-anchor five-member branching SCC | Do not add | SCC transitivity, multiple-SCC distinction, `collectionPaths`, duplicate occurrence, and cyclic proof laws are already separately normative. |
| Partial break leaving smaller SCC plus acyclic tails | Do not add | C-CLO-10/11/28 already define split, mixed result shape, ordinary identity, and containing-spine rewrite. |
| Same operation succeeds after detachment | Do not add | It composes C-CLO-04 shared-gas rollback with C-CLO-10/12 retired-edge causality; no new gas rule arises. |
| `collectionPaths`-backed cyclic formation | Do not add | C-EMB-08/10/11/13 define collection membership and lineage; C-CLO-29 requires exact authored active paths; cyclic finalization is cardinality/path-origin agnostic. |

No expected YAML value, oracle hash, package manifest, release identity, or fixture inventory was edited.
