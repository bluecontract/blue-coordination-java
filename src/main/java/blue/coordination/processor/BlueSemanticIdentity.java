package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.BlueIds;
import blue.repo.BlueRepository;
import java.util.LinkedHashMap;
import java.util.Map;

final class BlueSemanticIdentity {
    private static final int IDENTITY_CACHE_SIZE = 1024;
    private static final ThreadLocal<IdentityContext> CONTEXT = new ThreadLocal<IdentityContext>() {
        @Override
        protected IdentityContext initialValue() {
            return new IdentityContext();
        }
    };

    private BlueSemanticIdentity() {
    }

    static boolean equals(Node left, Node right) {
        return left != null && right != null && identity(left).equals(identity(right));
    }

    static boolean equals(Object model, Node node) {
        return model != null && node != null && identity(model).equals(identity(node));
    }

    static boolean matchesType(Node node, Node expectedType) {
        if (node == null || expectedType == null) {
            return false;
        }
        if (node.isReferenceOnly()) {
            identity(node);
            return true;
        }
        return CONTEXT.get().blue.nodeMatchesType(node, expectedType);
    }

    private static String identity(Node node) {
        if (node.isReferenceOnly()) {
            return BlueIds.requireBlueIdOrCyclicMember(node.getBlueId(), "Semantic identity reference");
        }
        Blue blue = CONTEXT.get().blue;
        Object typedValue = blue.nodeToObject(node, Object.class);
        if (typedValue != null && !(typedValue instanceof Node)) {
            return identity(blue.objectToNode(typedValue), blue);
        }
        return blue.calculateSemanticBlueId(node);
    }

    private static String identity(Object model) {
        Blue blue = CONTEXT.get().blue;
        return identity(blue.objectToNode(model), blue);
    }

    private static String identity(Node node, Blue blue) {
        String representationIdentity = BlueIdCalculator.calculateBlueId(node);
        String cached = cachedIdentity(representationIdentity);
        if (cached != null) {
            return cached;
        }
        String calculated = blue.calculateSemanticBlueId(node);
        cacheIdentity(representationIdentity, calculated);
        return calculated;
    }

    private static String cachedIdentity(String representationIdentity) {
        return CONTEXT.get().valueIdentities.get(representationIdentity);
    }

    private static void cacheIdentity(String representationIdentity, String valueIdentity) {
        CONTEXT.get().valueIdentities.put(representationIdentity, valueIdentity);
    }

    private static final class IdentityContext {
        private final Blue blue = BlueRepository.latest().configure(new Blue());
        private final Map<String, String> valueIdentities =
                new LinkedHashMap<String, String>(IDENTITY_CACHE_SIZE, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                        return size() > IDENTITY_CACHE_SIZE;
                    }
                };
    }
}
