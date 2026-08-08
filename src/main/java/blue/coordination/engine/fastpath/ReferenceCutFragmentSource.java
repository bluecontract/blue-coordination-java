package blue.coordination.engine.fastpath;

import blue.coordination.engine.fastpath.ExactNodeHandle;
import blue.coordination.engine.spi.CoordinationCanonicalFragmentHandleStore;
import blue.coordination.engine.spi.CoordinationFragmentStore;
import blue.language.api.NodeProviderOutcome;
import blue.language.model.Node;
import blue.language.provider.NodeProviderResult;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One-batch canonical planning-fragment source used by direct sparse-Root
 * assembly.
 *
 * <p>The abstraction keeps the compiler storage-neutral while allowing the
 * in-memory host to replace the portable clone-bearing adapter with verified
 * immutable handles. Every requested identity must produce exactly one
 * canonical physical fragment. PROCESS header views belong only to the
 * invocation provider: grafting them into a Root can make implicit metadata
 * explicit and create an invalid mixed payload.</p>
 */
@FunctionalInterface
public interface ReferenceCutFragmentSource {

    Map<String, ExactNodeHandle> loadCanonical(
            String inventoryIdentity,
            Collection<String> orderedBlueIds);

    /** Selects the verified handle path when the store supports it. */
    static ReferenceCutFragmentSource bestAvailable(
            CoordinationFragmentStore store,
            ReferenceCutMetrics metrics) {
        CoordinationFragmentStore checked = Objects.requireNonNull(
                store, "store");
        ReferenceCutMetrics measured = Objects.requireNonNull(
                metrics, "metrics");
        if (checked instanceof CoordinationCanonicalFragmentHandleStore) {
            return handles(
                    (CoordinationCanonicalFragmentHandleStore) checked,
                    measured);
        }
        return portable(checked, measured);
    }

    /** Portable adapter over the public fragment-store SPI. */
    static ReferenceCutFragmentSource portable(
            CoordinationFragmentStore store) {
        return portable(store, new ReferenceCutMetrics());
    }

    /** Portable adapter with direct source-work instrumentation. */
    static ReferenceCutFragmentSource portable(
            CoordinationFragmentStore store,
            ReferenceCutMetrics metrics) {
        CoordinationFragmentStore checked = Objects.requireNonNull(
                store, "store");
        ReferenceCutMetrics measured = Objects.requireNonNull(
                metrics, "metrics");
        Object portableOwner = new Object();
        return (inventoryIdentity, orderedBlueIds) -> {
            Objects.requireNonNull(inventoryIdentity, "inventoryIdentity");
            List<String> requested = Collections.unmodifiableList(
                    new java.util.ArrayList<String>(Objects.requireNonNull(
                            orderedBlueIds, "orderedBlueIds")));
            if (!requested.isEmpty()) {
                measured.canonicalBatchReads(1L);
                measured.portableCanonicalBatches(1L);
            }
            Map<String, NodeProviderResult> outcomes = checked
                    .readRepresentations(inventoryIdentity, requested)
                    .physical();
            LinkedHashMap<String, ExactNodeHandle> result =
                    new LinkedHashMap<String, ExactNodeHandle>();
            for (String blueId : requested) {
                NodeProviderResult outcome = outcomes.get(blueId);
                if (outcome == null
                        || outcome.outcome() != NodeProviderOutcome.FOUND) {
                    throw new IllegalStateException(
                            "Canonical fragment is unavailable for direct "
                                    + "sparse-Root assembly: " + blueId);
                }
                List<Node> nodes = outcome.nodes();
                if (nodes.size() != 1 || nodes.get(0).isReferenceOnly()) {
                    throw new IllegalStateException(
                            "Canonical fragment evidence must contain exactly "
                                    + "one concrete value: " + blueId);
                }
                result.put(
                        blueId,
                        ExactNodeHandle.copyAndVerify(
                                blueId, nodes.get(0), portableOwner));
            }
            return Collections.unmodifiableMap(result);
        };
    }

    /** Adapter over the in-process verified-handle storage extension. */
    static ReferenceCutFragmentSource handles(
            CoordinationCanonicalFragmentHandleStore store,
            ReferenceCutMetrics metrics) {
        CoordinationCanonicalFragmentHandleStore checked =
                Objects.requireNonNull(store, "store");
        ReferenceCutMetrics measured = Objects.requireNonNull(
                metrics, "metrics");
        return (inventoryIdentity, orderedBlueIds) -> {
            List<String> requested = Collections.unmodifiableList(
                    new java.util.ArrayList<String>(Objects.requireNonNull(
                            orderedBlueIds, "orderedBlueIds")));
            CoordinationCanonicalFragmentHandleStore
                    .CanonicalFragmentHandleBatch batch =
                    checked.readCanonicalFragmentHandles(
                            Objects.requireNonNull(
                                    inventoryIdentity,
                                    "inventoryIdentity"),
                            requested);
            measured.canonicalBatchReads(batch.batchReadCount());
            measured.canonicalSingleReads(batch.singleReadCount());
            if (!requested.isEmpty()) {
                measured.verifiedHandleBatches(1L);
            }
            Map<String, ExactNodeHandle> supplied = batch.handles();
            LinkedHashMap<String, ExactNodeHandle> result =
                    new LinkedHashMap<String, ExactNodeHandle>();
            for (String blueId : requested) {
                ExactNodeHandle handle = supplied.get(blueId);
                if (handle == null || !blueId.equals(handle.blueId())) {
                    throw new IllegalStateException(
                            "Verified canonical handle is unavailable: "
                                    + blueId);
                }
                result.put(blueId, handle);
            }
            if (result.size() != supplied.size()) {
                throw new IllegalStateException(
                        "Verified canonical handle batch contains "
                                + "unrequested identities");
            }
            return Collections.unmodifiableMap(result);
        };
    }
}
