package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.sdk.*;
import blue.language.processor.closure.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real SDK captures and processor-issued suspensions; this does not restore an entire engine. */
final class CohortInvocationStorageCodecTest {
    private static final int MAX = 32 * 1024 * 1024;
    private static final DocumentSessionStorage.Limits LIMITS = new DocumentSessionStorage.Limits(MAX, 256, 256L * 1024 * 1024);
    private static final ClosureExecutionEvidenceStorageCodec LANGUAGE = new ClosureExecutionEvidenceStorageCodec(MAX, 256);
    private static final ClosureProcessResultStorageCodec RESULTS = new ClosureProcessResultStorageCodec(MAX, 256);

    @Test void pairedRootSelectionReusesOneCaptureAndPreservesExactProcessingAndNextHead() throws Exception {
        try (var f = new Fixture()) {
            var root = f.start("source.yaml", "rcp2/source", ActivationPolicy.importFullHistory());
            var entry = f.append(root, "rcp2/source", "tick", "{}");
            var adapter = f.engine.contractsClosureAdapter();
            var entries = f.engine.auditTimelineEntries();
            long before = documentOpens(f);
            var separateLocal = adapter.nextRootLocalHistory(root.id(), entries);
            var separateLive = adapter.nextRootLiveInput(root.id(), entries).orElseThrow();
            assertFalse(separateLocal.pending());
            assertEquals(2L, documentOpens(f) - before, "Separate paths really capture the one owner twice");
            before = documentOpens(f);
            var paired = adapter.nextRootInputCandidates(root.id(), entries, () -> null);
            assertEquals(1L, documentOpens(f) - before);
            assertFalse(paired.local().pending());
            var actualInput = paired.live().orElseThrow().invocations().get(0);
            var referenceInput = separateLive.invocations().get(0);
            assertArrayEquals(LANGUAGE.encodeInvocation(referenceInput.input()), LANGUAGE.encodeInvocation(actualInput.input()));
            var history = f.history(root);
            var reference = f.process(referenceInput); var actual = f.process(actualInput);
            assertTrue(reference.isComplete()); assertTrue(actual.isComplete());
            assertTrue(reference.processResult().commits());
            assertArrayEquals(RESULTS.encode(reference.processResult()), RESULTS.encode(actual.processResult()),
                    "Independent calculations preserve full state, events, checkpoints and logical gas");
            assertEquals(history, f.history(root), "Selection and reference calculations do not publish");
            var outcome = adapter.processAndPublish(paired.live().orElseThrow()).get(0);
            assertTrue(outcome.published());
            assertArrayEquals(RESULTS.encode(actual.processResult()), RESULTS.encode(outcome.attempt().processResult()));
            assertEquals(history.size() + 1, f.history(root).size());
            assertEquals(actual.processResult().managedTransitionReceipts().get(0).afterBlueId(), root.snapshot().blueId());

            f.append(root, "rcp2/source", "tick", "{}");
            entries = f.engine.auditTimelineEntries();
            before = documentOpens(f);
            var next = adapter.nextRootInputCandidates(root.id(), entries, () -> null);
            assertEquals(1L, documentOpens(f) - before, "A later decision captures again instead of reusing stale fences");
            var nextInput = next.live().orElseThrow().invocations().get(0).input();
            assertNotEquals(actualInput.input().invocationIdentity(), nextInput.invocationIdentity());
            assertEquals(root.snapshot().blueId(), nextInput.snapshot().managedDocument(
                    ContractsClosureAdapter.closureId(root.id())).blueId());
            var nextSeparate = adapter.nextRootLiveInput(root.id(), entries).orElseThrow().invocations().get(0).input();
            assertArrayEquals(LANGUAGE.encodeInvocation(nextSeparate), LANGUAGE.encodeInvocation(nextInput));
            assertEquals(entry.blueId(), separateLive.entry().blueId());
        }
    }

