package blue.coordination.internal;

import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.sdk.*;
import blue.language.processor.closure.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual rooted terminal/feeder decisions; no invented processor outcomes or complete-engine recovery claim. */
final class PublicationReceiptStorageCodecTest {
    private static final int MAX = 32 * 1024 * 1024;
    private static final ClosureProcessResultStorageCodec RESULTS = new ClosureProcessResultStorageCodec(MAX, 256);
    private static final ClosureExecutionEvidenceStorageCodec LANGUAGE = new ClosureExecutionEvidenceStorageCodec(MAX, 256);
    private static final DocumentSessionStorage.Limits LIMITS = new DocumentSessionStorage.Limits(MAX, 256, 256L * 1024 * 1024);

    @Test void actualPublishedAdmissionAndLiveResultKeepPrivateRowsAfterProducerClose() throws Exception {
        var objects = new Bytes(); var storage = new DocumentSessionStorage(objects, LIMITS);
        var publications = new PublicationReceiptStorageCodec(MAX, 256); var core = new CoreReceiptStorageCodec(MAX, 256);
        var rowRefs = new ResultRowStorageCodec(MAX);
        byte[] publication, admission, drain, eventRef, checkpointRef, resultBytes, originalInput;
        String resultAddress; Set<String> requiredTimelines;
        try (var f = new Fixture()) {
            var source = f.start("source.yaml", "rcp2/source");
            var admitted = f.engine.documents().publicationSnapshot().admissionReceipts().values().iterator().next();
            admission = core.encodeAdmission(admitted);
            assertArrayEquals(admission, core.encodeAdmission(core.decodeAdmission(admission)));
            var entry = f.append(source, "rcp2/source", "tick", "{}");
            var raw = f.engine.processRootInput(source.id(), f.entry(entry));
            drain = core.encodeDrain(raw, key -> { throw new AssertionError("LIVE fixture has no registered application " + key); });
            assertArrayEquals(drain, core.encodeDrain(core.decodeDrain(drain), key -> { throw new AssertionError(key); }));
            var attempt = raw.contractsAttemptsFor(entry.blueId()).get(0); assertTrue(attempt.published());
            var receipt = f.engine.documents().closurePublicationReceipt(attempt.publicationIdentity()).orElseThrow();
            var result = receipt.attempt().processResult(); resultBytes = RESULTS.encode(result);
            originalInput = LANGUAGE.encodeInvocation(receipt.rootedTerminalEvidence().input());
            requiredTimelines = receipt.rootedTerminalEvidence().requiredTimelineIds(); assertEquals(Set.of("rcp2/source"), requiredTimelines);
            publication = publications.encodePublication(receipt, storage::retainView);
            resultAddress = objects.retain(resultBytes);
            assertFalse(result.publicEvents().isEmpty()); assertFalse(result.checkpointWrites().isEmpty());
            eventRef = rowRefs.encodeOutbox(result.publicEvents().get(0), result, ignored -> resultAddress);
            checkpointRef = rowRefs.encodeCheckpoint(result.checkpointWrites().get(0), result, ignored -> resultAddress);
            var copiedResult = RESULTS.decode(resultBytes);
            assertThrows(CoordinationObjectStorageException.class, () -> rowRefs.encodeOutbox(copiedResult.publicEvents().get(0), result,
                    ignored -> { throw new AssertionError("A lookalike row must not prewrite a result"); }));
            assertThrows(CoordinationObjectStorageException.class, () -> rowRefs.encodeCheckpoint(copiedResult.checkpointWrites().get(0), result,
                    ignored -> { throw new AssertionError("A lookalike row must not prewrite a result"); }));
        }
        try (var scope = new DocumentSessionStorage(objects, LIMITS).openScope()) {
            var cold = publications.decodePublication(publication, scope);
            assertArrayEquals(publication, publications.encodePublication(cold, scope::addressOf));
            assertArrayEquals(resultBytes, RESULTS.encode(cold.attempt().processResult()));
            assertArrayEquals(originalInput, LANGUAGE.encodeInvocation(cold.rootedTerminalEvidence().input()));
            assertEquals(requiredTimelines, cold.rootedTerminalEvidence().requiredTimelineIds());
            assertArrayEquals(admission, core.encodeAdmission(core.decodeAdmission(admission)));
            assertArrayEquals(drain, core.encodeDrain(core.decodeDrain(drain), key -> { throw new AssertionError(key); }));
            var retainedResult = RESULTS.decode(objects.values.get(resultAddress));
            var read = new AtomicInteger();
            java.util.function.Function<String, ClosureProcessResult> open = address -> {
                assertEquals(resultAddress, address); read.incrementAndGet(); return retainedResult;
            };
            assertSame(retainedResult.publicEvents().get(0), rowRefs.decodeOutbox(eventRef, open));
            assertSame(retainedResult.checkpointWrites().get(0), rowRefs.decodeCheckpoint(checkpointRef, open));
            assertEquals(2, read.get());
            assertThrows(CoordinationObjectStorageException.class, () -> rowRefs.decodeCheckpoint(eventRef, open));
            assertEquals(2, read.get(), "Wrong row domain is rejected before selecting an original result");
            assertThrows(CoordinationObjectStorageException.class, () -> rowRefs.decodeOutbox(eventRef, address -> null));
            byte[] changed = eventRef.clone(); ByteBuffer.wrap(changed, changed.length - 4, 4).putInt(Integer.MAX_VALUE);
            assertThrows(CoordinationObjectStorageException.class, () -> rowRefs.decodeOutbox(changed, open));
            assertThrows(CoordinationObjectStorageException.class, () -> publications.decodePublication(Arrays.copyOf(publication, publication.length - 1), scope));
            assertThrows(CoordinationObjectStorageException.class, () -> core.decodeDrain(Arrays.copyOf(drain, drain.length + 1)));
            assertThrows(CoordinationObjectStorageException.class, () -> new CoreReceiptStorageCodec(128, 256).decodeAdmission(admission));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"zeroMatches", "wrongPath"})
    void actualCompletedHostRejectionRetainsTheSameOriginalDraftPlan(String operation) throws Exception {
        try (var f = new Fixture()) {
            var selected = f.rejection(operation); var before = selected.host().exact().json();
            var outcome = f.engine.contractsClosureAdapter().processAndPublish(selected.batch()).get(0);
            assertTrue(outcome.attempt().isComplete()); assertTrue(outcome.attempt().processResult().commits()); assertFalse(outcome.published());
            var original = f.engine.documents().closurePublicationReceipt(outcome.publicationIdentity()).orElseThrow();
            assertNotNull(original.rejectedDraftPlan()); assertTrue(original.attempt().processResult().totalGas() > 0);
            assertEquals(original.publicationIdentity(), f.engine.documents().storedState().closureApplicationResults().publication(
                    original.attempt().processResult().outputClosureIdentity(),
                    original.attempt().processResult().commitCompanion().companionIdentity()),
                    "Application selection uses PROCESS commits, not final publication commits");
            var storage = new DocumentSessionStorage(new Bytes(), LIMITS); var codec = new PublicationReceiptStorageCodec(MAX, 256);
            byte[] bytes = codec.encodePublication(original, storage::retainView); int reads = f.reads.get();
            try (var scope = storage.openScope()) {
                var cold = codec.decodePublication(bytes, scope); assertEquals(reads, f.reads.get());
                assertSame(cold.rejectedDraftPlan(), cold.rootedTerminalEvidence().storedState().managedDraftPlan());
                cold.rootedTerminalEvidence().requireRejectedDraftPlan(cold.rejectedDraftPlan(), cold.attempt().processResult(), cold.publicationIdentity());
                assertArrayEquals(bytes, codec.encodePublication(cold, scope::addressOf));
                assertArrayEquals(RESULTS.encode(original.attempt().processResult()), RESULTS.encode(cold.attempt().processResult()));
                var plan = cold.rejectedDraftPlan(); var equalCopy = new ContractsManagedDraftPlan(plan.targetDocumentId(), plan.targetEpoch(),
                        plan.targetBlueId(), plan.drafts(), plan.managedRequestFields(), plan.expectedOccurrences());
                assertThrows(IllegalArgumentException.class, () -> cold.rootedTerminalEvidence().requireRejectedDraftPlan(equalCopy,
                        cold.attempt().processResult(), cold.publicationIdentity()));
            }
            assertEquals(before, selected.host().exact().json()); assertTrue(f.engine.documents().find(selected.child()).isEmpty());
            verifyRejectedReceiptThroughColdStore(f, selected, original, bytes);
        }
    }

