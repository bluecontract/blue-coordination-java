package blue.coordination.sdk;

import blue.coordination.api.CoordinationMetrics;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.coordination.internal.CoordinationTestControl;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact B3 -> retained C evidence, without any MyOS projection or persistence. */
final class SdkNestedExistingSourceEpochTest {
    private static final DocumentId A = DocumentId.of("nested-existing-epoch-a");
    private static final DocumentId B = DocumentId.of("nested-existing-epoch-b");
    private static final DocumentId C = DocumentId.of("nested-existing-epoch-c");
    private static final String TIMELINE = "nested-existing-epoch/alice";

    @Test
    void literalAuthoredNestedSourceRemainsExactWhileAnotherConsumerReplaysIt() throws IOException {
        try (BlueCoordination blue = BlueCoordination.builder().contentDerivedDocumentIds().build()) {
            // given
            CoordinationTestControl control = CoordinationTestControl.attach(blue.advanced().rawEngine());
            TimelineHandle timeline = blue.timelines().register(
                    "labs/retained-managed-epoch/nested/alice", "alice");
            ExactBlueValue cAuthored = blue.values().yaml(labYaml("source-c"));
            DocumentId C = DocumentId.of(cAuthored.blueId());
            DocumentHandle c = blue.documents().admit(ManagedDocument.yaml(C, labYaml("source-c"))
                    .publicRoot().fromNow());
            applied(blue.operations().on(c).from(timeline).call("advance")
                    .through("ownerChannel").request(request -> { }).execute());
            ExactBlueValue bAuthored = blue.values().yaml(labYaml("source-b"));
            DocumentId B = DocumentId.of(bAuthored.blueId());
            DocumentHandle b = blue.documents().admit(ManagedDocument.yaml(B, labYaml("source-b"))
                    .publicRoot().fromNow());
            applied(blue.operations().on(b).from(timeline).call("activateNested")
                    .through("ownerChannel").request(request -> request.exact("embeddedContract", cAuthored))
                    .selectManagedEpoch("/embeddedCandidates/selected", C, -1L, cAuthored.blueId())
                    .execute());
            System.out.println("LITERAL_B_INITIAL head=" + blue.advanced().auditDocument(B)
                    + " plans=" + blue.advanced().auditManagedCatchUpPlans(B)
                    + " exact=" + ExactBlueValue.wrap(blue.advanced().auditDocument(B).current()).json());
            drainToReady(blue, B, new ArrayList<>());
            List<String> bReceipts = receiptIdentities(blue, B);
            List<String> cReceipts = receiptIdentities(blue, C);
            String bHead = b.snapshot().blueId();
            String bExact = b.exact().json();
            String cHead = c.snapshot().blueId();
            assertEquals(3L, b.snapshot().epoch());
            assertEquals(bHead, blue.advanced().auditManagedEpoch(B, 3L).orElseThrow().afterBlueId());

            // when
            DocumentId A = DocumentId.of(blue.values().yaml(labYaml("consumer-a")).blueId());
            DocumentHandle a = blue.documents().admit(ManagedDocument.yaml(A, labYaml("consumer-a"))
                    .publicRoot().fromNow());

            // then
            assertEquals(bHead, blue.advanced().auditDocument(B).blueId(), "B must not change when A is admitted");
            EntryHandle attached = blue.operations().on(a).from(timeline).call("attachCandidate")
                    .through("ownerChannel").request(request -> request.exact("embeddedContract", bAuthored))
                    .selectManagedEpoch("/embeddedCandidates/selected", B, -1L, bAuthored.blueId())
                    .submit();
            DrainResult attachment = blue.processing().drainJournal(new DrainBudget(1L, 1L));
            applied(attachment.entry(attached));
            var attachmentEvidence = control.lastClosureProcessEvidence().orElseThrow();
            System.out.println("LITERAL_ATTACHMENT work=" + attachmentEvidence.workTrace()
                    + " resolved=" + attachment.entry(attached).closures().stream()
                            .flatMap(closure -> closure.managedSurfaceEvidence().resolvedOccurrences().stream()).toList());
            assertTrue(attachmentEvidence.workTrace().stream().allMatch(
                    work -> work.targetDocumentId().value().equals(A.value())),
                    "Attaching retained B must not execute B or C");
            assertEquals(bHead, blue.advanced().auditDocument(B).blueId(),
                    "B must not change when A attaches the authored B state");
            for (int step = 0; step < 12 && !blue.advanced()
                    .auditManagedDocumentReadiness(A).orElseThrow().ready(); step++) {
                var work = blue.advanced().auditNextProcessingSelection()
                        .managedEpochApplicationWork().orElseThrow();
                var source = blue.advanced().auditManagedEpoch(work.sourceDocumentId(), work.sourceEpoch())
                        .orElseThrow();
                System.out.println("LITERAL_APPLICATION work=" + work.workIdentity()
                        + " receipt=" + source.receiptIdentity() + " epoch=" + work.sourceEpoch()
                        + " graph=" + work.expectedGraphGeneration()
                        + " sourceExact=" + source.afterDocument().json());
                DrainResult result = blue.processing().drainManagedEpochApplication(work.workIdentity());
                assertEquals(1, result.managedEpochApplications().size(),
                        () -> "epoch=" + work.sourceEpoch() + " attempts="
                                + result.managedEpochApplicationAttempts() + " diagnostic=" + result.diagnostic());
                assertEquals(bReceipts, receiptIdentities(blue, B));
                assertEquals(cReceipts, receiptIdentities(blue, C));
                assertEquals(bHead, blue.advanced().auditDocument(B).blueId(),
                        () -> "B mutated at replay epoch " + work.sourceEpoch() + "; before="
                                + bExact + "; after="
                                + ExactBlueValue.wrap(blue.advanced().auditDocument(B).current()).json());
                assertEquals(cHead, blue.advanced().auditDocument(C).blueId());
            }
            assertTrue(blue.advanced().auditManagedDocumentReadiness(A).orElseThrow().ready());
        }
    }

