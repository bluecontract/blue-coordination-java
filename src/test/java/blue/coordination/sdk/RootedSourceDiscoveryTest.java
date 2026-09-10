package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.CoordinationException;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Physical first loading cannot become a bare source's semantic birth. */
final class RootedSourceDiscoveryTest {
    @Test
    void missingHistoryRemainsAWaitUntilCommittedEvidenceIsSupplied() throws IOException {
        // given
        String sourceYaml = RootedSdkFixture.resource("source.yaml");
        // when
        String initialId;
        String providerJson;
        try (var preparation = BlueCoordination.builder().contentDerivedDocumentIds().build()) {
            var source = preparation.values().yaml(sourceYaml);
            initialId = source.blueId(); providerJson = source.json();
        }
        try (var fixture = new RootedSdkFixture()) {
            var blue = fixture.blue;
            var parent = fixture.start("parent.yaml", "rcp2/parent", Map.of());
            fixture.timelines.put("rcp2/source", blue.timelines().register("rcp2/source", "alice"));
            var source15 = fixture.appendReference(initialId, "rcp2/source", "setCounter", 15, "counterValue: 5", false);
            var attachment = fixture.append(parent, "rcp2/parent", "attach", 20, "child:\n  blueId: " + initialId);
            var before = parent.snapshot().blueId();
            // then
            assertEquals(EntryDisposition.NEEDS_RESOURCES, blue.processing().processNext(parent).entry(attachment).disposition());
            fixture.exact.put(initialId, "name: wrong supplied source\ncounter: 999\n");
            var wrongIdentity = assertThrows(CoordinationException.class,
                    () -> blue.processing().processNext(parent));
            assertEquals(CoordinationErrorCode.FROZEN_PROCESSING_FAILED, wrongIdentity.code());
            var identityCause = assertInstanceOf(CoordinationException.class, wrongIdentity.getCause());
            assertEquals(CoordinationErrorCode.INVALID_DOCUMENT_IDENTITY, identityCause.code());
            assertEquals(initialId, identityCause.details().get("blueId"));
            assertEquals(before, parent.snapshot().blueId());
            assertEquals(1, fixture.history(parent).size());
            fixture.exact.put(initialId, providerJson);
            var bodyOnly = blue.processing().processNext(parent);
            assertEquals(EntryDisposition.NEEDS_RESOURCES, bodyOnly.entry(attachment).disposition());
            assertFalse(bodyOnly.quiescent());
            assertEquals(before, parent.snapshot().blueId());
            assertEquals(1, fixture.history(parent).size());
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertEquals(EntryDisposition.NEEDS_RESOURCES, blue.processing().processNext(parent).entry(attachment).disposition());
            assertEquals(before, parent.snapshot().blueId());

            // Supply history through independent, real source admission and
            // processing. Exact source bytes alone must never forge these receipts.
            var source = fixture.startYaml(sourceYaml, "rcp2/source");
            assertEquals(initialId, source.id().value());
            assertEquals(EntryDisposition.APPLIED, blue.processing().processNext(source).entry(source15).disposition());
            assertEquals("FULL_HISTORY", ((Map<?, ?>) fixture.control.historyBasis(source.id()).get("admission")).get("mode"));
            var sourceHistory = fixture.history(source);
            assertEquals(EntryDisposition.APPLIED, blue.processing().processNext(parent).entry(attachment).disposition());
            assertEquals(1, blue.processing().processNext(parent).managedEpochApplications().size(), "Authored -1 to initialized zero");
            var completed = blue.processing().processNext(parent);
            assertEquals(1, completed.managedEpochApplications().size(), "The immutable source15 revision");
            assertTrue(completed.quiescent());
            assertEquals(5L, parent.snapshot().longAt("/seen"));
            assertEquals(sourceHistory, fixture.history(source));
            var head = parent.snapshot().blueId();
            var history = fixture.history(parent);
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertEquals(head, parent.snapshot().blueId());
            assertEquals(history, fixture.history(parent));
            assertEquals(sourceHistory, fixture.history(source));
        }
    }

    @Test
    void bareSourceKeepsHistoryBeforeTheLaterAttachment() throws IOException {
        // given
        boolean suspended = false;
        // when
        var result = RootedSourcePrerequisiteTest.runDiscovery(suspended);
        // then
        assertEquals(2, ((java.util.List<?>) result.get("sourceHistory")).size());
    }

    @Test
    void suspendingTheEarlierLoaderCannotDiscardTheSourcesEarlierInput() throws IOException {
        // given
        boolean suspended = true;
        // when
        var result = RootedSourcePrerequisiteTest.runDiscovery(suspended);
        // then
        assertEquals(2, ((java.util.List<?>) result.get("sourceHistory")).size());
    }

}