    @Test void pairedRootSelectionKeepsLazyLiveCutoffsAndDropsFailedDecisionCapture() throws Exception {
        try (var f = new Fixture()) {
            var root = f.start("source.yaml", "rcp2/source", ActivationPolicy.importFullHistory());
            var adapter = f.engine.contractsClosureAdapter();
            long before = documentOpens(f);
            assertTrue(adapter.nextRootLiveInput(root.id(), List.of()).isEmpty());
            assertEquals(0L, documentOpens(f) - before, "Standalone empty LIVE search remains lazy");
            var empty = adapter.nextRootInputCandidates(root.id(), List.of(), () -> null);
            assertFalse(empty.local().pending()); assertTrue(empty.live().isEmpty());
            assertEquals(1L, documentOpens(f) - before, "The local path still performs its original complete capture");
            var entry = f.append(root, "rcp2/source", "tick", "{}");
            var entries = f.engine.auditTimelineEntries();
            var order = f.engine.auditTimelineEntry(entry.blueId()).orElseThrow().sourceOrderKey();
            before = documentOpens(f);
            assertTrue(adapter.nextRootLiveInput(root.id(), entries, order).isEmpty());
            assertEquals(0L, documentOpens(f) - before, "Equal retained order excludes LIVE before capture");
            assertTrue(adapter.nextRootInputCandidates(root.id(), entries, () -> order).live().isEmpty());
            assertEquals(1L, documentOpens(f) - before);

            var marker = new IllegalStateException("registered evidence unavailable");
            before = documentOpens(f);
            assertSame(marker, assertThrows(IllegalStateException.class,
                    () -> adapter.nextRootInputCandidates(root.id(), entries, () -> { throw marker; })));
            assertEquals(1L, documentOpens(f) - before);
            before = documentOpens(f);
            assertTrue(adapter.nextRootInputCandidates(root.id(), entries, () -> null).live().isPresent());
            assertEquals(1L, documentOpens(f) - before, "A failed decision does not publish a reusable capture");
        }
    }

    private static long documentOpens(Fixture fixture) {
        return fixture.engine.metricsSnapshot().counters().getOrDefault(ContractsClosureAdapter.DOCUMENT_OPENS, 0L);
    }

    @Test void actualDeclaredBirthSuspensionAndConsumedExpansionSurviveProducerClose() throws Exception {
        var bytes = new Bytes(); var storage = new DocumentSessionStorage(bytes, LIMITS);
        var codec = new CohortInvocationStorageCodec(MAX, 256);
        byte[] suspended, expandedBytes, expectedResult;
        Map<String, String> retainedExactObjects;
        String requestBlueId;
        try (var f = new Fixture()) {
            var parent = f.start("parent.yaml", "rcp2/parent", ActivationPolicy.importFullHistory());
            var initial = f.blue.values().yaml(resource("source.yaml")); f.exact.put(initial.blueId(), initial.json());
            var draft = f.blue.documents().draft(DocumentId.of(initial.blueId()), initial);
            var entry = f.blue.operations().on(parent).from(f.timelines.get("rcp2/parent")).call("attach").through("owner")
                    .request(request -> request.managed("child", draft)).expectOccurrence("/child", draft)
                    .activation(ActivationPolicy.fromNow()).submit();
            var exactRequest = f.blue.advanced().auditTimelineEntry(entry.blueId()).orElseThrow().request().orElseThrow();
            requestBlueId = exactRequest.blueId();
            f.exact.put(exactRequest.blueId(), exactRequest.json());
            var captured = f.capture(parent, entry);
            var before = parent.snapshot().blueId(); var history = f.history(parent);
            var attempt = f.process(captured);
            assertFalse(attempt.isComplete());
            var demand = assertInstanceOf(ManagedOccurrenceEvidenceDemand.class, attempt.resourceDemands().get(0));
            int reads = f.providerReads.get();
            suspended = codec.encodeAttempt(captured, attempt, demand, storage::retainView);
            try (var scope = storage.openScope()) {
                var cold = codec.decodeAttempt(suspended, scope);
                assertSame(cold.attempt().resourceDemands().get(0), cold.selectedDemand());
                assertNotSame(demand, cold.selectedDemand());
                assertArrayEquals(suspended, codec.encodeAttempt(cold.invocation(), cold.attempt(), cold.selectedDemand(), scope::addressOf));
                assertEquals(reads, f.providerReads.get());
                assertEquals(captured.rootedEvidence().baseInvocationIdentity(), cold.invocation().rootedEvidence().baseInvocationIdentity());
                var restoredPlan = cold.invocation().managedDraftPlan();
                assertEquals(captured.managedDraftPlan().managedRequestFields(), restoredPlan.managedRequestFields());
                var selectedDraft = restoredPlan.drafts().get(draft.id());
                var issued = (ManagedOccurrenceEvidenceDemand) cold.selectedDemand();
                // The unchanged Language birth authenticator consumes the actual restored issued capability.
                var expandedInput = ClosureEvidenceFactory.withProspectiveBirths(cold.invocation().input(), List.of(
                        new ManagedDocumentBirth(issued, ContractsClosureAdapter.closureId(draft.id()), selectedDraft.initial().copyNode())));
                var occurrence = new ManagedOccurrenceResolver.ResolvedOccurrence(issued, draft.id(), selectedDraft.initial().blueId(),
                        ManagedOccurrenceResolver.TargetKind.NEW_AUTHORED, -1, selectedDraft);
                var expansion = new AutomaticManagedOccurrenceExpansion(Map.of(draft.id(), selectedDraft), List.of(occurrence),
                        expandedInput.snapshot().occurrences().stream().filter(row -> !row.active()).map(ManagedOccurrenceBinding::occurrenceIdentity)
                                .collect(java.util.stream.Collectors.toSet()));
                var original = cold.invocation();
                var expanded = new ContractsClosureAdapter.CohortInvocation(expandedInput.snapshot().managedDocuments().stream()
                        .map(row -> ContractsClosureAdapter.coordinationId(row.documentId())).sorted(EmbeddingBinding.DOCUMENT_ORDER).toList(),
                        original.directDeliveries(), expandedInput, null, original.documents(), restoredPlan, expansion,
                        original.publicationIdentityMembers(), original.publicationIdentityPublicRoots(), original.rootedAnchor(), original.rootedEvidence());
                assertNotEquals(expanded.input().invocationIdentity(), expanded.rootedEvidence().baseInvocationIdentity());
                expandedBytes = codec.encode(expanded, scope::addressOf);
                var restoredExpanded = codec.decode(expandedBytes, scope);
                assertArrayEquals(LANGUAGE.encodeResourceDemand(issued), LANGUAGE.encodeResourceDemand(
                        restoredExpanded.automaticExpansion().occurrences().get(0).demand()));
                var complete = f.process(expanded);
                assertTrue(complete.isComplete()); assertTrue(complete.processResult().commits(), String.valueOf(complete.processResult().diagnostic()));
                expectedResult = RESULTS.encode(complete.processResult());
                assertArrayEquals(expectedResult, RESULTS.encode(f.process(restoredExpanded).processResult()));
                codec.decodeAttempt(codec.encodeAttempt(restoredExpanded, complete, null, scope::addressOf), scope);
            }
            assertEquals(before, parent.snapshot().blueId()); assertEquals(history, f.history(parent));
            assertTrue(f.engine.documents().find(draft.id()).isEmpty(), "Component processing and restoration never publish the child");
            retainedExactObjects = Map.copyOf(f.exact);
        }
        try (var consumer = new Fixture(); var scope = new DocumentSessionStorage(bytes, LIMITS).openScope()) {
            var restored = codec.decode(expandedBytes, scope);
            assertEquals(0, consumer.providerReads.get());
            var missingRequest = consumer.process(restored);
            assertFalse(missingRequest.isComplete());
            assertEquals(List.of(requestBlueId), missingRequest.resourceDemands().stream()
                    .map(ClosureResourceDemand::suppliedValueBlueId).toList());
            assertInstanceOf(ExactNodeDemand.class, missingRequest.resourceDemands().get(0));
            // The separately retained exact-object lane is available for subsequent PROCESS, never for decode.
            consumer.exact.putAll(retainedExactObjects);
            var continued = consumer.process(restored);
            assertTrue(continued.isComplete(), () -> continued.resourceDemands().stream()
                    .map(demand -> demand.kind() + ":" + demand.sourcePath() + ":" + demand.suppliedValueBlueId()).toList().toString());
            assertArrayEquals(expectedResult, RESULTS.encode(continued.processResult()));
            assertSame(codec.decodeAttempt(suspended, scope).selectedDemand().getClass(), ManagedOccurrenceEvidenceDemand.class);
        }
    }

