package blue.coordination.sdk;

import blue.coordination.api.DocumentId;

import java.util.Objects;

/** Starts target-aware operation calls. */
public final class OperationGateway {
    private final SdkCoordinationRuntime runtime;

    OperationGateway(SdkCoordinationRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /** Selects one exact managed-document target without supplying recipients. */
    public OperationCall on(DocumentHandle document) {
        return new OperationCall(
                runtime,
                runtime.selectTarget(Objects.requireNonNull(
                        document, "document")));
    }

    /** Selects a lineage by id, including a target that is currently absent. */
    public OperationCall on(DocumentId documentId) {
        return new OperationCall(
                runtime,
                runtime.selectTarget(Objects.requireNonNull(
                        documentId, "documentId")));
    }
}
