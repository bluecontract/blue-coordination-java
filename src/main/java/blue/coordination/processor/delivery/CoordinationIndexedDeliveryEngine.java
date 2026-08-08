package blue.coordination.processor.delivery;

import blue.coordination.engine.CoordinationProcessingEngine
        .AdmittedPlanningAuthority;
import blue.coordination.processor.CoordinationSubscriptionMerkleIndex;
import blue.language.model.Node;
import blue.language.processor.BlueContracts;
import blue.language.processor.ExternalChannelDependencySnapshot;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalDeliverySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ExternalSubscriptionOccurrenceKey;
import blue.language.processor.IndexedDeliveryDiagnostic;
import blue.language.processor.IndexedDeliveryPreparation;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.PlatformProcessingResult;
import blue.language.processor.PlatformProcessInvocation;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.VerifiedExecutionEvidence;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.PointerUtils;
import blue.language.provider.NodeProvider;
import blue.language.identity.DirectBlueIdCalculator;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Public-Contracts boundary for Coordination indexed-delivery evaluation.
 *
 * <p>Coordination owns persistence keys, resource closure, and host quotas.
 * Contracts remains authoritative for the active-surface proof, event-key
 * intersection, PRESELECTS/ACCEPTS evaluation, checkpoint identity, routing,
 * deterministic replay, gas admission, and exact candidate verification.</p>
 */
public final class CoordinationIndexedDeliveryEngine {
    private static final char OCCURRENCE_SEPARATOR = '\u001f';
    private static final String PLAN_IDENTITY_PREFIX = "sha256:";

    private final BlueContracts contracts;
    private final AdmittedPlanningAuthority admittedPlanningAuthority;

    /**
     * Creates an indexed boundary borrowing one live Contracts generation.
     *
     * @param contracts configured Contracts service
     */
    public CoordinationIndexedDeliveryEngine(BlueContracts contracts) {
        this(contracts, null);
    }

    private CoordinationIndexedDeliveryEngine(
            BlueContracts contracts,
            AdmittedPlanningAuthority admittedPlanningAuthority) {
        this.contracts = Objects.requireNonNull(contracts, "contracts");
        this.admittedPlanningAuthority = admittedPlanningAuthority;
    }

    /**
     * Creates the admitted-value boundary for one authority-bound Contracts
     * generation.
     */
    public static CoordinationIndexedDeliveryEngine forAdmittedPlanning(
            BlueContracts contracts,
            AdmittedPlanningAuthority admittedPlanningAuthority) {
        BlueContracts exactContracts = Objects.requireNonNull(
                contracts, "contracts");
        AdmittedPlanningAuthority authority = Objects.requireNonNull(
                admittedPlanningAuthority, "admittedPlanningAuthority");
        authority.requireContractsDomain(exactContracts);
        return new CoordinationIndexedDeliveryEngine(
                exactContracts, authority);
    }

    /**
     * Returns the registry identity used by {@link BlueContracts}' public
     * composition root.
     */
    public String runtimeRegistryIdentity() {
        return RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY;
    }

    /** Stable internal adapter key for one Language occurrence. */
    public static String languageOccurrenceKey(
            String scopePath,
            String channelKey) {
        if (channelKey == null || channelKey.isEmpty()) {
            throw new IllegalArgumentException(
                    "Channel key must be non-empty");
        }
        return PointerUtils.normalizeScope(scopePath)
                + OCCURRENCE_SEPARATOR
                + channelKey;
    }

    /**
     * Evaluates and verifies one complete indexed Root/event surface through
     * {@link BlueContracts#indexedDeliveryEvaluator()}.
     *
     * <p>The provider parameter remains an explicit host binding: the caller
     * has already used it to acquire the exact Root and event. Contracts uses
     * the provider frozen into its runtime generation for any exact reference
     * materialization reached during semantic evaluation.</p>
     */
    public Prepared prepare(
            Node root,
            Node event,
            NodeProvider exactProvider,
            long rootRevision,
            ExternalOrderKey eventOrderKey,
            Collection<? extends CoordinationSubscriptionOccurrenceView>
                    activeOccurrences,
            Collection<String> indexedCandidateOccurrenceKeys) {
        return prepare(
                root,
                event,
                exactProvider,
                rootRevision,
                eventOrderKey,
                IndexedActiveSurface.from(activeOccurrences),
                indexedCandidateOccurrenceKeys);
    }

