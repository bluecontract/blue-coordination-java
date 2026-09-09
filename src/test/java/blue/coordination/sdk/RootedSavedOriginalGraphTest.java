package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Saved authored graph inputs with explicit full-history admission. */
final class RootedSavedOriginalGraphTest {
    @Test void threeNodeRingKeepsExactHistoricalViewsAndFinishesWithin32Steps() throws Exception {
        run(new String[][]{{"A", "b", "B"}, {"B", "c", "C"}, {"C", "a", "A"}});
    }

    @Test void threeNodeRingCompletesAfterExactPreAnchorSourcePrerequisites() throws Exception {
        run(new String[][]{{"A", "b", "B"}, {"B", "c", "C"}, {"C", "a", "A"}}, true);
    }

    @Test void threeNodeRingWaitsForEarlierSourceWorkBeforeFreezingItsJoinAnchor() throws Exception {
        run(new String[][]{{"A", "b", "B"}, {"B", "c", "C"}, {"C", "a", "A"}}, false, true);
    }

    @Test void figureEightKeepsBothSavedOriginalLoopsAndTheirHistories() throws Exception {
        run(new String[][]{{"A", "b", "B"}, {"B", "a", "A"}, {"A", "c", "C"}, {"C", "a", "A"}});
    }

    @Test void localPendingHistoryMustFinishBeforeRootIsReady() throws Exception {
        String template;
        try (var in = getClass().getResourceAsStream("/rooted/node-graph.template.json")) {
            template = new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
        try (var f = new RootedSdkFixture()) {
            var originals = new LinkedHashMap<String, ExactBlueValue>();
            var handles = new LinkedHashMap<String, DocumentHandle>();
            for (String name : List.of("A", "B", "C")) {
                String yaml = template.replace("<NODE>", name).replace("<NAMESPACE>", "rooted-local-pending")
                        .replace("<TIMELINE>", "rcp/local/" + name);
                var exact = f.blue.values().yaml(yaml); originals.put(name, exact); f.exact.put(exact.blueId(), exact.json());
                handles.put(name, f.startYaml(yaml, "rcp/local/" + name));
            }
            int ordinal = 0;
            EntryHandle last = null;
            for (String[] edge : new String[][]{{"A", "b", "B"}, {"B", "c", "C"}}) {
                var root = handles.get(edge[0]);
                last = f.append(root, "rcp/local/" + edge[0], "attach", ++ordinal * 100L,
                        "edge: " + edge[1] + "\nsource: {blueId: " + originals.get(edge[2]).blueId() + "}");
                assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(root).entry(last).disposition());
                for (int i = 0; i < 32; i++) if (f.blue.processing().processNext(root).quiescent()) break;
                assertTrue(f.blue.processing().processNext(root).quiescent());
            }
            var sourceHeads = List.of(handles.get("B").snapshot().blueId(), handles.get("C").snapshot().blueId());
            var sourceHistories = List.of(f.history(handles.get("B")), f.history(handles.get("C")));
            var priorReady = handles.get("A").snapshot().blueId();
            var applied = f.blue.processing().processNext(handles.get("A"));
            assertEquals(EntryDisposition.APPLIED, applied.entry(last).disposition());
            var selected = f.control.selectedView(handles.get("A").id());
            assertTrue(selected.occurrences().stream().anyMatch(row -> row.sourceDocumentId().value().equals(handles.get("B").id().value())
                    && row.sourcePath().equals("/peers/c") && !row.active() && Long.valueOf(-1L).equals(row.pendingHistoricalEpoch())));
            var calculated = f.control.precomputeLocalInitialization(handles.get("A").id(), last.blueId());
            assertTrue(calculated.commits(), "Actual local retained initialization must compute: " + calculated.diagnostic());
            assertEquals(List.of(new blue.language.processor.closure.DocumentId(handles.get("A").id().value())),
                    calculated.rootedProjection().ownedDocuments().stream().map(doc -> doc.documentId()).toList());
            assertTrue(calculated.occurrenceBindings().stream().anyMatch(row -> row.sourceDocumentId().value().equals(handles.get("B").id().value())
                    && row.sourcePath().equals("/peers/c") && row.active()));
            assertEquals(selected.closureIdentity(), f.control.selectedView(handles.get("A").id()).closureIdentity());
            assertEquals(sourceHeads, List.of(handles.get("B").snapshot().blueId(), handles.get("C").snapshot().blueId()));
            assertEquals(sourceHistories, List.of(f.history(handles.get("B")), f.history(handles.get("C"))));
            System.out.println("LOCAL_RETAINED_CALCULATION_PROVED gas=" + calculated.totalGas());
            assertFalse(applied.quiescent(), "Pending root-local B:/peers/c must prevent readiness of A");
            assertFalse(f.blue.advanced().auditManagedDocumentReadiness(handles.get("A").id()).orElseThrow().ready());
            assertEquals(priorReady, handles.get("A").snapshot().blueId(), "Normal SDK reads retain the earlier ready head");
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertFalse(f.blue.advanced().auditManagedDocumentReadiness(handles.get("A").id()).orElseThrow().ready());
            var selectedWork = f.control.localHistoryWork(handles.get("A").id());
            var auditedWork = f.blue.advanced().auditManagedEpochApplicationWork(selectedWork.workIdentity()).orElseThrow();
            assertEquals(selectedWork.workIdentity(), auditedWork.workIdentity());
            assertEquals(selectedWork.planIdentity(), auditedWork.planIdentity());
            assertEquals(selectedWork.sourceReceiptIdentity(), auditedWork.sourceReceiptIdentity());
            assertEquals(selectedWork.expectedConsumerCommittedBlueId(), auditedWork.expectedConsumerCommittedBlueId());
            var beforeRejected = f.control.selectedView(handles.get("A").id()).closureIdentity();
            assertThrows(RuntimeException.class, () -> f.blue.advanced().processRetained(handles.get("A"), "sha256:" + "0".repeat(64)));
            assertEquals(beforeRejected, f.control.selectedView(handles.get("A").id()).closureIdentity());
            assertEquals(sourceHistories, List.of(f.history(handles.get("B")), f.history(handles.get("C"))));
            var settled = f.blue.advanced().processRetained(handles.get("A"), selectedWork.workIdentity());
            assertTrue(settled.entries().isEmpty(), "Historical application is not another external operation");
            assertTrue(settled.managedEpochApplications().isEmpty(), "No independent consumer revision was manufactured");
            assertEquals(1, settled.rootedRetainedResults().size());
            var retainedWork = settled.rootedRetainedApplications().get(0);
            assertEquals(selectedWork.workIdentity(), retainedWork.work().workIdentity());
            assertEquals(handles.get("A").id(), retainedWork.rootDocumentId());
            assertEquals(handles.get("B").id(), retainedWork.work().consumerDocumentId());
            assertEquals(handles.get("C").id(), retainedWork.work().sourceDocumentId());
            var local = settled.rootedRetainedResults().get(0);
            assertEquals(EntryDisposition.APPLIED, local.disposition());
            assertTrue(local.managedSurfaceEvidence().documentTransitions().stream()
                    .allMatch(change -> change.documentId().equals(handles.get("A").id())));
            assertTrue(local.managedSurfaceEvidence().graphChanges().stream()
                    .allMatch(change -> change.sourceDocumentId().equals(handles.get("A").id())));
            assertTrue(local.managedSurfaceEvidence().componentTransitions().stream().flatMap(change -> change.after().stream())
                    .flatMap(component -> component.memberDocumentIds().stream()).allMatch(handles.get("A").id()::equals));
            assertEquals(calculated.totalGas(), local.stats().gas());
            assertEquals(calculated.totalGas(), settled.stats().gas());
            assertEquals(List.of(handles.get("A").id()), local.changes().stream().map(DocumentChange::documentId).toList());
            assertEquals(calculated.outputClosureIdentity(), f.control.selectedView(handles.get("A").id()).closureIdentity());
            var rootHistory = f.blue.advanced().auditManagedEpochs(handles.get("A").id());
            var localReceipt = rootHistory.get(rootHistory.size() - 1);
            assertEquals(DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION, localReceipt.kind());
            var actualSourceReceipt = f.blue.advanced().auditManagedEpochs(handles.get("C").id()).get(0);
            assertEquals(actualSourceReceipt.receiptIdentity(), retainedWork.work().sourceReceiptIdentity());
            assertEquals(actualSourceReceipt.sourceOrder(), localReceipt.sourceOrder());
            assertEquals(actualSourceReceipt.sourceEntry(), localReceipt.sourceEntry());
            assertInstanceOf(blue.language.processor.closure.ManagedRevisionCause.class,
                    f.blue.advanced().closureInvocation(local.closureId()).orElseThrow().cause());
            assertTrue(settled.quiescent());
            assertTrue(f.blue.advanced().auditManagedDocumentReadiness(handles.get("A").id()).orElseThrow().ready());
            assertTrue(f.blue.processing().processNext(handles.get("A")).quiescent(), "Finite local history must complete");
            assertTrue(f.control.selectedView(handles.get("A").id()).occurrences().stream().anyMatch(row ->
                    row.sourceDocumentId().value().equals(handles.get("B").id().value()) && row.sourcePath().equals("/peers/c") && row.active()));
            assertEquals(sourceHeads, List.of(handles.get("B").snapshot().blueId(), handles.get("C").snapshot().blueId()));
            assertEquals(sourceHistories, List.of(f.history(handles.get("B")), f.history(handles.get("C"))));
        }
    }