    @Test void actualBareSourceDemandKeepsItsExactParentAttemptWithoutPublishingOrAcquiringSource() throws Exception {
        try (var f = new Fixture()) {
            var parent = f.start("parent.yaml", "rcp2/parent", ActivationPolicy.importFullHistory());
            var source = f.blue.values().yaml(resource("source.yaml")); f.exact.put(source.blueId(), source.json());
            var entry = f.append(parent, "rcp2/parent", "attach", "child: {blueId: " + source.blueId() + "}");
            var captured = f.capture(parent, entry); var before = f.history(parent);
            var attempt = f.process(captured); assertFalse(attempt.isComplete());
            var selected = assertInstanceOf(ManagedOccurrenceEvidenceDemand.class, attempt.resourceDemands().get(0));
            var codec = new CohortInvocationStorageCodec(MAX, 256);
            var storage = new DocumentSessionStorage(new Bytes(), LIMITS);
            byte[] bytes = codec.encodeAttempt(captured, attempt, selected, storage::retainView);
            int reads = f.providerReads.get();
            try (var scope = storage.openScope()) {
                var cold = codec.decodeAttempt(bytes, scope);
                assertEquals(reads, f.providerReads.get());
                assertSame(cold.attempt().resourceDemands().get(0), cold.selectedDemand());
                assertEquals(selected.demandIdentity(), cold.selectedDemand().demandIdentity());
                assertEquals(captured.rootedEvidence().publicationFences(), cold.invocation().rootedEvidence().publicationFences());
                assertArrayEquals(bytes, codec.encodeAttempt(cold.invocation(), cold.attempt(), cold.selectedDemand(), scope::addressOf));
                var repeated = f.process(cold.invocation());
                assertArrayEquals(LANGUAGE.encodeResourceDemand(selected), LANGUAGE.encodeResourceDemand(repeated.resourceDemands().get(0)));
                var copy = new ManagedOccurrenceEvidenceDemand(selected.demandIdentity(), selected.logicalCauseIdentity(),
                        selected.inputClosureIdentity(), selected.inputGraphGeneration(), selected.sourceDocumentId(), selected.sourcePath(),
                        selected.processEmbeddedDeclarationIdentity(), selected.suppliedValueBlueId(), selected.demandOrdinal());
                assertThrows(CoordinationObjectStorageException.class, () -> codec.encodeAttempt(captured, attempt, copy, storage::retainView));
            }
            assertEquals(before, f.history(parent));
            assertTrue(f.engine.documents().find(DocumentId.of(source.blueId())).isEmpty());
        }
    }