    private static void verifyRejectedReceiptThroughColdStore(Fixture f, RejectedOperation selected,
            ContractsClosurePublicationReceipt original, byte[] receiptBytes) {
        var objects = new Bytes();
        var maps = new PersistentMapStorage.Limits(40 * 1024 * 1024, 4096, MAX, 4096, 16);
        var logs = new PersistentAppendLogStorage.Limits(40 * 1024 * 1024, MAX, 4096, 8);
        var storage = new StoredDocumentStore(objects, maps, logs, LIMITS);
        var state = f.engine.documents().storedState();
        assertDoesNotThrow(() -> state.withSessions(state.sessions(), state.lineageIndex(),
                state.componentIndex(), state.componentIndexGeneration()), "Eager restoration checks the same rejected input");
        var partition = storage.retainPartition(state);
        String hostRecord = new DocumentSessionStorage(objects, LIMITS).retain(state.sessions().get(selected.host().id()));
        var phases = f.engine.metricsSnapshot().phaseNanos(); int providerReads = f.reads.get();
        var coldObjects = objects.copy();
        try (var cold = new StoredDocumentStore(coldObjects, maps, logs, LIMITS).open(partition, 8, 256)) {
            assertEquals(hostRecord, new DocumentSessionStorage(coldObjects, LIMITS)
                    .retain(cold.state().sessions().get(selected.host().id())));
            assertNull(cold.state().sessions().get(selected.child()), "The rejected draft was never published");
            // Opening only descriptors is insufficient: force the actual selected-row history validator.
            var restored = assertDoesNotThrow(() -> cold.state().closurePublicationReceipts().get(original.publicationIdentity()),
                    "A charged host rejection must validate its retained pre-state, not unpublished result heads");
            assertNotNull(restored);
            assertFalse(restored.commits()); assertTrue(restored.attempt().processResult().commits());
            assertSame(restored.rejectedDraftPlan(), restored.rootedTerminalEvidence().storedState().managedDraftPlan());
            restored.rootedTerminalEvidence().requireRejectedDraftPlan(restored.rejectedDraftPlan(),
                    restored.attempt().processResult(), restored.publicationIdentity());
            assertArrayEquals(receiptBytes, new PublicationReceiptStorageCodec(MAX, 256)
                    .encodePublication(restored, cold.viewScope()::addressOf));
            assertArrayEquals(RESULTS.encode(original.attempt().processResult()), RESULTS.encode(restored.attempt().processResult()));
            assertArrayEquals(LANGUAGE.encodeInvocation(original.rootedTerminalEvidence().input()),
                    LANGUAGE.encodeInvocation(restored.rootedTerminalEvidence().input()));
            assertEquals(original.attempt().processResult().totalGas(), restored.attempt().processResult().totalGas());
            var unchanged = cold.stage(cold.state());
            for (var root : StoredDocumentStore.Root.values()) assertArrayEquals(partition.root(root), unchanged.root(root),
                    "Reading the rejection must not change any semantic store family: " + root);
        }
        assertEquals(providerReads, f.reads.get());
        assertEquals(phases, f.engine.metricsSnapshot().phaseNanos(), "Cold receipt inspection never executes PROCESS");
        assertEquals(hostRecord, new DocumentSessionStorage(objects, LIMITS).retain(state.sessions().get(selected.host().id())));
    }

