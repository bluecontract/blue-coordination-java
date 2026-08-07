package blue.coordination.examples.support;

import blue.coordination.engine.api.CoordinationTransition;
import blue.coordination.engine.api.LocalityDiagnostics;
import blue.coordination.engine.memory.DemoTransition;
import blue.coordination.processor.CoordinationPreparedDelivery;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-runtime evidence collector for executable MyOS examples.
 *
 * <p>Record methods append only to this runtime. Close writes one immutable
 * shard, and a single JVM shutdown hook publishes the canonical combined
 * report after the complete test campaign.</p>
 */
final class MyOsDemoEvidence {

    static final String RUNTIME_EVIDENCE_PROPERTY =
            "myos.demo.runtimeEvidence";
    static final String RUNTIME_SHARDS_PROPERTY =
            "myos.demo.runtimeEvidenceShards";
    private static final String UNASSIGNED_EXAMPLE = "unassigned";
    private static final Object MONITOR = new Object();

    private static boolean initialized;
    private static long runtimeSequence;
    private static MyOsEvidencePublisher publisher;

    private final String exampleId;
    private final String caseId;
    private final String runtimeId;
    private final List<Map<String, Object>> admissions = new ArrayList<>();
    private final List<Map<String, Object>> transitions = new ArrayList<>();
    private final List<Map<String, Object>> observations = new ArrayList<>();

    private long transitionSequence;
    private long observationSequence;
    private boolean flushed;

    private MyOsDemoEvidence(
            String exampleId,
            String caseId,
            String runtimeId) {
        this.exampleId = exampleId;
        this.caseId = caseId;
        this.runtimeId = runtimeId;
    }

    static MyOsDemoEvidence begin(String requestedExampleId) {
        return begin(requestedExampleId, requestedExampleId);
    }

    static MyOsDemoEvidence begin(
            String requestedExampleId,
            String requestedCaseId) {
        String exampleId = normalizeExampleId(requestedExampleId);
        String caseId = normalizeCaseId(requestedCaseId);
        synchronized (MONITOR) {
            initializeReports();
            runtimeSequence = Math.addExact(runtimeSequence, 1L);
            return new MyOsDemoEvidence(
                    exampleId,
                    caseId,
                    exampleId + "/" + caseId + "#" + runtimeSequence);
        }
    }

    static String unassignedExampleId() {
        return UNASSIGNED_EXAMPLE;
    }

    String exampleId() {
        return exampleId;
    }

    String caseId() {
        return caseId;
    }

    synchronized void recordDocument(
            MyOsDemoDocument document,
            String canonicalIdentityInputBlueId,
            MyOsInitializationCoordinator.Receipt initialization) {
        requireOpen();
        MyOsDemoDocument checked = Objects.requireNonNull(
                document, "document");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("exampleId", exampleId);
        record.put("caseId", caseId);
        record.put("runtimeId", runtimeId);
        record.put("documentKey", checked.key());
        record.put("sourceDocumentBlueId", checked.initialBlueId());
        record.put(
                "canonicalIdentityInputBlueId",
                requireText(
                        canonicalIdentityInputBlueId,
                        "canonicalIdentityInputBlueId"));
        record.put("sessionId", checked.sessionId().value());
        MyOsInitializationCoordinator.Receipt receipt =
                Objects.requireNonNull(initialization, "initialization");
        if (!receipt.sessionId().equals(checked.sessionId().value())
                || !receipt.inputDocumentBlueId().equals(
                        checked.initialBlueId())
                || receipt.status()
                != MyOsInitializationCoordinator.TerminalStatus.SUCCEEDED) {
            throw new IllegalArgumentException(
                    "Initialization receipt does not bind the admission");
        }
        record.put("logicalDocumentId", receipt.identity().logicalId());
        record.put("initializationAttempt", receipt.attempt());
        record.put("initializationStatus", receipt.status().name());
        record.put(
                "initializationInputBlueId",
                receipt.inputDocumentBlueId());
        record.put(
                "initializationResultRootBlueId",
                receipt.resultRootBlueId());
        admissions.add(record);
    }

