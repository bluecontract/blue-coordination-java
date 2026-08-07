package blue.coordination.engine.spi;

import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.IndexedSessionCandidates;
import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.language.processor.ExternalOrderKey;

import java.util.List;

/** Derived cross-session index; committed session snapshots remain authority. */
public interface CoordinationSubscriptionIndex {

    void replaceSession(ManagedDocumentSnapshot snapshot);

    void removeSession(DocumentSessionId sessionId);

    CoordinationTargetCursor openCandidates(
            List<String> exactEventSubscriptionKeys,
            String sourceChannel,
            ExternalOrderKey eventOrderKey);

    List<IndexedSessionCandidates> candidates(
            List<String> exactEventSubscriptionKeys,
            String sourceChannel,
            ExternalOrderKey eventOrderKey);
}
