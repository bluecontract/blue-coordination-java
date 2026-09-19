package blue.coordination.api.storage;

import blue.coordination.api.SourceHistoryStageResult;
import blue.coordination.internal.RootedEngineStorage;

/** Closed source-stage observations, independent of a live SDK, provider or future selection. */
public final class SourceHistoryStageStorage {
    private SourceHistoryStageStorage() { }
    /** Encodes exact admission or historical results under physical byte/depth limits. */
    public static byte[] encode(SourceHistoryStageResult result, int maximumBytes, int maximumDepth) {
        return RootedEngineStorage.encodeSourceStage(result, maximumBytes, maximumDepth);
    }
    /** Restores observations only; decoded evidence never acquires execution or publication authority. */
    public static SourceHistoryStageResult decode(byte[] bytes, int maximumBytes, int maximumDepth) {
        return RootedEngineStorage.decodeSourceStage(bytes, maximumBytes, maximumDepth);
    }
}
