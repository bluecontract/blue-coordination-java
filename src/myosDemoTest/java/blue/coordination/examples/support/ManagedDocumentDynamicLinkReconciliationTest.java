package blue.coordination.examples.support;

import blue.coordination.engine.api.StoredCoordinationEvent;
import blue.coordination.engine.memory.CoordinationFanoutException;
import blue.coordination.engine.memory.InMemoryCoordinationSubscriptionIndexSnapshot;
import blue.coordination.examples.documents.ManagedLinkDocuments;
import blue.coordination.examples.documents.NestedTopologyDocuments;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end proof that PROCESS owns managed-link publication and removal. */
final class ManagedDocumentDynamicLinkReconciliationTest {

    @Test
    void shouldReconcileProcessAddedAndRemovedManagedLinkAcrossEveryIndex() {
        // given
        try (MyOsDemoRuntime demo = MyOsDemoRuntime.create(
                "managed-link-reconciliation")) {
            MyOsDemoDocument child = demo.addDocument(
                    "child", NestedTopologyDocuments.EMB2);
            MyOsDemoDocument parent = demo.addDocument(
                    "parent", ManagedLinkDocuments.DYNAMIC_PARENT);
            MyOsDemoTimeline alice = demo.timeline(
                    "examples/nested/alice",
                    MyOsDemoActor.principal("alice"));
            MyOsDemoTimeline bob = demo.timeline(
                    "examples/managed-links/bob",
                    MyOsDemoActor.principal("bob"));
            demo.append(alice, increment(1));
            assertEquals(0, demo.reconcileManagedEmbeddings(
                    "parent",
                    List.of(MyOsManagedEmbedding.at(
                            "/managedChild", "child"))).size());
            assertEquals(List.of(), demo.childrenOf("parent"));
            assertEquals(List.of(), demo.parentsOf("child"));
            assertEquals(Set.of("child"), demo.documentsForTimeline(alice));

            // when
            MyOsDemoDispatch attached = demo.process(demo.append(
                    bob, attach(demo.currentRootBlueId("child"))));
            MyOsDemoDispatch both = demo.process(demo.append(
                    alice, increment(1)));

            // then
            assertEquals(Set.of("parent"), attached.documentKeys());
            assertEquals(1, demo.childrenOf("parent").size());
            assertEquals(
                    child.initialBlueId(),
                    demo.childrenOf("parent").get(0).child()
                            .initialDocumentBlueId());
            assertEquals(1, demo.parentsOf("child").size());
            assertEquals(Set.of("child", "parent"),
                    demo.documentsForTimeline(alice));
            assertEquals(Set.of(alice.binding(), bob.binding()),
                    demo.timelinesForDocument("parent"));
            assertEquals(
                    Set.of(child.sessionId(), parent.sessionId()),
                    demo.environment().subscriptionIndex().sessionsFor(
                            alice.subscriptionKeys()));
            assertEquals(Set.of("child", "parent"), both.documentKeys());
            assertEquals(BigInteger.ONE,
                    demo.value("child", "/counter"));
            assertEquals(BigInteger.ONE,
                    demo.value("parent", "/managedChild/counter"));

            MyOsDemoDispatch detached = demo.process(demo.append(
                    bob,
                    MyOsDemoOperation.operation("detachManagedChild")
                            .through("controllerChannel")
                            .build()));
            assertEquals(Set.of("parent"), detached.documentKeys());
            assertEquals(List.of(), demo.childrenOf("parent"));
            assertEquals(List.of(), demo.parentsOf("child"));
            assertEquals(Set.of("child"), demo.documentsForTimeline(alice));
            assertEquals(Set.of(bob.binding()),
                    demo.timelinesForDocument("parent"));
            assertEquals(
                    Set.of(child.sessionId()),
                    demo.environment().subscriptionIndex().sessionsFor(
                            alice.subscriptionKeys()));
            assertEquals(Set.of("child"), demo.process(demo.append(
                    alice, increment(1))).documentKeys());
            assertEquals(BigInteger.valueOf(2),
                    demo.value("child", "/counter"));
            assertNull(demo.value("parent", "/managedChild"));
        }
    }

