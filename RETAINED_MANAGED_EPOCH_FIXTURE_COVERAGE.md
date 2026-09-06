# Retained managed-epoch fixture coverage

## Scope and evidence state

This ledger maps the retained managed-epoch requirements in sections 4 through
14 of `CODEX_RETAINED_MANAGED_EPOCH_CATCH_UP_ROUND.md` to executable source in
the current Contracts and Coordination worktrees:

- Contracts: `/private/tmp/blue-language-managed-epoch-rc22`;
- Coordination: `/private/tmp/blue-coordination-retained-catch-up-rc4`.

The source audit was refreshed on 2026-08-26. The status vocabulary is strict:

- **SOURCE-COVERED** means the named executable test method exists and its
  assertions were inspected for the stated behavior.
- **SOURCE-COVERED (composed)** means the required law is asserted across the
  named methods or a unit test plus a portable fixture.
- **SOURCE-CAVEAT** identifies an exact fixture-shape difference that must not
  be hidden by a broader claim.
- **Execution evidence: PENDING ROOT RUN** means this document does not claim
  that the current Coordination candidate, final immutable stages, or release
  gates have passed. Root must replace that marker only from recorded runs.

No MyOS test is part of this upstream round and this ledger claims no MyOS
integration.

## Contracts complete transition receipts — sections 5.8 and 13.1

The portable full-lifecycle sources are exported and replayed by
`FullLifecycleFixtureExporterTest.shouldExportDeterministicCompleteFixturesAndReplayExactEvidence`.
The released managed-revision corpus is executed by
`ManagedRevisionClosureFixtureTest.executesEachReleasedReceiptAsOneIndependentDocumentStep`,
and the complete result shape is checked by
`Cclo34FullResultConformanceTest.shouldMatchEveryReleasedCclo34ResultAndImplementationField`.
Those shared gates are listed here as source coverage only; their execution
evidence remains pending.

