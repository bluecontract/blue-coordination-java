package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ChannelCheckpointContext;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ExternalChannelMemberSnapshot;
import blue.repo.coordination.AllTimelinesChannel;
import blue.repo.coordination.CompositeTimelineChannel;
import blue.repo.coordination.TimelineChannel;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Coordination-owned Timeline validation and checkpoint-subject helpers.
 *
 * <p>Cross-Timeline total ordering and completeness admission are supplied by
 * the verified feeder boundary. This helper preserves that platform order and
 * enforces only the fixed Repository rule that timestamps increase strictly
 * within one exact Timeline.</p>
 */
public final class TimelineProviderSupport {
    private TimelineProviderSupport() {
    }

    /**
     * Returns the bounded exact preselection keys for the scalar Timeline and
     * Actor envelope used by Coordination's in-memory provider adapter.
     *
     * <p>The returned set includes the exact pair, Timeline-only, Actor-only,
     * and broad keys. The document-local frozen delivery-plan verifier remains
     * authoritative for acceptance; these keys only prevent whole-document
     * route scans.</p>
     */
    public static List<String> exactScalarEventKeys(
            String timelineId,
            String actorId) {
        return TimelineSubscriptionProjection.exactScalarEventKeys(
                timelineId,
                actorId,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    /**
     * Returns every bounded feeder preselection key emitted by the registered
     * Timeline channel family for one exact Timeline Entry header.
     */
    public static List<String> exactTimelineEntryEventKeys(
            String timelineId,
            String actorId) {
        LinkedHashSet<String> result = new LinkedHashSet<>(
                exactScalarEventKeys(timelineId, actorId));
        result.add(AllTimelinesExternalSubscriptionFunctions
                .ALL_TIMELINES_KEY);
        return List.copyOf(result);
    }

    /**
     * Returns the exact logical-delivery key used by the registered Operation
     * Request runtime for one resolved operation and target channel.
     *
     * <p>Feeder selection must freeze this value rather than the authored
     * operation name so Contracts can verify the selected delivery against
     * the same runtime function that will execute it.</p>
     *
     * @param operation resolved operation name
     * @param channel resolved target channel key
     * @return canonical runtime logical-delivery key
     */
    public static String operationRequestLogicalDeliveryKey(
            String operation,
            String channel) {
        return OperationRequestRoutingFunctions.logicalDeliveryKey(
                operation, channel);
    }

    /**
     * Validates the immutable envelope accepted by the compact Timeline
     * feeder. The check uses the registered Timeline Entry and Operation
     * Request identities; it does not invent document-targeting semantics.
     *
     * @param exactEntry exact, direct-header Timeline Entry
     * @throws IllegalArgumentException when the supported envelope is invalid
     */
    public static void validateExactEnvelope(Node exactEntry) {
        CoordinationEventNodes.TimelineEntryView entry =
                CoordinationEventNodes.timelineEntry(exactEntry);
        CoordinationEventNodes.OperationRequestView request =
                CoordinationEventNodes.operationRequest(exactEntry);
        String timelineId = entry == null ? null
                : textProperty(entry.timeline(), "timelineId");
        String actorId = entry == null ? null
                : textProperty(entry.actor(), "accountId");
        if (entry == null || request == null || !request.routable()
                || timelineId == null || timelineId.isBlank()
                || actorId == null || actorId.isBlank()
                || entry.timestamp().signum() <= 0
                || entry.timestamp().bitLength() > 63) {
            throw new IllegalArgumentException(
                    "Invalid exact Timeline Entry envelope");
        }
        Node message = entry.message();
        Node exactVersion = property(
                message, "requireExactDocumentVersion");
        Object exactVersionValue = exactVersion == null
                ? null : exactVersion.getValue();
        if (exactVersionValue != null
                && !(exactVersionValue instanceof Boolean)) {
            throw new IllegalArgumentException(
                    "requireExactDocumentVersion must be Boolean");
        }
        if (Boolean.TRUE.equals(exactVersionValue)
                && !hasDocumentValue(property(message, "document"))) {
            throw new IllegalArgumentException(
                    "Exact document version requires a document");
        }
        if (hasRuntimeValue(property(exactEntry, "onBehalfOf"))) {
            throw new IllegalArgumentException(
                    "onBehalfOf requires a Mandate resolver");
        }
    }

    /**
     * Recreates the exact current-runtime checkpoint discriminator retained by
     * the host for one processor-owned external subscription occurrence.
     */
    public static String checkpointDomainRuntimeDiscriminator(
            String effectiveTypeBlueId) {
        String typeBlueId = requireText(
                effectiveTypeBlueId, "effectiveTypeBlueId");
        if (CompositeTimelineChannel.blueId().equals(typeBlueId)) {
            return "coordination.composite-timeline:"
                    + "direct-timeline-members-v2"
                    + "|subject="
                    + CompositeTimelineExternalSubscriptionFunctions
                    .ORDER_SUBJECT_VERSION;
        }
        if (AllTimelinesChannel.blueId().equals(typeBlueId)) {
            return "coordination.all-timelines:"
                    + "timeline-type-family-v2"
                    + "|subject="
                    + AllTimelinesExternalSubscriptionFunctions
                    .ORDER_SUBJECT_VERSION;
        }
        CoordinationSemanticTypeIdentities identities =
                CoordinationSemanticTypeIdentities.publishedDefaults();
        return "coordination.timeline-entry:"
                + identities.timelineEntryBlueId()
                + "|semantic-profile="
                + identities.profileIdentity()
                + "|projection="
                + TimelineSubscriptionProjection.VERSION
                + "|subject="
                + TimelineExternalSubscriptionFunctions
                .TIMELINE_ORDER_SUBJECT_VERSION;
    }

    public static ChannelEvaluation evaluateTimelineEntry(TimelineChannel contract, ChannelEvaluationContext context) {
        return evaluateTimelineEntry(
                contract,
                context,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    static ChannelEvaluation evaluateTimelineEntry(
            TimelineChannel contract,
            ChannelEvaluationContext context,
            CoordinationSemanticTypeIdentities identities) {
        Node eventNode = context.event();
        CoordinationEventNodes.TimelineEntryView entry =
                CoordinationEventNodes.timelineEntry(
                        eventNode, identities);
        if (entry == null) {
            return ChannelEvaluation.noMatch();
        }
        if (!matchesTimelineAndActor(
                contract, entry, identities)) {
            return ChannelEvaluation.noMatch();
        }
        return ChannelEvaluation.match(eventNode, eventId(eventNode));
    }

    static boolean matchesTimelineAndActor(TimelineChannel contract,
                                           CoordinationEventNodes.TimelineEntryView entry) {
        return matchesTimelineAndActor(
                contract,
                entry,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    static boolean matchesTimelineAndActor(
            TimelineChannel contract,
            CoordinationEventNodes.TimelineEntryView entry,
            CoordinationSemanticTypeIdentities identities) {
        return contract != null
                && entry != null
                && CoordinationEventNodes.matchesGeneratedBinding(
                        entry.timeline(), contract.getTimeline(), identities)
                && CoordinationEventNodes.matchesGeneratedBinding(
                        entry.actor(), contract.getActor(), identities);
    }

    static ChannelEvaluation preserveUnionPayload(ChannelEvaluation childEvaluation,
                                                  Node fallbackEvent) {
        Node deliveryEvent = childEvaluation.event() != null
                ? childEvaluation.event()
                : fallbackEvent;
        return deliveryEvent != null
                ? ChannelEvaluation.match(deliveryEvent, childEvaluation.eventId())
                : ChannelEvaluation.noMatch();
    }

    public static String eventId(Node eventNode) {
        return eventNode != null
                ? BlueSemanticIdentity.identity(
                        eventNode.clone().blue(null))
                : null;
    }

    /**
     * Retains the pre-release context-free filter signature while applying the
     * current exact Timeline acceptance predicate.
     *
     * @param contract exact Timeline Channel contract
     * @param event exact event candidate
     * @return whether the current Timeline acceptance predicate accepts it
     */
    public static boolean matchesEventFilter(
            TimelineChannel contract,
            Node event) {
        return contract != null
                && event != null
                && TimelineExternalSubscriptionFunctions.INSTANCE
                .accepts(contract, event);
    }

    /**
     * Retains the pre-release signature and applies the current strict direct
     * Timeline checkpoint-subject ordering.
     *
     * @param context exact checkpoint context
     * @return whether the direct Timeline subject is strictly newer
     */
    public static boolean isNewerOrSameTimelineEvent(
            ChannelCheckpointContext context) {
        return isNewerTimelineSubject(
                context,
                TimelineExternalSubscriptionFunctions
                        .TIMELINE_ORDER_SUBJECT_VERSION);
    }

    /**
     * Retains the pre-release signature. Cross-Timeline ordering now belongs
     * to verified feeder evidence, so this method applies the same strict
     * direct-subject rule without recreating legacy cross-source ordering.
     *
     * @param context exact checkpoint context
     * @return whether the direct Timeline subject is strictly newer
     */
    public static boolean isNewerOrDifferentTimelineEvent(
            ChannelCheckpointContext context) {
        return isNewerOrSameTimelineEvent(context);
    }

    /**
     * Validates one platform-ordered feeder window against exact active-source
     * completeness evidence.
     *
     * <p>Every active Timeline is named by its exact BlueId. A window is ready
     * only when each source proves {@code completeBefore > max(timestamp)}.
     * Missing or insufficient evidence suspends the window; extra evidence or
     * an entry from an undeclared source is inconsistent and fails closed.
     * The input is already in the verified total order owned by the feeder and
     * is preserved exactly. Coordination validates only that timestamps are
     * strictly increasing within each individual Timeline; it neither defines
     * nor reconstructs cross-Timeline ordering.</p>
     *
     * @param exactTimelineEntries platform-ordered exact Timeline Entries
     * @param exactActiveTimelines exact active Timeline identities
     * @param completeBeforeByTimelineBlueId exclusive completeness frontier
     *                                         for each active Timeline BlueId
     * @return ready window or a suspended window naming incomplete Timelines
     */
    public static CompletenessWindow evaluateCompletenessWindow(
            List<Node> exactTimelineEntries,
            List<Node> exactActiveTimelines,
            Map<String, BigInteger> completeBeforeByTimelineBlueId) {
        if (exactTimelineEntries == null
                || exactActiveTimelines == null
                || completeBeforeByTimelineBlueId == null) {
            throw new IllegalArgumentException(
                    "Timeline completeness inputs must be present");
        }
        SortedSet<String> activeTimelineBlueIds =
                new TreeSet<String>();
        for (Node activeTimeline : exactActiveTimelines) {
            String timelineBlueId = exactNodeBlueId(
                    activeTimeline, "active Timeline");
            if (!activeTimelineBlueIds.add(timelineBlueId)) {
                throw new IllegalArgumentException(
                        "Duplicate active Timeline evidence: "
                                + timelineBlueId);
            }
        }
        for (String evidencedTimeline
                : completeBeforeByTimelineBlueId.keySet()) {
            if (!activeTimelineBlueIds.contains(evidencedTimeline)) {
                throw new IllegalArgumentException(
                        "Completeness evidence names an inactive Timeline: "
                                + evidencedTimeline);
            }
        }

        List<Node> entries = new ArrayList<Node>(
                exactTimelineEntries.size());
        Map<String, BigInteger> lastTimestampByTimeline =
                new HashMap<String, BigInteger>();
        BigInteger maximumTimestamp = null;
        for (Node exactEntry : exactTimelineEntries) {
            CoordinationEventNodes.TimelineEntryView entry =
                    requireTimelineEntry(exactEntry, "entry");
            String timelineBlueId = exactNodeBlueId(
                    entry.timeline(), "entry Timeline");
            if (!activeTimelineBlueIds.contains(timelineBlueId)) {
                throw new IllegalArgumentException(
                        "Timeline Entry belongs to an inactive Timeline: "
                                + timelineBlueId);
            }
            BigInteger lastTimestamp =
                    lastTimestampByTimeline.get(timelineBlueId);
            if (lastTimestamp != null
                    && entry.timestamp().compareTo(
                    lastTimestamp) <= 0) {
                throw new IllegalArgumentException(
                        "Timeline Entry timestamps must be strictly "
                                + "increasing within "
                                + "Timeline " + timelineBlueId + ": "
                                + entry.timestamp());
            }
            lastTimestampByTimeline.put(
                    timelineBlueId, entry.timestamp());
            if (maximumTimestamp == null
                    || entry.timestamp().compareTo(
                    maximumTimestamp) > 0) {
                maximumTimestamp = entry.timestamp();
            }
            entries.add(exactEntry.clone());
        }

        List<String> incomplete =
                new ArrayList<String>();
        if (maximumTimestamp != null) {
            for (String timelineBlueId
                    : activeTimelineBlueIds) {
                BigInteger completeBefore =
                        completeBeforeByTimelineBlueId.get(
                                timelineBlueId);
                if (completeBefore == null
                        || completeBefore.compareTo(
                        maximumTimestamp) <= 0) {
                    incomplete.add(timelineBlueId);
                }
            }
        }
        if (!incomplete.isEmpty()) {
            return CompletenessWindow.suspended(
                    maximumTimestamp, incomplete);
        }
        return CompletenessWindow.ready(
                maximumTimestamp, entries);
    }

    /**
     * Tests a provider append against an already binding completeness
     * frontier.
     *
     * <p>The frontier is exclusive: an entry at the frontier is not
     * backdated. Missing or mismatched binding evidence fails closed with an
     * exception instead of being interpreted as semantic absence.</p>
     *
     * @param exactTimelineEntry exact Timeline Entry proposed for append
     * @param exactTimeline exact Timeline bound to the frontier
     * @param completeBefore exclusive committed completeness frontier
     * @return whether the entry timestamp is before the committed frontier
     */
    public static boolean isBehindCommittedFrontier(
            Node exactTimelineEntry,
            Node exactTimeline,
            BigInteger completeBefore) {
        if (exactTimeline == null) {
            throw new IllegalArgumentException(
                    "Binding completeness requires an exact Timeline");
        }
        if (completeBefore == null) {
            throw new IllegalArgumentException(
                    "Binding completeness requires an exact frontier");
        }
        CoordinationEventNodes.TimelineEntryView entry =
                requireTimelineEntry(exactTimelineEntry, "entry");
        String boundTimelineBlueId = exactNodeBlueId(
                exactTimeline, "frontier Timeline");
        String entryTimelineBlueId = exactNodeBlueId(
                entry.timeline(), "entry Timeline");
        if (!boundTimelineBlueId.equals(entryTimelineBlueId)) {
            throw new IllegalArgumentException(
                    "Binding completeness frontier belongs to a different "
                            + "Timeline");
        }
        return entry.timestamp().compareTo(completeBefore) < 0;
    }

    /**
     * Verifies one exact predecessor edge and strictly increasing
     * same-Timeline timestamp order.
     *
     * @param exactTimelineEntry exact successor Timeline Entry
     * @param exactPredecessor exact proposed predecessor Timeline Entry
     * @return whether the successor names the predecessor and has a later
     *         timestamp on the same Timeline
     */
    public static boolean followsExactPredecessor(
            Node exactTimelineEntry,
            Node exactPredecessor) {
        CoordinationEventNodes.TimelineEntryView entry =
                requireTimelineEntry(exactTimelineEntry, "entry");
        CoordinationEventNodes.TimelineEntryView predecessor =
                requireTimelineEntry(exactPredecessor, "predecessor");
        Node declaredPredecessor = entry.prevEntry();
        if (declaredPredecessor == null) {
            return false;
        }
        if (!exactNodeBlueId(entry.timeline(), "entry Timeline")
                .equals(exactNodeBlueId(
                        predecessor.timeline(),
                        "predecessor Timeline"))) {
            return false;
        }
        return exactNodeBlueId(
                declaredPredecessor,
                "declared predecessor")
                .equals(predecessor.entryBlueId())
                && entry.timestamp().compareTo(
                predecessor.timestamp()) > 0;
    }

    public static Node property(Node node, String key) {
        if (node == null || node.getProperties() == null) {
            return null;
        }
        return node.getProperties().get(key);
    }

    private static boolean hasDocumentValue(Node node) {
        return hasRuntimeValue(node) || node != null && node.getType() != null;
    }

    private static boolean hasRuntimeValue(Node node) {
        return node != null && (node.getBlueId() != null
                || node.getValue() != null
                || node.getItems() != null && !node.getItems().isEmpty()
                || node.getProperties() != null
                && !node.getProperties().isEmpty());
    }

    public static String textProperty(Node node, String key) {
        Node property = property(node, key);
        Object value = property != null ? property.getValue() : null;
        return value instanceof String ? (String) value : null;
    }

    static Node timelineOrderSubject(
            CoordinationEventNodes.TimelineEntryView entry) {
        if (entry == null) {
            throw new IllegalArgumentException(
                    "Timeline order subject requires a Timeline Entry");
        }
        return timelineOrderSubject(
                TimelineExternalSubscriptionFunctions
                        .TIMELINE_ORDER_SUBJECT_VERSION,
                timelinePosition(entry, "checkpoint"));
    }

    static Node memberTimelineOrderSubject(
            String semantics,
            ExternalChannelMemberSnapshot member,
            Node exactMemberSubject) {
        TimelineOrder memberOrder = timelineOrder(
                exactMemberSubject,
                TimelineExternalSubscriptionFunctions
                        .TIMELINE_ORDER_SUBJECT_VERSION);
        if (memberOrder == null) {
            throw new IllegalArgumentException(
                    "Timeline member order subject requires the selected "
                            + "member's exact Timeline subject");
        }
        return timelineOrderSubject(
                semantics, memberOrder.position)
                .properties("memberKey",
                        new Node().value(member.channelKey()))
                .properties("memberDomain",
                        new Node().value(
                                member.checkpointDomainBlueId()));
    }

    static boolean isNewerTimelineSubject(
            ChannelCheckpointContext context,
            String expectedSemantics) {
        TimelineOrder current = timelineOrder(
                context.currentSubject(), expectedSemantics);
        if (current == null) {
            throw new IllegalArgumentException(
                    "Current Timeline checkpoint has no exact order "
                            + "subject");
        }
        if (context.eventSignature() != null
                && context.eventSignature().equals(
                context.lastEventSignature())) {
            return false;
        }
        Node previousSubject = context.lastEvent();
        if (previousSubject == null) {
            return true;
        }
        TimelineOrder previous =
                timelineOrder(previousSubject, expectedSemantics);
        if (previous == null) {
            throw new IllegalArgumentException(
                    "Stored Timeline checkpoint subject is malformed");
        }
        if (current.position.timelineBlueId.equals(
                previous.position.timelineBlueId)) {
            return current.position.timestamp.compareTo(
                    previous.position.timestamp) > 0;
        }
        /*
         * The managing feeder supplies and verifies the concrete source's
         * total order before PROCESS. A Composite or All Timelines checkpoint
         * must consume that order, not replace the source-specific
         * cross-Timeline rule with a lexical Timeline-BlueId tie-break.
         */
        return true;
    }

    private static TimelineOrder timelineOrder(Node node,
                                               String expectedSemantics) {
        String semantics = textProperty(node, "semantics");
        if (!expectedSemantics.equals(semantics)) {
            return null;
        }
        Node timestampNode = property(node, "timestamp");
        Object rawTimestamp =
                timestampNode != null
                        ? timestampNode.getValue()
                        : null;
        BigInteger timestamp = integer(rawTimestamp);
        String timelineBlueId =
                textProperty(node, "timelineBlueId");
        String entryBlueId =
                textProperty(node, "entryBlueId");
        Node memberKeyNode = property(node, "memberKey");
        Node memberDomainNode = property(node, "memberDomain");
        String memberKey = textProperty(node, "memberKey");
        String memberDomain = textProperty(node, "memberDomain");
        boolean direct = TimelineExternalSubscriptionFunctions
                .TIMELINE_ORDER_SUBJECT_VERSION.equals(
                        expectedSemantics);
        if (timestamp == null
                || timelineBlueId == null
                || timelineBlueId.isEmpty()
                || entryBlueId == null
                || entryBlueId.isEmpty()
                || direct && (memberKeyNode != null
                || memberDomainNode != null)
                || !direct && (memberKey == null
                || memberKey.isEmpty()
                || memberDomain == null
                || memberDomain.isEmpty())) {
            return null;
        }
        return new TimelineOrder(
                new TimelinePosition(
                        timestamp,
                        timelineBlueId,
                        entryBlueId));
    }

    private static Node timelineOrderSubject(
            String semantics,
            TimelinePosition position) {
        return new Node()
                .properties("semantics",
                        new Node().value(semantics))
                .properties("timestamp",
                        new Node().value(position.timestamp))
                .properties("timelineBlueId",
                        new Node().value(position.timelineBlueId))
                .properties("entryBlueId",
                        new Node().value(position.entryBlueId));
    }

    private static BigInteger integer(Object value) {
        if (value instanceof BigInteger) {
            return (BigInteger) value;
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return BigInteger.valueOf(
                    ((Number) value).longValue());
        }
        return null;
    }

    private static TimelinePosition timelinePosition(
            CoordinationEventNodes.TimelineEntryView entry,
            String role) {
        return new TimelinePosition(
                entry.timestamp(),
                exactNodeBlueId(entry.timeline(), role + " Timeline"),
                entry.entryBlueId());
    }

    private static CoordinationEventNodes.TimelineEntryView
    requireTimelineEntry(Node node, String role) {
        CoordinationEventNodes.TimelineEntryView entry =
                CoordinationEventNodes.timelineEntry(node);
        if (entry == null) {
            throw new IllegalArgumentException(
                    "Timeline " + role
                            + " must be an exact Timeline Entry");
        }
        return entry;
    }

    private static String exactNodeBlueId(Node node, String role) {
        if (node == null) {
            throw new IllegalArgumentException(
                    role + " exact node is required");
        }
        String blueId = eventId(node);
        if (blueId == null || blueId.isEmpty()) {
            throw new IllegalArgumentException(
                    role + " has no exact identity");
        }
        return blueId;
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    private static final class TimelinePosition {
        private final BigInteger timestamp;
        private final String timelineBlueId;
        private final String entryBlueId;

        private TimelinePosition(
                BigInteger timestamp,
                String timelineBlueId,
                String entryBlueId) {
            this.timestamp = timestamp;
            this.timelineBlueId = timelineBlueId;
            this.entryBlueId = entryBlueId;
        }
    }

    private static final class TimelineOrder {
        private final TimelinePosition position;

        private TimelineOrder(TimelinePosition position) {
            this.position = position;
        }
    }

    /**
     * Immutable result of deterministic feeder-window completeness.
     */
    public static final class CompletenessWindow {
        private final boolean ready;
        private final BigInteger maximumTimestamp;
        private final List<Node> orderedEntries;
        private final List<String> incompleteTimelineBlueIds;

        private CompletenessWindow(
                boolean ready,
                BigInteger maximumTimestamp,
                List<Node> orderedEntries,
                List<String> incompleteTimelineBlueIds) {
            this.ready = ready;
            this.maximumTimestamp = maximumTimestamp;
            this.orderedEntries = immutableNodes(orderedEntries);
            this.incompleteTimelineBlueIds =
                    Collections.unmodifiableList(
                            new ArrayList<String>(
                                    incompleteTimelineBlueIds));
        }

        private static CompletenessWindow ready(
                BigInteger maximumTimestamp,
                List<Node> orderedEntries) {
            return new CompletenessWindow(
                    true,
                    maximumTimestamp,
                    orderedEntries,
                    Collections.<String>emptyList());
        }

        private static CompletenessWindow suspended(
                BigInteger maximumTimestamp,
                List<String> incompleteTimelineBlueIds) {
            return new CompletenessWindow(
                    false,
                    maximumTimestamp,
                    Collections.<Node>emptyList(),
                    incompleteTimelineBlueIds);
        }

        public boolean ready() {
            return ready;
        }

        public BigInteger maximumTimestamp() {
            return maximumTimestamp;
        }

        public List<Node> orderedEntries() {
            return immutableNodes(orderedEntries);
        }

        public List<String> incompleteTimelineBlueIds() {
            return incompleteTimelineBlueIds;
        }

        private static List<Node> immutableNodes(
                List<Node> source) {
            List<Node> copy = new ArrayList<Node>(
                    source.size());
            for (Node node : source) {
                copy.add(node.clone());
            }
            return Collections.unmodifiableList(copy);
        }
    }

}