    synchronized void recordIndexedTransition(
            MyOsDemoDocument document,
            MyOsDemoEntry entry,
            DemoTransition delivery) {
        requireOpen();
        MyOsDemoDocument checkedDocument = Objects.requireNonNull(
                document, "document");
        MyOsDemoEntry checkedEntry = Objects.requireNonNull(entry, "entry");
        DemoTransition checkedDelivery = Objects.requireNonNull(
                delivery, "delivery");
        CoordinationTransition transition = checkedDelivery.transition();
        CoordinationPreparedDelivery prepared = transition.plan()
                .preparedDelivery();
        LocalityDiagnostics locality = transition.locality();

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("exampleId", exampleId);
        record.put("caseId", caseId);
        record.put("runtimeId", runtimeId);
        transitionSequence = Math.addExact(transitionSequence, 1L);
        record.put("transitionOrdinal", transitionSequence);
        record.put("documentKey", checkedDocument.key());
        record.put("sessionId", checkedDocument.sessionId().value());
        record.put("entryBlueId", checkedEntry.blueId());
        record.put("timelineId", checkedEntry.timelineId());
        record.put("operation", checkedEntry.operation());
        record.put("processorStatus", transition.status().wireValue());
        record.put(
                "selectedOccurrenceOrder",
                new ArrayList<>(prepared.preselectedOccurrenceOrder()));
        record.put(
                "selectedScopeOrder",
                new ArrayList<>(
                        prepared.selectedScopeChainIdentities().keySet()));
        record.put(
                "selectedScopeChains",
                copyScopeChains(prepared.selectedScopeChainIdentities()));
        record.put(
                "backendLoadedBlueIds",
                new ArrayList<>(locality.backendLoadedBlueIds()));
        record.put(
                "causallySelectedBlueIds",
                new ArrayList<>(locality.causallySelectedBlueIds()));
        record.put("batchCount", locality.batchCount());
        record.put("loadedBytes", locality.loadedBytes());
        record.put("forbiddenReadCount", locality.forbiddenReadCount());
        record.put("fallbackReadCount", locality.fallbackReadCount());

        Map<String, Object> cas = new LinkedHashMap<>();
        cas.put("status", checkedDelivery.commitOutcome().status().name());
        cas.put("committed", checkedDelivery.commitOutcome().committed());
        cas.put(
                "transitionIdentity",
                checkedDelivery.commitOutcome().transitionIdentity());
        record.put("cas", cas);
        transitions.add(record);
    }

    synchronized void recordCheckpoint(
            String name,
            MyOsDemoCheckpoint checkpoint) {
        requireOpen();
        MyOsDemoCheckpoint checked = Objects.requireNonNull(
                checkpoint, "checkpoint");
        Map<String, Object> record = ownedObservation("checkpoint");
        record.put("name", requireText(name, "name"));
        record.put("documentCount", checked.documentCount());
        record.put("timelineCount", checked.timelineCount());
        record.put("journalEntryCount", checked.journalEntryCount());
        record.put("physicalFragmentCount", checked.physicalFragmentCount());
        record.put("stateFingerprint", checked.stateFingerprint());
        observations.add(record);
    }

