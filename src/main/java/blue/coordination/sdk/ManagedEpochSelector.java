package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.language.identity.BlueIds;
import blue.language.model.value.BlueNumbers;

import java.util.Objects;

/**
 * Advanced exact selector for one otherwise ambiguous retained source state.
 *
 * <p>Epoch {@code -1} selects the authored pre-initialization position; it is
 * never a durable receipt epoch. Normal unambiguous current and retained states
 * require no selector.</p>
 */
public record ManagedEpochSelector(
        DocumentId sourceDocumentId,
        long sourceEpoch,
        String expectedSourceBlueId,
        String targetOccurrencePath) {

    /** Creates one exact retained-source selector for a target occurrence. */
    public static ManagedEpochSelector exact(
            DocumentId sourceDocumentId,
            long sourceEpoch,
            String expectedSourceBlueId,
            String targetOccurrencePath) {
        return new ManagedEpochSelector(
                sourceDocumentId,
                sourceEpoch,
                expectedSourceBlueId,
                targetOccurrencePath);
    }

    /** Validates one stable lineage, epoch, exact state, and target occurrence. */
    public ManagedEpochSelector {
        sourceDocumentId = Objects.requireNonNull(
                sourceDocumentId, "sourceDocumentId");
        long maximum = BlueNumbers.MAX_INTEROPERABLE_INTEGER.longValueExact();
        if (sourceEpoch < -1L || sourceEpoch > maximum) {
            throw new IllegalArgumentException(
                    "sourceEpoch must be -1 or a non-negative safe integer");
        }
        expectedSourceBlueId = BlueIds.requireBlueIdOrCyclicMember(
                expectedSourceBlueId, "/expectedSourceBlueId");
        targetOccurrencePath = SdkPreconditions.requireOccurrencePath(
                targetOccurrencePath);
    }
}