    @ParameterizedTest @ValueSource(strings = {"missingTarget", "foreignTarget", "missingFence", "changedFence",
            "changedPlanEpoch", "missingHistory", "changedAdmission", "changedContextHistory", "forgedDraftFence"})
    void rejectedReceiptStillRequiresItsExactOriginalRetainedAuthority(String corruption) throws Exception {
        try (var f = new Fixture()) {
            var selected = f.rejection("wrongPath");
            var outcome = f.engine.contractsClosureAdapter().processAndPublish(selected.batch()).get(0);
            var original = f.engine.documents().closurePublicationReceipt(outcome.publicationIdentity()).orElseThrow();
            var sessions = new LinkedHashMap<>(f.engine.documents().storedState().sessions());
            var terminal = original.rootedTerminalEvidence().storedState(); var rooted = terminal.rooted();
            var fences = new LinkedHashMap<>(rooted.publicationFences());
            var histories = new LinkedHashMap<>(rooted.histories()); var plan = original.rejectedDraftPlan();
            var target = plan.targetDocumentId(); var history = histories.get(target);
            switch (corruption) {
                case "missingTarget" -> sessions.remove(target);
                case "foreignTarget" -> sessions.put(target, f.engine.documents().require(f.start("source.yaml", "rcp2/source").id()));
                case "missingFence" -> fences.remove(target);
                case "changedFence" -> fences.put(target, new RootedInvocationEvidence.PublicationFence(
                        new InMemoryDocumentStore.DocumentHead(plan.targetEpoch(), plan.drafts().get(selected.child()).initial().blueId()),
                        fences.get(target).graphGeneration()));
                case "changedPlanEpoch" -> plan = new ContractsManagedDraftPlan(target, plan.targetEpoch() + 1,
                        plan.targetBlueId(), plan.drafts(), plan.managedRequestFields(), plan.expectedOccurrences());
                case "missingHistory" -> histories.remove(target);
                case "changedAdmission" -> histories.put(target, new RootedDocumentHistory(history.descriptor(), history.identity(),
                        "another-admission", history.admissionCompanionIdentity(), history.admissionSources()));
                case "changedContextHistory" -> {
                    var descriptor = new LinkedHashMap<>(history.descriptor()); descriptor.put("admission", Map.of("mode", "FULL_HISTORY"));
                    histories.put(target, new RootedDocumentHistory(descriptor, RootedProcessingContext.historyBasisIdentity(descriptor),
                            history.admissionInvocationIdentity(), history.admissionCompanionIdentity(), history.admissionSources()));
                }
                case "forgedDraftFence" -> fences.put(selected.child(), fences.get(target));
                default -> fail(corruption);
            }
            var changedRooted = new RootedInvocationEvidence(rooted.context(), rooted.deliveryBasisIdentity(),
                    rooted.invocationIdentity(), rooted.baseInvocationIdentity(), histories, rooted.historicalOrigin(), rooted.historicalWork(), fences);
            var changedTerminal = RootedTerminalEvidence.restoreStored(new RootedTerminalEvidence.StoredState(terminal.input(), plan,
                    changedRooted, terminal.executedInvocationIdentity(), terminal.historicalWorkIdentity(), terminal.historicalWork(), terminal.requiredTimelineIds()));
            // These remain valid typed receipt envelopes: the selected-store crosslink guard must reject them.
            var changed = new ContractsClosurePublicationReceipt(original.publicationIdentity(), original.documentIds(), original.attempt(),
                    original.automaticRetryCount(), original.managedSurfaceEvidence(), plan, changedTerminal);
            assertArrayEquals(RESULTS.encode(original.attempt().processResult()), RESULTS.encode(changed.attempt().processResult()));
            assertThrows(IllegalArgumentException.class,
                    () -> InMemoryDocumentStore.StoreState.requireRetainedClosureReceipt(changed, sessions, "Rejected control"), corruption);
            assertDoesNotThrow(() -> InMemoryDocumentStore.StoreState.requireRetainedClosureReceipt(original,
                    f.engine.documents().storedState().sessions(), "Unchanged rejection"));
        }
    }

