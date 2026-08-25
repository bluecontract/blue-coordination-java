package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused validation and immutability contracts for SDK public values. */
final class SdkValueModelTest {
    @Test
    void managedDocumentFluentDefinitionIsImmutableAndFailsClosed() {
        // given
        ManagedDocument base = ManagedDocument.yaml(
                "counter", "counter: 0");

        // when
        ManagedDocument admitted = base.publicRoot().fromNow();

        // then
        assertFalse(base.isPublicRoot());
        assertThrows(IllegalStateException.class, base::activationPolicy);
        assertTrue(admitted.isPublicRoot());
        assertEquals(DocumentId.of("counter"), admitted.id());
        assertEquals(ActivationPolicy.Kind.FROM_NOW,
                admitted.activationPolicy().kind());
    }

    @Test
    void managedClosurePreservesOrderAndRejectsAmbiguousEvidence() {
        // given
        ManagedClosure.Builder builder = ManagedClosure.builder()
                .document("b", "marker: b")
                .document("a", "marker: a")
                .bindOccurrence("b", "/a", "a")
                .bindOccurrence("a", "/b", "b")
                .publicRoot("a")
                .fromNow();

        // when
        ManagedClosure closure = builder.build();

        // then
        assertEquals(List.of("b", "a"), closure.documentAliases());
        assertEquals(Set.of("a"), closure.publicRootAliases());
        assertThrows(UnsupportedOperationException.class,
                () -> closure.publicRootAliases().add("b"));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedClosure.builder()
                        .document("a", "marker: a")
                        .bindOccurrence("a", "/b", "a")
                        .bindOccurrence("a", "/b", "a"));
        assertThrows(IllegalStateException.class,
                () -> ManagedClosure.builder()
                        .document("a", "marker: a")
                        .publicRoot("missing")
                        .fromNow()
                        .build());
    }

    @Test
    void exactValuesAndReadySnapshotsDetachMutableInput() {
        // given
        Node source = new Node().properties(
                "counter", new Node().value(BigInteger.valueOf(2L)),
                "enabled", new Node().value(true),
                "name", new Node().value("blue"));

        // when
        ExactBlueValue exact = new ExactBlueValue(
                ExactValue.verified(source));
        source.getProperties().get("counter").value(BigInteger.TEN);
        Node detached = exact.copyNode();
        detached.getProperties().get("counter").value(BigInteger.ZERO);
        List<PublicEvent> events = new ArrayList<>();
        DocumentSnapshot snapshot = new DocumentSnapshot(
                DocumentId.of("counter"), 3L, true, exact, events);
        events.add(new PublicEvent(exact));

        // then
        assertEquals(2L, snapshot.longAt("/counter"));
        assertEquals(BigInteger.valueOf(2L), exact.copyNode()
                .getProperties().get("counter").getValue());
        assertTrue(snapshot.booleanAt("/enabled"));
        assertEquals("blue", snapshot.textAt("/name"));
        assertTrue(snapshot.publicEvents().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentSnapshot(DocumentId.of("counter"),
                        3L, false, exact, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> snapshot.longAt("/name"));
    }

    @Test
    void draftsAndHandlesCannotBeSilentlyReusedAcrossOwners() {
        // given
        Object firstOwner = new Object();
        Object secondOwner = new Object();

        // when
        TimelineHandle firstTimeline = new TimelineHandle(
                firstOwner, "alice", "alice");
        TimelineHandle otherTimeline = new TimelineHandle(
                secondOwner, "alice", "alice");
        EntryHandle first = new EntryHandle(
                firstOwner, firstTimeline, "entry", 1L, 1L);
        EntryHandle sameEvidence = new EntryHandle(firstOwner, "entry");
        EntryHandle foreign = new EntryHandle(secondOwner, "entry");
        ExactBlueValue exact = exactScalar("state");
        ManagedDocumentDraft draft = new ManagedDocumentDraft(
                firstOwner, DocumentId.of("draft"), exact).atEpoch(5L);

        // then
        assertEquals(first, sameEvidence);
        assertNotEquals(first, foreign);
        assertNotEquals(firstTimeline, otherTimeline);
        assertEquals(5L, draft.knownEpoch().orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> new EntryHandle(firstOwner, otherTimeline,
                        "other", 1L, 1L));
    }

    @Test
    void resultsDefensivelyRetainIndependentClosureOutcomes() {
        // given
        Object owner = new Object();
        EntryHandle entry = new EntryHandle(owner, "entry");
        ExactBlueValue after = exactScalar("after");
        List<DocumentChange> changes = new ArrayList<>();
        changes.add(new DocumentChange(
                DocumentId.of("a"), 1L, null, after, List.of()));
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("COMPONENTS", 1L);
        ProcessingStats stats = new ProcessingStats(
                7L, 1L, 1L, 10L, List.of(DocumentId.of("a")), counters);

        // when
        ClosureResult applied = new ClosureResult(
                "closure-a", EntryDisposition.APPLIED, changes,
                List.of(), stats, Diagnostic.none());
        List<ClosureResult> closures = new ArrayList<>(List.of(applied));
        EntryResult result = new EntryResult(
                entry, EntryDisposition.APPLIED, closures,
                List.of(), stats, Diagnostic.none());
        closures.clear();
        changes.clear();
        counters.put("COMPONENTS", 99L);
        DrainResult drain = new DrainResult(
                List.of(result), stats, true, false, Diagnostic.none());

        // then
        assertTrue(result.applied());
        assertEquals(1, result.closures().size());
        assertEquals(1, applied.changes().size());
        assertTrue(applied.resourceDemands().isEmpty());
        assertEquals(1L, applied.processorAttemptCount());
        assertEquals(0L, applied.automaticRetryCount());
        assertEquals(1L, stats.counter("COMPONENTS"));
        assertEquals(result, drain.entry(entry));
        assertThrows(IllegalArgumentException.class,
                () -> drain.entry(new EntryHandle(new Object(), "entry")));
        assertThrows(IllegalArgumentException.class,
                () -> new DrainResult(List.of(), stats,
                        true, true, Diagnostic.none()));
    }

    @Test
    void frontierActivationAndDiagnosticsAreExactImmutableValues() {
        // given
        ExactBlueValue frontier = exactScalar("frontier");
        Map<String, String> details = new LinkedHashMap<>();
        details.put("documentId", "missing");

        // when
        ActivationPolicy policy = ActivationPolicy.importFromFrontier(
                frontier);
        Diagnostic diagnostic = new Diagnostic(
                "TARGET_DOCUMENT_NOT_FOUND", "Missing target", details);
        details.put("documentId", "changed");

        // then
        assertEquals(frontier, policy.frontierEvidence().orElseThrow());
        assertEquals(ActivationPolicy.Kind.IMPORT_FROM_FRONTIER,
                policy.kind());
        assertTrue(diagnostic.present());
        assertEquals("missing", diagnostic.details().get("documentId"));
        assertThrows(UnsupportedOperationException.class,
                () -> diagnostic.details().clear());
        assertFalse(Diagnostic.none().present());
    }

    private static ExactBlueValue exactScalar(String value) {
        return new ExactBlueValue(ExactValue.verified(
                new Node().value(value)));
    }
}
