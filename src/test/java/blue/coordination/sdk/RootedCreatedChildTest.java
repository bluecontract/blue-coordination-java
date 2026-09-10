package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.internal.CoordinationTestControl;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** An operation-created child must retain its birth and later support independent publication. */
final class RootedCreatedChildTest {
    @Test
    void createdChildWorksInParentFirstAndSourceFirstSchedules() throws IOException {
        // given
        var baseline = run(false);
        // when
        var alternate = run(true);
        // then
        assertEquals(baseline, alternate);
    }

    @Test
    void sourceCreationCalculatedByParentBeforeIndependentSourcePublication() throws IOException {
        // given
        boolean sourceFirst = false;
        // when
        var result = runLocalCreation(sourceFirst);
        // then
        assertEquals(3, result.heads().size());
        assertEquals(3, result.histories().size());
    }

    @Test
    void sourceCreationCalculatedByParentAfterIndependentSourcePublication() throws IOException {
        // given
        boolean sourceFirst = true;
        // when
        var result = runLocalCreation(sourceFirst);
        // then
        assertEquals(3, result.heads().size());
        assertEquals(3, result.histories().size());
    }

    @Test
    void localCreationSchedulesHaveIdenticalCommittedHeadsAndReceipts() throws IOException {
        // given
        var baseline = runLocalCreation(false);
        // when
        var alternate = runLocalCreation(true);
        // then
        assertEquals(baseline, alternate);
    }

    @Test
    void gasFailureAfterChildInitializationPublishesNeitherBirthNorParentProgress() throws IOException {
        // given
        long fullGas = birthWithBudget(null);
        assertTrue(fullGas > 1);
        // when
        long consumed = birthWithBudget(fullGas - 1);
        // then
        assertTrue(consumed < fullGas);
    }

    @Test
    void preBirthEntriesStayExcludedAcrossBothLocalCreationSchedules() throws IOException {
        // given
        var baseline = runLocalCreationWithPreBirthEntries(false);
        // when
        var alternate = runLocalCreationWithPreBirthEntries(true);
        // then
        assertEquals(baseline, alternate);
    }

