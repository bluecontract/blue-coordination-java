package blue.coordination.engine.spi;

import blue.coordination.engine.api.CommitOutcome;
import blue.coordination.engine.api.CoordinationAtomicCommitPlan;
import blue.coordination.engine.api.DocumentAdmissionCommit;
import blue.coordination.engine.api.DocumentAdmissionResult;
import blue.coordination.engine.api.DocumentEpochSnapshot;
import blue.coordination.engine.api.DocumentRemovalResult;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.ManagedDocumentSnapshot;

import java.util.Optional;

/** Compact authoritative session, epoch, progress, and outbox transaction SPI. */
public interface CoordinationSessionStore {
    Optional<ManagedDocumentSnapshot> findSession(DocumentSessionId id);
    Optional<DocumentEpochSnapshot> findEpoch(DocumentSessionId id, long epoch);
    DocumentAdmissionResult admit(DocumentAdmissionCommit commit);
    CommitOutcome commit(CoordinationAtomicCommitPlan plan);
    DocumentRemovalResult remove(DocumentSessionId id, long expectedEpoch);
}
