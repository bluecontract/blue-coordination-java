package blue.coordination.engine.memory;

import blue.coordination.engine.api.DocumentSessionId;

/** Low-overhead observer for the parallel Root-preparation boundary. */
public interface CoordinationRootPreparationObserver {

    void prepared(DocumentSessionId sessionId, long elapsedNanos);

    void committed(DocumentSessionId sessionId, long elapsedNanos);

    void discarded(DocumentSessionId sessionId);

    void failed(DocumentSessionId sessionId, Throwable failure);

    static CoordinationRootPreparationObserver none() {
        return None.INSTANCE;
    }

    enum None implements CoordinationRootPreparationObserver {
        INSTANCE;

        @Override
        public void prepared(DocumentSessionId sessionId, long elapsedNanos) {
        }

        @Override
        public void committed(DocumentSessionId sessionId, long elapsedNanos) {
        }

        @Override
        public void discarded(DocumentSessionId sessionId) {
        }

        @Override
        public void failed(DocumentSessionId sessionId, Throwable failure) {
        }
    }
}
