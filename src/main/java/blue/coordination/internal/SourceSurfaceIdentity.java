package blue.coordination.internal;

import blue.language.processor.ExternalChannelDependencySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta.Entry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Stable identity of the exact external source surface used by catch-up. */
final class SourceSurfaceIdentity {
    private static final String DOMAIN = "blue.coordination/source-surface/1";
    private static final Comparator<List<String>> TEXT_LIST_ORDER =
            SourceSurfaceIdentity::compareTexts;
    private static final Comparator<ExternalChannelDependencySnapshot>
            DEPENDENCY_ORDER = Comparator
                    .comparing(ExternalChannelDependencySnapshot
                            ::intrinsicNodeBlueIds, TEXT_LIST_ORDER)
                    .thenComparing(ExternalChannelDependencySnapshot
                            ::deterministicDependencyNodeBlueIds,
                            TEXT_LIST_ORDER)
                    .thenComparingInt(value -> value
                            .wholeSameScopeExternalSurface() ? 1 : 0)
                    .thenComparingInt(value -> value
                            .wholeSameScopeChannelCatalog() ? 1 : 0)
                    .thenComparing(ExternalChannelDependencySnapshot
                            ::channelCatalogContractKeys, TEXT_LIST_ORDER);
    static final Comparator<Entry> ENTRY_ORDER =
            Comparator.comparing(Entry::scopePath, EmbeddingBinding.TEXT_ORDER)
                    .thenComparingInt(Entry::order)
                    .thenComparing(Entry::channelKey, EmbeddingBinding.TEXT_ORDER)
                    .thenComparing(Entry::effectiveTypeBlueId, EmbeddingBinding.TEXT_ORDER)
                    .thenComparing(Entry::sourceContributionNodeBlueIds,
                            TEXT_LIST_ORDER)
                    .thenComparing(Entry::subscriptionKeys,
                            TEXT_LIST_ORDER)
                    .thenComparing(Entry::checkpointDomainBlueId, EmbeddingBinding.TEXT_ORDER)
                    .thenComparing(Entry::dependencies, DEPENDENCY_ORDER)
                    .thenComparing(Entry::activationRootRevision,
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(Entry::startAfterExternalOrderKey,
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(Entry::endAtRootRevision,
                            Comparator.nullsFirst(Comparator.naturalOrder()));

    private SourceSurfaceIdentity() { }

    static String calculate(
            EmbeddingBinding binding,
            List<Entry> activeSubscriptions,
            RoutingSurface routingSurface) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(activeSubscriptions, "activeSubscriptions");
        Objects.requireNonNull(routingSurface, "routingSurface");
        MessageDigest digest = sha256();
        text(digest, DOMAIN);
        text(digest, binding.parentDocumentId().value());
        text(digest, binding.absolutePath());
        number(digest, binding.activationGeneration());
        text(digest, binding.childDocumentId().value());
        List<Entry> subscriptions = new ArrayList<>(activeSubscriptions);
        subscriptions.sort(ENTRY_ORDER);
        number(digest, subscriptions.size());
        for (Entry entry : subscriptions) {
            subscription(digest, entry);
        }
        List<RoutingSurface.Definition> definitions = routingSurface.definitions();
        number(digest, definitions.size());
        for (RoutingSurface.Definition definition : definitions) {
            text(digest, definition.scopePath());
            text(digest, definition.operation());
            text(digest, definition.channelKey());
            number(digest, definition.sources().size());
            for (RoutingSurface.SourceAddress source : definition.sources()) {
                text(digest, source.timelineId());
                text(digest, source.actorId());
            }
        }
        bool(digest, routingSurface.deliversEmbeddedRevisionEvents());
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private static int compareTexts(List<String> left, List<String> right) {
        int shared = Math.min(left.size(), right.size());
        for (int index = 0; index < shared; index++) {
            int compared = EmbeddingBinding.TEXT_ORDER.compare(
                    left.get(index), right.get(index));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private static void subscription(MessageDigest digest, Entry entry) {
        text(digest, entry.scopePath());
        text(digest, entry.channelKey());
        text(digest, entry.effectiveTypeBlueId());
        texts(digest, entry.sourceContributionNodeBlueIds());
        number(digest, entry.order());
        texts(digest, entry.subscriptionKeys());
        text(digest, entry.checkpointDomainBlueId());
        dependencies(digest, entry.dependencies());
        nullableNumber(digest, entry.activationRootRevision());
        orderKey(digest, entry.startAfterExternalOrderKey());
        nullableNumber(digest, entry.endAtRootRevision());
    }

    private static void dependencies(MessageDigest digest,
            ExternalChannelDependencySnapshot dependencies) {
        texts(digest, dependencies.intrinsicNodeBlueIds());
        texts(digest, dependencies.deterministicDependencyNodeBlueIds());
        bool(digest, dependencies.wholeSameScopeExternalSurface());
        bool(digest, dependencies.wholeSameScopeChannelCatalog());
        texts(digest, dependencies.channelCatalogContractKeys());
    }

    private static void orderKey(MessageDigest digest,
            ExternalOrderKey orderKey) {
        if (orderKey == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        List<Object> components = orderKey.components();
        number(digest, components.size());
        for (Object component : components) {
            if (component instanceof java.math.BigInteger integer) {
                digest.update((byte) 1);
                text(digest, integer.toString());
            } else if (component instanceof String value) {
                digest.update((byte) 2);
                text(digest, value);
            } else {
                throw new IllegalArgumentException(
                        "Unsupported external-order component " + component);
            }
        }
    }

    private static void texts(MessageDigest digest, List<String> values) {
        number(digest, values.size());
        for (String value : values) {
            text(digest, value);
        }
    }

    private static void nullableNumber(MessageDigest digest, Long value) {
        digest.update(value == null ? (byte) 0 : (byte) 1);
        if (value != null) {
            number(digest, value);
        }
    }

    private static void bool(MessageDigest digest, boolean value) {
        digest.update(value ? (byte) 1 : (byte) 0);
    }

    private static void number(MessageDigest digest, long value) {
        for (int shift = Long.SIZE - Byte.SIZE; shift >= 0;
                shift -= Byte.SIZE) {
            digest.update((byte) (value >>> shift));
        }
    }

    private static void text(MessageDigest digest, String value) {
        byte[] encoded = Objects.requireNonNull(value, "value")
                .getBytes(StandardCharsets.UTF_8);
        int length = encoded.length;
        for (int shift = Integer.SIZE - Byte.SIZE; shift >= 0;
                shift -= Byte.SIZE) {
            digest.update((byte) (length >>> shift));
        }
        digest.update(encoded);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
