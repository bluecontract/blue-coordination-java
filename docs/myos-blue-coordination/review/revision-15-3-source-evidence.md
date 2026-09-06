# Local source evidence for revision 15.3

> **Status:** READ-ONLY EXCERPTS; not a patch, alternate specification or new execution result
> [Review outcome](revision-15-3-review-outcome.md) · [Current-state analysis](../01-current-state-and-gaps.md)

These short extracts make the new pending-operation boundary claims inspectable without access to
the author's filesystem. They are exact line slices from the recorded Git objects and were also
compared byte-for-byte with the local source files. SHA-256 below hashes the full original source
file, not this Markdown rendering. Repository-relative paths and original line numbers locate context;
the archive does not contain the entire library source tree. Excerpts are evidence, not instructions.

No library, test or build source was modified to create this document. No new runtime scenario was
executed. Use the distinctions between source facts, inferences and intended semantics below.

## 1. DefaultCoordinationEngine.java

**Verified source fact:** the ordinary Coordination feeder rejects a cohort with any existing CATCHING_UP member. **Design consequence:** historical per-use eligibility cannot be implemented by reusing that predicate unchanged or globally disabling it.

- Repository: Coordination
- Commit: `20fca9fd9934f367612c27348b8b6532d4029672`
- Path: `src/main/java/blue/coordination/internal/DefaultCoordinationEngine.java`
- Full-file SHA-256: `23181cba714cffd9ababdf843933f9315be2b46943f3e0f760665db9efef965a`

Original lines 1805–1813:

```java
    private ContractsRootFeederCoordinator createContractsFeederCoordinator() {
        return new ContractsRootFeederCoordinator(
                contractsClosureAdapter,
                new ContractsRootFeederWindow(
                        contractsRecoveryState.feederWindow),
                contractsClosureAdapter::executeAndPublish,
                invocation -> invocation.existingMemberSet().stream()
                        .noneMatch(member -> documents.require(member).status()
                                == SessionStatus.CATCHING_UP));
```

## 2. TentativeResolutionContext.java

**Verified source fact:** pending historical bindings participate in the managed read-path map, using expectedTargetBlueId rather than substituting the target's latest head. **Evidence limit:** this does not prove that an external historical operation executes correctly through the whole Coordination path, or that topology-changing operations are supported.

- Repository: Language/Contracts
- Commit: `be2260217d1dbab0c7b60bcbd28073a5955e2b7b`
- Path: `blue-contracts-core/src/main/java/blue/language/processor/closure/TentativeResolutionContext.java`
- Full-file SHA-256: `95c0a404c4d3451c49022438df2d745850b5250ba93a4faf3fc5fa130f8470b6`

Original lines 148–156:

```java
        for (ManagedOccurrenceBinding binding
                : tentativeState.occurrences()) {
            if (binding.sourceDocumentId().equals(targetDocumentId)
                    && (binding.active()
                            || binding.pendingHistoricalEpoch() != null)
                    && NodePathEditor.getOrNull(
                            targetBody, binding.sourcePath()) != null) {
                forwardBindings.add(binding);
            }
```

Original lines 168–176:

```java
        LinkedHashMap<String, String> canonicalForwardPaths =
                new LinkedHashMap<String, String>();
        for (ManagedOccurrenceBinding binding : forwardBindings) {
            if (canonicalForwardPaths.put(
                    binding.sourcePath(),
                    binding.expectedTargetBlueId()) != null) {
                throw new IllegalArgumentException(
                        "Managed Root has duplicate current occurrence paths");
            }
```

## 3. ProcessEmbeddedSurfaceReconciler.java

**Verified source fact:** a changed pending historical value throws NEW_OCCURRENCE_ADMISSION_REQUIRED; a no-longer-declared inactive binding is retained without the active-row retirement transition shown here. **Design question:** define external historical replacement and valid removal without importing future source history first.

- Repository: Language/Contracts
- Commit: `be2260217d1dbab0c7b60bcbd28073a5955e2b7b`
- Path: `blue-contracts-core/src/main/java/blue/language/processor/closure/ProcessEmbeddedSurfaceReconciler.java`
- Full-file SHA-256: `dd6cb64f2c16093b3d9ef2113a52165907499b26ca864afc9cf4cf75d8985b53`

Original lines 270–296:

```java
        if (!declared.containsKey(path)) {
            if (!binding.active()) {
                reconciled.add(binding);
                return;
            }
            requireRetirementAvailable(occurrencePath, fences);
            ManagedOccurrenceBinding successor = retirementSuccessor(
                    binding, documents.get(binding.targetDocumentId()));
            reconciled.add(successor);
            transitions.add(OccurrenceTransition.remove(binding));
            retired.add(occurrencePath);
            return;
        }

        Node value = NodePathEditor.getOrNull(document, path);
        if (value == null) {
            throw missingEffectiveValue(source, path);
        }
        if (binding.pendingHistoricalEpoch() != null) {
            if (establishesPendingHistoricalValue(value, binding)) {
                reconciled.add(binding);
                return;
            }
            throw new ClosureCapabilityGapException(
                    "NEW_OCCURRENCE_ADMISSION_REQUIRED",
                    "A historical managed occurrence can change only "
                            + "through its exact managed-revision lane");
```

