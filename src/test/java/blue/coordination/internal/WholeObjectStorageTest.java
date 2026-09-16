package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.language.identity.BlueIds;
import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.processor.NoncommittingExecutionException;
import blue.language.provider.CyclicSetProof;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Pinned external exact-object evidence with attempt-local rollback overlays. */
final class WholeObjectStorageTest {
    private final WholeObjectStorageFixture fixture = new WholeObjectStorageFixture();

    @Test void freshReadLoadsSelectedObjectOnlyAndDoesNotBecomePendingMutation() throws Exception {
        var writer = new WholeObjectStore(new EngineMetrics());
        for (int i = 0; i < 100; i++) writer.put(new Node().value("unrelated-" + i), "unrelated");
        ExactValue selected = writer.put(new Node().value("selected"), "selected");
        fixture.publish(writer.changes());
        var backing = fixture.view();
        var restored = new WholeObjectStore(new EngineMetrics(), backing);
        assertEquals(0, backing.reads);
        assertEquals(101, restored.size());
        assertEquals(0, backing.reads);
        assertExact(selected, restored.require(selected.blueId()));
        assertEquals(Set.of(selected.blueId()), backing.readKeys);
        assertTrue(restored.changes().entries().isEmpty());
        assertTrue(restored.changes().proofs().isEmpty());
        assertFalse(restored.contains("absent"));
        assertEquals(Set.of(selected.blueId()), backing.readKeys);
    }

    @Test void canonicalAndProviderViewsStaySeparateAcrossColdPublicationAndRollback() throws Exception {
        var writer = new WholeObjectStore(new EngineMetrics());
        ExactValue child = writer.put(new Node().properties("status", new Node().value("confirmed")), "child");
        ExactValue shell = writer.put(new Node().properties("child", child.referenceNode()), "shell");
        ExactValue materialized = ExactValue.verified(new Node().properties("child", child.copyNode()));
        fixture.publish(writer.changes());
        var originalView = fixture.view();
        var original = new WholeObjectStore(new EngineMetrics(), originalView);
        var attempt = new WholeObjectStore(new EngineMetrics(), fixture.view());
        var outer = attempt.mark();
        attempt.preferCanonicalRepresentation(materialized.frozen(), "materialized");
        assertExact(materialized, attempt.require(shell.blueId()));
        assertExact(shell, original.require(shell.blueId()));
        var inner = attempt.mark();
        ExactValue transientValue = attempt.put(new Node().value("transient"), "transient");
        attempt.preferProviderRepresentation(materialized.frozen(), "expanded-provider");
        attempt.rollbackTo(inner);
        assertFalse(attempt.contains(transientValue.blueId()));
        assertEquals(child.blueId(), attempt.fetchByBlueId(shell.blueId()).get(0)
                .getProperties().get("child").getBlueId());
        assertEquals(2, outer.changedKeyCount(),
                "the original outer undo journal also remembers the rolled-back inner key");
        attempt.rollbackTo(outer);
        assertExact(shell, attempt.require(shell.blueId()));
        assertTrue(attempt.changes().entries().isEmpty());
        attempt.preferCanonicalRepresentation(materialized.frozen(), "materialized");
        fixture.publish(attempt.changes());
        var cold = new WholeObjectStore(new EngineMetrics(), fixture.view());
        assertExact(materialized, cold.require(shell.blueId()));
        assertEquals(child.blueId(), cold.fetchByBlueId(shell.blueId()).get(0)
                .getProperties().get("child").getBlueId());
        assertEquals(2, cold.size());
        assertExact(shell, original.require(shell.blueId()), "pinned earlier view remains immutable");
    }