    private static String labYaml(String name) throws IOException {
        try (var source = SdkNestedExistingSourceEpochTest.class.getResourceAsStream(
                "/retained-nested-existing/" + name + ".yaml")) {
            if (source == null) {
                throw new IOException("Missing exact Lab 13 fixture: " + name);
            }
            return new String(source.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void b3IntroducesRetainedCAndACatchesUpWithoutReprocessingEitherSource() {
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            // given
            CoordinationTestControl control = CoordinationTestControl.attach(
                    blue.advanced().rawEngine());
            TimelineHandle timeline = blue.timelines().register(TIMELINE, "alice");
            DocumentHandle c = admit(blue, C);
            ExactBlueValue cZero = c.history().get(0).after();
            applied(increment(blue, c, timeline).execute());
            applied(increment(blue, c, timeline).execute());
            List<String> cReceipts = receiptIdentities(blue, C);
            String cHead = c.snapshot().blueId();

            DocumentHandle b = admit(blue, B);
            ExactBlueValue bZero = b.history().get(0).after();
            applied(increment(blue, b, timeline).execute());
            applied(increment(blue, b, timeline).execute());
            for (ManagedEpochReceipt receipt : blue.advanced().auditManagedEpochs(B)) {
                assertFalse(receipt.afterDocument().json().contains("\"child\""),
                        "B0..B2 must not already contain C");
            }

            // when
            EntryHandle attachC = attach(blue, b, timeline, cZero).submit();
            DrainResult introduced = blue.processing().drainJournal(new DrainBudget(1L, 1L));
            applied(introduced.entry(attachC));

            // then
            ManagedEpochReceipt bThree = blue.advanced().auditManagedEpoch(B, 3L).orElseThrow();
            assertEquals(cZero.blueId(), bThree.afterDocument().valueAt("/child").blueId(),
                    "B3 must carry C0, never C's newer current head");
            assertFalse(cHead.equals(cZero.blueId()));
            var nested = blue.advanced().auditManagedOccurrence(B, "/child").orElseThrow();
            ManagedOccurrenceCatchUpPlan nestedPlan = blue.advanced().auditManagedCatchUpPlans(B).get(0);
            assertEquals(C, nested.targetDocumentId());
            assertEquals(cZero.blueId(), nestedPlan.admittedSourceBlueId());
            assertEquals(1L, nested.activationGeneration());
            assertFalse(nested.active());
            assertEquals(0L, nestedPlan.admittedSourceEpoch());
            System.out.println("B3_EVIDENCE receipt=" + bThree.receiptIdentity()
                    + " epoch=" + bThree.epoch() + " occurrence=" + nested
                    + " plan=" + nestedPlan
                    + " resolved=" + introduced.entry(attachC).closures().stream()
                            .flatMap(closure -> closure.managedSurfaceEvidence().resolvedOccurrences().stream()).toList()
                    + " exactAfter=" + bThree.afterDocument().json());
            List<Long> nestedApplied = new ArrayList<>();
            drainToReady(blue, B, nestedApplied);
            assertEquals(List.of(1L, 2L), nestedApplied);
            assertEquals(cReceipts, receiptIdentities(blue, C));
            assertEquals(cHead, c.snapshot().blueId());

            DocumentHandle a = admit(blue, A);
            List<String> bReceipts = receiptIdentities(blue, B);
            String bHead = b.snapshot().blueId();
            String bExact = b.snapshot().exact().json();
            EntryHandle attachB = attach(blue, a, timeline, bZero).submit();
            DrainResult attached = blue.processing().drainJournal(new DrainBudget(1L, 1L));
            applied(attached.entry(attachB));
            List<ManagedOccurrenceCatchUpPlan> plans = blue.advanced().auditManagedCatchUpPlans(A);
            assertEquals(1, plans.size());
            assertEquals(0L, plans.get(0).admittedSourceEpoch());
            String barrier = plans.get(0).barrierIdentity();
            CoordinationTestControl.MetricsSnapshot before = control.metricsSnapshot();
            List<Long> applied = new ArrayList<>();
            for (int step = 0; step < 16 && !blue.advanced()
                    .auditManagedDocumentReadiness(A).orElseThrow().ready(); step++) {
                var work = blue.advanced().auditNextProcessingSelection()
                        .managedEpochApplicationWork().orElseThrow();
                ManagedEpochReceipt source = blue.advanced().auditManagedEpoch(
                        work.sourceDocumentId(), work.sourceEpoch()).orElseThrow();
                System.out.println("A_APPLICATION work=" + work.workIdentity()
                        + " receipt=" + source.receiptIdentity() + " epoch=" + work.sourceEpoch()
                        + " graph=" + work.expectedGraphGeneration()
                        + " occurrence=" + blue.advanced().auditManagedOccurrence(A, "/child")
                        + " sourceExact=" + source.afterDocument().json()
                        + " currentBExact=" + ExactBlueValue.wrap(blue.advanced().auditDocument(B).current()).json());
                DrainResult one = blue.processing().drainManagedEpochApplication(work.workIdentity());
                assertEquals(1, one.managedEpochApplications().size(),
                        () -> "sourceEpoch=" + work.sourceEpoch() + " attempts="
                                + one.managedEpochApplicationAttempts() + " diagnostic=" + one.diagnostic());
                applied.add(work.sourceEpoch());
                assertEquals(bReceipts, receiptIdentities(blue, B));
                assertEquals(cReceipts, receiptIdentities(blue, C));
                assertEquals(bHead, blue.advanced().auditDocument(B).blueId(),
                        () -> "Source B changed during retained replay of epoch " + work.sourceEpoch()
                                + "; before=" + bExact + "; after="
                                + ExactBlueValue.wrap(blue.advanced().auditDocument(B).current()).json());
            }
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L), applied);
            assertTrue(blue.advanced().auditManagedDocumentReadiness(A).orElseThrow().ready());
            assertEquals(ManagedCatchUpStatus.COMPLETE,
                    blue.advanced().auditManagedCatchUpPlans(A).get(0).status());
            assertEquals(barrier, blue.advanced().auditManagedCatchUpPlans(A).get(0).barrierIdentity());
            assertEquals(cHead, c.snapshot().blueId());
            CoordinationTestControl.MetricsSnapshot after = control.metricsSnapshot();
            assertEquals(0L, after.counters().getOrDefault("managedEpoch.catchUp.sourceProcessCalls", 0L)
                    - before.counters().getOrDefault("managedEpoch.catchUp.sourceProcessCalls", 0L));
            assertEquals(0L, after.counters().getOrDefault(CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS.name(), 0L)
                    - before.counters().getOrDefault(CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS.name(), 0L));
            assertEquals(0L, after.counters().getOrDefault(CoordinationMetrics.Counter.DOCUMENT_INITIALIZATIONS.name(), 0L)
                    - before.counters().getOrDefault(CoordinationMetrics.Counter.DOCUMENT_INITIALIZATIONS.name(), 0L));
        }
    }