    /**
     * Evaluates a snapshot-preindexed active surface without rebuilding its
     * complete occurrence maps and interval projection for every event.
     */
    public Prepared prepare(
            Node root,
            Node event,
            NodeProvider exactProvider,
            long rootRevision,
            ExternalOrderKey eventOrderKey,
            IndexedActiveSurface activeSurface,
            Collection<String> indexedCandidateOccurrenceKeys) {
        /* The planner owns these invocation-local exact Nodes and traverses
         * them read-only. IndexedDeliveryEvaluator takes its own defensive
         * copies at the public Contracts boundary, so cloning both complete
         * graphs here would provide no additional isolation. */
        Node exactRoot = Objects.requireNonNull(root, "root");
        Node exactEvent = Objects.requireNonNull(event, "event");
        return prepareInternal(
                null,
                exactRoot,
                null,
                exactEvent,
                exactProvider,
                rootRevision,
                eventOrderKey,
                activeSurface,
                indexedCandidateOccurrenceKeys,
                false);
    }

    /**
     * Uses identities proved at the engine admission boundary instead of
     * recalculating full Root/event BlueIds while constructing evidence.
     */
    public Prepared prepareAdmitted(
            AdmittedPlanningAuthority admittedAuthority,
            String rootBlueId,
            Node root,
            String eventBlueId,
            Node event,
            NodeProvider exactProvider,
            long rootRevision,
            ExternalOrderKey eventOrderKey,
            IndexedActiveSurface activeSurface,
            Collection<String> indexedCandidateOccurrenceKeys) {
        if (admittedPlanningAuthority == null
                || admittedPlanningAuthority != Objects.requireNonNull(
                        admittedAuthority, "admittedAuthority")) {
            throw invalid("Admitted planning capability is invalid");
        }
        return prepareInternal(
                requireText(rootBlueId, "rootBlueId"),
                Objects.requireNonNull(root, "root"),
                requireText(eventBlueId, "eventBlueId"),
                Objects.requireNonNull(event, "event"),
                exactProvider,
                rootRevision,
                eventOrderKey,
                activeSurface,
                indexedCandidateOccurrenceKeys,
                true);
    }