    @Test void referenceUpgradeRollbackRevealsPersistedReferenceWithoutDeletingIt() throws Exception {
        ExactValue value = ExactValue.verified(new Node().value("complete"));
        var writer = new WholeObjectStore(new EngineMetrics());
        ExactValue reference = writer.put(value.referenceNode(), "reference");
        fixture.publish(writer.changes());
        var restored = new WholeObjectStore(new EngineMetrics(), fixture.view());
        var mark = restored.mark();
        restored.put(value, "full");
        assertExact(value, restored.require(value.blueId()));
        assertEquals(1, restored.size());
        restored.rollbackTo(mark);
        assertExact(reference, restored.require(value.blueId()));
        assertFalse(restored.hasCompleteOrdinaryProviderBody(value.blueId()));
        assertTrue(restored.changes().entries().isEmpty());
    }

    @Test void cyclicProviderBodiesAndCompleteProofRemainAvailableAfterColdRead() throws Exception {
        var provider = new BasicNodeProvider(new Node().items(List.of(
                new Node().name("cycle-a").properties("peer", new Node().blueId("this#1")),
                new Node().name("cycle-b").properties("peer", new Node().blueId("this#0")))));
        String id = provider.getBlueIdByName("cycle-a");
        Node raw = provider.fetchByBlueId(id).get(0);
        CyclicSetProof proof = provider.cyclicSetProofFor(id).proof().orElseThrow();
        ExactValue value = ExactValue.fromVerifiedProviderEvidence(id, raw, proof);
        var writer = new WholeObjectStore(new EngineMetrics());
        writer.putVerifiedProviderEvidence(value, raw, proof, "cyclic");
        fixture.publish(writer.changes());
        var backing = fixture.view();
        var cold = new WholeObjectStore(new EngineMetrics(), backing);
        assertExact(value, cold.require(id));
        assertTrue(cold.hasVerifiedContentForBlueId(id));
        assertExact(value, ExactValue.fromVerifiedProviderEvidence(id,
                cold.fetchByBlueId(id).get(0), cold.cyclicSetProofFor(id).proof().orElseThrow()));
        assertExact(value, ExactValue.fromVerifiedProviderEvidence(id,
                cold.requireProviderDocument(value), proof));
        var mark = cold.mark();
        cold.putVerifiedProviderEvidence(value, raw, proof, "again");
        assertEquals(2, mark.changedKeyCount());
        cold.rollbackTo(mark);
        assertTrue(cold.hasVerifiedContentForBlueId(id));
        assertTrue(cold.changes().entries().isEmpty());
        assertTrue(cold.changes().proofs().isEmpty());
        assertEquals(List.of(id), backing.cyclicMembers(BlueIds.cyclicSetMasterBlueId(id)));
    }

    @Test void corruptionAndPhysicalReadFailureRemainNoncommitting() throws Exception {
        var writer = new WholeObjectStore(new EngineMetrics());
        ExactValue value = writer.put(new Node().value("value"), "test");
        fixture.publish(writer.changes());
        var backing = fixture.view();
        backing.corrupt(value.blueId());
        var restored = new WholeObjectStore(new EngineMetrics(), backing);
        assertInstanceOf(NoncommittingExecutionException.class,
                assertThrows(CoordinationObjectStorageException.class, () -> restored.require(value.blueId())));
        assertThrows(CoordinationObjectStorageException.class, () -> restored.contains(value.blueId()));
        assertThrows(CoordinationObjectStorageException.class, () -> restored.fetchResultByBlueId(value.blueId()));
        assertTrue(restored.changes().entries().isEmpty());
        var wrongKey = new WholeObjectBacking() {
            @Override public Optional<Entry> find(String id) { return Optional.of(new Entry(value, value, null, "test")); }
            @Override public Optional<CyclicSetProof> proof(String id) { return Optional.empty(); }
            @Override public Iterable<String> cyclicMembers(String id) { return List.of(); }
            @Override public int size() { return 1; }
        };
        var invalid = new WholeObjectStore(new EngineMetrics(), wrongKey);
        assertThrows(CoordinationObjectStorageException.class, () -> invalid.require("other"));
    }

