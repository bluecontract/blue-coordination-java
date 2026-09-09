package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.SourceHistoryPrerequisite;
import blue.coordination.api.SourceHistoryPrerequisiteResult;
import blue.coordination.internal.CoordinationTestControl;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real source admission and historical acquisition are individually observable SDK operations. */
final class RootedSourcePrerequisiteTest {
    @Test void unknownAdmissionAndEarlierLiveAreSeparateAndDoNotProcessFuture50() throws IOException {
        assertEquals(runDiscovery(false), runDiscovery(true));
    }

    static Map<String, Object> runDiscovery(boolean suspended) throws IOException {
        SourceInput input = sourceInput();
        try (var f = new RootedSdkFixture()) {
            var blue = f.blue;
            var a = f.startYaml(RootedSdkFixture.resource("parent.yaml").replace("RCP2 Parent", "RCP2 Discovery A")
                    .replace("rcp2/parent", "rcp2/discovery-a"), "rcp2/discovery-a");
            var b = f.startYaml(RootedSdkFixture.resource("parent.yaml").replace("RCP2 Parent", "RCP2 Discovery B")
                    .replace("rcp2/parent", "rcp2/discovery-b"), "rcp2/discovery-b");
            f.timelines.put("rcp2/source", blue.timelines().register("rcp2/source", "alice"));
            var e15 = f.appendReference(input.id(), "rcp2/source", "setCounter", 15, "counterValue: 5", false);
            var e50 = f.appendReference(input.id(), "rcp2/source", "setCounter", 50, "counterValue: 99", false);
            var e10 = f.append(a, "rcp2/discovery-a", "attach", 10, "child:\n  blueId: " + input.id());
            var e20 = f.append(b, "rcp2/discovery-b", "attach", 20, "child:\n  blueId: " + input.id());
            String aBefore = a.snapshot().blueId(), bBefore = b.snapshot().blueId();
            if (suspended) {
                assertEquals(EntryDisposition.NEEDS_RESOURCES, blue.processing().processNext(a).entry(e10).disposition());
                assertTrue(blue.advanced().sourceHistoryPrerequisites(a).isEmpty(), "No exact source body is not admission authority");
            }
            f.exact.put(input.id(), input.json());
            var loader = suspended ? b : a; var attachment = suspended ? e20 : e10;
            assertEquals(EntryDisposition.NEEDS_RESOURCES, blue.processing().processNext(loader).entry(attachment).disposition());
            var selectedAdmission = one(blue, loader, SourceHistoryPrerequisite.Kind.ADMISSION);
            assertEquals(input.id(), selectedAdmission.authoredBlueId());
            assertEquals(input.id(), selectedAdmission.sourceDocumentId().value());
            assertEquals(suspended ? 20 : 10, ((Number) selectedAdmission.cutoffExclusive().components().get(0)).intValue());
            var admitted = blue.advanced().processSourceHistoryPrerequisite(selectedAdmission);
            assertTrue(admitted.admission().orElseThrow().published());
            assertTrue(admitted.processing().isEmpty());
            assertEquals(List.of(DocumentId.of(input.id())), admitted.admission().orElseThrow().documentIds());
            assertTrue(admitted.admission().orElseThrow().attempt().processResult().totalGas() > 0L);
            assertEquals(aBefore, a.snapshot().blueId()); assertEquals(bBefore, b.snapshot().blueId());
            var source = blue.documents().require(DocumentId.of(input.id()));
            assertEquals(1, f.history(source).size());
            assertEquals("FULL_HISTORY", ((Map<?, ?>) f.control.historyBasis(source.id()).get("admission")).get("mode"));
            var repeatedAdmission = blue.advanced().processSourceHistoryPrerequisite(selectedAdmission);
            assertTrue(repeatedAdmission.replayed());
            assertEquals(admitted.admission(), repeatedAdmission.admission());
            assertEquals(1, f.history(source).size());

            if (!suspended) {
                assertTrue(blue.advanced().sourceHistoryPrerequisites(a).isEmpty(), "E15 is after A10");
                assertEquals(EntryDisposition.APPLIED, blue.processing().processNext(a).entry(e10).disposition());
                assertEquals(1, blue.processing().processNext(a).managedEpochApplications().size());
                assertEquals(0L, ((Number) f.selected(a, "/child").scalarAt("/counter")).longValue());
                assertEquals(EntryDisposition.NEEDS_RESOURCES, blue.processing().processNext(b).entry(e20).disposition());
            }
            var live = one(blue, b, SourceHistoryPrerequisite.Kind.LIVE);
            assertEquals(e15.blueId(), live.entryBlueId());
            assertNotEquals(e50.blueId(), live.entryBlueId());
            assertTrue(blue.advanced().sourceHistoryProcessingResult(live).isEmpty());
            var sourceResult = blue.advanced().processSourceHistoryPrerequisite(live);
            var publicResult = blue.advanced().sourceHistoryProcessingResult(live).orElseThrow();
            assertEquals(List.of(e15.blueId()), publicResult.entries().stream().map(row -> row.entry().blueId()).toList());
            assertEquals(sourceResult.processing().orElseThrow().committedProcessTransitions(), publicResult.stats().committedTransitions());
            assertTrue(blue.advanced().sourceHistoryProcessingResult(selectedAdmission).isEmpty());
            assertTrue(sourceResult.admission().isEmpty());
            assertEquals(List.of(e15.blueId()), sourceResult.processing().orElseThrow().processedEntries().stream()
                    .map(entry -> entry.blueId()).toList());
            assertEquals(1L, sourceResult.processing().orElseThrow().committedProcessTransitions());
            assertEquals(bBefore, b.snapshot().blueId(), "Source execution cannot silently apply B20");
            assertEquals(2, f.history(source).size());
            assertTrue(blue.advanced().sourceHistoryPrerequisites(b).isEmpty(), "Future50 is not a prerequisite of B20");
            assertEquals(EntryDisposition.APPLIED, blue.processing().processNext(b).entry(e20).disposition());
            assertEquals(1, blue.processing().processNext(b).managedEpochApplications().size());
            assertEquals(1, blue.processing().processNext(b).managedEpochApplications().size());
            assertEquals(5L, ((Number) f.selected(b, "/child").scalarAt("/counter")).longValue());
            assertEquals(5L, b.snapshot().longAt("/seen"));
            if (suspended) {
                assertEquals(EntryDisposition.APPLIED, blue.processing().processNext(a).entry(e10).disposition());
                assertEquals(1, blue.processing().processNext(a).managedEpochApplications().size());
                assertEquals(0L, ((Number) f.selected(a, "/child").scalarAt("/counter")).longValue(), "A10 must not substitute source's current E15 state");
            }
            var sourceHistory = f.history(source); var aHistory = f.history(a); var bHistory = f.history(b);
            String sourceHead = source.snapshot().blueId(), aHead = a.snapshot().blueId(), bHead = b.snapshot().blueId();
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertEquals(sourceHead, source.snapshot().blueId()); assertEquals(sourceHistory, f.history(source));
            assertEquals(aHead, a.snapshot().blueId()); assertEquals(aHistory, f.history(a));
            assertEquals(bHead, b.snapshot().blueId()); assertEquals(bHistory, f.history(b));
            assertTrue(blue.advanced().sourceHistoryPrerequisites(b).isEmpty());
            assertEquals(2, sourceHistory.size(), "No future50 or duplicate initialization/source event");
            return Map.of("sourceBasis", f.control.historyBasis(source.id()), "sourceHistory", sourceHistory,
                    "sourceHead", sourceHead, "bHead", bHead, "bHistory", bHistory);
        }
    }