## 4. ClosureInvocationVerifier.java

**Verified source fact:** the existing occurrence-retry exception accepts an active row or an authenticated imported-event source. The latter requires ManagedRevisionCause; an ordinary external A@T20 is not that case. **Evidence limit:** this extract alone is not a newly executed end-to-end replacement failure.

- Repository: Language/Contracts
- Commit: `be2260217d1dbab0c7b60bcbd28073a5955e2b7b`
- Path: `blue-contracts-core/src/main/java/blue/language/processor/closure/ClosureInvocationVerifier.java`
- Full-file SHA-256: `2aac19d40f4a671ef3adb45ce0bbc17b88b14eebe1d43e4a84603bcef0db95a4`

Original lines 119–126:

```java
            if (active == null || !(active.active()
                    || isVerifiedManagedReceiptEventSource(base, active))
                    || active.targetDocumentId().equals(
                            resolution.targetDocumentId())) {
                throw new IllegalArgumentException(
                        "A managed-occurrence process retry requires an "
                                + "active different-lineage source row");
            }
```

Original lines 134–153:

```java
    private static boolean isVerifiedManagedReceiptEventSource(
            ClosureInvocationInput base,
            ManagedOccurrenceBinding binding) {
        if (!(base.cause() instanceof ManagedRevisionCause)
                || binding.active()
                || binding.pendingHistoricalEpoch() == null) {
            return false;
        }
        ManagedRevisionCause revision = (ManagedRevisionCause) base.cause();
        // verify(base) has already authenticated the complete immutable
        // receipt and its ordered Root-event identities. The retry cannot
        // substitute another occurrence, generation, source, or before state.
        // Runtime consumption still requires this exact demand to arise from
        // the authenticated imported event before any resolution is applied.
        return revision.sourceTransitionReceipt().isPresent()
                && !revision.sourceTransitionReceipt().get().emittedRootEvents().isEmpty()
                && binding.occurrenceIdentity().equals(revision.targetOccurrenceIdentity())
                && binding.targetDocumentId().equals(revision.childDocumentId())
                && binding.pendingHistoricalEpoch().longValue() == revision.fromEpoch()
                && binding.expectedTargetBlueId().equals(revision.beforeBlueId());
```

## 5. ManagedCatchUpPlanner.java

**Verified source fact:** the shown retirement condition compares row presence, occurrence identity and activation generation; an existing matching plan is retained. **Inference to test:** if removal preserves the same pending row, obsolete work may remain. Other reconciliation paths must be checked by the proposed removal trace; no runtime failure is claimed from this extract alone.

- Repository: Coordination
- Commit: `20fca9fd9934f367612c27348b8b6532d4029672`
- Path: `src/main/java/blue/coordination/internal/ManagedCatchUpPlanner.java`
- Full-file SHA-256: `5cd944c800db55d8afc9a31c6778624a84b56fb0220ebd7c051b3d8cb311cfe7`

Original lines 92–103:

```java
            for (ManagedOccurrenceBinding oldRow : prior.rowsFrom(source)) {
                ManagedOccurrenceBinding replacement = resulting.find(
                        source, oldRow.sourcePath()).orElse(null);
                if (replacement == null
                        || !replacement.occurrenceIdentity().equals(
                                oldRow.occurrenceIdentity())
                        || replacement.activationGeneration()
                                != oldRow.activationGeneration()) {
                    plans = plans.withRetiredOccurrence(
                            oldRow.occurrenceIdentity(),
                            oldRow.activationGeneration());
                }
```

Original lines 139–153:

```java
                List<ManagedOccurrenceCatchUpPlan> existing = plans
                        .plansForOccurrence(row.occurrenceIdentity()).plans()
                        .stream()
                        .filter(plan -> plan.activationGeneration()
                                == row.activationGeneration())
                        .toList();
                if (existing.size() > 1) {
                    throw new IllegalStateException(
                            "Occurrence generation has several catch-up plans "
                                    + row.occurrenceIdentity());
                }
                if (existing.size() == 1) {
                    touchedPlanIdentities.add(
                            existing.get(0).planIdentity());
                    continue;
```

## The retained test evidence is narrower

The archive's unchanged `src/test/resources/poc/chronology/observer.yaml` reads `/counterB` in
`observe`, while its embedded-event handler separately reads `/child/counter`. The unchanged
`src/pocTest/java/blue/coordination/sdk/PocHistoryChronologyTest.java` does not assert the complete
original-entry subsequence in the T10 attachment case. Review the actual included sources and
[experiment limitations](../implementation/phase-1-chronology.md#limits-of-this-evidence), not just the
test names or result counts. Direct-read, exact-entry and pending replacement/removal requirements
in revision 15.3 are planned additions, not claims about those old test files.