| ID | Required behavior | Exact executable coverage | Portable fixture coverage | Source status | Execution evidence |
|---|---|---|---|---|---|
| 13.1.a | Initialization receipt | `FullLifecycleAdmissionTest.requirement01RootInitializationPatchCommitsExactMarkerState`; receipt/result binding is also exercised by `FullLifecycleAdmissionTest.requirement03EqualInitializationEventsRetainTwoOccurrences` | `fl-adm-01-root-patch-event.yaml` | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.1.b | Non-public Root emits one complete event | `FullLifecycleAdmissionTest.requirement04NonPublicEmitterReachesContainingPublicRootOnly` | `fl-adm-03-non-public-containing-route.yaml` | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.1.c | Non-public Root preserves two equal occurrences | `ManagedTransitionReceiptTest.eventOnlyReceiptPreservesTwoEqualOccurrences` constructs two ordered non-public occurrences and asserts equal event BlueIds, distinct occurrence identities, and admitted gas; occurrence construction authenticates each exact event body | The portable corpus separately covers duplicate equal events in `fl-adm-02-duplicate-equal-events.yaml` and the non-public boundary in `fl-adm-03-non-public-containing-route.yaml`; no single portable source combines both conditions | **SOURCE-COVERED (composed)**; **SOURCE-CAVEAT** on the combined portable vector | **PENDING ROOT RUN** |
| 13.1.d | Event-only transition with unchanged BlueId | `ManagedTransitionReceiptTest.eventOnlyReceiptPreservesTwoEqualOccurrences`; `FullLifecycleAdmissionTest.managedRevisionEventOnlyTransitionProducesBoundReceipt` | Managed-revision fixture/result fields are covered by the shared C-CLO-23/C-CLO-34 conformance methods above | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.1.e | State-only transition with an empty complete-event sequence | `ManagedTransitionReceiptTest.stateOnlyReceiptIsValidAndEmptyNoOpIsRejected`; `FullLifecycleAdmissionTest.requirement01RootInitializationPatchCommitsExactMarkerState` | State-only result forms occur in the released managed-revision corpus | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.1.f | State and events are authenticated together | `FullLifecycleAdmissionTest.requirement03EqualInitializationEventsRetainTwoOccurrences`; `ManagedTransitionReceiptTest.aggregateAndManagedRevisionCauseBindCompleteReceipt` | `fl-adm-01-root-patch-event.yaml`; `fl-adm-02-duplicate-equal-events.yaml` | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.1.g | Cyclic members receive canonical per-member receipts | `FullLifecycleAdmissionTest.requirement09FiniteCyclicInitializationRouteQuiesces` asserts two ordered member receipts and total receipt gas | `fl-adm-07-finite-cyclic-route.yaml` | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.1.h | Rollback publishes no transition receipt or companion | `FullLifecycleAdmissionTest.requirement10InfiniteCyclicRouteExhaustsSharedGasAndRollsBack`; `FullLifecycleAdmissionTest.requirement11LaterMemberFailureRollsBackWholeClosure`; both use `assertLiteralRollback`, which asserts an empty receipt sequence and absent companion | `fl-adm-08-infinite-cycle-gas-retry.yaml`; `fl-adm-09-late-member-rollback.yaml` | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.1.i | Tampered event body, occurrence identity, or aggregate receipt identity fails closed | `ManagedTransitionReceiptTest.eventBodyOccurrenceAndReceiptTamperingFailClosed`; aggregate binding is additionally distinguished by `ManagedTransitionReceiptTest.aggregateAndManagedRevisionCauseBindCompleteReceipt` | Complete fixture result identities are checked by the shared C-CLO-34 test | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.1.j | Complete Root events do not alter the public subset | `FullLifecycleAdmissionTest.requirement04NonPublicEmitterReachesContainingPublicRootOnly`; `FullLifecycleAdmissionTest.requirement05PublicRootInitializationEventIsProjectedOnce`; `FullLifecycleAdmissionTest.completeManagedRevisionReceiptRetainsTerminatedSourceWithoutLocalReprocessing` | `fl-adm-03-non-public-containing-route.yaml` | **SOURCE-COVERED** | **PENDING ROOT RUN** |

The managed-revision delivery requirement from section 5.8 is directly covered
by `FullLifecycleAdmissionTest.completeManagedRevisionReceiptRetainsTerminatedSourceWithoutLocalReprocessing`:
it replaces the target occurrence, preserves two source occurrences, executes
only the containing handler, does not rerun the authoritative source, does not
republish the imported source events, and permits the consumer to emit its own
new public events.

## Coordination matching — section 13.2

| ID | Required match | Exact executable coverage | Source status | Execution evidence |
|---|---|---|---|---|
| 13.2.a | Current existing lineage | `ManagedOccurrenceResolverTest.currentExactWinsWhenTheSameStateAlsoOccursAtOlderEpochs` | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.2.b | Existing authored initial, cursor `-1` | `ManagedOccurrenceResolverTest.existingAuthoredInitialSelectsPreInitializationEpoch` | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.2.c | Unique initialized epoch zero | `ManagedOccurrenceResolverTest.uniqueInitializedEpochZeroSelectsEpochZero` | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.2.d | Unique retained epoch | `ManagedOccurrenceResolverTest.uniqueRetainedEpochSelectsItsExactEpoch` | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.2.e | Current wins when the same state appeared historically | `ManagedOccurrenceResolverTest.currentExactWinsWhenTheSameStateAlsoOccursAtOlderEpochs` | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.2.f | Same BlueId at two historical epochs is ambiguous | `ManagedOccurrenceResolverTest.repeatedHistoricalBlueIdIsAmbiguous` | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.2.g | Same BlueId across lineages is ambiguous | `ManagedOccurrenceResolverTest.identicalExactStateAcrossLineagesIsAmbiguous`; inactive reservation precedence is covered by `ManagedOccurrenceResolverTest.inactiveReservationIsStableIdentityEvidenceAheadOfAmbiguity` | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.2.h | Explicit exact epoch disambiguation | `ManagedOccurrenceResolverTest.exactSelectorChoosesOneRepeatedHistoricalEpoch`; `ManagedOccurrenceResolverTest.exactSelectorDisambiguatesIdenticalStateAcrossLineages`; `ManagedOccurrenceResolverTest.exactSelectorRejectsWrongEpochStateAndOccurrenceState`; public SDK path: `SdkManagedEpochSelectorAcceptanceTest.operationSelectorChoosesExactHistoricalSourceEpoch` | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.2.i | Unknown progressed/initialized value is unproven history, not a new lineage | `ManagedOccurrenceResolverTest.unknownInitializedValueIsUnprovenHistoryNotANewLineage` | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.2.j | Inline/reference/verified-provider parity | `ManagedOccurrenceResolverTest.inlineAndPureReferenceResolveToTheSameManagedPosition`; `ManagedOccurrenceResolverTest.exactNodeDemandCompletesOnlyWhenProviderHasVerifiedContent`; `ManagedOccurrenceResolverTest.exactNodeResolutionCarriesOneShotProviderContentForTheRetry` | **SOURCE-COVERED (composed)** | **PENDING ROOT RUN** |