    @Test void preparedHistoricalRetryKeepsOriginalCapturedBaseAndExactResolution() throws Exception {
        try (var f = new Fixture()) {
            var source = f.start("source.yaml", "rcp2/source", ActivationPolicy.fromNow());
            String old = source.snapshot().blueId();
            f.apply(source, f.append(source, "rcp2/source", "tick", "{}")); f.retain(source);
            var previous = f.startYaml(resource("source.yaml").replace("RCP2 Source", "Previous source")
                    .replace("rcp2/source", "rcp2/previous"), "rcp2/previous", ActivationPolicy.fromNow());
            var parent = f.startYaml(resource("parent.yaml").replace("    - /child", "    - /child\n    - /other")
                    + "\nchild: {blueId: " + previous.snapshot().blueId() + "}\nother: {blueId: " + source.snapshot().blueId() + "}\n",
                    "rcp2/parent", ActivationPolicy.fromNow());
            var captured = f.capture(parent, f.append(parent, "rcp2/parent", "attach", "child: {blueId: " + old + "}"));
            var attempt = f.process(captured); assertFalse(attempt.isComplete());
            var demand = assertInstanceOf(ManagedOccurrenceEvidenceDemand.class, attempt.resourceDemands().get(0));
            assertEquals(old, demand.suppliedValueBlueId());
            assertEquals(source.snapshot().blueId(), captured.input().snapshot().managedDocument(ContractsClosureAdapter.closureId(source.id())).blueId());
            assertEquals(old, f.engine.documents().managedEpochEvidence(source.id(), 0).receipt().afterBlueId());
            var retry = ClosureProcessRetryInput.derived(captured.input(), List.of(
                    ManagedOccurrenceEvidenceResolution.derived(demand, ContractsClosureAdapter.closureId(source.id()), 0)));
            var prepared = new ContractsClosureAdapter.CohortInvocation(captured.members(), captured.directDeliveries(), captured.input(), retry,
                    captured.documents(), captured.managedDraftPlan(), captured.automaticExpansion(), captured.publicationIdentityMembers(),
                    captured.publicationIdentityPublicRoots(), captured.rootedAnchor(), captured.rootedEvidence());
            var codec = new CohortInvocationStorageCodec(MAX, 256); var storage = new DocumentSessionStorage(new Bytes(), LIMITS);
            byte[] bytes = codec.encode(prepared, storage::retainView); var before = List.of(f.history(parent), f.history(source), f.history(previous));
            try (var scope = storage.openScope()) {
                int reads = f.providerReads.get(); var cold = codec.decode(bytes, scope); assertEquals(reads, f.providerReads.get());
                assertArrayEquals(LANGUAGE.encodeRetry(retry), LANGUAGE.encodeRetry(cold.retryInput()));
                assertArrayEquals(LANGUAGE.encodeInvocation(captured.input()), LANGUAGE.encodeInvocation(cold.input()));
                var expected = f.process(prepared); var actual = f.process(cold);
                assertTrue(expected.isComplete()); assertTrue(expected.processResult().commits(), String.valueOf(expected.processResult().diagnostic()));
                assertArrayEquals(RESULTS.encode(expected.processResult()), RESULTS.encode(actual.processResult()));
                codec.decodeAttempt(codec.encodeAttempt(cold, actual, null, scope::addressOf), scope);
                assertThrows(CoordinationObjectStorageException.class, () -> codec.encodeAttempt(prepared, attempt, demand, storage::retainView),
                        "The ordinary suspension did not come from the prepared retry");
                assertEquals(before, List.of(f.history(parent), f.history(source), f.history(previous)));
            }
        }
    }