    @Test
    void shouldRejectDynamicCycleBeforePublishingAnyMutableRegistry() {
        // given
        try (MyOsDemoRuntime demo = MyOsDemoRuntime.create(
                "managed-link-cycle")) {
            MyOsDemoDocument first = demo.addDocument(
                    "cycle-a", ManagedLinkDocuments.DYNAMIC_PARENT);
            MyOsDemoDocument second = demo.addDocument(
                    "cycle-b",
                    NestedTopologyDocuments.emb1Linking(
                            first.initialBlueId()),
                    List.of(MyOsManagedEmbedding.at(
                            "/emb2", "cycle-a")));
            MyOsDemoTimeline bob = demo.timeline(
                    "examples/managed-links/bob",
                    MyOsDemoActor.principal("bob"));
            assertEquals(0, demo.reconcileManagedEmbeddings(
                    "cycle-a",
                    List.of(MyOsManagedEmbedding.at(
                            "/managedChild", "cycle-b"))).size());
            MyOsDemoEntry cyclic = demo.append(
                    bob, attach(demo.currentRootBlueId("cycle-b")));
            String firstRoot = demo.currentRootBlueId("cycle-a");
            String secondRoot = demo.currentRootBlueId("cycle-b");
            long firstEpoch = demo.currentEpoch("cycle-a");
            long secondEpoch = demo.currentEpoch("cycle-b");
            List<MyOsTopologyLink> firstChildren =
                    demo.childrenOf("cycle-a");
            List<MyOsTopologyLink> secondChildren =
                    demo.childrenOf("cycle-b");
            List<MyOsTopologyLink> firstParents =
                    demo.parentsOf("cycle-a");
            List<MyOsTopologyLink> secondParents =
                    demo.parentsOf("cycle-b");
            Set<String> documentsForBob = demo.documentsForTimeline(bob);
            Set<MyOsTimelineBinding> firstTimelines =
                    demo.timelinesForDocument("cycle-a");
            Set<MyOsTimelineBinding> secondTimelines =
                    demo.timelinesForDocument("cycle-b");
            MyOsInitializationCoordinator.Evidence initialization =
                    demo.initializationEvidence();
            List<MyOsInitializationCoordinator.Receipt> receipts =
                    demo.initializationReceipts();
            InMemoryCoordinationSubscriptionIndexSnapshot routes =
                    demo.environment().subscriptionIndex().snapshot();

            // when
            CoordinationFanoutException failure = assertThrows(
                    CoordinationFanoutException.class,
                    () -> demo.process(cyclic));

            // then
            String failureMessage = failure.getCause().getMessage();
            assertTrue(
                    failureMessage != null
                            && failureMessage.contains("cycle"),
                    failureMessage);
            assertEquals(first.sessionId(), failure.failedSessionId());
            assertEquals(firstRoot, demo.currentRootBlueId("cycle-a"));
            assertEquals(secondRoot, demo.currentRootBlueId("cycle-b"));
            assertEquals(firstEpoch, demo.currentEpoch("cycle-a"));
            assertEquals(secondEpoch, demo.currentEpoch("cycle-b"));
            assertEquals(firstChildren, demo.childrenOf("cycle-a"));
            assertEquals(secondChildren, demo.childrenOf("cycle-b"));
            assertEquals(firstParents, demo.parentsOf("cycle-a"));
            assertEquals(secondParents, demo.parentsOf("cycle-b"));
            assertEquals(documentsForBob, demo.documentsForTimeline(bob));
            assertEquals(firstTimelines,
                    demo.timelinesForDocument("cycle-a"));
            assertEquals(secondTimelines,
                    demo.timelinesForDocument("cycle-b"));
            assertEquals(initialization, demo.initializationEvidence());
            assertEquals(receipts, demo.initializationReceipts());
            assertEquals(routes.generation(), demo.environment()
                    .subscriptionIndex().snapshot().generation());
            assertEquals(routes.digest(), demo.environment()
                    .subscriptionIndex().snapshot().digest());
            assertEquals(Set.of(first.sessionId(), second.sessionId()),
                    demo.environment().subscriptionIndex().sessionsFor(
                            bob.subscriptionKeys()));
            StoredCoordinationEvent stored = demo.environment().eventStore()
                    .require(cyclic.blueId());
            assertTrue(demo.environment().committedDeliveryProbe()
                    .committedDelivery(stored, first.sessionId()).isEmpty());
            assertTrue(demo.environment().committedDeliveryProbe()
                    .committedDelivery(stored, second.sessionId()).isEmpty());
        }
    }

    private static MyOsDemoOperation attach(String childRootBlueId) {
        return MyOsDemoOperation.operation("attachManagedChild")
                .through("controllerChannel")
                .request("""
                        child:
                          blueId: %s
                        """.formatted(childRootBlueId))
                .build();
    }

    private static MyOsDemoOperation increment(int amount) {
        return MyOsDemoOperation.operation("increment")
                .through("ownerChannel")
                .request("amount: " + amount)
                .build();
    }
}
