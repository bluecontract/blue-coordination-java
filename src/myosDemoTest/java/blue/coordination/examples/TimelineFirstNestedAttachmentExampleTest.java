package blue.coordination.examples;

import blue.coordination.examples.documents.NestedTopologyDocuments;
import blue.coordination.examples.support.MyOsDemoActor;
import blue.coordination.examples.support.MyOsDemoDispatch;
import blue.coordination.examples.support.MyOsDemoEntry;
import blue.coordination.examples.support.MyOsDemoOperation;
import blue.coordination.examples.support.MyOsDemoRuntime;
import blue.coordination.examples.support.MyOsDemoTimeline;
import blue.coordination.examples.support.MyOsDemoAssertions;
import blue.coordination.examples.support.MyOsDocumentSlice;
import blue.coordination.examples.support.MyOsInitializationCoordinator;
import blue.coordination.examples.support.MyOsManagedEmbedding;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Required Root -> Emb1 -> Emb2 late-attachment proof. */
final class TimelineFirstNestedAttachmentExampleTest {

    @Test
    void shouldAdoptAnAlreadyProcessedEmb2AndFanOutLaterWorkInChunks() {
        // given
        try (MyOsDemoRuntime demo = MyOsDemoRuntime.create(
                "timeline-first-nested-attachment")) {
            demo.addDocument("emb2", NestedTopologyDocuments.EMB2);
            MyOsDemoTimeline alice = demo.timeline(
                    "examples/nested/alice",
                    MyOsDemoActor.principal("alice"));
            MyOsDemoEntry first = demo.append(alice, increment(2));
            MyOsDemoDispatch beforeAttachment = demo.process(first, 2);
            assertEquals(Set.of("emb2"), beforeAttachment.documentKeys());
            assertEquals(
                    BigInteger.valueOf(2), demo.value("emb2", "/counter"));

            // when
            demo.addDocument(
                    "emb1",
                    NestedTopologyDocuments.emb1Linking(
                            demo.document("emb2").initialBlueId()),
                    List.of(MyOsManagedEmbedding.at("/emb2", "emb2")));
            demo.addDocument(
                    "root",
                    NestedTopologyDocuments.rootLinking(
                            demo.document("emb1").initialBlueId()),
                    List.of(MyOsManagedEmbedding.at("/emb1", "emb1")));
            assertEquals(
                    BigInteger.valueOf(2),
                    demo.value("emb1", "/emb2/counter"));
            assertEquals(
                    BigInteger.valueOf(2),
                    demo.value("root", "/emb1/emb2/counter"));
            assertEquals(1L, demo.admissionJournalHighWater("emb1"));
            assertEquals(1L, demo.admissionJournalHighWater("root"));
            assertEquals(first.orderKey(), demo.environment().engine().session(
                    demo.document("emb1").sessionId())
                    .subscriptions().activationFrontier());
            assertEquals(first.orderKey(), demo.environment().engine().session(
                    demo.document("root").sessionId())
                    .subscriptions().activationFrontier());

            long emb2EpochBeforeReplay = demo.currentEpoch("emb2");
            long emb1EpochBeforeReplay = demo.currentEpoch("emb1");
            long rootEpochBeforeReplay = demo.currentEpoch("root");
            var workBeforeReplay = demo.engineWorkSnapshot();
            MyOsDemoDispatch replay = demo.process(first, 2);
            var replayWork = demo.engineWorkSnapshot().minus(workBeforeReplay);
            assertEquals(Set.of("emb2"), replay.documentKeys());
            assertEquals(0L, replayWork.processCompletions());
            assertEquals(emb2EpochBeforeReplay, demo.currentEpoch("emb2"));
            assertEquals(emb1EpochBeforeReplay, demo.currentEpoch("emb1"));
            assertEquals(rootEpochBeforeReplay, demo.currentEpoch("root"));

            MyOsDemoEntry later = demo.append(alice, increment(3));
            var workBeforeLater = demo.engineWorkSnapshot();
            MyOsDemoDispatch dispatch = demo.process(later, 2);
            var laterWork = demo.engineWorkSnapshot().minus(workBeforeLater);

            // then
            assertEquals(1, demo.initializationCount("emb2"));
            assertEquals(1, demo.initializationCount("emb1"));
            assertEquals(1, demo.initializationCount("root"));
            assertEquals(
                    new MyOsInitializationCoordinator.Evidence(3, 3, 0),
                    demo.initializationEvidence());
            assertEquals(3, demo.initializationReceipts().size());
            assertTrue(demo.initializationReceipts().stream().allMatch(
                    receipt -> receipt.status()
                            == MyOsInitializationCoordinator.TerminalStatus
                            .SUCCEEDED
                            && receipt.sessionId().equals(
                                    receipt.identity().logicalId())
                            && receipt.inputDocumentBlueId().equals(
                                    receipt.identity()
                                            .initialDocumentBlueId())
                            && receipt.resultRootBlueId() != null));
            assertEquals(
                    BigInteger.valueOf(5), demo.value("emb2", "/counter"));
            assertEquals(
                    BigInteger.valueOf(5),
                    demo.value("emb1", "/emb2/counter"));
            assertEquals(
                    BigInteger.valueOf(5),
                    demo.value("root", "/emb1/emb2/counter"));
            assertEquals(Set.of("emb2", "emb1", "root"),
                    dispatch.documentKeys());
            assertEquals(List.of(2, 1), dispatch.chunkSizes());
            assertEquals(3L, laterWork.processCompletions());
            assertEquals(3L, laterWork.bundleLoads());
            assertEquals(3L, laterWork.committed());
            MyOsDemoAssertions.assertSelectedScopes(
                    dispatch.require("emb2"), "/");
            MyOsDemoAssertions.assertSelectedScopes(
                    dispatch.require("emb1"), "/emb2");
            MyOsDemoAssertions.assertSelectedScopes(
                    dispatch.require("root"), "/emb1/emb2");
            assertEquals(2, demo.journalEntryCount());
            assertEquals(2, demo.storedEventInventoryCount());
            assertEquals(0, dispatch.work().fullRootReconstructions());
            assertEquals(2L, demo.committedJournalHighWater("emb2", alice));
            assertEquals(2L, demo.committedJournalHighWater("emb1", alice));
            assertEquals(2L, demo.committedJournalHighWater("root", alice));
            long reconstructionsBeforeSlice =
                    demo.work().snapshot().fullRootReconstructions();
            demo.environment().fragmentStore().resetReadCounts();
            MyOsDocumentSlice slice = demo.slice("root", "/emb1/emb2");
            assertEquals(
                    demo.document("emb2").initialBlueId(),
                    slice.logicalDocument().initialDocumentBlueId());
            assertEquals(
                    demo.currentRootBlueId("emb2"),
                    slice.physicalSlice().selectedRootBlueId());
            assertEquals(List.of(
                            List.of("myos-demo/root", "/emb1",
                                    "myos-demo/emb1"),
                            List.of("myos-demo/emb1", "/emb2",
                                    "myos-demo/emb2")),
                    slice.relationshipChain().stream()
                            .map(link -> List.of(
                                    link.parent().logicalId(),
                                    link.relativePath(),
                                    link.child().logicalId()))
                            .toList());
            assertEquals(BigInteger.valueOf(5),
                    demo.value(slice.exactSelectedRoot(), "/counter"));
            assertEquals(1L,
                    demo.environment().fragmentStore().batchReadCount());
            assertEquals(0L,
                    demo.environment().fragmentStore().singleReadCount());
            assertEquals(slice.selectedFragmentBlueIds().size(),
                    demo.environment().fragmentStore()
                            .requestedIdentityCount());
            assertTrue(slice.selectedFragmentBlueIds().size()
                    < demo.currentFragmentCount("root"));
            assertEquals(reconstructionsBeforeSlice,
                    demo.work().snapshot().fullRootReconstructions());
            assertEquals(
                    Set.of("emb2", "emb1", "root"),
                    demo.documentsForTimeline(alice));
            assertEquals(Set.of(alice.binding()),
                    demo.timelinesForDocument("root"));
            assertEquals(0, demo.reconcileManagedEmbeddings(
                    "root", List.of()).size());
            assertEquals(List.of(), demo.childrenOf("root"));
            assertEquals(1, demo.reconcileManagedEmbeddings(
                    "root",
                    List.of(MyOsManagedEmbedding.at("/emb1", "emb1")))
                    .size());
            assertEquals(1, demo.childrenOf("root").size());
        }
    }