    @Test void rejectedTargetCanBeAnExactRetainedSameEpochRepresentation() throws Exception {
        String operations = resource("rooted-managed-rejections", "host.yaml");
        String parent = "orders: {}\n" + resource("rooted", "parent.yaml")
                .replace("    paths:\n", "    collectionPaths:\n    - /orders\n    paths:\n")
                + operations.substring(operations.indexOf("  ownerChannel:")).replace("timelineId: rooted/rejected-birth", "timelineId: rcp2/parent");
        try (var f = new ManagedRepresentationVerificationMemoTest.Scenario(parent)) {
            var host = f.parent; var session = f.engine.documents().require(host.id());
            var target = host.snapshot();
            assertEquals(0L, target.epoch());
            assertNotEquals(session.revision(0).after().blueId(), target.blueId());
            assertTrue(session.retainsStoredPosition(0, target.blueId()));
            assertFalse(session.retainsStoredPosition(1, target.blueId()), "A matching body at another epoch is not authority");
            var timeline = f.blue.timelines().register("rcp2/parent", "alice");
            var initialChild = f.blue.values().yaml(resource("rooted-managed-rejections", "child.yaml"));
            var child = f.blue.documents().draft(DocumentId.of(initialChild.blueId()), initialChild);
            var entry = f.blue.operations().on(host).from(timeline).call("wrongPath").through("ownerChannel")
                    .request(request -> request.managed("order", child)).expectOccurrence("/orders/expected", child).submit();
            var before = f.state();
            var batch = f.engine.contractsClosureAdapter().captureRoot(host.id(), f.engine.auditTimelineEntry(entry.blueId()).orElseThrow());
            var outcome = f.engine.contractsClosureAdapter().processAndPublish(batch).get(0);
            assertFalse(outcome.published()); assertTrue(outcome.attempt().processResult().commits());
            var receipt = f.engine.documents().closurePublicationReceipt(outcome.publicationIdentity()).orElseThrow();
            assertNotNull(receipt.rejectedDraftPlan()); assertTrue(receipt.attempt().processResult().totalGas() > 0L);
            assertEquals(target.blueId(), receipt.rejectedDraftPlan().targetBlueId());
            var state = f.engine.documents().storedState();
            assertDoesNotThrow(() -> state.withSessions(state.sessions(), state.lineageIndex(), state.componentIndex(), state.componentIndexGeneration()));
            var objects = new Bytes();
            var maps = new PersistentMapStorage.Limits(40 * 1024 * 1024, 4096, MAX, 4096, 16);
            var logs = new PersistentAppendLogStorage.Limits(40 * 1024 * 1024, MAX, 4096, 8);
            var partition = new StoredDocumentStore(objects, maps, logs, LIMITS).retainPartition(state);
            try (var cold = new StoredDocumentStore(objects.copy(), maps, logs, LIMITS).open(partition, 8, 256)) {
                var restored = cold.state().closurePublicationReceipts().get(receipt.publicationIdentity());
                assertArrayEquals(RESULTS.encode(receipt.attempt().processResult()), RESULTS.encode(restored.attempt().processResult()));
                assertArrayEquals(LANGUAGE.encodeInvocation(receipt.rootedTerminalEvidence().input()),
                        LANGUAGE.encodeInvocation(restored.rootedTerminalEvidence().input()));
                assertEquals(target.blueId(), cold.state().sessions().get(host.id()).currentRepresentation().blueId());
                assertNull(cold.state().sessions().get(child.id()));
            }
            assertEquals(before, f.state(), "The rejected speculation changes no published history, representation, event or receipt gas");
        }
    }

