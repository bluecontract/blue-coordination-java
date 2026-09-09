package blue.coordination.sdk;

import org.junit.jupiter.api.Test;
import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.DocumentId;
import blue.coordination.internal.CoordinationTestControl;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Public admission cases at empty and actual-entry logical frontiers. */
final class RootedAdmissionBasisTest {
    @Test void fromNowAtEmptyJournalRemainsAUsablePublicAdmission() {
        try (var fixture = new RootedSdkFixture()) {
            var document = fixture.blue.documents().admitStaticProcessEmbedded(
                    "name: Rooted empty frontier\ncounter: 0\n", ActivationPolicy.fromNow()).document("root");
            assertEquals(0L, document.snapshot().longAt("/counter"));
            assertEquals(0L, fixture.blue.advanced().auditDocument(document.id()).epoch());
        }
    }

    @Test void emptyAuthoritativeTimelineRetainsBeginningThenProcessesFirstEntryOnceAcrossRestart() throws Exception {
        try (var fixture = new RootedSdkFixture()) {
            fixture.timelines.put("rcp2/source", fixture.blue.timelines().register("rcp2/source", "alice"));
            var document = fixture.blue.documents().admitStaticProcessEmbedded(
                    RootedSdkFixture.resource("source.yaml"), ActivationPolicy.fromNow()).document("root");
            var history = fixture.control.historyBasis(document.id());
            assertEquals(Map.of("mode", "FROM_NOW", "lowerExclusiveOrder", Map.of("kind", "BEGINNING")),
                    history.get("admission"));
            var initialReceipts = fixture.history(document);
            assertEquals(1, initialReceipts.size());
            CoordinationTestControl.attach(fixture.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(history, fixture.control.historyBasis(document.id()));
            assertEquals(initialReceipts, fixture.history(document));
            var entry = fixture.append(document, "rcp2/source", "tick", 1L, "{}");
            assertEquals(EntryDisposition.APPLIED, fixture.blue.processing().processNext(document).entry(entry).disposition());
            assertEquals(1L, document.snapshot().longAt("/counter"));
            var completed = fixture.history(document);
            assertEquals(initialReceipts, completed.subList(0, 1));
            assertEquals(2, completed.size());
            assertEquals(1, fixture.blue.advanced().auditManagedEpochs(document.id()).get(1).emittedEvents().size());
            CoordinationTestControl.attach(fixture.blue.advanced().rawEngine()).restartFromStores();
            assertTrue(fixture.blue.processing().processNext(document).entries().isEmpty());
            assertEquals(completed, fixture.history(document));
            assertEquals(history, fixture.control.historyBasis(document.id()));
            assertEquals(1L, document.snapshot().longAt("/counter"));
        }
    }

    @Test void realFromNowActivationEntryKeepsItsExactThreeFieldOrder() throws Exception {
        try (var fixture = new RootedSdkFixture()) {
            var source = fixture.start("source.yaml", "rcp2/source", Map.of());
            var entry = fixture.append(source, "rcp2/source", "tick", 10L, "{}");
            var target = fixture.blue.documents().admitStaticProcessEmbedded(
                    RootedSdkFixture.resource("source.yaml").replace("RCP2 Source", "RCP2 Later"),
                    ActivationPolicy.fromNow()).document("root");
            var actual = ((blue.coordination.internal.DefaultCoordinationEngine) fixture.blue.advanced().rawEngine()).auditTimelineEntries().get(0).sourceOrderKey().components();
            assertEquals(Map.of("mode", "FROM_NOW", "lowerExclusiveOrder", Map.of(
                    "timestampUs", actual.get(0).toString(), "timelineBlueId", actual.get(1), "entryBlueId", entry.blueId())),
                    fixture.control.historyBasis(target.id()).get("admission"));
            assertEquals(0L, target.snapshot().longAt("/counter"));
        }
    }

    @Test void unknownProviderCannotTurnAnEmptyLocalStoreIntoBeginning() throws Exception {
        rejectedBeginning(false, false);
    }

    @Test void unavailableProviderCannotTurnAnEmptyLocalStoreIntoBeginning() throws Exception {
        rejectedBeginning(true, false);
    }

    @Test void invalidProviderEvidenceCannotTurnAnEmptyLocalStoreIntoBeginning() throws Exception {
        rejectedBeginning(true, true);
    }

    private static void rejectedBeginning(boolean registered, boolean invalid) throws Exception {
        try (var fixture = new RootedSdkFixture()) {
            String yaml = RootedSdkFixture.resource("source.yaml");
            var id = DocumentId.of(fixture.blue.values().yaml(yaml).blueId());
            if (registered) {
                fixture.blue.timelines().register("rcp2/source", "alice");
                var control = CoordinationTestControl.attach(fixture.blue.advanced().rawEngine());
                if (invalid) control.invalidateHistoricalEvidence("invalid empty admission proof");
                else control.makeHistoricalUnavailable("empty admission provider unavailable");
            }
            var journalBefore = fixture.blue.advanced().auditTimelineEntries();
            var failure = assertThrows(CoordinationException.class, () -> fixture.blue.documents()
                    .admitStaticProcessEmbedded(yaml, ActivationPolicy.fromNow()));
            assertEquals(registered && !invalid ? CoordinationErrorCode.NEEDS_RESOURCES
                    : CoordinationErrorCode.INVALID_ACTIVATION_EVIDENCE, failure.code());
            assertEquals(registered ? (invalid ? "INVALID_EVIDENCE" : "DEFERRED_UNAVAILABLE")
                    : "UNKNOWN_PROVIDER", failure.details().get("reason"));
            assertEquals(journalBefore, fixture.blue.advanced().auditTimelineEntries());
            assertThrows(CoordinationException.class, () -> fixture.blue.documents().require(id));
        }
    }

    @Test void beginningRejectsWrongRegisteredActorWithoutPublishing() throws Exception {
        try (var fixture = new RootedSdkFixture()) {
            fixture.blue.timelines().register("rcp2/source", "alice");
            assertRejectedBeginning(fixture, RootedSdkFixture.resource("source.yaml")
                    .replace("accountId: alice", "accountId: bob"), "REGISTERED_ACTOR_MISMATCH");
        }
    }

    @Test void beginningRejectsUnknownExactTimelineProviderType() throws Exception {
        try (var fixture = new RootedSdkFixture()) {
            fixture.blue.timelines().register("rcp2/source", "alice");
            assertRejectedBeginning(fixture, RootedSdkFixture.resource("source.yaml")
                    .replace("      type: MyOS/MyOS Timeline\n", "      type:\n"
                            + "        name: Unregistered beginning provider\n"
                            + "        type: MyOS/MyOS Timeline\n"), "UNSUPPORTED_COORDINATION_TYPE");
        }
    }

    @Test void beginningRejectsCustomActorBytesDespiteMatchingAccount() throws Exception {
        try (var fixture = new RootedSdkFixture()) {
            fixture.blue.timelines().register("rcp2/source", "alice");
            assertRejectedBeginning(fixture, RootedSdkFixture.resource("source.yaml")
                    .replace("      type: MyOS/Principal Actor\n", "      type:\n"
                            + "        name: Unregistered beginning actor\n"
                            + "        type: MyOS/Principal Actor\n"), "REGISTERED_ACTOR_MISMATCH");
        }
    }

    @Test void beginningRejectsExtraFieldsOnTheExactTimelineProviderType() throws Exception {
        try (var fixture = new RootedSdkFixture()) {
            fixture.blue.timelines().register("rcp2/source", "alice");
            assertRejectedBeginning(fixture, RootedSdkFixture.resource("source.yaml")
                    .replace("timelineId: rcp2/source", "timelineId: rcp2/source\n      unregisteredEvidence: true"),
                    "UNSUPPORTED_COORDINATION_TYPE");
        }
    }

    @Test void beginningRejectsExtraFieldsOnTheExactRegisteredActorType() throws Exception {
        try (var fixture = new RootedSdkFixture()) {
            fixture.blue.timelines().register("rcp2/source", "alice");
            assertRejectedBeginning(fixture, RootedSdkFixture.resource("source.yaml")
                    .replace("accountId: alice", "accountId: alice\n      unregisteredEvidence: true"),
                    "REGISTERED_ACTOR_MISMATCH");
        }
    }

    @Test void beginningRejectsUnverifiedRegisteredActorKind() throws Exception {
        try (var fixture = new RootedSdkFixture()) {
            fixture.blue.timelines().register("rcp2/source", "alice", TimelineActorKind.AGENT);
            assertRejectedBeginning(fixture, RootedSdkFixture.resource("source.yaml"), "UNSUPPORTED_ACTOR_PROVIDER");
        }
    }

    @Test void compositeBeginningVerifiesBothSourcesThenProcessesBothOnceAcrossRestart() throws Exception {
        acceptedAggregateBeginning(false);
    }

    @Test void allTimelinesBeginningVerifiesBothSourcesThenProcessesBothOnceAcrossRestart() throws Exception {
        acceptedAggregateBeginning(true);
    }

    @Test void compositeBeginningCannotHideAnUnregisteredSecondSource() throws Exception {
        rejectedAggregateBeginning(false, false);
    }

    @Test void allTimelinesBeginningCannotHideAnUnregisteredSecondSource() throws Exception {
        rejectedAggregateBeginning(true, false);
    }

    @Test void compositeBeginningCannotHideAWrongActorInSecondSource() throws Exception {
        rejectedAggregateBeginning(false, true);
    }

    @Test void allTimelinesBeginningCannotHideAWrongActorInSecondSource() throws Exception {
        rejectedAggregateBeginning(true, true);
    }

    private static void acceptedAggregateBeginning(boolean all) throws Exception {
        try (var fixture = new RootedSdkFixture()) {
            fixture.timelines.put("rcp2/source", fixture.blue.timelines().register("rcp2/source", "alice"));
            fixture.timelines.put("rcp2/second", fixture.blue.timelines().register("rcp2/second", "bob"));
            var document = fixture.blue.documents().admitStaticProcessEmbedded(
                    aggregateBeginningYaml(all), ActivationPolicy.fromNow()).document("root");
            assertEquals(Map.of("mode", "FROM_NOW", "lowerExclusiveOrder", Map.of("kind", "BEGINNING")),
                    fixture.control.historyBasis(document.id()).get("admission"));
            var initial = fixture.history(document);
            assertEquals(1, initial.size());
            var first = appendAggregateBeginning(fixture, document, "rcp2/source", "alice", 1L);
            assertEquals(EntryDisposition.APPLIED, fixture.blue.processing().processNext(document).entry(first).disposition());
            var second = appendAggregateBeginning(fixture, document, "rcp2/second", "bob", 2L);
            assertEquals(EntryDisposition.APPLIED, fixture.blue.processing().processNext(document).entry(second).disposition());
            var completed = fixture.history(document);
            assertEquals(3, completed.size());
            assertEquals(initial, completed.subList(0, 1));
            assertEquals(2L, document.snapshot().longAt("/counter"));
            assertEquals(1, fixture.blue.advanced().auditManagedEpochs(document.id()).get(1).emittedEvents().size());
            assertEquals(1, fixture.blue.advanced().auditManagedEpochs(document.id()).get(2).emittedEvents().size());
            CoordinationTestControl.attach(fixture.blue.advanced().rawEngine()).restartFromStores();
            assertTrue(fixture.blue.processing().processNext(document).entries().isEmpty());
            assertEquals(completed, fixture.history(document));
            assertEquals(2L, document.snapshot().longAt("/counter"));
        }
    }

    private static void rejectedAggregateBeginning(boolean all, boolean wrongActor) throws Exception {
        try (var fixture = new RootedSdkFixture()) {
            fixture.blue.timelines().register("rcp2/source", "alice");
            if (wrongActor) fixture.blue.timelines().register("rcp2/second", "carol");
            assertRejectedBeginning(fixture, aggregateBeginningYaml(all),
                    wrongActor ? "REGISTERED_ACTOR_MISMATCH" : "UNKNOWN_PROVIDER");
        }
    }

    private static String aggregateBeginningYaml(boolean all) throws Exception {
        String extra = """
                  second:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: rcp2/second
                    actor:
                      type: MyOS/Principal Actor
                      accountId: bob
                  aggregate:
                    type: Coordination/%s
                """.formatted(all ? "All Timelines Channel" : "Composite Timeline Channel");
        if (!all) extra += "    channels: [owner, second, owner]\n";
        return RootedSdkFixture.resource("source.yaml").replace("contracts:\n", "contracts:\n" + extra)
                .replace("channel: owner", "channel: aggregate");
    }

    private static void assertRejectedBeginning(RootedSdkFixture fixture, String yaml, String reason) {
        var id = DocumentId.of(fixture.blue.values().yaml(yaml).blueId());
        var journal = fixture.blue.advanced().auditTimelineEntries();
        var failure = assertThrows(CoordinationException.class, () -> fixture.blue.documents()
                .admitStaticProcessEmbedded(yaml, ActivationPolicy.fromNow()));
        assertEquals(CoordinationErrorCode.INVALID_ACTIVATION_EVIDENCE, failure.code());
        assertEquals(reason, failure.details().get("reason"));
        assertEquals(journal, fixture.blue.advanced().auditTimelineEntries());
        assertThrows(CoordinationException.class, () -> fixture.blue.documents().require(id));
    }

    private static EntryHandle appendAggregateBeginning(RootedSdkFixture fixture, DocumentHandle target,
            String timeline, String actor, long timestamp) {
        String yaml = """
                type: Coordination/Timeline Entry
                timeline:
                  type: MyOS/MyOS Timeline
                  timelineId: %s
                timestamp: %d
                actor:
                  type: MyOS/Principal Actor
                  accountId: %s
                message:
                  type: Coordination/Operation Request
                  document:
                    blueId: %s
                  requireExactDocumentVersion: false
                  operation: tick
                  channel: aggregate
                  request: {}
                """.formatted(timeline, timestamp, actor, target.snapshot().blueId());
        return fixture.blue.events().from(fixture.timelines.get(timeline))
                .exact(fixture.blue.values().yaml(yaml)).submit();
    }
}
