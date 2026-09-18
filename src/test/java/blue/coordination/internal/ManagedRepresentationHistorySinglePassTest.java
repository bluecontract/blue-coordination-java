package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ManagedRepresentationCause;
import blue.language.processor.closure.ManagedRepresentationCursor;
import blue.language.processor.closure.ManagedRepresentationTransition;
import blue.language.processor.closure.ScopeAddress;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real retained publications, with read counting only at the test's durable publication map. */
final class ManagedRepresentationHistorySinglePassTest {
    private static final String WRONG = "sha256:" + "f".repeat(64);

    @Test void causeVerificationAuthenticatesTheFullChainOnceInBothBoundaryModes() throws Exception {
        // given
        try (var f = new Fixture(); var reads = new PublicationReads(f)) {
            var before = f.scenario.state();
            var cause = f.cause(f.first(), f.last().positionIdentity(), null);
            var pending = f.pending(f.last().positionIdentity());
            for (boolean rooted : List.of(false, true)) {
                for (boolean cold : List.of(false, true)) {
                    if (cold) f.scenario.engine.documents().clearRepresentationVerifications();
                    reads.reset();
                    // when
                    f.verifyCause(cause, pending, rooted);
                    // then
                    assertEquals(cold ? 4 : 2, reads.gets,
                            "Each row is selected once; a cold proof also rechecks current membership before retention");
                    reads.reset();
                    f.verifyCause(cause, pending, rooted);
                    assertEquals(2, reads.gets, "The next call still selects both current original publications");
                }
            }
            assertEquals(before, f.scenario.state(), "Validation changes no bodies, receipts, events or gas");
        }
    }

    @Test void successorVerificationReusesOneFullChainForPrefixAndSuppliedEvidenceChecks() throws Exception {
        // given
        try (var f = new Fixture(); var reads = new PublicationReads(f)) {
            var before = f.scenario.state();
            var work = f.work(f.last().positionIdentity());
            for (boolean cold : List.of(false, true)) {
                if (cold) f.scenario.engine.documents().clearRepresentationVerifications();
                reads.reset();
                // when
                f.history.verifySuccessor(work, f.selected(f.last()), f.boundary());
                // then
                assertEquals(cold ? 4 : 2, reads.gets,
                        "Prefix and original-evidence checks share one fully authenticated chain");
                reads.reset();
                f.history.verifySuccessor(work, f.selected(f.last()), f.boundary());
                assertEquals(2, reads.gets, "No proof survives this method call as a chain certificate");
            }
            assertEquals(before, f.scenario.state());
        }
    }

    @Test void aColdProofIsNotRetainedAfterPublicationOrMemoReplacement() throws Exception {
        // given
        try (var f = new Fixture(); var reads = new PublicationReads(f)) {
            var documents = f.scenario.engine.documents();
            var position = f.first();
            var publication = f.scenario.publication(position);
            // when
            var replacement = new ContractsClosurePublicationReceipt(publication.publicationIdentity(), publication.documentIds(),
                    publication.attempt(), publication.automaticRetryCount(), publication.managedSurfaceEvidence(),
                    publication.rejectedDraftPlan(), publication.rootedTerminalEvidence());
            // then
            assertEquals(publication, replacement);
            var before = f.scenario.state();
            for (String change : List.of("removed publication", "equal replacement", "cleared memo")) {
                documents.clearRepresentationVerifications();
                reads.reset();
                // The second selection is the existing post-proof guard, not a new timing/test hook.
                reads.beforeReturn = (key, count) -> {
                    if (count != 2) return;
                    assertEquals(publication.publicationIdentity(), key);
                    switch (change) {
                        case "removed publication" -> reads.rows.remove(key);
                        case "equal replacement" -> reads.rows.put(publication.publicationIdentity(), replacement);
                        case "cleared memo" -> documents.clearRepresentationVerifications();
                        default -> throw new AssertionError(change);
                    }
                };
                var unretained = f.proveRetained(position);
                assertEquals(2, reads.gets);
                assertEquals(position.positionIdentity(), unretained.positionIdentity());
                reads.beforeReturn = (key, count) -> { };
                reads.rows.put(publication.publicationIdentity(), publication);
                reads.reset();
                var retained = f.proveRetained(position);
                assertEquals(2, reads.gets, "Changed authority cannot lend a completed memo insertion: " + change);
                assertNotSame(unretained, retained);
                reads.reset();
                assertSame(retained, f.proveRetained(position));
                assertEquals(1, reads.gets, "A warm proof still selects the current exact publication");
            }
            assertEquals(before, f.scenario.state());
        }
    }