    synchronized void recordPhysicalSlice(
            String rootDocumentKey,
            MyOsDocumentSlice slice,
            int fullFragmentCount,
            long storeSingleReads,
            long storeBatchReads,
            long storeRequestedIdentities) {
        requireOpen();
        MyOsDocumentSlice checked = Objects.requireNonNull(slice, "slice");
        if (fullFragmentCount < checked.physicalSlice().fragmentCount()) {
            throw new IllegalArgumentException(
                    "fullFragmentCount is smaller than the selected slice");
        }
        Map<String, Object> record = ownedObservation("physical-slice");
        record.put(
                "rootDocumentKey",
                requireText(rootDocumentKey, "rootDocumentKey"));
        record.put(
                "absolutePath",
                requireText(checked.absolutePath(), "absolutePath"));
        record.put(
                "owningRootSessionId",
                checked.owningRootSessionId().value());
        record.put(
                "selectedLogicalDocumentId",
                checked.logicalDocument().logicalId());
        record.put(
                "expectedSelectedRootBlueId",
                checked.currentLogicalRootBlueId());
        record.put(
                "actualSelectedRootBlueId",
                checked.physicalSlice().selectedRootBlueId());
        List<Map<String, Object>> relationshipChain = new ArrayList<>();
        for (MyOsTopologyLink link : checked.relationshipChain()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("parentLogicalId", link.parent().logicalId());
            item.put("relativePath", link.relativePath());
            item.put("childLogicalId", link.child().logicalId());
            relationshipChain.add(item);
        }
        record.put("relationshipChain", relationshipChain);
        record.put(
                "selectedFragmentBlueIds",
                new ArrayList<>(checked.selectedFragmentBlueIds()));
        record.put(
                "loadedFragmentCount",
                checked.physicalSlice().fragmentCount());
        record.put("fullFragmentCount", fullFragmentCount);
        Map<String, Object> store = new LinkedHashMap<>();
        store.put(
                "singleReads",
                nonNegative(storeSingleReads, "storeSingleReads"));
        store.put(
                "batchReads",
                nonNegative(storeBatchReads, "storeBatchReads"));
        store.put(
                "requestedIdentities",
                nonNegative(
                        storeRequestedIdentities,
                        "storeRequestedIdentities"));
        record.put("store", store);
        observations.add(record);
    }

    synchronized boolean flush(
            MyOsMeasuredWork work,
            int documentCount,
            int timelineCount,
            int journalEntryCount,
            int storedEventInventoryCount) {
        if (flushed) {
            return false;
        }
        MyOsEvidencePublisher activePublisher;
        synchronized (MONITOR) {
            activePublisher = publisher;
        }
        if (activePublisher == null) {
            flushed = true;
            return false;
        }
        observations.add(runtimeSummary(
                Objects.requireNonNull(work, "work"),
                documentCount,
                timelineCount,
                journalEntryCount,
                storedEventInventoryCount));
        boolean written = activePublisher.writeShard(
                exampleId,
                caseId,
                runtimeId,
                admissions,
                transitions,
                observations);
        flushed = true;
        return written;
    }

    private Map<String, Object> runtimeSummary(
            MyOsMeasuredWork work,
            int documentCount,
            int timelineCount,
            int journalEntryCount,
            int storedEventInventoryCount) {
        Map<String, Object> record = ownedObservation("runtime-summary");
        Map<String, Object> host = new LinkedHashMap<>();
        host.put("sourceParses", work.sourceParses());
        host.put("documentInitializations", work.documentInitializations());
        host.put("eventPreparations", work.eventPreparations());
        host.put("eventSplits", work.eventSplits());
        host.put("routeIndexProbes", work.routeIndexProbes());
        host.put("fanoutPages", work.fanoutPages());

        Map<String, Object> engine = new LinkedHashMap<>();
        engine.put("plans", work.engine().plans());
        engine.put("bundleLoads", work.engine().bundleLoads());
        engine.put("bundleBatches", work.engine().bundleBatches());
        engine.put(
                "loadedFragmentIdentities",
                work.engine().loadedFragmentIdentities());
        engine.put("loadedBytes", work.engine().loadedBytes());
        engine.put(
                "processCompletions",
                work.engine().processCompletions());
        engine.put("commitAttempts", work.engine().commitAttempts());
        engine.put("committed", work.engine().committed());
        engine.put("alreadyCommitted", work.engine().alreadyCommitted());
        engine.put("conflicts", work.engine().conflicts());

        Map<String, Object> store = new LinkedHashMap<>();
        store.put("singleReads", work.storeSingleReads());
        store.put("batchReads", work.storeBatchReads());
        store.put(
                "requestedIdentities",
                work.storeRequestedIdentities());

        Map<String, Object> measuredWork = new LinkedHashMap<>();
        measuredWork.put("host", host);
        measuredWork.put("engine", engine);
        measuredWork.put("store", store);
        record.put("work", measuredWork);

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("documentCount", nonNegative(documentCount, "documentCount"));
        state.put("timelineCount", nonNegative(timelineCount, "timelineCount"));
        state.put(
                "journalEntryCount",
                nonNegative(journalEntryCount, "journalEntryCount"));
        state.put(
                "storedEventInventoryCount",
                nonNegative(
                        storedEventInventoryCount,
                        "storedEventInventoryCount"));
        record.put("state", state);
        return record;
    }

