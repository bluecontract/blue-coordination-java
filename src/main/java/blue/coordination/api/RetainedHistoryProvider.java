package blue.coordination.api;

import blue.coordination.sdk.AdvancedCoordination;

import java.util.Objects;
import java.util.UUID;

/**
 * Bounded transport of retained source evidence from an explicitly trusted
 * producer. This interface does not import history or authorize publication.
 */
@FunctionalInterface
public interface RetainedHistoryProvider {
    /** Exports only committed evidence from the supplied owning SDK runtime. */
    static RetainedHistoryProvider from(AdvancedCoordination source,
            java.security.KeyPair producerKeys) {
        return new RuntimeRetainedHistoryProvider(
                Objects.requireNonNull(source, "source").rawEngine(),
                producerKeys);
    }

    /**
     * Reads a complete page. Missing retained receipts or content must throw
     * {@link Unavailable}; they must never be reported as an empty history.
     */
    RetainedHistoryPage read(Request request);

    /** Exact scope of one request, including a fresh anti-replay challenge. */
    record Request(DocumentId documentId, long firstEpoch, int limit,
                   String challenge) {
        public static final int MAX_PAGE_SIZE = 256;

        public Request {
            Objects.requireNonNull(documentId, "documentId");
            if (firstEpoch < 0 || firstEpoch > 9_007_199_254_740_991L
                    || limit < 1 || limit > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("Invalid retained page bounds");
            }
            Objects.requireNonNull(challenge, "challenge");
            if (challenge.isBlank() || challenge.length() > 128) {
                throw new IllegalArgumentException("Invalid retained page challenge");
            }
        }

        /** New reads use a fresh challenge; retries may retain the request. */
        public static Request fresh(DocumentId documentId, long firstEpoch,
                int limit) {
            return new Request(documentId, firstEpoch, limit,
                    UUID.randomUUID().toString());
        }
    }

    /** Temporary absence of evidence, distinct from invalid supplied evidence. */
    final class Unavailable extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public Unavailable(String message) {
            super(message);
        }
    }
}