    @Test void failedReferenceUpgradeReadLeavesNoPartialLocalChangeWithoutCallerMark() {
        ExactValue value = ExactValue.verified(new Node().value("complete"));
        ExactValue reference = ExactValue.verified(value.referenceNode());
        var backing = new WholeObjectBacking() {
            int reads;
            @Override public Optional<Entry> find(String id) {
                if (++reads == 2) throw new IllegalStateException("second lane read unavailable");
                return Optional.of(new Entry(reference, reference, null, "persisted-reference"));
            }
            @Override public Optional<CyclicSetProof> proof(String id) { return Optional.empty(); }
            @Override public Iterable<String> cyclicMembers(String id) { return List.of(); }
            @Override public int size() { return 1; }
        };
        var attempt = new WholeObjectStore(new EngineMetrics(), backing);
        assertThrows(CoordinationObjectStorageException.class, () -> attempt.put(value, "upgrade"));
        assertTrue(attempt.changes().entries().isEmpty());
        assertExact(reference, attempt.require(value.blueId()));
        assertFalse(attempt.hasCompleteOrdinaryProviderBody(value.blueId()));
    }

    @Test void inconsistentPersistedCyclicBodyOrProofIsNeverSemanticProviderRejection() {
        var provider = new BasicNodeProvider(new Node().items(List.of(
                new Node().name("stored-cycle-a").properties("peer", new Node().blueId("this#1")),
                new Node().name("stored-cycle-b").properties("peer", new Node().blueId("this#0")))));
        String id = provider.getBlueIdByName("stored-cycle-a");
        Node raw = provider.fetchByBlueId(id).get(0);
        CyclicSetProof complete = provider.cyclicSetProofFor(id).proof().orElseThrow();
        ExactValue exact = ExactValue.fromVerifiedProviderEvidence(id, raw, complete);
        for (String fault : List.of("body", "proof", "missing-proof")) {
            var backing = new WholeObjectBacking() {
                @Override public Optional<Entry> find(String key) {
                    Node body = raw.clone();
                    if (fault.equals("body")) body.name("corrupt-body");
                    return Optional.of(new Entry(exact, exact, body, "test"));
                }
                @Override public Optional<CyclicSetProof> proof(String master) {
                    if (fault.equals("missing-proof")) return Optional.empty();
                    if (fault.equals("proof")) return Optional.of(CyclicSetProof.fromDeclaredPlaceholderSet(
                            List.of(new Node().value("wrong-proof"))));
                    return Optional.of(complete);
                }
                @Override public Iterable<String> cyclicMembers(String master) { return List.of(id); }
                @Override public int size() { return 1; }
            };
            var attempt = new WholeObjectStore(new EngineMetrics(), backing);
            assertThrows(CoordinationObjectStorageException.class, () -> attempt.require(id), fault);
            assertThrows(CoordinationObjectStorageException.class, () -> attempt.requireProviderDocument(exact), fault);
            assertThrows(CoordinationObjectStorageException.class, () -> attempt.hasVerifiedContentForBlueId(id), fault);
            assertThrows(CoordinationObjectStorageException.class, () -> attempt.fetchResultByBlueId(id), fault);
            // A missing master proof used to short-circuit before reading the
            // indexed provider body. It must still authenticate that body.
            assertThrows(CoordinationObjectStorageException.class, () -> attempt.cyclicSetProofFor(id), fault);
        }
    }

