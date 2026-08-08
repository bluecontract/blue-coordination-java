package blue.coordination.basic;

import blue.coordination.processor.CoordinationDeliveryPlanning;
import blue.coordination.processor.CoordinationIndexedDeliveryPlanner;
import blue.coordination.processor.CoordinationPreparedDelivery;
import blue.coordination.processor.CoordinationSubscriptionOccurrence;
import blue.coordination.processor.CoordinationSubscriptionProjector;
import blue.coordination.processor.CoordinationSubscriptionSnapshot;
import blue.coordination.processor.CoordinationSubscriptionUpdate;
import blue.coordination.processor.CoordinationTestRuntime;
import blue.coordination.processor.CoordinationTimelineRouteProjection;
import blue.coordination.examples.support.MyOsDemoYaml;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.processor.EmbeddedScopePlanView;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.PlatformProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.util.PointerUtils;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.repo.BlueRepository;

import java.math.BigInteger;
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
 * Minimal in-memory acceptance host whose physical boundary is a Process
 * Embedded scope.
 *
 * <p>This is deliberately not a Coordination processing-engine session. It
 * exercises real Language and Contracts initialization and processing, then
 * retains each current Root in an occurrence-scoped layout without invoking
 * the canonical direct-node admission profile. A Root is retained whole;
 * only concrete children declared by the effective {@code Process Embedded}
 * catalog become separate content-addressed document objects. Timeline
 * Entries are retained whole. Authored pure references remain authored
 * references and are never reported as splitter-created edges.</p>
 */
final class EmbeddedOnlyDocumentEnvironment implements AutoCloseable {
    static final String LAYOUT_PROFILE_ID =
            "blue.coordination/document-layout/process-embedded-only/1.0";
    private static final long BASE_TIMESTAMP_MICROS =
            1_785_000_000_000_000L;

    private final CoordinationTestRuntime runtime;
    private final CoordinationSubscriptionProjector subscriptionProjector;
    private final CoordinationIndexedDeliveryPlanner deliveryPlanner;
    private final Map<String, StartedDocument> documents =
            new LinkedHashMap<>();
    private final Map<String, Node> exactNodesByBlueId =
            new LinkedHashMap<>();
    private final Map<String, TimelineState> timelines =
            new LinkedHashMap<>();
    private final Map<String, TimelineEntry> entriesByBlueId =
            new LinkedHashMap<>();
    private long timelineEntrySequence;
    private boolean closed;

    private EmbeddedOnlyDocumentEnvironment(
            CoordinationTestRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.subscriptionProjector =
                CoordinationDeliveryPlanning.subscriptionProjector(
                        runtime.processor(), runtime.contracts());
        this.deliveryPlanner = CoordinationDeliveryPlanning.indexed(
                runtime.processor(), runtime.contracts());
    }

    static EmbeddedOnlyDocumentEnvironment create() {
        return new EmbeddedOnlyDocumentEnvironment(
                CoordinationTestRuntime.create(BlueRepository.current()));
    }

    synchronized StartResult start(String key, String authoredYaml) {
        ensureOpen();
        String checkedKey = requireText(key, "key");
        String checkedYaml = requireText(authoredYaml, "authoredYaml");
        if (documents.containsKey(checkedKey)) {
            throw new IllegalArgumentException(
                    "Duplicate document key: " + checkedKey);
        }

        long totalStarted = System.nanoTime();
        long phaseStarted = System.nanoTime();
        Node source = runtime.parseSourceYaml(checkedYaml);
        long parseSourceNanos = elapsed(phaseStarted);

        phaseStarted = System.nanoTime();
        Node sourceIdentityInput = runtime.canonicalize(source);
        String initialBlueId = DirectBlueIdCalculator.calculateBlueId(
                sourceIdentityInput);
        long sourceIdentityNanos = elapsed(phaseStarted);

        phaseStarted = System.nanoTime();
        Node preprocessed = runtime.preprocess(source);
        long preprocessNanos = elapsed(phaseStarted);

        phaseStarted = System.nanoTime();
        ResolvedSnapshot initializationSnapshot =
                runtime.resolveToSnapshot(preprocessed);
        long resolveInitializationSnapshotNanos = elapsed(phaseStarted);

        phaseStarted = System.nanoTime();
        DocumentProcessingResult initialization =
                runtime.initializeDocument(initializationSnapshot);
        long contractsInitializationNanos = elapsed(phaseStarted);

        phaseStarted = System.nanoTime();
        requireSuccessfulInitialization(checkedKey, initialization);
        Node initializedRoot = initialization.document();
        String initializedRootBlueId =
                DirectBlueIdCalculator.calculateBlueId(initializedRoot);
        long captureInitializedRootNanos = elapsed(phaseStarted);

        phaseStarted = System.nanoTime();
        EffectiveFragmentationCatalog catalog =
                runtime.contracts().effectiveFragmentationCatalog(
                        initializedRoot);
        long discoverEmbeddedScopesNanos = elapsed(phaseStarted);

        phaseStarted = System.nanoTime();
        EmbeddedDocumentLayout layout = EmbeddedDocumentLayout.create(
                initializedRoot,
                catalog,
                runtime.nodeProvider());
        long retainDocumentObjectsNanos = elapsed(phaseStarted);

        phaseStarted = System.nanoTime();
        ExternalOrderKey admissionFrontier = currentAdmissionFrontier(
                checkedKey);
        CoordinationSubscriptionSnapshot subscriptions =
                subscriptionProjector.projectCurrent(
                        initializedRoot,
                        1L,
                        admissionFrontier);
        long projectSubscriptionsNanos = elapsed(phaseStarted);

        phaseStarted = System.nanoTime();
        StartedDocument document = new StartedDocument(
                checkedKey,
                checkedYaml,
                initialBlueId,
                initializedRootBlueId,
                0L,
                initialization.totalGas(),
                initialization.events().size(),
                layout,
                subscriptions,
                admissionFrontier,
                Set.of());
        Map<String, Node> stagedExactNodes = new LinkedHashMap<>();
        stageExactNode(
                stagedExactNodes, initialBlueId, sourceIdentityInput);
        stageLayoutNodes(stagedExactNodes, layout);
        requireCompatibleExactNodes(stagedExactNodes);
        documents.put(checkedKey, document);
        publishExactNodes(stagedExactNodes);
        long publicationNanos = elapsed(phaseStarted);

        StartTiming timing = new StartTiming(
                elapsed(totalStarted),
                parseSourceNanos,
                sourceIdentityNanos,
                preprocessNanos,
                resolveInitializationSnapshotNanos,
                contractsInitializationNanos,
                captureInitializedRootNanos,
                discoverEmbeddedScopesNanos,
                retainDocumentObjectsNanos,
                projectSubscriptionsNanos,
                publicationNanos);
        return new StartResult(document, timing);
    }