Supporting identity and locality assertions are in
`ManagedOccurrenceResolverTest.newAuthoredValueUsesExactIdentityAndNeverReadsAuthoredDocumentId`,
`ManagedOccurrenceResolverTest.duplicateNewOccurrencesShareOnePendingLineage`,
and
`ManagedOccurrenceResolverTest.exactResolutionAndLineageAdvanceRemainLocalWithOneThousandAmbient`.

## Coordination catch-up acceptance — sections 13.3 through 13.15

| Section | Required behavior | Exact executable coverage | Key assertions present in source | Source status | Execution evidence |
|---|---|---|---|---|---|
| 13.3 | Authored-initial A/B catch-up through B0/B1/B2; ready only afterward; source never reprocessed | `SdkRetainedManagedEpochCatchUpTest.authoredInitialInlineValueAppliesInitializationAndLaterEpochs`; full B0/B1/B2 sequence in `SdkCanonicalRetainedManagedEpochScenarioTest.canonicalScenarioReplaysToExactDurableEquality`; receipt boundary in `ManagedEpochEvidenceTest.authoredSelectorSentinelCannotBecomeADurableReceiptEpoch` | Authored cursor `-1` is selector-only; first receipt is epoch `0` with no receipt before-state; every audited receipt epoch is nonnegative; one source lineage; bounded B0/B1/B2 applications; `CATCHING_UP` then `READY`; frozen source history and source PROCESS count | **SOURCE-COVERED (composed)** | **PENDING ROOT RUN** |
| 13.4 | Attach B0, retained B1, and current B | `SdkRetainedManagedEpochCatchUpTest.initializedEpochZeroCatchesUpWithoutReprocessingTheSource`; `SdkRetainedManagedEpochCatchUpTest.retainedEpochAppliesOnlyItsSuffixAndCurrentStateCreatesNoPlan` | B0 applies epochs 1 and 2 behind the ready head; retained B1 applies only epoch 2; current exact state creates no plan | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.5 | Duplicate occurrences have separate cursors and share source receipts | `SdkRetainedManagedEpochCatchUpTest.duplicateOccurrencesOwnIndependentCursorsAndShareSourceReceipts`; store ordering in `CatchUpPlanStoreTest.duplicateOccurrencesOwnIndependentCursorsAndCanonicalDueOrder`; one new live source epoch reaching both paths after one source PROCESS in `SdkCanonicalRetainedManagedEpochScenarioTest.canonicalScenarioReplaysToExactDurableEquality` | Two paths and two historical plans/cursors share immutable retained receipts; canonical step 12 proves one new B3 PROCESS reaches both active paths through the ordinary connected closure without reopening either completed historical plan | **SOURCE-COVERED (composed)** | **PENDING ROOT RUN** |
| 13.6 | Multiple parents progress independently when one fails | `SdkManagedEpochSharedFailureIsolationTest.sharedSuffixSurvivesOneDeterministicConsumerGasFailure` | A/C/D share B receipts; A fails; C/D commit and become ready; A retries the identical failed work; source histories and PROCESS counters remain unchanged | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.7 | Non-public source promotion preserves lineage/history | `SdkManagedEpochPromotionAndRetargetTest.promotionPreservesNonPublicHistoryAndAuthoredInitialCatchUp` | Promotion changes no state, epoch, history, receipts, journal count, initialization count, or PROCESS count; authored-initial catch-up selects the same source | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.8 | Consumer gas failure is atomic and retry-exact | `SdkManagedEpochSharedFailureIsolationTest.sharedSuffixSurvivesOneDeterministicConsumerGasFailure`; isolated cyclic variant in `SdkManagedEpochCycleAcceptanceTest.infiniteCycleApplicationRollsBackAndRetriesExactly` | Both failures preserve the consumer head, plan/cursor, occurrence, history, events, prior application receipt, and source PROCESS count. The isolated cyclic retry has identical work, invocation, gas trace, rejected work, and rejected charge. In the shared-parent fixture the work and its committed-head/graph fences remain stable, while successful C/D siblings legitimately enlarge A's next ordinary affected closure, so that retry's invocation and closure identities differ and each attempt independently rolls back to its own input. | **SOURCE-COVERED (composed)** | **PENDING ROOT RUN** |
| 13.9 | Response loss after store commit does not invoke PROCESS twice | `ManagedEpochApplicationResponseLossTest.sameProcessRetryReconcilesCommittedApplicationWithoutProcess`; `ManagedEpochApplicationResponseLossTest.restartRebuildsRoutesAndDropsDisposableRepairWithoutProcess` | Store commit, cursor, application receipt, consumer event/history, and companion survive; same-process replay is marked replayed; restart rebuilds routes and finds no due work; process counters remain fixed | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.10 | Missing/tampered immutable history fails closed | `ManagedEpochReceiptVerificationTest.sixMissingOrTamperedHistoryCasesFailClosedAndAllowSiblingProgress`; `ManagedEpochReceiptVerificationTest.missingContractsTransitionBlocksBeforeProcess`; cyclic-proof matrix in `ManagedEpochIndirectComponentRebindTest` | Missing epoch, wrong before, wrong after, wrong source, wrong event, wrong receipt identity, missing Contracts receipt, and cyclic proof NOT_FOUND/UNAVAILABLE/INVALID_EVIDENCE become typed wait/block states without cursor/head/occurrence/history/receipt mutation; restart preserves status; source PROCESS remains zero | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.11 | Multi-child barrier, canonical interleaving, and no overtaking | `SdkManagedCatchUpBarrierAndLiveExtensionTest.multiChildBarrierFinishesAtAttachmentCutoffBeforeLaterTimelineEntries`; `ManagedCatchUpPlannerTest.waitingOrBlockedSiblingGatesOnlyItsImpactedConsumer` | One barrier owns B/C plans; receipts interleave by original source order; WAITING/BLOCKED siblings gate only their consumer; later direct A entry remains queued until completion; unrelated consumers continue | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.12 | Captured temporal cutoff and later source traffic | `SdkTimestampCatchUpOrderTest.fourThirtyRootInputCannotBeOvertakenByQueuedFivePmSourceInput`; `SdkManagedCatchUpBarrierAndLiveExtensionTest.boundedDrainsCompleteCapturedSuffixBeforeContinuingFutureSourceTraffic` | Historical suffix completes through the attachment boundary under bounded drains and restart; earlier direct root entries precede queued future source entries; retained source receipts remain exact and future entries execute once through ordinary processing | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.13 | Catch-up forms a cycle; finite settles and detaches; infinite rolls back/retries | `SdkManagedEpochCycleAcceptanceTest.retainedApplicationFormsFiniteCycleThenDetachRetiresIt`; `SdkManagedEpochCycleAcceptanceTest.infiniteCycleApplicationRollsBackAndRetriesExactly` | The finite fixture proves ordinary two-member cyclic identity/component formation, one finite reaction, source non-reprocessing, cycle dissolution, and occurrence retirement; its already-complete plan remains immutable `COMPLETE` audit evidence after detach. The separate infinite fixture proves exact gas rollback and an identical isolated retry without claiming post-failure detach. | **SOURCE-COVERED (composed)** | **PENDING ROOT RUN** |
| 13.14 | Detach/re-add/retarget owns fresh generations and never migrates cursors | `SdkManagedEpochPromotionAndRetargetTest.detachReaddAndRetargetOwnDistinctPlansAndCursors`; store laws in `CatchUpPlanStoreTest.occurrenceRetirementIsGenerationSafeAndCancelsPendingWork` and `retirementKeepsCompleteAuditAndCancelsBlockedPlan` | Incomplete/BLOCKED generations cancel; COMPLETE status/snapshot/identity remain immutable audit; re-add and retarget create fresh generations/plans/cursors; retired occurrences receive no later event | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13.15 | Locality with 1,000 unrelated sessions | `ManagedEpochCatchUpLocalityTest.oneThousandUnrelatedSessionsOpenOnlyExactCatchUpRows`; store-level support in `ManagedEpochReceiptStoreTest.oneThousandUnrelatedDocumentsDoNotExpandAnExactRead` and `CatchUpPlanStoreTest.oneThousandUnrelatedPlansDoNotExpandDueSelectionOrSourceAudit` | No full scan or unrelated document read; exact receipt/plan/barrier/application row counts; bounded occurrence examinations; unrelated session and persistent index identities retained | **SOURCE-COVERED** | **PENDING ROOT RUN** |