    @Test void componentRetentionReadFailureRollsBackEarlierAttemptLocalMembers() {
        DocumentId a = DocumentId.of("retention-a"), b = DocumentId.of("retention-b");
        try (var publicEngine = blue.coordination.api.CoordinationEngine.inMemoryContracts10(
                BundledContracts10Release.configuration(Set.of(a, b)))) {
            var scenario = new Contracts10ScenarioBuilder((DefaultCoordinationEngine) publicEngine)
                    .document(a, "name: Retention A\ncount: 0\n")
                    .document(b, "name: Retention B\ncount: 0\n")
                    .publicRoot(a).publicRoot(b).expectedComponent(a).expectedComponent(b)
                    .admissionLabel("stored-retention-fault");
            var result = scenario.admitTo(publicEngine).admissionReceipt().attempt().processResult();
            assertEquals(2, result.resultingDocuments().size());
            String second = result.resultingDocuments().get(1).afterBlueId();
            var metrics = new EngineMetrics();
            var partialWasObserved = new java.util.concurrent.atomic.AtomicBoolean();
            var backing = new WholeObjectBacking() {
                @Override public Optional<Entry> find(String id) {
                    if (id.equals(second)) {
                        partialWasObserved.set(metrics.counter("wholeObjectStore.insertions") > 0);
                        throw new IllegalStateException("second member read unavailable");
                    }
                    return Optional.empty();
                }
                @Override public Optional<CyclicSetProof> proof(String id) { return Optional.empty(); }
                @Override public Iterable<String> cyclicMembers(String id) { return List.of(); }
                @Override public int size() { return 0; }
            };
            var attempt = new WholeObjectStore(metrics, backing);
            assertThrows(CoordinationObjectStorageException.class,
                    () -> attempt.retainVerifiedClosureComponentEvidence(result));
            assertTrue(partialWasObserved.get(), "fault occurs after the first local member write");
            assertTrue(attempt.changes().entries().isEmpty());
            assertTrue(attempt.changes().proofs().isEmpty());
            assertEquals(0, attempt.size());
        }
    }


    @Test void missingSelectedObjectAndProofFailClosedWhileUnrelatedCorruptionStaysUnread() {
        var writer = new WholeObjectStore(new EngineMetrics());
        var selected = writer.put(new Node().value("selected"), "selected");
        var other = writer.put(new Node().value("other"), "other");
        fixture.publish(writer.changes());
        var view = fixture.view(); view.corrupt(other.blueId());
        var cold = new WholeObjectStore(new EngineMetrics(), view);
        assertExact(selected, cold.require(selected.blueId()));
        assertEquals(Set.of(selected.blueId()), view.readKeys);
        fixture.remove(selected.blueId());
        assertThrows(CoordinationObjectStorageException.class, () -> cold.require(selected.blueId()));
        assertTrue(cold.changes().entries().isEmpty());

        var provider = new BasicNodeProvider(new Node().items(List.of(
                new Node().name("missing-proof-a").properties("peer", new Node().blueId("this#1")),
                new Node().name("missing-proof-b").properties("peer", new Node().blueId("this#0")))));
        String id = provider.getBlueIdByName("missing-proof-a");
        var raw = provider.fetchByBlueId(id).get(0); var proof = provider.cyclicSetProofFor(id).proof().orElseThrow();
        var cycle = new WholeObjectStore(new EngineMetrics());
        cycle.putVerifiedProviderEvidence(ExactValue.fromVerifiedProviderEvidence(id, raw, proof), raw, proof, "cycle");
        fixture.publish(cycle.changes());
        fixture.objects.remove(fixture.proofAddress(BlueIds.cyclicSetMasterBlueId(id)));
        var reopened = new WholeObjectStore(new EngineMetrics(), fixture.view());
        assertThrows(CoordinationObjectStorageException.class, () -> reopened.cyclicSetProofFor(id));
    }

