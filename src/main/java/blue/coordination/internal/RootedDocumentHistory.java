package blue.coordination.internal;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.RootedProcessingContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Admission-owned history facts retained with a document, independently of its head. */
record RootedDocumentHistory(Map<String, Object> descriptor, String identity,
        String admissionInvocationIdentity, String admissionCompanionIdentity) {
    RootedDocumentHistory {
        descriptor = Map.copyOf(Objects.requireNonNull(descriptor, "descriptor"));
        if (!RootedProcessingContext.historyBasisIdentity(descriptor).equals(identity)) {
            throw new IllegalArgumentException("Rooted history identity differs from its exact descriptor");
        }
        Objects.requireNonNull(admissionInvocationIdentity, "admissionInvocationIdentity");
        Objects.requireNonNull(admissionCompanionIdentity, "admissionCompanionIdentity");
    }

    /** Called only while staging a fully verified successful admission. */
    static RootedDocumentHistory admitted(DocumentId document, ExactValue authored,
            CoordinationEngine.AdmissionPolicy policy, ExternalOrderKey frontier,
            ClosureInvocationInput input, ClosureProcessResult result,
            String runtimeSemanticsIdentity, RootedBeginningAdmission beginning) {
        if (input.operation() != ClosureInvocationInput.Operation.ADMIT_CLOSURE
                || !result.commits() || result.platformCommitCompanion() == null
                || !result.invocationIdentity().equals(input.invocationIdentity())
                || !result.inputClosureIdentity().equals(input.snapshot().closureIdentity())
                || input.snapshot().managedDocument(ContractsClosureAdapter.closureId(document)) == null) {
            throw new IllegalArgumentException("History basis requires its complete successful admission evidence");
        }
        Map<String, Object> admission;
        if (beginning != null) {
            if (policy != CoordinationEngine.AdmissionPolicy.FROM_NOW
                    || !RootedBeginningAdmission.BOUND.equals(frontier)) {
                throw new IllegalArgumentException("BEGINNING proof cannot change another admission mode or bound");
            }
            beginning.requireFor(input, result);
            admission = Map.of("mode", "FROM_NOW", "lowerExclusiveOrder", Map.of("kind", "BEGINNING"));
        } else if (policy == CoordinationEngine.AdmissionPolicy.FULL_HISTORY) {
            admission = Map.of("mode", "FULL_HISTORY");
        } else {
            List<Object> order = frontier.components();
            if (order.size() != 3) {
                throw new IllegalArgumentException("Rooted bounded admission requires an exact logical activation entry");
            }
            admission = Map.of("mode", policy == CoordinationEngine.AdmissionPolicy.FROM_NOW
                            ? "FROM_NOW" : "FROM_FRONTIER",
                    "lowerExclusiveOrder", Map.of("timestampUs", order.get(0).toString(),
                            "timelineBlueId", order.get(1), "entryBlueId", order.get(2)));
        }
        Map<String, Object> descriptor = Map.of("documentId", document.value(),
                "initialDocumentBlueId", authored.blueId(),
                "runtimeSemanticsIdentity", runtimeSemanticsIdentity, "admission", admission);
        return new RootedDocumentHistory(descriptor,
                RootedProcessingContext.historyBasisIdentity(descriptor), input.invocationIdentity(),
                result.platformCommitCompanion().companionIdentity());
    }

    /** Staged only with the complete authenticated, owned birth transaction. */
    static RootedDocumentHistory created(DocumentId document, ExactValue authored,
            ContractsClosureAdapter.CohortInvocation invocation, ClosureProcessResult result,
            ExternalOrderKey order) {
        var input = invocation.input();
        var id = ContractsClosureAdapter.closureId(document);
        var projection = result.rootedProjection();
        var plan = invocation.managedDraftPlan();
        var before = input.snapshot().managedDocument(id);
        if (projection == null || !result.commits() || !projection.owns(id)
                || !result.invocationIdentity().equals(input.invocationIdentity())
                || !result.inputClosureIdentity().equals(input.snapshot().closureIdentity())
                || plan == null || !plan.drafts().containsKey(document)
                || before == null || before.initialized() || before.epoch() != 0L
                || !before.blueId().equals(authored.blueId()) || order.components().size() != 3) {
            throw new IllegalArgumentException("Created history requires its exact owned causal birth");
        }
        var expected = plan.expectedOccurrences().stream()
                .filter(row -> row.targetDocumentId().equals(document)).findFirst().orElseThrow();
        var birth = plan.prospectiveOccurrence(input, expected);
        if (!projection.owns(birth.sourceDocumentId())
                || result.occurrenceBindings().stream().noneMatch(row -> row.active()
                        && row.occurrenceIdentity().equals(birth.occurrenceIdentity())
                        && row.targetDocumentId().equals(id))) {
            throw new IllegalArgumentException("Created history lacks its committed birth occurrence");
        }
        List<Object> key = order.components();
        Map<String, Object> admission = Map.of("mode", "CREATED_IN_OPERATION",
                "creatorOperationIdentity", projection.invocationIdentity(),
                "birthOccurrenceIdentity", birth.occurrenceIdentity(),
                "lowerExclusiveOrder", Map.of("timestampUs", key.get(0).toString(),
                        "timelineBlueId", key.get(1), "entryBlueId", key.get(2)));
        Map<String, Object> descriptor = Map.of("documentId", document.value(),
                "initialDocumentBlueId", authored.blueId(),
                "runtimeSemanticsIdentity", BundledContracts10Release.manifest().contractsRelease(),
                "admission", admission);
        return new RootedDocumentHistory(descriptor, RootedProcessingContext.historyBasisIdentity(descriptor),
                input.invocationIdentity(), result.platformCommitCompanion().companionIdentity());
    }
}
