package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coordination spec §18.2 excludes ambient host state from semantic input and
 * §22.5 makes provider unavailability a deferral, never a denial. A host
 * provider that throws (database timeout, network error) while PROCESS
 * demands an exact node must therefore behave like a provider that answers
 * "not available now": the entry stays non-terminal and the same entry
 * applies once the provider recovers.
 */
final class SdkThrowingExactNodeProviderTest {
    private static final DocumentId COUNTER = DocumentId.of(
            "sdk-throwing-provider-counter");
    private static final String TIMELINE = "sdk/throwing-provider/alice";

    @Test
    void providerExceptionDefersTheEntryAndTheSameEntryAppliesAfterRecovery() {
        // given
        ExactBlueValue counterValue = providerValue("7");
        FlakyProvider provider = new FlakyProvider(counterValue);
        provider.failing = true;
        try (BlueCoordination blue = BlueCoordination.builder()
                .exactNodeProvider(provider)
                .build()) {
            TimelineHandle timeline = blue.timelines().register(
                    TIMELINE, "alice");
            DocumentHandle counter = blue.documents().admit(
                    ManagedDocument.yaml(COUNTER, counterYaml(
                            counterValue.blueId()))
                            .publicRoot()
                            .fromNow());
            EntryHandle increment = blue.operations()
                    .on(counter)
                    .from(timeline)
                    .call("increment")
                    .through("ownerChannel")
                    .requestYaml("amount: 3")
                    .submit();

            // when
            DrainResult whileFailing = blue.processing().drain();
            long epochWhileFailing = counter.snapshot().epoch();
            provider.failing = false;
            DrainResult afterRecovery = blue.processing().drain();

            // then
            EntryResult deferred = whileFailing.entry(increment);
            assertEquals(EntryDisposition.NEEDS_RESOURCES,
                    deferred.disposition(),
                    "a throwing provider is a temporarily unavailable "
                            + "provider, not invalid content: "
                            + deferred.diagnostic());
            assertEquals(0L, epochWhileFailing,
                    "nothing commits while the provider is failing");
            Optional<EntryResult> applied = afterRecovery.find(increment);
            assertEquals(Optional.of(EntryDisposition.APPLIED),
                    applied.map(EntryResult::disposition),
                    "the same retained entry must apply once the provider "
                            + "answers; a consumed entry can never be retried");
            assertEquals(1L, counter.snapshot().epoch());
            assertEquals(10L, counter.snapshot().longAt("/counterValue"));
            assertTrue(provider.reads > 1,
                    "recovery must re-read the provider for the same entry");
        }
    }

    private static String counterYaml(String counterValueBlueId) {
        return """
                documentId: %s
                counterValue:
                  blueId: %s
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  increment:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      amount: {type: Integer}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /counterValue/value
                              val:
                                $add:
                                  - $document: /counterValue/value
                                  - $binding: event/message/request/amount
                          - $return: true
                """.formatted(COUNTER.value(), counterValueBlueId, TIMELINE);
    }

    private static ExactBlueValue providerValue(String yaml) {
        try (BlueCoordination verifier = BlueCoordination.inMemory()) {
            return verifier.values().providerContentYaml(yaml);
        }
    }

    /** Holds the value but throws like a broken database while failing. */
    private static final class FlakyProvider implements ExactNodeProvider {
        private final ExactBlueValue value;
        private volatile boolean failing;
        private int reads;

        private FlakyProvider(ExactBlueValue value) {
            this.value = value;
        }

        @Override
        public Optional<String> findExactContent(String blueId) {
            return findExactEvidence(blueId)
                    .map(ExactNodeEvidence::exactContent);
        }

        @Override
        public Optional<ExactNodeEvidence> findExactEvidence(String blueId) {
            if (!value.blueId().equals(blueId)) {
                return Optional.empty();
            }
            reads++;
            if (failing) {
                throw new IllegalStateException(
                        "database timeout while reading " + blueId);
            }
            return Optional.of(ExactNodeEvidence.ordinary(value.json()));
        }
    }
}
