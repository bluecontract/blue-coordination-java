package blue.coordination.integration;

import blue.coordination.api.DocumentId;

/** Read-only catch-up plan compatibility projection shared by test suites. */
record CatchUpPlan(Link link, Status status) {
    enum Status {
        PENDING_INITIALIZATION,
        REPLAYING,
        COMPLETE,
        BLOCKED
    }

    record Link(
            DocumentId parentDocumentId,
            DocumentId childDocumentId,
            String occurrencePath,
            long appliedChildEpoch,
            long activationGeneration) {
    }
}
