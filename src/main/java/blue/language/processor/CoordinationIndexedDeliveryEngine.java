package blue.language.processor;

import blue.coordination.processor.CoordinationDeliveryDiagnostic;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.provider.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.BlueIds;
import blue.language.utils.JsonPointer;
import blue.language.processor.util.PointerUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 * Package bridge from Coordination's public indexed façade to the
 * package-private Language subscription-function evaluator.
 *
 * <p>This class deliberately reuses the configured registry, converter,
 * matcher sessions, and contract loader. Immutable snapshot keys establish
 * the complete canonical candidate set; only those candidates are reopened
 * for authoritative source acceptance, target routing, checkpoint, and
 * dependency revalidation.</p>
 */
public final class CoordinationIndexedDeliveryEngine {

    private static final String PLAN_IDENTITY_PREFIX =
            "sha256:";
    private final DocumentProcessor processor;

    /**
     * Captures the configured Language processor whose immutable runtime
     * functions remain authoritative.
     *
     * @param processor configured Coordination processor
     */
    public CoordinationIndexedDeliveryEngine(
            DocumentProcessor processor) {
        this.processor = Objects.requireNonNull(
                processor, "processor");
    }

    /**
     * Returns the exact runtime registry identity against which snapshots and
     * evidence must be bound.
     */
    String runtimeRegistryIdentity() {
        return processor.runtimeRegistryIdentity();
    }

    /**
     * Returns Language's internal occurrence selector for a public
     * scope/raw-key occurrence.
     *
     * <p>The value is exposed only as an adapter for this bridge. Public hosts
     * should persist
     * {@code CoordinationSubscriptionOccurrence.occurrenceKey()} instead.</p>
     */
    public static String languageOccurrenceKey(
            String scopePath,
            String channelKey) {
        if (channelKey == null || channelKey.isEmpty()) {
            throw invalid("Channel key must be non-empty");
        }
        return PointerUtils.normalizeScope(scopePath)
                + ProcessorIdentityConstants
                .SELECTOR_COMPONENT_DELIMITER
                + channelKey;
    }