    synchronized int documentCount() {
        ensureOpen();
        return documents.size();
    }

    synchronized StartedDocument document(String key) {
        ensureOpen();
        StartedDocument document = documents.get(
                Objects.requireNonNull(key, "key"));
        if (document == null) {
            throw new IllegalArgumentException("Unknown document: " + key);
        }
        return document;
    }

    synchronized Timeline timeline(String timelineId, String actorId) {
        ensureOpen();
        String checkedTimelineId = requireText(
                timelineId, "timelineId");
        String checkedActorId = requireText(actorId, "actorId");
        TimelineState existing = timelines.get(checkedTimelineId);
        if (existing != null) {
            if (!existing.timeline.actorId().equals(checkedActorId)) {
                throw new IllegalArgumentException(
                        "Timeline belongs to another actor: "
                                + checkedTimelineId);
            }
            return existing.timeline;
        }
        Timeline timeline = new Timeline(
                checkedTimelineId, checkedActorId);
        timelines.put(checkedTimelineId, new TimelineState(timeline));
        return timeline;
    }

    synchronized TimelineEntry append(
            Timeline timeline,
            String operation,
            String channel,
            String requestYaml) {
        ensureOpen();
        Timeline checkedTimeline = Objects.requireNonNull(
                timeline, "timeline");
        TimelineState state = timelines.get(checkedTimeline.timelineId());
        if (state == null || !state.timeline.equals(checkedTimeline)) {
            throw new IllegalArgumentException(
                    "Timeline does not belong to this environment");
        }
        String checkedOperation = requireText(operation, "operation");
        String checkedChannel = requireText(channel, "channel");
        String checkedRequest = Objects.requireNonNull(
                requestYaml, "requestYaml").strip();
        if (checkedRequest.isEmpty()) {
            checkedRequest = "{}";
        }
        long nextSequence = Math.addExact(timelineEntrySequence, 1L);
        long timestamp = Math.addExact(BASE_TIMESTAMP_MICROS, nextSequence);
        String yaml = timelineEntryYaml(
                checkedTimeline,
                state.previousEntryBlueId,
                timestamp,
                checkedOperation,
                checkedChannel,
                checkedRequest);
        Node source = runtime.parseSourceYaml(yaml);
        Node preprocessed = runtime.preprocess(source);
        Node exactEvent = runtime.resolveToSnapshot(
                preprocessed).canonicalRoot();
        String eventBlueId = DirectBlueIdCalculator.calculateBlueId(
                exactEvent);
        ExternalOrderKey orderKey = ExternalOrderKey.of(List.of(
                BigInteger.valueOf(timestamp),
                checkedTimeline.timelineId(),
                eventBlueId));
        TimelineEntry entry = new TimelineEntry(
                exactEvent,
                eventBlueId,
                orderKey,
                checkedTimeline.timelineId(),
                checkedTimeline.actorId(),
                checkedChannel,
                checkedOperation,
                checkedChannel,
                timestamp);
        TimelineEntry duplicate = entriesByBlueId.get(eventBlueId);
        if (duplicate != null
                && !NodeWireForm.get(duplicate.exactEvent()).equals(
                        NodeWireForm.get(exactEvent))) {
            throw new IllegalStateException(
                    "Conflicting Timeline Entry " + eventBlueId);
        }
        state.previousEntryBlueId = eventBlueId;
        state.entryBlueIds.add(eventBlueId);
        entriesByBlueId.putIfAbsent(eventBlueId, entry);
        if (duplicate == null) {
            exactNodesByBlueId.put(eventBlueId, exactEvent.clone());
        }
        timelineEntrySequence = nextSequence;
        return duplicate != null ? duplicate : entry;
    }

    synchronized Set<String> candidateDocumentKeys(
            TimelineEntry entry) {
        ensureOpen();
        TimelineEntry checked = requireAuthoredEntry(entry);
        List<String> eventKeys = CoordinationTimelineRouteProjection
                .exactEventSubscriptionKeys(
                        checked.timelineId(), checked.actorId());
        Set<String> candidates = new LinkedHashSet<>();
        for (StartedDocument document : documents.values()) {
            if (!candidateOccurrenceKeys(
                    document, checked, eventKeys).isEmpty()) {
                candidates.add(document.key());
            }
        }
        return Collections.unmodifiableSet(candidates);
    }

    synchronized DispatchResult process(TimelineEntry entry) {
        ensureOpen();
        long totalStarted = System.nanoTime();
        TimelineEntry checked = requireAuthoredEntry(entry);
        for (StartedDocument document : documents.values()) {
            if (document.deliveredEventBlueIds().contains(
                    checked.blueId())) {
                throw new IllegalStateException(
                        "Timeline Entry already committed: "
                                + checked.blueId());
            }
        }
        long phaseStarted = System.nanoTime();
        Set<String> candidateKeys = candidateDocumentKeys(checked);
        long candidateRoutingNanos = elapsed(phaseStarted);
        if (candidateKeys.isEmpty()) {
            throw new IllegalStateException(
                    "Timeline Entry has no subscribed documents");
        }
        Map<String, DocumentTransition> transitions =
                new LinkedHashMap<>();
        Map<String, StartedDocument> stagedDocuments =
                new LinkedHashMap<>();
        Map<String, Node> stagedExactNodes = new LinkedHashMap<>();
        for (String key : candidateKeys) {
            StartedDocument before = document(key);
            requireDeliverable(before, checked);
            DocumentTransition transition = prepareTransition(
                    before, checked);
            transitions.put(key, transition);
            stagedDocuments.put(key, transition.after());
            stageLayoutNodes(
                    stagedExactNodes, transition.after().layout());
        }
        requireCompatibleExactNodes(stagedExactNodes);

        // Publish only after every selected Root has processed and validated.
        phaseStarted = System.nanoTime();
        documents.putAll(stagedDocuments);
        publishExactNodes(stagedExactNodes);
        long atomicPublicationNanos = elapsed(phaseStarted);
        long transitionNanos = transitions.values().stream()
                .mapToLong(transition -> transition.timing().totalNanos())
                .reduce(0L, Math::addExact);
        DispatchTiming timing = new DispatchTiming(
                elapsed(totalStarted),
                candidateRoutingNanos,
                transitionNanos,
                atomicPublicationNanos);
        return new DispatchResult(checked, transitions, timing);
    }

