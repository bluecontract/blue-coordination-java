package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationCommittedDelivery;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.StoredCoordinationEvent;

import java.util.Optional;

/** Reads an authoritative session-store receipt committed with Root state. */
public interface CoordinationCommittedDeliveryProbe {

    Optional<CoordinationCommittedDelivery> committedDelivery(
            StoredCoordinationEvent event,
            DocumentSessionId sessionId);

    static CoordinationCommittedDeliveryProbe none() {
        return (event, sessionId) -> Optional.empty();
    }
}
