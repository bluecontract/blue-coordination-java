# Empty-object and embedded-collection legacy test map

The implementation prompt's section 51 lists conceptual legacy test names.
Those names are not classes in this repository. The behaviors are split across
the maintained tests below. This map is audit evidence; it does not declare an
alias and does not preserve a deprecated test API.

| Section 51 review name | Maintained replacement evidence |
| --- | --- |
| `CoordinationCollectionSubscriptionLifecycleTest` | `ClosureSubscriptionInventoryTest#retainsProcessorOnlyEmbeddedDemandByExactSourcePath`, `#embeddedDemandModesMatchDecodedPointerSegments`, `#sameCollectionPathRetainsEveryModeRegardlessOfInsertionOrder`, and `#processorChannelCannotSpoofCollectionDemandWithDispatchField` cover authenticated demand retention and matching. `SdkStaticProcessEmbeddedAdmissionTest#collectionDuplicatesShareOneLineageAndKeepTwoOccurrences` covers admission lifecycle. |
| `CoordinationPublicCollectionPlatformLifecycleTest` | `ContractsRootSourceSurfaceTest#prospectiveMemberUnderAbsentCollectionStaysOutsideActiveSurface`, `#committedActivationRecomputesTheRootSourceSurface`, and `ContractsActiveSourceTimelineIndexTest#collectionActivationRecomputesFromCommittedActiveRows` cover public source-surface activation. `ContractsPublicBranchingCollectionCycleTest#sharedAnchorCollectionCycleConvergesOnceInCanonicalOrder` covers cyclic publication. |
| `CoordinationNestedIndexedCurrentRootDeliveryEquivalenceTest` | `SdkNestedExistingSourceEpochTest#literalAuthoredNestedSourceRemainsExactWhileAnotherConsumerReplaysIt`, `#b3IntroducesRetainedCAndACatchesUpWithoutReprocessingEitherSource`, and `#sameStateNestedSourceReplayReadsThroughHistoricalChildReference` cover indexed nested-source delivery and replay. `SdkPendingNestedSourceReadinessTest#bOwnedPendingCBlocksAUntilItsReadyFrontierAcrossRestartAndReceiptRecovery` covers incomplete readiness. |
| `CoordinationSubscriptionProjectorTest` | `ContractsRootSourceSurfaceTest#rootLaneOwnsUnionOfRootAndActiveEmbeddedTimelinesOnly` and `ContractsActiveSourceTimelineIndexTest#descendantChangeRefreshesEveryContainingPublicRoot` cover projection and recomputation. `ContractsClosureAdapterTest#collectionDirectDemandSelectsOnlyDirectMemberParent`, `#collectionDescendantDemandSelectsParentAcrossIntermediateMember`, and `#collectionDemandUsesDecodedEscapedSegmentsNotRawPrefixes` cover reverse-parent selection. |
| `RuntimeChannelsTest` | `RoutingSurfaceInternalChannelTest#processorManagedChannelNeverBecomesAnExternalRoute`, `#onlyTimelineChannelEntersMixedExternalProviderSurface`, and `#coordCollection05RuntimeCatalogAuthenticatesMixedChannelRoles` cover internal/external channel roles and mixed-channel authentication. |
| `OperationRequestLogicalRoutingTest` | `WholeRequestEntryFactoryPresenceTest#absentAndExactEmptyRequestsRemainDifferentExactEntries`, `#exactEmptyMandateConstraintIsPresentAndFailsClosed`, `#exactEmptyRequestBodyHasInlineAndVerifiedReferenceParity`, `#unavailableReferencedRequestRemainsIncomplete`, and `#coordEmpty04And05KeepBexNullAndEmptyRequestDistinct` cover request construction and matching. `SdkOperationRouteAuditTest#auditKeepsAbsentAndExactEmptyRequestPatternsDistinct` covers the public audit projection. |

The broader scenario search required by section 51 remains represented by the
Wadowice/NBA/cyclic/catch-up/retry suites and by the exact eleven-ID manifest
in `gradle/verification/coordination-empty-object-coverage.json`. Identity or
result changes must be classified in their owning tests; snapshot rewrites are
not accepted as evidence by this map.
