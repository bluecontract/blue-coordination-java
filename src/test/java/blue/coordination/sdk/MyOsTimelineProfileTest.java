package blue.coordination.sdk;

import blue.coordination.sdk.ActivationPolicy;
import blue.coordination.sdk.BlueCoordination;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Public native profile control, independent of the later actual worker acceptance. */
class MyOsTimelineProfileTest {
    @ParameterizedTest
    @ValueSource(strings = {"Coordination/Timeline Channel", "MyOS/MyOS Timeline Channel"})
    void shouldAdmitAndProcessCurrentMyOsPrincipalTimelineChannel(String channelType) throws Exception {
        // given
        try (var owner = BlueCoordination.inMemory()) {
            var timeline = owner.timelines().register("profile-source", "alice");
            String source = """
                    name: Current principal profile
                    counter: 0
                    contracts:
                      source:
                        type: %s
                        timeline: {type: MyOS/MyOS Timeline, timelineId: profile-source}
                        actor: {type: MyOS/Principal Actor, accountId: alice}
                      tick:
                        type: Coordination/Sequential Workflow Operation
                        channel: source
                        request: {}
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $appendChange: {op: replace, path: /counter, val: 1}
                              - $return: true
                    """.formatted(channelType);
            // when
            var admission = owner.documents().admitStaticProcessEmbedded(source, ActivationPolicy.importFullHistory());
            var document = admission.document("root");
            var entry = owner.events().from(timeline).exact(owner.values().yaml("""
                    type: Coordination/Timeline Entry
                    timeline: {type: MyOS/MyOS Timeline, timelineId: profile-source}
                    timestamp: 90
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                    message:
                      type: Coordination/Operation Request
                      document: {blueId: %s}
                      requireExactDocumentVersion: false
                      operation: tick
                      channel: source
                      request: {}
                    """.formatted(document.id().value()))).submit();
            var outcome = owner.processing().selectStage(document, entry.blueId()).execute();
            // then
            assertEquals(1, outcome.resultOwners().size());
            assertEquals(1, document.snapshot().epoch());
            assertEquals(1, document.snapshot().longAt("/counter"));
        }
    }
    @org.junit.jupiter.api.Test
    void currentMyOsChannelRemainsRegisteredAfterColdLogicalRestore() throws Exception {
        String source = RootedSdkFixture.resource("source.yaml")
                .replace("Coordination/Timeline Channel", "MyOS/MyOS Timeline Channel");
        var account = new LogicalInstanceHistoryTest.Account();
        var id = account.transact(scope -> {
            scope.coordination().timelines().register("rcp2/source", "alice");
            return scope.coordination().documents().admitStaticProcessEmbedded(
                    source,
                    ActivationPolicy.importFullHistory()).document("root").id();
        });
        String entry = account.transact(scope -> {
            var root = scope.documentHandle(id).orElseThrow();
            var accepted = account.append(scope, root, "rcp2/source", "owner", "setCounter", "counterValue: 7");
            assertTrue(scope.coordination().processing().processNextStage(root).entry(accepted).applied());
            return accepted.blueId();
        });
        account.transact(scope -> {
            assertEquals(7, scope.documentHandle(id).orElseThrow().snapshot().longAt("/counter"));
            assertTrue(scope.coordination().processing().originalResult(entry).orElseThrow().applied());
            assertEquals(ProcessingStageResult.Disposition.NO_WORK,
                    scope.coordination().processing().processNextStage(scope.documentHandle(id).orElseThrow()).disposition());
            return null;
        });
    }

    @org.junit.jupiter.api.Test
    void configurationAuthenticatesTheFixedRuntimeRegistrationOnColdOpen() {
        var account = new LogicalInstanceHistoryTest.Account();
        var codec = new SdkStorageCodec(new Object(), account.limits.sdk().maximumCodecBytes());
        var original = codec.decode(account.configuration.bytes(), SdkStorageCodec.Configuration.class);
        assertTrue(original.bundledIdentities().containsKey("runtimeRegistration"));
        var identities = new java.util.LinkedHashMap<>(original.bundledIdentities());
        identities.remove("runtimeRegistration");
        var wrong = new RootedCoordinationStorage.Configuration(codec.encode(new SdkStorageCodec.Configuration(
                original.language(), original.contracts(), original.bundledRelease(),
                original.contentDerivedDocumentIds(), original.policy(), identities)));
        try (var attempt = account.records.attempt()) {
            assertThrows(RuntimeException.class, () -> RootedCoordinationStorage.openLogical(account.objects,
                    account.limits, wrong, attempt, ExactNodeProvider.empty()));
            assertThrows(IllegalStateException.class, () -> attempt.prepare("wrong-profile", java.util.List.of(),
                    LogicalInstanceHistoryTest.Account.EVIDENCE));
        }
    }

}
