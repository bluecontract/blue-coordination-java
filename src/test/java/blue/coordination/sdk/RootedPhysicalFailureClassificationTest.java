package blue.coordination.sdk;

import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.api.SourceHistoryPrerequisiteObservation;
import blue.coordination.internal.BundledContracts10Release;
import blue.language.processor.NoncommittingExecutionException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Genuine provider failure, not a private store-field injection. */
final class RootedPhysicalFailureClassificationTest {
    @Test void typedProviderOutageCannotBecomeSemanticMissingOrInvalidContent() throws Exception {
        assertEquals(run(false), run(true), "Same entry resumes to the exact no-outage semantic result");
    }

    private Outcome run(boolean failFirstRead) throws Exception {
        String source; String sourceBody;
        try (var producer = create(ExactNodeProvider.empty())) {
            var authored = producer.values().yaml(resource("source.yaml"));
            source = authored.blueId(); sourceBody = authored.json();
        }
        var outage = new CoordinationObjectStorageException("Selected exact source storage is unavailable");
        AtomicInteger reads = new AtomicInteger();
        AtomicBoolean unavailable = new AtomicBoolean(failFirstRead);
        try (var blue = create(id -> {
            if (source.equals(id)) {
                reads.incrementAndGet(); if (unavailable.get()) throw outage;
                return Optional.of(sourceBody);
            }
            return Optional.empty();
        })) {
            var timeline = blue.timelines().register("rcp2/parent", "alice");
            blue.timelines().register("rcp2/source", "alice");
            var parent = blue.documents().admitStaticProcessEmbedded(resource("parent.yaml"),
                    ActivationPolicy.importFullHistory()).document("root");
            var before = parent.snapshot().blueId();
            var history = history(blue, parent);
            var entry = blue.operations().on(parent).from(timeline).call("attach").through("owner")
                    .requestYaml("child:\n  blueId: " + source).submit();
            if (failFirstRead) {
                var actual = assertThrows(NoncommittingExecutionException.class, () -> blue.processing().processNext(parent));
                assertSame(outage, actual);
                assertTrue(reads.get() > 0, "The real selected provider was reached");
            }
            assertEquals(before, parent.snapshot().blueId());
            assertEquals(history, history(blue, parent));
            unavailable.set(false);
            var stopped = blue.processing().processNext(parent).entry(entry);
            assertEquals(EntryDisposition.NEEDS_RESOURCES, stopped.disposition());
            var sourceAdmission = blue.advanced().sourceHistoryPrerequisites(parent).get(0);
            assertTrue(blue.advanced().processSourceHistoryPrerequisite(sourceAdmission).admission().orElseThrow().published());
            assertEquals(SourceHistoryPrerequisiteObservation.Status.SATISFIED,
                    blue.advanced().observeSourceHistoryPrerequisite(sourceAdmission).status());
            var committed = blue.processing().processNext(parent).entry(entry);
            assertEquals(EntryDisposition.APPLIED, committed.disposition());
            assertEquals(1, blue.advanced().auditTimeline("rcp2/parent").size(), "Retry cannot append a second entry");
            // Finish the same post-attachment checkpoint work in both runs;
            // quiescent describes what remains, not whether this call published.
            var continuation = blue.processing().processNext(parent);
            assertTrue(continuation.quiescent());
            var retained = history(blue, parent);
            var idle = blue.processing().processNext(parent);
            assertTrue(idle.quiescent());
            assertEquals(0L, idle.stats().gas());
            assertEquals(0L, idle.stats().committedTransitions());
            assertEquals(retained, history(blue, parent));
            return new Outcome(entry.blueId(), parent.snapshot().blueId(), retained, committed.stats().gas(),
                    committed.stats().documentStepOrder(), committed.closures().stream().map(ClosureResult::closureId).toList(),
                    committed.diagnostic(), committed.stats().counters(), continuation.stats().gas(),
                    continuation.stats().documentStepOrder(), continuation.stats().counters());
        }
    }

    private static List<String> history(BlueCoordination blue, DocumentHandle document) {
        return blue.advanced().auditManagedEpochs(document.id()).stream().map(ManagedEpochReceipt::receiptIdentity).toList();
    }
    private record Outcome(String entry, String head, List<String> history, long gas,
            List<blue.coordination.api.DocumentId> stepOrder, List<String> closures, Diagnostic diagnostic,
            Map<String, Long> counters, long continuationGas,
            List<blue.coordination.api.DocumentId> continuationStepOrder, Map<String, Long> continuationCounters) { }

    private static BlueCoordination create(ExactNodeProvider provider) {
        return BlueCoordination.builder().contentDerivedDocumentIds()
                .release(BundledContracts10Release.manifest().blueLanguageSpecification(),
                        BundledContracts10Release.manifest().contractsSpecification())
                .exactNodeProvider(provider).build();
    }
    private static String resource(String name) throws Exception {
        try (var in = RootedPhysicalFailureClassificationTest.class.getResourceAsStream("/rooted/" + name)) {
            return new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
