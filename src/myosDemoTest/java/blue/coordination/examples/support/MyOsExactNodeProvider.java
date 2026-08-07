package blue.coordination.examples.support;

import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.provider.NodeProvider;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JVM-local exact-node provider for authored inputs and current managed views.
 *
 * <p>The example kernel is intentionally shared between test cases. Dynamic
 * documents therefore cannot be copied into the kernel's static Repository.
 * Authored initial identities remain permanent evidence. Dynamic
 * initialization snapshots and managed-child inventories are replaceable,
 * runtime-owned scopes released when the runtime closes. This bounds dynamic
 * state without exposing the global fragment store as a cross-inventory
 * fallback.</p>
 */
final class MyOsExactNodeProvider implements NodeProvider {

    private final Map<String, Node> permanent = new LinkedHashMap<>();
    private final Map<Object, Map<String, Map<String, Node>>> current =
            new IdentityHashMap<>();

    synchronized void register(String blueId, Node exactNode) {
        String identity = requireText(blueId, "blueId");
        Node retained = verified(identity, exactNode);
        requireSame(identity, retained, permanent.get(identity));
        permanent.putIfAbsent(identity, retained);
    }

    /**
     * Atomically replaces every current scope owned by {@code owner}.
     *
     * <p>The complete replacement is cloned, identity-verified, and checked
     * for collisions before the shared lookup surface is mutated. A failed
     * replacement therefore leaves every prior scope visible together.</p>
     */
    synchronized void replaceCurrent(
            Object owner,
            Map<String, Map<String, Node>> scopes) {
        Object checkedOwner = Objects.requireNonNull(owner, "owner");
        Map<String, Map<String, Node>> replacement = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Node>> scope
                : Objects.requireNonNull(scopes, "scopes").entrySet()) {
            String checkedScope = requireText(scope.getKey(), "scope");
            if (replacement.put(
                    checkedScope, verified(scope.getValue())) != null) {
                throw new IllegalArgumentException(
                        "Exact publication repeats scope " + checkedScope);
            }
        }

        Map<String, Node> replacementByBlueId = new LinkedHashMap<>();
        for (Map<String, Node> publication : replacement.values()) {
            for (Map.Entry<String, Node> entry : publication.entrySet()) {
                requireSame(
                        entry.getKey(),
                        entry.getValue(),
                        replacementByBlueId.get(entry.getKey()));
                replacementByBlueId.putIfAbsent(
                        entry.getKey(), entry.getValue());
                requireCurrentCompatible(
                        entry.getKey(), entry.getValue(), checkedOwner);
            }
        }

        if (replacement.isEmpty()) {
            current.remove(checkedOwner);
        } else {
            current.put(checkedOwner, replacement);
        }
    }

    synchronized void release(Object owner) {
        current.remove(Objects.requireNonNull(owner, "owner"));
    }

    @Override
    public synchronized List<Node> fetchByBlueId(String blueId) {
        String identity = requireText(blueId, "blueId");
        Node node = null;
        for (Map<String, Map<String, Node>> byScope
                : current.values()) {
            for (Map<String, Node> publication : byScope.values()) {
                node = publication.get(identity);
                if (node != null) break;
            }
            if (node != null) break;
        }
        if (node == null) node = permanent.get(identity);
        return node == null
                ? Collections.<Node>emptyList()
                : Collections.singletonList(node.clone());
    }

    private void requireCurrentCompatible(
            String blueId,
            Node proposed,
            Object replacedOwner) {
        for (Map.Entry<Object, Map<String, Map<String, Node>>> owner
                : current.entrySet()) {
            if (owner.getKey() == replacedOwner) continue;
            for (Map.Entry<String, Map<String, Node>> scope
                    : owner.getValue().entrySet()) {
                requireSame(
                        blueId,
                        proposed,
                        scope.getValue().get(blueId));
            }
        }
    }

    private static void requireSame(
            String blueId,
            Node proposed,
            Node existing) {
        if (existing != null
                && !Objects.equals(
                        NodeWireForm.get(existing),
                        NodeWireForm.get(proposed))) {
            throw new IllegalStateException(
                    "Exact BlueId is bound to different canonical content: "
                            + blueId);
        }
    }

    private static Map<String, Node> verified(Map<String, Node> source) {
        Map<String, Node> result = new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry : Objects.requireNonNull(
                source, "exactNodes").entrySet()) {
            String blueId = requireText(entry.getKey(), "blueId");
            Node prior = result.put(blueId, verified(
                    blueId, entry.getValue()));
            if (prior != null) {
                throw new IllegalArgumentException(
                        "Exact publication repeats BlueId " + blueId);
            }
        }
        return result;
    }

    private static Node verified(String blueId, Node exactNode) {
        Node retained = Objects.requireNonNull(
                exactNode, "exactNode").clone();
        String actual = DirectBlueIdCalculator.calculateBlueId(
                retained.clone());
        if (!blueId.equals(actual) || retained.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Exact publication has invalid content for " + blueId);
        }
        return retained;
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return checked;
    }
}
