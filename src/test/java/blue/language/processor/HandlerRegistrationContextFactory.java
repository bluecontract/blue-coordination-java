package blue.language.processor;

import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.TypeClassResolver;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test-only factory for immutable Handler registration headers.
 */
public final class HandlerRegistrationContextFactory {
    private HandlerRegistrationContextFactory() {
    }

    public static HandlerRegistrationContext create(
            String handlerKey,
            Map<String, Node> contracts) {
        Map<String, FrozenNode> frozen =
                new LinkedHashMap<String, FrozenNode>();
        Map<String, String> typeBlueIds =
                new LinkedHashMap<String, String>();
        for (Map.Entry<String, Node> entry
                : contracts.entrySet()) {
            frozen.put(
                    entry.getKey(),
                    FrozenNode.fromResolvedNode(
                            entry.getValue()));
            Node type = entry.getValue().getType();
            if (type != null
                    && type.getBlueId() != null) {
                typeBlueIds.put(
                        entry.getKey(),
                        type.getBlueId());
            }
        }
        return new HandlerRegistrationContext(
                "/",
                handlerKey,
                frozen,
                typeBlueIds,
                new NodeToObjectConverter(
                        new TypeClassResolver()));
    }
}
