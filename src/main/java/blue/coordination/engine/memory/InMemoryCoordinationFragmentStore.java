package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationCanonicalFragment;
import blue.coordination.engine.api.CoordinationEventAdmissionCacheKey;
import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.CoordinationVerifiedEventAdmission;
import blue.coordination.engine.fastpath.ExactNodeHandle;
import blue.coordination.engine.internal.RequestLocalNodeProvider;
import blue.coordination.engine.spi.CoordinationVerifiedEventAdmissionStore;
import blue.coordination.processor.CoordinationFragmentAdmissionVerifier;
import blue.language.api.NodeProviderOutcome;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.provider.NodeProviderResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Thread-safe in-memory immutable fragment store and reference SPI adapter. */
public final class InMemoryCoordinationFragmentStore
        implements CoordinationVerifiedEventAdmissionStore {

    private final String profileIdentity;
    private final Object immutableContentSharingToken;
    private final Map<String, Node> fragments =
            new LinkedHashMap<String, Node>();
    private final Map<String, ExactNodeHandle> fragmentHandles =
            new LinkedHashMap<String, ExactNodeHandle>();
    private final Map<String, Long> fragmentEncodedSizes =
            new LinkedHashMap<String, Long>();
    private final Map<String, String> fragmentWireFingerprints =
            new LinkedHashMap<String, String>();
    private final Map<String, Node> processingViews =
            new LinkedHashMap<String, Node>();
    private final Map<String, Map<String, Node>> processingViewsByInventory =
            new LinkedHashMap<String, Map<String, Node>>();
    private final Map<String, Map<String, ExactNodeHandle>>
            processingViewHandlesByInventory =
            new LinkedHashMap<String, Map<String, ExactNodeHandle>>();
    private final Map<String, Map<String, Long>>
            processingViewEncodedSizesByInventory =
            new LinkedHashMap<String, Map<String, Long>>();
    private final Map<String, Map<String, String>>
            processingViewWireFingerprintsByInventory =
            new LinkedHashMap<String, Map<String, String>>();
    private final Map<String, CoordinationFragmentInventory> inventories =
            new LinkedHashMap<String, CoordinationFragmentInventory>();
    private long singleReadCount;
    private long batchReadCount;
    private long requestedIdentityCount;
    private String verifiedAdmissionDomainIdentity;
    private final ThreadLocal<VerifiedAdmissionCapture>
            verifiedAdmissionCapture =
            new ThreadLocal<VerifiedAdmissionCapture>();

    public InMemoryCoordinationFragmentStore(String profileIdentity) {
        this(profileIdentity, new Object());
    }

    private InMemoryCoordinationFragmentStore(
            String profileIdentity,
            Object immutableContentSharingToken) {
        this.profileIdentity = requireText(
                profileIdentity, "profileIdentity");
        this.immutableContentSharingToken = Objects.requireNonNull(
                immutableContentSharingToken,
                "immutableContentSharingToken");
    }

    static InMemoryCoordinationFragmentStore fromCheckpoint(
            InMemoryCoordinationCheckpoint checkpoint) {
        InMemoryCoordinationCheckpoint checked = Objects.requireNonNull(
                checkpoint, "checkpoint");
        InMemoryCoordinationFragmentStore result =
                new InMemoryCoordinationFragmentStore(
                        checked.profileIdentity,
                        checked.immutableContentSharingToken);
        result.fragments.putAll(checked.fragments);
        result.processingViews.putAll(checked.processingViews);
        result.processingViewsByInventory.putAll(
                checked.processingViewsByInventory);
        result.inventories.putAll(checked.inventories);
        result.rebuildPreparedRepresentations();
        return result;
    }

    /**
     * Atomically admits one splitter-verified event without repeating the
     * portable store's clone/hash/canonicalize/read-back sequence.
     *
     * <p>Every conflict and every required materialization is completed
     * before authoritative maps are changed. Existing legacy values acquire
     * a cached physical fingerprint only after the complete transaction has
     * validated successfully.</p>
     */
    @Override
    public synchronized CoordinationEventAdmissionReceipt admitVerifiedEvent(
            CoordinationVerifiedEventAdmission admission) {
        PreparedVerifiedEventAdmission prepared =
                prepareVerifiedEventAdmission(admission);
        VerifiedAdmissionCapture capture = verifiedAdmissionCapture.get();
        if (capture != null) {
            capture.accept(prepared);
            return prepared.receipt;
        }
        publishPreparedVerifiedEventAdmission(prepared);
        return prepared.receipt;
    }

    /**
     * Runs one engine admission while retaining its fully materialized store
     * delta instead of publishing it. The engine-facing SPI is unchanged;
     * callers in this package can compose the delta with another host's
     * append transaction.
     */
    <T> StagedVerifiedEvent<T> stageVerifiedEventAdmission(
            Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        if (verifiedAdmissionCapture.get() != null) {
            throw new IllegalStateException(
                    "Nested verified-event admission staging is unsupported");
        }
        VerifiedAdmissionCapture capture = new VerifiedAdmissionCapture();
        verifiedAdmissionCapture.set(capture);
        try {
            T result = action.get();
            if (capture.prepared == null) {
                throw new IllegalStateException(
                        "Staged action did not admit a verified event");
            }
            return new StagedVerifiedEvent<T>(result, capture.prepared);
        } finally {
            verifiedAdmissionCapture.remove();
        }
    }

    synchronized void validatePreparedVerifiedEventAdmission(
            PreparedVerifiedEventAdmission prepared) {
        PreparedVerifiedEventAdmission checked = Objects.requireNonNull(
                prepared, "prepared");
        if (checked.owner != this) {
            throw new IllegalArgumentException(
                    "Prepared event admission belongs to another store");
        }
        if (verifiedAdmissionDomainIdentity != null
                && !verifiedAdmissionDomainIdentity.equals(
                        checked.proposedDomain)) {
            throw new IllegalStateException(
                    "Prepared event admission domain is stale");
        }
        for (CoordinationCanonicalFragment proposed
                : checked.admission.fragments().values()) {
            Node current = fragments.get(proposed.blueId());
            if (current != null) {
                String fingerprint = fragmentWireFingerprints.get(
                        proposed.blueId());
                if (fingerprint == null) {
                    fingerprint = CoordinationFragmentAdmissionVerifier
                            .physicalFragmentIdentity(current);
                }
                if (!fingerprint.equals(
                        proposed.canonicalWireFingerprint())) {
                    throw new IllegalStateException(
                            "Prepared fragment conflicts at publication: "
                                    + proposed.blueId());
                }
            }
        }
        CoordinationFragmentInventory inventory =
                checked.admission.inventory();
        CoordinationFragmentInventory currentInventory = inventories.get(
                inventory.inventoryIdentity());
        if (currentInventory != null
                && !currentInventory.toMap().equals(inventory.toMap())) {
            throw new IllegalStateException(
                    "Prepared inventory conflicts at publication: "
                            + inventory.inventoryIdentity());
        }
        Map<String, Node> currentViews = processingViewsByInventory.get(
                inventory.inventoryIdentity());
        if (currentViews != null) {
            Map<String, CoordinationCanonicalFragment> proposedViews =
                    checked.admission.processingViews();
            if (!currentViews.keySet().equals(proposedViews.keySet())) {
                throw new IllegalStateException(
                        "Prepared PROCESS-view surface conflicts at "
                                + "publication: "
                                + inventory.inventoryIdentity());
            }
            Map<String, String> currentFingerprints =
                    processingViewWireFingerprintsByInventory.get(
                            inventory.inventoryIdentity());
            for (CoordinationCanonicalFragment proposed
                    : proposedViews.values()) {
                String fingerprint = currentFingerprints == null
                        ? null
                        : currentFingerprints.get(proposed.blueId());
                if (fingerprint == null) {
                    fingerprint = CoordinationFragmentAdmissionVerifier
                            .physicalFragmentIdentity(
                                    currentViews.get(proposed.blueId()));
                }
                if (!fingerprint.equals(
                        proposed.canonicalWireFingerprint())) {
                    throw new IllegalStateException(
                            "Prepared PROCESS view conflicts at publication: "
                                    + proposed.blueId());
                }
            }
        }
    }

    synchronized void publishPreparedVerifiedEventAdmission(
            PreparedVerifiedEventAdmission prepared) {
        validatePreparedVerifiedEventAdmission(prepared);
        publishPreparedVerifiedEventAdmissionUnchecked(prepared);
    }

    private PreparedVerifiedEventAdmission prepareVerifiedEventAdmission(
            CoordinationVerifiedEventAdmission admission) {
        CoordinationVerifiedEventAdmission checked = Objects.requireNonNull(
                admission, "admission");
        CoordinationFragmentInventory inventory = checked.inventory();
        requireProfile(inventory.fragmentationProfileIdentity());
        CoordinationEventAdmissionCacheKey key = checked.key();
        requireProfile(key.fragmentationProfileIdentity());
        String proposedDomain = admissionDomainIdentity(key);
        if (verifiedAdmissionDomainIdentity != null
                && !verifiedAdmissionDomainIdentity.equals(proposedDomain)) {
            throw new IllegalArgumentException(
                    "Verified event evidence belongs to another engine "
                            + "domain");
        }

        List<String> inserted = new ArrayList<String>();
        List<String> retained = new ArrayList<String>();
        Map<String, String> learnedFragmentFingerprints =
                new LinkedHashMap<String, String>();
        for (CoordinationCanonicalFragment proposed
                : checked.fragments().values()) {
            Node current = fragments.get(proposed.blueId());
            if (current == null) {
                inserted.add(proposed.blueId());
                continue;
            }
            String currentFingerprint = fragmentWireFingerprints.get(
                    proposed.blueId());
            if (currentFingerprint == null) {
                currentFingerprint = CoordinationFragmentAdmissionVerifier
                        .physicalFragmentIdentity(current);
                learnedFragmentFingerprints.put(
                        proposed.blueId(), currentFingerprint);
            }
            if (!currentFingerprint.equals(
                    proposed.canonicalWireFingerprint())) {
                throw new IllegalStateException(
                        "Conflicting immutable fragment content for "
                                + proposed.blueId());
            }
            retained.add(proposed.blueId());
        }

        CoordinationFragmentInventory currentInventory = inventories.get(
                inventory.inventoryIdentity());
        if (currentInventory != null
                && !currentInventory.toMap().equals(inventory.toMap())) {
            throw new IllegalStateException(
                    "Conflicting inventory for immutable identity "
                            + inventory.inventoryIdentity());
        }

        Map<String, CoordinationCanonicalFragment> proposedViews =
                checked.processingViews();
        Map<String, Node> currentViews = processingViewsByInventory.get(
                inventory.inventoryIdentity());
        Map<String, String> learnedViewFingerprints = null;
        if (currentViews != null) {
            if (!currentViews.keySet().equals(proposedViews.keySet())) {
                throw new IllegalStateException(
                        "Conflicting PROCESS-view surface for inventory "
                                + inventory.inventoryIdentity());
            }
            Map<String, String> currentFingerprints =
                    processingViewWireFingerprintsByInventory.get(
                            inventory.inventoryIdentity());
            learnedViewFingerprints = currentFingerprints == null
                    ? new LinkedHashMap<String, String>()
                    : new LinkedHashMap<String, String>(currentFingerprints);
            for (CoordinationCanonicalFragment proposed
                    : proposedViews.values()) {
                String currentFingerprint = learnedViewFingerprints.get(
                        proposed.blueId());
                if (currentFingerprint == null) {
                    currentFingerprint =
                            CoordinationFragmentAdmissionVerifier
                                    .physicalFragmentIdentity(
                                            currentViews.get(
                                                    proposed.blueId()));
                    learnedViewFingerprints.put(
                            proposed.blueId(), currentFingerprint);
                }
                if (!currentFingerprint.equals(
                        proposed.canonicalWireFingerprint())) {
                    throw new IllegalStateException(
                            "Conflicting PROCESS view for "
                                    + proposed.blueId());
                }
            }
        }

        // Materialize only missing immutable bodies, still before mutation.
        Map<String, Node> materializedFragments =
                new LinkedHashMap<String, Node>();
        Map<String, ExactNodeHandle> materializedFragmentHandles =
                new LinkedHashMap<String, ExactNodeHandle>();
        Map<String, Long> materializedFragmentSizes =
                new LinkedHashMap<String, Long>();
        for (String blueId : inserted) {
            Node materialized = checked.fragments().get(blueId).materialize();
            materializedFragments.put(blueId, materialized);
            materializedFragmentHandles.put(
                    blueId,
                    ExactNodeHandle.adoptAndVerify(
                            blueId,
                            materialized,
                            immutableContentSharingToken));
            materializedFragmentSizes.put(
                    blueId,
                    Long.valueOf(RequestLocalNodeProvider.bytes(
                            materialized)));
        }
        Map<String, Node> materializedViews = null;
        Map<String, ExactNodeHandle> materializedViewHandles = null;
        Map<String, Long> materializedViewSizes = null;
        Map<String, String> newViewFingerprints = null;
        if (currentViews == null) {
            materializedViews = new LinkedHashMap<String, Node>();
            materializedViewHandles =
                    new LinkedHashMap<String, ExactNodeHandle>();
            materializedViewSizes = new LinkedHashMap<String, Long>();
            newViewFingerprints = new LinkedHashMap<String, String>();
            for (CoordinationCanonicalFragment view
                    : proposedViews.values()) {
                Node materialized = view.materialize();
                materializedViews.put(view.blueId(), materialized);
                if (!materialized.isReferenceOnly()) {
                    materializedViewHandles.put(
                            view.blueId(),
                            ExactNodeHandle.adoptAndVerify(
                                    view.blueId(),
                                    materialized,
                                    immutableContentSharingToken));
                    materializedViewSizes.put(
                            view.blueId(),
                            Long.valueOf(RequestLocalNodeProvider.bytes(
                                    materialized)));
                }
                newViewFingerprints.put(
                        view.blueId(), view.canonicalWireFingerprint());
            }
        }
        CoordinationFragmentInventory retainedInventory =
                currentInventory == null ? inventory.retainedCopy() : null;
        CoordinationEventAdmissionReceipt receipt =
                new CoordinationEventAdmissionReceipt(
                        key.eventBlueId(),
                        inventory.inventoryIdentity(),
                        inserted,
                        retained,
                        currentViews == null ? proposedViews.size() : 0,
                        currentInventory == null);

        return new PreparedVerifiedEventAdmission(
                this,
                proposedDomain,
                checked,
                learnedFragmentFingerprints,
                materializedFragments,
                materializedFragmentHandles,
                materializedFragmentSizes,
                retainedInventory,
                currentViews == null,
                materializedViews,
                materializedViewHandles,
                materializedViewSizes,
                newViewFingerprints,
                learnedViewFingerprints,
                receipt);
    }

    /** Publishes only prevalidated/preallocated values; it has no callbacks. */
    void publishPreparedVerifiedEventAdmissionUnchecked(
            PreparedVerifiedEventAdmission prepared) {
        verifiedAdmissionDomainIdentity = prepared.proposedDomain;
        fragmentWireFingerprints.putAll(
                prepared.learnedFragmentFingerprints);
        for (Map.Entry<String, Node> item
                : prepared.materializedFragments.entrySet()) {
            String blueId = item.getKey();
            if (!fragments.containsKey(blueId)) {
                fragments.put(blueId, item.getValue());
                fragmentHandles.put(
                        blueId,
                        prepared.materializedFragmentHandles.get(blueId));
                fragmentEncodedSizes.put(
                        blueId,
                        prepared.materializedFragmentSizes.get(blueId));
            }
            fragmentWireFingerprints.put(
                    blueId,
                    prepared.admission.fragments().get(blueId)
                            .canonicalWireFingerprint());
        }
        if (!inventories.containsKey(
                prepared.admission.inventory().inventoryIdentity())) {
            inventories.put(
                    prepared.admission.inventory().inventoryIdentity(),
                    prepared.retainedInventory);
        }
        if (!processingViewsByInventory.containsKey(
                prepared.admission.inventory().inventoryIdentity())) {
            processingViewsByInventory.put(
                    prepared.admission.inventory().inventoryIdentity(),
                    Collections.unmodifiableMap(
                            prepared.materializedViews));
            processingViewHandlesByInventory.put(
                    prepared.admission.inventory().inventoryIdentity(),
                    Collections.unmodifiableMap(
                            prepared.materializedViewHandles));
            processingViewEncodedSizesByInventory.put(
                    prepared.admission.inventory().inventoryIdentity(),
                    Collections.unmodifiableMap(
                            prepared.materializedViewSizes));
            processingViewWireFingerprintsByInventory.put(
                    prepared.admission.inventory().inventoryIdentity(),
                    Collections.unmodifiableMap(
                            prepared.newViewFingerprints));
        } else {
            Map<String, String> fingerprints =
                    prepared.insertProcessingViews
                            ? prepared.newViewFingerprints
                            : prepared.learnedViewFingerprints;
            processingViewWireFingerprintsByInventory.put(
                    prepared.admission.inventory().inventoryIdentity(),
                    Collections.unmodifiableMap(
                            new LinkedHashMap<String, String>(fingerprints)));
        }
    }

    synchronized InMemoryCoordinationCheckpoint fragmentCheckpoint(
            InMemoryCoordinationSessionStore sessionStore,
            InMemoryStoredCoordinationEventStore storedEvents,
            InMemoryCoordinationDispatchLedger dispatchLedger,
            Map<String, Node> currentRootViews,
            long sessionSequence) {
        return Objects.requireNonNull(sessionStore, "sessionStore")
                .checkpoint(
                        profileIdentity,
                        immutableContentSharingToken,
                        fragments,
                        processingViews,
                        processingViewsByInventory,
                        inventories,
                        Objects.requireNonNull(
                                currentRootViews, "currentRootViews"),
                        Objects.requireNonNull(storedEvents, "storedEvents"),
                        Objects.requireNonNull(
                                dispatchLedger, "dispatchLedger"),
                        sessionSequence);
    }

    @Override
    public String fragmentationProfileIdentity() {
        return profileIdentity;
    }

    @Override
    public synchronized List<Node> fetchByBlueId(String blueId) {
        Node node = exactProviderRead(blueId, true);
        return node == null
                ? Collections.<Node>emptyList()
                : Collections.singletonList(node);
    }

    @Override
    public synchronized NodeProviderResult fetchResultByBlueId(
            String blueId) {
        Node node = exactProviderRead(blueId, true);
        return node == null
                ? NodeProviderResult.notFound()
                : NodeProviderResult.found(Collections.singletonList(node));
    }

    @Override
    public synchronized Node read(String profile, String blueId) {
        requireProfile(profile);
        return exactRead(blueId, false);
    }

    @Override
    public synchronized boolean putIfAbsent(
            String profile,
            String blueId,
            Node exactFragment) {
        requireProfile(profile);
        Node proposed = verified(blueId, exactFragment);
        Node current = fragments.get(blueId);
        if (current != null) {
            requireSame(blueId, proposed, current);
            return false;
        }
        ExactNodeHandle handle = ExactNodeHandle.adoptAndVerify(
                blueId, proposed, immutableContentSharingToken);
        long encodedSize = RequestLocalNodeProvider.bytes(proposed);
        fragments.put(blueId, proposed);
        fragmentHandles.put(blueId, handle);
        fragmentEncodedSizes.put(blueId, Long.valueOf(encodedSize));
        return true;
    }

    @Override
    public synchronized boolean putAllIfAbsent(
            String profile,
            Map<String, Node> exactFragments) {
        requireProfile(profile);
        Map<String, Node> proposed = new LinkedHashMap<String, Node>();
        for (Map.Entry<String, Node> entry
                : Objects.requireNonNull(
                        exactFragments, "exactFragments").entrySet()) {
            proposed.put(
                    entry.getKey(),
                    verified(entry.getKey(), entry.getValue()));
        }
        for (Map.Entry<String, Node> entry : proposed.entrySet()) {
            Node current = fragments.get(entry.getKey());
            if (current != null) {
                requireSame(entry.getKey(), entry.getValue(), current);
            }
        }
        boolean installed = false;
        for (Map.Entry<String, Node> entry : proposed.entrySet()) {
            if (!fragments.containsKey(entry.getKey())) {
                Node retained = entry.getValue().clone();
                ExactNodeHandle handle = ExactNodeHandle.adoptAndVerify(
                        entry.getKey(),
                        retained,
                        immutableContentSharingToken);
                long encodedSize = RequestLocalNodeProvider.bytes(retained);
                fragments.put(entry.getKey(), retained);
                fragmentHandles.put(entry.getKey(), handle);
                fragmentEncodedSizes.put(
                        entry.getKey(), Long.valueOf(encodedSize));
                installed = true;
            }
        }
        return installed;
    }

    @Override
    public synchronized Map<String, NodeProviderResult> readAll(
            Collection<String> blueIds) {
        return readBatch(blueIds, false);
    }

    @Override
    public synchronized NodeProviderResult readCanonical(String blueId) {
        return outcome(exactRead(blueId, true));
    }

    @Override
    public synchronized Map<String, NodeProviderResult> readProcessingAll(
            Collection<String> blueIds) {
        return readBatch(blueIds, true);
    }

    @Override
    public synchronized Map<String, NodeProviderResult> readProcessingAll(
            String inventoryIdentity,
            Collection<String> blueIds) {
        String inventory = requireText(
                inventoryIdentity, "inventoryIdentity");
        CoordinationFragmentInventory owner = requireInventory(inventory);
        Map<String, Node> scoped = processingViewsByInventory.get(inventory);
        return readBatch(
                blueIds,
                scoped != null
                        ? scoped
                        : Collections.<String, Node>emptyMap(),
                new HashSet<String>(owner.fragmentBlueIds()));
    }

    @Override
    public synchronized NodeProviderResult readProcessing(
            String inventoryIdentity,
            String blueId) {
        String inventory = requireText(
                inventoryIdentity, "inventoryIdentity");
        String identity = requireText(blueId, "blueId");
        CoordinationFragmentInventory owner = requireInventory(inventory);
        singleReadCount++;
        requestedIdentityCount++;
        if (!owner.fragmentBlueIds().contains(identity)) {
            return NodeProviderResult.notFound();
        }
        Map<String, Node> scoped = processingViewsByInventory.get(inventory);
        Node view = scoped == null ? null : scoped.get(identity);
        Node node = view != null ? verified(identity, view) : exactRead(
                identity, false);
        return outcome(node);
    }

    @Override
    public synchronized FragmentRepresentations readRepresentations(
            String inventoryIdentity,
            Collection<String> blueIds) {
        String inventory = requireText(
                inventoryIdentity, "inventoryIdentity");
        CoordinationFragmentInventory owner = requireInventory(inventory);
        batchReadCount++;
        return representationBatch(owner, blueIds);
    }

    @Override
    public synchronized InventoryFragmentRepresentations
            readRepresentationsByInventory(
                    Map<String, Collection<String>> blueIdsByInventory) {
        Map<String, Collection<String>> requested = Objects.requireNonNull(
                blueIdsByInventory, "blueIdsByInventory");
        Map<String, FragmentRepresentations> result =
                new LinkedHashMap<String, FragmentRepresentations>();
        boolean hasRequestedIdentity = false;
        for (Map.Entry<String, Collection<String>> entry
                : requested.entrySet()) {
            String inventory = requireText(
                    entry.getKey(), "inventoryIdentity");
            Collection<String> blueIds = Objects.requireNonNull(
                    entry.getValue(), "inventoryBlueIds");
            CoordinationFragmentInventory owner = requireInventory(inventory);
            if (blueIds.isEmpty()) {
                continue;
            }
            hasRequestedIdentity = true;
            result.put(inventory, representationBatch(owner, blueIds));
        }
        if (hasRequestedIdentity) {
            batchReadCount++;
        }
        return new InventoryFragmentRepresentations(
                result, hasRequestedIdentity ? 1 : 0);
    }

    /**
     * Trusted in-process counterpart of the portable representation read.
     * Values were cloned, identity-verified and byte-accounted when admitted;
     * this method therefore returns only immutable ownership handles and
     * metadata, without constructing {@link NodeProviderResult}s or touching
     * {@code NodeWireForm} on the request path.
     */
    synchronized PreparedInventoryFragmentRepresentations
            readPreparedRepresentationsByInventory(
                    Map<String, Collection<String>> blueIdsByInventory) {
        Set<String> accounted = new LinkedHashSet<String>();
        for (Collection<String> blueIds : Objects.requireNonNull(
                blueIdsByInventory, "blueIdsByInventory").values()) {
            accounted.addAll(blueIds);
        }
        return readPreparedRepresentationsByInventory(
                blueIdsByInventory, accounted);
    }

    /**
     * Returns all requested prepared handles while accounting only identities
     * in the initial loaded set. Extra allowed handles are admission-time
     * metadata made available for lazy O(1) demand, not backend reads.
     */
    synchronized PreparedInventoryFragmentRepresentations
            readPreparedRepresentationsByInventory(
                    Map<String, Collection<String>> blueIdsByInventory,
                    Collection<String> initiallyLoadedBlueIds) {
        Map<String, Collection<String>> requested = Objects.requireNonNull(
                blueIdsByInventory, "blueIdsByInventory");
        Set<String> initiallyLoaded = new HashSet<String>(
                Objects.requireNonNull(
                        initiallyLoadedBlueIds,
                        "initiallyLoadedBlueIds"));
        Map<String, PreparedFragmentRepresentations> result =
                new LinkedHashMap<String, PreparedFragmentRepresentations>();
        boolean hasRequestedIdentity = false;
        for (Map.Entry<String, Collection<String>> entry
                : requested.entrySet()) {
            String inventoryIdentity = requireText(
                    entry.getKey(), "inventoryIdentity");
            Collection<String> blueIds = Objects.requireNonNull(
                    entry.getValue(), "inventoryBlueIds");
            CoordinationFragmentInventory inventory = requireInventory(
                    inventoryIdentity);
            if (blueIds.isEmpty()) continue;
            Set<String> members = new HashSet<String>(
                    inventory.fragmentBlueIds());
            Map<String, ExactNodeHandle> physical =
                    new LinkedHashMap<String, ExactNodeHandle>();
            Map<String, ExactNodeHandle> processing =
                    new LinkedHashMap<String, ExactNodeHandle>();
            Map<String, Long> physicalSizes =
                    new LinkedHashMap<String, Long>();
            Map<String, Long> processingSizes =
                    new LinkedHashMap<String, Long>();
            Map<String, ExactNodeHandle> scopedHandles =
                    processingViewHandlesByInventory.get(inventoryIdentity);
            Map<String, Long> scopedSizes =
                    processingViewEncodedSizesByInventory.get(
                            inventoryIdentity);
            for (String requestedBlueId : blueIds) {
                String blueId = requireText(requestedBlueId, "blueId");
                if (initiallyLoaded.contains(blueId)) {
                    requestedIdentityCount++;
                    hasRequestedIdentity = true;
                }
                if (!members.contains(blueId)) {
                    throw new IllegalArgumentException(
                            "Prepared fragment is outside inventory "
                                    + inventoryIdentity + ": " + blueId);
                }
                ExactNodeHandle physicalHandle = fragmentHandles.get(blueId);
                Long physicalSize = fragmentEncodedSizes.get(blueId);
                if (physicalHandle == null || physicalSize == null) {
                    throw new IllegalStateException(
                            "Admitted fragment lacks prepared content: "
                                    + blueId);
                }
                ExactNodeHandle processingHandle = scopedHandles == null
                        ? null
                        : scopedHandles.get(blueId);
                Long processingSize = scopedSizes == null
                        ? null
                        : scopedSizes.get(blueId);
                physical.put(blueId, physicalHandle);
                physicalSizes.put(blueId, physicalSize);
                processing.put(
                        blueId,
                        processingHandle == null
                                ? physicalHandle
                                : processingHandle);
                processingSizes.put(
                        blueId,
                        processingSize == null
                                ? physicalSize
                                : processingSize);
            }
            result.put(
                    inventoryIdentity,
                    new PreparedFragmentRepresentations(
                            processing,
                            physical,
                            processingSizes,
                            physicalSizes));
        }
        if (hasRequestedIdentity) batchReadCount++;
        return new PreparedInventoryFragmentRepresentations(
                result, hasRequestedIdentity ? 1 : 0);
    }

    private FragmentRepresentations representationBatch(
            CoordinationFragmentInventory inventory,
            Collection<String> blueIds) {
        Map<String, Node> scoped = processingViewsByInventory.get(
                inventory.inventoryIdentity());
        Map<String, NodeProviderResult> processing =
                new LinkedHashMap<String, NodeProviderResult>();
        Map<String, NodeProviderResult> physical =
                new LinkedHashMap<String, NodeProviderResult>();
        Set<String> inventoryBlueIds = new HashSet<String>(
                inventory.fragmentBlueIds());
        for (String requestedBlueId
                : Objects.requireNonNull(blueIds, "blueIds")) {
            String blueId = requireText(requestedBlueId, "blueId");
            requestedIdentityCount++;
            if (!inventoryBlueIds.contains(blueId)) {
                processing.put(blueId, NodeProviderResult.notFound());
                physical.put(blueId, NodeProviderResult.notFound());
                continue;
            }
            Node canonical = exactRead(blueId, false);
            Node view = scoped == null ? null : scoped.get(blueId);
            Node process = view == null
                    ? (canonical == null ? null : canonical.clone())
                    : verified(blueId, view);
            processing.put(blueId, outcome(process));
            physical.put(blueId, outcome(canonical));
        }
        return new FragmentRepresentations(processing, physical);
    }

    private static NodeProviderResult outcome(Node node) {
        return node == null
                ? NodeProviderResult.notFound()
                : NodeProviderResult.found(Collections.singletonList(node));
    }

    private Map<String, NodeProviderResult> readBatch(
            Collection<String> blueIds,
            boolean processing) {
        batchReadCount++;
        Map<String, NodeProviderResult> result =
                new LinkedHashMap<String, NodeProviderResult>();
        for (String requestedBlueId
                : Objects.requireNonNull(blueIds, "blueIds")) {
            String blueId = requireText(requestedBlueId, "blueId");
            requestedIdentityCount++;
            Node node = processing
                    ? exactProviderRead(blueId, false)
                    : exactRead(blueId, false);
            result.put(
                    blueId,
                    node == null
                            ? NodeProviderResult.notFound()
                            : NodeProviderResult.found(
                                    Collections.singletonList(node)));
        }
        return Collections.unmodifiableMap(result);
    }

    private Map<String, NodeProviderResult> readBatch(
            Collection<String> blueIds,
            Map<String, Node> scopedViews,
            Set<String> inventoryBlueIds) {
        batchReadCount++;
        Map<String, NodeProviderResult> result =
                new LinkedHashMap<String, NodeProviderResult>();
        for (String requestedBlueId
                : Objects.requireNonNull(blueIds, "blueIds")) {
            String blueId = requireText(requestedBlueId, "blueId");
            requestedIdentityCount++;
            if (!inventoryBlueIds.contains(blueId)) {
                result.put(blueId, NodeProviderResult.notFound());
                continue;
            }
            Node view = scopedViews.get(blueId);
            Node node = view != null
                    ? verified(blueId, view)
                    : exactRead(blueId, false);
            result.put(
                    blueId,
                    node == null
                            ? NodeProviderResult.notFound()
                            : NodeProviderResult.found(
                            Collections.singletonList(node)));
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public synchronized void putProcessingViews(
            Map<String, Node> exactProcessingViews) {
        Map<String, Node> proposed = new LinkedHashMap<String, Node>();
        for (Map.Entry<String, Node> entry : Objects.requireNonNull(
                exactProcessingViews, "exactProcessingViews").entrySet()) {
            String blueId = requireText(entry.getKey(), "processingViewBlueId");
            if (!fragments.containsKey(blueId)) {
                throw new IllegalStateException(
                        "PROCESS view has no canonical physical fragment: "
                                + blueId);
            }
            proposed.put(blueId, verified(blueId, entry.getValue()));
        }
        for (Map.Entry<String, Node> entry : proposed.entrySet()) {
            Node current = processingViews.get(entry.getKey());
            if (current != null) {
                requireSame(entry.getKey(), entry.getValue(), current);
            }
        }
        for (Map.Entry<String, Node> entry : proposed.entrySet()) {
            if (!processingViews.containsKey(entry.getKey())) {
                processingViews.put(entry.getKey(), entry.getValue().clone());
            }
        }
    }

    @Override
    public synchronized void putProcessingViews(
            String inventoryIdentity,
            Map<String, Node> exactProcessingViews) {
        String inventory = requireText(
                inventoryIdentity, "inventoryIdentity");
        CoordinationFragmentInventory owner = inventories.get(inventory);
        if (owner == null) {
            throw new IllegalStateException(
                    "PROCESS-view inventory is absent: " + inventory);
        }
        Objects.requireNonNull(
                exactProcessingViews, "exactProcessingViews");
        Map<String, Node> proposed = new LinkedHashMap<String, Node>();
        for (Map.Entry<String, Node> entry
                : exactProcessingViews.entrySet()) {
            String blueId = requireText(
                    entry.getKey(), "processingViewBlueId");
            if (!fragments.containsKey(blueId)) {
                throw new IllegalStateException(
                        "PROCESS view has no canonical physical fragment: "
                                + blueId);
            }
            if (!owner.fragmentBlueIds().contains(blueId)) {
                throw new IllegalStateException(
                        "PROCESS view is outside inventory " + inventory
                                + ": " + blueId);
            }
            proposed.put(blueId, verified(blueId, entry.getValue()));
        }
        Map<String, Node> current = processingViewsByInventory.get(inventory);
        if (current != null) {
            if (!current.keySet().equals(proposed.keySet())) {
                List<String> onlyCurrent = new ArrayList<String>(
                        current.keySet());
                onlyCurrent.removeAll(proposed.keySet());
                List<String> onlyProposed = new ArrayList<String>(
                        proposed.keySet());
                onlyProposed.removeAll(current.keySet());
                throw new IllegalStateException(
                        "Conflicting PROCESS-view surface for inventory "
                                + inventory
                                + "; retained only=" + onlyCurrent
                                + "; proposed only=" + onlyProposed);
            }
            for (Map.Entry<String, Node> entry : proposed.entrySet()) {
                requireSame(
                        entry.getKey(), entry.getValue(),
                        current.get(entry.getKey()));
            }
            return;
        }
        Map<String, Node> retained = new LinkedHashMap<String, Node>();
        Map<String, ExactNodeHandle> retainedHandles =
                new LinkedHashMap<String, ExactNodeHandle>();
        Map<String, Long> retainedSizes =
                new LinkedHashMap<String, Long>();
        for (Map.Entry<String, Node> entry : proposed.entrySet()) {
            Node retainedView = entry.getValue().clone();
            retained.put(entry.getKey(), retainedView);
            if (!retainedView.isReferenceOnly()) {
                retainedHandles.put(
                        entry.getKey(),
                        ExactNodeHandle.adoptAndVerify(
                                entry.getKey(),
                                retainedView,
                                immutableContentSharingToken));
                retainedSizes.put(
                        entry.getKey(),
                        Long.valueOf(RequestLocalNodeProvider.bytes(
                                retainedView)));
            }
        }
        processingViewsByInventory.put(
                inventory, Collections.unmodifiableMap(retained));
        processingViewHandlesByInventory.put(
                inventory, Collections.unmodifiableMap(retainedHandles));
        processingViewEncodedSizesByInventory.put(
                inventory, Collections.unmodifiableMap(retainedSizes));
    }

    @Override
    public synchronized void putInventory(
            CoordinationFragmentInventory inventory) {
        CoordinationFragmentInventory checked = Objects.requireNonNull(
                inventory, "inventory");
        requireProfile(checked.fragmentationProfileIdentity());
        for (String blueId : checked.fragmentBlueIds()) {
            if (!fragments.containsKey(blueId)) {
                throw new IllegalStateException(
                        "Inventory refers to an absent immutable fragment: "
                                + blueId);
            }
        }
        CoordinationFragmentInventory current = inventories.get(
                checked.inventoryIdentity());
        if (current != null && !current.toMap().equals(checked.toMap())) {
            throw new IllegalStateException(
                    "Conflicting inventory for immutable identity "
                            + checked.inventoryIdentity());
        }
        if (current == null) {
            inventories.put(
                    checked.inventoryIdentity(), checked.retainedCopy());
        }
    }

    @Override
    public synchronized CoordinationFragmentInventory requireInventory(
            String inventoryIdentity) {
        CoordinationFragmentInventory inventory = inventories.get(
                requireText(inventoryIdentity, "inventoryIdentity"));
        if (inventory == null) {
            throw new IllegalStateException(
                    "Fragment inventory is absent: " + inventoryIdentity);
        }
        // Inventories are immutable body-free evidence. Bounded exact Root
        // views belong to the engine cache, not to this persistence store.
        return inventory;
    }

    /** Returns the physical immutable-body count for deduplication evidence. */
    public synchronized int physicalFragmentCount() {
        return fragments.size();
    }

    /** Returns the noncanonical, body-free PROCESS-view count. */
    public synchronized int processingViewCount() {
        int count = processingViews.size();
        for (Map<String, Node> scoped
                : processingViewsByInventory.values()) {
            count += scoped.size();
        }
        return count;
    }

    public synchronized int inventoryCount() { return inventories.size(); }
    public synchronized long singleReadCount() { return singleReadCount; }
    public synchronized long batchReadCount() { return batchReadCount; }
    public synchronized long requestedIdentityCount() {
        return requestedIdentityCount;
    }

    public synchronized void resetReadCounts() {
        singleReadCount = 0L;
        batchReadCount = 0L;
        requestedIdentityCount = 0L;
    }

    private void rebuildPreparedRepresentations() {
        for (Map.Entry<String, Node> entry : fragments.entrySet()) {
            fragmentHandles.put(
                    entry.getKey(),
                    ExactNodeHandle.adoptAndVerify(
                            entry.getKey(),
                            entry.getValue(),
                            immutableContentSharingToken));
            fragmentEncodedSizes.put(
                    entry.getKey(),
                    Long.valueOf(RequestLocalNodeProvider.bytes(
                            entry.getValue())));
        }
        for (Map.Entry<String, Map<String, Node>> inventory
                : processingViewsByInventory.entrySet()) {
            Map<String, ExactNodeHandle> handles =
                    new LinkedHashMap<String, ExactNodeHandle>();
            Map<String, Long> sizes = new LinkedHashMap<String, Long>();
            for (Map.Entry<String, Node> entry
                    : inventory.getValue().entrySet()) {
                if (!entry.getValue().isReferenceOnly()) {
                    handles.put(
                            entry.getKey(),
                            ExactNodeHandle.adoptAndVerify(
                                    entry.getKey(),
                                    entry.getValue(),
                                    immutableContentSharingToken));
                    sizes.put(
                            entry.getKey(),
                            Long.valueOf(RequestLocalNodeProvider.bytes(
                                    entry.getValue())));
                }
            }
            processingViewHandlesByInventory.put(
                    inventory.getKey(), Collections.unmodifiableMap(handles));
            processingViewEncodedSizesByInventory.put(
                    inventory.getKey(), Collections.unmodifiableMap(sizes));
        }
    }

    static final class StagedVerifiedEvent<T> {
        private final T result;
        private final PreparedVerifiedEventAdmission prepared;

        private StagedVerifiedEvent(
                T result,
                PreparedVerifiedEventAdmission prepared) {
            this.result = Objects.requireNonNull(result, "result");
            this.prepared = Objects.requireNonNull(prepared, "prepared");
        }

        T result() { return result; }

        PreparedVerifiedEventAdmission prepared() { return prepared; }
    }

    static final class PreparedVerifiedEventAdmission {
        private final InMemoryCoordinationFragmentStore owner;
        private final String proposedDomain;
        private final CoordinationVerifiedEventAdmission admission;
        private final Map<String, String> learnedFragmentFingerprints;
        private final Map<String, Node> materializedFragments;
        private final Map<String, ExactNodeHandle>
                materializedFragmentHandles;
        private final Map<String, Long> materializedFragmentSizes;
        private final CoordinationFragmentInventory retainedInventory;
        private final boolean insertProcessingViews;
        private final Map<String, Node> materializedViews;
        private final Map<String, ExactNodeHandle> materializedViewHandles;
        private final Map<String, Long> materializedViewSizes;
        private final Map<String, String> newViewFingerprints;
        private final Map<String, String> learnedViewFingerprints;
        private final CoordinationEventAdmissionReceipt receipt;

        private PreparedVerifiedEventAdmission(
                InMemoryCoordinationFragmentStore owner,
                String proposedDomain,
                CoordinationVerifiedEventAdmission admission,
                Map<String, String> learnedFragmentFingerprints,
                Map<String, Node> materializedFragments,
                Map<String, ExactNodeHandle> materializedFragmentHandles,
                Map<String, Long> materializedFragmentSizes,
                CoordinationFragmentInventory retainedInventory,
                boolean insertProcessingViews,
                Map<String, Node> materializedViews,
                Map<String, ExactNodeHandle> materializedViewHandles,
                Map<String, Long> materializedViewSizes,
                Map<String, String> newViewFingerprints,
                Map<String, String> learnedViewFingerprints,
                CoordinationEventAdmissionReceipt receipt) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.proposedDomain = Objects.requireNonNull(
                    proposedDomain, "proposedDomain");
            this.admission = Objects.requireNonNull(admission, "admission");
            this.learnedFragmentFingerprints = Objects.requireNonNull(
                    learnedFragmentFingerprints,
                    "learnedFragmentFingerprints");
            this.materializedFragments = Objects.requireNonNull(
                    materializedFragments, "materializedFragments");
            this.materializedFragmentHandles = Objects.requireNonNull(
                    materializedFragmentHandles,
                    "materializedFragmentHandles");
            this.materializedFragmentSizes = Objects.requireNonNull(
                    materializedFragmentSizes,
                    "materializedFragmentSizes");
            this.retainedInventory = retainedInventory;
            this.insertProcessingViews = insertProcessingViews;
            this.materializedViews = materializedViews;
            this.materializedViewHandles = materializedViewHandles;
            this.materializedViewSizes = materializedViewSizes;
            this.newViewFingerprints = newViewFingerprints;
            this.learnedViewFingerprints = learnedViewFingerprints;
            this.receipt = Objects.requireNonNull(receipt, "receipt");
            if (insertProcessingViews
                    && (materializedViews == null
                    || materializedViewHandles == null
                    || materializedViewSizes == null
                    || newViewFingerprints == null)) {
                throw new IllegalArgumentException(
                        "New PROCESS views are not fully materialized");
            }
            if (!insertProcessingViews
                    && learnedViewFingerprints == null) {
                throw new IllegalArgumentException(
                        "Existing PROCESS views lack verified fingerprints");
            }
        }
    }

    private static final class VerifiedAdmissionCapture {
        private PreparedVerifiedEventAdmission prepared;

        private void accept(PreparedVerifiedEventAdmission candidate) {
            if (prepared != null) {
                throw new IllegalStateException(
                        "Staged action admitted more than one event");
            }
            prepared = Objects.requireNonNull(candidate, "candidate");
        }
    }

    static final class PreparedFragmentRepresentations {
        private final Map<String, ExactNodeHandle> processing;
        private final Map<String, ExactNodeHandle> physical;
        private final Map<String, Long> processingSizes;
        private final Map<String, Long> physicalSizes;

        private PreparedFragmentRepresentations(
                Map<String, ExactNodeHandle> processing,
                Map<String, ExactNodeHandle> physical,
                Map<String, Long> processingSizes,
                Map<String, Long> physicalSizes) {
            this.processing = Collections.unmodifiableMap(
                    new LinkedHashMap<String, ExactNodeHandle>(processing));
            this.physical = Collections.unmodifiableMap(
                    new LinkedHashMap<String, ExactNodeHandle>(physical));
            this.processingSizes = Collections.unmodifiableMap(
                    new LinkedHashMap<String, Long>(processingSizes));
            this.physicalSizes = Collections.unmodifiableMap(
                    new LinkedHashMap<String, Long>(physicalSizes));
        }

        Map<String, ExactNodeHandle> processing() { return processing; }
        Map<String, ExactNodeHandle> physical() { return physical; }
        Map<String, Long> processingSizes() { return processingSizes; }
        Map<String, Long> physicalSizes() { return physicalSizes; }
    }

    static final class PreparedInventoryFragmentRepresentations {
        private final Map<String, PreparedFragmentRepresentations>
                byInventory;
        private final int backendReadCount;

        private PreparedInventoryFragmentRepresentations(
                Map<String, PreparedFragmentRepresentations> byInventory,
                int backendReadCount) {
            this.byInventory = Collections.unmodifiableMap(
                    new LinkedHashMap<String,
                            PreparedFragmentRepresentations>(byInventory));
            this.backendReadCount = backendReadCount;
        }

        Map<String, PreparedFragmentRepresentations> byInventory() {
            return byInventory;
        }

        int backendReadCount() { return backendReadCount; }
    }

    private Node exactRead(String blueId, boolean countSingle) {
        String identity = requireText(blueId, "blueId");
        if (countSingle) {
            singleReadCount++;
            requestedIdentityCount++;
        }
        Node node = fragments.get(identity);
        if (node == null) return null;
        Node verified = verified(identity, node);
        return verified.clone();
    }

    private Node exactProviderRead(String blueId, boolean countSingle) {
        String identity = requireText(blueId, "blueId");
        if (countSingle) {
            singleReadCount++;
            requestedIdentityCount++;
        }
        Node view = processingViews.get(identity);
        Node node = view != null ? view : fragments.get(identity);
        return node == null ? null : verified(identity, node).clone();
    }

    private Node verified(String blueId, Node value) {
        String identity = requireText(blueId, "blueId");
        Node node = Objects.requireNonNull(value, "fragment").clone();
        String actual = DirectBlueIdCalculator.calculateBlueId(node.clone());
        if (!identity.equals(actual)) {
            throw new IllegalStateException(
                    "Fragment identity evidence is invalid: expected "
                            + identity + " but got " + actual);
        }
        return node;
    }

    private static void requireSame(
            String blueId,
            Node proposed,
            Node current) {
        if (!NodeWireForm.get(proposed).equals(NodeWireForm.get(current))) {
            throw new IllegalStateException(
                    "Conflicting immutable fragment content for " + blueId);
        }
    }

    private void requireProfile(String profile) {
        if (!profileIdentity.equals(profile)) {
            throw new IllegalArgumentException(
                    "Fragmentation profile mismatch: " + profile);
        }
    }

    private static String admissionDomainIdentity(
            CoordinationEventAdmissionCacheKey key) {
        return lengthPrefixed(key.environmentIdentity())
                + lengthPrefixed(key.fragmentationProfileIdentity())
                + lengthPrefixed(key.languageGenerationIdentity())
                + lengthPrefixed(key.providerGenerationIdentity());
    }

    private static String lengthPrefixed(String value) {
        return value.length() + ":" + value;
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return checked;
    }
}
