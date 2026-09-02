package blue.coordination.internal;

import blue.language.processor.EffectiveContractSnapshot;
import blue.language.processor.EffectiveContractSnapshotConstants;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.mapping.TypeClassResolver;
import blue.language.snapshot.FrozenNode;
import blue.repo.coordination.AllTimelinesChannel;
import blue.repo.coordination.CompositeTimelineChannel;
import blue.repo.coordination.SequentialWorkflowOperation;

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
    private static final Map<String, Class<?>> REPOSITORY_TYPES =
            Collections.unmodifiableMap(new LinkedHashMap<>(
                    new TypeClassResolver("blue.repo").getBlueIdMap()));
    private static final Comparator<SourceAddress> SOURCE_ORDER = Comparator
            .comparing(SourceAddress::timelineId, EmbeddingBinding.TEXT_ORDER)
            .thenComparing(SourceAddress::actorId, EmbeddingBinding.TEXT_ORDER);

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
                    .sorted(SOURCE_ORDER)
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

    /** Read-only metadata for one externally invocable operation handler. */
    record OperationDefinition(
            String scopePath,
            String operation,
            String channelKey,
            FrozenNode requestPattern,
            List<SourceAddress> sources) {
        OperationDefinition {
            scopePath = requireText(scopePath, "scopePath");
            operation = requireText(operation, "operation");
            channelKey = requireText(channelKey, "channelKey");
            Set<SourceAddress> unique = new LinkedHashSet<>(
                    Objects.requireNonNull(sources, "sources"));
            if (unique.contains(null)) {
                throw new NullPointerException("source address");
            }
            sources = unique.stream()
                    .sorted(SOURCE_ORDER)
                    .toList();
        }
    }

    private final List<Definition> definitions;
    private final List<OperationDefinition> operationDefinitions;
    private final boolean embeddedRevisionHandler;

    RoutingSurface(
            Collection<Definition> definitions,
            boolean embeddedRevisionHandler) {
        this(definitions, List.of(), embeddedRevisionHandler);
    }

    private RoutingSurface(
            Collection<Definition> definitions,
            Collection<OperationDefinition> operationDefinitions,
            boolean embeddedRevisionHandler) {
        List<Definition> ordered = new ArrayList<>(Objects.requireNonNull(
                definitions, "definitions"));
        ordered.sort(Comparator
                .comparing(Definition::scopePath, EmbeddingBinding.TEXT_ORDER)
                .thenComparing(Definition::operation, EmbeddingBinding.TEXT_ORDER)
                .thenComparing(Definition::channelKey, EmbeddingBinding.TEXT_ORDER)
                .thenComparing(Definition::sources,
                        RoutingSurface::compareSources));
        this.definitions = Collections.unmodifiableList(ordered);
        List<OperationDefinition> orderedOperations = new ArrayList<>(
                Objects.requireNonNull(
                        operationDefinitions, "operationDefinitions"));
        orderedOperations.sort(Comparator
                .comparing(OperationDefinition::scopePath,
                        EmbeddingBinding.TEXT_ORDER)
                .thenComparing(OperationDefinition::operation,
                        EmbeddingBinding.TEXT_ORDER)
                .thenComparing(OperationDefinition::channelKey,
                        EmbeddingBinding.TEXT_ORDER)
                .thenComparing(OperationDefinition::sources,
                        RoutingSurface::compareSources));
        this.operationDefinitions = Collections.unmodifiableList(
                orderedOperations);
        this.embeddedRevisionHandler = embeddedRevisionHandler;
    }

    static int compareSources(
            List<SourceAddress> left,
            List<SourceAddress> right) {
        int shared = Math.min(left.size(), right.size());
        for (int index = 0; index < shared; index++) {
            int comparison = SOURCE_ORDER.compare(
                    left.get(index), right.get(index));
            if (comparison != 0) return comparison;
        }
        return Integer.compare(left.size(), right.size());
    }

    public static RoutingSurface from(
            EffectiveFragmentationCatalog catalog,
            Collection<String> managedBoundaries) {
        Objects.requireNonNull(catalog, "catalog");
        Map<Definition, Definition> unique = new LinkedHashMap<>();
        Map<Definition, OperationDefinition> operations =
                new LinkedHashMap<>();
        catalog.effectiveContractsByScope().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(EmbeddingBinding.TEXT_ORDER))
                .filter(entry -> owned(
                        entry.getKey(), managedBoundaries))
                .forEach(entry -> collect(
                        entry.getKey(), entry.getValue(), unique,
                        operations));
        boolean embeddedHandler = catalog.effectiveContractsByScope().entrySet()
                .stream()
                .filter(entry -> owned(entry.getKey(), managedBoundaries))
                .flatMap(entry -> entry.getValue().stream())
                .anyMatch(contract ->
                        EffectiveContractSnapshotConstants.Role.HANDLER.equals(
                                contract.role())
                                && EmbeddedEpochInput.INTERNAL_OPERATION
                                .equals(contract.key()));
        return new RoutingSurface(
                unique.values(), operations.values(), embeddedHandler);
    }

    /** Compiles the non-recursive routing surface of one independently
     * managed Root from its processor-authenticated effective contracts. */
    static RoutingSurface fromManagedRootContracts(
            Collection<EffectiveContractSnapshot> effectiveContracts) {
        List<EffectiveContractSnapshot> contracts = new ArrayList<>(
                Objects.requireNonNull(
                        effectiveContracts, "effectiveContracts"));
        contracts.sort(Comparator
                .comparingInt(EffectiveContractSnapshot::order)
                .thenComparing(EffectiveContractSnapshot::key,
                        EmbeddingBinding.TEXT_ORDER));
        for (EffectiveContractSnapshot contract : contracts) {
            if (!"/".equals(Objects.requireNonNull(
                    contract, "effective contract").scopePath())) {
                throw new IllegalArgumentException(
                        "Managed Root routing contract has non-Root scope "
                                + contract.scopePath());
            }
        }
        Map<Definition, Definition> unique = new LinkedHashMap<>();
        Map<Definition, OperationDefinition> operations =
                new LinkedHashMap<>();
        collect("/", contracts, unique, operations);
        boolean embeddedHandler = contracts.stream().anyMatch(contract ->
                EffectiveContractSnapshotConstants.Role.HANDLER.equals(
                        contract.role())
                        && EmbeddedEpochInput.INTERNAL_OPERATION.equals(
                        contract.key()));
        return new RoutingSurface(
                unique.values(), operations.values(), embeddedHandler);
    }

    public List<Definition> definitions() {
        return definitions;
    }

    List<OperationDefinition> operationDefinitions() {
        return operationDefinitions;
    }

    public List<String> externalTimelineIds() {
        return definitions.stream()
                .flatMap(definition -> definition.sources().stream())
                .map(SourceAddress::timelineId)
                .distinct()
                .sorted(EmbeddingBinding.TEXT_ORDER)
                .toList();
    }

    public boolean deliversEmbeddedRevisionEvents() {
        return embeddedRevisionHandler;
    }

    private static void collect(
            String scopePath,
            List<EffectiveContractSnapshot> contracts,
            Map<Definition, Definition> unique,
            Map<Definition, OperationDefinition> operations) {
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
            if (isInvocableOperation(contract.effectiveTypeBlueId())) {
                FrozenNode requestPattern = contract.headerFields().get(
                        "request");
                operations.putIfAbsent(definition, new OperationDefinition(
                        scopePath,
                        contract.key(),
                        channelKey,
                        isInheritedRequestMetadataOnly(requestPattern)
                                ? null
                                : requestPattern,
                        sources));
            }
        }
    }

    /** See OperationRequestMatcher's request-presence boundary. */
    private static boolean isInheritedRequestMetadataOnly(
            FrozenNode pattern) {
        return pattern != null
                && pattern.getType() == null
                && pattern.getItemType() == null
                && pattern.getKeyType() == null
                && pattern.getValueType() == null
                && pattern.getValue() == null
                && pattern.getItems() == null
                && pattern.getProperties() == null
                && pattern.getContracts() == null
                && pattern.getReferenceBlueId() == null
                && pattern.getSchema() == null
                && pattern.getMergePolicy() == null
                && pattern.getPreviousBlueId() == null
                && pattern.getPosition() == null
                && pattern.getBlue() == null;
    }

    private static boolean isInvocableOperation(String effectiveTypeBlueId) {
        Class<?> effectiveType = REPOSITORY_TYPES.get(effectiveTypeBlueId);
        return effectiveType != null
                && SequentialWorkflowOperation.class.isAssignableFrom(
                        effectiveType);
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
