package blue.coordination.processor;

import blue.language.processor.ExternalOrderKey;
import blue.language.processor.util.PointerUtils;
import blue.language.model.wire.JsonPointer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable classifier for physical reads that may be demanded after indexed
 * delivery preparation.
 *
 * <p>This classifier is a locality boundary, not authorization and not a
 * third PROCESS input.  The Language runtime must still prove every selected
 * Handler, reactive body, and value read.  Runtime-selected reads are admitted
 * only inside a scope selected by an exact source delivery.</p>
 */
public final class CoordinationSemanticDemandBoundary {

    private static final Comparator<String> TEXT_ORDER =
            ExternalOrderKey::compareTextCodePoints;

    private final String rootBlueId;
    private final String eventBlueId;
    private final List<String> selectedScopePaths;
    private final Set<String> requiredSeedBlueIds;
    private final Set<String> sourceHeaderBlueIds;
    private final Set<String> targetHeaderBlueIds;
    private final Set<String> targetChannelSelectors;
    private final List<String> prefetchBlueIds;

    /**
     * Creates a deterministic demand classifier.
     */
    public CoordinationSemanticDemandBoundary(
            String rootBlueId,
            String eventBlueId,
            Collection<String> selectedScopePaths,
            Collection<String> requiredSeedBlueIds,
            Collection<String> sourceHeaderBlueIds,
            Collection<String> targetHeaderBlueIds,
            Collection<String> targetChannelSelectors,
            Collection<String> prefetchBlueIds) {
        this.rootBlueId = requireText(rootBlueId, "rootBlueId");
        this.eventBlueId = requireText(eventBlueId, "eventBlueId");
        this.selectedScopePaths = immutableScopes(
                selectedScopePaths);
        this.requiredSeedBlueIds = immutableTextSet(
                requiredSeedBlueIds, "required seed BlueId");
        this.sourceHeaderBlueIds = immutableTextSet(
                sourceHeaderBlueIds, "source header BlueId");
        this.targetHeaderBlueIds = immutableTextSet(
                targetHeaderBlueIds, "target header BlueId");
        this.targetChannelSelectors = immutableTextSet(
                targetChannelSelectors, "target Channel selector");
        this.prefetchBlueIds = immutableSortedText(
                prefetchBlueIds, "prefetch BlueId");
        if (!this.requiredSeedBlueIds.contains(rootBlueId)
                || !this.requiredSeedBlueIds.contains(eventBlueId)) {
            throw new IllegalArgumentException(
                    "The Root and event must be required seed fragments");
        }
    }

    public String rootBlueId() {
        return rootBlueId;
    }

    public String eventBlueId() {
        return eventBlueId;
    }

    public List<String> selectedScopePaths() {
        return selectedScopePaths;
    }

    public Set<String> requiredSeedBlueIds() {
        return requiredSeedBlueIds;
    }

    public Set<String> sourceHeaderBlueIds() {
        return sourceHeaderBlueIds;
    }

    public Set<String> targetHeaderBlueIds() {
        return targetHeaderBlueIds;
    }

    public Set<String> targetChannelSelectors() {
        return targetChannelSelectors;
    }

    public List<String> prefetchBlueIds() {
        return prefetchBlueIds;
    }

    /**
     * Classifies one proposed exact read.
     *
     * <p>{@link Demand#runtimeSelected()} is meaningful only for reads whose
     * semantic reachability is established later by Language.  Setting that
     * bit does not bypass Language verification; it only prevents the
     * physical host from prefetching such content before selection.</p>
     *
     * @param demand immutable proposed read
     * @return whether the read is inside this preparation's locality boundary
     */
    public boolean permits(Demand demand) {
        Demand checked = Objects.requireNonNull(demand, "demand");
        switch (checked.kind()) {
            case ROOT:
                return rootBlueId.equals(checked.blueId());
            case EVENT:
                return eventBlueId.equals(checked.blueId());
            case SCOPE_CHAIN:
                return requiredSeedBlueIds.contains(checked.blueId())
                        && onSelectedScopeChain(checked.scopePath());
            case SOURCE_CHANNEL_HEADER:
                return sourceHeaderBlueIds.contains(checked.blueId())
                        && isSelectedScope(checked.scopePath());
            case TARGET_CHANNEL_HEADER:
                return targetHeaderBlueIds.contains(checked.blueId())
                        && isSelectedScope(checked.scopePath())
                        && targetChannelSelectors.contains(
                        selector(
                                checked.scopePath(),
                                checked.channelKey()));
            case SELECTED_HANDLER_BODY:
                return checked.runtimeSelected()
                        && isSelectedScope(checked.scopePath())
                        && targetChannelSelectors.contains(
                        selector(
                                checked.scopePath(),
                                checked.channelKey()));
            case REACTIVE_BODY:
            case SCOPE_VALUE:
                return checked.runtimeSelected()
                        && onSelectedScopeChain(checked.scopePath());
            default:
                return false;
        }
    }