    private Prepared prepareInternal(
            String admittedRootBlueId,
            Node exactRoot,
            String admittedEventBlueId,
            Node exactEvent,
            NodeProvider exactProvider,
            long rootRevision,
            ExternalOrderKey eventOrderKey,
            IndexedActiveSurface activeSurface,
            Collection<String> indexedCandidateOccurrenceKeys,
            boolean admitted) {
        Objects.requireNonNull(exactProvider, "exactProvider");
        if (rootRevision < 0L) {
            throw new IllegalArgumentException(
                    "Root revision must be non-negative");
        }
        ExternalOrderKey exactOrder = Objects.requireNonNull(
                eventOrderKey, "eventOrderKey");
        IndexedActiveSurface surface = Objects.requireNonNull(
                activeSurface, "activeSurface");
        List<ExternalSubscriptionOccurrenceKey> candidateKeys =
                surface.candidateKeys(indexedCandidateOccurrenceKeys);

        final IndexedDeliveryPreparation indexed;
        try {
            indexed = indexedDeliveryEvaluator().prepare(
                    exactRoot,
                    exactEvent,
                    rootRevision,
                    exactOrder,
                    surface.intervals,
                    candidateKeys);
        } catch (InvalidExecutionEvidenceException invalidCandidates) {
            throw classifiedCandidateFailure(
                    invalidCandidates,
                    exactRoot,
                    exactEvent,
                    rootRevision,
                    exactOrder,
                    surface,
                    candidateKeys);
        }
        ExternalDeliveryPlan plan = indexed.deliveryPlan();
        Map<ExternalSubscriptionOccurrenceKey, IndexedDeliveryDiagnostic>
                diagnosticByOccurrence = new LinkedHashMap<>();
        for (IndexedDeliveryDiagnostic diagnostic
                : indexed.diagnostics()) {
            diagnosticByOccurrence.put(
                    diagnostic.occurrenceKey(), diagnostic);
        }

        List<String> occurrenceOrder = new ArrayList<>();
        List<CoordinationDeliveryDiagnosticView> diagnostics =
                new ArrayList<>();
        for (ExternalDeliverySnapshot delivery : plan.deliveries()) {
            ExternalSubscriptionOccurrenceKey key =
                    ExternalSubscriptionOccurrenceKey.of(
                            delivery.scopePath(),
                            delivery.channelKey());
            CoordinationSubscriptionOccurrenceView occurrence =
                    surface.occurrence(key);
            IndexedDeliveryDiagnostic diagnostic =
                    diagnosticByOccurrence.get(key);
            if (occurrence == null || diagnostic == null
                    || !diagnostic.preselects()) {
                throw invalid(
                        "Contracts returned a delivery outside the retained "
                                + "preselected occurrence surface at " + key);
            }
            occurrenceOrder.add(languageOccurrenceKey(
                    key.scopePath(), key.channelKey()));
            diagnostics.add(publicDiagnostic(
                    occurrence, diagnostic));
        }

        VerifiedExecutionEvidence evidence = admitted
                ? evidence(admittedRootBlueId, admittedEventBlueId, plan)
                : evidence(exactRoot, exactEvent, plan);
        return new Prepared(
                plan,
                evidence,
                occurrenceOrder,
                diagnostics,
                planIdentity(
                        evidence.rootBlueId(),
                        evidence.eventBlueId(),
                        plan,
                        runtimeRegistryIdentity()));
    }

    /**
     * Prepares the verified result for one atomic host commit through the
     * public Contracts platform-commit boundary.
     */
    public PlatformProcessingResult processForPlatformCommit(
            Node root,
            Node event,
            Prepared prepared) {
        Objects.requireNonNull(prepared, "prepared");
        return processForPlatformCommit(
                root, event, prepared.evidence());
    }

    /** Processes already prepared immutable evidence for an atomic commit. */
    public PlatformProcessingResult processForPlatformCommit(
            Node root,
            Node event,
            VerifiedExecutionEvidence evidence) {
        Node exactRoot = Objects.requireNonNull(root, "root");
        Node exactEvent = Objects.requireNonNull(event, "event");
        return contracts.processForPlatformCommit(
                exactRoot,
                exactEvent,
                Objects.requireNonNull(evidence, "evidence"));
    }

    /**
     * Processes the evaluator-bound plan through one strict request-local
     * provider. Unlike reconstructed public evidence, the plan retains the
     * frozen Contracts generation identity established by the indexed
     * evaluator itself.
     */
    public PlatformProcessingResult processForPlatformCommit(
            Node root,
            Node event,
            ExternalDeliveryPlan plan,
            NodeProvider exactProvider) {
        PlatformProcessInvocation invocation =
                PlatformProcessInvocation.builder()
                        .deliveryPlan(Objects.requireNonNull(
                                plan, "plan"))
                        .nodeProvider(Objects.requireNonNull(
                                exactProvider, "exactProvider"))
                        .build();
        return contracts.processForPlatformCommit(
                Objects.requireNonNull(root, "root"),
                Objects.requireNonNull(event, "event"),
                invocation);
    }

    private blue.language.processor.IndexedDeliveryEvaluator
    indexedDeliveryEvaluator() {
        return contracts.indexedDeliveryEvaluator();
    }

