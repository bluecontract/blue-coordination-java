package blue.coordination.api;

import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.DocumentHandle;
import blue.coordination.sdk.ManagedDocument;
import blue.coordination.sdk.TimelineHandle;
import blue.coordination.sdk.EntryResult;
import blue.coordination.sdk.EntryDisposition;

import blue.coordination.api.DocumentId;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Independent consumer verification of a genuine runtime producer. */
final class RetainedHistoryProviderTest {
    private static final DocumentId SOURCE = DocumentId.of("f2-history-producer");

    @Test
    void boundedPagesPreserveDuplicateEmissionsAndObserveSourceExtension() throws Exception {
        // given
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        try (BlueCoordination producer = BlueCoordination.inMemory()) {
            TimelineHandle timeline = producer.timelines().register("f2/source", "alice");
            DocumentHandle source = producer.documents().admit(ManagedDocument
                    .yaml(SOURCE, sourceYaml())
                    .publicRoot().fromNow());
            emit(producer, source, timeline);
            emit(producer, source, timeline);
            RetainedHistoryProvider provider = RetainedHistoryProvider.from(producer.advanced(), keys);
            String authored = producer.advanced().auditDocument(SOURCE).authoredInitialBlueId();
            var firstRequest = RetainedHistoryProvider.Request.fresh(SOURCE, 0, 2);

            // when
            var first = provider.read(firstRequest).verify(firstRequest,
                    authored, authored, keys.getPublic());
            emit(producer, source, timeline);
            var nextRequest = RetainedHistoryProvider.Request.fresh(SOURCE, 2, 2);
            var next = provider.read(nextRequest).verify(nextRequest, authored,
                    first.receipts().get(1).afterBlueId(), keys.getPublic());

            // then
            assertEquals(List.of(0L, 1L), first.receipts().stream()
                    .map(blue.coordination.api.ManagedEpochReceipt::epoch).toList());
            assertEquals(List.of(2L, 3L), next.receipts().stream()
                    .map(blue.coordination.api.ManagedEpochReceipt::epoch).toList());
            assertEquals(2L, first.nextEpoch());
            assertEquals(4L, next.nextEpoch());
            assertEquals(2L, first.observedHeadEpoch());
            assertEquals(3L, next.observedHeadEpoch());
            assertEquals(source.snapshot().blueId(), next.observedHeadBlueId());
            for (var receipt : next.receipts()) {
                assertEquals(blue.coordination.api.DocumentRevision.Kind.TIMELINE_ENTRY,
                        receipt.kind());
                assertEquals(2, receipt.emittedEvents().size());
                var one = receipt.emittedEvents().get(0);
                var two = receipt.emittedEvents().get(1);
                assertEquals(one.eventBlueId(), two.eventBlueId());
                assertEquals(0L, one.ordinal());
                assertEquals(1L, two.ordinal());
                assertNotEquals(one.eventOccurrenceIdentity(), two.eventOccurrenceIdentity());
            }
            assertEquals(4, source.history().size(), "reading must never execute source workflows");
        }
    }

    @Test
    void consumerRejectsGapsReorderingTamperingAndUntrustedProducers() throws Exception {
        // given
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair stranger = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        try (BlueCoordination producer = BlueCoordination.inMemory()) {
            TimelineHandle timeline = producer.timelines().register("f2/source", "alice");
            DocumentHandle source = producer.documents().admit(ManagedDocument
                    .yaml(SOURCE, sourceYaml())
                    .publicRoot().fromNow());
            emit(producer, source, timeline);
            var request = RetainedHistoryProvider.Request.fresh(SOURCE, 0, 2);

            // when
            RetainedHistoryPage page = RetainedHistoryProvider.from(producer.advanced(), keys).read(request);
            String authored = page.authoredInitialBlueId();
            byte[] changedSignature = page.signature();
            changedSignature[0] ^= 1;

            // then
            assertThrows(IllegalArgumentException.class, () -> page.verify(request,
                    authored, authored, stranger.getPublic()));
            assertThrows(IllegalArgumentException.class, () -> page.verify(
                    RetainedHistoryProvider.Request.fresh(SOURCE, 0, 2),
                    authored, authored, keys.getPublic()));
            assertThrows(IllegalArgumentException.class, () -> page.verify(request,
                    page.headBlueId(), authored, keys.getPublic()));
            assertThrows(IllegalArgumentException.class, () -> copy(page,
                    page.receipts(), changedSignature).verify(request,
                    authored, authored, keys.getPublic()));
            assertThrows(IllegalArgumentException.class, () -> copy(page,
                    List.of(page.receipts().get(1), page.receipts().get(0)), page.signature()));
            assertThrows(IllegalArgumentException.class, () -> copy(page,
                    List.of(page.receipts().get(0), page.receipts().get(0)), page.signature()));
            assertThrows(IllegalArgumentException.class, () -> copy(page,
                    List.of(page.receipts().get(0)), page.signature()));
            assertThrows(IllegalArgumentException.class, () -> new RetainedHistoryPage(
                    request, authored, page.headEpoch() + 1, page.headBlueId(),
                    page.predecessorBlueId(), page.receipts(), page.signature())
                    .verify(request, authored, authored, keys.getPublic()));
            var original = page.receipts().get(1);
            // Self-consistent hashes are insufficient: removing one duplicate
            // emission must still invalidate the trusted producer's signature.
            var omittedDuplicate = blue.coordination.api.ManagedEpochReceipt.identified(
                    original.documentId(), original.epoch(), original.kind(),
                    original.beforeBlueId().orElseThrow(), original.afterDocument(),
                    original.originalCauseIdentity(), original.sourceEntry().orElse(null),
                    original.sourceOrder().orElse(null),
                    original.contractsTransitionReceiptIdentity(), original.commitCompanionIdentity(),
                    List.of(original.emittedEvents().get(0)), original.processingGas());
            assertThrows(IllegalArgumentException.class, () -> copy(page,
                    List.of(page.receipts().get(0), omittedDuplicate), page.signature())
                    .verify(request, authored, authored, keys.getPublic()));
            assertEquals(2, page.verify(request, authored, authored, keys.getPublic())
                    .receipts().size(), "failed verification must not poison the valid retry");
            assertEquals(2, source.history().size());
        }
    }