## Cross-cutting retained managed-epoch laws

| Requirement | Exact executable coverage | Key assertions/coverage boundary | Source status | Execution evidence |
|---|---|---|---|---|
| One immutable Coordination receipt per contiguous nonnegative source epoch, including initialization and event-only same-state epochs | `ManagedEpochEvidenceTest.initializationReceiptHasNoBeforeStateAndIntegratesWithEpochZero`; `ManagedEpochEvidenceTest.authoredSelectorSentinelCannotBecomeADurableReceiptEpoch`; `ManagedEpochEvidenceTest.completeReceiptPreservesDuplicateEventsAndRejectsTampering`; `ManagedEpochReceiptStoreTest.contiguousHistoryIsImmutableAuditableAndIdempotent`; `ManagedSameStateEpochPublicationTest.eventOnlyReactionPublishesSameStateEmbeddedRevisionAndReceipt` | Initialization begins at epoch zero; equal-state event transitions still create contiguous immutable evidence. | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| Gaps, wrong chains, conflicts, and reordered events fail closed | `ManagedEpochReceiptStoreTest.gapsWrongBeforeAndEpochConflictsFailClosed`; `ManagedEpochEvidenceTest.completeReceiptPreservesDuplicateEventsAndRejectsTampering`; section 13.10 tests above | No conflicting or noncontiguous receipt becomes authoritative. | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| Plan/barrier/work/application/readiness identities are self-verifying and occurrence-specific | `ManagedEpochEvidenceTest.planBarrierWorkApplicationAndReadinessAreSelfVerifying`; `ManagedCatchUpPlannerTest.oneConsumerOwnsOneFenceAndTheNextOccurrenceIsRebasedAfterCommit`; `CatchUpPlanStoreTest.cursorAndBarrierMismatchesFailClosed` | Stable plan/barrier definitions retain their identities while their authenticated snapshot identities bind mutable progress, membership, status, and waits. | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| Repeated committed work is idempotent and missing history can resume the same immutable work/cursor without inconsistent indexes | `CatchUpPlanStoreTest.applicationIsResponseLossSafeAndCompletePlanStaysClosed`; `CatchUpPlanStoreTest.missingReceiptPublishesWaitingEvidenceAndWorkResumesIt`; `CatchUpPlanStoreTest.evidenceRepairRequeuesTheRegisteredWorkWithoutReplacingItsAudit`; `CatchUpPlanStoreTest.staleRegisteredWorkCannotReplaceAnotherPendingAttempt` | Repair restores both scheduling indexes around the same registered work; it does not replace audit identity. | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| Permanently failing managed lanes do not starve healthy managed or direct work, including across restart | `SdkManagedEpochBudgetFairnessTest.poisonLanesRoundRobinAndHealthyProgressSurviveRestart` | Two deterministic gas-poison lanes rotate in canonical order while one healthy managed lane and independent direct work both commit; the restarted and uninterrupted runs have the same selection order, failed cursors and histories remain unchanged, and the healthy plan completes. | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| An authenticated retained terminal epoch is delivered exactly, then the plan completes | `SdkManagedEpochSourceTerminationTest.terminatingEpochCompletesItsConsumerPlanWithoutSourceReprocessing`; `productionTerminationLaneRemainsFailClosed` | The catch-up fixture stages authenticated terminal evidence internally. The production operation-time lane is separately characterized as a fail-closed `RUNTIME_EXECUTION_FAILURE` requiring the lifecycle/marker batch lane; this round does not implement that lane. | **SOURCE-COVERED — authenticated synthetic fixture and production gap characterization**; **SOURCE-CAVEAT** | **PENDING ROOT RUN** |
| A retained-application handler may add an existing historical lineage to its owning barrier; nested `NEW_AUTHORED` creation during catch-up is not claimed | `SdkManagedCatchUpBarrierAndLiveExtensionTest.completedSiblingGatesLaterSourceUntilOlderWorkAcrossRestart`; ordinary current-lineage reuse in `ContractsManagedDraftExpansionTest.typedDemandReusesOneExistingCurrentLineageWithoutInitialization` | Applying B1 makes A's handler install retained C0, publishes B and C plans on the same barrier, applies older C1 before queued B2 across two store reconstructions, and leaves both source histories unchanged. A genuinely new authored nested lineage remains explicitly fail-closed. | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| A catch-up-produced consumer epoch propagates through an already-active typed-demand parent in the same ordinary affected closure | `SdkManagedCatchUpBarrierAndLiveExtensionTest.retainedConsumerEpochPropagatesThroughAlreadyActiveParent` | A actively embeds B before B attaches retained C; applying C1 advances B and A together, delivers B's emitted event to A, and leaves C history/receipts/events and source PROCESS counts unchanged | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| A cyclic source successor requires separately retained complete proof; eventless cyclic epochs remain transitive source evidence | `ManagedEpochIndirectComponentRebindTest.missingRetainedCyclicSourceProofWaitsWithoutAdvancingCursor`; `unavailableRetainedCyclicSourceProofWaitsWithoutAdvancingCursor`; `invalidRetainedCyclicSourceProofBlocksWithoutAdvancingCursor`; `ManagedSameStateEpochPublicationTest.eventlessCyclicApplicationEpochRemainsReplayableAsSourceEvidence` | NOT_FOUND and UNAVAILABLE publish typed same-cursor waits; INVALID_EVIDENCE publishes a typed block; direct/restart/SDK paths preserve head, occurrence, history, receipts, application registry and zero PROCESS; a same-BlueId cyclic eventless epoch later applies through another occurrence without source reprocessing and replays after restart | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| Ordinary closures and suspended managed applications expose host-persistable automatic retry count and typed per-demand matching failures without parsing diagnostic text | `SdkDrainResultMapperTest.mapsRetainedResolutionStatusWithoutInterpretingDiagnosticText`; `SdkDrainResultMapperTest.suspendedClosureExposesTypedDemandAndAutomaticAttemptCount`; `SdkManagedEpochApplicationResolutionEvidenceTest.mapsMissingExactContentWithAutomaticRetryCount`; `SdkManagedEpochApplicationResolutionEvidenceTest.mapsAmbiguousEpochWithoutInterpretingDiagnosticText` | Both SDK result paths retain resource-demand identity, retry count, `MISSING_EXACT_CONTENT`/`AMBIGUOUS_MANAGED_EPOCH` enum, and opaque diagnostic independently | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| Same-epoch component representation preservation is bounded to the authenticated finite two-member cycle; a larger merge requiring source-epoch change fails closed | `SdkManagedEpochCycleAcceptanceTest.retainedApplicationFormsFiniteCycleThenDetachRetiresIt`; `ManagedEpochIndirectComponentRebindTest.componentMergeThatWouldAdvanceSourceEpochFailsClosed`; store/index laws in `ManagedEpochReceiptStoreTest.componentRebindAdvancesOnlyTheContinuationCursor` and `ManagedLineageIndexTest.representationRebindMovesOnlyCurrentIdentityAndPreservesSourceEpoch` | The positive A/B fixture changes B's current cyclic component representation while retaining B's exact source history. The A plus existing B/C cyclic-component merge is rejected when it would append or reinterpret immutable source history; durable state and the same pending work survive restart. No arbitrary-indirect-peer acceptance is claimed. | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| Public SDK exact selector and bounded drain are typed and immutable | `ManagedEpochSdkSurfaceTest.selectorPlumbingIsTypedImmutableAndOccurrenceSpecific`; `ManagedEpochSdkSurfaceTest.boundedSdkDrainDelegatesToTheExistingEngineBudget` | Selector evidence is occurrence-specific; the SDK budget delegates to the existing engine boundary. | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| Packaged staged coordinate exposes typed receipts/events, immutable source order, selector, bounded drain, application attempts/receipts, and advanced audits without source-output substitution | `StagedCoordinationConsumerTest.retainedEpochEvidenceExecutesThroughTheStagedCoordinate` | The smoke imports only the packaged public surface and executes retained-epoch evidence through it. | **SOURCE-COVERED** | **PENDING ROOT RUN** |