    /*
     * Frozen Contracts deliberately reports one generic mismatch for an
     * inexact physical candidate vector. Keep the successful path single-pass,
     * but classify that already-failed request through the public compatibility
     * deriver so Coordination's persistence boundary exposes a stable and
     * actionable omission/extra/order diagnostic. If independent derivation
     * cannot establish the distinction, preserve the authoritative failure.
     */
    private InvalidExecutionEvidenceException classifiedCandidateFailure(
            InvalidExecutionEvidenceException original,
            Node root,
            Node event,
            long rootRevision,
            ExternalOrderKey eventOrderKey,
            IndexedActiveSurface surface,
            List<ExternalSubscriptionOccurrenceKey> supplied) {
        String message = original.getMessage();
        if (message == null
                || !message.contains(
                        "candidate occurrence list does not match")) {
            return original;
        }
        final ExternalDeliveryPlan expectedPlan;
        try {
            expectedPlan = contracts.currentRootDeliveryPlanDeriver(
                            rootRevision,
                            eventOrderKey,
                            surface.intervals)
                    .derive(root, event);
        } catch (RuntimeException unavailableClassification) {
            return original;
        }
        List<ExternalSubscriptionOccurrenceKey> expected =
                new ArrayList<ExternalSubscriptionOccurrenceKey>();
        for (ExternalDeliverySnapshot delivery
                : expectedPlan.deliveries()) {
            expected.add(ExternalSubscriptionOccurrenceKey.of(
                    delivery.scopePath(), delivery.channelKey()));
        }
        if (expected.equals(supplied)) {
            return original;
        }
        Set<ExternalSubscriptionOccurrenceKey> expectedSet =
                new LinkedHashSet<ExternalSubscriptionOccurrenceKey>(
                        expected);
        Set<ExternalSubscriptionOccurrenceKey> suppliedSet =
                new LinkedHashSet<ExternalSubscriptionOccurrenceKey>(
                        supplied);
        Set<ExternalSubscriptionOccurrenceKey> omitted =
                new LinkedHashSet<ExternalSubscriptionOccurrenceKey>(
                        expectedSet);
        omitted.removeAll(suppliedSet);
        Set<ExternalSubscriptionOccurrenceKey> extras =
                new LinkedHashSet<ExternalSubscriptionOccurrenceKey>(
                        suppliedSet);
        extras.removeAll(expectedSet);
        if (omitted.isEmpty() && extras.isEmpty()) {
            return invalid(
                    "Indexed candidates are in the wrong canonical order");
        }
        if (!omitted.isEmpty() && extras.isEmpty()) {
            return invalid(
                    "Indexed candidate list omits canonical occurrences: "
                            + omitted);
        }
        if (omitted.isEmpty()) {
            return invalid(
                    "Indexed candidate list contains illegal extras: "
                            + extras);
        }
        return invalid(
                "Indexed candidate list both omits canonical occurrences "
                        + omitted + " and contains illegal extras " + extras);
    }

    private static CoordinationDeliveryDiagnosticView publicDiagnostic(
            CoordinationSubscriptionOccurrenceView occurrence,
            IndexedDeliveryDiagnostic diagnostic) {
        String targetKey = diagnostic.handlerChannelKey();
        ExternalChannelDependencySnapshot.ChannelEntry target =
                targetKey == null
                        ? null
                        : targetChannel(
                                diagnostic.dependencies(), targetKey);
        if (targetKey != null && target == null) {
            throw invalid(
                    "Contracts routed to a Channel absent from its exact "
                            + "dependency evidence at "
                            + occurrence.scopePath() + "/" + targetKey);
        }
        return new ImmutableCoordinationDeliveryDiagnostic(
                languageOccurrenceKey(
                        occurrence.scopePath(),
                        occurrence.channelKey()),
                occurrence.scopePath(),
                occurrence.channelKey(),
                occurrence.effectiveTypeBlueId(),
                occurrence.headerIdentityBlueId(),
                occurrence.sourceContributionNodeBlueIds(),
                diagnostic.checkpointDomainBlueId(),
                diagnostic.checkpointSubjectBlueId(),
                diagnostic.payloadBlueId(),
                targetKey,
                target == null ? null : target.effectiveTypeBlueId(),
                target == null ? null : target.headerIdentityBlueId(),
                target == null
                        ? Collections.<String>emptyList()
                        : target.sourceContributionNodeBlueIds(),
                diagnostic.logicalDeliveryKey(),
                diagnostic.dependencies()
                        .deterministicDependencyNodeBlueIds());
    }

