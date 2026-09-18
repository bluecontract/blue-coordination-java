package blue.coordination.api.storage;

import java.util.Optional;

/**
 * Host-owned immutable physical bytes; addresses are SHA-256, never BlueIds.
 *
 * <p>The library owns encoding and verifies every returned byte array. The host
 * must copy caller bytes, atomically retain the first exact value at an address,
 * and reject a different value at that address. A missing object is distinct from
 * an unavailable store. The read byte limit must be enforced by the host before
 * allocating the result: digest verification alone is not a memory bound.
 * This port does not publish roots, own transactions, or select semantic work.</p>
 */
public interface CoordinationImmutableObjectStore {
    /** Lowercase 64-hex SHA-256 addresses. Returns the exact retained bytes. */
    byte[] putIfAbsent(String physicalSha256, byte[] bytes);

    /** Returns detached bytes, enforcing {@code maximumBytes} before allocation. */
    Optional<byte[]> get(String physicalSha256, int maximumBytes);
}
