package blue.coordination.engine.memory;

import blue.coordination.engine.api.DocumentSessionId;

import java.util.Objects;

/** Unforgeable outside the in-memory ledger package; binds one live attempt. */
public final class CoordinationDeliveryAdmission {

    private final String eventBlueId;
    private final DocumentSessionId sessionId;
    private final int attemptNumber;

    CoordinationDeliveryAdmission(
            String eventBlueId,
            DocumentSessionId sessionId,
            int attemptNumber) {
        this.eventBlueId = requireText(eventBlueId, "eventBlueId");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (attemptNumber <= 0) {
            throw new IllegalArgumentException(
                    "attemptNumber must be positive");
        }
        this.attemptNumber = attemptNumber;
    }

    public String eventBlueId() { return eventBlueId; }
    public DocumentSessionId sessionId() { return sessionId; }
    public int attemptNumber() { return attemptNumber; }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return checked;
    }
}
