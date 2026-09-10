package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.sdk.ActivationPolicy;
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.DocumentHandle;
import blue.coordination.sdk.EntryDisposition;
import blue.coordination.sdk.EntryHandle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real causal birth results exercise the independent store publication fences. */
final class RootedBirthPublicationTest {
    @Test void newOwnedSubscriptionsRequireExactPresentAndAbsentFences() throws IOException {
        // given
        try (var scenario = new Scenario()) {
            // when
            var before = scenario.engine.documents().closureSnapshot(Set.of(scenario.parent.id()));
            var outcome = scenario.blue.processing().processNext(scenario.parent).entry(scenario.creation);
            // then
            assertEquals(EntryDisposition.APPLIED, outcome.disposition());
            var result = scenario.blue.advanced().closureExecution(outcome.closures().get(0).closureId()).orElseThrow();
            var present = Map.of(scenario.parent.id(), before.graphGenerations().require(scenario.parent.id()));
            var absent = Set.of(scenario.child);
            var subscriptions = before.closureSubscriptions();
            assertDoesNotThrow(() -> subscriptions.applyOwned(result, present, absent));
            assertThrows(IllegalArgumentException.class, () -> subscriptions.applyOwned(result, present));
            assertThrows(IllegalArgumentException.class, () -> subscriptions.applyOwned(result, Map.of(), absent));
            assertThrows(IllegalArgumentException.class, () -> subscriptions.applyOwned(result, present, Set.of(scenario.parent.id())));
            assertThrows(IllegalArgumentException.class, () -> subscriptions.applyOwned(result,
                    Map.of(scenario.child, 0L), Set.of(scenario.parent.id())));
            assertThrows(IllegalArgumentException.class, () -> subscriptions.applyOwned(result,
                    Map.of(scenario.parent.id(), 0L, scenario.child, 0L), absent));
            assertThrows(IllegalArgumentException.class, () -> subscriptions.applyOwned(result, present,
                    Set.of(scenario.child, DocumentId.of("unrelated-birth"))));
            assertThrows(IllegalArgumentException.class, () -> scenario.engine.documents().closureSnapshot(
                    Set.of(scenario.parent.id(), scenario.child)).closureSubscriptions().applyOwned(result, present, absent));
        }
    }

    @Test void lostBirthResponseRestoresRoutesWithoutPublishingAgain() throws IOException {
        // given
        try (var scenario = new Scenario()) {
            // when
            var adapter = scenario.engine.contractsClosureAdapter();
            adapter.onPublicationFailurePoint(point -> { throw new IllegalStateException("lost birth response"); });
            // then
            assertThrows(RuntimeException.class, () -> scenario.blue.processing().processNext(scenario.parent));
            adapter.onPublicationFailurePoint(ignored -> { });
            var child = scenario.blue.documents().require(scenario.child);
            var heads = List.of(scenario.parent.snapshot().blueId(), child.snapshot().blueId());
            var history = scenario.blue.advanced().auditManagedEpochs(scenario.child).stream()
                    .map(blue.coordination.sdk.ManagedEpochReceipt::receiptIdentity).toList();
            assertEquals(1, history.size());
            scenario.blue.processing().processNext(scenario.parent);
            assertEquals(heads, List.of(scenario.parent.snapshot().blueId(), child.snapshot().blueId()));
            assertEquals(history, scenario.blue.advanced().auditManagedEpochs(scenario.child).stream()
                    .map(blue.coordination.sdk.ManagedEpochReceipt::receiptIdentity).toList());
            CoordinationTestControl.attach(scenario.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(heads, List.of(scenario.parent.snapshot().blueId(), child.snapshot().blueId()));
            assertTrue(scenario.blue.processing().processNext(scenario.parent).quiescent());
        }
    }

    private static final class Scenario implements AutoCloseable {
        final BlueCoordination blue = BlueCoordination.builder().contentDerivedDocumentIds().build();
        final DefaultCoordinationEngine engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
        final DocumentHandle parent;
        final DocumentId child;
        final EntryHandle creation;

        Scenario() throws IOException {
            var timeline = blue.timelines().register("rcp2/parent", "alice");
            parent = blue.documents().admitStaticProcessEmbedded(resource("parent.yaml"),
                    ActivationPolicy.importFullHistory()).document("root");
            var initial = blue.values().yaml(resource("source.yaml"));
            child = DocumentId.of(initial.blueId());
            var draft = blue.documents().draft(child, initial);
            creation = blue.operations().on(parent).from(timeline).call("attach").through("owner")
                    .request(request -> request.managed("child", draft)).expectOccurrence("/child", draft)
                    .activation(ActivationPolicy.fromNow()).submit();
        }

        @Override public void close() { blue.close(); }
    }

    private static String resource(String name) throws IOException {
        try (var in = RootedBirthPublicationTest.class.getResourceAsStream("/rooted/" + name)) {
            if (in == null) throw new IOException("Missing literal birth fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
