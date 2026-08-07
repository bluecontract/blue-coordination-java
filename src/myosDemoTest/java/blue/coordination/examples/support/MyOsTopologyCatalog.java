package blue.coordination.examples.support;

import blue.coordination.engine.api.DocumentSessionId;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.ExternalOrderKey;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit logical-document topology with staged cycle checking and inverse
 * edges. Equal Blue content is evidence and never merges logical documents.
 */
public final class MyOsTopologyCatalog {

    public record Resolution(
            DocumentState owningRoot,
            DocumentState selectedDocument,
            String absolutePath,
            List<MyOsTopologyLink> chain) {
        public Resolution {
            Objects.requireNonNull(owningRoot, "owningRoot");
            Objects.requireNonNull(selectedDocument, "selectedDocument");
            absolutePath = JsonPointer.canonicalize(
                    Objects.requireNonNull(absolutePath, "absolutePath"));
            chain = List.copyOf(chain);
        }
    }

    public record DesiredLink(String relativePath, MyOsDocumentIdentity child) {
        public DesiredLink {
            relativePath = JsonPointer.canonicalize(
                    Objects.requireNonNull(relativePath, "relativePath"));
            if (relativePath.isEmpty()) {
                throw new IllegalArgumentException("Root cannot be a child path");
            }
            Objects.requireNonNull(child, "child");
        }
    }

    public record DocumentState(
            String key,
            MyOsDocumentIdentity identity,
            DocumentSessionId sessionId,
            String currentRootBlueId,
            long generation,
            long admissionJournalHighWater,
            ExternalOrderKey committedFrontier) {
        public DocumentState {
            key = requireText(key, "key");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(sessionId, "sessionId");
            currentRootBlueId = requireText(
                    currentRootBlueId, "currentRootBlueId");
            if (generation < 0L || admissionJournalHighWater < 0L) {
                throw new IllegalArgumentException("Negative document position");
            }
            Objects.requireNonNull(committedFrontier, "committedFrontier");
        }

        public DocumentState advance(
                String rootBlueId,
                ExternalOrderKey frontier) {
            ExternalOrderKey checked = Objects.requireNonNull(
                    frontier, "frontier");
            if (checked.compareTo(committedFrontier) <= 0) {
                throw new IllegalArgumentException(
                        "Committed frontier must advance monotonically");
            }
            return new DocumentState(key, identity, sessionId, rootBlueId,
                    Math.addExact(generation, 1L),
                    admissionJournalHighWater, checked);
        }
    }

    private final Map<String, MyOsDocumentIdentity> identityByKey =
            new LinkedHashMap<>();
    private final Map<MyOsDocumentIdentity, DocumentState> states =
            new LinkedHashMap<>();
    private final Map<String, Set<MyOsDocumentIdentity>> identitiesByRoot =
            new LinkedHashMap<>();
    private final Map<MyOsDocumentIdentity, Map<String, MyOsTopologyLink>>
            children = new LinkedHashMap<>();
    private final Map<MyOsDocumentIdentity, Set<MyOsTopologyLink>> parents =
            new LinkedHashMap<>();

    public synchronized void register(DocumentState supplied) {
        DocumentState checked = Objects.requireNonNull(supplied, "supplied");
        registerWithLinks(
                checked, checked.admissionJournalHighWater(), List.of());
    }

    /**
     * Stages all admission validation without publishing the new document.
     * A serialized host can call this before its session-store transaction.
     */
    public synchronized void validateRegistrationWithLinks(
            DocumentState supplied,
            long activationJournalSequence,
            List<DesiredLink> desired) {
        plannedAdmissionLinks(
                supplied, activationJournalSequence, desired);
    }

    /** Validates the entire prospective graph before publishing admission. */
    public synchronized List<MyOsTopologyLink> registerWithLinks(
            DocumentState supplied,
            long activationJournalSequence,
            List<DesiredLink> desired) {
        DocumentState state = Objects.requireNonNull(supplied, "state");
        Map<String, MyOsTopologyLink> replacement = plannedAdmissionLinks(
                state, activationJournalSequence, desired);

        identityByKey.put(state.key(), state.identity());
        states.put(state.identity(), state);
        bindRoot(state.identity().initialDocumentBlueId(), state.identity());
        bindRoot(state.currentRootBlueId(), state.identity());
        children.put(state.identity(), replacement);
        addInverse(replacement.values());
        return List.copyOf(replacement.values());
    }

