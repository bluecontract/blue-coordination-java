package blue.coordination.internal;

import blue.coordination.api.*;
import blue.coordination.sdk.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static blue.coordination.internal.DocumentSessionStorageTest.resource;
import static blue.coordination.internal.SourceDiscoveryStorageCodecTest.coordinator;

final class SourcePrerequisiteResultStorageValidationTest {
    @Test void actualAdmissionCannotBeReplacedByAnotherPublishedAdmission() throws Exception {
        // given
        try (var scenario = new SourceDiscoveryStorageCodecTest.Scenario(false)) {
            var d = scenario.selection(); var selected = scenario.coordinator().requireSelection(d);
            var response = scenario.f.blue.advanced().processSourceHistoryPrerequisite(d);
            validateWithoutWork(scenario.f, scenario.parent, selected, response);
            // when
            var other = scenario.f.engine.documents().publicationSnapshot().admissionReceipts().values().stream()
                    .filter(r -> r.documentIds().contains(scenario.parent.id())).findFirst().orElseThrow();
            // then
            assertNotEquals(response.admission().orElseThrow().publicationIdentity(), other.publicationIdentity());
            assertThrows(IllegalArgumentException.class, () -> validate(scenario.f, selected,
                    new SourceHistoryPrerequisiteResult(d, Optional.of(other), Optional.empty(), false)));
            var original = response.admission().orElseThrow();
            var relabelled = new ContractsClosureAdmissionReceipt(other.attempt(), original.publicationIdentity(),
                    original.publicationOutcome(), original.documentIds());
            assertThrows(IllegalArgumentException.class, () -> validate(scenario.f, selected,
                    new SourceHistoryPrerequisiteResult(d, Optional.of(relabelled), Optional.empty(), false)));
            var replay = scenario.f.blue.advanced().processSourceHistoryPrerequisite(d);
            assertTrue(replay.replayed()); validateWithoutWork(scenario.f, scenario.parent, selected, replay);
        }
    }

    @Test void actualLiveCannotBeReplacedByAnotherEntryOrRelabelledTerminalResult() throws Exception {
        // given
        try (var scenario = new SourceDiscoveryStorageCodecTest.Scenario(true)) {
            var d = scenario.selection(); var selected = scenario.coordinator().requireSelection(d);
            var response = scenario.f.blue.advanced().processSourceHistoryPrerequisite(d);
            validateWithoutWork(scenario.f, scenario.parent, selected, response);
            var other = unrelatedLive(scenario.f);
            rejectsProcessing(scenario.f, selected, other);
            var a = other.contractsAttemptsByEntry().values().iterator().next().get(0);
            var expected = response.processing().orElseThrow();
            var relabelled = new ContractsClosureDispatchAttempt(d.entryBlueId(), a.documentIds(), a.attempt(), true,
                    expected.contractsAttemptsFor(d.entryBlueId()).get(0).publicationIdentity(), false);
            var counterfeit = new ProcessingDrainReceipt(expected.processedEntries(), expected.outcomesByEntry(),
                    Map.of(d.entryBlueId(), List.of(relabelled)), expected.processedThrough().orElseThrow(),
                    expected.quiescent(), expected.paused(), other.committedProcessTransitions(), other.elapsedNanos());
            rejectsProcessing(scenario.f, selected, counterfeit);
            // when
            var replay = scenario.f.blue.advanced().processSourceHistoryPrerequisite(d);
            // then
            assertTrue(replay.replayed()); validateWithoutWork(scenario.f, scenario.parent, selected, replay);
        }
    }