    private static Outcome runLocalCreationWithPreBirthEntries(boolean sourceFirst) throws IOException {
        try (var fixture = new RootedSdkFixture()) {
            var blue = fixture.blue;
            var source = fixture.startYaml(RootedSdkFixture.resource("parent.yaml")
                    .replace("RCP2 Parent", "RCP2 Creator").replace("rcp2/parent", "rcp2/creator"), "rcp2/creator");
            var parent = fixture.start("parent.yaml", "rcp2/parent", Map.of("child", fixture.retain(source)));
            fixture.timelines.put("rcp2/source", blue.timelines().register("rcp2/source", "alice"));
            var initial = blue.values().yaml(RootedSdkFixture.resource("source.yaml"));
            var draft = blue.documents().draft(DocumentId.of(initial.blueId()), initial);
            // Actual retained inputs advance the SDK's clock; no private clock setter is used.
            long base = 1_800_000_000_000_000L;
            var old15 = fixture.appendReference(initial.blueId(), "rcp2/source", "tick", base + 15L, "{}", false);
            var old19 = fixture.appendReference(initial.blueId(), "rcp2/source", "tick", base + 19L, "{}", false);
            assertThrows(blue.coordination.api.CoordinationException.class, () -> blue.documents().require(draft.id()));
            var creation = blue.operations().on(source).from(fixture.timelines.get("rcp2/creator"))
                    .call("attach").through("owner").request(request -> request.managed("child", draft))
                    .expectOccurrence("/child", draft).activation(ActivationPolicy.fromNow()).submit();
            var exactBirth = blue.advanced().auditTimelineEntry(creation.blueId()).orElseThrow();
            assertEquals(base + 20L, exactBirth.timestampMicros());
            assertEquals(base + 15L, blue.advanced().auditTimelineEntry(old15.blueId()).orElseThrow().timestampMicros());
            assertEquals(base + 19L, blue.advanced().auditTimelineEntry(old19.blueId()).orElseThrow().timestampMicros());
            EntryResult sourceBirth = null;
            if (sourceFirst) {
                sourceBirth = blue.processing().processNext(source).entry(creation);
                assertEquals(EntryDisposition.APPLIED, sourceBirth.disposition());
            }
            var priorSourceHead = source.snapshot().blueId();
            var priorSourceHistory = fixture.history(source);
            var local = blue.processing().processNext(parent).entry(creation);
            assertEquals(EntryDisposition.APPLIED, local.disposition(), local.diagnostic().toString());
            assertEquals(priorSourceHead, source.snapshot().blueId());
            assertEquals(priorSourceHistory, fixture.history(source));
            assertEquals(List.of(parent.id()), local.closures().stream()
                    .flatMap(closure -> closure.changes().stream()).map(DocumentChange::documentId).toList());
            var selected = fixture.control.selectedView(parent.id()).managedDocument(
                    new blue.language.processor.closure.DocumentId(draft.id().value()));
            assertNotNull(selected);
            assertTrue(selected.initialized());
            assertEquals(0L, ((Number) selected.document().getProperties().get("counter").getValue()).longValue());
            if (!sourceFirst) {
                assertThrows(blue.coordination.api.CoordinationException.class, () -> blue.documents().require(draft.id()));
                CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
                assertThrows(blue.coordination.api.CoordinationException.class, () -> blue.documents().require(draft.id()));
                sourceBirth = blue.processing().processNext(source).entry(creation);
                assertEquals(EntryDisposition.APPLIED, sourceBirth.disposition());
            }
            var child = blue.documents().require(draft.id());
            assertEquals(initial.blueId(), child.id().value());
            assertEquals(initial.blueId(), blue.advanced().auditDocument(child.id()).authoredInitialBlueId());
            assertEquals(selected.blueId(), child.snapshot().blueId());
            assertEquals(0L, child.snapshot().longAt("/counter"));
            var birth = blue.advanced().auditManagedEpoch(child.id(), 0L).orElseThrow();
            assertEquals(DocumentRevision.Kind.INITIALIZATION, birth.kind());
            assertTrue(birth.beforeBlueId().isEmpty());
            assertEquals(1, fixture.history(child).size());
            var result = blue.advanced().closureExecution(java.util.Objects.requireNonNull(sourceBirth)
                    .closures().get(0).closureId()).orElseThrow();
            assertEquals(result.platformCommitCompanion().companionIdentity(), birth.commitCompanionIdentity());
            var input = blue.advanced().closureInvocation(sourceBirth.closures().get(0).closureId()).orElseThrow();
            assertEquals(input.cause().causeIdentity(), birth.originalCauseIdentity());
            var basis = fixture.control.historyBasis(child.id());
            var admission = (Map<?, ?>) basis.get("admission");
            assertEquals("CREATED_IN_OPERATION", admission.get("mode"));
            assertEquals(result.rootedProjection().invocationIdentity(), admission.get("creatorOperationIdentity"));
            var order = ((blue.language.processor.closure.ExternalEventCause) input.cause()).sourceOrder().components();
            assertEquals(order, birth.sourceOrder().orElseThrow().components());
            assertEquals(Map.of("timestampUs", Long.toString(base + 20L), "timelineBlueId", order.get(1),
                    "entryBlueId", creation.blueId()), admission.get("lowerExclusiveOrder"));
            assertTrue(result.occurrenceBindings().stream().anyMatch(row -> row.active()
                    && row.occurrenceIdentity().equals(admission.get("birthOccurrenceIdentity"))
                    && row.sourceDocumentId().value().equals(source.id().value())
                    && row.targetDocumentId().value().equals(child.id().value()) && row.sourcePath().equals("/child")));
            var heads = List.of(parent.snapshot().blueId(), source.snapshot().blueId(), child.snapshot().blueId());
            var histories = List.of(fixture.history(parent), fixture.history(source), fixture.history(child));
            var journal = blue.advanced().auditTimelineEntries().stream().map(TimelineEntrySnapshot::blueId).toList();
            assertTrue(journal.containsAll(List.of(old15.blueId(), old19.blueId(), creation.blueId())));
            for (var root : List.of(child, source, parent)) assertTrue(blue.processing().processNext(root).quiescent());
            assertEquals(heads, List.of(parent.snapshot().blueId(), source.snapshot().blueId(), child.snapshot().blueId()));
            assertEquals(histories, List.of(fixture.history(parent), fixture.history(source), fixture.history(child)));
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertEquals(basis, fixture.control.historyBasis(child.id()));
            assertEquals(journal, blue.advanced().auditTimelineEntries().stream().map(TimelineEntrySnapshot::blueId).toList());
            for (var root : List.of(child, source, parent)) assertTrue(blue.processing().processNext(root).quiescent());
            assertEquals(heads, List.of(parent.snapshot().blueId(), source.snapshot().blueId(), child.snapshot().blueId()));
            assertEquals(histories, List.of(fixture.history(parent), fixture.history(source), fixture.history(child)));
            System.out.println("PRE_BIRTH_EXCLUSION_PROVED sourceFirst=" + sourceFirst + " authored=" + initial.blueId()
                    + " born=" + child.snapshot().blueId() + " old15=" + old15.blueId() + " old19=" + old19.blueId()
                    + " birth=" + creation.blueId() + " birthOrder=" + order);
            return new Outcome(heads, histories);
        }
    }

