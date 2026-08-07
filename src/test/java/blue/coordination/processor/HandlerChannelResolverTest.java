package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.HandlerRegistrationContext;
import blue.language.processor.HandlerRegistrationContextFactory;
import blue.language.identity.DirectBlueIdCalculator;
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
        // given
        HandlerRegistrationContext context =
                context(new Node().value("ignored"));

        // when
        String resolved =
                HandlerChannelResolver.resolve(
                        CHANNEL, context);

        // then
        assertEquals(CHANNEL, resolved);
    }

    @Test
    void shouldResolvePureScalarIdentityToExactSameScopeChannelKey() {
        // given
        Node canonicalReference =
                new Node().blueId(
                        DirectBlueIdCalculator.calculateBlueId(
                                new Node().value(CHANNEL)));
        HandlerRegistrationContext context =
                context(canonicalReference);

        // when
        String resolved =
                HandlerChannelResolver.resolve(
                        null, context);

        // then
        assertEquals(CHANNEL, resolved);
    }

    @Test
    void shouldRejectUnknownChannelIdentityWithoutOpeningExecutableBody() {
        // given
        Node unknownReference =
                new Node().blueId(
                        DirectBlueIdCalculator.calculateBlueId(
                                new Node().value("absent-channel")));
        HandlerRegistrationContext context =
                context(unknownReference);

        // when
        String resolved =
                HandlerChannelResolver.resolve(
                        null, context);

        // then
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
                                        DirectBlueIdCalculator.calculateBlueId(
                                                new Node().value(
                                                        "body-must-remain-cold")))));
        return HandlerRegistrationContextFactory.create(
                HANDLER, contracts);
    }
}
