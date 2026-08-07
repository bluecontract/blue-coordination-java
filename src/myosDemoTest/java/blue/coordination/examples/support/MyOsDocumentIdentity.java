package blue.coordination.examples.support;

import blue.language.processor.ExternalOrderKey;

import java.util.Objects;

/** Host logical identity plus immutable initial-content evidence. */
public record MyOsDocumentIdentity(
        String logicalId,
        String initialDocumentBlueId)
        implements Comparable<MyOsDocumentIdentity> {

    public MyOsDocumentIdentity {
        String checkedLogical = Objects.requireNonNull(
                logicalId, "logicalId");
        if (checkedLogical.isBlank()
                || !checkedLogical.equals(checkedLogical.trim())) {
            throw new IllegalArgumentException(
                    "logicalId must be exact non-blank text");
        }
        String checked = Objects.requireNonNull(
                initialDocumentBlueId, "initialDocumentBlueId");
        if (checked.isBlank() || !checked.equals(checked.trim())) {
            throw new IllegalArgumentException(
                    "initialDocumentBlueId must be exact non-blank text");
        }
        logicalId = checkedLogical;
        initialDocumentBlueId = checked;
    }

    @Override
    public int compareTo(MyOsDocumentIdentity other) {
        MyOsDocumentIdentity checked = Objects.requireNonNull(other, "other");
        int compared = ExternalOrderKey.compareTextCodePoints(
                logicalId, checked.logicalId);
        return compared != 0
                ? compared
                : ExternalOrderKey.compareTextCodePoints(
                        initialDocumentBlueId,
                        checked.initialDocumentBlueId);
    }
}
