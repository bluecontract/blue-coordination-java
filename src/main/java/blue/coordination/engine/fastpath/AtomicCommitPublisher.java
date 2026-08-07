package blue.coordination.engine.fastpath;

import blue.coordination.engine.api.CommitOutcome;

/**
 * One storage transaction/CAS boundary. Implementations publish fragment
 * inventory, processing views, session, epoch, outbox, delivery receipt and
 * subscription-index generation together, or publish none of them.
 */
public interface AtomicCommitPublisher {
    CommitOutcome compareAndPublish(PreparedAtomicCommit commit);
}