    @Test void snapshotBearingExactValueRetainsActualResolverEvidenceWithoutProviderReplay() {
        var provider = new BasicNodeProvider(new Node().name("PaymentInstruction")
                .properties("reserved", new Node().value(true)));
        String typeId = provider.getBlueIdByName("PaymentInstruction");
        blue.language.merge.ResolvedSnapshot snapshot;
        try (var language = blue.language.runtime.BlueLanguage.builder().nodeProvider(provider).build()) {
            snapshot = language.snapshots().resolve(new Node().type(new Node().blueId(typeId)));
        }
        var writer = new WholeObjectStore(new EngineMetrics());
        var value = writer.put(snapshot, "snapshot");
        fixture.publish(writer.changes());
        var cold = new WholeObjectStore(new EngineMetrics(), fixture.view());
        var restored = cold.require(value.blueId()).snapshot().orElseThrow();
        assertNotSame(snapshot, restored);
        assertNotNull(restored.verifiedReferenceResolution());
        assertEquals(typeId, restored.canonicalTypeIdentities()
                .requireCanonicalTypeBlueId(restored.resolvedRoot().getType()));
        assertEquals(snapshot.frozenSourceRoot().resolvedStructuralKey(), restored.frozenSourceRoot().resolvedStructuralKey());
        assertEquals(snapshot.frozenCanonicalRoot().resolvedStructuralKey(), restored.frozenCanonicalRoot().resolvedStructuralKey());
        assertEquals(snapshot.frozenResolvedRoot().resolvedStructuralKey(), restored.frozenResolvedRoot().resolvedStructuralKey());
        assertTrue(cold.changes().entries().isEmpty());
        assertEquals(1, fixture.view().size());
    }

    @Test void rawResolvedMetadataAndSchemaKeywordNodesSurviveColdBodyRead() {
        var raw = new Node().blueId(ExactValue.verified(new Node().value("identity")).blueId())
                .description("resolved metadata \uD800")
                .properties("nested", new Node().value(new java.math.BigDecimal("1.00")))
                .schema(new blue.language.model.Schema().minLength(new Node().value(1).description("keyword metadata")));
        var frozen = blue.language.snapshot.FrozenNode.fromResolvedNode(raw);
        var codec = new blue.language.snapshot.FrozenNodeStorageCodec(1024 * 1024, 128);
        var restored = codec.decode(codec.encode(frozen));
        assertFalse(restored.isStrictCanonical());
        assertEquals(frozen.resolvedStructuralKey(), restored.resolvedStructuralKey());
        assertEquals(raw.getDescription(), restored.toNode().getDescription());
        assertThrows(IllegalArgumentException.class, () -> ExactValue.fromFrozen(frozen),
                "Raw transport fidelity does not make an invalid semantic string an exact value");
        var ordinary = ExactValue.fromFrozen(blue.language.snapshot.FrozenNode.fromResolvedNode(
                raw.clone().description("valid resolved metadata")));
        var writer = new WholeObjectStore(new EngineMetrics()); writer.put(ordinary, "resolved");
        fixture.publish(writer.changes());
        var cold = new WholeObjectStore(new EngineMetrics(), fixture.view());
        assertExact(ordinary, cold.require(ordinary.blueId()));
    }

    @Test void bodyBudgetsAndUnsupportedRawValuesNeverSilentlyChangeRepresentation() {
        var ordinary = blue.language.snapshot.FrozenNode.fromNode(new Node().value("value"));
        assertThrows(IllegalArgumentException.class, () -> new blue.language.snapshot.FrozenNodeStorageCodec(128, 128).encode(ordinary));
        var deep = new Node().value("leaf");
        for (int i = 0; i < 20; i++) deep = new Node().properties("next", deep);
        var frozen = blue.language.snapshot.FrozenNode.fromNode(deep);
        var generous = new blue.language.snapshot.FrozenNodeStorageCodec(1024 * 1024, 128);
        var bounded = new blue.language.snapshot.FrozenNodeStorageCodec(1024 * 1024, 8);
        assertThrows(IllegalArgumentException.class, () -> bounded.encode(frozen));
        assertThrows(IllegalArgumentException.class, () -> bounded.decode(generous.encode(frozen)));
        var raw = blue.language.snapshot.FrozenNode.fromResolvedNode(new Node().value(new int[]{1, 2}));
        assertEquals(raw.resolvedStructuralKey(), generous.decode(generous.encode(raw)).resolvedStructuralKey());
        var unsupported = blue.language.snapshot.FrozenNode.fromResolvedNode(new Node().value(Thread.State.NEW));
        assertThrows(IllegalArgumentException.class, () -> generous.encode(unsupported));
        byte[] encoded = generous.encode(ordinary);
        assertThrows(IllegalArgumentException.class, () -> generous.decode(java.util.Arrays.copyOf(encoded, encoded.length + 1)));
    }