Across the integration tests, source non-reprocessing is asserted from frozen
source history/receipt/event identities and from
`EXTERNAL_PROCESS_CALLS`/`managedEpoch.catchUp.sourceProcessCalls`. The barrier
and locality tests additionally assert zero deltas for full-environment scans,
unrelated reads, source replay per parent, post-process full projection, and
graph-retry parent/child reruns.

## Section 14 canonical public-SDK scenario

All 17 steps are implemented in the single readable method
`SdkCanonicalRetainedManagedEpochScenarioTest.canonicalScenarioReplaysToExactDurableEquality`.

| Step | Required action/evidence present in that method | Source status | Execution evidence |
|---|---|---|---|
| 1 | Admit A as a public Root | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 2 | Admit initialized B as a non-public managed document; audit B0 initialization event | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 3 | Increment B twice; assert epochs 0, 1, and 2 | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 4 | Promote B and compare history, receipts, and Timeline input count exactly | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 5 | Attach B authored initial at A's first path | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 6 | Audit B reuse and A's committed-not-ready barrier/plan | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 7 | Drain B0, B1, and B2 individually with `DrainBudget(1, 1)` and exact receipt/cursor assertions | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 8 | Assert one initialization and two change reactions at A's first occurrence | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 9 | Assert A ready, B history identical, and B source PROCESS count unchanged | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 10 | Attach exact retained B epoch 1 at A's second path with `ManagedEpochSelector.exact` | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 11 | Apply only B2 through the second occurrence | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 12 | Process B3 once and deliver its one receipt through both occurrence plans | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 13 | B embeds current A; audit an ordinary cyclic component containing A and B | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 14 | Run and settle one finite A→B→A cyclic route | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 15 | Detach B→A, prove acyclic state, and prove later A work does not relay through B | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 16 | Re-run the same accepted deterministic input program in a fresh in-memory runtime, using the prompt-permitted deterministic replay mechanism rather than serialized-store restore | **SOURCE-COVERED** | **PENDING ROOT RUN** |
| 17 | Compare exact Timeline entries, histories, receipt streams, plans/cursors, occurrence states, application receipts, component transitions/state, document audits, and readiness | **SOURCE-COVERED** | **PENDING ROOT RUN** |