    @Test void unavailableCompletenessBlocksTheNewAdmissionLane() throws IOException {
        SourceInput input = sourceInput();
        try (var f = new RootedSdkFixture()) {
            var parent = setup(f, input, true);
            var control = CoordinationTestControl.attach(f.blue.advanced().rawEngine());
            control.makeHistoricalUnavailable("source Timeline completeness unavailable");
            String before = parent.snapshot().blueId();
            assertFalse(f.blue.processing().processNext(parent).quiescent());
            var wait = one(f.blue, parent, SourceHistoryPrerequisite.Kind.WAIT);
            assertTrue(wait.diagnostic().contains("completeness unavailable"));
            assertThrows(RuntimeException.class, () -> f.blue.advanced().processSourceHistoryPrerequisite(wait));
            assertEquals(before, parent.snapshot().blueId()); assertEquals(1, f.history(parent).size());
            control.restartFromStores();
            control.makeHistoricalUnavailable("source Timeline completeness unavailable");
            assertEquals(SourceHistoryPrerequisite.Kind.WAIT, one(f.blue, parent, SourceHistoryPrerequisite.Kind.WAIT).kind());
            control.makeHistoricalAvailable();
            assertEquals(SourceHistoryPrerequisite.Kind.ADMISSION,
                    one(f.blue, parent, SourceHistoryPrerequisite.Kind.ADMISSION).kind());
            control.invalidateHistoricalEvidence("invalid source completeness");
            assertThrows(RuntimeException.class, () -> f.blue.advanced().sourceHistoryPrerequisites(parent));
            assertEquals(before, parent.snapshot().blueId()); assertEquals(1, f.history(parent).size());
        }
    }