    synchronized int timelineCount() {
        ensureOpen();
        return timelines.size();
    }

    synchronized int timelineEntryCount() {
        ensureOpen();
        return entriesByBlueId.size();
    }

    private DocumentTransition prepareTransition(
            StartedDocument before,
            TimelineEntry entry) {
        long totalStarted = System.nanoTime();
        long phaseStarted = System.nanoTime();
        Node currentRoot = before.currentRoot();
        long reconstructRootNanos = elapsed(phaseStarted);

        phaseStarted = System.nanoTime();
        Node exactEvent = entry.exactEvent();
        NodeProvider exactProvider = exactProvider(
                currentRoot, exactEvent);
        CoordinationPreparedDelivery prepared =
                deliveryPlanner.prepare(
                        before.currentRootBlueId(),
                        entry.blueId(),
                        before.subscriptions(),
                        candidateOccurrenceKeys(before, entry),
                        exactProvider,
                        before.subscriptions().rootRevision(),
                        entry.orderKey());
        long prepareDeliveryNanos = elapsed(phaseStarted);

        phaseStarted = System.nanoTime();
        PlatformProcessingResult platform =
                deliveryPlanner.processForPlatformCommit(
                        currentRoot,
                        exactEvent,
                        prepared,
                        exactProvider);
        DocumentProcessingResult processed = platform.processResult();
        long contractsProcessNanos = elapsed(phaseStarted);
        requireSuccessfulProcess(before.key(), processed);

        phaseStarted = System.nanoTime();
        Node resultingRoot = processed.document();
        CoordinationSubscriptionUpdate subscriptionUpdate =
                subscriptionProjector.applyPlatformCommit(
                        before.subscriptions(),
                        platform,
                        resultingRoot);
        EffectiveFragmentationCatalog catalog = subscriptionUpdate
                .fragmentationCatalog()
                .orElseGet(() -> runtime.contracts()
                        .effectiveFragmentationCatalog(resultingRoot));
        long updateSubscriptionsNanos = elapsed(phaseStarted);

        phaseStarted = System.nanoTime();
        EmbeddedDocumentLayout layout = EmbeddedDocumentLayout.create(
                resultingRoot, catalog, runtime.nodeProvider());
        long retainDocumentObjectsNanos = elapsed(phaseStarted);

        phaseStarted = System.nanoTime();
        StartedDocument after = new StartedDocument(
                before.key(),
                before.authoredYaml(),
                before.initialBlueId(),
                layout.rootBlueId(),
                Math.addExact(before.currentEpoch(), 1L),
                before.initializationGas(),
                before.initializationEventCount(),
                layout,
                subscriptionUpdate.snapshot(),
                entry.orderKey(),
                deliveredEventIds(before, entry));
        long stagePublicationNanos = elapsed(phaseStarted);
        ProcessTiming timing = new ProcessTiming(
                elapsed(totalStarted),
                reconstructRootNanos,
                prepareDeliveryNanos,
                contractsProcessNanos,
                updateSubscriptionsNanos,
                retainDocumentObjectsNanos,
                stagePublicationNanos);
        return new DocumentTransition(
                before,
                after,
                processed.events(),
                processed.totalGas(),
                timing);
    }

    private TimelineEntry requireAuthoredEntry(TimelineEntry supplied) {
        TimelineEntry checked = Objects.requireNonNull(supplied, "entry");
        TimelineEntry authored = entriesByBlueId.get(checked.blueId());
        if (authored == null
                || !NodeWireForm.get(authored.exactEvent()).equals(
                        NodeWireForm.get(checked.exactEvent()))
                || !authored.orderKey().equals(checked.orderKey())) {
            throw new IllegalArgumentException(
                    "Timeline Entry was not authored by this environment");
        }
        return authored;
    }

    private static List<String> candidateOccurrenceKeys(
            StartedDocument document,
            TimelineEntry entry) {
        return candidateOccurrenceKeys(
                document,
                entry,
                CoordinationTimelineRouteProjection
                        .exactEventSubscriptionKeys(
                                entry.timelineId(), entry.actorId()));
    }

    private static List<String> candidateOccurrenceKeys(
            StartedDocument document,
            TimelineEntry entry,
            List<String> eventKeys) {
        List<CoordinationSubscriptionOccurrence> eligible =
                new ArrayList<>();
        boolean sourceChannelPresent = false;
        for (CoordinationSubscriptionOccurrence occurrence
                : document.subscriptions().occurrences()) {
            if (!sharesKey(eventKeys, occurrence.subscriptionKeys())) {
                continue;
            }
            ExternalOrderKey frontier = occurrence.activationFrontier();
            if (frontier != null
                    && entry.orderKey().compareTo(frontier) <= 0) {
                continue;
            }
            eligible.add(occurrence);
            sourceChannelPresent |= entry.sourceChannel().equals(
                    occurrence.channelKey());
        }
        if (!sourceChannelPresent) {
            return Collections.emptyList();
        }
        eligible.sort(
                EmbeddedOnlyDocumentEnvironment::compareOccurrences);
        List<String> occurrenceKeys = new ArrayList<>(eligible.size());
        for (CoordinationSubscriptionOccurrence occurrence : eligible) {
            occurrenceKeys.add(occurrence.occurrenceKey());
        }
        return Collections.unmodifiableList(occurrenceKeys);
    }