    /**
     * Evaluates and verifies one exact indexed delivery plan.
     *
     * @param root exact canonical Root content
     * @param event exact canonical event content
     * @param exactProvider exact direct-content provider for event fragments
     * @param rootRevision managed/indexed Root revision
     * @param eventOrderKey exact total-order position
     * @param activeIntervals complete retained active subscription surface
     * @param indexedCandidateOccurrenceKeys exact ordered physical candidates
     * @return immutable verified Language plan and diagnostics
     */
    public Prepared prepare(
            Node root,
            Node event,
            NodeProvider exactProvider,
            long rootRevision,
            ExternalOrderKey eventOrderKey,
            Collection<SubscriptionDelta.Entry> activeIntervals,
            Collection<String> indexedCandidateOccurrenceKeys) {
        Node exactRoot = Objects.requireNonNull(root, "root").clone();
        Node exactEvent = Objects.requireNonNull(event, "event").clone();
        if (rootRevision < 0L) {
            throw invalid("Root revision must be non-negative");
        }
        ExternalOrderKey exactEventOrder = Objects.requireNonNull(
                eventOrderKey, "eventOrderKey");
        List<SubscriptionDelta.Entry> intervals =
                canonicalIntervals(
                        activeIntervals, rootRevision);
        List<String> suppliedCandidates =
                exactCandidateKeys(indexedCandidateOccurrenceKeys);

        ProcessingSnapshotManager snapshotManager =
                Objects.requireNonNull(
                        processor.snapshotManager(),
                        "processor snapshotManager");
        ProcessingSnapshotManager semanticSnapshotManager =
                exactMaterializingSnapshotManager(
                        snapshotManager,
                        Objects.requireNonNull(
                                exactProvider,
                                "exactProvider"));
        List<SubscriptionDelta.Entry> canonicalCandidates =
                canonicalCandidates(
                        intervals,
                        exactEvent,
                        exactEventOrder,
                        semanticSnapshotManager);
        List<String> canonicalCandidateKeys =
                occurrenceKeys(canonicalCandidates);
        if (!canonicalCandidateKeys.equals(
                suppliedCandidates)) {
            throw invalid(candidateMismatch(
                    canonicalCandidateKeys,
                    suppliedCandidates));
        }

        ResolvedSnapshot snapshot =
                canonicalCandidates.isEmpty()
                        ? null
                        : DocumentProcessingRuntime
                        .resolveCanonicalTransient(
                                snapshotManager,
                                FrozenNode.fromNode(
                                        exactRoot),
                                scopePaths(
                                        canonicalCandidates),
                                processor.registry()
                                        .executableBodyFieldsByType());
        List<Candidate> evaluated = new ArrayList<>(
                canonicalCandidates.size());
        for (SubscriptionDelta.Entry interval
                : canonicalCandidates) {
            Candidate candidate = evaluate(
                    snapshot,
                    exactEvent,
                    interval,
                    semanticSnapshotManager);
            if (!candidate.evaluation.preselects()) {
                throw invalid(
                        "Indexed immutable keys selected an occurrence "
                                + "whose registered PRESELECTS function "
                                + "rejected the event at "
                                + interval.scopePath() + "/"
                                + interval.channelKey());
            }
            evaluated.add(candidate);
        }

        ExternalDeliveryPlan.Builder plan =
                ExternalDeliveryPlan.builder()
                        .revisions(rootRevision, rootRevision)
                        .eventOrderKey(exactEventOrder)
                        .availableExactNode(
                                BlueIdCalculator.calculateBlueId(
                                        exactRoot))
                        .availableExactNode(
                                BlueIdCalculator.calculateBlueId(
                                        exactEvent))
                        .requiredExactNode(
                                BlueIdCalculator.calculateBlueId(
                                        exactRoot))
                        .requiredExactNode(
                                BlueIdCalculator.calculateBlueId(
                                        exactEvent))
                        .exactRuntimeState();
        for (SubscriptionDelta.Entry interval : intervals) {
            plan.activeSubscriptionInterval(interval);
        }

        List<CoordinationDeliveryDiagnostic> diagnostics =
                new ArrayList<>();
        for (Candidate candidate : evaluated) {
            plan.delivery(delivery(candidate));
            diagnostics.add(diagnostic(candidate));
        }
        ExternalDeliveryPlan builtPlan = plan.build();
        VerifiedExecutionEvidence evidence =
                bindAndVerify(
                        exactRoot, exactEvent, builtPlan);
        return new Prepared(
                builtPlan,
                evidence,
                canonicalCandidateKeys,
                diagnostics,
                planIdentity(
                        exactRoot,
                        exactEvent,
                        builtPlan,
                        processor
                                .runtimeRegistryIdentity()));
    }

    private List<SubscriptionDelta.Entry> canonicalCandidates(
            List<SubscriptionDelta.Entry> intervals,
            Node event,
            ExternalOrderKey eventOrderKey,
            ProcessingSnapshotManager snapshotManager) {
        Map<String, List<String>> eventKeysByType =
                eventKeysByType(
                        intervals,
                        event,
                        eventOrderKey,
                        snapshotManager);
        List<SubscriptionDelta.Entry> selected =
                new ArrayList<>();
        for (SubscriptionDelta.Entry interval : intervals) {
            if (!activeAt(interval, eventOrderKey)) {
                continue;
            }
            List<String> eventKeys =
                    eventKeysByType.get(
                            interval.effectiveTypeBlueId());
            if (eventKeys == null) {
                throw invalid(
                        "Indexed event-key projection is unavailable for "
                                + interval.effectiveTypeBlueId());
            }
            if (intersects(
                    interval.subscriptionKeys(),
                    eventKeys)) {
                selected.add(interval);
            }
        }
        Collections.sort(
                selected,
                Candidate.CANONICAL_INTERVAL_ORDER);
        return Collections.unmodifiableList(selected);
    }

