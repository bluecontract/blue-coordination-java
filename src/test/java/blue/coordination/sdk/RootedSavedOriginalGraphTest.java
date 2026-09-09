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
        assertTrue(run(new String[][]{{"A", "b", "B"}, {"B", "c", "C"}, {"C", "a", "A"}}) > 0,
                "The ring must exercise an authenticated indirect reference update with an unowned direct lane");
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

    private static int run(String[][] edges) throws Exception {
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
            for (String[] edge : edges) {
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
                var attached = f.blue.processing().processNext(root);
                assertEquals(EntryDisposition.APPLIED, attached.entry(submitted).disposition(),
                        "Saved attachment " + String.join("/", edge) + " " + attached.entry(submitted).diagnostic());
                for (int step = 0; step < 32; step++) {
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
            int proofs = verifyReferenceProofNegatives(f, handles.get("C"));
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
            return proofs;
        }
    }

    private static int verifyReferenceProofNegatives(RootedSdkFixture f, DocumentHandle owner) {
        var terminals = f.control.retainedTerminals(owner.id());
        var id = new blue.language.processor.closure.DocumentId(owner.id().value());
        int verified = 0;
        for (var terminal : terminals) {
            var result = terminal.result();
            var before = terminal.input().snapshot().managedDocument(id);
            var after = result.resultingDocuments().stream().filter(doc -> doc.documentId().equals(id)).findFirst();
            var targets = terminal.input().directDeliveries().stream().map(delivery -> delivery.targetDocumentId())
                    .distinct().sorted().toList();
            if (!result.commits() || before == null || after.isEmpty() || before.epoch() != after.orElseThrow().epoch()
                    || before.blueId().equals(after.orElseThrow().afterBlueId()) || targets.isEmpty()
                    || targets.contains(id) || !result.rootedProjection().owns(id)
                    || targets.stream().anyMatch(target -> result.rootedProjection().owns(target))) continue;
            var direct = targets.stream().map(target -> blue.coordination.api.DocumentId.of(target.value())).toList();
            assertTrue(f.control.verifiesRetainedReferenceRebind(terminal.identity(), result, owner.id(), direct));
            assertFalse(f.control.verifiesRetainedReferenceRebind(terminal.identity(), result, owner.id(), List.of()));
            assertFalse(f.control.verifiesRetainedReferenceRebind(terminal.identity(), result, owner.id(), List.of(owner.id())));
            var extra = new java.util.ArrayList<>(direct); extra.add(owner.id());
            assertFalse(f.control.verifiesRetainedReferenceRebind(terminal.identity(), result, owner.id(), extra));
            var duplicate = new java.util.ArrayList<>(direct); duplicate.add(direct.get(0));
            assertFalse(f.control.verifiesRetainedReferenceRebind(terminal.identity(), result, owner.id(), duplicate));
            assertFalse(f.control.verifiesRetainedReferenceRebind(terminal.identity(), result, direct.get(0), direct));
            var other = terminals.stream().filter(value -> !value.identity().equals(terminal.identity())).findFirst().orElseThrow();
            assertThrows(IllegalArgumentException.class, () -> f.control.verifiesRetainedReferenceRebind(
                    terminal.identity(), other.result(), owner.id(), direct));
            verified++;
        }
        return verified;
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