    @Test void admissionSourceViewsShareTheSelectedSessionScopeAndUnrelatedCorruptionIsUnread() throws Exception {
        try (var f = new Fixture()) {
            var source = f.start("source.yaml", "rcp2/source", ActivationPolicy.fromNow());
            f.apply(source, f.append(source, "rcp2/source", "tick", "{}")); f.retain(source);
            var parent = f.startYaml(resource("parent.yaml") + "\nchild: {blueId: " + source.snapshot().blueId() + "}\n",
                    "rcp2/parent", ActivationPolicy.fromNow());
            var entry = f.append(parent, "rcp2/parent", "attach", "child: {blueId: " + source.snapshot().blueId() + "}");
            var captured = f.capture(parent, entry);
            assertTrue(captured.rootedEvidence().histories().get(parent.id()).admissionSources().storedViews().containsKey(source.id()));
            var bytes = new Bytes(); var storage = new DocumentSessionStorage(bytes, LIMITS);
            var codec = new CohortInvocationStorageCodec(MAX, 256);
            String sourceAddress = storage.retain(f.engine.documents().require(source.id()));
            byte[] cohort = codec.encode(captured, storage::retainView);
            String selectedView = storage.retainView(f.engine.documents().require(source.id()).rootedView());
            bytes.values.put("0".repeat(64), new byte[]{9, 7});
            for (boolean sourceFirst : List.of(true, false)) try (var scope = storage.openScope()) {
                int reads = f.providerReads.get();
                var sourceSession = sourceFirst ? scope.open(source.id(), sourceAddress) : null;
                var restored = codec.decode(cohort, scope);
                if (!sourceFirst) sourceSession = scope.open(source.id(), sourceAddress);
                assertSame(sourceSession.rootedView(), restored.rootedEvidence().histories().get(parent.id()).admissionSources().storedViews().get(source.id()));
                assertEquals(reads, f.providerReads.get());
                assertFalse(bytes.reads.contains("0".repeat(64)));
            }
            byte[] selected = bytes.values.remove(selectedView);
            try (var scope = storage.openScope()) { assertThrows(CoordinationObjectStorageException.class, () -> codec.decode(cohort, scope)); }
            bytes.values.put(selectedView, selected.clone()); bytes.values.get(selectedView)[0] ^= 1;
            try (var scope = storage.openScope()) { assertThrows(CoordinationObjectStorageException.class, () -> codec.decode(cohort, scope)); }
            bytes.values.put(selectedView, selected);
            var closed = storage.openScope(); closed.close();
            assertThrows(CoordinationObjectStorageException.class, () -> codec.decode(cohort, closed));
        }
    }

    @Test void malformedRowsAndChangedOriginalRootedAssociationFailClosed() throws Exception {
        try (var f = new Fixture()) {
            var parent = f.start("parent.yaml", "rcp2/parent", ActivationPolicy.importFullHistory());
            var source = f.blue.values().yaml(resource("source.yaml")); f.exact.put(source.blueId(), source.json());
            var captured = f.capture(parent, f.append(parent, "rcp2/parent", "attach", "child: {blueId: " + source.blueId() + "}"));
            var codec = new CohortInvocationStorageCodec(MAX, 256); var storage = new DocumentSessionStorage(new Bytes(), LIMITS);
            byte[] bytes = codec.encode(captured, storage::retainView);
            try (var scope = storage.openScope()) {
                assertThrows(CoordinationObjectStorageException.class, () -> codec.decode(Arrays.copyOf(bytes, bytes.length - 1), scope));
                assertThrows(CoordinationObjectStorageException.class, () -> codec.decode(Arrays.copyOf(bytes, bytes.length + 1), scope));
                assertThrows(CoordinationObjectStorageException.class, () -> new CohortInvocationStorageCodec(bytes.length - 1, 256).decode(bytes, scope));
                assertThrows(CoordinationObjectStorageException.class, () -> new CohortInvocationStorageCodec(128, 256).encode(captured, storage::retainView));
                var changed = new ContractsClosureAdapter.CohortInvocation(captured.members(), captured.directDeliveries(), captured.input(),
                        captured.retryInput(), captured.documents(), captured.managedDraftPlan(), captured.automaticExpansion(),
                        captured.publicationIdentityMembers(), captured.publicationIdentityPublicRoots(), captured.rootedAnchor(), null);
                assertThrows(CoordinationObjectStorageException.class, () -> codec.encode(changed, storage::retainView));
                var cold = codec.decode(bytes, scope);
                assertThrows(UnsupportedOperationException.class, () -> cold.documents().clear());
                assertThrows(UnsupportedOperationException.class, () -> cold.members().clear());
                cold.documents().values().iterator().next().current().copyNode().name("mutable returned copy");
                assertArrayEquals(bytes, codec.encode(cold, scope::addressOf));
            }
        }
    }

