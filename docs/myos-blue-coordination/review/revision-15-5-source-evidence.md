# Source evidence for the critical r15.4 follow-up

> **Revision:** 15.5 · **Status:** inspected baseline source and test assertions, not new executions
> [Outcome](revision-15-5-review-outcome.md) · [Edge contracts](../19-semantic-edge-contracts.md)

All full local files below were compared byte-for-byte with the named Git objects. SHA-256 identifies
the complete original file, not its excerpts. Repository roots are recorded in
[provenance](../00-conventions-and-provenance.md). This appendix is not the full repositories or a
new remote freshness check. Read the named complete methods when validating a source-dependent
claim; abbreviated snippets alone are not a complete algorithm or proof of the proposed behavior.

## 1. ClosureExecutionSession.java

Current initialization inherits activating work; emitted IDs bind the invocation. Retained import queues the source events against one captured target; exact retirement successors can remain valid. Full import path at362–450 updates the source reference and drains reference-update work before event delivery. Proposed canonical source initialization must change the owning constructor, not use a first-insert winner.

- Repository: `blue-language-java` at `be2260217d1dbab0c7b60bcbd28073a5955e2b7b`
- Path: `blue-contracts-core/src/main/java/blue/language/processor/closure/ClosureExecutionSession.java`
- Full-file SHA-256: `47f3db7a75a9a0f93936803e72b560ead5110c8c13b5186cd0bc8e3765b5ec5e`

Original lines 1089–1102:

```java
        if (activated.isEmpty()) {
            return;
        }
        String causeIdentity = Objects.requireNonNull(
                owner, "owner").workIdentity();
        for (ManagedOccurrenceBinding binding : currentBindings) {
            if (!binding.active()
                    || !activated.contains(binding.occurrenceIdentity())) {
                continue;
            }
            DocumentId target = binding.targetDocumentId();
            if (initializationRequired(target)) {
                pendingInitializationCauses.putIfAbsent(
                        target, causeIdentity);
```

Original lines 1580–1589:

```java
        Node exactEvent = Objects.requireNonNull(event, "event").clone();
        long eventOrdinal = nextEventOrdinal++;
        String occurrenceIdentity = IDENTITIES.eventOccurrenceIdentity(
                input.invocationIdentity(), eventOrdinal, eventBlueId);
        ManagedDocumentSnapshot emitter = currentSnapshot.managedDocument(
                frame.work.targetDocumentId());
        Long count = managedRootEventCounts.get(emitter.documentId());
        long receiptOrdinal = count == null ? 0L : count.longValue();
        managedRootEventCounts.put(
                emitter.documentId(), Long.valueOf(receiptOrdinal + 1L));
```

Original lines 428–450:

```java
        for (ManagedRootEventOccurrence event
                : receipt.emittedRootEvents()) {
            eventQueue.addLast(new EmittedOccurrence(
                    event.occurrenceOrdinal(),
                    event.eventBlueId(),
                    event.occurrenceIdentity(),
                    event.sourceDocumentId(),
                    event.exactEvent(),
                    Collections.singletonList(target),
                    null,
                    true));
            charge(
                    "processor",
                    "internalEventEnqueued",
                    1L,
                    documentContext(
                            currentSnapshot.managedDocument(
                                    target.sourceDocumentId()),
                            null,
                            "managed-revision.event."
                                    + event.ordinal() + ".enqueue"));
        }
        drainCausalWork();
```

Original lines 2014–2020:

```java
        if (current == null) {
            if (successor != null
                    && importedEvent != null
                    && exactManagedRevisionRetirementSuccessor(
                            revision, frozen, successor)) {
                return;
            }
```

## 2. ActivationPolicy.java

FROM_NOW explicitly describes new lineage birth. It must not be silently recast as an observer-only history filter. Concurrent incompatible source bases remain a review question.

- Repository: `blue-coordination-java` at `20fca9fd9934f367612c27348b8b6532d4029672`
- Path: `src/main/java/blue/coordination/sdk/ActivationPolicy.java`
- Full-file SHA-256: `41640cefe6dc5e90d0753e54527acec8b5dace566d557ec5fb331314a67d185c`

Original lines 8–21:

```java
/** Temporal admission policy for a managed document occurrence. */
public final class ActivationPolicy {
    /** Stable SDK vocabulary for the supported Contracts 1.0 policies. */
    public enum Kind {
        /** A new managed lineage begins when it is attached. */
        FROM_NOW,
        /** An imported lineage is eligible from complete retained history. */
        IMPORT_FULL_HISTORY,
        /** An imported lineage is eligible after an exact persisted frontier. */
        IMPORT_FROM_FRONTIER,
        /** An existing lineage is already current through attachment. */
        ATTACH_CURRENT_STATE,
        /** An immutable value is evidence only and is not a live process. */
        PASSIVE_SNAPSHOT
```