    private Map<String, Object> ownedObservation(String kind) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("exampleId", exampleId);
        record.put("caseId", caseId);
        record.put("runtimeId", runtimeId);
        String checkedKind = requireText(kind, "kind");
        record.put("kind", checkedKind);
        observationSequence = Math.addExact(observationSequence, 1L);
        record.put(
                "observationId",
                checkedKind + "#" + observationSequence);
        return record;
    }

    private void requireOpen() {
        if (flushed) {
            throw new IllegalStateException(
                    "Runtime evidence has already been flushed: " + runtimeId);
        }
    }

    private static Map<String, List<String>> copyScopeChains(
            Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    private static void initializeReports() {
        if (initialized) {
            return;
        }
        initialized = true;
        String configuredCombined = configured(
                RUNTIME_EVIDENCE_PROPERTY);
        String configuredShards = configured(RUNTIME_SHARDS_PROPERTY);
        if (configuredCombined == null && configuredShards == null) {
            return;
        }

        Path combined = configuredCombined == null
                ? deriveCombined(Paths.get(configuredShards))
                : Paths.get(configuredCombined);
        Path shards = configuredShards == null
                ? deriveShards(combined)
                : Paths.get(configuredShards);
        publisher = new MyOsEvidencePublisher(shards, combined);
        MyOsEvidencePublisher suitePublisher = publisher;
        Runtime.getRuntime().addShutdownHook(new Thread(
                suitePublisher::publishCombinedOnce,
                "myos-demo-evidence-publisher"));
    }

    private static Path deriveCombined(Path shards) {
        Path absolute = shards.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IllegalArgumentException(
                    "Runtime shard directory must have a parent: " + shards);
        }
        return parent.resolve("runtime-evidence.json");
    }

    private static Path deriveShards(Path combined) {
        Path absolute = combined.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IllegalArgumentException(
                    "Runtime evidence destination must have a parent: "
                            + combined);
        }
        return parent.resolve("runtime-shards");
    }

    private static String configured(String property) {
        String value = System.getProperty(property);
        return value == null || value.trim().isEmpty() ? null : value;
    }

    private static String normalizeExampleId(String value) {
        return normalizeIdentifier(value, "exampleId");
    }

    private static String normalizeCaseId(String value) {
        return normalizeIdentifier(value, "caseId");
    }

    private static String normalizeIdentifier(String value, String label) {
        String checked = requireText(value, label).trim();
        if (!checked.equals(value)) {
            throw new IllegalArgumentException(
                    label + " cannot have surrounding whitespace");
        }
        return checked;
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    private static int nonNegative(int value, String label) {
        if (value < 0) {
            throw new IllegalArgumentException(label + " must be non-negative");
        }
        return value;
    }

    private static long nonNegative(long value, String label) {
        if (value < 0L) {
            throw new IllegalArgumentException(label + " must be non-negative");
        }
        return value;
    }
}