    private Map<String, List<String>> eventKeysByType(
            List<SubscriptionDelta.Entry> intervals,
            Node event,
            ExternalOrderKey eventOrderKey,
            ProcessingSnapshotManager snapshotManager) {
        Map<String, List<String>> result =
                new LinkedHashMap<>();
        for (SubscriptionDelta.Entry interval : intervals) {
            if (!activeAt(interval, eventOrderKey)
                    || result.containsKey(
                    interval.effectiveTypeBlueId())) {
                continue;
            }
            result.put(
                    interval.effectiveTypeBlueId(),
                    eventKeys(
                            interval.effectiveTypeBlueId(),
                            event,
                            snapshotManager));
        }
        return Collections.unmodifiableMap(result);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<String> eventKeys(
            String effectiveTypeBlueId,
            Node event,
            ProcessingSnapshotManager snapshotManager) {
        ChannelProcessor channelProcessor =
                processor.registry()
                        .lookupChannel(
                                effectiveTypeBlueId)
                        .orElse(null);
        ExternalChannelSubscriptionFunctions functions =
                channelProcessor != null
                        ? channelProcessor
                        .externalSubscriptionFunctions()
                        : null;
        if (functions == null) {
            throw invalid(
                    "Indexed occurrence runtime type does not expose "
                            + "immutable subscription functions: "
                            + effectiveTypeBlueId);
        }
        List<String> first =
                eventKeysOnce(
                        effectiveTypeBlueId,
                        functions,
                        event,
                        snapshotManager);
        List<String> second =
                eventKeysOnce(
                        effectiveTypeBlueId,
                        functions,
                        event,
                        snapshotManager);
        if (!first.equals(second)) {
            throw invalid(
                    "External Channel event-key projection is not "
                            + "deterministic for "
                            + effectiveTypeBlueId);
        }
        return first;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<String> eventKeysOnce(
            String effectiveTypeBlueId,
            ExternalChannelSubscriptionFunctions functions,
            Node event,
            ProcessingSnapshotManager snapshotManager) {
        ExternalChannelFunctionEvaluation.MatcherSession matcher =
                ExternalChannelFunctionEvaluation
                        .verifiedMatcherSessions(
                                snapshotManager)
                        .open();
        RuntimeWorkSession runtimeWorkSession =
                new RuntimeWorkSession(
                        new GasMeter(),
                        RuntimeWorkSession.Mode.ADMISSION);
        try {
            ExternalChannelFunctionContext context =
                    new ExternalChannelFunctionContext(
                            JsonPointer.ROOT,
                            effectiveTypeBlueId,
                            new EventKeyAccess(matcher),
                            runtimeWorkSession);
            return immutableEventKeys(
                    functions.eventKeys(
                            event.clone(),
                            context));
        } finally {
            try {
                matcher.close();
            } finally {
                if (runtimeWorkSession.isOpen()) {
                    runtimeWorkSession.suspend();
                }
            }
        }
    }

    private static List<String> immutableEventKeys(
            Collection<String> supplied) {
        if (supplied == null) {
            throw invalid(
                    "External Channel event-key projection returned null");
        }
        List<String> result =
                new ArrayList<>(supplied.size());
        Set<String> unique = new LinkedHashSet<>();
        for (String key : supplied) {
            if (key == null
                    || key.isEmpty()
                    || !unique.add(key)) {
                throw invalid(
                        "External Channel event keys must be unique "
                                + "non-empty values");
            }
            result.add(key);
        }
        return Collections.unmodifiableList(result);
    }

    private static List<String> occurrenceKeys(
            Collection<SubscriptionDelta.Entry> intervals) {
        List<String> result =
                new ArrayList<>(intervals.size());
        for (SubscriptionDelta.Entry interval : intervals) {
            result.add(interval.occurrenceKey());
        }
        return Collections.unmodifiableList(result);
    }

    private static Set<String> scopePaths(
            Collection<SubscriptionDelta.Entry> intervals) {
        Set<String> paths =
                new LinkedHashSet<>();
        for (SubscriptionDelta.Entry interval
                : intervals) {
            paths.add(interval.scopePath());
        }
        return Collections.unmodifiableSet(paths);
    }

    private static boolean intersects(
            Collection<String> left,
            Collection<String> right) {
        Set<String> rightKeys =
                new LinkedHashSet<>(right);
        for (String value : left) {
            if (rightKeys.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private Candidate evaluate(
            ResolvedSnapshot snapshot,
            Node event,
            SubscriptionDelta.Entry interval,
            ProcessingSnapshotManager snapshotManager) {
        String scopePath = interval.scopePath();
        FrozenNode selected = snapshot.canonicalAt(scopePath);
        FrozenNode effective = snapshot.resolvedAt(scopePath);
        if (selected == null || effective == null) {
            throw invalid(
                    "Indexed subscription scope is absent: "
                            + scopePath);
        }
        ContractBundle bundle =
                processor.contractLoader()
                        .loadExternalClassification(
                                selected,
                                effective,
                                scopePath,
                                interval.channelKey(),
                                true,
                                interval.dependencies(),
                                ProcessingMetricsSink.NOOP,
                                null,
                                null);
        EffectiveContractSnapshot contract =
                bundle.effectiveContractSnapshot(
                        interval.channelKey());
        if (contract == null
                || !EffectiveContractSnapshotConstants
                .Role.EXTERNAL_CHANNEL.equals(
                contract.role())) {
            throw invalid(
                    "Indexed occurrence is absent or not an External "
                            + "Channel at "
                            + scopePath + "/"
                            + interval.channelKey());
        }
        List<String> effectiveKeys =
                interval.dependencies()
                        .wholeSameScopeChannelCatalog()
                        ? interval.dependencies()
                        .channelCatalogContractKeys()
                        : null;
        ExternalChannelFunctionEvaluation evaluation =
                ExternalChannelFunctionEvaluation.evaluate(
                        processor.registry(),
                        processor.contractConverter(),
                        ExternalChannelFunctionEvaluation
                                .verifiedMatcherSessions(
                                        snapshotManager),
                        bundle,
                        contract,
                        event,
                        effectiveKeys);
        verifyRetainedHeader(
                interval, contract, evaluation);
        if (evaluation.accepts()
                && !evaluation.preselects()) {
            throw invalid(
                    "External subscription law violated "
                            + "(ACCEPTS => PRESELECTS) at "
                            + scopePath + "/"
                            + interval.channelKey());
        }
        return new Candidate(interval, contract, evaluation);
    }

    private void verifyRetainedHeader(
            SubscriptionDelta.Entry interval,
            EffectiveContractSnapshot contract,
            ExternalChannelFunctionEvaluation evaluation) {
        if (!interval.scopePath().equals(
                contract.scopePath())
                || !interval.channelKey().equals(
                contract.key())
                || !interval.effectiveTypeBlueId().equals(
                contract.effectiveTypeBlueId())
                || !interval.sourceContributionNodeBlueIds()
                .equals(
                        contract
                                .sourceContributionNodeBlueIds())
                || interval.order() != contract.order()
                || !interval.subscriptionKeys().equals(
                evaluation.channelKeys())
                || !interval.checkpointDomainBlueId().equals(
                evaluation.checkpointDomainBlueId())
                || !interval.dependencies().equals(
                evaluation.dependencies())) {
            throw invalid(
                    "Indexed occurrence header or dependency evidence "
                            + "is stale at "
                            + interval.scopePath() + "/"
                            + interval.channelKey());
        }
    }

    private VerifiedExecutionEvidence bindAndVerify(
            Node root,
            Node event,
            ExternalDeliveryPlan plan) {
        VerifiedExecutionEvidence evidence =
                plan.bind(
                        root,
                        event,
                        processor.runtimeRegistryIdentity());
        /*
         * Candidate completeness was proved above from the immutable,
         * revision-bound index keys. Re-running the generic current-Root
         * verifier here would reopen every unrelated retained occurrence and
         * defeat the indexed boundary. Candidate headers and their declared
         * dependencies have already been revalidated by evaluate(...).
         */
        evidence.revalidateBinding(
                root,
                event,
                processor.runtimeRegistryIdentity());
        return evidence;
    }

    private static ExternalDeliverySnapshot delivery(
            Candidate candidate) {
        SubscriptionDelta.Entry interval =
                candidate.interval;
        ExternalChannelFunctionEvaluation evaluation =
                candidate.evaluation;
        String subject = evaluation.checkpointSubjectBlueId();
        if (subject == null) {
            /*
             * PRESELECTS may intentionally over-approximate ACCEPTS.
             * Language ignores the subject unless ACCEPTS is true, while the
             * immutable snapshot shape still requires a stable exact value.
             */
            subject = evaluation.checkpointDomainBlueId();
        }
        ExternalDeliverySnapshot.Builder builder =
                ExternalDeliverySnapshot.builder(
                                interval.scopePath(),
                                interval.channelKey())
                        .effectiveTypeBlueId(
                                interval.effectiveTypeBlueId())
                        .order(interval.order())
                        .checkpointDomainBlueId(
                                evaluation
                                        .checkpointDomainBlueId())
                        .checkpointSubjectBlueId(subject)
                        .activationStartExclusive(
                                interval
                                        .startAfterExternalOrderKey());
        for (String contribution
                : interval.sourceContributionNodeBlueIds()) {
            builder.sourceContribution(contribution);
        }
        for (String key : evaluation.channelKeys()) {
            builder.subscriptionKey(key);
        }
        return builder.build();
    }

    private static CoordinationDeliveryDiagnostic diagnostic(
            Candidate candidate) {
        ExternalChannelFunctionEvaluation evaluation =
                candidate.evaluation;
        ChannelMemberSnapshot source =
                ChannelMemberSnapshot.from(
                        candidate.contract);
        ChannelMemberSnapshot target =
                evaluation.handlerChannel();
        FrozenNode payload = evaluation.payload();
        return new CoordinationDeliveryDiagnostic(
                candidate.interval.occurrenceKey(),
                candidate.interval.scopePath(),
                candidate.interval.channelKey(),
                candidate.interval.effectiveTypeBlueId(),
                source.headerIdentityBlueId(),
                candidate.interval
                        .sourceContributionNodeBlueIds(),
                evaluation.checkpointDomainBlueId(),
                evaluation.checkpointSubjectBlueId() != null
                        ? evaluation.checkpointSubjectBlueId()
                        : evaluation.checkpointDomainBlueId(),
                payload != null ? payload.blueId() : null,
                evaluation.handlerChannelKey(),
                target != null
                        ? target.effectiveTypeBlueId()
                        : null,
                target != null
                        ? target.headerIdentityBlueId()
                        : null,
                target != null
                        ? target.sourceContributionNodeBlueIds()
                        : Collections.<String>emptyList(),
                evaluation.logicalDeliveryKey(),
                evaluation.dependencies()
                        .deterministicDependencyNodeBlueIds());
    }

    private static List<SubscriptionDelta.Entry>
    canonicalIntervals(
            Collection<SubscriptionDelta.Entry> supplied,
            long rootRevision) {
        Objects.requireNonNull(
                supplied, "activeIntervals");
        List<SubscriptionDelta.Entry> copy =
                new ArrayList<>(supplied.size());
        Set<String> occurrences = new LinkedHashSet<>();
        for (SubscriptionDelta.Entry interval : supplied) {
            SubscriptionDelta.Entry checked =
                    Objects.requireNonNull(
                            interval, "active interval");
            if (!checked.isActiveInterval()
                    || checked.activationRootRevision() == null
                    || checked.activationRootRevision()
                    > rootRevision) {
                throw invalid(
                        "Indexed occurrence is stale or not active at Root "
                                + "revision "
                                + rootRevision + ": "
                                + checked.scopePath() + "/"
                                + checked.channelKey());
            }
            if (!occurrences.add(checked.occurrenceKey())) {
                throw invalid(
                        "Duplicate indexed subscription occurrence: "
                                + checked.scopePath() + "/"
                                + checked.channelKey());
            }
            copy.add(checked);
        }
        Collections.sort(copy, (left, right) -> {
            int compared =
                    ExternalOrderKey.compareTextCodePoints(
                            left.scopePath(),
                            right.scopePath());
            if (compared != 0) {
                return compared;
            }
            compared = Integer.compare(
                    left.order(), right.order());
            if (compared != 0) {
                return compared;
            }
            compared =
                    ExternalOrderKey.compareTextCodePoints(
                            left.channelKey(),
                            right.channelKey());
            return compared != 0
                    ? compared
                    : ExternalOrderKey.compareTextCodePoints(
                            left.effectiveTypeBlueId(),
                            right.effectiveTypeBlueId());
        });
        return Collections.unmodifiableList(copy);
    }

    private static boolean activeAt(
            SubscriptionDelta.Entry interval,
            ExternalOrderKey eventOrderKey) {
        return interval.startAfterExternalOrderKey() == null
                || eventOrderKey.compareTo(
                interval.startAfterExternalOrderKey()) > 0;
    }

    private static List<String> exactCandidateKeys(
            Collection<String> supplied) {
        Objects.requireNonNull(
                supplied, "indexedCandidateOccurrenceKeys");
        List<String> copy =
                new ArrayList<>(supplied.size());
        Set<String> unique = new LinkedHashSet<>();
        for (String key : supplied) {
            if (key == null || key.isEmpty()) {
                throw invalid(
                        "Indexed candidate occurrence keys must be "
                                + "non-empty");
            }
            if (!unique.add(key)) {
                throw invalid(
                        "Duplicate indexed candidate occurrence: "
                                + key);
            }
            copy.add(key);
        }
        return Collections.unmodifiableList(copy);
    }

    private static String candidateMismatch(
            List<String> expected,
            List<String> supplied) {
        Set<String> omitted = new LinkedHashSet<>(expected);
        omitted.removeAll(supplied);
        Set<String> extra = new LinkedHashSet<>(supplied);
        extra.removeAll(expected);
        if (!omitted.isEmpty()) {
            return "Indexed candidate set omits canonical occurrences: "
                    + omitted;
        }
        if (!extra.isEmpty()) {
            return "Indexed candidate set contains illegal extras: "
                    + extra;
        }
        return "Indexed candidate occurrences are in the wrong canonical "
                + "order";
    }

    private static String planIdentity(
            Node root,
            Node event,
            ExternalDeliveryPlan plan,
            String runtimeRegistryIdentity) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            add(digest, "blue.coordination/delivery-plan/1.0");
            add(digest, BlueIdCalculator.calculateBlueId(root));
            add(digest, BlueIdCalculator.calculateBlueId(event));
            add(digest, runtimeRegistryIdentity);
            add(digest, plan.managedRootRevision());
            for (Object component
                    : plan.eventOrderKey().components()) {
                add(digest, component.getClass().getName());
                add(digest, String.valueOf(component));
            }
            for (SubscriptionDelta.Entry interval
                    : plan.activeSubscriptionIntervals()) {
                add(digest, interval.occurrenceKey());
                add(digest, interval.effectiveTypeBlueId());
                add(digest, interval.order());
                addAll(
                        digest,
                        interval
                                .sourceContributionNodeBlueIds());
                addAll(digest, interval.subscriptionKeys());
                add(digest, interval.checkpointDomainBlueId());
                add(digest, interval.activationRootRevision());
                add(digest, String.valueOf(
                        interval.startAfterExternalOrderKey()));
                addAll(
                        digest,
                        interval.dependencies()
                                .deterministicDependencyNodeBlueIds());
            }
            for (ExternalDeliverySnapshot delivery
                    : plan.deliveries()) {
                add(digest, delivery.scopePath());
                add(digest, delivery.channelKey());
                add(digest, delivery.order());
                add(digest, delivery.effectiveTypeBlueId());
                addAll(
                        digest,
                        delivery
                                .sourceContributionNodeBlueIds());
                addAll(digest, delivery.subscriptionKeys());
                add(digest, delivery.checkpointDomainBlueId());
                add(digest, delivery.checkpointSubjectBlueId());
                add(digest, String.valueOf(
                        delivery.activationStartExclusive()));
            }
            addAll(
                    digest,
                    plan.availableExactNodeBlueIds());
            addAll(
                    digest,
                    plan.requiredExactNodeBlueIds());
            return PLAN_IDENTITY_PREFIX + hex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", impossible);
        }
    }

    private static void addAll(
            MessageDigest digest,
            Collection<String> values) {
        add(digest, values.size());
        for (String value : values) {
            add(digest, value);
        }
    }

    private static void add(
            MessageDigest digest,
            long value) {
        digest.update(
                ByteBuffer.allocate(Long.BYTES)
                        .putLong(value)
                        .array());
    }

    private static void add(
            MessageDigest digest,
            Object value) {
        byte[] bytes = String.valueOf(value)
                .getBytes(StandardCharsets.UTF_8);
        digest.update(
                ByteBuffer.allocate(Integer.BYTES)
                        .putInt(bytes.length)
                        .array());
        digest.update(bytes);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result =
                new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(
                    Character.forDigit(
                            (value >>> 4) & 0x0f, 16));
            result.append(
                    Character.forDigit(
                            value & 0x0f, 16));
        }
        return result.toString();
    }

    private static InvalidExecutionEvidenceException invalid(
            String message) {
        return new InvalidExecutionEvidenceException(message);
    }

    private static ProcessingSnapshotManager
    exactMaterializingSnapshotManager(
            ProcessingSnapshotManager delegate,
            NodeProvider exactProvider) {
        return new ProcessingSnapshotManager() {
            @Override
            public ResolvedSnapshot fromDocument(
                    Node document) {
                return delegate.fromDocument(document);
            }

            @Override
            public ResolvedSnapshot fromDocumentTransient(
                    Node document) {
                return delegate.fromDocumentTransient(
                        document);
            }

            @Override
            public FrozenNode materializeVerifiedExactReference(
                    FrozenNode reference) {
                if (!reference.isReferenceOnly()) {
                    return reference;
                }
                String blueId =
                        reference.getReferenceBlueId();
                NodeProviderResult result =
                        Objects.requireNonNull(
                                exactProvider
                                        .fetchResultByBlueId(
                                                blueId),
                                "provider result");
                if (result.outcome()
                        == NodeProviderOutcome.NOT_FOUND) {
                    return delegate
                            .materializeVerifiedExactReference(
                                    reference);
                }
                if (result.outcome()
                        == NodeProviderOutcome.UNAVAILABLE) {
                    throw new ExecutionEvidenceUnavailableException(
                            "Exact indexed event fragment is unavailable "
                                    + "for " + blueId,
                            Collections.singleton(
                                    blueId));
                }
                if (result.outcome()
                        == NodeProviderOutcome.INVALID_EVIDENCE) {
                    throw invalid(
                            "Exact indexed event fragment provider "
                                    + "reported invalid evidence for "
                                    + blueId);
                }
                if (result.nodes().size() != 1) {
                    throw invalid(
                            "Exact indexed event fragment lookup must "
                                    + "return exactly one node for "
                                    + blueId);
                }
                Node canonical =
                        result.nodes().get(0).clone();
                if (canonical.isReferenceOnly()) {
                    throw invalid(
                            "Exact indexed event fragment provider "
                                    + "returned a pure reference for "
                                    + blueId);
                }
                String declared = canonical.getBlueId();
                if (declared != null) {
                    if (!blueId.equals(declared)) {
                        throw invalid(
                                "Indexed event fragment root BlueId "
                                        + declared
                                        + " disagrees with requested "
                                        + blueId);
                    }
                    canonical.blueId(null);
                }
                if (!BlueIds.hasCyclicMemberSeparator(
                        blueId)) {
                    String calculated =
                            BlueIdCalculator
                                    .calculateBlueId(
                                            canonical);
                    if (!blueId.equals(calculated)) {
                        throw invalid(
                                "Indexed event fragment content has "
                                        + "BlueId " + calculated
                                        + " for requested "
                                        + blueId);
                    }
                }
                return FrozenNode.fromNode(
                        canonical);
            }

            @Override
            public ResolvedSnapshot applyPatch(
                    ResolvedSnapshot snapshot,
                    JsonPatch patch) {
                return delegate.applyPatch(
                        snapshot, patch);
            }
        };
    }

    /** Immutable verified result retained behind the public Coordination API. */
    public static final class Prepared {
        private final ExternalDeliveryPlan plan;
        private final VerifiedExecutionEvidence evidence;
        private final List<String> occurrenceOrder;
        private final List<CoordinationDeliveryDiagnostic>
                diagnostics;
        private final String planIdentity;

        private Prepared(
                ExternalDeliveryPlan plan,
                VerifiedExecutionEvidence evidence,
                List<String> occurrenceOrder,
                List<CoordinationDeliveryDiagnostic> diagnostics,
                String planIdentity) {
            this.plan = Objects.requireNonNull(plan, "plan");
            this.evidence = Objects.requireNonNull(
                    evidence, "evidence");
            this.occurrenceOrder =
                    Collections.unmodifiableList(
                            new ArrayList<>(
                                    occurrenceOrder));
            this.diagnostics =
                    Collections.unmodifiableList(
                            new ArrayList<>(diagnostics));
            this.planIdentity =
                    Objects.requireNonNull(
                            planIdentity, "planIdentity");
        }

        public ExternalDeliveryPlan plan() {
            return plan;
        }

        public VerifiedExecutionEvidence evidence() {
            return evidence;
        }

        public List<String> occurrenceOrder() {
            return occurrenceOrder;
        }

        public List<CoordinationDeliveryDiagnostic> diagnostics() {
            return diagnostics;
        }

        public String planIdentity() {
            return planIdentity;
        }
    }

    private static final class EventKeyAccess
            implements ExternalChannelFunctionContext.Access {
        private final ExternalChannelFunctionEvaluation.MatcherSession
                matcher;

        private EventKeyAccess(
                ExternalChannelFunctionEvaluation.MatcherSession
                        matcher) {
            this.matcher =
                    Objects.requireNonNull(
                            matcher, "matcher");
        }

        @Override
        public ExternalChannelMemberSnapshot member(
                String key) {
            throw occurrenceDependentProjection();
        }

        @Override
        public List<ExternalChannelMemberSnapshot> members() {
            throw occurrenceDependentProjection();
        }

        @Override
        public List<ExternalChannelMemberSnapshot>
        membersByEffectiveType(
                String effectiveTypeBlueId) {
            throw occurrenceDependentProjection();
        }

        @Override
        public List<ExternalChannelMemberSnapshot>
        membersAssignableToType(
                String baseTypeBlueId) {
            throw occurrenceDependentProjection();
        }

        @Override
        public ChannelMemberSnapshot
        dependOnSameScopeChannel(
                String key) {
            throw occurrenceDependentProjection();
        }

        @Override
        public void dependOnSameScopeChannelCatalog() {
            throw occurrenceDependentProjection();
        }

        @Override
        public ChannelLookupResult lookupChannel(
                String key) {
            throw occurrenceDependentProjection();
        }

        @Override
        public boolean matchesPattern(
                FrozenNode candidate,
                FrozenNode pattern) {
            return matcher.matches(
                    candidate, pattern);
        }

        @Override
        public FrozenNode materializeExactReference(
                FrozenNode reference) {
            return matcher.materializeExactReference(
                    reference);
        }

        private static InvalidExecutionEvidenceException
        occurrenceDependentProjection() {
            return invalid(
                    "Indexed event-key projection attempted to open "
                            + "an occurrence-dependent scope or header");
        }
    }

    private static final class Candidate {
        private static final Comparator<
                SubscriptionDelta.Entry>
        CANONICAL_INTERVAL_ORDER =
                (left, right) -> {
                    int compared = Integer.compare(
                            JsonPointer.split(
                                    right.scopePath())
                                    .size(),
                            JsonPointer.split(
                                    left.scopePath())
                                    .size());
                    if (compared != 0) {
                        return compared;
                    }
                    compared =
                            ExternalOrderKey.compareTextCodePoints(
                                    left.scopePath(),
                                    right.scopePath());
                    if (compared != 0) {
                        return compared;
                    }
                    compared = Integer.compare(
                            left.order(),
                            right.order());
                    if (compared != 0) {
                        return compared;
                    }
                    compared =
                            ExternalOrderKey.compareTextCodePoints(
                                    left.channelKey(),
                                    right.channelKey());
                    return compared != 0
                            ? compared
                            : ExternalOrderKey
                            .compareTextCodePoints(
                                    left.effectiveTypeBlueId(),
                                    right.effectiveTypeBlueId());
                };

        private final SubscriptionDelta.Entry interval;
        private final EffectiveContractSnapshot contract;
        private final ExternalChannelFunctionEvaluation evaluation;

        private Candidate(
                SubscriptionDelta.Entry interval,
                EffectiveContractSnapshot contract,
                ExternalChannelFunctionEvaluation evaluation) {
            this.interval = interval;
            this.contract = contract;
            this.evaluation = evaluation;
        }
    }
}