    @Test void coldExactBodyKeepsMixedFrozenConstructionModesAndSharedChildren() {
        var strict = blue.language.snapshot.FrozenNode.fromNode(new Node().value("strict"));
        var resolved = blue.language.snapshot.FrozenNode.fromResolvedNode(new Node().value("resolved"));
        var mixed = blue.language.snapshot.FrozenNode.fromResolvedNode(blue.language.model.Nodes.emptyObject())
                .withProperty("first", strict).withProperty("again", strict).withProperty("resolved", resolved);
        var original = ExactValue.fromFrozen(mixed);
        var writer = new WholeObjectStore(new EngineMetrics());
        writer.put(original, "mixed");
        fixture.publish(writer.changes());
        var cold = new WholeObjectStore(new EngineMetrics(), fixture.view());
        var restored = cold.require(original.blueId()).frozen();
        assertEquals(mixed.resolvedStructuralKey(), restored.resolvedStructuralKey());
        assertFalse(restored.isStrictCanonical());
        assertTrue(restored.property("first").isStrictCanonical());
        assertFalse(restored.property("resolved").isStrictCanonical());
        assertSame(restored.property("first"), restored.property("again"));
    }

    @Test void coldBodyProviderPreservesActualRootedSdkResultAndGasBoundary() throws Exception {
        ExactValue authored;
        try (var sdk = blue.coordination.sdk.BlueCoordination.inMemory()) {
            authored = ExactValue.verified(blue.language.codec.jackson.UncheckedObjectMapper.JSON_MAPPER.readValue(
                    sdk.values().yaml(resource("source.yaml")).json(), Node.class));
        }
        var resident = new WholeObjectStore(new EngineMetrics()); resident.put(authored, "authored-source");
        fixture.publish(resident.changes());
        var calibration = calculate(resident, authored.blueId(), 100_000L);
        assertEquals(blue.language.processor.ProcessorStatus.SUCCESS, calibration.status());
        assertTrue(calibration.totalGas() > 1);
        for (long limit : List.of(calibration.totalGas(), calibration.totalGas() - 1L)) {
            var reference = calculate(resident, authored.blueId(), limit);
            var cold = new WholeObjectStore(new EngineMetrics(), fixture.view());
            var actual = calculate(cold, authored.blueId(), limit);
            assertEquals(reference.status(), actual.status());
            assertEquals(reference.inputClosureIdentity(), actual.inputClosureIdentity());
            assertEquals(reference.outputClosureIdentity(), actual.outputClosureIdentity());
            assertEquals(reference.publicEventsIdentity(), actual.publicEventsIdentity());
            assertEquals(reference.checkpointWritesIdentity(), actual.checkpointWritesIdentity());
            assertEquals(reference.managedTransitionReceiptsIdentity(), actual.managedTransitionReceiptsIdentity());
            assertEquals(reference.totalGas(), actual.totalGas());
            assertEquals(reference.gasTraceIdentity(), actual.gasTraceIdentity());
            assertEquals(fullTrace(reference), fullTrace(actual));
            assertEquals(reference.resultingDocuments().stream().map(row -> row.afterBlueId()).toList(),
                    actual.resultingDocuments().stream().map(row -> row.afterBlueId()).toList());
            if (reference.commits()) {
                assertEquals(reference.commitCompanion().companionIdentity(), actual.commitCompanion().companionIdentity());
            } else {
                assertEquals(blue.language.processor.ProcessorStatus.GAS_LIMIT_EXCEEDED, actual.status());
                assertEquals(reference.rejectedCharge().rejectedChargeIdentity(), actual.rejectedCharge().rejectedChargeIdentity());
                assertTrue(actual.rollbackToInput()); assertNull(actual.commitCompanion());
                assertTrue(actual.publicEvents().isEmpty()); assertTrue(actual.checkpointWrites().isEmpty());
            }
            assertTrue(cold.changes().entries().isEmpty(), "Provider reads are never local mutations");
        }
        assertTrue(fixture.physicalReads > 0, "The SDK must actually consume cold external body evidence");
    }

