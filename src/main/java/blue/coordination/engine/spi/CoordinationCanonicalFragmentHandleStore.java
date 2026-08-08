package blue.coordination.engine.spi;

import blue.coordination.engine.fastpath.ExactNodeHandle;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Optional Coordination-owned storage path for verified canonical planning
 * fragment handles.
 *
 * <p>The returned handles remain owned by the store. Their public copy
 * operation is defensive, while zero-copy borrowing still requires the
 * store's private owner capability and an engine-only access authority. This
 * lets an in-process engine avoid public {@code NodeProviderResult}
 * construction, cloning and repeated identity hashing without exposing a
 * mutable stored {@code Node}.</p>
 */
public interface CoordinationCanonicalFragmentHandleStore {

    /** Exact storage generation/authority that owns the returned handles. */
    String canonicalFragmentStorageGenerationAuthority();

    /**
     * Reads verified canonical physical-fragment handles for one exact
     * inventory. Implementations must never substitute identity-equivalent
     * PROCESS header views: those views can be body-free or encode implicit
     * metadata and are safe only behind the PROCESS request provider, not for
     * direct Root graft assembly. Implementations must account the actual
     * backend batch and single reads in the returned evidence.
     */
    CanonicalFragmentHandleBatch readCanonicalFragmentHandles(
            String inventoryIdentity,
            Collection<String> orderedBlueIds);

    /** Immutable result and direct storage-work evidence for one request. */
    final class CanonicalFragmentHandleBatch {
        private final Map<String, ExactNodeHandle> handles;
        private final int batchReadCount;
        private final int singleReadCount;

        public CanonicalFragmentHandleBatch(
                Map<String, ExactNodeHandle> handles,
                int batchReadCount,
                int singleReadCount) {
            if (batchReadCount < 0 || singleReadCount < 0) {
                throw new IllegalArgumentException(
                        "read counts must be non-negative");
            }
            Map<String, ExactNodeHandle> copied =
                    new LinkedHashMap<String, ExactNodeHandle>();
            for (Map.Entry<String, ExactNodeHandle> entry
                    : Objects.requireNonNull(handles, "handles").entrySet()) {
                String blueId = requireText(entry.getKey(), "blueId");
                ExactNodeHandle handle = Objects.requireNonNull(
                        entry.getValue(), "handle");
                if (!blueId.equals(handle.blueId())) {
                    throw new IllegalArgumentException(
                            "Handle identity does not match map key "
                                    + blueId);
                }
                copied.put(blueId, handle);
            }
            this.handles = Collections.unmodifiableMap(copied);
            this.batchReadCount = batchReadCount;
            this.singleReadCount = singleReadCount;
        }

        public Map<String, ExactNodeHandle> handles() { return handles; }
        public int batchReadCount() { return batchReadCount; }
        public int singleReadCount() { return singleReadCount; }

        private static String requireText(String value, String label) {
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException(
                        label + " must not be empty");
            }
            return value;
        }
    }
}