    public synchronized void advance(
            MyOsDocumentIdentity identity,
            String currentRootBlueId,
            ExternalOrderKey frontier) {
        DocumentState prior = require(identity);
        DocumentState advanced = prior.advance(
                requireText(currentRootBlueId, "currentRootBlueId"),
                Objects.requireNonNull(frontier, "frontier"));
        states.put(identity, advanced);
        if (!prior.currentRootBlueId().equals(
                identity.initialDocumentBlueId())) {
            unbindRoot(prior.currentRootBlueId(), identity);
        }
        bindRoot(currentRootBlueId, identity);
    }

    /** Validates a full replacement without changing either edge direction. */
    public synchronized void validateReconciliation(
            MyOsDocumentIdentity parent,
            long expectedGeneration,
            long activationJournalSequence,
            List<DesiredLink> desired) {
        plannedReconciliation(
                parent,
                expectedGeneration,
                activationJournalSequence,
                desired);
    }

    /** Validates a full replacement before changing either edge direction. */
    public synchronized List<MyOsTopologyLink> reconcile(
            MyOsDocumentIdentity parent,
            long expectedGeneration,
            long activationJournalSequence,
            List<DesiredLink> desired) {
        Map<String, MyOsTopologyLink> replacement = plannedReconciliation(
                parent,
                expectedGeneration,
                activationJournalSequence,
                desired);
        Map<String, MyOsTopologyLink> prior = children.put(
                parent, replacement);
        removeInverse(prior == null ? List.of() : prior.values());
        addInverse(replacement.values());
        return List.copyOf(replacement.values());
    }

    private Map<String, MyOsTopologyLink> plannedReconciliation(
            MyOsDocumentIdentity parent,
            long expectedGeneration,
            long activationJournalSequence,
            List<DesiredLink> desired) {
        DocumentState parentState = require(parent);
        if (parentState.generation() != expectedGeneration) {
            throw new IllegalStateException(
                    "Stale topology reconciliation for " + parent);
        }
        if (activationJournalSequence
                < parentState.admissionJournalHighWater()) {
            throw new IllegalArgumentException(
                    "Topology activation predates document admission");
        }
        Map<String, MyOsTopologyLink> prior = children.getOrDefault(
                parent, Map.of());
        Map<String, MyOsTopologyLink> replacement = links(
                parent, expectedGeneration,
                activationJournalSequence, desired, prior);

        Map<MyOsDocumentIdentity, Map<String, MyOsTopologyLink>> prospective =
                deepCopy(children);
        prospective.put(parent, replacement);
        assertAcyclic(prospective);

        return replacement;
    }

    public synchronized DocumentState state(String key) {
        MyOsDocumentIdentity identity = identityByKey.get(key);
        if (identity == null) throw new IllegalArgumentException("Unknown key");
        return require(identity);
    }

    public synchronized DocumentState state(MyOsDocumentIdentity identity) {
        return require(identity);
    }

