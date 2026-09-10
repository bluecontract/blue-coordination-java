package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.api.CoordinationException;
import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.internal.BundledContracts10Release;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Real SDK baseline for the literal RCP-RUN-001/002 source operation. */
final class RootedSourceIsolationTest {
    @Test
    void literalSourcePublishesOneTickAndRetainsItAcrossStoreRestart() throws IOException {
        // given
        int observers = 0;
        // when
        var result = verifySource(observers);
        // then
        assertTrue(result.gas() > 0L);
    }

    @Test
    void oneIncomingParentCannotBecomePartOfTheSourcePublication() throws IOException {
        // given
        int observers = 1;
        // when
        var result = verifySource(observers);
        // then
        assertTrue(result.gas() > 0L);
    }

    @Test
    void twoIncomingParentsCannotBecomePartOfTheSourcePublication() throws IOException {
        // given
        int observers = 2;
        // when
        var result = verifySource(observers);
        // then
        assertTrue(result.gas() > 0L);
    }

    @Test
    void parentFirstAdvancesItsViewWithoutPublishingTheIndependentSource() throws IOException {
        // given
        boolean sourceFirst = false;
        // when
        var result = verifyParent(sourceFirst);
        // then
        assertTrue(result.gas() > 0L);
    }

    @Test
    void sourceFirstStillProcessesTheParentsOriginalLiveView() throws IOException {
        // given
        boolean sourceFirst = true;
        // when
        var result = verifyParent(sourceFirst);
        // then
        assertTrue(result.gas() > 0L);
    }

    @Test
    void incomingObserversCannotChangeSourceGasOrSemanticIdentities() throws IOException {
        // given
        ExecutionIdentity sourceOnly = verifySource(0);
        // when
        assertEquals(sourceOnly, verifySource(1));
        // then
        assertEquals(sourceOnly, verifySource(2));
    }

    @Test
    void sourceFirstCannotChangeTheParentsLiveCalculation() throws IOException {
        // given
        var baseline = verifyParent(false);
        // when
        var alternate = verifyParent(true);
        // then
        assertEquals(baseline, alternate);
    }

    @Test
    void aGasFailedSiblingCannotChangeSourceOrParentProcessing() throws IOException {
        // given
        var baseline = verifyParent(true, false);
        // when
        var alternate = verifyParent(true, true);
        // then
        assertEquals(baseline, alternate);
    }

    private static ExecutionIdentity verifyParent(boolean sourceFirst) throws IOException {
        return verifyParent(sourceFirst, false);
    }

