package blue.coordination.examples.support;

import java.util.Objects;

/** Document plus phase timings from the same successful start operation. */
public record MyOsDocumentStartResult(
        MyOsDemoDocument document,
        MyOsDocumentStartTiming timing) {

    public MyOsDocumentStartResult {
        document = Objects.requireNonNull(document, "document");
        timing = Objects.requireNonNull(timing, "timing");
        if (!document.key().equals(timing.documentKey())) {
            throw new IllegalArgumentException(
                    "document and timing keys must match");
        }
    }
}