    private static DocumentHandle admit(BlueCoordination blue, DocumentId id) {
        return blue.documents().admit(ManagedDocument.yaml(id, yaml(id)).publicRoot().fromNow());
    }

    private static OperationCall increment(BlueCoordination blue, DocumentHandle document, TimelineHandle timeline) {
        return blue.operations().on(document).from(timeline).call("increment")
                .through("ownerChannel").request(request -> { });
    }

    private static OperationCall attach(BlueCoordination blue, DocumentHandle document,
                                        TimelineHandle timeline, ExactBlueValue child) {
        return blue.operations().on(document).from(timeline).call("attach")
                .through("ownerChannel").request(request -> request.exact("embeddedContract", child));
    }

    private static void applied(EntryResult result) {
        assertEquals(EntryDisposition.APPLIED, result.disposition(), result.diagnostic().toString());
    }

    private static List<String> receiptIdentities(BlueCoordination blue, DocumentId id) {
        return blue.advanced().auditManagedEpochs(id).stream().map(ManagedEpochReceipt::receiptIdentity).toList();
    }

    private static void drainToReady(BlueCoordination blue, DocumentId id, List<Long> epochs) {
        for (int step = 0; step < 16 && !blue.advanced().auditManagedDocumentReadiness(id).orElseThrow().ready(); step++) {
            var work = blue.advanced().auditNextProcessingSelection().managedEpochApplicationWork().orElseThrow();
            DrainResult one = blue.processing().drainManagedEpochApplication(work.workIdentity());
            assertEquals(1, one.managedEpochApplications().size(), one.managedEpochApplicationAttempts().toString());
            epochs.add(work.sourceEpoch());
        }
        assertTrue(blue.advanced().auditManagedDocumentReadiness(id).orElseThrow().ready());
    }

    private static String yaml(DocumentId id) {
        return """
                documentId: %s
                counter: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    paths: [/child]
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  increment:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /counter
                              val: {$add: [{$document: /counter}, 1]}
                          - $return: true
                  attach:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      embeddedContract: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /child
                              val: {$binding: event/message/request/embeddedContract}
                          - $return: true
                """.formatted(id.value(), TIMELINE);
    }
}