    @Test void sourceSelectionRejectsAChangedJournalBeforeAdmission() throws IOException {
        SourceInput input = sourceInput();
        try (var f = new RootedSdkFixture()) {
            var parent = setup(f, input, true); f.blue.processing().processNext(parent);
            var original = one(f.blue, parent, SourceHistoryPrerequisite.Kind.ADMISSION);
            f.appendReference(input.id(), "rcp2/source", "setCounter", 51, "counterValue: 100", false);
            assertThrows(IllegalArgumentException.class, () -> f.blue.advanced().processSourceHistoryPrerequisite(original));
            var refreshed = one(f.blue, parent, SourceHistoryPrerequisite.Kind.ADMISSION);
            assertNotEquals(original.selectionIdentity(), refreshed.selectionIdentity());
            assertEquals(original.workIdentity(), refreshed.workIdentity(), "Provider revision is not source admission semantics");
            assertEquals(1, f.history(parent).size());
        }
    }

    @Test void alreadyKnownSourceSelectsLiveWithoutAnotherAdmission() throws IOException {
        SourceInput input = sourceInput();
        try (var f = new RootedSdkFixture()) {
            var source = f.startYaml(RootedSdkFixture.resource("source.yaml"), "rcp2/source");
            assertEquals(input.id(), source.id().value());
            var parent = setup(f, input, false);
            assertFalse(f.blue.processing().processNext(parent).quiescent());
            var live = one(f.blue, parent, SourceHistoryPrerequisite.Kind.LIVE);
            var result = f.blue.advanced().processSourceHistoryPrerequisite(live);
            assertTrue(result.admission().isEmpty()); assertEquals(1, result.processing().orElseThrow().processedEntries().size());
            assertEquals(2, f.history(source).size()); assertEquals(1, f.history(parent).size());
        }
    }

