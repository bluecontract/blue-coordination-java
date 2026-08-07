package blue.coordination.engine.spi;

import blue.coordination.engine.api.CoordinationProcessingPlan;
import blue.coordination.engine.api.LoadedProcessingBundle;
import blue.coordination.engine.api.ManagedDocumentSnapshot;

import java.util.Collection;

/** Predictable initial-batch loading boundary for one PROCESS invocation. */
public interface CoordinationProcessingBundleLoader {
    LoadedProcessingBundle load(
            ManagedDocumentSnapshot session,
            CoordinationProcessingPlan plan,
            Collection<String> preferredBlueIds);
}