    private boolean isSelectedScope(String scopePath) {
        return selectedScopePaths.contains(
                normalizeScope(scopePath));
    }

    private boolean onSelectedScopeChain(String scopePath) {
        String candidate = normalizeScope(scopePath);
        for (String selected : selectedScopePaths) {
            if (PointerUtils.descendantOrEqual(
                    selected, candidate)) {
                return true;
            }
        }
        return false;
    }

    static String selector(String scopePath, String channelKey) {
        return normalizeScope(scopePath)
                + "\u001f"
                + requireText(channelKey, "channelKey");
    }

    private static List<String> immutableScopes(
            Collection<String> source) {
        Objects.requireNonNull(source, "selectedScopePaths");
        Set<String> unique = new LinkedHashSet<>();
        for (String value : source) {
            unique.add(normalizeScope(value));
        }
        List<String> ordered = new ArrayList<>(unique);
        Collections.sort(ordered, (left, right) -> {
            int depth = Integer.compare(
                    JsonPointer.split(right).size(),
                    JsonPointer.split(left).size());
            return depth != 0
                    ? depth
                    : TEXT_ORDER.compare(left, right);
        });
        return Collections.unmodifiableList(ordered);
    }

    private static Set<String> immutableTextSet(
            Collection<String> source,
            String label) {
        return Collections.unmodifiableSet(
                new LinkedHashSet<>(
                        immutableSortedText(source, label)));
    }

    private static List<String> immutableSortedText(
            Collection<String> source,
            String label) {
        Objects.requireNonNull(source, label + " collection");
        Set<String> unique = new LinkedHashSet<>();
        for (String value : source) {
            unique.add(requireText(value, label));
        }
        List<String> ordered = new ArrayList<>(unique);
        Collections.sort(ordered, TEXT_ORDER);
        return Collections.unmodifiableList(ordered);
    }

    private static String normalizeScope(String scopePath) {
        return PointerUtils.normalizeScope(
                requireText(scopePath, "scopePath"));
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(
                    label + " must be non-empty");
        }
        return value;
    }

    /** Physical read categories understood by this deterministic classifier. */
    public enum Kind {
        ROOT,
        EVENT,
        SCOPE_CHAIN,
        SOURCE_CHANNEL_HEADER,
        TARGET_CHANNEL_HEADER,
        SELECTED_HANDLER_BODY,
        REACTIVE_BODY,
        SCOPE_VALUE
    }

    /** Immutable proposed exact provider read. */
    public static final class Demand {
        private final Kind kind;
        private final String scopePath;
        private final String channelKey;
        private final String blueId;
        private final boolean runtimeSelected;

        public Demand(
                Kind kind,
                String scopePath,
                String channelKey,
                String blueId,
                boolean runtimeSelected) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.scopePath = scopePath == null
                    ? null
                    : normalizeScope(scopePath);
            this.channelKey = channelKey;
            this.blueId = requireText(blueId, "blueId");
            this.runtimeSelected = runtimeSelected;
            if (requiresScope(kind) && this.scopePath == null) {
                throw new IllegalArgumentException(
                        kind + " requires a scopePath");
            }
            if (requiresChannel(kind)
                    && (channelKey == null || channelKey.isEmpty())) {
                throw new IllegalArgumentException(
                        kind + " requires a channelKey");
            }
        }

        public Kind kind() {
            return kind;
        }

        public String scopePath() {
            return scopePath;
        }

        public String channelKey() {
            return channelKey;
        }

        public String blueId() {
            return blueId;
        }

        public boolean runtimeSelected() {
            return runtimeSelected;
        }

        private static boolean requiresScope(Kind kind) {
            return kind != Kind.ROOT && kind != Kind.EVENT;
        }

        private static boolean requiresChannel(Kind kind) {
            return kind == Kind.TARGET_CHANNEL_HEADER
                    || kind == Kind.SELECTED_HANDLER_BODY;
        }
    }
}