## 3. BexConformancePropertyTest.java

Existing test assertions require identical result/gas for warm/cold provider and compiled-cache variants. This is inspected test source, not a test run or proof of the proposed Coordination initialization boundary.

- Repository: `blue-bex-java` at `42691919f8a4b9f114d6b0e591d2cf9befc87ada`
- Path: `src/test/java/blue/bex/conformance/BexConformancePropertyTest.java`
- Full-file SHA-256: `8c0fbcc8eee84e2054a73beb80c861f2554fde23e8aa4f40817d292aec93e4c5`

Original lines 109–115:

```java
        assertEquals(0, warmBatched.providerWarmupNodeLoads);
        assertEquals(1, warmBatched.providerWarmupBatchLoads);
        assertEquals(0, warmBatched.providerRuntimeNodeLoads);
        assertEquals(0, warmBatched.providerRuntimeBatchLoads);
        assertTrue(warmBatched.providerRuntimeCacheHits > 0);
        assertEquals(coldUnbatched.result, warmBatched.result);
        assertEquals(coldUnbatched.gasTrace, warmBatched.gasTrace);
```

Original lines 134–140:

```java
                engine.execute(warmProgram, defaultContext());

        assertEquals(cold.value().toSimple(), warm.value().toSimple());
        assertEquals(traceSignature(cold), traceSignature(warm));
        assertEquals(cold.gasUsed(), warm.gasUsed());
        assertEquals(1L, observed.get(0).compileCacheMisses());
        assertEquals(1L, observed.get(2).compileCacheHits());
```

## 4. SdkAcceptanceTest.java

The complete detachBreaksTheLoopAndTheLaterCallTerminates test at578–687 performs failed startLoop, successful detach, then a NEW successful startLoop. Failed state/epoch0 is retained; detach establishes epoch1; the next successful A operation establishes epoch2. This existing control is not failed-managed-import recovery.

- Repository: `blue-coordination-java` at `20fca9fd9934f367612c27348b8b6532d4029672`
- Path: `src/test/java/blue/coordination/sdk/SdkAcceptanceTest.java`
- Full-file SHA-256: `9295291dc97cf2adbfabc7de12a2fb8e4f11d47158396a73ca86743180041fb2`

Original lines 599–617:

```java
            // then
            assertEquals(EntryDisposition.GAS_LIMIT_EXCEEDED,
                    rejected.disposition());
            assertEquals(1, rejected.closures().size());
            assertEquals(rejected.stats(),
                    rejected.closures().get(0).stats());
            assertTrue(rejected.diagnostic().present());
            assertTrue(rejected.closures().get(0).changes().isEmpty());
            assertTrue(rejected.publicEvents().isEmpty());
            assertTrue(rejected.closures().get(0).publicEvents().isEmpty());
            assertEquals(0L, rejected.stats().committedTransitions());
            assertEquals(2L, rejected.stats().documentsOpened());
            assertEquals(expectedAlternatingLoopOrder(
                            scenario.a().id(), scenario.b().id(), 712),
                    rejected.stats().documentStepOrder());
            assertExactGas(rejected.stats(), 99_997L);
            assertEquals(initial, blueIds(handles));
            handles.values().forEach(handle ->
                    assertCurrentHistory(handle, 0L));
```

Original lines 619–625:

```java
                    handles, List.of(scenario.a().id(), scenario.b().id())));

            EntryResult detached = coordination.operations()
                    .on(scenario.b())
                    .from(scenario.controlTimeline())
                    .call("detach")
                    .through("controlChannel")
```

Original lines 670–680:

```java
            assertExactGas(accepted.stats(), 707L);
            assertExactPublicEvents(
                    coordination,
                    accepted.publicEvents(),
                    List.of(new ExpectedPublicEvent(
                            scenario.a().id(), "LOOP")));
            assertExactChangeEvidence(
                    accepted,
                    Map.of(scenario.a().id(), scenario.a()),
                    afterDetach,
                    Map.of(scenario.a().id(), 2L));
```

## 5. ManagedEpochApplicationExecutor.java

Noncommitting managed import returns without application publication. A separate representation-only rebind path preserves the business epoch. No source-receipt skipping or new business receipt is implied.

- Repository: `blue-coordination-java` at `20fca9fd9934f367612c27348b8b6532d4029672`
- Path: `src/main/java/blue/coordination/internal/ManagedEpochApplicationExecutor.java`
- Full-file SHA-256: `3596e99a2bf3fd998019c9caa0c35e57f84df51dfee1d9726de18390f2f9699b`

Original lines 185–201:

```java
            if (!attempt.isComplete()
                    || !attempt.processResult().commits()) {
                ContractsClosureAdapter.ManagedApplicationOutcome outcome =
                        new ContractsClosureAdapter
                                .ManagedApplicationOutcome(
                                selected,
                                attempt,
                                null,
                                false,
                                false,
                                automatic.expansionCount(),
                                automatic.automaticResolutionStopReason(),
                                automatic.unresolvedDemands(),
                                Optional.empty());
                objects.rollbackTo(attemptMark);
                attemptMarkClosed = true;
                return outcome;
```

Original lines 492–504:

```java
                if (componentRepresentationRebind) {
                    transaction.stageIndirectComponentRepresentationRebind(
                            invocation.publicationIdentityMembers(),
                            after,
                            layout,
                            activeSubscriptionsAfter,
                            transition);
                    resultingHeads.put(
                            entry.getKey(),
                            new ManagedCatchUpPlanner.Head(
                                    before.head().epoch(),
                                    after.afterBlueId()));
                    continue;
```

## 6. ManagedEpochReceiptStore.java

Representation rebind advances exact component representation without creating or replacing a managed epoch receipt. Inspect the full138–184 method and corresponding existing store test for its validation.

- Repository: `blue-coordination-java` at `20fca9fd9934f367612c27348b8b6532d4029672`
- Path: `src/main/java/blue/coordination/internal/ManagedEpochReceiptStore.java`
- Full-file SHA-256: `cb151bfda5d1c83f410e95a76820a824de079d546dd3d76d6cfd65056a57e9ff`

Original lines 138–149:

```java
    /**
     * Advances only the exact component-representation cursor of one source
     * lineage. No managed epoch receipt is created or replaced.
     */
    ManagedEpochReceiptStore withComponentRepresentationRebind(
            DocumentId documentId,
            long epoch,
            String beforeBlueId,
            String afterBlueId,
            ManagedDocumentTransitionReceipt transitionReceipt) {
        DocumentId document = Objects.requireNonNull(
                documentId, "documentId");
```

## 7. ComponentFinalizationKernel.java

The kernel already separates cyclic exact-value encoding from business scheduling/gas/epochs/commit. Joint placeholder finalization is at264–295. This supports reuse, not a claim that all mixed historical/current cycle paths are already solved.

- Repository: `blue-language-java` at `be2260217d1dbab0c7b60bcbd28073a5955e2b7b`
- Path: `blue-contracts-core/src/main/java/blue/language/processor/closure/ComponentFinalizationKernel.java`
- Full-file SHA-256: `c19d972964b704f7fd2a3dd8fae40c431d5fb9a36fe69ecd459afadf8de91387`

Original lines 24–35:

```java
 * Pure exact-identity kernel for finalized managed-document components.
 *
 * <p>The kernel derives the active graph solely from complete occurrence
 * bindings, assigns component generations, and visits SCCs target before
 * source. It rewrites only active occurrence paths. Acyclic singletons use
 * the ordinary Language direct identity path; cyclic components delegate the
 * complete placeholder set to the unchanged Language circular-set
 * finalizer.</p>
 *
 * <p>This class performs no document-step execution, scheduling, gas charge,
 * epoch advance, durable write, or commit.</p>
 */
```

## 8. DynamicCycleDetachIntegrationTest.java

The MyOS command is APPLIED while the entry result is GAS_LIMIT_EXCEEDED; runtime/epoch/occurrence evidence is unchanged, then detach succeeds. The separate manual-acceptance/retained-managed-epoch/12-cycle-gas-rollback-detach scenario finishes catch-up before the live failure; it is not proof of recovery from failed receipt import.

- Repository: `myos-simple` at `2fe59d3d67f0db9e93abc5b1891a4af231665953`
- Path: `src/test/java/blue/myos/mini/command/DynamicCycleDetachIntegrationTest.java`
- Full-file SHA-256: `9b1b89cde4887536ba48ddb844bb914a06bc0e33d0cd3354ea7bc02d5279e9e0`

Original lines 243–260:

```java
        EntryResultRecord loopResult = onlyResult(loop);
        assertThat(loop.status()).isEqualTo(CommandStatus.APPLIED);
        assertThat(loopResult.disposition())
                .isEqualTo(EntryDisposition.GAS_LIMIT_EXCEEDED);
        assertThat(json.tree(loopResult.statsJson()).path("gas").asLong())
                .isEqualTo(EXPECTED_GAS_AT_LIMIT);
        assertThat(runtimeEvidence(aDocumentId)).isEqualTo(beforeLoopA);
        assertThat(runtimeEvidence(bDocumentId)).isEqualTo(beforeLoopB);
        assertThat(epochs.count()).isEqualTo(epochCountBeforeLoop);
        assertThat(occurrences.count())
                .isEqualTo(activeOccurrenceCountBeforeLoop);
        assertThat(cyclicEvidence.count())
                .isEqualTo(retainedCyclicEvidenceCount);
        assertOccurrence(bDocumentId, "/peer", aDocumentId, 1L, true);

        operation(
                document(bDocumentId),
                "detachA",
```
