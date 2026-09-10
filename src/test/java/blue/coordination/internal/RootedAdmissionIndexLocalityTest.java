package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.sdk.ActivationPolicy;
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.DocumentHandle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual SDK admission must not rebuild unchanged incoming source surfaces. */
final class RootedAdmissionIndexLocalityTest {
    @Test void eachIndependentAdmissionRefreshesOnlyItsActualNewForwardSurface() throws Exception {
        // given
        try (var fixture = new Fixture()) {
            // when
            var source = fixture.source();
            String saved = fixture.retain(source);
            var first = fixture.parent("first", saved);
            var second = fixture.parent("second", saved);
            var sourceHistory = fixture.history(source);
            var firstHistory = fixture.history(first);
            var secondHistory = fixture.history(second);
            long roots = fixture.counter("sourceSurface.rootsResolved");
            long documents = fixture.counter("sourceSurface.documentsResolved");
            var third = fixture.parent("third", saved);
            // The previous members-based caller refreshes S plus all three
            // parents and fails these exact actual-work assertions.
            // then
            assertEquals(1L, fixture.counter("sourceSurface.rootsResolved") - roots);
            assertEquals(2L, fixture.counter("sourceSurface.documentsResolved") - documents);
            assertEquals(sourceHistory, fixture.history(source));
            assertEquals(firstHistory, fixture.history(first));
            assertEquals(secondHistory, fixture.history(second));
            assertEquals(saved, source.snapshot().blueId());
            assertEquals(0L, fixture.engine.documents().require(third.id()).epoch());
            assertEquals(1, fixture.history(third).size());
            var sourceView = fixture.engine.documents().require(source.id()).rootedView().snapshot();
            var thirdView = fixture.engine.documents().require(third.id()).rootedView().snapshot();
            fixture.engine.restartFromStores();
            assertEquals(sourceView.closureIdentity(), fixture.engine.documents().require(source.id()).rootedView().snapshot().closureIdentity());
            assertEquals(thirdView.closureIdentity(), fixture.engine.documents().require(third.id()).rootedView().snapshot().closureIdentity());
            assertEquals(sourceHistory, fixture.history(source));
            assertEquals(firstHistory, fixture.history(first));
            assertEquals(secondHistory, fixture.history(second));
        }
    }

    @Test void failedPreSwapAdmissionDoesNotPublishOrRefreshAndRetryInitializesOnce() throws Exception {
        // given
        try (var fixture = new Fixture()) {
            // when
            var source = fixture.source();
            String saved = fixture.retain(source);
            var first = fixture.parent("first", saved);
            String pending = fixture.parentYaml("pending", saved);
            fixture.blue.timelines().register("rcp2/pending", "alice");
            var before = fixture.engine.documents().publicationSnapshot();
            var sourceHistory = fixture.history(source);
            var firstHistory = fixture.history(first);
            long roots = fixture.counter("sourceSurface.rootsResolved");
            AtomicBoolean injected = new AtomicBoolean();
            fixture.engine.contractsClosureAdmissionAdapter().onFailurePoint(point -> {
                if (point == MultiDocumentPublicationTransaction.FailurePoint.BEFORE_SWAP) {
                    injected.set(true);
                    throw new IllegalStateException("admission-before-swap");
                }
            });
            // then
            assertThrows(RuntimeException.class, () -> fixture.admit(pending));
            assertTrue(injected.get());
            assertEquals(before, fixture.engine.documents().publicationSnapshot());
            assertEquals(roots, fixture.counter("sourceSurface.rootsResolved"));
            fixture.engine.contractsClosureAdmissionAdapter().onFailurePoint(ignored -> { });
            var admitted = fixture.admit(pending);
            assertEquals(roots + 1L, fixture.counter("sourceSurface.rootsResolved"));
            assertEquals(1, fixture.history(admitted).size());
            assertEquals(sourceHistory, fixture.history(source));
            assertEquals(firstHistory, fixture.history(first));
            assertEquals(saved, source.snapshot().blueId());
        }
    }