    private static ExecutionIdentity verifyParent(boolean sourceFirst, boolean failSibling) throws IOException {
        Map<String, String> exactContent = new LinkedHashMap<>();
        try (BlueCoordination blue = BlueCoordination.builder().contentDerivedDocumentIds()
                .release(BundledContracts10Release.manifest().blueLanguageSpecification(),
                        "sha256:e91381c970859a6bafecdd99e46f5115ba033bf0534be84bbd5582531e9e347f")
                .exactNodeProvider(id -> Optional.ofNullable(exactContent.get(id))).build()) {
            TimelineHandle timeline = blue.timelines().register("rcp2/source", "alice");
            blue.timelines().register("rcp2/parent", "alice");
            DocumentHandle source = blue.documents().admitStaticProcessEmbedded(
                    resource("source.yaml"), ActivationPolicy.importFullHistory()).document("root");
            String sourceInitial = source.snapshot().blueId();
            exactContent.put(sourceInitial, source.snapshot().exact().json());
            DocumentHandle parent = blue.documents().admitStaticProcessEmbedded(resource("parent.yaml")
                    + "\nchild:\n  blueId: " + sourceInitial + "\n",
                    ActivationPolicy.importFullHistory()).document("root");
            DocumentHandle sibling = null;
            String siblingHead = null;
            List<String> siblingHistory = null;
            if (failSibling) {
                blue.timelines().register("rcp2/failing", "alice");
                sibling = blue.documents().admitStaticProcessEmbedded(resource("parent.yaml")
                        .replace("name: RCP2 Parent", "name: RCP2 Failing Observer")
                        .replace("timelineId: rcp2/parent", "timelineId: rcp2/failing")
                        + "\nchild:\n  blueId: " + sourceInitial + "\n",
                        ActivationPolicy.importFullHistory()).document("root");
                siblingHead = sibling.snapshot().blueId();
                siblingHistory = receipts(blue, sibling.id());
            }
            EntryHandle input = blue.events().from(timeline).exact(blue.values().yaml("""
                    type: Coordination/Timeline Entry
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: rcp2/source
                    timestamp: 100
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                    message:
                      type: Coordination/Operation Request
                      operation: tick
                      channel: owner
                      request: {}
                    """)).submit();
            if (sourceFirst) {
                assertEquals(EntryDisposition.APPLIED, blue.processing().process(source, input).entry(input).disposition());
            }
            String sourceHead = source.snapshot().blueId();
            List<String> sourceHistory = receipts(blue, source.id());
            if (failSibling) {
                EntryResult failure = blue.advanced().process(sibling, input,
                        ContractsExecutionPolicy.exactSharedGas(1L, "rcp-one-gas")).entry(input);
                assertEquals(EntryDisposition.GAS_LIMIT_EXCEEDED, failure.disposition(), failure.diagnostic().toString());
                var failedCalculation = blue.advanced().closureExecution(failure.closures().get(0).closureId()).orElseThrow();
                var failedInput = blue.advanced().closureInvocation(failure.closures().get(0).closureId()).orElseThrow();
                assertEquals(failedInput.invocationIdentity(), failedCalculation.invocationIdentity());
                assertEquals(failedInput.snapshot().closureIdentity(), failedCalculation.inputClosureIdentity());
                assertEquals(1L, failedInput.executionPolicy().sharedLimit());
                assertTrue(failedCalculation.rollbackToInput());
                assertNotNull(failedCalculation.rejectedCharge());
                assertNull(failedCalculation.commitCompanion());
                assertTrue(failure.publicEvents().isEmpty());
                assertTrue(failure.closures().stream().allMatch(c -> c.changes().isEmpty()));
                assertEquals(siblingHead, sibling.snapshot().blueId());
                assertEquals(siblingHistory, receipts(blue, sibling.id()));
                assertEquals(sourceHead, source.snapshot().blueId());
                assertEquals(sourceHistory, receipts(blue, source.id()));
            }
            DrainResult drained = blue.processing().process(parent, input);
            EntryResult result = drained.entry(input);
            assertEquals(EntryDisposition.APPLIED, result.disposition(), result.diagnostic().toString());
            assertEquals(1L, drained.stats().committedTransitions(), "Only the owned parent commits a PROCESS revision");
            assertAll(
                    () -> assertEquals(sourceHead, source.snapshot().blueId(), "root-local calculation must not publish S"),
                    () -> assertEquals(sourceHistory, receipts(blue, source.id())),
                    () -> assertEquals(1L, parent.snapshot().longAt("/seen")),
                    () -> assertEquals(1L, ((Number) blue.values().retained(
                            parent.snapshot().valueAt("/child").blueId()).orElseThrow().scalarAt("/counter")).longValue()),
                    () -> assertEquals(List.of(parent.id()), result.closures().stream()
                            .flatMap(c -> c.changes().stream()).map(DocumentChange::documentId).toList()),
                    () -> assertEquals(0, result.publicEvents().stream()
                            .filter(e -> e.sourceDocument().orElseThrow().equals(source.id())).count()),
                    () -> assertTrue(result.stats().gas() > 0));
            String parentHead = parent.snapshot().blueId();
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertEquals(parentHead, parent.snapshot().blueId());
            assertEquals(sourceHead, source.snapshot().blueId());
            assertEquals(sourceHistory, receipts(blue, source.id()));
            if (failSibling) {
                assertEquals(siblingHead, sibling.snapshot().blueId());
                assertEquals(siblingHistory, receipts(blue, sibling.id()));
            }
            return new ExecutionIdentity(result.stats().gas(), result.closures().get(0).closureId(),
                    parentHead, receipts(blue, parent.id()), blue.advanced().closureExecution(
                            result.closures().get(0).closureId()).orElseThrow().gasTraceIdentity());
        }
    }