    private static long birthWithBudget(Long budget) throws IOException {
        try (var fixture = new RootedSdkFixture()) {
            var blue = fixture.blue;
            var parent = fixture.start("parent.yaml", "rcp2/parent", Map.of());
            var initial = blue.values().yaml(RootedSdkFixture.resource("source.yaml"));
            var draft = blue.documents().draft(DocumentId.of(initial.blueId()), initial);
            var creation = blue.operations().on(parent).from(fixture.timelines.get("rcp2/parent"))
                    .call("attach").through("owner").request(request -> request.managed("child", draft))
                    .expectOccurrence("/child", draft).activation(ActivationPolicy.fromNow()).submit();
            var head = parent.snapshot().blueId();
            var history = fixture.history(parent);
            var result = budget == null ? blue.processing().processNext(parent).entry(creation)
                    : blue.advanced().process(parent, creation,
                        ContractsExecutionPolicy.exactSharedGas(budget, "rooted-birth-boundary")).entry(creation);
            var execution = blue.advanced().closureExecution(result.closures().get(0).closureId()).orElseThrow();
            if (budget != null) {
                assertEquals(EntryDisposition.GAS_LIMIT_EXCEEDED, result.disposition());
                assertTrue(execution.rollbackToInput());
                assertNotNull(execution.rejectedCharge());
                assertNull(execution.commitCompanion());
                assertEquals(head, parent.snapshot().blueId());
                assertEquals(history, fixture.history(parent));
                assertThrows(blue.coordination.api.CoordinationException.class, () -> blue.documents().require(draft.id()));
                assertTrue(result.publicEvents().isEmpty());
                CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
                assertEquals(head, parent.snapshot().blueId());
                assertEquals(history, fixture.history(parent));
                assertThrows(blue.coordination.api.CoordinationException.class, () -> blue.documents().require(draft.id()));
            } else assertEquals(EntryDisposition.APPLIED, result.disposition());
            return execution.totalGas();
        }
    }

    private static Outcome runLocalCreation(boolean sourceFirst) throws IOException {
        try (var fixture = new RootedSdkFixture()) {
            var blue = fixture.blue;
            var source = fixture.startYaml(RootedSdkFixture.resource("parent.yaml")
                    .replace("RCP2 Parent", "RCP2 Creator").replace("rcp2/parent", "rcp2/creator"), "rcp2/creator");
            var parent = fixture.start("parent.yaml", "rcp2/parent", Map.of("child", fixture.retain(source)));
            fixture.timelines.put("rcp2/source", blue.timelines().register("rcp2/source", "alice"));
            var initial = blue.values().yaml(RootedSdkFixture.resource("source.yaml"));
            var draft = blue.documents().draft(DocumentId.of(initial.blueId()), initial);
            var creation = blue.operations().on(source).from(fixture.timelines.get("rcp2/creator"))
                    .call("attach").through("owner").request(request -> request.managed("child", draft))
                    .expectOccurrence("/child", draft).activation(ActivationPolicy.fromNow()).submit();
            if (sourceFirst) assertEquals(EntryDisposition.APPLIED,
                    blue.processing().processNext(source).entry(creation).disposition());
            var sourceBefore = fixture.history(source);
            var local = blue.processing().processNext(parent).entry(creation);
            assertEquals(EntryDisposition.APPLIED, local.disposition(), local.diagnostic().toString());
            assertEquals(sourceBefore, fixture.history(source));
            assertEquals(List.of(parent.id()), local.closures().stream()
                    .flatMap(closure -> closure.changes().stream()).map(DocumentChange::documentId).toList());
            var selected = fixture.control.selectedView(parent.id()).managedDocument(
                    new blue.language.processor.closure.DocumentId(draft.id().value()));
            assertNotNull(selected, "The local calculation retains the created exact child");
            assertTrue(selected.initialized());
            if (!sourceFirst) {
                assertThrows(blue.coordination.api.CoordinationException.class, () -> blue.documents().require(draft.id()),
                        "A local calculation cannot publish the source's birth");
                CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
                assertThrows(blue.coordination.api.CoordinationException.class, () -> blue.documents().require(draft.id()));
                assertEquals(EntryDisposition.APPLIED, blue.processing().processNext(source).entry(creation).disposition());
            }
            var child = blue.documents().require(draft.id());
            assertEquals(selected.blueId(), child.snapshot().blueId());
            assertEquals(1, fixture.history(child).size());
            var heads = List.of(parent.snapshot().blueId(), source.snapshot().blueId(), child.snapshot().blueId());
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertEquals(heads, List.of(parent.snapshot().blueId(), source.snapshot().blueId(), child.snapshot().blueId()));
            assertTrue(blue.processing().processNext(parent).quiescent());
            assertTrue(blue.processing().processNext(source).quiescent());
            return new Outcome(heads, List.of(fixture.history(parent), fixture.history(source), fixture.history(child)));
        }
    }

