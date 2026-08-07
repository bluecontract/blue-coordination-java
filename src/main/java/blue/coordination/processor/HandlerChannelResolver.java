package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.HandlerRegistrationContext;
import blue.language.identity.DirectBlueIdCalculator;

/**
 * Resolves an immutable Handler channel header without opening an executable
 * body.
 */
final class HandlerChannelResolver {
    private static final String CHANNEL = "channel";

    private HandlerChannelResolver() {
    }

    static String resolve(
            String convertedChannel,
            HandlerRegistrationContext context) {
        String declared = nonBlank(convertedChannel);
        if (declared != null) {
            return declared;
        }

        Node header = context.contractNode(
                context.handlerKey());
        Node channel = header != null
                && header.getProperties() != null
                ? header.getProperties().get(CHANNEL)
                : null;
        if (channel == null) {
            return null;
        }
        Object raw = channel.getRawValue();
        if (raw instanceof String) {
            return nonBlank((String) raw);
        }
        if (!channel.isReferenceOnly()) {
            return null;
        }

        /*
         * Canonical direct-node fragments may leave a scalar header as its
         * exact BlueId. The same-scope catalog is already immutable, so match
         * that identity against its raw channel keys without fetching any
         * executable body or inventing provider evidence.
         */
        for (String candidate : context.contractKeys()) {
            if (channel.getBlueId().equals(
                    DirectBlueIdCalculator.calculateBlueId(
                            new Node().value(candidate)))) {
                return candidate;
            }
        }
        return null;
    }

    private static String nonBlank(String value) {
        if (value == null) {
            return null;
        }
        return value.trim().isEmpty()
                ? null
                : value;
    }
}