    @Test void actualLocalHistoricalCohortKeepsOriginWorkAndIndependentSourceFences() throws Exception {
        try (var f = new Fixture()) {
            String template = resource("node-graph.template.json");
            var roots = new LinkedHashMap<String, DocumentHandle>();
            var originals = new HashMap<String, String>();
            for (String name : List.of("A", "B", "C")) {
                String yaml = template.replace("<NODE>", name).replace("<NAMESPACE>", "cohort-storage")
                        .replace("<TIMELINE>", "cohort-storage/all");
                originals.put(name, f.blue.values().yaml(yaml).blueId());
                roots.put(name, f.startYaml(yaml, "cohort-storage/all", ActivationPolicy.fromNow()));
            }
            var a = roots.get("A"); var b = roots.get("B");
            f.apply(a, f.append(a, "cohort-storage/all", "attach", "edge: b\nsource: {blueId: " + originals.get("B") + "}"));
            f.settle(a);
            var attachment = f.append(b, "cohort-storage/all", "attach", "edge: c\nsource: {blueId: " + originals.get("C") + "}");
            f.apply(b, attachment); f.settle(b);
            f.apply(a, attachment);
            var selected = f.engine.contractsClosureAdapter().nextRootLocalHistory(a.id(), f.engine.auditTimelineEntries());
            assertTrue(selected.pending()); assertNotNull(selected.step());
            var captured = selected.step().invocation();
            var adapter = f.engine.contractsClosureAdapter();
            var entries = f.engine.auditTimelineEntries();
            var paired = adapter.nextRootInputCandidates(a.id(), entries, () -> null);
            assertTrue(paired.local().pending()); assertNotNull(paired.local().step());
            assertTrue(paired.live().isEmpty(), "Equal-order local history retains priority over LIVE");
            assertArrayEquals(LANGUAGE.encodeInvocation(captured.input()),
                    LANGUAGE.encodeInvocation(paired.local().step().invocation().input()));
            var work = selected.step().work();
            assertEquals(roots.get("C").id(), work.sourceDocumentId());
            assertEquals(0L, work.sourceEpoch());
            assertEquals(Long.valueOf(-1L), selected.step().target().pendingHistoricalEpoch());
            var documents = f.engine.documents();
            var originalStore = documents.storedState();
            var stateField = InMemoryDocumentStore.class.getDeclaredField("state");
            stateField.setAccessible(true);
            try {
                // Inject temporarily unavailable selected source evidence, not a valid complete
                // published-store state. Preserve its exact immutable image for the next oracle.
                documents.replaceManagedEpochEvidenceForTesting(work.sourceDocumentId(), work.sourceEpoch(), null, null);
                var blocked = adapter.nextRootInputCandidates(a.id(), entries, () -> {
                    fail("Blocked local history must not inspect the later registered-work cutoff"); return null;
                });
                assertTrue(blocked.local().pending()); assertNull(blocked.local().step());
                assertTrue(blocked.live().isEmpty());
            } finally {
                // The corruption helper only replaces existing rows; it cannot resurrect one
                // it deleted. Restore the original store image without rebuilding any evidence.
                stateField.set(documents, originalStore);
            }
            assertSame(originalStore, documents.storedState());
            assertArrayEquals(LANGUAGE.encodeInvocation(captured.input()), LANGUAGE.encodeInvocation(
                    adapter.nextRootInputCandidates(a.id(), entries, () -> null).local().step().invocation().input()));
            assertNotNull(captured.rootedEvidence().historicalOrigin());
            assertEquals(selected.step().work().workIdentity(), captured.rootedEvidence().historicalWork().workIdentity());
            var before = roots.values().stream().map(f::history).toList();
            var objects = new Bytes(); var storage = new DocumentSessionStorage(objects, LIMITS);
            String parentSession = storage.retain(f.engine.documents().require(a.id()));
            var codec = new CohortInvocationStorageCodec(MAX, 256);
            byte[] bytes = codec.encode(captured, storage::retainView);
            var works = new ManagedWorkStorageCodec(MAX, 256);
            byte[] workBytes = works.encode(selected.step().work());
            assertArrayEquals(workBytes, works.encode(works.decode(workBytes)));
            try (var scope = storage.openScope()) {
                var session = scope.open(a.id(), parentSession);
                int reads = f.providerReads.get();
                var cold = codec.decode(bytes, scope);
                assertEquals(reads, f.providerReads.get());
                assertSame(session.rootedView(), cold.rootedEvidence().historicalOrigin());
                assertEquals(captured.rootedEvidence().publicationFences(), cold.rootedEvidence().publicationFences());
                assertArrayEquals(workBytes, works.encode(cold.rootedEvidence().historicalWork()));
                var expected = f.process(captured); var actual = f.process(cold);
                assertTrue(expected.isComplete()); assertTrue(expected.processResult().commits(), String.valueOf(expected.processResult().diagnostic()));
                assertArrayEquals(RESULTS.encode(expected.processResult()), RESULTS.encode(actual.processResult()));
                assertArrayEquals(bytes, codec.encode(cold, scope::addressOf));
                assertEquals(before, roots.values().stream().map(f::history).toList());
            }
            assertThrows(CoordinationObjectStorageException.class, () -> works.decode(Arrays.copyOf(workBytes, workBytes.length - 1)));
            f.settle(a);
            assertFalse(adapter.nextRootInputCandidates(a.id(), f.engine.auditTimelineEntries(), () -> null).local().pending(),
                    "After catch-up changes the occurrence topology, the next decision must capture the new view");
            assertThrows(IllegalArgumentException.class, () -> selected.step().requireCurrentInput(f.engine.documents()));
        }
    }

