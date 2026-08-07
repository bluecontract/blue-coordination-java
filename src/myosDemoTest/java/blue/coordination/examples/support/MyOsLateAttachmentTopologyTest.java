package blue.coordination.examples.support;

import blue.coordination.engine.api.DocumentSessionId;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fast deterministic proofs for late attachment and fail-closed topology. */
final class MyOsLateAttachmentTopologyTest {

    @Test
    void shouldGraftCurrentStateAndNeverReplayEntriesAtAdmissionHighWater() {
        // given
        MyOsTopologyCatalog topology = new MyOsTopologyCatalog();
        MyOsDocumentIdentity emb2 = identity("emb2", "initial-emb2");
        topology.register(state("emb2", emb2, "emb2-v0", 0L));
        topology.advance(emb2, "emb2-v2", order(1L));

        MyOsDocumentIdentity emb1 = identity("emb1", "initial-emb1");
        topology.registerWithLinks(
                state("emb1", emb1, "emb1-with-v2", 1L),
                1L,
                List.of(new MyOsTopologyCatalog.DesiredLink("/emb2", emb2)));
        MyOsDocumentIdentity root = identity("root", "initial-root");
        topology.registerWithLinks(
                state("root", root, "root-with-v2", 1L),
                1L,
                List.of(new MyOsTopologyCatalog.DesiredLink("/emb1", emb1)));

        MyOsDeliveryLedger ledger = new MyOsDeliveryLedger();
        for (MyOsDocumentIdentity document : List.of(emb2, emb1, root)) {
            ledger.admit(new MyOsDeliveryLedger.StreamKey(document, "alice"),
                    document.equals(emb2) ? 0L : 1L);
        }

        // when
        MyOsJournalPosition old = position(1L);
        MyOsJournalPosition later = position(2L);
        for (MyOsDocumentIdentity document : List.of(emb1, root)) {
            MyOsDeliveryLedger.StreamKey stream =
                    new MyOsDeliveryLedger.StreamKey(document, "alice");
            assertEquals(MyOsDeliveryLedger.Outcome.BEFORE_ADMISSION,
                    ledger.claim(stream, old).outcome());
            MyOsDeliveryLedger.Claim claim = ledger.claim(stream, later);
            assertTrue(claim.acquired());
            ledger.commit(claim);
        }

        // then
        assertEquals("emb2-v2", topology.state(emb2).currentRootBlueId());
        assertEquals(List.of(emb1, root), topology.ancestorsOf(emb2));
        assertEquals(1L, topology.requirePath(emb1, "/emb2")
                .activationJournalSequence());
        assertEquals(2L, ledger.contiguousHighWater(
                new MyOsDeliveryLedger.StreamKey(root, "alice")));
    }

    @Test
    void shouldReplaceOnlyTheExplicitManagedPath() {
        // given
        Node parent = parse("""
                emb2:
                  counter: 0
                unrelated: keep
                """);
        Node child = parse("counter: 2");

        // when
        Node grafted = new MyOsCurrentStateGraft().apply(parent,
                List.of(new MyOsCurrentStateGraft.Replacement("/emb2", child)));

        // then
        assertEquals(BigInteger.valueOf(2),
                NodePathEditor.getOrNull(grafted, "/emb2/counter").getValue());
        assertEquals("keep",
                NodePathEditor.getOrNull(grafted, "/unrelated").getValue());
        assertEquals(BigInteger.ZERO,
                NodePathEditor.getOrNull(parent, "/emb2/counter").getValue(),
                "graft must not mutate the admitted input");
    }