    private static int compareOccurrences(
            CoordinationSubscriptionOccurrence left,
            CoordinationSubscriptionOccurrence right) {
        int compared = Integer.compare(
                JsonPointer.split(right.scopePath()).size(),
                JsonPointer.split(left.scopePath()).size());
        if (compared != 0) {
            return compared;
        }
        compared = ExternalOrderKey.compareTextCodePoints(
                left.scopePath(), right.scopePath());
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(left.order(), right.order());
        if (compared != 0) {
            return compared;
        }
        compared = ExternalOrderKey.compareTextCodePoints(
                left.channelKey(), right.channelKey());
        if (compared != 0) {
            return compared;
        }
        compared = ExternalOrderKey.compareTextCodePoints(
                left.effectiveTypeBlueId(), right.effectiveTypeBlueId());
        return compared != 0
                ? compared
                : ExternalOrderKey.compareTextCodePoints(
                        left.occurrenceKey(), right.occurrenceKey());
    }

    private ExternalOrderKey currentAdmissionFrontier(String key) {
        ExternalOrderKey latest = null;
        for (TimelineEntry entry : entriesByBlueId.values()) {
            if (latest == null || entry.orderKey().compareTo(latest) > 0) {
                latest = entry.orderKey();
            }
        }
        return latest == null ? admissionFrontier(key) : latest;
    }

    private static void requireDeliverable(
            StartedDocument document,
            TimelineEntry entry) {
        if (document.deliveredEventBlueIds().contains(entry.blueId())) {
            throw new IllegalStateException(
                    "Timeline Entry already committed for "
                            + document.key() + ": " + entry.blueId());
        }
        if (entry.orderKey().compareTo(
                document.committedFrontier()) <= 0) {
            throw new IllegalStateException(
                    "Timeline Entry is not newer than the committed frontier "
                            + "for " + document.key());
        }
    }

    private static Set<String> deliveredEventIds(
            StartedDocument before,
            TimelineEntry entry) {
        Set<String> delivered = new LinkedHashSet<>(
                before.deliveredEventBlueIds());
        delivered.add(entry.blueId());
        return Collections.unmodifiableSet(delivered);
    }

    private NodeProvider exactProvider(Node root, Node event) {
        Map<String, Node> invocation = new LinkedHashMap<>();
        // Prefer the two fully reconstructed invocation values. Indexing only
        // their top-level identities is sufficient because all declared
        // Process Embedded scopes are concrete in the reconstructed Root. It
        // also avoids transient hashing/cloning of every ordinary subtree.
        stageExactNode(
                invocation,
                DirectBlueIdCalculator.calculateBlueId(root),
                root);
        stageExactNode(
                invocation,
                DirectBlueIdCalculator.calculateBlueId(event),
                event);
        NodeProvider supplied = blueId -> {
            Node exact = invocation.get(blueId);
            return exact == null
                    ? Collections.emptyList()
                    : Collections.singletonList(exact.clone());
        };
        NodeProvider retained = blueId -> {
            Node exact = exactNodesByBlueId.get(blueId);
            return exact == null
                    ? Collections.emptyList()
                    : Collections.singletonList(exact.clone());
        };
        return new SequentialNodeProvider(
                List.of(supplied, retained, runtime.nodeProvider()));
    }

    private static void stageLayoutNodes(
            Map<String, Node> destination,
            EmbeddedDocumentLayout layout) {
        for (String scopePath : layout.scopePaths()) {
            Node stored = layout.storedDocumentObject(scopePath);
            stageExactNode(
                    destination,
                    DirectBlueIdCalculator.calculateBlueId(stored),
                    stored);
        }
    }

    private static void stageExactNode(
            Map<String, Node> destination,
            String blueId,
            Node exact) {
        String checkedBlueId = requireText(blueId, "blueId");
        Node checked = Objects.requireNonNull(exact, "exact").clone();
        requireIdentity(checkedBlueId, checked, "exact-node staging");
        Node previous = destination.putIfAbsent(checkedBlueId, checked);
        if (previous != null
                && !NodeWireForm.get(previous).equals(
                        NodeWireForm.get(checked))) {
            // Different exact representations may legitimately share an
            // identity when an embedded child is replaced by its pure
            // reference. Prefer the first request-local representation.
            requireIdentity(checkedBlueId, previous,
                    "existing exact-node staging");
        }
    }

    private void requireCompatibleExactNodes(
            Map<String, Node> staged) {
        for (Map.Entry<String, Node> candidate : staged.entrySet()) {
            Node retained = exactNodesByBlueId.get(candidate.getKey());
            if (retained != null) {
                requireIdentity(
                        candidate.getKey(), retained, "retained exact node");
                requireIdentity(candidate.getKey(), candidate.getValue(),
                        "candidate exact node");
            }
        }
    }

    private void publishExactNodes(Map<String, Node> staged) {
        for (Map.Entry<String, Node> candidate : staged.entrySet()) {
            exactNodesByBlueId.putIfAbsent(
                    candidate.getKey(), candidate.getValue().clone());
        }
    }

    private static void requireIdentity(
            String expectedBlueId,
            Node exact,
            String label) {
        String actual = DirectBlueIdCalculator.calculateBlueId(
                Objects.requireNonNull(exact, "exact"));
        if (!expectedBlueId.equals(actual)) {
            throw new IllegalArgumentException(
                    label + " has identity " + actual
                            + ", expected " + expectedBlueId);
        }
    }

    private static ExternalOrderKey admissionFrontier(String key) {
        return ExternalOrderKey.of(List.of(
                BigInteger.ZERO,
                "admission",
                requireText(key, "key")));
    }