    @Test void actualSuspendedWrongBirthRetainsIssuedDemandAndSharedPlanWithoutContinuingParent() throws Exception {
        try (var f = new Fixture()) {
            var selected = f.rejection("wrongExactState"); var before = selected.host().exact().json();
            var outcome = f.engine.contractsClosureAdapter().processAndPublish(selected.batch()).get(0);
            var original = outcome.rejectedBirth(); assertNotNull(original); assertFalse(outcome.attempt().isComplete());
            var storage = new DocumentSessionStorage(new Bytes(), LIMITS); var codec = new PublicationReceiptStorageCodec(MAX, 256);
            byte[] bytes = codec.encodeRejection(original, storage::retainView); int reads = f.reads.get();
            try (var scope = storage.openScope()) {
                var cold = codec.decodeRejection(bytes, scope); var state = cold.storedState(); assertEquals(reads, f.reads.get());
                assertSame(state.selected().managedDraftPlan(), state.executed().managedDraftPlan());
                for (var issue : state.issues()) assertTrue(state.attempt().resourceDemands().stream().anyMatch(demand -> demand == issue.demand()));
                assertArrayEquals(bytes, codec.encodeRejection(cold, scope::addressOf));
                cold.requireSameObligation(selected.batch().invocations().get(0)); cold.requireCurrentFences(f.engine.documents());
                assertEquals(original.terminalKey(), cold.terminalKey());
                var issued = (ManagedOccurrenceEvidenceDemand) state.issues().get(0).demand();
                var copy = new ManagedOccurrenceEvidenceDemand(issued.demandIdentity(), issued.logicalCauseIdentity(), issued.inputClosureIdentity(),
                        issued.inputGraphGeneration(), issued.sourceDocumentId(), issued.sourcePath(), issued.processEmbeddedDeclarationIdentity(),
                        issued.suppliedValueBlueId(), issued.demandOrdinal(), issued.suppliedExactValue().orElseThrow());
                var changed = new ManagedOccurrenceResolver.UnresolvedDemand(copy, state.issues().get(0).status(), state.issues().get(0).diagnostic());
                assertThrows(IllegalArgumentException.class, () -> RootedDeclaredBirthRejection.restoreStored(new RootedDeclaredBirthRejection.StoredState(
                        state.selected(), state.executed(), state.attempt(), List.of(changed), state.retries())));
                assertThrows(CoordinationObjectStorageException.class, () -> codec.decodeRejection(Arrays.copyOf(bytes, bytes.length + 1), scope));
            }
            var observed = f.engine.processRootInput(selected.host().id(), selected.batch().entry());
            var replay = observed.contractsAttemptsFor(selected.batch().entry().blueId()).get(0);
            assertTrue(replay.replayed()); assertFalse(replay.attempt().isComplete());
            var core = new CoreReceiptStorageCodec(MAX, 256);
            byte[] drain = core.encodeDrain(observed, key -> { throw new AssertionError(key); });
            int retainedReads = f.reads.get(); var restoredDrain = core.decodeDrain(drain);
            assertEquals(retainedReads, f.reads.get());
            assertArrayEquals(drain, core.encodeDrain(restoredDrain, key -> { throw new AssertionError(key); }));
            var restoredAttempt = restoredDrain.contractsAttemptsFor(selected.batch().entry().blueId()).get(0).attempt();
            assertEquals(replay.attempt().resourceDemands().size(), restoredAttempt.resourceDemands().size());
            for (int index = 0; index < restoredAttempt.resourceDemands().size(); index++)
                assertArrayEquals(LANGUAGE.encodeResourceDemand(replay.attempt().resourceDemands().get(index)),
                        LANGUAGE.encodeResourceDemand(restoredAttempt.resourceDemands().get(index)));
            assertEquals(before, selected.host().exact().json()); assertTrue(f.engine.documents().find(selected.child()).isEmpty());
        }
    }