    public synchronized MyOsDocumentIdentity requireUniqueRoot(String blueId) {
        Set<MyOsDocumentIdentity> matches = identitiesByRoot.getOrDefault(
                requireText(blueId, "blueId"), Set.of());
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "BlueId is absent or logically ambiguous: "
                            + blueId + " -> " + matches);
        }
        return matches.iterator().next();
    }

    public synchronized List<MyOsTopologyLink> childrenOf(
            MyOsDocumentIdentity parent) {
        return List.copyOf(children.getOrDefault(parent, Map.of()).values());
    }

    public synchronized Set<MyOsTopologyLink> parentsOf(
            MyOsDocumentIdentity child) {
        List<MyOsTopologyLink> ordered = new ArrayList<>(
                parents.getOrDefault(child, Set.of()));
        ordered.sort(MyOsTopologyCatalog::compareLinks);
        return Collections.unmodifiableSet(new LinkedHashSet<>(ordered));
    }

    /** Nearest parent first, with deterministic order inside each level. */
    public synchronized List<MyOsDocumentIdentity> ancestorsOf(
            MyOsDocumentIdentity child) {
        require(child);
        List<MyOsDocumentIdentity> result = new ArrayList<>();
        Set<MyOsDocumentIdentity> visited = new LinkedHashSet<>();
        Deque<MyOsDocumentIdentity> queue = new ArrayDeque<>();
        queue.add(child);
        while (!queue.isEmpty()) {
            MyOsDocumentIdentity current = queue.removeFirst();
            for (MyOsTopologyLink link : parentsOf(current)) {
                if (visited.add(link.parent())) {
                    result.add(link.parent());
                    queue.addLast(link.parent());
                }
            }
        }
        return List.copyOf(result);
    }

    public synchronized MyOsTopologyLink requirePath(
            MyOsDocumentIdentity parent,
            String relativePath) {
        MyOsTopologyLink link = children.getOrDefault(parent, Map.of()).get(
                JsonPointer.canonicalize(relativePath));
        if (link == null) throw new IllegalArgumentException("Unknown link path");
        return link;
    }

    public synchronized Resolution resolve(
            String rootKey,
            String absolutePath) {
        DocumentState root = state(rootKey);
        String canonical = JsonPointer.canonicalize(
                Objects.requireNonNull(absolutePath, "absolutePath"));
        List<String> sought = JsonPointer.split(canonical);
        int consumed = 0;
        MyOsDocumentIdentity current = root.identity();
        List<MyOsTopologyLink> chain = new ArrayList<>();
        while (consumed < sought.size()) {
            MyOsTopologyLink winner = null;
            int winnerLength = -1;
            for (MyOsTopologyLink candidate
                    : children.getOrDefault(current, Map.of()).values()) {
                List<String> candidateSegments = JsonPointer.split(
                        candidate.relativePath());
                if (candidateSegments.size() <= winnerLength
                        || !matches(sought, consumed, candidateSegments)) {
                    continue;
                }
                winner = candidate;
                winnerLength = candidateSegments.size();
            }
            if (winner == null) {
                throw new IllegalArgumentException(
                        "Path crosses no declared managed child at segment "
                                + consumed + ": " + canonical);
            }
            chain.add(winner);
            current = winner.child();
            consumed += winnerLength;
        }
        return new Resolution(root, require(current), canonical, chain);
    }

    public synchronized MyOsTopologyCatalog copy() {
        MyOsTopologyCatalog result = new MyOsTopologyCatalog();
        result.identityByKey.putAll(identityByKey);
        result.states.putAll(states);
        identitiesByRoot.forEach((blueId, identities) ->
                result.identitiesByRoot.put(
                        blueId, new LinkedHashSet<>(identities)));
        children.forEach((identity, links) ->
                result.children.put(identity, new LinkedHashMap<>(links)));
        parents.forEach((identity, links) ->
                result.parents.put(identity, new LinkedHashSet<>(links)));
        return result;
    }

    private DocumentState require(MyOsDocumentIdentity identity) {
        DocumentState state = states.get(Objects.requireNonNull(identity));
        if (state == null) throw new IllegalArgumentException(
                "Unknown logical document " + identity);
        return state;
    }

    private Map<String, MyOsTopologyLink> plannedAdmissionLinks(
            DocumentState supplied,
            long activationJournalSequence,
            List<DesiredLink> desired) {
        DocumentState state = Objects.requireNonNull(supplied, "state");
        if (activationJournalSequence != state.admissionJournalHighWater()) {
            throw new IllegalArgumentException(
                    "Admission links must use the captured journal high-water");
        }
        if (identityByKey.containsKey(state.key())
                || states.containsKey(state.identity())) {
            throw new IllegalArgumentException(
                    "Logical document key or identity is already registered");
        }
        if (states.values().stream().anyMatch(existing ->
                existing.sessionId().equals(state.sessionId()))) {
            throw new IllegalArgumentException("Session is already registered");
        }
        Map<String, MyOsTopologyLink> replacement = links(
                state.identity(), state.generation(),
                activationJournalSequence, desired, Map.of());
        Map<MyOsDocumentIdentity, Map<String, MyOsTopologyLink>> prospective =
                deepCopy(children);
        prospective.put(state.identity(), replacement);
        assertAcyclic(prospective);
        return replacement;
    }

    private Map<String, MyOsTopologyLink> links(
            MyOsDocumentIdentity parent,
            long generation,
            long activation,
            List<DesiredLink> desired,
            Map<String, MyOsTopologyLink> prior) {
        List<DesiredLink> ordered = new ArrayList<>(
                Objects.requireNonNull(desired, "desired"));
        ordered.sort((left, right) -> {
            int path = ExternalOrderKey.compareTextCodePoints(
                    left.relativePath(), right.relativePath());
            return path != 0 ? path : left.child().compareTo(right.child());
        });
        Map<String, MyOsTopologyLink> replacement = new LinkedHashMap<>();
        for (DesiredLink draft : ordered) {
            DesiredLink checked = Objects.requireNonNull(draft, "desired link");
            if (parent.equals(checked.child())) {
                throw new IllegalArgumentException(
                        "Embedded logical-document graph contains a cycle");
            }
            require(checked.child());
            MyOsTopologyLink existing = prior.get(checked.relativePath());
            long effectiveActivation = existing != null
                    && existing.child().equals(checked.child())
                    ? existing.activationJournalSequence()
                    : activation;
            long effectiveGeneration = existing != null
                    && existing.child().equals(checked.child())
                    ? existing.parentGeneration()
                    : generation;
            MyOsTopologyLink link = new MyOsTopologyLink(
                    parent, checked.relativePath(), checked.child(),
                    effectiveActivation, effectiveGeneration);
            if (replacement.putIfAbsent(link.relativePath(), link) != null) {
                throw new IllegalArgumentException(
                        "Duplicate embedded path " + link.relativePath());
            }
        }
        return replacement;
    }

    private void addInverse(Iterable<MyOsTopologyLink> links) {
        for (MyOsTopologyLink link : links) {
            parents.computeIfAbsent(link.child(), ignored ->
                    new LinkedHashSet<>()).add(link);
        }
    }

    private void removeInverse(Iterable<MyOsTopologyLink> links) {
        for (MyOsTopologyLink link : links) {
            Set<MyOsTopologyLink> inverse = parents.get(link.child());
            if (inverse != null) {
                inverse.remove(link);
                if (inverse.isEmpty()) parents.remove(link.child());
            }
        }
    }

    private void bindRoot(String blueId, MyOsDocumentIdentity identity) {
        identitiesByRoot.computeIfAbsent(blueId, ignored ->
                new LinkedHashSet<>()).add(identity);
    }

    private void unbindRoot(String blueId, MyOsDocumentIdentity identity) {
        Set<MyOsDocumentIdentity> matches = identitiesByRoot.get(blueId);
        if (matches == null) return;
        matches.remove(identity);
        if (matches.isEmpty()) identitiesByRoot.remove(blueId);
    }

    private static void assertAcyclic(
            Map<MyOsDocumentIdentity, Map<String, MyOsTopologyLink>> graph) {
        Set<MyOsDocumentIdentity> visiting = new LinkedHashSet<>();
        Set<MyOsDocumentIdentity> visited = new LinkedHashSet<>();
        for (MyOsDocumentIdentity identity : graph.keySet()) {
            visit(identity, graph, visiting, visited);
        }
    }

    private static void visit(
            MyOsDocumentIdentity current,
            Map<MyOsDocumentIdentity, Map<String, MyOsTopologyLink>> graph,
            Set<MyOsDocumentIdentity> visiting,
            Set<MyOsDocumentIdentity> visited) {
        if (visited.contains(current)) return;
        if (!visiting.add(current)) {
            throw new IllegalArgumentException(
                    "Embedded logical-document graph contains a cycle");
        }
        for (MyOsTopologyLink link
                : graph.getOrDefault(current, Map.of()).values()) {
            visit(link.child(), graph, visiting, visited);
        }
        visiting.remove(current);
        visited.add(current);
    }

    private static Map<MyOsDocumentIdentity, Map<String, MyOsTopologyLink>>
            deepCopy(
            Map<MyOsDocumentIdentity, Map<String, MyOsTopologyLink>> source) {
        Map<MyOsDocumentIdentity, Map<String, MyOsTopologyLink>> copy =
                new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(
                key, new LinkedHashMap<>(value)));
        return copy;
    }

    private static boolean matches(
            List<String> source,
            int offset,
            List<String> candidate) {
        if (offset + candidate.size() > source.size()) return false;
        for (int index = 0; index < candidate.size(); index++) {
            if (!source.get(offset + index).equals(candidate.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static int compareLinks(
            MyOsTopologyLink left,
            MyOsTopologyLink right) {
        int parent = left.parent().compareTo(right.parent());
        if (parent != 0) return parent;
        int path = ExternalOrderKey.compareTextCodePoints(
                left.relativePath(), right.relativePath());
        return path != 0 ? path : left.child().compareTo(right.child());
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) throw new IllegalArgumentException(label);
        return checked;
    }
}