    private static Outcome run(boolean sourceFirst) throws IOException {
        try (var fixture = new RootedSdkFixture()) {
            var blue = fixture.blue;
            var parent = fixture.start("parent.yaml", "rcp2/parent", Map.of());
            fixture.timelines.put("rcp2/source", blue.timelines().register("rcp2/source", "alice"));
            var initial = blue.values().yaml(RootedSdkFixture.resource("source.yaml"));
            var draft = blue.documents().draft(DocumentId.of(initial.blueId()), initial);
            var birthEntry = blue.operations().on(parent).from(fixture.timelines.get("rcp2/parent"))
                    .call("attach").through("owner")
                    .request(request -> request.managed("child", draft))
                    .expectOccurrence("/child", draft).activation(ActivationPolicy.fromNow()).submit();
            var created = blue.processing().processNext(parent).entry(birthEntry);
            assertEquals(EntryDisposition.APPLIED, created.disposition(), created.diagnostic().toString());
            var child = blue.documents().require(draft.id());
            assertEquals(List.of(parent.id(), child.id()).stream().sorted().toList(), created.closures().stream()
                    .flatMap(c -> c.changes().stream()).map(DocumentChange::documentId).sorted().toList());
            assertEquals(0L, child.snapshot().longAt("/counter"));
            var birth = blue.advanced().auditManagedEpoch(child.id(), 0).orElseThrow();
            assertEquals(DocumentRevision.Kind.INITIALIZATION, birth.kind());
            assertTrue(birth.beforeBlueId().isEmpty(), "Epoch zero has no predecessor epoch");
            assertEquals(initial.blueId(), blue.advanced().auditDocument(child.id()).authoredInitialBlueId());
            long birthTime = blue.advanced().auditTimelineEntry(birthEntry.blueId()).orElseThrow().timestampMicros();
            var historyBasis = fixture.control.historyBasis(child.id());
            assertEquals("CREATED_IN_OPERATION", ((Map<?, ?>) historyBasis.get("admission")).get("mode"));
            var tick = fixture.append(child, "rcp2/source", "tick", birthTime + 100, "{}");
            if (sourceFirst) assertEquals(EntryDisposition.APPLIED, blue.processing().process(child, tick).entry(tick).disposition());
            var sourceBefore = fixture.history(child);
            var observed = blue.processing().process(parent, tick).entry(tick);
            assertEquals(EntryDisposition.APPLIED, observed.disposition(), observed.diagnostic().toString());
            assertEquals(sourceBefore, fixture.history(child), "The parent's local child calculation cannot publish the independent source");
            assertEquals(1L, parent.snapshot().longAt("/seen"));
            var parentHead = parent.snapshot().blueId();
            if (!sourceFirst) assertEquals(EntryDisposition.APPLIED, blue.processing().process(child, tick).entry(tick).disposition());
            assertEquals(parentHead, parent.snapshot().blueId());
            assertEquals(1L, child.snapshot().longAt("/counter"));
            assertEquals(birth.receiptIdentity(), blue.advanced().auditManagedEpoch(child.id(), 0).orElseThrow().receiptIdentity());
            assertEquals(2, fixture.history(child).size(), "One birth and one source publication");
            var heads = List.of(parent.snapshot().blueId(), child.snapshot().blueId());
            var histories = List.of(fixture.history(parent), fixture.history(child));
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertEquals(heads, List.of(parent.snapshot().blueId(), child.snapshot().blueId()));
            assertEquals(histories, List.of(fixture.history(parent), fixture.history(child)));
            assertEquals(historyBasis, fixture.control.historyBasis(child.id()));
            assertTrue(blue.processing().processNext(parent).quiescent());
            assertTrue(blue.processing().processNext(child).quiescent());
            return new Outcome(heads, histories);
        }
    }

    private record Outcome(List<String> heads, List<List<String>> histories) { }
}
