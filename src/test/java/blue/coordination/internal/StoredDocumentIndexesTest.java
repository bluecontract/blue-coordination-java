package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.sdk.ActivationPolicy;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.Test;
import static blue.coordination.internal.DocumentSessionStorageTest.resource;
import static org.junit.jupiter.api.Assertions.*;

final class StoredDocumentIndexesTest {
    private static final DocumentSessionStorage.Limits SESSION = new DocumentSessionStorage.Limits(32 * 1024 * 1024, 256, 256L * 1024 * 1024);
    private static final PersistentMapStorage.Limits MAP = new PersistentMapStorage.Limits(1024 * 1024, 4096, 512 * 1024, 4096, 32);

    @Test void workingSelectionReusesOnlyItsPositivePinnedFactAndStagesReauthenticateIt() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var other = f.start(resource("source.yaml") + "\nlabel: independent\n", "independent", ActivationPolicy.fromNow());
            var original = retain(indexes(f.bytes), state(f));
            String reverse = rootNodeAddress(original.lineages().storedState().current().storedRootDescriptor());
            byte[] valid = f.bytes.records.get(reverse).clone();
            var reads = new ArrayList<String>(); int[] checks = {0};
            var observed = new CoordinationImmutableObjectStore() {
                public Optional<byte[]> get(String key, int bound) { reads.add(key); return f.bytes.get(key, bound); }
                public byte[] putIfAbsent(String key, byte[] value) { return f.bytes.putIfAbsent(key, value); }
            };
            var storage = indexes(observed); var pinned = reopen(storage, original);
            var scope = storage.openWorkingSessions(pinned.sessions(), pinned.lineages(), pinned.generations(), 1,
                    (id, selected) -> checks[0]++);
            var working = scope.open();
            try (scope; var strict = storage.openOwner(1)) {
                reads.clear();
                // when
                var selected = working.get(source.id());
                // then
                assertTrue(reads.contains(reverse), "The first selection must authenticate reverse membership");
                select(strict, pinned, source.id()); reads.clear();
                assertSame(selected, working.get(source.id()));
                assertSame(selected, scope.selected(source.id()).session());
                assertEquals(1, checks[0]); assertFalse(reads.contains(reverse), "Warm reads reuse the completed selection fact");
                assertPhysical(() -> working.get(other.id()));
                assertNull(working.get(DocumentId.of("absent")), "Absence remains a strict lookup, not a positive certificate");

                f.bytes.records.remove(reverse);
                assertSame(selected, working.get(source.id()), "Out-of-band deletion does not revoke an already captured immutable fact");
                assertPhysical(() -> select(strict, pinned, source.id()));
                try (var fresh = storage.openWorkingSessions(pinned.sessions(), pinned.lineages(), pinned.generations(), 1, (id, row) -> { })) {
                    assertPhysical(() -> fresh.open().get(source.id()));
                }
                int writes = f.bytes.writes;
                assertPhysical(() -> scope.stage(working)); assertEquals(writes, f.bytes.writes);
                assertPhysical(() -> working.get(source.id()), "Failed staging must retire the earlier positive fact");
                f.bytes.records.put(reverse, valid.clone());
                assertSame(selected, working.get(source.id()));
                assertEquals(pinned.sessions().get(source.id()), scope.stage(working).get(source.id()));
                f.bytes.records.get(reverse)[0] ^= 1;
                assertPhysical(() -> working.get(source.id()), "Successful staging must also retire the positive fact");
                f.bytes.records.put(reverse, valid.clone());
                assertSame(selected, working.get(source.id()));
            }
            assertPhysical(() -> working.get(source.id())); assertPhysical(() -> scope.selected(source.id()));
        }
    }

    @Test void failedMembershipOrOwningStoreCheckCannotGrantAWorkingSelectionFact() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var storage = indexes(f.bytes); var pinned = reopen(storage, retain(storage, state(f)));
            String reverse = rootNodeAddress(pinned.lineages().storedState().current().storedRootDescriptor());
            byte[] valid = f.bytes.records.get(reverse).clone(); int[] checks = {0};
            try (var scope = storage.openWorkingSessions(pinned.sessions(), pinned.lineages(), pinned.generations(), 1,
                    (id, selected) -> { if (++checks[0] == 1) throw new CoordinationObjectStorageException("Injected owning-store check failure"); })) {
                var working = scope.open();
                // when
                f.bytes.records.get(reverse)[0] ^= 1;
                // then
                assertPhysical(() -> working.get(source.id())); assertEquals(0, checks[0]);
                f.bytes.records.put(reverse, valid.clone());
                assertPhysical(() -> working.get(source.id())); assertEquals(1, checks[0]);
                f.bytes.records.remove(reverse);
                assertPhysical(() -> working.get(source.id()), "A failed callback must not leave a membership fast path");
                assertEquals(1, checks[0]); f.bytes.records.put(reverse, valid);
                var selected = working.get(source.id()); assertSame(selected, working.get(source.id())); assertEquals(2, checks[0]);
            }
        }
    }

    @Test void directWorkingStageChecksRemovedAndReplacedOriginalsBeforeAnyPrewrite() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var storage = indexes(f.bytes); var pinned = reopen(storage, retain(storage, state(f)));
            String reverse = rootNodeAddress(pinned.lineages().storedState().current().storedRootDescriptor());
            byte[] valid = f.bytes.records.get(reverse).clone();
            f.process(source, f.append(source, "rcp2/source", "tick"));
            var replacement = f.engine.documents().require(source.id());
            for (boolean remove : List.of(false, true)) {
                try (var scope = storage.openWorkingSessions(pinned.sessions(), pinned.lineages(), pinned.generations(), 1, (id, row) -> { })) {
                    var working = scope.open(); var selected = working.get(source.id());
                    // when
                    var changed = remove ? working.remove(source.id()).map() : working.put(source.id(), replacement).map();
                    // then
                    assertNotSame(selected, changed.get(source.id()), "Changed rows are mutable working values, not the selected original");
                    f.bytes.records.remove(reverse); int writes = f.bytes.writes;
                    assertPhysical(() -> scope.stage(changed)); assertEquals(writes, f.bytes.writes);
                    assertPhysical(() -> working.get(source.id()));
                    assertSame(remove ? null : replacement, changed.get(source.id()), "No original proof is inherited by a new working row");
                    f.bytes.records.put(reverse, valid);
                    var staged = scope.stage(changed);
                    if (remove) assertNull(staged.get(source.id()));
                    else assertEquals(storage.retainSession(replacement), staged.get(source.id()));
                }
            }
        }
    }

    @Test void workingSelectionDoesNotCarryAuthorityAcrossChangedOrStaleIndexRoots() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var storage = indexes(f.bytes); var before = retain(storage, state(f));
            try (var old = storage.openWorkingSessions(before.sessions(), before.lineages(), before.generations(), 1, (id, row) -> { })) {
                var original = old.open().get(source.id());
                f.process(source, f.append(source, "rcp2/source", "tick"));
                // when
                var after = retain(storage, state(f));
                try (var mixed = storage.openWorkingSessions(before.sessions(), after.lineages(), after.generations(), 1, (id, row) -> { })) {
                    // then
                    assertPhysical(() -> mixed.open().get(source.id()));
                }
                var rows = after.lineages().storedState();
                var stale = ManagedLineageIndex.restoreStored(new ManagedLineageIndex.StoredState(rows.documents(), rows.authored(),
                        rows.initialized(), rows.retained(), rows.current().remove(source.snapshot().blueId()).map(), rows.copiedNodes()));
                try (var mixed = storage.openWorkingSessions(after.sessions(), stale, after.generations(), 1, (id, row) -> { })) {
                    assertPhysical(() -> mixed.open().get(source.id()));
                }
                var wrong = after.sessions().put(source.id(), new StoreIndexCodecs.SessionAddress(DocumentId.of("wrong-owner"),
                        after.sessions().get(source.id()).address())).map();
                try (var mixed = storage.openWorkingSessions(wrong, after.lineages(), after.generations(), 1, (id, row) -> { })) {
                    assertPhysical(() -> mixed.open().get(source.id()));
                }
                try (var fresh = storage.openWorkingSessions(after.sessions(), after.lineages(), after.generations(), 1, (id, row) -> { })) {
                    assertEquals(1L, fresh.open().get(source.id()).epoch());
                }
                assertSame(original, old.open().get(source.id())); assertEquals(0L, original.epoch());
            }
        }
    }

    @Test void workingStageSharesOnlyNewImmutableViewEncodingAcrossDistinctSessionOwners() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var parent = f.start(resource("parent.yaml") + "\nchild: {blueId: " + source.snapshot().blueId() + "}\n",
                    "rcp2/parent", ActivationPolicy.fromNow());
            var s = f.engine.documents().require(source.id());
            // when
            var p = f.engine.documents().require(parent.id());
            // then
            assertSame(s.rootedView(), p.requireRootedHistory().admissionSources().storedViews().get(source.id()));
            String sharedView = f.storage.viewAddress(s.rootedView());
            var readKeys = new ArrayList<String>(); var writeKeys = new ArrayList<String>();
            var observed = new CoordinationImmutableObjectStore() {
                @Override public Optional<byte[]> get(String key, int maximum) {
                    readKeys.add(key); return f.bytes.get(key, maximum);
                }
                @Override public byte[] putIfAbsent(String key, byte[] value) {
                    writeKeys.add(key); return f.bytes.putIfAbsent(key, value);
                }
            };
            var storage = indexes(observed); var pinned = retain(storage, state(f));
            var sourceAddress = storage.retainSession(s); var parentAddress = storage.retainSession(p);
            try (var scope = storage.openWorkingSessions(pinned.sessions(), pinned.lineages(), pinned.generations(), 2,
                    (id, selected) -> { })) {
                var working = scope.open().put(source.id(), s).map().put(parent.id(), p).map();
                for (int repeat = 0; repeat < 2; repeat++) {
                    readKeys.clear(); writeKeys.clear();
                    var staged = scope.stage(working);
                    assertEquals(1L, readKeys.stream().filter(sharedView::equals).count(),
                            "The second owner authenticates the new shared view retained by the first owner in this stage");
                    assertEquals(1L, writeKeys.stream().filter(sharedView::equals).count(),
                            "The first owner acknowledges the new view; the second freshly authenticates that dependency");
                    assertEquals(sourceAddress, staged.get(source.id())); assertEquals(parentAddress, staged.get(parent.id()));
                }
            }
        }
    }

    @Test void actualWorkingStageReauthenticatesKnownViewsWithoutRewritingThemOrDroppingMutableRows() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var original = retain(indexes(f.bytes), state(f));
            var readKeys = new ArrayList<String>(); var writeKeys = new ArrayList<String>();
            var observed = new CoordinationImmutableObjectStore() {
                @Override public Optional<byte[]> get(String key, int maximum) {
                    readKeys.add(key); return f.bytes.get(key, maximum);
                }
                @Override public byte[] putIfAbsent(String key, byte[] value) {
                    writeKeys.add(key); return f.bytes.putIfAbsent(key, value);
                }
            };
            var storage = indexes(observed); var pinned = reopen(storage, original);
            try (var scope = storage.openWorkingSessions(pinned.sessions(), pinned.lineages(), pinned.generations(), 1,
                    (id, selected) -> { })) {
                var working = scope.open(); var session = working.get(source.id());
                String view = scope.viewScope().addressOf(session.rootedView());
                session.markCatchingUp(); readKeys.clear(); writeKeys.clear();
                // when
                var staged = scope.stage(working);
                // then
                assertEquals(1L, readKeys.stream().filter(view::equals).count(), "Actual stage uses the selected-view byte path");
                assertEquals(0L, writeKeys.stream().filter(view::equals).count(), "A freshly authenticated dependency is not rewritten");
                String selectedAddress = staged.get(source.id()).address();
                assertEquals(1L, writeKeys.stream().filter(selectedAddress::equals).count(),
                        "The complete mutable session still requires its write acknowledgement");
                assertNotEquals(pinned.sessions().get(source.id()).address(), selectedAddress);
                assertEquals(storage.retainSession(session).address(), selectedAddress, "Complete ordinary encoding remains the byte oracle");
                try (var cold = storage.openOwner(1)) {
                    assertEquals(blue.coordination.api.SessionStatus.CATCHING_UP,
                            cold.find(staged, pinned.lineages(), pinned.generations(), source.id()).orElseThrow().session().status());
                }
                byte[] valid = f.bytes.records.get(view).clone();
                f.bytes.records.get(view)[0] ^= 1; writeKeys.clear();
                assertPhysical(() -> scope.stage(working));
                assertTrue(writeKeys.isEmpty(), "A selected-view read failure precedes this session's immutable prewrites");
                f.bytes.records.put(view, valid); f.bytes.badAck = true;
                assertPhysical(() -> scope.stage(working)); f.bytes.badAck = false;
                assertEquals(selectedAddress, scope.stage(working).get(source.id()).address());
                try (var old = storage.openOwner(1)) {
                    assertEquals(blue.coordination.api.SessionStatus.READY, select(old, pinned, source.id()).session().status());
                }
            }
        }
    }

    @Test void workingSessionsStageOnlyCompleteCurrentInstancesIncludingChangesWithoutIndexPuts() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var storage = indexes(f.bytes); var pinned = retain(storage, state(f));
            var checked = new ArrayList<DocumentId>();
            try (var scope = storage.openWorkingSessions(pinned.sessions(), pinned.lineages(), pinned.generations(), 1,
                    (id, selected) -> checked.add(id))) {
                int reads = f.bytes.reads, writes = f.bytes.writes;
                // when
                var working = scope.open();
                // then
                assertEquals(reads, f.bytes.reads); assertEquals(writes, f.bytes.writes);
                var selected = working.get(source.id()); assertSame(selected, working.get(source.id()));
                assertEquals(Set.of(source.id()), new HashSet<>(checked));
                selected.markCatchingUp();
                assertEquals(writes, f.bytes.writes, "An in-place status update does not serialize an intermediate session");
                var statusRows = scope.stage(working);
                assertNotEquals(pinned.sessions().get(source.id()).address(), statusRows.get(source.id()).address());
                try (var cold = storage.openOwner(1)) {
                    assertEquals(blue.coordination.api.SessionStatus.CATCHING_UP,
                            cold.find(statusRows, pinned.lineages(), pinned.generations(), source.id()).orElseThrow().session().status());
                }
                f.process(source, f.append(source, "rcp2/source", "tick"));
                var replacement = f.engine.documents().require(source.id());
                var changed = working.put(source.id(), replacement).map();
                assertSame(replacement, changed.get(source.id()));
                f.bytes.failAtWrite = f.bytes.writes + 2;
                assertPhysical(() -> scope.stage(changed)); f.bytes.failAtWrite = -1;
                assertSame(replacement, changed.get(source.id()));
                var finalRows = scope.stage(changed);
                var nextLineages = pinned.lineages().withAdvancedRevision(replacement);
                try (var cold = storage.openOwner(1)) {
                    var reopened = cold.find(finalRows, nextLineages, pinned.generations(), source.id()).orElseThrow().session();
                    assertEquals(1L, reopened.epoch()); assertEquals(replacement.status(), reopened.status());
                    assertEquals(replacement.currentRepresentation().blueId(), reopened.currentRepresentation().blueId());
                }
                var removed = scope.stage(working.remove(source.id()).map());
                assertNull(removed.get(source.id()), "A previously selected mutable instance cannot resurrect a removed row");
                try (var old = storage.openOwner(1)) { assertEquals(0L, select(old, pinned, source.id()).session().epoch()); }
            }
        }
    }

    @Test void actualSourceParentCrossOpenSharesViewsWithoutReadingUnrelatedSessionsOrWriting() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            f.process(source, f.append(source, "rcp2/source", "tick")); f.retain(source);
            var parent = f.start(resource("parent.yaml") + "\nchild: {blueId: " + source.snapshot().blueId() + "}\n",
                    "rcp2/parent", ActivationPolicy.fromNow());
            for (int i = 0; i < 6; i++) f.start(resource("source.yaml") + "\nlabel: unrelated-" + i + "\n", "unrelated/" + i, ActivationPolicy.fromNow());
            var original = state(f);
            var writer = indexes(f.bytes); var stored = retain(writer, original);
            Set<String> unrelated = new HashSet<>(); Set<String> sessionAddresses = new HashSet<>();
            for (var entry : stored.sessions().entries()) {
                sessionAddresses.add(entry.getValue().address());
                if (!Set.of(source.id(), parent.id()).contains(entry.getKey()))
                // when
                unrelated.add(entry.getValue().address());
            }
            // then
            assertEquals(8, sessionAddresses.size()); assertEquals(6, unrelated.size());
            var expectedSessionReads = Map.of(stored.sessions().get(source.id()).address(), 1,
                    stored.sessions().get(parent.id()).address(), 1);
            var bytes = new CountingBytes(f.bytes.copy(), unrelated, sessionAddresses);
            var cold = indexes(bytes); var opened = reopen(cold, stored);
            assertEquals(original.lineageIndex().lastMutationNodeCopies(), opened.lineages().lastMutationNodeCopies());
            assertEquals(original.graphGenerations().lastOperationComparisonsForTesting(), opened.generations().lastOperationComparisonsForTesting());
            assertEquals(original.graphGenerations().lastOperationCopiedNodesForTesting(), opened.generations().lastOperationCopiedNodesForTesting());
            for (var id : original.sessionIndex().keys()) assertEquals(original.sessionIndex().read(id).comparisons(), opened.sessions().read(id).comparisons());
            for (boolean sourceFirst : List.of(true, false)) try (var owner = cold.openOwner(2)) {
                int reads = f.providerReads.get(); int writes = bytes.writes; bytes.reads = 0; bytes.sessionReads.clear();
                var first = sourceFirst ? source.id() : parent.id(); var second = sourceFirst ? parent.id() : source.id();
                var a = select(owner, opened, first).session(); var b = select(owner, opened, second).session();
                var sourceSession = sourceFirst ? a : b; var parentSession = sourceFirst ? b : a;
                assertSame(sourceSession.rootedView(), parentSession.requireRootedHistory().admissionSources().storedViews().get(source.id()));
                assertSame(a, select(owner, opened, first).session(), "One owner preserves mutable selected-session identity");
                assertEquals(expectedSessionReads, bytes.sessionReads,
                        "Each selected session body is opened exactly once; none of the six unrelated session bodies is opened");
                assertEquals(reads, f.providerReads.get()); assertEquals(writes, bytes.writes);
                assertEquals(original.lineageIndex().currentMatches(sourceSession.currentRepresentation().blueId()),
                        cold.lineageMatches(opened.lineages(), StoredDocumentIndexes.LineageRoot.CURRENT, sourceSession.currentRepresentation().blueId()));
                assertEquals(expectedSessionReads, bytes.sessionReads, "Lineage lookup must not hydrate another session body");
                assertEquals(reads, f.providerReads.get()); assertEquals(writes, bytes.writes);
            }
            try (var bounded = cold.openOwner(1)) {
                var first = select(bounded, opened, source.id()).session();
                assertPhysical(() -> select(bounded, opened, parent.id()));
                assertSame(first, select(bounded, opened, source.id()).session(), "A full owner never evicts its mutable session");
            }
        }
    }

    @Test void exactSameEpochRebindMutatesStoredIndexesWithResidentShapeAndCounters() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml") + """
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
                """, "rcp2/source", ActivationPolicy.importFullHistory());
            f.retain(source);
            var parent = f.start(resource("parent.yaml") + "\nchild: {blueId: " + source.snapshot().blueId() + "}\n",
                    "rcp2/parent", ActivationPolicy.importFullHistory());
            var original = state(f); var storage = indexes(f.bytes); var stored = retain(storage, original);
            var residentLineages = original.lineageIndex(); var residentGenerations = original.graphGenerations();
            var cold = indexes(f.bytes.copy()); var opened = reopen(cold, stored);
            var storedLineages = opened.lineages(); var storedGenerations = opened.generations();
            String numbered = parent.snapshot().blueId();
            for (int i = 0; i < 2; i++) {
                long expectedGeneration = residentGenerations.require(parent.id());
                f.process(parent, f.append(source, "rcp2/source", "emitUnmatched"));
                // when
                var changed = f.engine.documents().require(parent.id());
                // then
                assertEquals(0L, changed.epoch()); assertEquals(1, changed.revisions().size());
                residentLineages = residentLineages.withComponentRepresentationRebound(changed);
                storedLineages = storedLineages.withComponentRepresentationRebound(changed);
                assertEquals(residentLineages.lastMutationNodeCopies(), storedLineages.lastMutationNodeCopies());
                assertEquals(residentLineages.byDocumentId(parent.id()), storedLineages.byDocumentId(parent.id()));
                assertEquals(residentLineages.retainedMatches(numbered), cold.retainedMatches(storedLineages, numbered));
                var result = changed.rootedView().result();
                residentGenerations = residentGenerations.applyOwned(result, Map.of(parent.id(), expectedGeneration));
                storedGenerations = storedGenerations.applyOwned(result, Map.of(parent.id(), expectedGeneration));
                assertEquals(residentGenerations.generations(), storedGenerations.generations());
                assertEquals(residentGenerations.lastOperationComparisonsForTesting(), storedGenerations.lastOperationComparisonsForTesting());
                assertEquals(residentGenerations.lastOperationCopiedNodesForTesting(), storedGenerations.lastOperationCopiedNodesForTesting());
                String representation = changed.currentRepresentation().blueId(); assertNotEquals(numbered, representation);
                assertEquals(residentLineages.currentMatches(representation), cold.lineageMatches(storedLineages, StoredDocumentIndexes.LineageRoot.CURRENT, representation));
                assertEquals(residentLineages.exactLookupSteps(representation), storedLineages.exactLookupSteps(representation));
            }
            assertEquals(0L, f.engine.documents().require(source.id()).epoch());
            assertEquals(2, f.engine.documents().require(parent.id()).representationTransitions().size());
            // Reopen each changed family independently; no combined mutable root exists.
            var finalLineages = reopenLineages(cold, storedLineages);
            assertEquals(residentLineages.byDocumentId(parent.id()), finalLineages.byDocumentId(parent.id()));
            assertEquals(original.lineageIndex().byDocumentId(parent.id()), reopenLineages(cold, stored.lineages()).byDocumentId(parent.id()));
        }
    }

    @Test void owningAttemptReusesItsMutatedSessionWithoutReplacingPinnedAuthority() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var storage = indexes(f.bytes); var pinned = retain(storage, state(f));
            try (var owner = storage.openOwner(1)) {
                var selected = select(owner, pinned, source.id()); var working = selected.session();
                var original = working.storedState();
                // Produce the successor through the real rooted SDK, then use the same detached-session
                // mutation as MultiDocumentPublicationTransaction, with all original exact evidence.
                f.process(source, f.append(source, "rcp2/source", "tick"));
                var next = f.engine.documents().require(source.id()); var complete = next.storedState();
                var newReceipts = new HashSet<>(complete.transitionReceipts());
                // when
                newReceipts.removeAll(original.transitionReceipts());
                // then
                assertEquals(1, newReceipts.size());
                working.commit(next.revisions().get(1), complete.layout(), next.readyThrough(),
                        next.activeSubscriptions(), newReceipts.iterator().next());
                working.retainRootedView(next.rootedView()); working.markGraphPublished(); working.markReady(next.readyThrough());
                assertEquals(1L, working.epoch()); assertEquals(0L, selected.retainedLineage().currentEpoch());
                assertNotEquals(selected.retainedLineage(), ManagedLineageIndex.Lineage.from(working));
                int providerReads = f.providerReads.get();
                assertSame(working, select(owner, pinned, source.id()).session());
                assertSame(original.rootedView(), select(owner, pinned, source.id()).retainedView());
                assertNotSame(working.rootedView(), select(owner, pinned, source.id()).retainedView());
                assertEquals(providerReads, f.providerReads.get());

                // Working index updates remain explicit and must move together to a fresh publication view.
                var nextLineages = pinned.lineages().withAdvancedRevision(working);
                var nextGenerations = pinned.generations().applyOwned(next.rootedView().result(),
                        Map.of(source.id(), selected.retainedGraphGeneration()));
                var nextAddresses = pinned.sessions().put(source.id(), storage.retainSession(working)).map();
                var published = new Stored(nextAddresses, nextLineages, nextGenerations);
                assertPhysical(() -> select(owner, published, source.id()));
                assertPhysical(() -> owner.find(pinned.sessions(), nextLineages, pinned.generations(), source.id()));
                assertEquals(selected.retainedGraphGeneration(), nextGenerations.require(source.id()),
                        "A value-only revision does not change graph topology");
                var graph = nextGenerations.storedState();
                var changedGeneration = ClosureGraphGenerationInventory.restoreStored(new ClosureGraphGenerationInventory.StoredState(
                        graph.generations().put(source.id(), selected.retainedGraphGeneration() + 1).map(), graph.comparisons(), graph.copiedNodes()));
                assertPhysical(() -> owner.find(pinned.sessions(), pinned.lineages(), changedGeneration, source.id()));
                var empty = retain(storage, InMemoryDocumentStore.StoreState.empty());
                assertPhysical(() -> select(owner, empty, source.id()));
                try (var fresh = storage.openOwner(1)) {
                    var reopened = select(fresh, published, source.id());
                    assertEquals(1L, reopened.session().epoch());
                    assertEquals(nextLineages.byDocumentId(source.id()), reopened.retainedLineage());
                    assertEquals(next.currentRepresentation().blueId(), reopened.session().currentRepresentation().blueId());
                }
                try (var old = storage.openOwner(1)) { assertEquals(0L, select(old, pinned, source.id()).session().epoch()); }
            }
        }
    }

    @Test void mixedMissingWrongOwnerAndChangedAddressSelectionsFailNoncommitting() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var storage = indexes(f.bytes); var before = retain(storage, state(f));
            try (var owner = storage.openOwner(2)) {
                select(owner, before, source.id());
                f.process(source, f.append(source, "rcp2/source", "tick"));
                // when
                var after = retain(storage, state(f));
                // then
                assertPhysical(() -> select(owner, after, source.id()));
                try (var fresh = storage.openOwner(2)) {
                    assertPhysical(() -> fresh.find(before.sessions(), after.lineages(), after.generations(), source.id()));
                    assertEquals(1L, select(fresh, after, source.id()).session().epoch(),
                            "Failed initial validation must not pin the rejected session address");
                }
                var missing = after.sessions().remove(source.id()).map();
                try (var fresh = storage.openOwner(2)) {
                    assertPhysical(() -> fresh.find(missing, after.lineages(), after.generations(), source.id()));
                }
                DocumentId wrong = DocumentId.of("wrong-owner");
                var misbound = after.sessions().put(source.id(), new StoreIndexCodecs.SessionAddress(wrong, after.sessions().get(source.id()).address())).map();
                try (var fresh = storage.openOwner(2)) {
                    assertPhysical(() -> fresh.find(misbound, after.lineages(), after.generations(), source.id()));
                }
                var state = after.lineages().storedState();
                var incomplete = ManagedLineageIndex.restoreStored(new ManagedLineageIndex.StoredState(state.documents(), state.authored(), state.initialized(),
                        state.retained(), state.current().remove(source.snapshot().blueId()).map(), state.copiedNodes()));
                try (var fresh = storage.openOwner(2)) {
                    assertPhysical(() -> fresh.find(after.sessions(), incomplete, after.generations(), source.id()));
                }
                var graph = after.generations().storedState();
                var wrongGraph = ClosureGraphGenerationInventory.restoreStored(new ClosureGraphGenerationInventory.StoredState(
                        graph.generations().put(source.id(), after.generations().require(source.id()) + 1).map(), graph.comparisons(), graph.copiedNodes()));
                try (var fresh = storage.openOwner(2)) {
                    assertPhysical(() -> fresh.find(after.sessions(), after.lineages(), wrongGraph, source.id()));
                }
                assertPhysical(() -> storage.openSessions(storage.lineageRoot(after.lineages(), StoredDocumentIndexes.LineageRoot.DOCUMENT)));
            }
        }
    }

    @Test void malformedLineageRowsAndPhysicalFailuresDoNotBecomeValidAbsence() throws Exception {
        // given
        var bytes = new DocumentSessionStorageTest.Bytes(); var codecs = new StoreIndexCodecs(bytes, MAP);
        var id = DocumentId.of("selected");
        var zero = new ManagedLineageIndex.RetainedState(id, 0, "x");
        var two = new ManagedLineageIndex.RetainedState(id, 2, "x");
        // when
        var gap = new ManagedLineageIndex.Lineage(id, "authored", "x", 2, "x", List.of(zero, two), -1);
        // then
        assertPhysical(() -> codecs.lineages.decode(codecs.lineages.encode(codecs.lineages.prepareForStorage(gap))));
        var wrongInitial = new ManagedLineageIndex.Lineage(id, "authored", "wrong", 0, "x", List.of(zero), -1);
        assertPhysical(() -> codecs.lineages.decode(codecs.lineages.encode(codecs.lineages.prepareForStorage(wrongInitial))));
        var valid = new ManagedLineageIndex.Lineage(id, "authored", "x", 2, "x", List.of(zero,
                new ManagedLineageIndex.RetainedState(id, 1, "y"), two), -1);
        assertEquals(List.of(0L, 2L), codecs.lineages.decode(codecs.lineages.encode(codecs.lineages.prepareForStorage(valid))).epochsFor("x"));
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var storage = indexes(f.bytes); var stored = retain(storage, state(f));
            try (var owner = storage.openOwner(1)) {
                assertTrue(owner.find(stored.sessions(), stored.lineages(), stored.generations(), DocumentId.of("absent")).isEmpty());
                f.bytes.failRead = true;
                assertPhysical(() -> select(owner, stored, source.id()));
                f.bytes.failRead = false;
                assertEquals(0, select(owner, stored, source.id()).session().epoch());
            }
            var closed = storage.openOwner(1); closed.close(); assertPhysical(() -> select(closed, stored, source.id()));
        }
    }

    private static StoredDocumentIndexes indexes(CoordinationImmutableObjectStore bytes) { return new StoredDocumentIndexes(bytes, MAP, SESSION); }
    private static InMemoryDocumentStore.StoreState state(DocumentSessionStorageTest.Fixture fixture) throws Exception {
        Field field = InMemoryDocumentStore.class.getDeclaredField("state"); field.setAccessible(true);
        return (InMemoryDocumentStore.StoreState) field.get(fixture.engine.documents());
    }
    private static Stored retain(StoredDocumentIndexes storage, InMemoryDocumentStore.StoreState state) {
        return new Stored(storage.retainSessionPartition(state.sessionIndex()), storage.retainLineagePartition(state.lineageIndex()),
                storage.retainGenerationPartition(state.graphGenerations()));
    }
    private static ManagedLineageIndex reopenLineages(StoredDocumentIndexes storage, ManagedLineageIndex lineages) {
        return storage.openLineages(root -> storage.lineageRoot(lineages, root), lineages.lastMutationNodeCopies());
    }
    private static Stored reopen(StoredDocumentIndexes storage, Stored before) {
        var state = before.generations().storedState();
        return new Stored(storage.openSessions(before.sessions().storedRootDescriptor()), reopenLineages(storage, before.lineages()),
                storage.openGenerations(storage.generationRoot(before.generations()), state.comparisons(), state.copiedNodes()));
    }
    private static StoredDocumentIndexes.SelectedDocument select(StoredDocumentIndexes.Owner owner, Stored state, DocumentId id) {
        return owner.find(state.sessions(), state.lineages(), state.generations(), id).orElseThrow();
    }
    private record Stored(PersistentOrderedMap<DocumentId, StoreIndexCodecs.SessionAddress> sessions,
            ManagedLineageIndex lineages, ClosureGraphGenerationInventory generations) { }
    private static void assertPhysical(org.junit.jupiter.api.function.Executable action) { assertThrows(CoordinationObjectStorageException.class, action); }
    private static void assertPhysical(org.junit.jupiter.api.function.Executable action, String message) {
        assertThrows(CoordinationObjectStorageException.class, action, message);
    }
    static String rootNodeAddress(byte[] descriptor) throws Exception {
        try (var input = new DataInputStream(new ByteArrayInputStream(descriptor))) {
            assertEquals(0x504d5231, input.readInt());
            for (int binding = 0; binding < 3; binding++) input.skipNBytes(input.readInt());
            assertEquals(1, input.readUnsignedByte()); return HexFormat.of().formatHex(input.readNBytes(32));
        }
    }
    private static final class CountingBytes implements CoordinationImmutableObjectStore {
        final CoordinationImmutableObjectStore delegate; final Set<String> denied; final Set<String> sessionAddresses;
        final Map<String, Integer> sessionReads = new LinkedHashMap<>(); int reads; int writes;
        CountingBytes(CoordinationImmutableObjectStore delegate, Set<String> denied, Set<String> sessionAddresses) {
            this.delegate = delegate; this.denied = Set.copyOf(denied); this.sessionAddresses = Set.copyOf(sessionAddresses);
        }
        @Override public byte[] putIfAbsent(String digest, byte[] bytes) { writes++; return delegate.putIfAbsent(digest, bytes); }
        @Override public Optional<byte[]> get(String digest, int maximum) {
            reads++;
            if (sessionAddresses.contains(digest)) sessionReads.merge(digest, 1, Integer::sum);
            assertFalse(denied.contains(digest), "Unrelated selected session body read"); return delegate.get(digest, maximum);
        }
    }
}
