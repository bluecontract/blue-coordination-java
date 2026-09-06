package blue.coordination.external;

import blue.coordination.api.ExactValue;
import blue.language.model.Node;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.closure.*;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DirectedTimelineMembershipTest {
    @Test void omittedTimelineCannotSkipItsEarlierEntryEvenWithAHostFence() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var source = born(f, source(f, "TA", "TB"), List.of(), List.of());
            var cut = snapshot(List.of(source), List.of());
            var a10 = input("TA", 10); var b5 = input("TB", 5);
            var partial = evidence(cut, Set.of("TA"), List.of(prefix("TA", a10)));
            var missing = assertInstanceOf(CoordinationCore.NeedEvidence.class, f.core.evaluate(intent(source), partial));
            assertEquals(List.of("timeline-membership-missing:TB"), missing.keys());
            var earlier = assertInstanceOf(CoordinationCore.MetadataProgress.class, f.core.evaluate(intent(source),
                    evidence(cut, Set.of("TA", "TB"), List.of(prefix("TA", a10), prefix("TB", b5)))));
            assertEquals(b5.entry().blueId(), earlier.input().entry().blueId());
        }
    }

    @Test void completeChannelMetadataSelectsWithoutAnyApplicationBodyAndRejectsAnotherState() {
        try (var f = new CanonicalSourceHistoryTest.Fixture(); var contracts = new BlueClosureContracts(f.processor)) {
            var source = born(f, source(f, "TA", "TB"), List.of(), List.of());
            var full = snapshot(List.of(source), List.of());
            var metadata = contracts.captureRootMetadata(full);
            var noBodies = full.retainResidentBodies(Set.of(), metadata);
            assertFalse(noBodies.managedDocument(source.documentId()).hasResidentBody());
            assertThrows(blue.language.processor.ExecutionEvidenceUnavailableException.class,
                    () -> noBodies.managedDocument(source.documentId()).document());
            var progress = assertInstanceOf(CoordinationCore.MetadataProgress.class, f.core.evaluate(intent(source),
                    evidence(noBodies, Set.of("TA", "TB"), List.of(prefix("TA", input("TA", 10)), prefix("TB", input("TB", 5))))));
            assertEquals("TB", progress.input().timelineId());
            assertNull(metadata.get(0).routingDocument().getProperties());
            var staleEpoch = new ManagedDocumentSnapshot(source.documentId(), source.blueId(), source.document(), true, false, true, source.epoch() + 1, 0);
            assertThrows(InvalidExecutionEvidenceException.class, () -> metadata.get(0).verifyState(staleEpoch));
            assertThrows(InvalidExecutionEvidenceException.class,
                    () -> metadata.get(0).verifyRegistry("sha256:" + "f".repeat(64)));
        }
    }

    @Test void sourceIgnoresReverseOrdersButTheirOwnDirectedCutIncludesTheSourceTimeline() {
        try (var f = new CanonicalSourceHistoryTest.Fixture(); var contracts = new BlueClosureContracts(f.processor)) {
            var source = born(f, source(f, "TS"), List.of(), List.of()); f.nodes.put(source.blueId(), source.document());
            var authoredOrder = f.authored("""
                    name: Reverse Order
                    contracts:
                      ingress:
                        type: Coordination/Timeline Channel
                        timeline: {type: MyOS/MyOS Timeline, timelineId: TO}
                        actor: {type: MyOS/Principal Actor, accountId: source-actor}
                      embedded:
                        type: {blueId: %s}
                        paths: [/child]
                    """.formatted(blue.language.processor.registry.RuntimeBlueIds.PROCESS_EMBEDDED), Map.of("child", source.blueId()));
            var binding = f.binding(authoredOrder, source);
            var order = born(f, authoredOrder, List.of(source), List.of(binding));
            var full = snapshot(List.of(source, order), List.of(binding));
            var noBodies = full.retainResidentBodies(Set.of(), contracts.captureRootMetadata(full));
            var progress = assertInstanceOf(CoordinationCore.MetadataProgress.class, f.core.evaluate(intent(source),
                    evidence(noBodies, Set.of("TS"), List.of(prefix("TS", input("TS", 10))))));
            assertEquals("TS", progress.input().timelineId());
            var needs = assertInstanceOf(CoordinationCore.NeedEvidence.class, f.core.evaluate(intent(order),
                    evidence(noBodies, Set.of("TO"), List.of(prefix("TO", input("TO", 10))))));
            assertEquals(List.of("timeline-membership-missing:TS"), needs.keys());
            assertThrows(IllegalArgumentException.class, () -> noBodies.managedDocument(order.documentId()).reusableAuthority().orElseThrow().verifyUnchanged(
                    ClosureEvidenceFactory.affectedClosure(0, noBodies.managedDocuments(), List.of(), noBodies.components(), noBodies.publicRootDocumentIds())));
        }
    }

    @Test void aScalarLocatorNeverAuthenticatesAnotherTypedProvider() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var source = born(f, source(f, "TA"), List.of(), List.of());
            var entry = input("TA", 5).entry().copyNode();
            entry.getProperties().get("timeline").type(new Node().blueId(blue.repo.coordination.Timeline.blueId()));
            var changed = ExactValue.verified(entry);
            assertThrows(IllegalArgumentException.class, () -> new CoordinationCore.TimelineInput("TA", 5, changed, changed, List.of()));
            var extra = assertInstanceOf(CoordinationCore.NeedEvidence.class, f.core.evaluate(intent(source),
                    evidence(snapshot(List.of(source), List.of()), Set.of("TA", "unrelated"), List.of(prefix("TA", input("TA", 5))))));
            assertEquals(List.of("timeline-membership-extraneous:unrelated"), extra.keys());
        }
    }

    @Test void inactiveTargetRemainsInTheSealedReadCutButNeverJoinsLiveTimelineSelection() {
        try (var f = new CanonicalSourceHistoryTest.Fixture(); var contracts = new BlueClosureContracts(f.processor)) {
            var live = born(f, source(f, "TA"), List.of(), List.of());
            var inactive = born(f, source(f, "TB"), List.of(), List.of());
            var template = f.binding(live, inactive);
            var dormant = ManagedOccurrenceBinding.derived(template.bindingPolicyIdentity(), live.documentId(),
                    template.sourceAddress(), inactive.documentId(), inactive.blueId(), false, null);
            var full = snapshot(List.of(live, inactive), List.of(dormant));
            // Only the eligible Root has routing metadata. The inactive target has
            // closed state authority, but no application body or channel metadata.
            var cold = full.retainResidentBodies(Set.of(), contracts.captureRootMetadata(full, Set.of(live.documentId())));
            assertTrue(cold.managedDocument(inactive.documentId()).rootMetadata().isEmpty());
            assertFalse(cold.managedDocument(inactive.documentId()).hasResidentBody());
            var progress = assertInstanceOf(CoordinationCore.MetadataProgress.class, f.core.evaluate(intent(live),
                    evidence(cold, Set.of("TA"), List.of(prefix("TA", input("TA", 10)), prefix("TB", input("TB", 5))))));
            assertEquals("TA", progress.input().timelineId());
            assertEquals(List.of(dormant), cold.managedDocument(live.documentId()).reusableAuthority().orElseThrow().outgoingBindings());
            DocumentId foreign = f.source().documentId();
            assertThrows(IllegalArgumentException.class, () -> contracts.captureRootMetadata(cold, Set.of(foreign)));
        }
    }

    @Test void ambientNewerSourceChannelsCannotChooseAnObserversNextInput() {
        try (var f = new CanonicalSourceHistoryTest.Fixture(); var contracts = new BlueClosureContracts(f.processor)) {
            var oldSource = born(f, source(f, "TB5"), List.of(), List.of()); f.nodes.put(oldSource.blueId(), oldSource.document());
            var authored = f.authored("""
                    name: observer with exact historical pin
                    contracts:
                      embedded:
                        type: {blueId: %s}
                        paths: [/child]
                    """.formatted(blue.language.processor.registry.RuntimeBlueIds.PROCESS_EMBEDDED), Map.of("child", oldSource.blueId()));
            var binding = f.binding(authored, oldSource);
            var observer = born(f, authored, List.of(oldSource), List.of(binding));
            Node later = oldSource.document();
            later.getContracts().getProperties().get("channel0").getProperties().get("timeline").getProperties().get("timelineId").value("TB100");
            var exact = ExactValue.verified(later);
            var ambient = new ManagedDocumentSnapshot(oldSource.documentId(), exact.blueId(), exact.copyNode(), true, false, true, oldSource.epoch() + 1, 0);
            var full = ClosureEvidenceFactory.affectedClosure(0, List.of(ambient, observer), List.of(binding),
                    List.of(ClosureEvidenceFactory.acyclicComponent(ambient), ClosureEvidenceFactory.acyclicComponent(observer)),
                    List.of(ambient.documentId(), observer.documentId()), List.of(ManagedReadPin.fromExactEvidence(oldSource.documentId(), oldSource.blueId(), oldSource.document(), null)));
            var cold = full.retainResidentBodies(Set.of(), contracts.captureRootMetadata(full));
            var needs = assertInstanceOf(CoordinationCore.NeedEvidence.class, f.core.evaluate(intent(observer),
                    evidence(cold, Set.of("TB100"), List.of(prefix("TB100", input("TB100", 10))))));
            assertEquals(List.of("root-channel-logical-view:" + oldSource.documentId().value()), needs.keys());
        }
    }

    private static ManagedDocumentSnapshot source(CanonicalSourceHistoryTest.Fixture f, String... timelines) {
        StringBuilder yaml = new StringBuilder("name: Exact Timeline membership\nlargeApplicationBody: not-routing-evidence\ncontracts:\n");
        for (int i = 0; i < timelines.length; i++) yaml.append("  channel").append(i).append(":\n")
                .append("    type: Coordination/Timeline Channel\n    timeline: {type: MyOS/MyOS Timeline, timelineId: ").append(timelines[i])
                .append("}\n    actor: {type: MyOS/Principal Actor, accountId: source-actor}\n");
        return f.authored(yaml.toString(), Map.of());
    }
    private static ManagedDocumentSnapshot born(CanonicalSourceHistoryTest.Fixture f, ManagedDocumentSnapshot target,
            List<ManagedDocumentSnapshot> dependencies, List<ManagedOccurrenceBinding> bindings) {
        List<ManagedDocumentSnapshot> states = new ArrayList<>(dependencies); states.add(target);
        var result = assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(
                new CoordinationCore.WorkIntent(target.documentId(), CoordinationCore.OperationKind.INITIALIZATION),
                evidence(snapshot(states, bindings), Set.of(), List.of())));
        var encoded = OperationReceiptCodec.encode(result, f.blobs::put, FrozenNodeEvidenceCodec.Limits.defaults());
        return OperationReceiptCodec.restoreState(encoded.receiptIdentity(), target.documentId(), true, 0, f.blobs::get, FrozenNodeEvidenceCodec.Limits.defaults());
    }
    private static AffectedClosureSnapshot snapshot(List<ManagedDocumentSnapshot> states, List<ManagedOccurrenceBinding> bindings) {
        return ClosureEvidenceFactory.affectedClosure(0, states, bindings,
                states.stream().map(ClosureEvidenceFactory::acyclicComponent).toList(), states.stream().map(ManagedDocumentSnapshot::documentId).toList());
    }
    private static CoordinationCore.WorkIntent intent(ManagedDocumentSnapshot target) { return new CoordinationCore.WorkIntent(target.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT); }
    private static CoordinationCore.EvaluationEvidence evidence(AffectedClosureSnapshot cut, Set<String> timelines, List<CoordinationCore.TimelinePrefix> prefixes) {
        return new CoordinationCore.EvaluationEvidence(cut, timelines, prefixes, Optional.empty(), List.of(new CoordinationCore.ReadFence("host-cut", "1")), Map.of());
    }
    private static CoordinationCore.TimelinePrefix prefix(String timeline, CoordinationCore.TimelineInput entry) { return new CoordinationCore.TimelinePrefix(timeline, 30, List.of(entry)); }
    private static CoordinationCore.TimelineInput input(String timeline, long micros) {
        var entry = ExactValue.verified(new Node().type(new Node().blueId(blue.repo.coordination.TimelineEntry.blueId()))
                .properties("timeline", new Node().type(new Node().blueId(blue.repo.myos.MyOSTimeline.blueId())).properties("timelineId", new Node().value(timeline)))
                .properties("timestamp", new Node().value(BigInteger.valueOf(micros))).properties("message", new Node().value("unmatched"))
                .properties("actor", new Node().type(new Node().blueId(blue.repo.myos.PrincipalActor.blueId())).properties("accountId", new Node().value("other-actor")))
                .properties("source", new Node().value("test")));
        return new CoordinationCore.TimelineInput(timeline, micros, entry, entry, List.of());
    }
}