    private static RetainedHistoryPage copy(RetainedHistoryPage page,
            List<blue.coordination.api.ManagedEpochReceipt> receipts, byte[] signature) {
        return new RetainedHistoryPage(page.request(), page.authoredInitialBlueId(),
                page.headEpoch(), page.headBlueId(), page.predecessorBlueId(), receipts, signature);
    }

    @Test
    void delayedEvidenceAndOutOfOrderDeliveryKeepTheSameConsumerCursor() throws Exception {
        // given
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        try (BlueCoordination producer = BlueCoordination.inMemory()) {
            TimelineHandle timeline = producer.timelines().register("f2/source", "alice");
            DocumentHandle source = producer.documents().admit(ManagedDocument
                    .yaml(SOURCE, sourceYaml())
                    .publicRoot().fromNow());
            for (int i = 0; i < 3; i++) {
                emit(producer, source, timeline);
            }
            var unavailable = new java.util.concurrent.atomic.AtomicBoolean(true);
            var reads = new java.util.concurrent.atomic.AtomicInteger();
            var engine = producer.advanced().rawEngine();
            var delayed = (blue.coordination.api.CoordinationEngine)
                    java.lang.reflect.Proxy.newProxyInstance(
                            engine.getClass().getClassLoader(),
                            new Class<?>[] { blue.coordination.api.CoordinationEngine.class },
                            (proxy, method, arguments) -> {
                                if (method.getName().equals("auditManagedEpoch")) {
                                    reads.incrementAndGet();
                                    if (unavailable.get() && arguments[1].equals(1L)) {
                                        return java.util.Optional.empty();
                                    }
                                }
                                try {
                                    return method.invoke(engine, arguments);
                                } catch (java.lang.reflect.InvocationTargetException failure) {
                                    throw failure.getCause();
                                }
                            });
            RetainedHistoryProvider provider = new RuntimeRetainedHistoryProvider(delayed, keys);
            var firstRequest = RetainedHistoryProvider.Request.fresh(SOURCE, 0, 2);
            var laterRequest = RetainedHistoryProvider.Request.fresh(SOURCE, 2, 2);
            String authored = engine.auditDocument(SOURCE).authoredInitialBlueId();

            // when
            assertThrows(RetainedHistoryProvider.Unavailable.class,
                    () -> provider.read(firstRequest));
            unavailable.set(false);
            RetainedHistoryPage later = provider.read(laterRequest);
            assertThrows(IllegalArgumentException.class,
                    () -> later.verify(firstRequest, authored, authored, keys.getPublic()));
            var first = provider.read(firstRequest).verify(firstRequest,
                    authored, authored, keys.getPublic());
            var acceptedLater = later.verify(laterRequest, authored,
                    first.receipts().get(1).afterBlueId(), keys.getPublic());

            // then
            assertEquals(2L, first.nextEpoch());
            assertEquals(4L, acceptedLater.nextEpoch());
            assertEquals(7, reads.get(), "two failed reads, then three and two bounded reads");
            assertEquals(4, source.history().size());
        }
    }

    private static String sourceYaml() {
        return """
                contracts:
                  owner:
                    type: Coordination/Timeline Channel
                    timeline: {type: MyOS/MyOS Timeline, timelineId: f2/source}
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                  emit:
                    type: Coordination/Sequential Workflow Operation
                    channel: owner
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendEvent: {type: Coordination/Event, kind: signal}
                          - $appendEvent: {type: Coordination/Event, kind: signal}
                          - $return: true
                """;
    }

    private static void emit(BlueCoordination producer, DocumentHandle source,
            TimelineHandle timeline) {
        EntryResult result = producer.operations().on(source).from(timeline).call("emit")
                .through("owner").request(request -> { }).execute();
        assertEquals(EntryDisposition.APPLIED, result.disposition(), result.diagnostic().toString());
    }
}