    @Test void actualSameEpochTransitionRetainsSeparateRepresentationAndFutureSuccessorWorkDomains() throws Exception {
        try (var f = new Fixture()) {
            var source = f.startYaml(resource("source.yaml") + """
                      emitUnmatched:
                        type: Coordination/Sequential Workflow Operation
                        channel: owner
                        request: {}
                        steps:
                        - type: Coordination/Compute
                          do:
                          - $appendEvent:
                              type: Coordination/Event
                              kind: RCP2/Unmatched
                          - $return: true
                    """,
                    "rcp2/source", ActivationPolicy.fromNow());
            var parent = f.startYaml(resource("parent.yaml") + "\nchild: {blueId: " + source.snapshot().blueId() + "}\n",
                    "rcp2/parent", ActivationPolicy.fromNow());
            var anchor = f.engine.documents().managedEpochEvidence(parent.id(), 0).transitionReceipt();
            assertNotNull(anchor);
            var entry = f.append(source, "rcp2/source", "emitUnmatched", "{}");
            var captured = f.capture(parent, entry); var attempt = f.process(captured);
            assertTrue(attempt.isComplete()); var result = attempt.processResult(); assertTrue(result.commits());
            var receipt = result.managedTransitionReceipts().stream()
                    .filter(row -> row.documentId().value().equals(parent.id().value())).findFirst().orElseThrow();
            assertNotEquals(receipt.beforeBlueId(), receipt.afterBlueId());
            var document = result.resultingDocuments().stream().filter(row -> row.documentId().equals(receipt.documentId())).findFirst().orElseThrow();
            assertEquals(0, document.epoch(), "Actual dependency normalization is not an owned numbered change");
            var transition = new ManagedRepresentationTransition(receipt.documentId(), 0, anchor.transitionReceiptIdentity(),
                    anchor.transitionReceiptIdentity(), captured.input(), result, receipt.transitionReceiptIdentity());
            String occurrence = "sha256:" + "b".repeat(64);
            var cause = new ManagedRepresentationCause(occurrence, transition, transition.positionIdentity(), null, null);
            var coordinates = ManagedEpochApplicationWork.identified("sha256:" + "c".repeat(64), "sha256:" + "d".repeat(64),
                    anchor.transitionReceiptIdentity(), parent.id(), 0, DocumentId.of("independent-work-consumer"), occurrence,
                    "/child", 1, 0, source.snapshot().blueId(), 1);
            var representation = ManagedEpochApplicationWork.identifiedRepresentation(coordinates, cause);
            var successor = ManagedEpochApplicationWork.identifiedWithSuccessorRepresentationCause(coordinates, cause);
            var codec = new ManagedWorkStorageCodec(MAX, 256);
            assertNotEquals(representation.workIdentity(), successor.workIdentity());
            for (var original : List.of(coordinates, representation, successor)) {
                byte[] bytes = codec.encode(original); int reads = f.providerReads.get();
                var restored = codec.decode(bytes);
                assertEquals(reads, f.providerReads.get()); assertArrayEquals(bytes, codec.encode(restored));
                assertEquals(original.workIdentity(), restored.workIdentity());
                assertEquals(original.isRepresentationApplication(), restored.isRepresentationApplication());
                assertEquals(original.expectedNextSourceEpoch(), restored.expectedNextSourceEpoch());
                assertEquals(original.successorRepresentationCause().map(ProcessingCause::causeIdentity),
                        restored.successorRepresentationCause().map(ProcessingCause::causeIdentity));
            }
            assertThrows(CoordinationObjectStorageException.class, () -> codec.decode(workPacket(representation, cause, cause)));
            assertThrows(CoordinationObjectStorageException.class, () -> codec.decode(workPacket(representation, null, cause)));
            assertThrows(CoordinationObjectStorageException.class, () -> codec.decode(workPacket(representation, captured.input().cause(), null)));
            assertThrows(CoordinationObjectStorageException.class, () -> codec.decode(workPacket(successor, null, null)));
        }
    }