    @Test void actualManagedHistoryBindsTheOriginalWorkAndCannotAcceptAnOrdinaryDrain() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var leaf = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory());
            f.process(leaf, f.append(leaf, "rcp2/source", "tick"));
            var source = f.start(resource("parent.yaml").replace("rcp2/parent", "rcp2/middle"),
                    "rcp2/middle", ActivationPolicy.importFullHistory());
            // when
            var attachLeaf = attach(f, source, "rcp2/middle", 120, "child: {blueId: " + leaf.id().value() + "}");
            // then
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(source).entry(attachLeaf).disposition());
            var parent = f.start(resource("parent.yaml"), "rcp2/parent", ActivationPolicy.importFullHistory());
            var attachSource = attach(f, parent, "rcp2/parent", 200, "child: {blueId: " + source.id().value() + "}");
            assertEquals(EntryDisposition.NEEDS_RESOURCES, f.blue.processing().processNext(parent).entry(attachSource).disposition());
            historical(f, parent, SourceHistoryPrerequisite.Kind.MANAGED_HISTORY);
        }
    }

    @Test void actualRootLocalHistoryBindsItsOriginalRootWorkAndTerminalPublication() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var nodes = new java.util.LinkedHashMap<String, DocumentHandle>();
            for (String name : List.of("A", "B", "C")) {
                String yaml = resource("node-graph.template.json").replace("<NODE>", name)
                        .replace("<NAMESPACE>", "stored-source-local").replace("<TIMELINE>", "stored-source/" + name);
                var authored = f.blue.values().yaml(yaml); f.exact.put(authored.blueId(), authored.json());
                nodes.put(name, f.start(yaml, "stored-source/" + name, ActivationPolicy.importFullHistory()));
            }
            var a = nodes.get("A"); var b = nodes.get("B"); var c = nodes.get("C");
            // when
            var ab = attach(f, a, "stored-source/A", 10, "edge: b\nsource: {blueId: " + b.id().value() + "}");
            // then
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(a).entry(ab).disposition());
            assertEquals(1, f.blue.processing().processNext(a).managedEpochApplications().size());
            var bc = attach(f, b, "stored-source/B", 12, "edge: c\nsource: {blueId: " + c.id().value() + "}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(b).entry(bc).disposition());
            assertEquals(1, f.blue.processing().processNext(b).managedEpochApplications().size());
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(a).entry(bc).disposition());
            var parent = f.start(resource("parent.yaml"), "rcp2/parent", ActivationPolicy.importFullHistory());
            var pa = attach(f, parent, "rcp2/parent", 20, "child: {blueId: " + a.id().value() + "}");
            assertEquals(EntryDisposition.NEEDS_RESOURCES, f.blue.processing().processNext(parent).entry(pa).disposition());
            historical(f, parent, SourceHistoryPrerequisite.Kind.ROOTED_RETAINED);
        }
    }

    private static void historical(DocumentSessionStorageTest.Fixture f, DocumentHandle parent, SourceHistoryPrerequisite.Kind kind) throws Exception {
        var d = f.blue.advanced().sourceHistoryPrerequisites(parent).get(0); assertEquals(kind, d.kind());
        var selected = coordinator(f).requireSelection(d); var beforeParent = f.completeEvidence(parent.id());
        var response = f.blue.advanced().processSourceHistoryPrerequisite(d);
        assertEquals(beforeParent, f.completeEvidence(parent.id()), "Prerequisite must not retry the parent");
        assertTrue(response.processing().orElseThrow().committedProcessTransitions() > 0);
        validateWithoutWork(f, parent, selected, response);
        rejectsProcessing(f, selected, unrelatedLive(f));
    }

    private static ProcessingDrainReceipt unrelatedLive(DocumentSessionStorageTest.Fixture f) throws Exception {
        var other = f.start(resource("source.yaml").replace("rcp2/source", "independent/source"),
                "independent/source", ActivationPolicy.importFullHistory());
        f.clock = 500; var entry = f.append(other, "independent/source", "tick");
        var exact = f.engine.auditTimelineEntries().stream().filter(e -> e.blueId().equals(entry.blueId())).findFirst().orElseThrow();
        var result = f.engine.processRootInput(other.id(), exact);
        assertTrue(result.committedProcessTransitions() > 0); return result;
    }

    private static void rejectsProcessing(DocumentSessionStorageTest.Fixture f,
            RootedSourceDiscoveryCoordinator.Prepared selected, ProcessingDrainReceipt wrong) {
        assertThrows(IllegalArgumentException.class, () -> validate(f, selected,
                new SourceHistoryPrerequisiteResult(selected.descriptor(), Optional.empty(), Optional.of(wrong), false)));
    }

    private static void validateWithoutWork(DocumentSessionStorageTest.Fixture f, DocumentHandle parent,
            RootedSourceDiscoveryCoordinator.Prepared selected, SourceHistoryPrerequisiteResult response) {
        int reads = f.providerReads.get(); var before = f.completeEvidence(parent.id());
        assertDoesNotThrow(() -> validate(f, selected, response));
        assertEquals(reads, f.providerReads.get()); assertEquals(before, f.completeEvidence(parent.id()));
    }

    private static void validate(DocumentSessionStorageTest.Fixture f,
            RootedSourceDiscoveryCoordinator.Prepared selected, SourceHistoryPrerequisiteResult response) {
        SourcePrerequisiteResultStorageValidation.require(selected, response,
                id -> f.engine.documents().admissionReceipt(id).orElse(null), new CoreReceiptStorageCodec(32 * 1024 * 1024, 128));
    }

    @Test void actualAutomaticallyExpandedAdmissionUsesItsIndependentOriginalLedgerAfterColdDecode() throws Exception {
        // given
        String source = resource("source.yaml").replace("contracts:", """
                child:
                  name: source-admission-child
                  counter: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    paths: [/child]
                """);
        var codec = new CoreReceiptStorageCodec(32 * 1024 * 1024, 128);
        var sources = new SourceDiscoveryStorageCodec(32 * 1024 * 1024, 128);
        byte[] preparedBytes; byte[] responseBytes; byte[] ledgerBytes;
        SourceHistoryPrerequisite descriptor; DocumentSessionStorageTest.Bytes objects;
        try (var scenario = new SourceDiscoveryStorageCodecTest.Scenario(false, source)) {
            // when
            descriptor = scenario.selection();
            // then
            assertEquals(SourceHistoryPrerequisite.Kind.ADMISSION, descriptor.kind());
            var selected = scenario.coordinator().requireSelection(descriptor);
            assertEquals(1, selected.admission().invocation().snapshot().managedDocuments().size());
            var before = scenario.f.completeEvidence(scenario.parent.id());
            var response = scenario.f.blue.advanced().processSourceHistoryPrerequisite(descriptor);
            var receipt = response.admission().orElseThrow(); assertTrue(receipt.published());
            assertEquals(2, receipt.documentIds().size());
            assertNotEquals(selected.admission().invocation().invocationIdentity(), receipt.attempt().processResult().invocationIdentity());
            assertNotEquals(selected.admission().invocation().snapshot().closureIdentity(), receipt.attempt().processResult().inputClosureIdentity());
            assertEquals(before, scenario.f.completeEvidence(scenario.parent.id()));
            validateWithoutWork(scenario.f, scenario.parent, selected, response);
            preparedBytes = sources.encodePrepared(selected, scenario.f.storage::retainView);
            responseBytes = codec.encodeAdmission(receipt);
            ledgerBytes = codec.encodeAdmission(scenario.f.engine.documents().admissionReceipt(receipt.publicationIdentity()).orElseThrow());
            objects = scenario.f.bytes.copy();
        }
        var sessions = new DocumentSessionStorage(objects,
                new DocumentSessionStorage.Limits(32 * 1024 * 1024, 128, 128L * 1024 * 1024));
        try (var views = sessions.openScope()) {
            var selected = sources.decodePrepared(descriptor.selectionIdentity(), preparedBytes, views);
            var response = new SourceHistoryPrerequisiteResult(descriptor, Optional.of(codec.decodeAdmission(responseBytes)), Optional.empty(), true);
            var ledger = codec.decodeAdmission(ledgerBytes); var keysRead = new java.util.ArrayList<String>();
            SourcePrerequisiteResultStorageValidation.require(selected, response, key -> { keysRead.add(key); return ledger; }, codec);
            assertEquals(List.of(ledger.publicationIdentity()), keysRead, "Only the independently keyed selected admission is opened");
            assertThrows(IllegalArgumentException.class,
                    () -> SourcePrerequisiteResultStorageValidation.require(selected, response, key -> null, codec));
            var unrelated = new ContractsClosureAdmissionReceipt(ledger.attempt(), "another-publication",
                    ledger.publicationOutcome(), ledger.documentIds());
            assertThrows(IllegalArgumentException.class,
                    () -> SourcePrerequisiteResultStorageValidation.require(selected, response, key -> unrelated, codec));
        }
    }

    private static EntryHandle attach(DocumentSessionStorageTest.Fixture f, DocumentHandle root,
            String timeline, long time, String request) {
        return f.blue.events().from(f.timelines.get(timeline)).exact(f.blue.values().yaml("""
                type: Coordination/Timeline Entry
                timeline: {type: MyOS/MyOS Timeline, timelineId: %s}
                timestamp: %d
                actor: {type: MyOS/Principal Actor, accountId: alice}
                message:
                  type: Coordination/Operation Request
                  document: {blueId: %s}
                  operation: attach
                  channel: owner
                  request:
                %s
                """.formatted(timeline, time, root.snapshot().blueId(), request.indent(4)))).submit();
    }
}
