package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.HandlerRegistrationContext;
import blue.language.processor.HandlerRegistrationContextFactory;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class HandlerChannelResolverTest {
    private static final String HANDLER = "operation";
    private static final String CHANNEL = "timeline/raw~key";

    @Test
    void shouldPreserveConvertedInlineChannelKey() {
        // Given
        HandlerRegistrationContext context =
                context(new Node().value("ignored"));

        // When
        String resolved =
                HandlerChannelResolver.resolve(
                        CHANNEL, context);

        // Then
        assertEquals(CHANNEL, resolved);
    }

    @Test
    void shouldResolvePureScalarIdentityToExactSameScopeChannelKey() {
        // Given
        Node canonicalReference =
                new Node().blueId(
                        BlueIdCalculator.INSTANCE
                                .calculate(CHANNEL));
        HandlerRegistrationContext context =
                context(canonicalReference);

        // When
        String resolved =
                HandlerChannelResolver.resolve(
                        null, context);

        // Then
        assertEquals(CHANNEL, resolved);
    }

    @Test
    void shouldRejectUnknownChannelIdentityWithoutOpeningExecutableBody() {
        // Given
        Node unknownReference =
                new Node().blueId(
                        BlueIdCalculator.INSTANCE
                                .calculate("absent-channel"));
        HandlerRegistrationContext context =
                context(unknownReference);

        // When
        String resolved =
                HandlerChannelResolver.resolve(
                        null, context);

        // Then
        assertNull(resolved);
    }

    private static HandlerRegistrationContext context(
            Node channel) {
        Map<String, Node> contracts =
                new LinkedHashMap<String, Node>();
        contracts.put(
                CHANNEL,
                new Node());
        contracts.put(
                HANDLER,
                new Node()
                        .properties(
                                "channel",
                                channel)
                        .properties(
                                "steps",
                                new Node().blueId(
                                        BlueIdCalculator.INSTANCE
                                                .calculate(
                                                        "body-must-remain-cold"))));
        return HandlerRegistrationContextFactory.create(
                HANDLER, contracts);
    }
}