    private static void run(String[][] edges) throws Exception {
        run(edges, false);
    }

    private static void run(String[][] edges, boolean settlePreAnchorSource) throws Exception {
        run(edges, settlePreAnchorSource, false);
    }

    private static void run(String[][] edges, boolean settlePreAnchorSource, boolean expectJoinPrerequisite) throws Exception {
        String template;
        try (var in = RootedSavedOriginalGraphTest.class.getResourceAsStream("/rooted/node-graph.template.json")) {
            template = new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
        try (var f = new RootedSdkFixture()) {
            var originals = new LinkedHashMap<String, ExactBlueValue>();
            var handles = new LinkedHashMap<String, DocumentHandle>();
            for (String name : List.of("A", "B", "C")) {
                String source = template.replace("<NODE>", name).replace("<NAMESPACE>", "rooted-saved-graph")
                        .replace("<TIMELINE>", "rcp/saved/" + name);
                var exact = f.blue.values().yaml(source);
                originals.put(name, exact); f.exact.put(exact.blueId(), exact.json());
                var handle = f.startYaml(source, "rcp/saved/" + name);
                handles.put(name, handle);
                assertEquals(exact.blueId(), handle.id().value());
            }
            int ordinal = 0;
            var attachedInputs = new java.util.LinkedHashSet<String>();
            EntryHandle precedingAttachment = null;
            for (String[] edge : edges) {
                if (settlePreAnchorSource && ordinal == 2) {
                    assertEquals("C", edge[0]);
                    assertEquals("A", edge[2]);
                    settleExactPreAnchorSource(f, handles, java.util.Objects.requireNonNull(precedingAttachment));
                }
                var replayedLiveInputs = new java.util.LinkedHashSet<String>();
                var root = handles.get(edge[0]);
                var saved = originals.get(edge[2]);
                System.out.println("SAVED_GRAPH_ATTACH " + String.join("/", edge));
                for (var item : handles.entrySet()) {
                    var view = f.control.selectedView(item.getValue().id());
                    System.out.println("SAVED_GRAPH_VIEW " + item.getKey() + " components=" + view.components()
                            .stream().map(component -> component.orderedMemberDocumentIds()).toList()
                            + " rows=" + view.occurrences().stream().map(row -> row.sourceDocumentId() + ":"
                                    + row.sourcePath() + "->" + row.targetDocumentId() + ":active=" + row.active()).toList());
                }
                var submitted = f.append(root, "rcp/saved/" + edge[0], "attach", ++ordinal * 100L,
                        "edge: " + edge[1] + "\nsource:\n  blueId: " + saved.blueId());
                var beforeHeads = handles.values().stream().map(doc -> doc.snapshot().blueId()).toList();
                var beforeHistories = histories(f, handles);
                var attached = f.blue.processing().processNext(root);
                boolean journalSettled = false;
                if (expectJoinPrerequisite && ordinal == 3) {
                    assertEquals(EntryDisposition.NEEDS_RESOURCES, attached.entry(submitted).disposition(),
                            "An incomplete causal source view must not become a frozen historical join anchor");
                    assertTrue(attached.blocked());
                    assertEquals(beforeHeads, handles.values().stream().map(doc -> doc.snapshot().blueId()).toList());
                    assertEquals(beforeHistories, histories(f, handles));
                    assertTrue(f.blue.advanced().auditManagedOccurrence(root.id(), "/peers/" + edge[1]).isEmpty());
                    CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
                    var retried = f.blue.processing().processNext(root);
                    assertEquals(EntryDisposition.NEEDS_RESOURCES, retried.entry(submitted).disposition());
                    assertEquals(beforeHistories, histories(f, handles));
                    settleExactPreAnchorSource(f, handles, java.util.Objects.requireNonNull(precedingAttachment), submitted);
                    attached = f.blue.processing().processNext(root);
                }
                if (!settlePreAnchorSource && !expectJoinPrerequisite && edges.length == 3 && ordinal == 3) {
                    assertEquals(EntryDisposition.NEEDS_RESOURCES, attached.entry(submitted).disposition());
                    assertTrue(attached.blocked());
                    assertEquals(beforeHeads, handles.values().stream().map(doc -> doc.snapshot().blueId()).toList());
                    assertEquals(beforeHistories, histories(f, handles));
                    assertTrue(f.blue.advanced().auditManagedOccurrence(root.id(), "/peers/" + edge[1]).isEmpty());
                    finishJoinWithPublicDriver(f, handles, submitted, attachedInputs);
                    journalSettled = true;
                }
                if (!journalSettled) assertEquals(EntryDisposition.APPLIED, attached.entry(submitted).disposition(),
                        "Saved attachment " + String.join("/", edge) + " " + attached.entry(submitted).diagnostic());
                for (int step = 0; !journalSettled && step < 32; step++) {
                    Map<String, List<String>> prefixes = histories(f, handles);
                    var result = f.blue.processing().processNext(root);
                    for (var prior : prefixes.entrySet()) assertEquals(prior.getValue(),
                            f.history(handles.get(prior.getKey())).subList(0, prior.getValue().size()));
                    if (result.quiescent()) break;
                    System.out.println("ATTACH_DRAIN root=" + edge[0] + " step=" + step + " blocked=" + result.blocked()
                            + " paused=" + result.paused() + " diagnostic=" + result.diagnostic()
                            + " local=" + result.rootedRetainedResults().stream().map(value -> value.disposition() + ":" + value.diagnostic()).toList()
                            + " history=" + result.managedEpochApplications().size()
                            + " state=" + f.control.localHistoryDescription(root.id()));
                    for (var live : result.entries()) {
                        assertEquals(EntryDisposition.APPLIED, live.disposition());
                        assertTrue(attachedInputs.contains(live.entry().blueId()), "Only an earlier actual attachment input is pending LIVE");
                        assertTrue(replayedLiveInputs.add(live.entry().blueId()), "The same LIVE input must not be delivered twice");
                    }
                    assertFalse(result.managedEpochApplications().isEmpty() && result.rootedRetainedResults().isEmpty()
                                    && result.entries().isEmpty(),
                            "No independently evidenced processing progress: " + result.diagnostic());
                }
                assertTrue(f.blue.processing().processNext(root).quiescent(), "Unfinished saved attachment " + String.join("/", edge));
                assertTrue(f.blue.advanced().auditManagedOccurrence(root.id(), "/peers/" + edge[1]).orElseThrow().active());
                attachedInputs.add(submitted.blueId());
                precedingAttachment = submitted;
                for (var doc : handles.values()) assertTrue(f.blue.advanced().auditManagedEpochs(doc.id())
                        .stream().allMatch(receipt -> receipt.emittedEvents().isEmpty()), "No duplicate initialization/source emission");
            }
            var heads = handles.values().stream().map(doc -> doc.snapshot().blueId()).toList();
            var histories = histories(f, handles);
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(heads, handles.values().stream().map(doc -> doc.snapshot().blueId()).toList());
            assertEquals(histories, histories(f, handles));
            for (var item : handles.entrySet()) {
                System.out.println("SAVED_GRAPH_RESTART_CHECK " + item.getKey() + " components="
                        + f.control.selectedView(item.getValue().id()).components().stream()
                                .map(component -> component.orderedMemberDocumentIds()).toList());
                assertTrue(f.blue.processing().processNext(item.getValue()).quiescent());
            }
            for (var doc : handles.values()) {
                var entry = f.append(doc, "rcp/saved/" + name(handles, doc), "touch", ++ordinal * 100L, "{}");
                var applied = applyWithin32(f, doc, entry, handles);
                assertEquals(EntryDisposition.APPLIED, applied.disposition());
                assertEquals(1L, doc.snapshot().longAt("/touches"));
            }
            for (var root : handles.entrySet()) {
                var selected = f.control.selectedView(root.getValue().id());
                System.out.println("FINITE_SELECTED " + root.getKey() + " docs=" + selected.managedDocuments().stream()
                        .map(doc -> doc.document().getProperties().get("nodeName").getValue() + ":epoch=" + doc.epoch()
                                + ":id=" + doc.blueId() + ":touches=" + doc.document().getProperties().get("touches").getValue()).toList()
                        + " rows=" + selected.occurrences().stream().map(row -> row.sourceDocumentId() + ":" + row.sourcePath()
                                + "->" + row.targetDocumentId() + ":active=" + row.active() + ":pending=" + row.pendingHistoricalEpoch()).toList());
            }
            var emitted = f.append(handles.get("C"), "rcp/saved/C", "emit", ++ordinal * 100L,
                    edges.length == 3 ? "to: B\nnext: A" : "to: A\nnext: B");
            assertEquals(EntryDisposition.APPLIED, applyWithin32(f, handles.get("A"), emitted, handles).disposition());
            var view = f.control.selectedView(handles.get("A").id());
            for (String name : List.of("A", "B", "C")) {
                var document = view.managedDocument(new blue.language.processor.closure.DocumentId(handles.get(name).id().value()));
                assertNotNull(document, "Complete selected graph retains " + name);
                assertEquals(name.equals("C") ? 0L : 1L,
                        ((Number) document.document().getProperties().get("observed").getValue()).longValue(),
                        "Finite token must visit A and B exactly once in the selected root view: " + name);
            }
            var finalHeads = handles.values().stream().map(doc -> doc.snapshot().blueId()).toList();
            var finalHistories = histories(f, handles);
            var finalView = view.closureIdentity();
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(finalHeads, handles.values().stream().map(doc -> doc.snapshot().blueId()).toList());
            assertEquals(finalHistories, histories(f, handles));
            assertEquals(finalView, f.control.selectedView(handles.get("A").id()).closureIdentity());
            assertTrue(f.blue.processing().processNext(handles.get("A")).quiescent());
            assertEquals(finalHeads, handles.values().stream().map(doc -> doc.snapshot().blueId()).toList());
            assertEquals(finalHistories, histories(f, handles));
        }
    }

    /** Explicit host scheduling: each prerequisite remains its own public, metered selection. */
    private static void finishJoinWithPublicDriver(RootedSdkFixture f, Map<String, DocumentHandle> handles,
            EntryHandle submitted, java.util.Set<String> previouslyApplied) {
        boolean observed = false;
        var invocations = new java.util.HashSet<String>();
        // The initial root-only wait already consumed one of the unchanged 32 selections.
        for (int step = 1; step < 32; step++) {
            var prefixes = histories(f, handles);
            var result = f.blue.processing().drain(new DrainBudget(1L, 1L));
            prefixes.forEach((name, prefix) -> assertEquals(prefix,
                    f.history(handles.get(name)).subList(0, prefix.size())));
            assertFalse(result.blocked(), result.diagnostic().toString());
            for (var actual : result.entries()) {
                boolean current = actual.entry().equals(submitted);
                assertTrue(current || previouslyApplied.contains(actual.entry().blueId()));
                if (actual.disposition() == EntryDisposition.APPLIED) {
                    if (current) observed = true;
                    for (var closure : actual.closures()) assertTrue(invocations.add(closure.closureId()),
                            "A committed invocation must not be executed twice");
                    assertTrue(actual.publicEvents().isEmpty());
                } else {
                    assertEquals(EntryDisposition.NO_MATCH, actual.disposition());
                    assertTrue(!current || observed, "A transport marker cannot replace actual application");
                    assertTrue(actual.closures().isEmpty());
                }
            }
            System.out.println("LATE_JOIN_PUBLIC_DRIVER step=" + step + " entries=" + result.entries().size()
                    + " retained=" + result.rootedRetainedResults().size() + " quiescent=" + result.quiescent());
            if (result.quiescent()) {
                assertTrue(observed, "The exact saved-original attachment must be applied");
                return;
            }
        }
        throw new AssertionError("Saved-original join failed to finish within 32 actual public selections");
    }

    /** Diagnostic scheduling only: A owns its two actual earlier prerequisites before C attaches A. */
    private static void settleExactPreAnchorSource(RootedSdkFixture f,
            Map<String, DocumentHandle> handles, EntryHandle precedingAttachment) {
        settleExactPreAnchorSource(f, handles, precedingAttachment, null);
    }

    private static void settleExactPreAnchorSource(RootedSdkFixture f,
            Map<String, DocumentHandle> handles, EntryHandle precedingAttachment, EntryHandle laterInput) {
        var a = handles.get("A");
        var b = handles.get("B");
        var c = handles.get("C");
        var sourceHeads = List.of(List.of(b.snapshot().epoch(), b.snapshot().blueId()),
                List.of(c.snapshot().epoch(), c.snapshot().blueId()));
        var sourceHistories = List.of(f.history(b), f.history(c));
        var priorAHistory = f.history(a);
        var live = f.blue.processing().processNext(a);
        assertEquals(EntryDisposition.APPLIED, live.entry(precedingAttachment).disposition());
        assertEquals(1, live.entries().size(), "Only the already supplied B attachment at 200 is processed");
        assertTrue(live.managedEpochApplications().isEmpty());
        assertTrue(live.rootedRetainedResults().isEmpty());
        assertFalse(live.quiescent(), "A still needs the exact C initialization inside its calculated B");
        assertEquals(sourceHeads, List.of(List.of(b.snapshot().epoch(), b.snapshot().blueId()),
                List.of(c.snapshot().epoch(), c.snapshot().blueId())));
        assertEquals(sourceHistories, List.of(f.history(b), f.history(c)));
        assertEquals(priorAHistory, f.history(a).subList(0, priorAHistory.size()));
        var afterLiveHistory = f.history(a);
        var local = f.blue.processing().processNext(a);
        assertTrue(local.entries().isEmpty(), "The prerequisite is not a fabricated external entry");
        assertTrue(local.managedEpochApplications().isEmpty(), "No independent B publication is fabricated");
        assertEquals(1, local.rootedRetainedApplications().size());
        var retained = local.rootedRetainedApplications().get(0);
        assertEquals(a.id(), retained.rootDocumentId());
        assertEquals(b.id(), retained.work().consumerDocumentId());
        assertEquals(c.id(), retained.work().sourceDocumentId());
        assertEquals(0L, retained.work().sourceEpoch());
        assertEquals(EntryDisposition.APPLIED, local.rootedRetainedResults().get(0).disposition());
        if (laterInput == null) {
            assertTrue(local.quiescent(), "The exact two prerequisites must suffice; there is no new drain loop");
        } else {
            assertEquals(laterInput.blueId(), f.control.nextLiveInput(a.id()).orElseThrow(),
                    "The new attachment remains a later ordinary LIVE input after the two earlier prerequisites");
        }
        assertEquals(sourceHeads, List.of(List.of(b.snapshot().epoch(), b.snapshot().blueId()),
                List.of(c.snapshot().epoch(), c.snapshot().blueId())));
        assertEquals(sourceHistories, List.of(f.history(b), f.history(c)));
        assertEquals(priorAHistory, f.history(a).subList(0, priorAHistory.size()));
        assertEquals(afterLiveHistory, f.history(a).subList(0, afterLiveHistory.size()));
        var selectedB = f.control.selectedView(a.id()).managedDocument(
                new blue.language.processor.closure.DocumentId(b.id().value()));
        assertEquals(b.snapshot().epoch(), selectedB.epoch(), "A now selects B's real causal revision");
        assertEquals(b.snapshot().blueId(), selectedB.blueId(), "The required source state must match exactly");
        System.out.println("PRE_ANCHOR_PREREQUISITES_PROVED actualSource=B200 retainedSource=C0");
    }

    private static EntryResult applyWithin32(RootedSdkFixture f, DocumentHandle root,
            EntryHandle entry, Map<String, DocumentHandle> handles) {
        for (int step = 0; step < 32; step++) {
            var prefixes = histories(f, handles);
            var result = f.blue.processing().processNext(root);
            prefixes.forEach((name, prefix) -> assertEquals(prefix,
                    f.history(handles.get(name)).subList(0, prefix.size())));
            assertFalse(result.blocked(), "Required source evidence unavailable: " + result.diagnostic());
            System.out.println("FINITE_DRAIN root=" + name(handles, root) + " step=" + step + " wanted=" + entry.blueId()
                    + " entries=" + result.entries().stream().map(value -> value.entry().blueId() + ":" + value.disposition()).toList()
                    + " historical=" + result.managedEpochApplications().size() + " quiescent=" + result.quiescent());
            var terminal = result.find(entry);
            if (terminal.isPresent()) return terminal.orElseThrow();
            assertFalse(result.quiescent(), "Selected operation disappeared without a terminal result");
        }
        throw new AssertionError("Operation failed to reach a terminal result within 32 processing steps");
    }

    private static String name(Map<String, DocumentHandle> handles, DocumentHandle target) {
        return handles.entrySet().stream().filter(entry -> entry.getValue() == target).findFirst().orElseThrow().getKey();
    }

    private static Map<String, List<String>> histories(RootedSdkFixture f, Map<String, DocumentHandle> handles) {
        var result = new LinkedHashMap<String, List<String>>(); handles.forEach((name, doc) -> result.put(name, f.history(doc)));
        return result;
    }
}
