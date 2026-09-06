package blue.coordination.internal;

import blue.coordination.api.ExactValue;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact definition bodies authenticated by the processing Source resolver. */
final class ProcessingSourceTypeEvidence {
    private ProcessingSourceTypeEvidence() {}

    static List<ExactValue> from(ResolvedSnapshot snapshot) {
        CanonicalTypeIdentityLookup identities = snapshot.canonicalTypeIdentities();
        Map<String, ExactValue> exact = new LinkedHashMap<>();
        Deque<Nodes> pending = new ArrayDeque<>();
        pending.add(new Nodes(snapshot.resolvedRoot(), snapshot.sourceRoot()));
        while (!pending.isEmpty()) {
            Nodes nodes = pending.removeFirst();
            Node resolved = nodes.resolved();
            Node source = nodes.source();
            type(resolved.getType(), source == null ? null : source.getType(), identities, exact, pending);
            type(resolved.getItemType(), source == null ? null : source.getItemType(), identities, exact, pending);
            type(resolved.getKeyType(), source == null ? null : source.getKeyType(), identities, exact, pending);
            type(resolved.getValueType(), source == null ? null : source.getValueType(), identities, exact, pending);
            if (resolved.getProperties() != null) {
                resolved.getProperties().forEach((key, value) -> pending.add(new Nodes(value,
                        source == null || source.getProperties() == null ? null : source.getProperties().get(key))));
            }
            if (resolved.getItems() != null) {
                for (int index = 0; index < resolved.getItems().size(); index++) {
                    Node supplied = source == null || source.getItems() == null
                            || index >= source.getItems().size() ? null : source.getItems().get(index);
                    pending.add(new Nodes(resolved.getItems().get(index), supplied));
                }
            }
        }
        return List.copyOf(exact.values());
    }

    private static void type(Node type, Node source, CanonicalTypeIdentityLookup identities,
                             Map<String, ExactValue> exact, Deque<Nodes> pending) {
        if (type == null || type.isReferenceOnly()) return;
        identities.findCanonicalTypeIdentityEvidence(type, source).ifPresent(evidence -> {
            Node canonical = evidence.canonicalTypeIdentityInput();
            if (canonical != null) {
                ExactValue checked = ExactValue.verified(evidence.blueId(), canonical);
                exact.putIfAbsent(checked.blueId(), checked);
            }
        });
        pending.add(new Nodes(type, source));
    }

    private record Nodes(Node resolved, Node source) {}
}