    @Test void aFrozenFirstPositionStillAuthenticatesMissingAndMalformedLaterSuffixes() throws Exception {
        // given
        try (var f = new Fixture(); var reads = new PublicationReads(f)) {
            String target = f.first().positionIdentity();
            var cause = f.cause(f.first(), target, null);
            var pending = f.pending(target);
            var work = f.work(target);
            var selected = f.selected(f.first());
            List<Runnable> checks = List.of(
                    () -> f.verifyCause(cause, pending, false),
                    () -> f.verifyCause(cause, pending, true),
                    () -> f.history.verifySuccessor(work, selected, f.boundary()));
            checks.forEach(Runnable::run);
            var later = f.scenario.publication(f.last());
            // when
            reads.rows.remove(later.publicationIdentity());
            for (var check : checks) {
                // then
                assertThrows(IllegalArgumentException.class, check::run,
                        "Even a warmed target before the missing suffix requires the complete history");
            }
            var malformed = new ContractsClosurePublicationReceipt(later.publicationIdentity(), later.documentIds(),
                    later.attempt(), later.automaticRetryCount(), later.managedSurfaceEvidence(), null, null);
            reads.rows.put(later.publicationIdentity(), malformed);
            for (var check : checks) {
                assertThrows(IllegalArgumentException.class, check::run,
                        "A later original checkpoint publication cannot lose rooted authority");
            }
            reads.rows.put(later.publicationIdentity(), later);
            checks.forEach(Runnable::run);
        }
    }

    @Test void causeVerificationStillRejectsSkippedPositionsAndChangedTargetOrNextReceipt() throws Exception {
        // given
        try (var f = new Fixture()) {
            var before = f.scenario.state();
            var pending = f.pending(f.last().positionIdentity());
            // when
            var malformed = List.of(
                    f.cause(f.last(), f.last().positionIdentity(), null),
                    f.cause(f.first(), WRONG, null),
                    f.cause(f.first(), f.last().positionIdentity(), WRONG));
            for (boolean rooted : List.of(false, true)) {
                for (var cause : malformed) {
                    // then
                    assertThrows(IllegalArgumentException.class, () -> f.verifyCause(cause, pending, rooted));
                }
                var active = ManagedOccurrenceBinding.derived(pending.bindingPolicyIdentity(), pending.sourceDocumentId(),
                        pending.sourceAddress(), pending.targetDocumentId(), pending.expectedTargetBlueId(), true, null);
                assertThrows(IllegalArgumentException.class,
                        () -> f.verifyCause(f.cause(f.first(), f.last().positionIdentity(), null), active, rooted));
            }
            assertEquals(before, f.scenario.state());
        }
    }

    @Test void successorAndStandaloneSuppliedVerificationKeepTheirIndependentGuards() throws Exception {
        // given
        try (var f = new Fixture(); var reads = new PublicationReads(f)) {
            // when
            var before = f.scenario.state();
            // then
            assertThrows(IllegalArgumentException.class,
                    () -> f.history.verifySuccessor(f.work(WRONG), f.selected(f.last()), f.boundary()));
            assertThrows(IllegalArgumentException.class,
                    () -> f.history.verifySuccessor(f.work(f.last().positionIdentity()), f.selected(f.first()), f.boundary()));
            var p = f.first();
            var forged = new ManagedRepresentationTransition(p.documentId(), p.epoch(), WRONG,
                    p.predecessorPositionIdentity(), p.originalInput(), p.originalResult(), p.transitionReceipt().transitionReceiptIdentity());
            reads.reset();
            assertThrows(IllegalArgumentException.class, () -> f.history.verifySupplied(forged));
            assertEquals(2, reads.gets, "Standalone supplied verification still loads a complete durable chain");
            reads.reset();
            f.history.verifySupplied(p);
            assertEquals(2, reads.gets);
            assertEquals(before, f.scenario.state());
        }
    }

