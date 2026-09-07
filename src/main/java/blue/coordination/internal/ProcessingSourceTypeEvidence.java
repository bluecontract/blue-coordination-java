package blue.coordination.internal;

import blue.coordination.api.ExactValue;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.runtime.LanguageProcessing;
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
            if (resolved.getContracts() != null) pending.add(new Nodes(resolved.getContracts(),
                    source == null ? null : source.getContracts()));
            if (resolved.getBlue() != null) pending.add(new Nodes(resolved.getBlue(),
                    source == null ? null : source.getBlue()));
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

    /**
     * Exact executable fields are canonicalized before the enclosing snapshot.
     * Retain only authored inline declarations actually replaced by references
     * in that canonical Source. Unchanged cold fields remain untouched.
     */
    static List<ExactValue> fromCanonicalizedSource(
            Node canonical, Node authored, LanguageProcessing.Scope scope) {
        Map<String, ExactValue> exact = new LinkedHashMap<>();
        Deque<Nodes> pending = new ArrayDeque<>();
        pending.add(new Nodes(canonical, authored));
        while (!pending.isEmpty()) {
            Nodes nodes = pending.removeFirst();
            Node value = nodes.resolved();
            Node source = nodes.source();
            if (value == null || source == null) continue;
            retainedType(value.getType(), source.getType(), scope, exact, pending);
            retainedType(value.getItemType(), source.getItemType(), scope, exact, pending);
            retainedType(value.getKeyType(), source.getKeyType(), scope, exact, pending);
            retainedType(value.getValueType(), source.getValueType(), scope, exact, pending);
            pending.add(new Nodes(value.getContracts(), source.getContracts()));
            pending.add(new Nodes(value.getBlue(), source.getBlue()));
            if (value.getProperties() != null && source.getProperties() != null) {
                value.getProperties().forEach((key, child) -> pending.add(
                        new Nodes(child, source.getProperties().get(key))));
            }
            if (value.getItems() != null && source.getItems() != null) {
                for (int index = 0; index < Math.min(value.getItems().size(), source.getItems().size()); index++) {
                    pending.add(new Nodes(value.getItems().get(index), source.getItems().get(index)));
                }
            }
        }
        return List.copyOf(exact.values());
    }

    private static void retainedType(Node canonical, Node authored, LanguageProcessing.Scope scope,
                                     Map<String, ExactValue> exact, Deque<Nodes> pending) {
        if (canonical == null || !canonical.isReferenceOnly()
                || authored == null || authored.isReferenceOnly()) return;
        CanonicalTypeIdentityEvidence evidence = scope.resolveTypeDeclarationIdentity(authored);
        if (!canonical.getBlueId().equals(evidence.blueId())
                || evidence.canonicalTypeIdentityInput() == null) {
            throw new IllegalStateException("Canonical processing Source lost its authored type identity");
        }
        Node body = evidence.canonicalTypeIdentityInput();
        ExactValue checked = ExactValue.verified(canonical.getBlueId(), body);
        exact.putIfAbsent(checked.blueId(), checked);
        pending.add(new Nodes(body, authored));
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