    @Test void actualTightGasRollbackRetainsOriginalRootedInputWithoutInventingProjection() throws Exception {
        try (var f = new Fixture()) {
            var source = f.start("source.yaml", "rcp2/source"); var entry = f.append(source, "rcp2/source", "tick", "{}");
            var captured = f.engine.contractsClosureAdapter().captureRoot(source.id(), f.entry(entry)).invocations().get(0);
            var calculated = new BlueClosureContracts(f.engine.runtime().documentProcessor()).processClosure(captured.input()).processResult();
            assertTrue(calculated.commits()); long budget = calculated.totalGas() - 1; assertTrue(budget > 0);
            var before = source.exact().json();
            var raw = f.engine.processRootInput(source.id(), f.entry(entry), ContractsExecutionPolicy.exactSharedGas(budget, "stored-terminal-tight"));
            var attempted = raw.contractsAttemptsFor(entry.blueId()).get(0); assertFalse(attempted.attempt().processResult().commits());
            var original = f.engine.documents().closurePublicationReceipt(attempted.publicationIdentity()).orElseThrow();
            assertTrue(original.attempt().processResult().rollbackToInput()); assertNull(original.attempt().processResult().rootedProjection());
            var codec = new PublicationReceiptStorageCodec(MAX, 256); var storage = new DocumentSessionStorage(new Bytes(), LIMITS);
            byte[] bytes = codec.encodePublication(original, storage::retainView); int reads = f.reads.get();
            try (var scope = storage.openScope()) {
                var restored = codec.decodePublication(bytes, scope); assertEquals(reads, f.reads.get());
                assertArrayEquals(bytes, codec.encodePublication(restored, scope::addressOf));
                assertArrayEquals(RESULTS.encode(original.attempt().processResult()), RESULTS.encode(restored.attempt().processResult()));
                assertArrayEquals(LANGUAGE.encodeInvocation(original.rootedTerminalEvidence().input()),
                        LANGUAGE.encodeInvocation(restored.rootedTerminalEvidence().input()));
            }
            assertEquals(before, source.exact().json());
        }
    }