    private static ExternalChannelDependencySnapshot.ChannelEntry
    targetChannel(
            ExternalChannelDependencySnapshot dependencies,
            String targetKey) {
        for (ExternalChannelDependencySnapshot.ChannelEntry entry
                : dependencies.channelEntries()) {
            if (targetKey.equals(entry.channelKey())) {
                return entry;
            }
        }
        return null;
    }

    private static VerifiedExecutionEvidence evidence(
            Node root,
            Node event,
            ExternalDeliveryPlan plan) {
        String rootBlueId = DirectBlueIdCalculator.calculateBlueId(root);
        String eventBlueId = DirectBlueIdCalculator.calculateBlueId(event);
        return evidence(rootBlueId, eventBlueId, plan);
    }

    private static VerifiedExecutionEvidence evidence(
            String rootBlueId,
            String eventBlueId,
            ExternalDeliveryPlan plan) {
        VerifiedExecutionEvidence.Builder builder =
                VerifiedExecutionEvidence.builder(
                                rootBlueId, eventBlueId)
                        .revisions(
                                plan.managedRootRevision(),
                                plan.indexedRootRevision())
                        .runtimeRegistryIdentity(
                                RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY)
                        .eventOrderKey(plan.eventOrderKey())
                        .activeSubscriptionIntervals(
                                plan.activeSubscriptionIntervals());
        for (ExternalDeliverySnapshot delivery : plan.deliveries()) {
            builder.delivery(delivery);
        }
        for (String available : plan.availableExactNodeBlueIds()) {
            builder.availableExactNode(available);
        }
        for (String required : plan.requiredExactNodeBlueIds()) {
            builder.requiredExactNode(required);
        }
        return builder.build();
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw invalid(label + " must be non-empty");
        }
        return value;
    }

    private static String planIdentity(
            String rootBlueId,
            String eventBlueId,
            ExternalDeliveryPlan plan,
            String runtimeRegistryIdentity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, "blue.coordination/delivery-plan/1.0");
            add(digest, rootBlueId);
            add(digest, eventBlueId);
            add(digest, runtimeRegistryIdentity);
            add(digest, plan.managedRootRevision());
            for (Object component : plan.eventOrderKey().components()) {
                add(digest, component.getClass().getName());
                add(digest, String.valueOf(component));
            }
            for (SubscriptionDelta.Entry interval
                    : plan.activeSubscriptionIntervals()) {
                add(digest, interval.scopePath());
                add(digest, interval.channelKey());
                add(digest, interval.effectiveTypeBlueId());
                add(digest, interval.order());
                addAll(digest, interval.sourceContributionNodeBlueIds());
                addAll(digest, interval.subscriptionKeys());
                add(digest, interval.checkpointDomainBlueId());
                add(digest, interval.activationRootRevision());
                add(digest, String.valueOf(
                        interval.startAfterExternalOrderKey()));
                addAll(digest, interval.dependencies()
                        .deterministicDependencyNodeBlueIds());
            }
            for (ExternalDeliverySnapshot delivery : plan.deliveries()) {
                add(digest, delivery.scopePath());
                add(digest, delivery.channelKey());
                add(digest, delivery.order());
                add(digest, delivery.effectiveTypeBlueId());
                addAll(digest, delivery.sourceContributionNodeBlueIds());
                addAll(digest, delivery.subscriptionKeys());
                add(digest, delivery.checkpointDomainBlueId());
                add(digest, delivery.checkpointSubjectBlueId());
                add(digest, String.valueOf(
                        delivery.activationStartExclusive()));
            }
            addAll(digest, plan.availableExactNodeBlueIds());
            addAll(digest, plan.requiredExactNodeBlueIds());
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

    private static void add(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES)
                .putLong(value).array());
    }

    private static void add(MessageDigest digest, Object value) {
        byte[] bytes = String.valueOf(value)
                .getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(value & 0x0f, 16));
        }
        return result.toString();
    }

    private static InvalidExecutionEvidenceException invalid(
            String message) {
        return new InvalidExecutionEvidenceException(message);
    }

    /**
     * Immutable exact indexes and Language interval values for one active
     * subscription snapshot.
     *
     * <p>Snapshot construction creates this value once. Trusted event plans
     * then validate only their selected candidate keys instead of rebuilding
     * maps by scanning every active occurrence.</p>
     */
    public static final class IndexedActiveSurface {
        private final Map<ExternalSubscriptionOccurrenceKey,
                CoordinationSubscriptionOccurrenceView> occurrences;
        private final Map<String, ExternalSubscriptionOccurrenceKey>
                occurrenceKeysByPublicKey;
        private final List<SubscriptionDelta.Entry> intervals;
        private final CoordinationSubscriptionMerkleIndex
                .PersistentOccurrenceList persistentOccurrences;

        private IndexedActiveSurface(
                Map<ExternalSubscriptionOccurrenceKey,
                        CoordinationSubscriptionOccurrenceView> occurrences,
                Map<String, ExternalSubscriptionOccurrenceKey>
                        occurrenceKeysByPublicKey,
                List<SubscriptionDelta.Entry> intervals) {
            this.occurrences = Collections.unmodifiableMap(
                    new LinkedHashMap<
                            ExternalSubscriptionOccurrenceKey,
                            CoordinationSubscriptionOccurrenceView>(
                            occurrences));
            this.occurrenceKeysByPublicKey = Collections.unmodifiableMap(
                    new LinkedHashMap<String,
                            ExternalSubscriptionOccurrenceKey>(
                            occurrenceKeysByPublicKey));
            this.intervals = Collections.unmodifiableList(
                    new ArrayList<SubscriptionDelta.Entry>(intervals));
            this.persistentOccurrences = null;
        }

        private IndexedActiveSurface(
                CoordinationSubscriptionMerkleIndex
                        .PersistentOccurrenceList occurrences) {
            this.occurrences = Collections.emptyMap();
            this.occurrenceKeysByPublicKey = Collections.emptyMap();
            this.persistentOccurrences = Objects.requireNonNull(
                    occurrences, "occurrences");
            this.intervals = Collections.unmodifiableList(
                    new AbstractList<SubscriptionDelta.Entry>() {
                        @Override
                        public SubscriptionDelta.Entry get(int index) {
                            return IndexedActiveSurface.this
                                    .persistentOccurrences.get(index)
                                    .toSubscriptionDeltaEntry();
                        }

                        @Override
                        public int size() {
                            return IndexedActiveSurface.this
                                    .persistentOccurrences.size();
                        }
                    });
        }

        /** Builds and verifies exact active-surface indexes once. */
        public static IndexedActiveSurface from(
                Collection<? extends CoordinationSubscriptionOccurrenceView>
                        supplied) {
            Objects.requireNonNull(supplied, "activeOccurrences");
            if (supplied instanceof CoordinationSubscriptionMerkleIndex
                    .PersistentOccurrenceList) {
                return new IndexedActiveSurface(
                        (CoordinationSubscriptionMerkleIndex
                                .PersistentOccurrenceList) supplied);
            }
            Map<ExternalSubscriptionOccurrenceKey,
                    CoordinationSubscriptionOccurrenceView> occurrences =
                    new LinkedHashMap<>();
            Map<String, ExternalSubscriptionOccurrenceKey> byPublicKey =
                    new LinkedHashMap<>();
            List<SubscriptionDelta.Entry> intervals = new ArrayList<>();
            for (CoordinationSubscriptionOccurrenceView occurrence
                    : supplied) {
                CoordinationSubscriptionOccurrenceView exact =
                        Objects.requireNonNull(
                                occurrence, "active occurrence");
                ExternalSubscriptionOccurrenceKey key =
                        ExternalSubscriptionOccurrenceKey.of(
                                exact.scopePath(), exact.channelKey());
                if (occurrences.put(key, exact) != null) {
                    throw invalid(
                            "Duplicate retained subscription occurrence at "
                                    + key);
                }
                if (byPublicKey.put(
                        exact.occurrenceKey(), key) != null) {
                    throw invalid(
                            "Duplicate retained public occurrence key: "
                                    + exact.occurrenceKey());
                }
                intervals.add(exact.toSubscriptionDeltaEntry());
            }
            return new IndexedActiveSurface(
                    occurrences, byPublicKey, intervals);
        }

        private List<ExternalSubscriptionOccurrenceKey> candidateKeys(
                Collection<String> supplied) {
            Objects.requireNonNull(
                    supplied, "indexedCandidateOccurrenceKeys");
            List<ExternalSubscriptionOccurrenceKey> result =
                    new ArrayList<>(supplied.size());
            Set<String> unique = new LinkedHashSet<>();
            for (String publicKey : supplied) {
                if (publicKey == null || publicKey.isEmpty()) {
                    throw invalid(
                            "Indexed candidate occurrence keys must be "
                                    + "non-empty");
                }
                if (!unique.add(publicKey)) {
                    throw invalid(
                            "Duplicate indexed candidate occurrence: "
                                    + publicKey);
                }
                CoordinationSubscriptionOccurrenceView occurrence =
                        occurrence(publicKey);
                ExternalSubscriptionOccurrenceKey key = occurrence == null
                        ? null
                        : ExternalSubscriptionOccurrenceKey.of(
                                occurrence.scopePath(),
                                occurrence.channelKey());
                if (key == null) {
                    throw invalid(
                            "Indexed candidate is absent or stale in the "
                                    + "active surface: " + publicKey);
                }
                result.add(key);
            }
            return Collections.unmodifiableList(result);
        }

        private CoordinationSubscriptionOccurrenceView occurrence(
                String publicKey) {
            return persistentOccurrences != null
                    ? persistentOccurrences.occurrence(publicKey)
                    : occurrenceFor(occurrenceKeysByPublicKey.get(publicKey));
        }

        private CoordinationSubscriptionOccurrenceView occurrence(
                ExternalSubscriptionOccurrenceKey key) {
            return persistentOccurrences != null
                    ? persistentOccurrences.occurrence(key)
                    : occurrenceFor(key);
        }

        private CoordinationSubscriptionOccurrenceView occurrenceFor(
                ExternalSubscriptionOccurrenceKey key) {
            return key == null ? null : occurrences.get(key);
        }
    }

    /** Immutable verified result retained by the public planner API. */
    public static final class Prepared {
        private final ExternalDeliveryPlan plan;
        private final VerifiedExecutionEvidence evidence;
        private final List<String> occurrenceOrder;
        private final List<CoordinationDeliveryDiagnosticView> diagnostics;
        private final String planIdentity;

        public Prepared(
                ExternalDeliveryPlan plan,
                VerifiedExecutionEvidence evidence,
                List<String> occurrenceOrder,
                List<? extends CoordinationDeliveryDiagnosticView> diagnostics,
                String planIdentity) {
            this.plan = Objects.requireNonNull(plan, "plan");
            this.evidence = Objects.requireNonNull(evidence, "evidence");
            this.occurrenceOrder = Collections.unmodifiableList(
                    new ArrayList<String>(occurrenceOrder));
            this.diagnostics = Collections.unmodifiableList(
                    new ArrayList<CoordinationDeliveryDiagnosticView>(
                            diagnostics));
            this.planIdentity = Objects.requireNonNull(
                    planIdentity, "planIdentity");
        }

        public ExternalDeliveryPlan plan() { return plan; }
        public VerifiedExecutionEvidence evidence() { return evidence; }
        public List<String> occurrenceOrder() { return occurrenceOrder; }
        public List<CoordinationDeliveryDiagnosticView> diagnostics() {
            return diagnostics;
        }
        public String planIdentity() { return planIdentity; }
    }
}