## Java and artifact gates — section 13.16

Existence of source coverage is not release evidence. Each final candidate gate
below remains explicitly unclaimed until root records the invocation, runtime,
candidate commit/tree, immutable repository manifest, report, and outcome.

| ID | Gate | Required recorded evidence | Execution evidence |
|---|---|---|---|
| 13.16.a | Java 17 | Clean Contracts and Coordination commands, JVM identity, complete reports | **PENDING ROOT RUN** |
| 13.16.b | Java 21 | Same candidate sources, JVM identity, complete reports, equivalent semantics | **PENDING ROOT RUN** |
| 13.16.c | Unit | Full Contracts and Coordination unit inventories, not focused-only results | **PENDING ROOT RUN** |
| 13.16.d | Integration | All retained managed-epoch acceptance classes above | **PENDING ROOT RUN** |
| 13.16.e | Consumer | Public consumer compiled/run from packaged JAR or immutable coordinate only | **PENDING ROOT RUN** |
| 13.16.f | Scenario | `SdkCanonicalRetainedManagedEpochScenarioTest.canonicalScenarioReplaysToExactDurableEquality` | **PENDING ROOT RUN** |
| 13.16.g | Javadocs | Contracts and Coordination public API Javadoc reports | **PENDING ROOT RUN** |
| 13.16.h | API/binary compatibility | Additive delta reports against published 3.1.0-rc.22 and 3.0.0-rc.4 | **PENDING ROOT RUN** |
| 13.16.i | Dependency preflight | Invocation-owned immutable Contracts stage, manifest binding, no Maven Local | **PENDING ROOT RUN** |
| 13.16.j | Clean release | Clean-worktree release/quality receipts at candidate heads | **PENDING ROOT RUN** |
| 13.16.k | Extracted-source build | Deterministic source archive identity and clean extracted build | **PENDING ROOT RUN** |
| 13.16.l | Staged downstream consumer | `StagedCoordinationConsumerTest.retainedEpochEvidenceExecutesThroughTheStagedCoordinate` resolved from the immutable Coordination and Contracts repositories | **PENDING ROOT RUN** |
| 13.16.m | `git diff --check` | Empty output at both final candidate heads | **PENDING ROOT RUN** |