    private record RejectedOperation(DocumentHandle host, DocumentId child, ContractsClosureAdapter.FrozenBatch batch) { }
    private static final class Fixture implements AutoCloseable {
        final AtomicInteger reads = new AtomicInteger();
        final BlueCoordination blue = BlueCoordination.builder().exactNodeProvider(id -> { reads.incrementAndGet(); return Optional.empty(); }).build();
        final DefaultCoordinationEngine engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
        final Map<String, TimelineHandle> timelines = new HashMap<>();
        DocumentHandle start(String resource, String timeline) throws Exception {
            timelines.computeIfAbsent(timeline, key -> blue.timelines().register(key, "alice"));
            return blue.documents().admitStaticProcessEmbedded(resource("rooted", resource), ActivationPolicy.fromNow()).document("root");
        }
        EntryHandle append(DocumentHandle target, String timeline, String operation, String request) {
            return blue.operations().on(target).from(timelines.get(timeline)).call(operation).through("owner").requestYaml(request).submit();
        }
        blue.coordination.api.TimelineEntry entry(EntryHandle entry) { return engine.auditTimelineEntry(entry.blueId()).orElseThrow(); }
        RejectedOperation rejection(String operation) throws Exception {
            var timeline = blue.timelines().register("rooted/rejected-birth", "alice");
            var host = blue.documents().admit(ManagedDocument.yaml(DocumentId.of("rooted-rejected-birth-host"), resource("rooted-managed-rejections", "host.yaml"))
                    .publicRoot().fromNow());
            var childId = DocumentId.of("rooted-rejected-birth-child"); String childYaml = resource("rooted-managed-rejections", "child.yaml");
            var child = blue.documents().draft(childId, blue.values().yaml(childYaml));
            var wrong = blue.values().yaml(childYaml.replace("state: draft", "state: altered"));
            var entry = blue.operations().on(host).from(timeline).call(operation).through("ownerChannel")
                    .request(request -> { request.managed("order", child); if (operation.equals("wrongExactState")) request.exact("wrong", wrong); })
                    .expectOccurrence("/orders/expected", child).submit();
            return new RejectedOperation(host, childId, engine.contractsClosureAdapter().captureRoot(host.id(), entry(entry)));
        }
        @Override public void close() { blue.close(); }
    }
    private static final class Bytes implements CoordinationImmutableObjectStore {
        final Map<String, byte[]> values = new HashMap<>();
        Bytes copy() {
            var copy = new Bytes(); values.forEach((key, value) -> copy.values.put(key, value.clone())); return copy;
        }
        String retain(byte[] bytes) {
            try { String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); putIfAbsent(key, bytes); return key; }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
        @Override public byte[] putIfAbsent(String key, byte[] bytes) {
            var old = values.putIfAbsent(key, bytes.clone());
            if (old != null && !Arrays.equals(old, bytes)) throw new CoordinationObjectStorageException("Conflict");
            return (old == null ? bytes : old).clone();
        }
        @Override public Optional<byte[]> get(String key, int max) {
            var value = values.get(key); if (value != null && value.length > max) throw new CoordinationObjectStorageException("Oversized");
            return Optional.ofNullable(value == null ? null : value.clone());
        }
    }
    private static String resource(String directory, String name) throws Exception {
        try (var stream = PublicationReceiptStorageCodecTest.class.getResourceAsStream("/" + directory + "/" + name)) {
            return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
