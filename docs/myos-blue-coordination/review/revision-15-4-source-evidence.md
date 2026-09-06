# Source evidence for independent-lineage boundary review

> **Revision:** 15.4 · **Status:** exact read-only baseline excerpts; not a runtime patch or newly executed test
> [Current outcome](revision-15-4-review-outcome.md) · [Design](../18-independent-lineage-processing.md)

These slices were read from the recorded Git objects and compared byte-for-byte with local files.
Hashes identify each full original file, not the excerpt. Coordination baseline is
`20fca9fd9934f367612c27348b8b6532d4029672`; Language baseline is
`be2260217d1dbab0c7b60bcbd28073a5955e2b7b`. The archive is not the complete repository.

The source is evidence of the current boundary to change. It does not override the user's required
independent Agreement/Order model or establish that the proposed replacement is already implemented.

## 1. ContractsClosureAdapter.java

Current live selection recursively includes active parents with relevant typed incoming demand. This is an implementation boundary; it does not prove all observers intrinsically need one transaction.

- Repository: blue-coordination-java
- Path: `src/main/java/blue/coordination/internal/ContractsClosureAdapter.java`
- Full-file SHA-256: `a21f8281d8160f980893db767990b453adec3eca6b388c883ba13d432835772e`

Original lines 949–965:

```java
            for (ManagedOccurrenceBinding row
                    : inventory.rowsTouching(current)) {
                DocumentId target = coordinationId(row.targetDocumentId());
                if (!target.equals(current)) {
                    continue;
                }
                DocumentId source = coordinationId(row.sourceDocumentId());
                if (source.equals(current)
                        || !row.active()
                        || !incomingDemand.test(row)
                        || occurrences.putIfAbsent(
                                row.occurrenceIdentity(), row) != null) {
                    continue;
                }
                rowsExamined = Math.addExact(rowsExamined, 1L);
                if (discovered.putIfAbsent(source, Boolean.TRUE) == null) {
                    pending.addLast(source);
```

## 2. ManagedEpochInvocationCapturer.java

Current receipt consumption requires an inactive historically pending occurrence. Reusing the receipt mechanism for active subscribed-but-lagging observers needs an owning-library change, not only another worker.

- Repository: blue-coordination-java
- Path: `src/main/java/blue/coordination/internal/ManagedEpochInvocationCapturer.java`
- Full-file SHA-256: `62b2d63edb0e687bd5f0724a45f6731ffbbf6eaa1654917edba2a737510cd993`

Original lines 125–139:

```java
        boolean historicalPending = !target.active()
                && target.pendingHistoricalEpoch() != null
                && target.pendingHistoricalEpoch().longValue() == fromEpoch;
        if (!target.occurrenceIdentity().equals(
                    work.targetOccurrenceIdentity())
                || target.activationGeneration()
                        != work.activationGeneration()
                || !historicalPending
                || !target.targetDocumentId().value().equals(
                        work.sourceDocumentId().value())
                || !target.expectedTargetBlueId().equals(
                        sourceBeforeBlueId)) {
            throw ContractsClosureAdapter.stale(
                    "Managed application occurrence cursor changed for "
                            + work.workIdentity());
```

Original lines 239–244:

```java
                sourceReceipt.afterDocument(), lineageIndex);
        ManagedRevisionCause cause = sourceTransition == null
                ? ClosureEvidenceFactory.managedRevisionCause(
                        work.targetOccurrenceIdentity(),
                        ContractsClosureAdapter.closureId(
                                work.sourceDocumentId()),
```

## 3. DefaultClosureProcessor.java

Gas exhaustion returns a complete rollback result for the admitted invocation. The revised target must define independent source/consumer scopes; splitting this existing result in MyOS would change semantics without a valid protocol.

- Repository: blue-language-java
- Path: `blue-contracts-core/src/main/java/blue/language/processor/closure/DefaultClosureProcessor.java`
- Full-file SHA-256: `52503b08c69901e9337d0afc05d52110693b3688123980a6c3482058fa9e7969`

Original lines 114–127:

```java
        } catch (GasLimitExceededException rejection) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            ClosureImplementationEvidence evidence =
                    recorder.snapshot(null);
            observer.onExecutionEvidence(evidence);
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.gasFailure(
                            admitted,
                            trace,
                            rejection,
                            evidence.workTrace()));
        } catch (ProcessorFailureException failure) {
```

## 4. ClosureIdentityService.java

Current invocation identity binds closure membership, documents, occurrences and execution policy. The new local scope must exclude unrelated observing siblings and their physical commit order.

- Repository: blue-language-java
- Path: `blue-contracts-core/src/main/java/blue/language/processor/closure/ClosureIdentityService.java`
- Full-file SHA-256: `30c1dd71ba89f9f0491519c96d0bf1af945af646e29bd50e1d332dd515656447`

Original lines 609–630:

```java
    Map<String, Object> invocationIdentityConstructorValue(
            ClosureInvocationInput input) {
        ClosureInvocationInput selected = Objects.requireNonNull(
                input, "input");
        AffectedClosureSnapshot snapshot = selected.snapshot();
        ClosureEnvironment environment = selected.environment();
        LinkedHashMap<String, Object> value = objectValue();
        value.put("operation", selected.operation().wireValue());
        value.put("causeIdentity", selected.cause().causeIdentity());
        value.put("admissionCandidateIdentity",
                selected.admissionCandidateIdentity());
        value.put("inputGraphGeneration", Long.valueOf(
                snapshot.graphGeneration()));
        value.put("inputClosureIdentity", snapshot.closureIdentity());
        value.put("documents", documentValues(snapshot.managedDocuments()));
        value.put("directDeliverySnapshotIdentity",
                selected.directDeliverySnapshotIdentity());
        value.put("occurrenceBindingSetIdentity",
                snapshot.occurrenceBindingSetIdentity());
        value.put("runtimeRegistryIdentity",
                environment.runtimeRegistryIdentity());
        value.put("gasPolicyIdentity", selected.executionPolicy().identity());
```

## 5. ClosureExecutionSession.java

Current public event occurrence identity derives from the invocation identity. Changing scope has exact identity consequences even in successful executions, not only rollback behavior.

- Repository: blue-language-java
- Path: `blue-contracts-core/src/main/java/blue/language/processor/closure/ClosureExecutionSession.java`
- Full-file SHA-256: `47f3db7a75a9a0f93936803e72b560ead5110c8c13b5186cd0bc8e3765b5ec5e`

Original lines 1578–1586:

```java
        ActiveFrame frame = activeFrame();
        frame.applicationEventCount++;
        Node exactEvent = Objects.requireNonNull(event, "event").clone();
        long eventOrdinal = nextEventOrdinal++;
        String occurrenceIdentity = IDENTITIES.eventOccurrenceIdentity(
                input.invocationIdentity(), eventOrdinal, eventBlueId);
        ManagedDocumentSnapshot emitter = currentSnapshot.managedDocument(
                frame.work.targetDocumentId());
        Long count = managedRootEventCounts.get(emitter.documentId());
```

## Scope of inference

The inspected methods support the baseline claims above. They are not an execution of the 1000-Order
case or proof of the new fanout algorithm. The rollback result's all-document restoration is also
visible in Language `ClosureRollbackResultAssembler` (lines106–147 and181–209). Exact gas policy
is in `ExecutionPolicy`. Review these full symbols if a conclusion depends on omitted context;
otherwise mark it UNDECIDED rather than invent missing source behavior.

No runtime source, executable test, Gradle configuration or prior JUnit evidence was changed.