    @Test void privateSuppliedEvidenceCheckRejectsAChainForAnotherDocumentOrEpoch() throws Exception {
        // given
        try (var f = new Fixture()) {
            var verify = ManagedRepresentationHistory.class.getDeclaredMethod("verifySupplied",
                    ManagedRepresentationTransition.class, ManagedRepresentationHistory.Chain.class);
            // when
            verify.setAccessible(true);
            for (var foreign : List.of(
                    new ManagedRepresentationHistory.Chain(DocumentId.of("foreign-source"), f.chain.epoch(),
                            f.chain.anchor(), f.chain.transitions(), f.chain.targetPositionIdentity(), null),
                    new ManagedRepresentationHistory.Chain(f.chain.documentId(), f.chain.epoch() + 1,
                            f.chain.anchor(), f.chain.transitions(), f.chain.targetPositionIdentity(), null))) {
                // Even copied exact positions cannot substitute for the helper's matching subject.
                // then
                var failure = assertThrows(InvocationTargetException.class, () -> verify.invoke(null, f.first(), foreign));
                assertInstanceOf(IllegalArgumentException.class, failure.getCause());
                assertEquals("Supplied representation belongs to another source history", failure.getCause().getMessage());
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ManagedRepresentationVerificationMemoTest.Scenario scenario = new ManagedRepresentationVerificationMemoTest.Scenario();
        final ManagedRepresentationHistory.Chain chain = scenario.chain();
        final ManagedRepresentationHistory history = new ManagedRepresentationHistory(scenario.engine.documents())
                .forConsumer(scenario.parent.id());

        Fixture() throws Exception { assertEquals(2, chain.transitions().size()); }
        ManagedRepresentationTransition first() { return chain.transitions().get(0); }
        ManagedRepresentationTransition last() { return chain.transitions().get(1); }
        ManagedRepresentationTransition proveRetained(ManagedRepresentationTransition position) {
            String publication = position.originalResult().rootedProjection().context()
                    .terminalKey(position.originalResult().rootedProjection().deliveryBasisIdentity());
            return scenario.engine.documents().proveRetainedRepresentation(publication, position.documentId(), position.epoch(),
                    position.anchorReceiptIdentity(), position.predecessorPositionIdentity(),
                    position.transitionReceipt().transitionReceiptIdentity());
        }
        blue.language.processor.ExternalOrderKey boundary() {
            return scenario.engine.documents().require(scenario.parent.id()).rootedView().logicalBoundary();
        }
        ManagedDocumentSnapshot selected(ManagedRepresentationTransition position) {
            return scenario.engine.documents().require(scenario.parent.id())
                    .rootedViewForInvocation(position.originalResult().invocationIdentity()).retainedSnapshot()
                    .managedDocument(position.documentId());
        }
        ManagedOccurrenceBinding pending(String target) {
            // The validator receives typed occurrence coordinates, not a forged publication or proof.
            return ManagedOccurrenceBinding.derived(first().originalInput().environment().managedBindingPolicyIdentity(),
                    ContractsClosureAdapter.closureId(scenario.source.id()), ScopeAddress.embedded("/pending", 1),
                    first().documentId(), chain.anchor().afterBlueId(), false, chain.epoch())
                    .withRepresentationCursor(new ManagedRepresentationCursor(chain.anchor().receiptIdentity(),
                            chain.anchor().receiptIdentity(), target, null));
        }
        ManagedRepresentationCause cause(ManagedRepresentationTransition position, String target, String next) {
            return new ManagedRepresentationCause(pending(target).occurrenceIdentity(), position, target, next,
                    scenario.engine.objects().cyclicSetProofFor(position.transitionReceipt().afterBlueId()).proof().orElse(null));
        }
        ManagedEpochApplicationWork work(String target) {
            var occurrence = pending(target);
            var coordinates = ManagedEpochApplicationWork.identified(WRONG, WRONG, chain.anchor().receiptIdentity(),
                    scenario.parent.id(), chain.epoch(), DocumentId.of(occurrence.sourceDocumentId().value()),
                    occurrence.occurrenceIdentity(), occurrence.sourcePath(), occurrence.activationGeneration(),
                    0, scenario.source.snapshot().blueId(), 0);
            return ManagedEpochApplicationWork.identifiedWithSuccessorRepresentationCause(coordinates,
                    cause(first(), target, null));
        }
        void verifyCause(ManagedRepresentationCause cause, ManagedOccurrenceBinding pending, boolean rooted) {
            if (rooted) history.verifyCause(cause, pending, boundary());
            else history.verifyCause(cause, pending);
        }
        @Override public void close() { scenario.close(); }
    }

    private static final class PublicationReads extends AbstractMap<String, ContractsClosurePublicationReceipt>
            implements AutoCloseable {
        final Object state;
        final Field field;
        final Map<String, ContractsClosurePublicationReceipt> original;
        final Map<String, ContractsClosurePublicationReceipt> rows;
        final Set<String> counted;
        int gets;
        java.util.function.BiConsumer<Object, Integer> beforeReturn = (key, count) -> { };

        @SuppressWarnings("unchecked")
        PublicationReads(Fixture f) throws Exception {
            counted = Set.of(f.scenario.publication(f.first()).publicationIdentity(),
                    f.scenario.publication(f.last()).publicationIdentity());
            state = f.scenario.engine.documents().storedState();
            field = state.getClass().getDeclaredField("closurePublicationReceipts");
            field.setAccessible(true);
            original = (Map<String, ContractsClosurePublicationReceipt>) field.get(state);
            rows = new LinkedHashMap<>(original);
            field.set(state, this);
        }
        void reset() { gets = 0; }
        @Override public ContractsClosurePublicationReceipt get(Object key) {
            if (counted.contains(key)) gets++;
            beforeReturn.accept(key, gets);
            return rows.get(key);
        }
        @Override public Set<Entry<String, ContractsClosurePublicationReceipt>> entrySet() { return rows.entrySet(); }
        @Override public void close() throws IllegalAccessException { field.set(state, original); }
    }
}
