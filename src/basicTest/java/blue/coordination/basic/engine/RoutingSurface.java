package blue.coordination.basic.engine;

import blue.language.processor.EffectiveContractSnapshot;
import blue.language.processor.EffectiveContractSnapshotConstants;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.snapshot.FrozenNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable operation index compiled once from the authoritative effective
 * contract catalog. All scopes owned by this autonomous Root are included;
 * scopes at or below Process Embedded boundaries are excluded because their
 * sessions compile their own routing surfaces.
 */
public final class RoutingSurface {
    public record Definition(
            String operation,
            String channelKey,
            String timelineId,
            String actorId) {
        public Definition {
            operation = requireText(operation, "operation");
            channelKey = requireText(channelKey, "channelKey");
            timelineId = requireText(timelineId, "timelineId");
            actorId = requireText(actorId, "actorId");
        }
    }

    private final List<Definition> definitions;
    private final String fingerprint;
    private final boolean embeddedRevisionHandler;

    private RoutingSurface(
            Collection<Definition> definitions,
            boolean embeddedRevisionHandler) {
        List<Definition> ordered = new ArrayList<>(Objects.requireNonNull(
                definitions, "definitions"));
        ordered.sort(Comparator
                .comparing(Definition::operation)
                .thenComparing(Definition::channelKey)
                .thenComparing(Definition::timelineId)
                .thenComparing(Definition::actorId));
        this.definitions = Collections.unmodifiableList(ordered);
        this.embeddedRevisionHandler = embeddedRevisionHandler;
        this.fingerprint = calculateFingerprint(ordered);
    }

    public static RoutingSurface from(
            EffectiveFragmentationCatalog catalog,
            Collection<String> autonomousBoundaries) {
        Objects.requireNonNull(catalog, "catalog");
        Map<Definition, Definition> unique = new LinkedHashMap<>();
        catalog.effectiveContractsByScope().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(entry -> owned(
                        entry.getKey(), autonomousBoundaries))
                .forEach(entry -> collect(entry.getValue(), unique));
        boolean embeddedHandler = catalog.effectiveContractsByScope().entrySet()
                .stream()
                .filter(entry -> owned(entry.getKey(), autonomousBoundaries))
                .flatMap(entry -> entry.getValue().stream())
                .anyMatch(contract ->
                        EffectiveContractSnapshotConstants.Role.HANDLER.equals(
                                contract.role())
                                && InternalRevisionEventFactory.INTERNAL_OPERATION
                                .equals(contract.key()));
        return new RoutingSurface(unique.values(), embeddedHandler);
    }

    public List<Definition> definitions() {
        return definitions;
    }

    public List<String> externalTimelineIds() {
        return definitions.stream()
                .map(Definition::timelineId)
                .distinct()
                .sorted()
                .toList();
    }

    public String fingerprint() {
        return fingerprint;
    }

    public boolean deliversEmbeddedRevisionEvents() {
        return embeddedRevisionHandler;
    }

    private static void collect(
            List<EffectiveContractSnapshot> contracts,
            Map<Definition, Definition> unique) {
        Map<String, ChannelAddress> channels = new LinkedHashMap<>();
        for (EffectiveContractSnapshot contract : contracts) {
            if (!EffectiveContractSnapshotConstants.Role.EXTERNAL_CHANNEL
                    .equals(contract.role())) {
                continue;
            }
            FrozenNode timeline = contract.headerFields().get("timeline");
            FrozenNode actor = contract.headerFields().get("actor");
            channels.put(contract.key(), new ChannelAddress(
                    scalarProperty(timeline, "timelineId", contract.key()),
                    scalarProperty(actor, "accountId", contract.key())));
        }
        for (EffectiveContractSnapshot contract : contracts) {
            if (!EffectiveContractSnapshotConstants.Role.HANDLER
                    .equals(contract.role())) {
                continue;
            }
            String channelKey = contract.dispatchFields().get(
                    EffectiveContractSnapshotConstants.DispatchField.CHANNEL);
            if (channelKey == null || channelKey.isBlank()) {
                continue;
            }
            ChannelAddress channel = channels.get(channelKey);
            if (channel == null
                    || contract.key().equals(
                            InternalRevisionEventFactory.INTERNAL_OPERATION)
                    || channel.timelineId().startsWith("coordination/internal/")
                    || channel.actorId().equals("coordination")) {
                continue;
            }
            Definition definition = new Definition(
                    contract.key(),
                    channelKey,
                    channel.timelineId(),
                    channel.actorId());
            unique.putIfAbsent(definition, definition);
        }
    }

    private static boolean owned(
            String scopePath,
            Collection<String> autonomousBoundaries) {
        for (String boundary : Objects.requireNonNull(
                autonomousBoundaries, "autonomousBoundaries")) {
            if (scopePath.equals(boundary)
                    || scopePath.startsWith(boundary + "/")) {
                return false;
            }
        }
        return true;
    }

    private static String scalarProperty(
            FrozenNode owner,
            String property,
            String channelKey) {
        if (owner == null) {
            throw new IllegalStateException(
                    "External channel " + channelKey + " has no " + property
                            + " owner");
        }
        FrozenNode selected = owner.property(property);
        Object value = selected == null ? null : selected.getValue();
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException(
                    "External channel " + channelKey + " has no Text "
                            + property);
        }
        return text;
    }

    private static String calculateFingerprint(List<Definition> definitions) {
        MessageDigest digest = sha256();
        for (Definition definition : definitions) {
            update(digest, definition.operation());
            update(digest, definition.channelKey());
            update(digest, definition.timelineId());
            update(digest, definition.actorId());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM has no SHA-256", impossible);
        }
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    private record ChannelAddress(String timelineId, String actorId) {
    }
}