    private static byte[] workPacket(ManagedEpochApplicationWork work, ProcessingCause representation, ProcessingCause successor) {
        return SessionStorageWire.encode(MAX, out -> {
            out.text("blue-coordination/managed-work-storage/1");
            out.text(work.workIdentity()); out.text(work.planIdentity()); out.text(work.barrierIdentity());
            out.text(work.sourceReceiptIdentity()); out.text(work.sourceDocumentId().value()); out.longValue(work.sourceEpoch());
            out.text(work.consumerDocumentId().value()); out.text(work.targetOccurrenceIdentity()); out.text(work.targetPath());
            out.longValue(work.activationGeneration()); out.longValue(work.expectedConsumerCommittedEpoch());
            out.text(work.expectedConsumerCommittedBlueId()); out.longValue(work.expectedGraphGeneration());
            SessionRecordCodec.optional(out, representation, (w, cause) -> w.bytes(LANGUAGE.encodeProcessingCause(cause)));
            SessionRecordCodec.optional(out, successor, (w, cause) -> w.bytes(LANGUAGE.encodeProcessingCause(cause)));
        });
    }

    private static final class Fixture implements AutoCloseable {
        final Map<String, String> exact = new HashMap<>();
        final AtomicInteger providerReads = new AtomicInteger();
        final BlueCoordination blue = BlueCoordination.builder().contentDerivedDocumentIds().exactNodeProvider(id -> {
            providerReads.incrementAndGet(); return Optional.ofNullable(exact.get(id));
        }).build();
        final DefaultCoordinationEngine engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
        final Map<String, TimelineHandle> timelines = new HashMap<>();
        DocumentHandle start(String name, String timeline, ActivationPolicy policy) throws Exception { return startYaml(resource(name), timeline, policy); }
        DocumentHandle startYaml(String yaml, String timeline, ActivationPolicy policy) {
            timelines.computeIfAbsent(timeline, id -> blue.timelines().register(id, "alice"));
            var value = blue.values().yaml(yaml); exact.put(value.blueId(), value.json());
            var document = blue.documents().admitStaticProcessEmbedded(yaml, policy).document("root"); retain(document); return document;
        }
        void retain(DocumentHandle document) { exact.put(document.snapshot().blueId(), document.snapshot().exact().json()); }
        EntryHandle append(DocumentHandle document, String timeline, String operation, String request) {
            return blue.operations().on(document).from(timelines.get(timeline)).call(operation).through("owner").requestYaml(request).submit();
        }
        void apply(DocumentHandle document, EntryHandle entry) {
            var result = blue.advanced().process(document, entry, ContractsExecutionPolicy.releaseDefault());
            assertEquals(EntryDisposition.APPLIED, result.entry(entry).disposition(), result.entry(entry).diagnostic().toString());
        }
        void settle(DocumentHandle document) {
            for (int i = 0; i < 24; i++) {
                var progress = blue.processing().processNext(document);
                assertFalse(progress.blocked(), progress.diagnostic().toString());
                if (progress.quiescent()) return;
            }
            fail("Actual source fixture did not settle within its bounded steps");
        }
        ContractsClosureAdapter.CohortInvocation capture(DocumentHandle document, EntryHandle entry) {
            return engine.contractsClosureAdapter().captureRoot(document.id(), engine.auditTimelineEntry(entry.blueId()).orElseThrow()).invocations().get(0);
        }
        ClosureAttemptResult process(ContractsClosureAdapter.CohortInvocation invocation) {
            var processor = new BlueClosureContracts(engine.runtime().documentProcessor());
            return invocation.retryInput() == null ? processor.processClosure(invocation.input()) : processor.processClosureRetry(invocation.retryInput());
        }
        List<String> history(DocumentHandle document) { return blue.advanced().auditManagedEpochs(document.id()).stream().map(row -> row.receiptIdentity()).toList(); }
        @Override public void close() { blue.close(); }
    }

    private static final class Bytes implements CoordinationImmutableObjectStore {
        final Map<String, byte[]> values = new HashMap<>(); final Set<String> reads = new HashSet<>();
        @Override public byte[] putIfAbsent(String key, byte[] bytes) {
            byte[] retained = values.putIfAbsent(key, bytes.clone());
            if (retained != null && !Arrays.equals(retained, bytes)) throw new CoordinationObjectStorageException("Conflicting object");
            return (retained == null ? bytes : retained).clone();
        }
        @Override public Optional<byte[]> get(String key, int maximumBytes) {
            reads.add(key); byte[] bytes = values.get(key);
            if (bytes != null && bytes.length > maximumBytes) throw new CoordinationObjectStorageException("Oversized object");
            return Optional.ofNullable(bytes == null ? null : bytes.clone());
        }
    }
    private static String resource(String name) throws Exception {
        try (var stream = CohortInvocationStorageCodecTest.class.getResourceAsStream("/rooted/" + name)) {
            return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
