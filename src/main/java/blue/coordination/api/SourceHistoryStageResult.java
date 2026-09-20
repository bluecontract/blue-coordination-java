package blue.coordination.api;

import java.util.List;
import java.util.Objects;

/** One complete source stage, before host publication and without retrying its requesting parent. */
public record SourceHistoryStageResult(SourceHistoryStageContext selection, SourceHistoryPrerequisiteResult result,
        List<DocumentId> resultOwners, boolean selectionInvalidated) {
    /** Retains the complete entry/result union through source publication, including splits and new births. */
    public SourceHistoryStageResult {
        Objects.requireNonNull(selection); Objects.requireNonNull(result); resultOwners = List.copyOf(resultOwners);
        if (!selection.prerequisite().equals(result.selection())
                || !resultOwners.equals(resultOwners.stream().distinct().sorted().toList())
                || !resultOwners.containsAll(selection.entryOwners().stream().map(SourceHistoryStageContext.Owner::documentId).toList()))
            throw new IllegalArgumentException("Source result differs from its frozen selection or complete owners");
    }

    /** False for rejected physical publication; the mutable owner must be discarded, even if PROCESS completed. */
    public boolean committable() {
        return result.processing().map(receipt -> receipt.managedEpochApplicationAttempts().stream()
                .noneMatch(attempt -> attempt.publicationFailure().isPresent())
                && java.util.stream.Stream.concat(receipt.contractsAttemptsByEntry().values().stream().flatMap(List::stream),
                        receipt.rootedRetainedAttempts().stream().map(ProcessingDrainReceipt.RootedRetainedAttempt::attempt))
                .noneMatch(attempt -> attempt.attempt().isComplete() && attempt.attempt().processResult().commits()
                        && !attempt.published())).orElse(true);
    }
}
