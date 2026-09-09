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
    @Test void createdChildWorksInParentFirstAndSourceFirstSchedules() throws IOException {
        assertEquals(run(false), run(true));
    }

    @Test void sourceCreationCalculatedByParentBeforeIndependentSourcePublication() throws IOException {
        runLocalCreation(false);
    }

    @Test void sourceCreationCalculatedByParentAfterIndependentSourcePublication() throws IOException {
        runLocalCreation(true);
    }

    @Test void localCreationSchedulesHaveIdenticalCommittedHeadsAndReceipts() throws IOException {
        assertEquals(runLocalCreation(false), runLocalCreation(true));
    }

    @Test void gasFailureAfterChildInitializationPublishesNeitherBirthNorParentProgress() throws IOException {
        long fullGas = birthWithBudget(null);
        assertTrue(fullGas > 1);
        birthWithBudget(fullGas - 1);
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
