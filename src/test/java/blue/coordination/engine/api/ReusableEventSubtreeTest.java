package blue.coordination.engine.api;

import blue.coordination.engine.memory.CoordinationEventAdmissionMetrics;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Acceptance proof for immutable event-fragment evidence reuse. */
final class ReusableEventSubtreeTest {

    @Test
    void shouldReuseTheExactPayNoteClosureWithoutRefingerprintingIt() {
        // given
        CoordinationEventAdmissionMetrics metrics =
                new CoordinationEventAdmissionMetrics();
        CoordinationEventAdmissionCompiler compiler = compiler(metrics);
        Node payNote = payNote();
        String payNoteBlueId = DirectBlueIdCalculator.calculateBlueId(
                payNote);
        Node firstEvent = event(1L, payNote);
        CoordinationVerifiedEventAdmission first = compiler.compile(
                DirectBlueIdCalculator.calculateBlueId(firstEvent),
                firstEvent);
        CoordinationEventAdmissionMetrics.Snapshot before =
                metrics.snapshot();
        Node secondEvent = event(2L, payNote);

        // when
        CoordinationVerifiedEventAdmission second = compiler.compile(
                DirectBlueIdCalculator.calculateBlueId(secondEvent),
                secondEvent);

        // then
        CoordinationEventAdmissionMetrics.Snapshot work =
                metrics.snapshot().minus(before);
        Set<String> shared = new LinkedHashSet<String>(
                first.fragments().keySet());
        shared.retainAll(second.fragments().keySet());
        assertTrue(shared.contains(payNoteBlueId),
                "the complete request document must be a reusable fragment");
        assertEquals(shared.size(), work.fragmentEvidenceHits());
        assertEquals(second.fragments().size() - shared.size(),
                work.fragmentEvidenceMisses());
        assertEquals(work.fragmentEvidenceMisses(),
                work.wireFingerprints(),
                "only new fragments may be wire-fingerprinted");
        for (String sharedBlueId : shared) {
            assertSame(first.fragments().get(sharedBlueId),
                    second.fragments().get(sharedBlueId),
                    "cached immutable evidence should be shared by identity");
        }

        Node authoredClosure = NodePathEditor.getOrNull(
                second.exactEvent(), "/message/request/document");
        assertNotNull(authoredClosure);
        assertEquals(NodeWireForm.get(payNote),
                NodeWireForm.get(authoredClosure));
        assertEquals(NodeWireForm.get(secondEvent),
                NodeWireForm.get(second.exactEvent()));

        Node callerCopy = second.fragments().get(payNoteBlueId)
                .materialize();
        callerCopy.value("altered by caller");
        assertEquals(payNoteBlueId,
                DirectBlueIdCalculator.calculateBlueId(
                        second.fragments().get(payNoteBlueId)
                                .materialize()));
    }

    @Test
    void shouldRejectAClaimedIdentityForAlteredImmutableContent() {
        // given
        CoordinationEventAdmissionCompiler compiler = compiler(
                new CoordinationEventAdmissionMetrics());
        Node authored = event(3L, payNote());
        String authoredBlueId = DirectBlueIdCalculator.calculateBlueId(
                authored);
        Node altered = authored.clone();
        NodePathEditor.put(altered,
                "/message/request/document/amountMinor",
                new Node().value(1L));

        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(authoredBlueId, altered));
    }

    private static CoordinationEventAdmissionCompiler compiler(
            CoordinationEventAdmissionMetrics metrics) {
        return new CoordinationEventAdmissionCompiler(
                "reusable-subtree-environment",
                "reusable-subtree-language",
                "reusable-subtree-provider",
                CoordinationDocumentSplitter.forEventSplitting(),
                8,
                256,
                metrics);
    }

    private static Node event(long timestamp, Node payNote) {
        return new Node().properties(
                "type", new Node().value("Coordination/Timeline Entry"),
                "timestamp", new Node().value(timestamp),
                "actor", new Node().properties(
                        "type", new Node().value("MyOS/Principal Actor"),
                        "accountId", new Node().value("alice")),
                "message", new Node().properties(
                        "type", new Node().value(
                                "Coordination/Operation Request"),
                        "operation", new Node().value(
                                "attachPayNoteAsCustomer"),
                        "request", new Node().properties(
                                "document", payNote.clone())));
    }

    private static Node payNote() {
        Map<String, Node> properties = new LinkedHashMap<String, Node>();
        properties.put("type", new Node().value("MyOS/PayNote"));
        properties.put("paymentId", new Node().value("package-payment"));
        properties.put("amountMinor", new Node().value(130000L));
        properties.put("currency", new Node().value("PLN"));
        properties.put("contracts", new Node().properties(
                "checkpoint", new Node().value("created"),
                "guarantor", new Node().value("myos-admin")));
        return new Node().properties(properties);
    }
}
