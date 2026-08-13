package blue.coordination.internal;

import blue.coordination.api.TimelineEntry;
import blue.language.processor.ExternalOrderKey;

import java.util.Objects;

/** Closed result of one incremental historical-source probe. */
sealed interface HistoricalStep permits HistoricalStep.EligibleEntry,
        HistoricalStep.Complete, HistoricalStep.CompleteEmpty,
        HistoricalStep.Unavailable, HistoricalStep.InvalidEvidence {
    /** One canonical entry and the exact exclusive cursor after consuming it. */
    record EligibleEntry(
            TimelineEntry entry,
            ExternalOrderKey nextExclusive) implements HistoricalStep {
        public EligibleEntry {
            entry = Objects.requireNonNull(entry, "entry");
            nextExclusive = Objects.requireNonNull(
                    nextExclusive, "nextExclusive");
            if (!nextExclusive.equals(entry.sourceOrderKey())) {
                throw new IllegalArgumentException(
                        "nextExclusive must identify the eligible entry");
            }
        }
    }

    /** The queried interval is complete but contained ineligible evidence. */
    record Complete(CompletenessEvidence evidence)
            implements HistoricalStep {
        public Complete {
            evidence = Objects.requireNonNull(evidence, "evidence");
        }
    }

    /** The queried interval is proven complete and contained no entries. */
    record CompleteEmpty(CompletenessEvidence evidence)
            implements HistoricalStep {
        public CompleteEmpty {
            evidence = Objects.requireNonNull(evidence, "evidence");
        }
    }

    /** The source cannot currently prove or produce the next result. */
    record Unavailable(String diagnostic) implements HistoricalStep {
        public Unavailable {
            diagnostic = requireText(diagnostic, "diagnostic");
        }
    }

    /** Supplied source/progress evidence is invalid, not absent. */
    record InvalidEvidence(String diagnostic) implements HistoricalStep {
        public InvalidEvidence {
            diagnostic = requireText(diagnostic, "diagnostic");
        }
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
