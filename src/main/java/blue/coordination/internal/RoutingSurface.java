package blue.coordination.internal;

import blue.language.processor.EffectiveContractSnapshot;
import blue.language.processor.EffectiveContractSnapshotConstants;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.snapshot.FrozenNode;
import blue.repo.coordination.AllTimelinesChannel;
import blue.repo.coordination.CompositeTimelineChannel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable operation index compiled once from the authoritative effective
 * contract catalog. All scopes owned by this managed document are included;
 * scopes at or below Process Embedded boundaries are excluded because their
 * sessions compile their own routing surfaces.
 */
final class RoutingSurface {
    public record SourceAddress(String timelineId, String actorId) {
        public SourceAddress {
            timelineId = requireText(timelineId, "timelineId");
            actorId = requireText(actorId, "actorId");
        }
    }

    public record Definition(
            String scopePath,
            String operation,
            String channelKey,
            List<SourceAddress> sources) {
        public Definition {
            scopePath = requireText(scopePath, "scopePath");
            operation = requireText(operation, "operation");
            channelKey = requireText(channelKey, "channelKey");
            Set<SourceAddress> unique = new LinkedHashSet<>(
                    Objects.requireNonNull(sources, "sources"));
            if (unique.contains(null)) {
                throw new NullPointerException("source address");
            }
            sources = unique.stream()
                    .sorted(Comparator
                            .comparing(SourceAddress::timelineId)
                            .thenComparing(SourceAddress::actorId))
                    .toList();
        }

        public Definition(
                String scopePath,
                String operation,
                String channelKey,
                String timelineId,
                String actorId) {
            this(scopePath, operation, channelKey,
                    List.of(new SourceAddress(timelineId, actorId)));
        }
    }

    private final List<Definition> definitions;
    private final boolean embeddedRevisionHandler;

    RoutingSurface(
            Collection<Definition> definitions,
            boolean embeddedRevisionHandler) {
        List<Definition> ordered = new ArrayList<>(Objects.requireNonNull(
                definitions, "definitions"));
        ordered.sort(Comparator
                .comparing(Definition::scopePath)
                .thenComparing(Definition::operation)
                .thenComparing(Definition::channelKey)
                .thenComparing(definition -> definition.sources().toString()));
        this.definitions = Collections.unmodifiableList(ordered);
        this.embeddedRevisionHandler = embeddedRevisionHandler;
    }

    public static RoutingSurface from(
            EffectiveFragmentationCatalog catalog,
            Collection<String> managedBoundaries) {
        Objects.requireNonNull(catalog, "catalog");
        Map<Definition, Definition> unique = new LinkedHashMap<>();
        catalog.effectiveContractsByScope().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(entry -> owned(
                        entry.getKey(), managedBoundaries))
                .forEach(entry -> collect(
                        entry.getKey(), entry.getValue(), unique));
        boolean embeddedHandler = catalog.effectiveContractsByScope().entrySet()
                .stream()
                .filter(entry -> owned(entry.getKey(), managedBoundaries))
                .flatMap(entry -> entry.getValue().stream())
                .anyMatch(contract ->
                        EffectiveContractSnapshotConstants.Role.HANDLER.equals(
                                contract.role())
                                && EmbeddedEpochInput.INTERNAL_OPERATION
                                .equals(contract.key()));
        return new RoutingSurface(unique.values(), embeddedHandler);
    }

    public List<Definition> definitions() {
        return definitions;
    }

    public List<String> externalTimelineIds() {
        return definitions.stream()
                .flatMap(definition -> definition.sources().stream())
                .map(SourceAddress::timelineId)
                .distinct()
                .sorted()
                .toList();
    }

    public boolean deliversEmbeddedRevisionEvents() {
        return embeddedRevisionHandler;
    }