    @Test
    void shouldNeverOverrideExplicitManagedIdentityFromABlueIdReference() {
        // given
        try (MyOsDemoRuntime demo = MyOsDemoRuntime.create(
                "timeline-first-explicit-identity")) {
            demo.addDocument("emb2", NestedTopologyDocuments.EMB2);
            MyOsDemoTimeline alice = demo.timeline(
                    "examples/nested/alice",
                    MyOsDemoActor.principal("alice"));
            demo.process(demo.append(alice, increment(2)));
            demo.addDocument(
                    "other",
                    NestedTopologyDocuments.EMB2.replace(
                            "Late Attached Emb2", "Other Emb2"));

            // when
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> demo.addDocument(
                            "unmanaged-parent",
                            NestedTopologyDocuments.emb1Linking(
                                    demo.document("emb2").initialBlueId()),
                            List.of(MyOsManagedEmbedding.at(
                                    "/emb2", "other"))));

            // then
            assertTrue(failure.getMessage().contains(
                    "lacks exact initial identity evidence"));
            assertEquals(2, demo.documentCount());
            assertEquals(BigInteger.valueOf(2),
                    demo.value("emb2", "/counter"));
        }
    }

    private static MyOsDemoOperation increment(int amount) {
        return MyOsDemoOperation.operation("increment")
                .through("ownerChannel")
                .request("amount: " + amount)
                .build();
    }
}
