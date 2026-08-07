package blue.coordination.engine.memory;

import blue.language.model.wire.JsonPointer;
import blue.language.processor.ExternalOrderKey;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Immutable canonical observation of one in-memory subscription-index state.
 *
 * <p>The content digest deliberately excludes {@link #generation()}. A fresh
 * index rebuilt from authoritative sessions can therefore prove that its
 * physical route rows are equivalent even when its publication history is
 * different.</p>
 */
public final class InMemoryCoordinationSubscriptionIndexSnapshot {

    private static final String FORMAT_IDENTITY =
            "blue.coordination/subscription-index-snapshot/1.0";

    private final long generation;
    private final List<Row> rows;
    private final String digest;

    InMemoryCoordinationSubscriptionIndexSnapshot(
            long generation,
            List<Row> rows) {
        if (generation < 0L) {
            throw new IllegalArgumentException(
                    "generation must be non-negative");
        }
        this.generation = generation;
        List<Row> checked = new ArrayList<Row>(Objects.requireNonNull(
                rows, "rows"));
        Row previous = null;
        for (Row row : checked) {
            Row current = Objects.requireNonNull(row, "index row");
            if (previous != null
                    && Row.CANONICAL_ORDER.compare(previous, current) >= 0) {
                throw new IllegalArgumentException(
                        "index rows must be unique and canonically ordered");
            }
            previous = current;
        }
        this.rows = Collections.unmodifiableList(checked);
        this.digest = digest(checked);
    }

    /** @return publication generation observed with these rows */
    public long generation() {
        return generation;
    }

    /** @return immutable physical rows in canonical code-point order */
    public List<Row> rows() {
        return rows;
    }

    /** @return content-only SHA-256 identity of the canonical rows */
    public String digest() {
        return digest;
    }

    private static String digest(List<Row> rows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            addText(digest, FORMAT_IDENTITY);
            addInt(digest, rows.size());
            for (Row row : rows) {
                addText(digest, row.subscriptionKey);
                addText(digest, row.sessionId);
                addText(digest, row.occurrenceKey);
                addText(digest, row.scopePath);
                addInt(digest, row.order);
                addText(digest, row.channelKey);
                addText(digest, row.effectiveTypeBlueId);
                addOrderKey(digest, row.activationFrontier);
                addLong(digest, row.plannedEpoch);
                addText(digest, row.plannedRootBlueId);
                addText(digest, row.subscriptionSnapshotIdentity);
            }
            return "sha256:" + hexadecimal(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static void addOrderKey(
            MessageDigest digest,
            ExternalOrderKey orderKey) {
        if (orderKey == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        List<Object> components = orderKey.components();
        addInt(digest, components.size());
        for (Object component : components) {
            if (component instanceof BigInteger) {
                digest.update((byte) 0);
                addText(digest, component.toString());
            } else if (component instanceof String) {
                digest.update((byte) 1);
                addText(digest, (String) component);
            } else {
                throw new IllegalStateException(
                        "Unsupported external-order component: "
                                + component.getClass().getName());
            }
        }
    }

    private static void addText(MessageDigest digest, String value) {
        byte[] bytes = Objects.requireNonNull(value, "canonical text")
                .getBytes(StandardCharsets.UTF_8);
        addInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void addInt(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(value).array());
    }

    private static void addLong(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES)
                .putLong(value).array());
    }

    private static String hexadecimal(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(value & 0x0f, 16));
        }
        return result.toString();
    }

    /** Immutable physical registration retained by the in-memory index. */
    public static final class Row {

        private static final Comparator<Row> CANONICAL_ORDER =
                new Comparator<Row>() {
                    @Override
                    public int compare(Row left, Row right) {
                        int compared = compareText(
                                left.subscriptionKey,
                                right.subscriptionKey);
                        if (compared != 0) {
                            return compared;
                        }
                        compared = compareText(
                                left.sessionId, right.sessionId);
                        if (compared != 0) {
                            return compared;
                        }
                        compared = Integer.compare(
                                depth(right.scopePath),
                                depth(left.scopePath));
                        if (compared != 0) {
                            return compared;
                        }
                        compared = compareText(
                                left.scopePath, right.scopePath);
                        if (compared != 0) {
                            return compared;
                        }
                        compared = Integer.compare(
                                left.order, right.order);
                        if (compared != 0) {
                            return compared;
                        }
                        compared = compareText(
                                left.channelKey, right.channelKey);
                        if (compared != 0) {
                            return compared;
                        }
                        compared = compareText(
                                left.effectiveTypeBlueId,
                                right.effectiveTypeBlueId);
                        return compared != 0
                                ? compared
                                : compareText(
                                        left.occurrenceKey,
                                        right.occurrenceKey);
                    }
                };

        private final String subscriptionKey;
        private final String sessionId;
        private final String occurrenceKey;
        private final String scopePath;
        private final int order;
        private final String channelKey;
        private final String effectiveTypeBlueId;
        private final ExternalOrderKey activationFrontier;
        private final long plannedEpoch;
        private final String plannedRootBlueId;
        private final String subscriptionSnapshotIdentity;

        Row(
                String subscriptionKey,
                String sessionId,
                String occurrenceKey,
                String scopePath,
                int order,
                String channelKey,
                String effectiveTypeBlueId,
                ExternalOrderKey activationFrontier,
                long plannedEpoch,
                String plannedRootBlueId,
                String subscriptionSnapshotIdentity) {
            this.subscriptionKey = requireText(
                    subscriptionKey, "subscriptionKey");
            this.sessionId = requireText(sessionId, "sessionId");
            this.occurrenceKey = requireText(
                    occurrenceKey, "occurrenceKey");
            this.scopePath = requireText(scopePath, "scopePath");
            this.order = order;
            this.channelKey = requireText(channelKey, "channelKey");
            this.effectiveTypeBlueId = requireText(
                    effectiveTypeBlueId, "effectiveTypeBlueId");
            this.activationFrontier = activationFrontier;
            if (plannedEpoch < 0L) {
                throw new IllegalArgumentException(
                        "plannedEpoch must be non-negative");
            }
            this.plannedEpoch = plannedEpoch;
            this.plannedRootBlueId = requireText(
                    plannedRootBlueId, "plannedRootBlueId");
            this.subscriptionSnapshotIdentity = requireText(
                    subscriptionSnapshotIdentity,
                    "subscriptionSnapshotIdentity");
        }

        public String subscriptionKey() {
            return subscriptionKey;
        }

        public String sessionId() {
            return sessionId;
        }

        public String occurrenceKey() {
            return occurrenceKey;
        }

        public String scopePath() {
            return scopePath;
        }

        public int order() {
            return order;
        }

        public String channelKey() {
            return channelKey;
        }

        public String effectiveTypeBlueId() {
            return effectiveTypeBlueId;
        }

        public ExternalOrderKey activationFrontier() {
            return activationFrontier;
        }

        public long plannedEpoch() {
            return plannedEpoch;
        }

        public String plannedRootBlueId() {
            return plannedRootBlueId;
        }

        public String subscriptionSnapshotIdentity() {
            return subscriptionSnapshotIdentity;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Row)) {
                return false;
            }
            Row row = (Row) other;
            return order == row.order
                    && plannedEpoch == row.plannedEpoch
                    && subscriptionKey.equals(row.subscriptionKey)
                    && sessionId.equals(row.sessionId)
                    && occurrenceKey.equals(row.occurrenceKey)
                    && scopePath.equals(row.scopePath)
                    && channelKey.equals(row.channelKey)
                    && effectiveTypeBlueId.equals(row.effectiveTypeBlueId)
                    && Objects.equals(
                            activationFrontier, row.activationFrontier)
                    && plannedRootBlueId.equals(row.plannedRootBlueId)
                    && subscriptionSnapshotIdentity.equals(
                            row.subscriptionSnapshotIdentity);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    subscriptionKey,
                    sessionId,
                    occurrenceKey,
                    scopePath,
                    order,
                    channelKey,
                    effectiveTypeBlueId,
                    activationFrontier,
                    plannedEpoch,
                    plannedRootBlueId,
                    subscriptionSnapshotIdentity);
        }

        private static String requireText(String value, String label) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        label + " must be non-blank");
            }
            return value;
        }

        private static int depth(String path) {
            return JsonPointer.split(path).size();
        }

        private static int compareText(String left, String right) {
            return ExternalOrderKey.compareTextCodePoints(left, right);
        }
    }
}