    @Test
    void shouldRejectCycleWithoutPublishingPartialReplacement() {
        // given
        MyOsTopologyCatalog topology = new MyOsTopologyCatalog();
        MyOsDocumentIdentity root = identity("root", "r0");
        MyOsDocumentIdentity emb1 = identity("emb1", "e10");
        MyOsDocumentIdentity emb2 = identity("emb2", "e20");
        topology.register(state("root", root, "r0", 0L));
        topology.register(state("emb1", emb1, "e10", 0L));
        topology.register(state("emb2", emb2, "e20", 0L));
        topology.reconcile(root, 0L, 0L,
                List.of(new MyOsTopologyCatalog.DesiredLink("/emb1", emb1)));
        topology.reconcile(emb1, 0L, 0L,
                List.of(new MyOsTopologyCatalog.DesiredLink("/emb2", emb2)));

        // when
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () ->
                        topology.reconcile(emb2, 0L, 0L, List.of(
                                new MyOsTopologyCatalog.DesiredLink(
                                        "/root", root))));

        // then
        assertTrue(failure.getMessage().contains("cycle"));
        assertEquals(List.of(), topology.childrenOf(emb2));
        assertEquals(emb1, topology.requirePath(root, "/emb1").child());
        assertEquals(emb2, topology.requirePath(emb1, "/emb2").child());
    }

    @Test
    void shouldRejectDirectSelfCycleBeforePublishingStagedAdmission() {
        // given
        MyOsTopologyCatalog topology = new MyOsTopologyCatalog();
        MyOsDocumentIdentity self = identity("self", "self-v0");
        MyOsTopologyCatalog.DocumentState staged =
                state("self", self, "self-v0", 0L);

        // when
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> topology.validateRegistrationWithLinks(
                        staged,
                        0L,
                        List.of(new MyOsTopologyCatalog.DesiredLink(
                                "/self", self))));

        // then
        assertTrue(failure.getMessage().contains("cycle"));
        assertThrows(IllegalArgumentException.class,
                () -> topology.state("self"));
        assertThrows(IllegalArgumentException.class,
                () -> topology.state(self));
        assertEquals(List.of(), topology.childrenOf(self));
        assertEquals(Set.of(), topology.parentsOf(self));
        assertThrows(IllegalStateException.class,
                () -> topology.requireUniqueRoot("self-v0"));
    }

    @Test
    void shouldNeverInferLogicalIdentityFromEqualContent() {
        // given
        MyOsTopologyCatalog topology = new MyOsTopologyCatalog();
        MyOsDocumentIdentity first = identity("first", "same-blue-id");
        MyOsDocumentIdentity second = identity("second", "same-blue-id");
        topology.register(state("first", first, "same-blue-id", 0L));
        topology.register(state("second", second, "same-blue-id", 0L));

        // when
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> topology.requireUniqueRoot("same-blue-id"));

        // then
        assertTrue(failure.getMessage().contains("ambiguous"));
        assertEquals("first", topology.state(first).key());
        assertEquals("second", topology.state(second).key());
    }

    @Test
    void shouldReconcileRemovalInBothTopologyDirections() {
        // given
        MyOsTopologyCatalog topology = new MyOsTopologyCatalog();
        MyOsDocumentIdentity parent = identity("parent", "parent-v0");
        MyOsDocumentIdentity child = identity("child", "child-v0");
        topology.register(state("child", child, "child-v0", 0L));
        topology.registerWithLinks(
                state("parent", parent, "parent-v0", 0L),
                0L,
                List.of(new MyOsTopologyCatalog.DesiredLink("/child", child)));

        // when
        MyOsTopologyLink unchanged = topology.reconcile(
                parent,
                0L,
                1L,
                List.of(new MyOsTopologyCatalog.DesiredLink(
                        "/child", child))).get(0);
        topology.reconcile(parent, 0L, 2L, List.of());

        // then
        assertEquals(0L, unchanged.activationJournalSequence(),
                "an unchanged relationship must not be reactivated");
        assertEquals(List.of(), topology.childrenOf(parent));
        assertEquals(Set.of(), topology.parentsOf(child));
        assertThrows(IllegalArgumentException.class,
                () -> topology.requirePath(parent, "/child"));
    }

    @Test
    void shouldRetryAbandonedDeliveryWithoutRepeatingCommittedWork() {
        // given
        MyOsDeliveryLedger ledger = new MyOsDeliveryLedger();
        MyOsDeliveryLedger.StreamKey stream = new MyOsDeliveryLedger.StreamKey(
                identity("doc", "initial-doc"), "timeline");
        ledger.admit(stream, 0L);
        MyOsJournalPosition first = position(1L);
        MyOsJournalPosition second = position(3L);

        // when
        MyOsDeliveryLedger.Claim firstClaim = ledger.claim(stream, first);
        ledger.commit(firstClaim);
        MyOsDeliveryLedger.Claim failed = ledger.claim(stream, second);
        ledger.abandon(failed);
        MyOsDeliveryLedger.Claim retry = ledger.claim(stream, second);
        ledger.commit(retry);

        // then
        assertEquals(MyOsDeliveryLedger.Outcome.ALREADY_COMMITTED,
                ledger.claim(stream, first).outcome());
        assertTrue(retry.token() != failed.token());
        assertEquals(3L, ledger.committedHighWater(stream));
        assertEquals(Set.of(1L, 3L), ledger.committedSequences(stream));
    }

    @Test
    void shouldInitializeOneLogicalDocumentExactlyOnceUnderContention()
            throws Exception {
        // given
        MyOsInitializationCoordinator<String> coordinator =
                new MyOsInitializationCoordinator<>();
        MyOsDocumentIdentity identity = identity("only", "initial");
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);

        // when
        try {
            List<Future<String>> results = java.util.stream.IntStream.range(0, 32)
                    .mapToObj(index -> pool.submit(() -> coordinator.initialize(
                            identity,
                            "test-session",
                            () -> {
                                calls.incrementAndGet();
                                entered.countDown();
                                try {
                                    release.await();
                                } catch (InterruptedException failure) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(failure);
                                }
                                return new MyOsInitializationCoordinator
                                        .Completed<>("ready", "ready-root");
                            })))
                    .toList();
            entered.await();
            release.countDown();
            for (Future<String> result : results) {
                assertEquals("ready", result.get());
            }
        } finally {
            pool.shutdownNow();
        }

        // then
        assertEquals(1, calls.get());
        assertEquals(new MyOsInitializationCoordinator.Evidence(1, 1, 0),
                coordinator.evidence());
        assertEquals(1, coordinator.initializedCount());
        assertEquals(List.of(new MyOsInitializationCoordinator.Receipt(
                        identity,
                        "test-session",
                        "initial",
                        1L,
                        MyOsInitializationCoordinator.TerminalStatus.SUCCEEDED,
                        "ready-root",
                        null)),
                coordinator.terminalReceipts());
    }

    private static MyOsTopologyCatalog.DocumentState state(
            String key,
            MyOsDocumentIdentity identity,
            String rootBlueId,
            long admissionHighWater) {
        return new MyOsTopologyCatalog.DocumentState(
                key, identity, DocumentSessionId.of("myos-demo/" + key),
                rootBlueId, 0L, admissionHighWater, order(0L));
    }

    private static MyOsDocumentIdentity identity(String key, String initial) {
        return new MyOsDocumentIdentity("myos-demo/" + key, initial);
    }

    private static MyOsJournalPosition position(long sequence) {
        return new MyOsJournalPosition(
                sequence, "entry-" + sequence, order(sequence));
    }

    private static ExternalOrderKey order(long sequence) {
        return ExternalOrderKey.of(Arrays.<Object>asList(
                BigInteger.ZERO, "test", sequence));
    }

    private static Node parse(String yaml) {
        return MyOsDemoKernel.runtime().parseSourceYaml(yaml);
    }

    private static String blueId(Node node) {
        return MyOsDemoKernel.runtime().calculateBlueId(node);
    }
}