    @Test void lyingImmutableAcknowledgementCannotProducePublishedReferences() {
        var writer = new WholeObjectStore(new EngineMetrics()); writer.put(new Node().value("exact"), "test");
        var lying = new blue.coordination.api.storage.CoordinationImmutableObjectStore() {
            @Override public byte[] putIfAbsent(String address, byte[] bytes) { return new byte[]{1}; }
            @Override public Optional<byte[]> get(String address, int maximumBytes) { return Optional.empty(); }
        };
        var storage = new WholeObjectStorage(lying, 1024 * 1024, 128);
        var failure = assertThrows(CoordinationObjectStorageException.class, () -> storage.retain(writer.changes()));
        assertTrue(failure.getMessage().contains("acknowledgement"));
        assertEquals(1, writer.size());
    }

    private static blue.language.processor.closure.ClosureProcessResult calculate(
            WholeObjectStore objects, String sourceIdentity, long limit) throws Exception {
        var json = blue.language.codec.jackson.UncheckedObjectMapper.JSON_MAPPER;
        try (var sdk = blue.coordination.sdk.BlueCoordination.builder().contentDerivedDocumentIds()
                .exactNodeProvider(id -> {
                    var nodes = objects.fetchByBlueId(id);
                    return nodes.isEmpty() ? Optional.empty() : Optional.of(json.writeValueAsString(nodes.get(0)));
                }).build()) {
            var timeline = sdk.timelines().register("rcp2/source", "alice");
            sdk.timelines().register("rcp2/parent", "alice");
            var closure = sdk.documents().admitStaticProcessEmbedded(resource("parent.yaml")
                    + "\nchild:\n  blueId: " + sourceIdentity + "\n",
                    blue.coordination.sdk.ActivationPolicy.importFullHistory());
            var parent = closure.document("root"); var source = closure.document("embedded-0");
            var entry = sdk.events().from(timeline).exact(sdk.values().yaml("""
                    type: Coordination/Timeline Entry
                    timeline: {type: MyOS/MyOS Timeline, timelineId: rcp2/source}
                    timestamp: 100
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                    message:
                      type: Coordination/Operation Request
                      document: {blueId: %s}
                      requireExactDocumentVersion: false
                      operation: tick
                      channel: owner
                      request: {}
                    """.formatted(source.snapshot().blueId()))).submit();
            var result = sdk.advanced().process(parent, entry,
                    blue.coordination.api.ContractsExecutionPolicy.exactSharedGas(limit, "stored-body-parity")).entry(entry);
            assertEquals(1, result.closures().size(), result.diagnostic().toString());
            return sdk.advanced().closureExecution(result.closures().get(0).closureId()).orElseThrow();
        }
    }

    private static String resource(String name) throws Exception {
        try (var input = WholeObjectStorageTest.class.getResourceAsStream("/rooted/" + name)) {
            assertNotNull(input); return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private static List<List<Object>> fullTrace(blue.language.processor.closure.ClosureProcessResult result) {
        return result.gasTrace().stream().map(charge -> java.util.Arrays.<Object>asList(
                charge.sequence(), charge.namespace(), charge.counter(), charge.quantity(), charge.weight(), charge.subtotal(),
                charge.documentId(), charge.scopePath(), charge.activationGeneration(), charge.componentGeneration(),
                charge.contractKey(), charge.logicalPath(), charge.workOccurrenceId(), charge.reason())).toList();
    }

    private static void assertExact(ExactValue expected, ExactValue actual, String... message) {
        assertEquals(expected.blueId(), actual.blueId(), String.join(" ", message));
        assertEquals(expected.frozen().resolvedStructuralKey(), actual.frozen().resolvedStructuralKey());
    }
}