    private static void collect(
            String scopePath,
            List<EffectiveContractSnapshot> contracts,
            Map<Definition, Definition> unique) {
        Map<String, EffectiveContractSnapshot> channels =
                new LinkedHashMap<>();
        for (EffectiveContractSnapshot contract : contracts) {
            if (!EffectiveContractSnapshotConstants.Role.EXTERNAL_CHANNEL
                    .equals(contract.role())) {
                continue;
            }
            channels.put(contract.key(), contract);
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
            EffectiveContractSnapshot channel = channels.get(channelKey);
            if (channel == null || contract.key().equals(
                    EmbeddedEpochInput.INTERNAL_OPERATION)) {
                continue;
            }
            List<SourceAddress> sources = sourcesFor(channel, channels)
                    .stream()
                    .filter(source -> !source.timelineId().startsWith(
                            "coordination/internal/"))
                    .filter(source -> !source.actorId().equals(
                            "coordination"))
                    .toList();
            Definition definition = new Definition(
                    scopePath,
                    contract.key(),
                    channelKey,
                    sources);
            unique.putIfAbsent(definition, definition);
        }
    }

    private static List<SourceAddress> sourcesFor(
            EffectiveContractSnapshot channel,
            Map<String, EffectiveContractSnapshot> channels) {
        FrozenNode timeline = channel.headerFields().get("timeline");
        FrozenNode actor = channel.headerFields().get("actor");
        if (timeline != null || actor != null) {
            return List.of(new SourceAddress(
                    scalarProperty(timeline, "timelineId", channel.key()),
                    scalarProperty(actor, "accountId", channel.key())));
        }
        if (CompositeTimelineChannel.blueId().equals(
                channel.effectiveTypeBlueId())) {
            List<SourceAddress> result = new ArrayList<>();
            for (String memberKey : textItems(
                    channel.headerFields().get("channels"), channel.key())) {
                EffectiveContractSnapshot member = channels.get(memberKey);
                if (member == null) {
                    throw new IllegalStateException(
                            "Composite Timeline Channel " + channel.key()
                                    + " has no external member " + memberKey);
                }
                result.addAll(requireDirectTimelineSources(
                        member, channel.key()));
            }
            return result;
        }
        if (AllTimelinesChannel.blueId().equals(
                channel.effectiveTypeBlueId())) {
            List<SourceAddress> result = new ArrayList<>();
            channels.values().stream()
                    .filter(candidate -> candidate != channel)
                    .forEach(candidate -> result.addAll(
                            directTimelineSources(candidate)));
            return result;
        }
        throw new IllegalStateException(
                "Unsupported external Channel routing type "
                        + channel.effectiveTypeBlueId() + " at "
                        + channel.scopePath() + "/" + channel.key());
    }

    private static List<SourceAddress> requireDirectTimelineSources(
            EffectiveContractSnapshot member,
            String compositeKey) {
        List<SourceAddress> result = directTimelineSources(member);
        if (result.isEmpty()) {
            throw new IllegalStateException(
                    "Composite Timeline Channel " + compositeKey
                            + " member " + member.key()
                            + " has no direct Timeline/Actor binding");
        }
        return result;
    }

    private static List<SourceAddress> directTimelineSources(
            EffectiveContractSnapshot channel) {
        FrozenNode timeline = channel.headerFields().get("timeline");
        FrozenNode actor = channel.headerFields().get("actor");
        if (timeline == null && actor == null) {
            return List.of();
        }
        return List.of(new SourceAddress(
                scalarProperty(timeline, "timelineId", channel.key()),
                scalarProperty(actor, "accountId", channel.key())));
    }

    private static List<String> textItems(
            FrozenNode value,
            String channelKey) {
        if (value == null || value.getItems() == null
                || value.getItems().isEmpty()) {
            throw new IllegalStateException(
                    "Composite Timeline Channel " + channelKey
                            + " requires member keys");
        }
        List<String> result = new ArrayList<>();
        for (FrozenNode item : value.getItems()) {
            Object scalar = item.getValue();
            if (!(scalar instanceof String text) || text.isBlank()) {
                throw new IllegalStateException(
                        "Composite Timeline Channel " + channelKey
                                + " has a non-Text member key");
            }
            result.add(text);
        }
        return result;
    }

    private static boolean owned(
            String scopePath,
            Collection<String> managedBoundaries) {
        for (String boundary : Objects.requireNonNull(
                managedBoundaries, "managedBoundaries")) {
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

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
