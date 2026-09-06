package blue.coordination.external;

import blue.language.processor.closure.*;

import java.util.*;

/** Fixture-owned original admission authority. Never a production history worker's policy fallback. */
final class OriginalSourceInputTestSupport {
    record Admitted(CanonicalSourceHistory.Request request, CoordinationCore.EvaluationEvidence evidence,
                    SourceInputAdmission admission) { }

    static Admitted admit(CoordinationCore core, CanonicalSourceHistory.Cursor cursor, CanonicalSourceHistory.Request request,
                          CoordinationCore.EvaluationEvidence evidence, SameOriginAttachmentPolicy originalSelections,
                          Map<String, byte[]> originalAdmissionStore) {
        if (cursor.successfulView().isEmpty()) return new Admitted(request, evidence, null);
        var selected = evidence.prefixes().stream().filter(prefix -> evidence.relevantTimelines().contains(prefix.timelineId()))
                .flatMap(prefix -> prefix.inputs().stream()).filter(input -> input.order().compareTo(request.inclusiveCut()) <= 0)
                .filter(input -> cursor.handledThrough().isEmpty() || input.order().compareTo(cursor.handledThrough().orElseThrow()) > 0)
                .min(Comparator.comparing(CoordinationCore.TimelineInput::order));
        if (selected.isEmpty()) return new Admitted(request, evidence, null);
        Set<DocumentId> members = new TreeSet<>();
        evidence.snapshot().components().stream().filter(component -> component.orderedMemberDocumentIds().contains(cursor.source()))
                .forEach(component -> members.addAll(component.orderedMemberDocumentIds()));
        originalSelections.entries().forEach(choice -> members.add(choice.creatorLineage()));
        Map<DocumentId, String> bases = new TreeMap<>(), predecessors = new TreeMap<>();
        for (DocumentId member : members) {
            bases.put(member, SourceExecutionBasis.identity(member, core.environment(), core.executionPolicy()));
            String previous = member.equals(cursor.source()) ? cursor.semanticPredecessor().orElse(null) : evidence.precedingOperations().get(member);
            if (previous != null) predecessors.put(member, previous);
        }
        // This fixture fixes the original selections before preparation and models their independent admission.
        String admittedRoot = SourceInputAdmission.encodeCandidate(selected.orElseThrow(), bases, predecessors, originalSelections,
                originalAdmissionStore::put, FrozenNodeEvidenceCodec.Limits.defaults());
        var admission = SourceInputAdmission.restore(admittedRoot, originalAdmissionStore::get, FrozenNodeEvidenceCodec.Limits.defaults());
        return new Admitted(new CanonicalSourceHistory.Request(request.source(), request.inclusiveCut(),
                Map.of(selected.orElseThrow().entry().blueId(), admittedRoot)), evidence.withSourceInputAdmissions(List.of(admission)), admission);
    }
}