    @Test void alreadyKnownSourceRetainedHistoryRunsOneApplicationAtATime() throws IOException {
        try (var f = new RootedSdkFixture()) {
            var leaf = f.start("source.yaml", "rcp2/source", Map.of());
            var leafEntry = f.append(leaf, "rcp2/source", "setCounter", 5, "counterValue: 5");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(leaf).entry(leafEntry).disposition());
            var source = f.startYaml(RootedSdkFixture.resource("parent.yaml").replace("RCP2 Parent", "Retained source")
                    .replace("rcp2/parent", "rcp2/retained-source"), "rcp2/retained-source");
            var attachLeaf = f.append(source, "rcp2/retained-source", "attach", 12,
                    "child:\n  blueId: " + leaf.id().value());
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(source).entry(attachLeaf).disposition());
            var parent = f.start("parent.yaml", "rcp2/parent", Map.of());
            var attachSource = f.append(parent, "rcp2/parent", "attach", 20, "child:\n  blueId: " + source.id().value());
            String parentBefore = parent.snapshot().blueId(); var leafHistory = f.history(leaf);
            assertEquals(EntryDisposition.NEEDS_RESOURCES, f.blue.processing().processNext(parent).entry(attachSource).disposition());
            var first = one(f.blue, parent, SourceHistoryPrerequisite.Kind.MANAGED_HISTORY);
            var firstResult = f.blue.advanced().processSourceHistoryPrerequisite(first);
            assertEquals(1, firstResult.processing().orElseThrow().managedEpochApplications().size());
            assertTrue(firstResult.processing().orElseThrow().processedEntries().isEmpty());
            assertEquals(parentBefore, parent.snapshot().blueId());
            var second = one(f.blue, parent, SourceHistoryPrerequisite.Kind.MANAGED_HISTORY);
            assertNotEquals(first.workIdentity(), second.workIdentity());
            var secondResult = f.blue.advanced().processSourceHistoryPrerequisite(second);
            assertEquals(1, secondResult.processing().orElseThrow().managedEpochApplications().size());
            assertEquals(5L, source.snapshot().longAt("/seen"));
            assertEquals(parentBefore, parent.snapshot().blueId()); assertEquals(leafHistory, f.history(leaf));
            assertTrue(f.blue.advanced().sourceHistoryPrerequisites(parent).isEmpty());
        }
    }

    @Test void knownSourceRootedRetainedWorkIsOneDistinctPrerequisite() throws IOException {
        try (var f = new RootedSdkFixture()) {
            var nodes = new java.util.LinkedHashMap<String, DocumentHandle>();
            String template = RootedSdkFixture.resource("node-graph.template.json");
            for (String name : List.of("A", "B", "C")) {
                String yaml = template.replace("<NODE>", name).replace("<NAMESPACE>", "source-prerequisite-local")
                        .replace("<TIMELINE>", "rcp/source-prerequisite/" + name);
                var original = f.blue.values().yaml(yaml); f.exact.put(original.blueId(), original.json());
                nodes.put(name, f.startYaml(yaml, "rcp/source-prerequisite/" + name));
            }
            var a = nodes.get("A"); var b = nodes.get("B"); var c = nodes.get("C");
            var ab = f.append(a, "rcp/source-prerequisite/A", "attach", 10, "edge: b\nsource: {blueId: " + b.id().value() + "}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(a).entry(ab).disposition());
            assertEquals(1, f.blue.processing().processNext(a).managedEpochApplications().size());
            var bc = f.append(b, "rcp/source-prerequisite/B", "attach", 12, "edge: c\nsource: {blueId: " + c.id().value() + "}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(b).entry(bc).disposition());
            assertEquals(1, f.blue.processing().processNext(b).managedEpochApplications().size());
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(a).entry(bc).disposition());
            var parent = f.start("parent.yaml", "rcp2/parent", Map.of());
            var pa = f.append(parent, "rcp2/parent", "attach", 20, "child:\n  blueId: " + a.id().value());
            String before = parent.snapshot().blueId(); var bHistory = f.history(b); var cHistory = f.history(c);
            assertEquals(EntryDisposition.NEEDS_RESOURCES, f.blue.processing().processNext(parent).entry(pa).disposition());
            var selected = one(f.blue, parent, SourceHistoryPrerequisite.Kind.ROOTED_RETAINED);
            var result = f.blue.advanced().processSourceHistoryPrerequisite(selected);
            assertTrue(result.admission().isEmpty());
            assertEquals(1, result.processing().orElseThrow().rootedRetainedAttempts().size());
            assertTrue(result.processing().orElseThrow().processedEntries().isEmpty());
            assertEquals(before, parent.snapshot().blueId());
            assertEquals(bHistory, f.history(b)); assertEquals(cHistory, f.history(c));
            assertTrue(f.blue.advanced().sourceHistoryPrerequisites(parent).isEmpty());
        }
    }

    @Test void exactBodyWithoutRegisteredTimelineRemainsUnavailable() throws IOException {
        SourceInput input = sourceInput();
        try (var f = new RootedSdkFixture()) {
            var parent = f.start("parent.yaml", "rcp2/parent", Map.of());
            f.exact.put(input.id(), input.json());
            var attach = f.append(parent, "rcp2/parent", "attach", 20, "child:\n  blueId: " + input.id());
            String before = parent.snapshot().blueId();
            assertEquals(EntryDisposition.NEEDS_RESOURCES, f.blue.processing().processNext(parent).entry(attach).disposition());
            var wait = one(f.blue, parent, SourceHistoryPrerequisite.Kind.WAIT);
            assertTrue(wait.diagnostic().contains("not registered"));
            assertThrows(RuntimeException.class, () -> f.blue.advanced().processSourceHistoryPrerequisite(wait));
            assertEquals(before, parent.snapshot().blueId()); assertEquals(1, f.history(parent).size());
            f.timelines.put("rcp2/source", f.blue.timelines().register("rcp2/source", "alice"));
            assertEquals(SourceHistoryPrerequisite.Kind.ADMISSION,
                    one(f.blue, parent, SourceHistoryPrerequisite.Kind.ADMISSION).kind());
        }
    }

    @Test void wrongRegisteredActorCannotAuthenticateTheSourceWindow() throws IOException {
        SourceInput input = sourceInput();
        try (var f = new RootedSdkFixture()) {
            var parent = f.start("parent.yaml", "rcp2/parent", Map.of());
            f.timelines.put("rcp2/source", f.blue.timelines().register("rcp2/source", "mallory"));
            f.exact.put(input.id(), input.json());
            f.append(parent, "rcp2/parent", "attach", 20, "child:\n  blueId: " + input.id());
            String before = parent.snapshot().blueId();
            assertThrows(RuntimeException.class, () -> f.blue.processing().processNext(parent));
            assertEquals(before, parent.snapshot().blueId()); assertEquals(1, f.history(parent).size());
        }
    }

    @Test void unchangedDigestCannotAuthorizeAChangedSourceDescriptor() throws IOException {
        SourceInput input = sourceInput();
        try (var f = new RootedSdkFixture()) {
            var parent = setup(f, input, true); f.blue.processing().processNext(parent);
            var exact = one(f.blue, parent, SourceHistoryPrerequisite.Kind.ADMISSION);
            var forged = new SourceHistoryPrerequisite(exact.selectionIdentity(), exact.requestingRoot(),
                    exact.requestingInvocationIdentity(), exact.demandIdentity(), parent.id(), exact.authoredBlueId(),
                    exact.cutoffExclusive(), exact.kind(), exact.sourceEpoch(), exact.sourceBlueId(), exact.workIdentity(),
                    exact.entryBlueId(), exact.journalRevision(), exact.routeGeneration(), exact.sourceSurfaceIdentity(), exact.diagnostic());
            assertThrows(IllegalArgumentException.class, () -> f.blue.advanced().processSourceHistoryPrerequisite(forged));
            assertEquals(1, f.history(parent).size());
            assertEquals(exact, one(f.blue, parent, SourceHistoryPrerequisite.Kind.ADMISSION));
        }
    }

    @Test void lostLivePublicationResponseReconcilesOnlyTheActualRetainedResult() throws IOException {
        SourceInput input = sourceInput();
        try (var f = new RootedSdkFixture()) {
            var source = f.startYaml(RootedSdkFixture.resource("source.yaml"), "rcp2/source");
            var parent = setup(f, input, false); f.blue.processing().processNext(parent);
            var selected = one(f.blue, parent, SourceHistoryPrerequisite.Kind.LIVE);
            String before = parent.snapshot().blueId();
            f.control.failPublicationAt("AFTER_STORE_COMMIT_BEFORE_ROUTE_PUBLISH");
            assertThrows(RuntimeException.class, () -> f.blue.advanced().processSourceHistoryPrerequisite(selected));
            f.control.clearPublicationFailure();
            assertEquals(2, f.history(source).size());
            var history = f.history(source);
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            var recovered = f.blue.advanced().processSourceHistoryPrerequisite(selected);
            assertTrue(recovered.replayed());
            assertEquals(0L, recovered.processing().orElseThrow().committedProcessTransitions());
            assertEquals(history, f.history(source)); assertEquals(before, parent.snapshot().blueId());
            assertTrue(f.blue.advanced().sourceHistoryPrerequisites(parent).isEmpty());
        }
    }

    private static DocumentHandle setup(RootedSdkFixture f, SourceInput input, boolean provider) throws IOException {
        var parent = f.start("parent.yaml", "rcp2/parent", Map.of());
        f.timelines.putIfAbsent("rcp2/source", f.blue.timelines().register("rcp2/source", "alice"));
        f.appendReference(input.id(), "rcp2/source", "setCounter", 15, "counterValue: 5", false);
        f.appendReference(input.id(), "rcp2/source", "setCounter", 50, "counterValue: 99", false);
        f.append(parent, "rcp2/parent", "attach", 20, "child:\n  blueId: " + input.id());
        if (provider) f.exact.put(input.id(), input.json());
        return parent;
    }
    private static SourceHistoryPrerequisite one(BlueCoordination blue, DocumentHandle root, SourceHistoryPrerequisite.Kind kind) {
        var selected = blue.advanced().sourceHistoryPrerequisites(root);
        assertEquals(1, selected.size(), selected.toString()); assertEquals(kind, selected.get(0).kind()); return selected.get(0);
    }
    private static SourceInput sourceInput() throws IOException {
        try (var preparation = BlueCoordination.builder().contentDerivedDocumentIds().build()) {
            var value = preparation.values().yaml(RootedSdkFixture.resource("source.yaml"));
            return new SourceInput(value.blueId(), value.json());
        }
    }
    private record SourceInput(String id, String json) { }
}