    private static ExecutionIdentity verifySource(int observerCount) throws IOException {
        Map<String, String> exactContent = new LinkedHashMap<>();
        try (BlueCoordination blue = BlueCoordination.builder().contentDerivedDocumentIds()
                .release(BundledContracts10Release.manifest().blueLanguageSpecification(),
                        "sha256:e91381c970859a6bafecdd99e46f5115ba033bf0534be84bbd5582531e9e347f")
                .exactNodeProvider(id -> Optional.ofNullable(exactContent.get(id))).build()) {
            TimelineHandle timeline = blue.timelines().register("rcp2/source", "alice");
            DocumentHandle source = blue.documents().admitStaticProcessEmbedded(
                    resource("source.yaml"), ActivationPolicy.importFullHistory()).document("root");
            exactContent.put(source.snapshot().blueId(), source.snapshot().exact().json());
            List<DocumentHandle> parents = new ArrayList<>();
            List<String> parentHeads = new ArrayList<>();
            List<List<String>> parentReceipts = new ArrayList<>();
            for (int index = 0; index < observerCount; index++) {
                blue.timelines().register("rcp2/parent/" + index, "alice");
                String yaml = resource("parent.yaml")
                        .replace("name: RCP2 Parent", "name: RCP2 Parent " + index)
                        .replace("timelineId: rcp2/parent", "timelineId: rcp2/parent/" + index)
                        + "\nchild:\n  blueId: " + source.snapshot().blueId() + "\n";
                DocumentHandle parent;
                try {
                    parent = blue.documents().admitStaticProcessEmbedded(
                            yaml, ActivationPolicy.importFullHistory()).document("root");
                } catch (CoordinationException failure) {
                    throw new AssertionError("Parent exact-reference admission: " + failure.details(), failure);
                }
                parents.add(parent);
                parentHeads.add(parent.snapshot().blueId());
                parentReceipts.add(receipts(blue, parent.id()));
            }
            EntryHandle input = blue.events().from(timeline).exact(blue.values().yaml("""
                    type: Coordination/Timeline Entry
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: rcp2/source
                    timestamp: 100
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                    message:
                      type: Coordination/Operation Request
                      operation: tick
                      channel: owner
                      request: {}
                    """)).submit();

            // One journal selection, with no fall-through to downstream retained work.
            EntryResult result = blue.processing().drainJournal(new DrainBudget(1, 1)).entry(input);
            assertEquals(EntryDisposition.APPLIED, result.disposition(), result.diagnostic().toString());
            List<DocumentId> writes = result.closures().stream().flatMap(c -> c.changes().stream())
                    .map(DocumentChange::documentId).toList();
            assertAll(
                    () -> assertEquals(List.of(source.id()), writes, "RCP-OWN-01/04 source owns only itself"),
                    () -> assertEquals(1L, source.snapshot().longAt("/counter")),
                    () -> assertEquals(1, result.publicEvents().size()),
                    () -> assertEquals(source.id(), result.publicEvents().get(0).sourceDocument().orElseThrow()),
                    () -> assertEquals("RCP2/Tick", result.publicEvents().get(0).exact().scalarAt("/kind")),
                    () -> assertTrue(result.stats().gas() > 0));
            for (int index = 0; index < parents.size(); index++) {
                assertEquals(parentHeads.get(index), parents.get(index).snapshot().blueId());
                assertEquals(parentReceipts.get(index), receipts(blue, parents.get(index).id()));
            }
            String head = source.snapshot().blueId();
            List<String> history = receipts(blue, source.id());
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertEquals(head, source.snapshot().blueId());
            assertEquals(history, receipts(blue, source.id()));
            return new ExecutionIdentity(result.stats().gas(), result.closures().get(0).closureId(), head, history, blue.advanced().closureExecution(
                    result.closures().get(0).closureId()).orElseThrow().gasTraceIdentity());
        }
    }

    private record ExecutionIdentity(long gas, String terminalKey, String exactHead, List<String> receiptIdentities,
                                     String gasTraceIdentity) { }

    private static List<String> receipts(BlueCoordination blue, DocumentId id) {
        return blue.advanced().auditManagedEpochs(id).stream()
                .map(ManagedEpochReceipt::receiptIdentity).toList();
    }

    private static String resource(String name) throws IOException {
        try (var input = RootedSourceIsolationTest.class.getResourceAsStream("/rooted/" + name)) {
            if (input == null) throw new IOException("Missing literal rooted source: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
