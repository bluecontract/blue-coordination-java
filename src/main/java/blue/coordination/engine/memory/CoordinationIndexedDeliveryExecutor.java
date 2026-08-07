package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationCommittedDelivery;
import blue.coordination.engine.api.IndexedSessionCandidates;
import blue.coordination.engine.api.PrefetchPolicy;
import blue.coordination.engine.api.StoredCoordinationEvent;

/** Host boundary used by resumable fan-out to execute one Root delivery. */
public interface CoordinationIndexedDeliveryExecutor {

    /** Returns evidence committed atomically with the authoritative session. */
    CoordinationCommittedDelivery deliver(
            StoredCoordinationEvent event,
            IndexedSessionCandidates target,
            PrefetchPolicy prefetchPolicy);
}