    private static boolean sharesKey(
            List<String> first,
            List<String> second) {
        if (first.size() > second.size()) {
            return sharesKey(second, first);
        }
        Set<String> lookup = new LinkedHashSet<>(second);
        for (String key : first) {
            if (lookup.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static String timelineEntryYaml(
            Timeline timeline,
            String previousEntryBlueId,
            long timestampMicros,
            String operation,
            String channel,
            String requestYaml) {
        String previous = previousEntryBlueId == null
                ? ""
                : """
                  prevEntry:
                    blueId: %s
                  """.formatted(previousEntryBlueId);
        String request = "{}".equals(requestYaml)
                ? "  request: {}\n"
                : "  request:\n"
                        + MyOsDemoYaml.indent(requestYaml, 4)
                        + "\n";
        return """
                type: Coordination/Timeline Entry
                timeline:
                  type: MyOS/MyOS Timeline
                  timelineId: %s
                %stimestamp: %d
                actor:
                  type: MyOS/Principal Actor
                  accountId: %s
                message:
                  type: Coordination/Operation Request
                  operation: %s
                  channel: %s
                %s
                """.formatted(
                timeline.timelineId(),
                previous,
                timestampMicros,
                timeline.actorId(),
                operation,
                channel,
                request);
    }

    private static void requireSuccessfulProcess(
            String key,
            DocumentProcessingResult processed) {
        if (processed.status() == ProcessorStatus.SUCCESS
                && processed.commits()) {
            return;
        }
        String diagnostic = processed.diagnostic() == null
                ? ""
                : ": " + processed.diagnostic().message();
        throw new IllegalStateException(
                "PROCESS failed for " + key + " with "
                        + processed.status() + diagnostic);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        documents.clear();
        exactNodesByBlueId.clear();
        timelines.clear();
        entriesByBlueId.clear();
        runtime.close();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Environment is closed");
        }
    }

    private static void requireSuccessfulInitialization(
            String key,
            DocumentProcessingResult initialization) {
        if (initialization.status() == ProcessorStatus.SUCCESS
                && initialization.commits()) {
            return;
        }
        String diagnostic = initialization.diagnostic() == null
                ? ""
                : ": " + initialization.diagnostic().message();
        throw new IllegalStateException(
                "Initialization failed for " + key + " with "
                        + initialization.status() + diagnostic);
    }

    private static long elapsed(long startedNanos) {
        return System.nanoTime() - startedNanos;
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    private static List<Node> immutableNodes(List<Node> supplied) {
        Objects.requireNonNull(supplied, "nodes");
        List<Node> copy = new ArrayList<>(supplied.size());
        for (Node node : supplied) {
            copy.add(Objects.requireNonNull(node, "node").clone());
        }
        return Collections.unmodifiableList(copy);
    }

    private static final class TimelineState {
        private final Timeline timeline;
        private final Set<String> entryBlueIds = new LinkedHashSet<>();
        private String previousEntryBlueId;

        private TimelineState(Timeline timeline) {
            this.timeline = Objects.requireNonNull(timeline, "timeline");
        }
    }

    record Timeline(String timelineId, String actorId) {
        Timeline {
            timelineId = requireText(timelineId, "timelineId");
            actorId = requireText(actorId, "actorId");
        }
    }

    record TimelineEntry(
            Node exactEvent,
            String blueId,
            ExternalOrderKey orderKey,
            String timelineId,
            String actorId,
            String sourceChannel,
            String operation,
            String handlerChannel,
            long timestampMicros) {
        TimelineEntry {
            exactEvent = Objects.requireNonNull(
                    exactEvent, "exactEvent").clone();
            blueId = requireText(blueId, "blueId");
            orderKey = Objects.requireNonNull(orderKey, "orderKey");
            timelineId = requireText(timelineId, "timelineId");
            actorId = requireText(actorId, "actorId");
            sourceChannel = requireText(
                    sourceChannel, "sourceChannel");
            operation = requireText(operation, "operation");
            handlerChannel = requireText(
                    handlerChannel, "handlerChannel");
            if (timestampMicros <= 0L) {
                throw new IllegalArgumentException(
                        "timestampMicros must be positive");
            }
            String actualBlueId = DirectBlueIdCalculator.calculateBlueId(
                    exactEvent);
            if (!blueId.equals(actualBlueId)) {
                throw new IllegalArgumentException(
                        "Timeline Entry identity does not match exact event");
            }
        }

        @Override
        public Node exactEvent() {
            return exactEvent.clone();
        }
    }

    record ProcessTiming(
            long totalNanos,
            long reconstructRootNanos,
            long prepareDeliveryNanos,
            long contractsProcessNanos,
            long updateSubscriptionsNanos,
            long retainDocumentObjectsNanos,
            long stagePublicationNanos) {
        ProcessTiming {
            StartTiming.requireNonNegative(totalNanos, "totalNanos");
            StartTiming.requireNonNegative(
                    reconstructRootNanos, "reconstructRootNanos");
            StartTiming.requireNonNegative(
                    prepareDeliveryNanos, "prepareDeliveryNanos");
            StartTiming.requireNonNegative(
                    contractsProcessNanos, "contractsProcessNanos");
            StartTiming.requireNonNegative(
                    updateSubscriptionsNanos,
                    "updateSubscriptionsNanos");
            StartTiming.requireNonNegative(
                    retainDocumentObjectsNanos,
                    "retainDocumentObjectsNanos");
            StartTiming.requireNonNegative(
                    stagePublicationNanos, "stagePublicationNanos");
            if (attributedNanos() > totalNanos) {
                throw new IllegalArgumentException(
                        "PROCESS phases exceed total time");
            }
        }

        Map<String, Long> detailedPhases() {
            Map<String, Long> phases = new LinkedHashMap<>();
            phases.put("reconstruct semantic Root",
                    reconstructRootNanos);
            phases.put("prepare verified indexed delivery",
                    prepareDeliveryNanos);
            phases.put("frozen Contracts PROCESS",
                    contractsProcessNanos);
            phases.put("project resulting subscriptions and catalog",
                    updateSubscriptionsNanos);
            phases.put("retain Root and Process Embedded documents",
                    retainDocumentObjectsNanos);
            phases.put("stage immutable document revision",
                    stagePublicationNanos);
            phases.put("per-Root PROCESS timing overhead",
                    totalNanos - attributedNanos());
            return Collections.unmodifiableMap(phases);
        }

        long attributedNanos() {
            return StartTiming.attributedNanos(
                    reconstructRootNanos,
                    prepareDeliveryNanos,
                    contractsProcessNanos,
                    updateSubscriptionsNanos,
                    retainDocumentObjectsNanos,
                    stagePublicationNanos);
        }
    }

    record DocumentTransition(
            StartedDocument before,
            StartedDocument after,
            List<Node> events,
            long processingGas,
            ProcessTiming timing) {
        DocumentTransition {
            before = Objects.requireNonNull(before, "before");
            after = Objects.requireNonNull(after, "after");
            events = immutableNodes(events);
            if (processingGas < 0L) {
                throw new IllegalArgumentException(
                        "processingGas must be non-negative");
            }
            timing = Objects.requireNonNull(timing, "timing");
            if (!before.key().equals(after.key())) {
                throw new IllegalArgumentException(
                        "Transition changed document key");
            }
            if (after.currentEpoch()
                    != Math.addExact(before.currentEpoch(), 1L)) {
                throw new IllegalArgumentException(
                        "Transition must advance exactly one epoch");
            }
        }

        @Override
        public List<Node> events() {
            return immutableNodes(events);
        }
    }

    record DispatchTiming(
            long totalNanos,
            long candidateRoutingNanos,
            long rootTransitionNanos,
            long atomicPublicationNanos) {
        DispatchTiming {
            StartTiming.requireNonNegative(totalNanos, "totalNanos");
            StartTiming.requireNonNegative(
                    candidateRoutingNanos, "candidateRoutingNanos");
            StartTiming.requireNonNegative(
                    rootTransitionNanos, "rootTransitionNanos");
            StartTiming.requireNonNegative(
                    atomicPublicationNanos, "atomicPublicationNanos");
            if (attributedNanos() > totalNanos) {
                throw new IllegalArgumentException(
                        "Dispatch phases exceed total time");
            }
        }

        long orchestrationOverheadNanos() {
            return totalNanos - attributedNanos();
        }

        private long attributedNanos() {
            return StartTiming.attributedNanos(
                    candidateRoutingNanos,
                    rootTransitionNanos,
                    atomicPublicationNanos);
        }
    }

    record DispatchResult(
            TimelineEntry entry,
            Map<String, DocumentTransition> transitions,
            DispatchTiming timing) {
        DispatchResult {
            entry = Objects.requireNonNull(entry, "entry");
            transitions = Collections.unmodifiableMap(
                    new LinkedHashMap<>(transitions));
            timing = Objects.requireNonNull(timing, "timing");
            if (transitions.isEmpty()) {
                throw new IllegalArgumentException(
                        "Dispatch must contain at least one transition");
            }
        }

        Set<String> documentKeys() {
            return Collections.unmodifiableSet(
                    new LinkedHashSet<>(transitions.keySet()));
        }

        DocumentTransition require(String key) {
            DocumentTransition transition = transitions.get(
                    Objects.requireNonNull(key, "key"));
            if (transition == null) {
                throw new IllegalArgumentException(
                        "Dispatch did not process document: " + key);
            }
            return transition;
        }
    }

    record StartResult(StartedDocument document, StartTiming timing) {
        StartResult {
            document = Objects.requireNonNull(document, "document");
            timing = Objects.requireNonNull(timing, "timing");
        }
    }

    record StartedDocument(
            String key,
            String authoredYaml,
            String initialBlueId,
            String currentRootBlueId,
            long currentEpoch,
            long initializationGas,
            int initializationEventCount,
            EmbeddedDocumentLayout layout,
            CoordinationSubscriptionSnapshot subscriptions,
            ExternalOrderKey committedFrontier,
            Set<String> deliveredEventBlueIds) {
        StartedDocument {
            key = requireText(key, "key");
            authoredYaml = requireText(authoredYaml, "authoredYaml");
            initialBlueId = requireText(initialBlueId, "initialBlueId");
            currentRootBlueId = requireText(
                    currentRootBlueId, "currentRootBlueId");
            if (currentEpoch < 0L) {
                throw new IllegalArgumentException(
                        "currentEpoch must be non-negative");
            }
            if (initializationGas < 0L) {
                throw new IllegalArgumentException(
                        "initializationGas must be non-negative");
            }
            if (initializationEventCount < 0) {
                throw new IllegalArgumentException(
                        "initializationEventCount must be non-negative");
            }
            layout = Objects.requireNonNull(layout, "layout");
            subscriptions = Objects.requireNonNull(
                    subscriptions, "subscriptions");
            committedFrontier = Objects.requireNonNull(
                    committedFrontier, "committedFrontier");
            deliveredEventBlueIds = Collections.unmodifiableSet(
                    new LinkedHashSet<>(Objects.requireNonNull(
                            deliveredEventBlueIds,
                            "deliveredEventBlueIds")));
            if (!currentRootBlueId.equals(layout.rootBlueId())) {
                throw new IllegalArgumentException(
                        "Layout belongs to another initialized Root");
            }
            if (!currentRootBlueId.equals(subscriptions.rootBlueId())) {
                throw new IllegalArgumentException(
                        "Subscriptions belong to another current Root");
            }
            if (subscriptions.rootRevision()
                    != Math.addExact(currentEpoch, 1L)) {
                throw new IllegalArgumentException(
                        "Subscription revision must equal epoch + 1");
            }
        }

        Node currentRoot() {
            return layout.reconstructRoot();
        }
    }

    record StartTiming(
            long totalNanos,
            long parseSourceNanos,
            long sourceIdentityNanos,
            long preprocessNanos,
            long resolveInitializationSnapshotNanos,
            long contractsInitializationNanos,
            long captureInitializedRootNanos,
            long discoverEmbeddedScopesNanos,
            long retainDocumentObjectsNanos,
            long projectSubscriptionsNanos,
            long publicationNanos) {
        StartTiming {
            requireNonNegative(totalNanos, "totalNanos");
            requireNonNegative(parseSourceNanos, "parseSourceNanos");
            requireNonNegative(sourceIdentityNanos,
                    "sourceIdentityNanos");
            requireNonNegative(preprocessNanos, "preprocessNanos");
            requireNonNegative(resolveInitializationSnapshotNanos,
                    "resolveInitializationSnapshotNanos");
            requireNonNegative(contractsInitializationNanos,
                    "contractsInitializationNanos");
            requireNonNegative(captureInitializedRootNanos,
                    "captureInitializedRootNanos");
            requireNonNegative(discoverEmbeddedScopesNanos,
                    "discoverEmbeddedScopesNanos");
            requireNonNegative(retainDocumentObjectsNanos,
                    "retainDocumentObjectsNanos");
            requireNonNegative(projectSubscriptionsNanos,
                    "projectSubscriptionsNanos");
            requireNonNegative(publicationNanos, "publicationNanos");
            if (attributedNanos(
                    parseSourceNanos,
                    sourceIdentityNanos,
                    preprocessNanos,
                    resolveInitializationSnapshotNanos,
                    contractsInitializationNanos,
                    captureInitializedRootNanos,
                    discoverEmbeddedScopesNanos,
                    retainDocumentObjectsNanos,
                    projectSubscriptionsNanos,
                    publicationNanos) > totalNanos) {
                throw new IllegalArgumentException(
                        "Start phases exceed total time");
            }
        }

        Map<String, Long> detailedPhases() {
            Map<String, Long> phases = new LinkedHashMap<>();
            phases.put("parse authored YAML", parseSourceNanos);
            phases.put("calculate source identity", sourceIdentityNanos);
            phases.put("preprocess authored document", preprocessNanos);
            phases.put("resolve initialization snapshot",
                    resolveInitializationSnapshotNanos);
            phases.put("frozen Contracts initialization",
                    contractsInitializationNanos);
            phases.put("capture initialized exact Root",
                    captureInitializedRootNanos);
            phases.put("discover effective Process Embedded scopes",
                    discoverEmbeddedScopesNanos);
            phases.put("retain Root and embedded document objects",
                    retainDocumentObjectsNanos);
            phases.put("project initial Timeline subscriptions",
                    projectSubscriptionsNanos);
            phases.put("publish document in environment",
                    publicationNanos);
            phases.put("document-start timing overhead",
                    totalNanos - attributedNanos());
            return Collections.unmodifiableMap(phases);
        }

        long attributedNanos() {
            return attributedNanos(
                    parseSourceNanos,
                    sourceIdentityNanos,
                    preprocessNanos,
                    resolveInitializationSnapshotNanos,
                    contractsInitializationNanos,
                    captureInitializedRootNanos,
                    discoverEmbeddedScopesNanos,
                    retainDocumentObjectsNanos,
                    projectSubscriptionsNanos,
                    publicationNanos);
        }

        private static long attributedNanos(long... phases) {
            long total = 0L;
            for (long phase : phases) {
                total = Math.addExact(total, phase);
            }
            return total;
        }

        private static void requireNonNegative(long value, String label) {
            if (value < 0L) {
                throw new IllegalArgumentException(
                        label + " must be non-negative");
            }
        }
    }

    static final class EmbeddedDocumentLayout {
        private final String rootBlueId;
        private final Map<String, StoredDocument> documentsByScope;
        private final List<EmbeddedBoundary> boundaries;

        private EmbeddedDocumentLayout(
                String rootBlueId,
                Map<String, StoredDocument> documentsByScope,
                List<EmbeddedBoundary> boundaries) {
            this.rootBlueId = requireText(rootBlueId, "rootBlueId");
            this.documentsByScope = Collections.unmodifiableMap(
                    new LinkedHashMap<>(documentsByScope));
            this.boundaries = Collections.unmodifiableList(
                    new ArrayList<>(boundaries));
        }

        static EmbeddedDocumentLayout create(
                Node initializedRoot,
                EffectiveFragmentationCatalog catalog,
                NodeProvider provider) {
            Node exactRoot = Objects.requireNonNull(
                    initializedRoot, "initializedRoot").clone();
            EffectiveFragmentationCatalog checkedCatalog =
                    Objects.requireNonNull(catalog, "catalog");
            String rootBlueId = DirectBlueIdCalculator.calculateBlueId(
                    exactRoot);
            if (!rootBlueId.equals(checkedCatalog.rootBlueId())) {
                throw new IllegalArgumentException(
                        "Process Embedded catalog belongs to another Root");
            }

            Map<String, EmbeddedScopePlanView> plans =
                    checkedCatalog.scopePlansByScope();
            if (!plans.containsKey(JsonPointer.ROOT)) {
                throw new IllegalStateException(
                        "Process Embedded catalog has no Root scope");
            }
            Map<String, Node> exactScopes = materializeScopes(
                    exactRoot, plans.keySet(), provider);
            Map<String, StoredDocument> storedByScope =
                    new LinkedHashMap<>();
            List<EmbeddedBoundary> boundaries = new ArrayList<>();

            for (Map.Entry<String, EmbeddedScopePlanView> entry
                    : plans.entrySet()) {
                String scopePath = entry.getKey();
                Node exactScope = requireScope(exactScopes, scopePath);
                String scopeBlueId =
                        DirectBlueIdCalculator.calculateBlueId(exactScope);
                Node retained = exactScope.clone();
                List<String> childScopePaths = new ArrayList<>();
                for (String childPath
                        : entry.getValue().concreteChildPaths()) {
                    Node exactChild = requireScope(exactScopes, childPath);
                    String childBlueId =
                            DirectBlueIdCalculator.calculateBlueId(
                                    exactChild);
                    String relativePath = PointerUtils.relativizePointer(
                            scopePath, childPath);
                    Node authoredChild = NodePathEditor.getOrNull(
                            exactScope, relativePath);
                    if (authoredChild == null) {
                        throw new IllegalStateException(
                                "Embedded child is absent at " + childPath);
                    }
                    boolean splitterCreated =
                            !authoredChild.isReferenceOnly();
                    if (splitterCreated) {
                        NodePathEditor.put(
                                retained,
                                relativePath,
                                new Node().blueId(childBlueId));
                    }
                    childScopePaths.add(childPath);
                    boundaries.add(new EmbeddedBoundary(
                            scopePath,
                            childPath,
                            childBlueId,
                            entry.getValue().originsByConcretePath()
                                    .get(childPath),
                            splitterCreated));
                }
                requireIdentity(scopeBlueId, retained, scopePath);
                StoredDocument stored = new StoredDocument(
                        scopePath,
                        scopeBlueId,
                        retained,
                        childScopePaths);
                storedByScope.put(scopePath, stored);
            }

            for (EmbeddedBoundary boundary : boundaries) {
                if (!storedByScope.containsKey(boundary.childScopePath())) {
                    throw new IllegalStateException(
                            "Catalog boundary has no child document: "
                                    + boundary.childScopePath());
                }
            }
            return new EmbeddedDocumentLayout(
                    rootBlueId,
                    storedByScope,
                    boundaries);
        }

        String layoutProfileIdentity() {
            return LAYOUT_PROFILE_ID;
        }

        String rootBlueId() {
            return rootBlueId;
        }

        Set<String> scopePaths() {
            return Collections.unmodifiableSet(
                    new LinkedHashSet<>(documentsByScope.keySet()));
        }

        int declaredEmbeddedDocumentCount() {
            return documentsByScope.size() - 1;
        }

        int physicalObjectCount() {
            return documentsByScope.size();
        }

        int splitterCreatedEdgeCount() {
            return Math.toIntExact(boundaries.stream()
                    .filter(EmbeddedBoundary::splitterCreated)
                    .count());
        }

        int authoredReferenceEdgeCount() {
            return Math.toIntExact(boundaries.stream()
                    .filter(boundary -> !boundary.splitterCreated())
                    .count());
        }

        Node storedRootObject() {
            return storedDocumentObject(JsonPointer.ROOT);
        }

        Node storedDocumentObject(String scopePath) {
            StoredDocument stored = documentsByScope.get(
                    Objects.requireNonNull(scopePath, "scopePath"));
            if (stored == null) {
                throw new IllegalArgumentException(
                        "Unknown stored document scope: " + scopePath);
            }
            return stored.storedObject();
        }

        Set<String> physicalObjectBlueIds() {
            Set<String> result = new LinkedHashSet<>();
            for (StoredDocument document : documentsByScope.values()) {
                result.add(document.blueId());
            }
            return Collections.unmodifiableSet(result);
        }

        Node reconstructRoot() {
            Node reconstructed = reconstructScope(
                    JsonPointer.ROOT, new LinkedHashSet<>());
            requireIdentity(rootBlueId, reconstructed, JsonPointer.ROOT);
            return reconstructed;
        }

        private Node reconstructScope(
                String scopePath,
                Set<String> active) {
            if (!active.add(scopePath)) {
                throw new IllegalStateException(
                        "Cyclic embedded document layout at " + scopePath);
            }
            try {
                StoredDocument stored = documentsByScope.get(scopePath);
                if (stored == null) {
                    throw new IllegalStateException(
                            "Missing stored document at " + scopePath);
                }
                Node result = stored.storedObject();
                for (EmbeddedBoundary boundary : boundaries) {
                    if (!boundary.parentScopePath().equals(scopePath)
                            || !boundary.splitterCreated()) {
                        continue;
                    }
                    Node child = reconstructScope(
                            boundary.childScopePath(), active);
                    NodePathEditor.put(
                            result,
                            PointerUtils.relativizePointer(
                                    scopePath,
                                    boundary.childScopePath()),
                            child);
                }
                requireIdentity(stored.blueId(), result, scopePath);
                return result;
            } finally {
                active.remove(scopePath);
            }
        }

        private static Map<String, Node> materializeScopes(
                Node exactRoot,
                Collection<String> scopePaths,
                NodeProvider provider) {
            List<String> orderedPaths = new ArrayList<>(scopePaths);
            orderedPaths.sort(Comparator
                    .comparingInt((String path) ->
                            JsonPointer.split(path).size())
                    .thenComparing(Comparator.naturalOrder()));
            Map<String, Node> result = new LinkedHashMap<>();
            result.put(JsonPointer.ROOT, exactRoot.clone());
            for (String scopePath : orderedPaths) {
                if (JsonPointer.ROOT.equals(scopePath)) {
                    continue;
                }
                String ancestorPath = nearestAncestor(
                        result.keySet(), scopePath);
                Node ancestor = requireScope(result, ancestorPath);
                Node selected = NodePathEditor.getOrNull(
                        ancestor,
                        PointerUtils.relativizePointer(
                                ancestorPath, scopePath));
                if (selected == null) {
                    throw new IllegalStateException(
                            "Embedded scope is absent at " + scopePath);
                }
                result.put(
                        scopePath,
                        selected.isReferenceOnly()
                                ? fetchExact(selected, provider, scopePath)
                                : selected.clone());
            }
            return result;
        }

        private static String nearestAncestor(
                Collection<String> candidates,
                String childPath) {
            String selected = null;
            int selectedDepth = -1;
            for (String candidate : candidates) {
                if (candidate.equals(childPath)
                        || !PointerUtils.descendantOrEqual(
                                childPath, candidate)) {
                    continue;
                }
                int depth = JsonPointer.split(candidate).size();
                if (depth > selectedDepth) {
                    selected = candidate;
                    selectedDepth = depth;
                }
            }
            if (selected == null) {
                throw new IllegalStateException(
                        "Embedded scope has no retained ancestor: "
                                + childPath);
            }
            return selected;
        }

        private static Node fetchExact(
                Node reference,
                NodeProvider provider,
                String scopePath) {
            String blueId = requireText(reference.getBlueId(),
                    "embedded reference blueId");
            List<Node> matches = Objects.requireNonNull(
                    provider, "provider").fetchByBlueId(blueId);
            if (matches.size() != 1) {
                throw new IllegalStateException(
                        "Embedded reference at " + scopePath
                                + " resolved to " + matches.size()
                                + " exact objects");
            }
            Node exact = matches.get(0).clone();
            requireIdentity(blueId, exact, scopePath);
            return exact;
        }

        private static Node requireScope(
                Map<String, Node> scopes,
                String path) {
            Node scope = scopes.get(path);
            if (scope == null) {
                throw new IllegalStateException(
                        "Missing exact embedded scope at " + path);
            }
            return scope;
        }

        private static void requireIdentity(
                String expectedBlueId,
                Node representation,
                String scopePath) {
            String actual = DirectBlueIdCalculator.calculateBlueId(
                    representation);
            if (!expectedBlueId.equals(actual)) {
                throw new IllegalStateException(
                        "Embedded-only representation changed identity at "
                                + scopePath + " from " + expectedBlueId
                                + " to " + actual);
            }
        }

    }

    private record StoredDocument(
            String scopePath,
            String blueId,
            Node storedObject,
            List<String> childScopePaths) {
        private StoredDocument {
            scopePath = requireText(scopePath, "scopePath");
            blueId = requireText(blueId, "blueId");
            storedObject = Objects.requireNonNull(
                    storedObject, "storedObject").clone();
            childScopePaths = List.copyOf(childScopePaths);
        }

        @Override
        public Node storedObject() {
            return storedObject.clone();
        }
    }

    private record EmbeddedBoundary(
            String parentScopePath,
            String childScopePath,
            String childBlueId,
            EmbeddedScopePlanView.Origin origin,
            boolean splitterCreated) {
        private EmbeddedBoundary {
            parentScopePath = requireText(
                    parentScopePath, "parentScopePath");
            childScopePath = requireText(
                    childScopePath, "childScopePath");
            childBlueId = requireText(childBlueId, "childBlueId");
            origin = Objects.requireNonNull(origin, "origin");
        }
    }
}