## Source audit summary

| Campaign slice | Source mapping | Execution evidence |
|---|---|---|
| 13.1 Contracts complete receipts | All 10 required semantic rows map to existing executable methods; one combined portable-vector caveat is recorded for non-public duplicate-equal events | **PENDING ROOT RUN** |
| 13.2 matching | All 10 required rows map to existing executable methods | **PENDING ROOT RUN** |
| 13.3–13.15 Coordination acceptance | All 13 required rows map to existing executable methods; the live-extension epoch-label caveat is recorded | **PENDING ROOT RUN** |
| Cross-cutting sections 4, 6, 8–12 | Receipt/store identity, retry/resume, source termination, typed unresolved-demand persistence, poison-lane fairness across restart, retained-handler historical-child/barrier extension, reverse-parent propagation, separately retained cyclic successor proof and its three typed failure outcomes, the bounded two-member same-epoch representation case plus larger-merge rejection, SDK, and packaged-stage surfaces are mapped. | **PENDING ROOT RUN** |
| Section 14 | All 17 steps map to the canonical SDK method; fresh-runtime replay scope is stated precisely | **PENDING ROOT RUN** |
| Section 13.16 | No gate outcome is claimed in this source-audit snapshot | **PENDING ROOT RUN** |

The final receipt must update only the execution-evidence column from actual
recorded runs. It must not erase the portable duplicate-event or
literal-frontier source caveats without adding the corresponding executable
fixtures.