    @Test void committedAdmissionRecoversItsDisposableIndexesWithoutDuplicateHistory() throws Exception {
        // given
        try (var fixture = new Fixture()) {
            // when
            var source = fixture.source();
            String saved = fixture.retain(source);
            var first = fixture.parent("first", saved);
            String pending = fixture.parentYaml("pending", saved);
            fixture.blue.timelines().register("rcp2/pending", "alice");
            String original = fixture.blue.values().yaml(pending).blueId();
            var sourceHistory = fixture.history(source);
            var firstHistory = fixture.history(first);
            long roots = fixture.counter("sourceSurface.rootsResolved");
            AtomicBoolean injected = new AtomicBoolean();
            fixture.engine.contractsClosureAdmissionAdapter().onPublicationFailurePoint(point -> {
                if (point == ContractsClosureAdmissionAdapter.PublicationFailurePoint.AFTER_STORE_COMMIT_BEFORE_ROUTE_PUBLISH) {
                    injected.set(true);
                    throw new IllegalStateException("admission-after-commit");
                }
            });
            // then
            assertThrows(RuntimeException.class, () -> fixture.admit(pending));
            assertTrue(injected.get());
            assertEquals(roots, fixture.counter("sourceSurface.rootsResolved"));
            assertEquals(3, fixture.engine.documents().size());
            var committed = fixture.blue.advanced().auditManagedEpochs(DocumentId.of(original)).stream()
                    .map(receipt -> receipt.receiptIdentity()).toList();
            assertEquals(1, committed.size());
            fixture.engine.contractsClosureAdmissionAdapter().onPublicationFailurePoint(ignored -> { });
            var recovered = fixture.admit(pending);
            assertEquals(DocumentId.of(original), recovered.id());
            assertEquals(committed, fixture.history(recovered));
            assertEquals(sourceHistory, fixture.history(source));
            assertEquals(firstHistory, fixture.history(first));
            fixture.engine.restartFromStores();
            assertEquals(committed, fixture.history(recovered));
            assertEquals(sourceHistory, fixture.history(source));
            assertEquals(firstHistory, fixture.history(first));
            assertEquals(saved, source.snapshot().blueId());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Map<String, String> exact = new LinkedHashMap<>();
        final BlueCoordination blue = BlueCoordination.builder().contentDerivedDocumentIds()
                .exactNodeProvider(id -> Optional.ofNullable(exact.get(id))).build();
        final DefaultCoordinationEngine engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
        DocumentHandle source() throws IOException {
            blue.timelines().register("rcp2/source", "alice");
            return admit(resource("source.yaml"));
        }
        DocumentHandle parent(String name, String saved) throws IOException {
            blue.timelines().register("rcp2/" + name, "alice");
            return admit(parentYaml(name, saved));
        }
        String parentYaml(String name, String saved) throws IOException {
            return resource("parent.yaml").replace("RCP2 Parent", "RCP2 " + name)
                    .replace("rcp2/parent", "rcp2/" + name) + "\nchild:\n  blueId: " + saved + "\n";
        }
        DocumentHandle admit(String yaml) {
            return blue.documents().admitStaticProcessEmbedded(yaml, ActivationPolicy.importFullHistory()).document("root");
        }
        String retain(DocumentHandle document) {
            String identity = document.snapshot().blueId();
            exact.put(identity, document.snapshot().exact().json());
            return identity;
        }
        List<String> history(DocumentHandle document) {
            return blue.advanced().auditManagedEpochs(document.id()).stream().map(receipt -> receipt.receiptIdentity()).toList();
        }
        long counter(String name) { return engine.metricsSnapshot().counters().getOrDefault(name, 0L); }
        @Override public void close() { blue.close(); }
    }
    private static String resource(String name) throws IOException {
        try (var input = RootedAdmissionIndexLocalityTest.class.getResourceAsStream("/rooted/" + name)) {
            if (input == null) throw new IOException("Missing rooted literal " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
