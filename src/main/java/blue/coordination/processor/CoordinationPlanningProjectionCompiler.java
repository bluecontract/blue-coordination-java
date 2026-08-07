package blue.coordination.processor;

import blue.coordination.fastpath.AdmittedOccurrence;
import blue.coordination.fastpath.AdmittedProjection;
import blue.coordination.fastpath.FastPathWorkMetrics;
import blue.coordination.fastpath.ProjectionGenerationKey;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePath;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.provider.NodeProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Admission-time compiler from the durable semantic subscription snapshot to
 * its compact event-time planning projection.
 *
 * <p>Scope-chain identities and dependency pointers are produced while the
 * Root is already being admitted/split. They are mandatory: the compiler
 * refuses to hide an event-time tree walk behind a fallback.</p>
 */
public final class CoordinationPlanningProjectionCompiler {
    private final FastPathWorkMetrics metrics;

    public CoordinationPlanningProjectionCompiler(FastPathWorkMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Compiles the complete projection from an exact Root that has already
     * passed engine inventory admission.
     *
     * <p>Every scope-chain identity is calculated once per distinct pointer
     * with one invocation-local bottom-up identity index. Dependency paths
     * are deliberately rooted at {@code /}: the frozen Language dependency
     * evidence exposes exact identities but not their source pointers, so a
     * narrower path would make delta invalidation unsound. This conservative
     * proof may refresh more occurrences, but it can never reuse stale
     * planning evidence.</p>
     */
    public AdmittedProjection compileAdmitted(
            ProjectionGenerationKey generation,
            CoordinationSubscriptionSnapshot snapshot,
            Node exactRoot) {
        return compileAdmitted(
                generation,
                snapshot,
                exactRoot,
                requestedBlueId -> Collections.<Node>emptyList());
    }

    /**
     * Reference-aware admitted compiler using the same exact lookup domain as
     * indexed planning. Provider generation is already part of the supplied
     * {@link ProjectionGenerationKey}; a missing, ambiguous, pure-reference,
     * or identity-mismatched dereference fails the projection build.
     */
    public AdmittedProjection compileAdmitted(
            ProjectionGenerationKey generation,
            CoordinationSubscriptionSnapshot snapshot,
            Node exactRoot,
            NodeProvider exactProvider) {
        ProjectionGenerationKey exactGeneration = Objects.requireNonNull(
                generation, "generation");
        CoordinationSubscriptionSnapshot exactSnapshot = Objects.requireNonNull(
                snapshot, "snapshot");
        requireBinding(exactGeneration, exactSnapshot);
        Node root = Objects.requireNonNull(exactRoot, "exactRoot");
        if (root.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "admitted planning Root must be expanded exact content");
        }
        if (root.getBlueId() != null
                && !exactGeneration.rootBlueId().equals(root.getBlueId())) {
            throw new IllegalArgumentException(
                    "admitted planning Root carries another identity");
        }

        Map<String, List<String>> chains = scopeChains(
                exactGeneration,
                exactSnapshot,
                root,
                Objects.requireNonNull(exactProvider, "exactProvider"));
        Map<String, Collection<String>> dependencyPaths =
                new LinkedHashMap<String, Collection<String>>();
        for (CoordinationSubscriptionOccurrence occurrence
                : exactSnapshot.occurrences()) {
            dependencyPaths.put(
                    occurrence.occurrenceKey(),
                    Collections.singleton(JsonPointer.ROOT));
        }
        return compile(
                exactGeneration,
                exactSnapshot,
                chains,
                dependencyPaths);
    }

    public AdmittedProjection compile(
            ProjectionGenerationKey generation,
            CoordinationSubscriptionSnapshot snapshot,
            Map<String, ? extends Collection<String>> scopeChainsByPath,
            Map<String, ? extends Collection<String>>
                    dependencyPathsByOccurrenceKey) {
        ProjectionGenerationKey exactGeneration = Objects.requireNonNull(
                generation, "generation");
        CoordinationSubscriptionSnapshot exactSnapshot = Objects.requireNonNull(
                snapshot, "snapshot");
        requireBinding(exactGeneration, exactSnapshot);
        Map<String, ? extends Collection<String>> chains = Objects.requireNonNull(
                scopeChainsByPath, "scopeChainsByPath");
        Map<String, ? extends Collection<String>> dependencyPaths =
                Objects.requireNonNull(
                        dependencyPathsByOccurrenceKey,
                        "dependencyPathsByOccurrenceKey");
        List<AdmittedOccurrence> compiled = new ArrayList<AdmittedOccurrence>(
                exactSnapshot.occurrences().size());
        for (CoordinationSubscriptionOccurrence occurrence
                : exactSnapshot.occurrences()) {
            Collection<String> chain = chains.get(occurrence.scopePath());
            if (chain == null) {
                throw new IllegalArgumentException(
                        "admission omitted scope chain for "
                                + occurrence.scopePath());
            }
            Collection<String> paths = dependencyPaths.get(
                    occurrence.occurrenceKey());
            if (paths == null || paths.isEmpty()) {
                throw new IllegalArgumentException(
                        "admission omitted dependency pointers for "
                                + occurrence.occurrenceKey());
            }
            compiled.add(new AdmittedOccurrence(
                    occurrence.occurrenceKey(),
                    occurrence.scopePath(),
                    occurrence.scopeBlueId(),
                    occurrence.channelKey(),
                    occurrence.effectiveTypeBlueId(),
                    occurrence.order(),
                    occurrence.headerIdentityBlueId(),
                    occurrence.checkpointDomainBlueId(),
                    chain,
                    occurrence.sourceContributionNodeBlueIds(),
                    occurrence.dependencyNodeBlueIds(),
                    occurrence.subscriptionKeys(),
                    paths));
        }
        AdmittedProjection result = new AdmittedProjection(
                exactGeneration, compiled);
        metrics.admittedProjectionBuilt(compiled.size());
        return result;
    }

    private static void requireBinding(
            ProjectionGenerationKey generation,
            CoordinationSubscriptionSnapshot snapshot) {
        List<String> errors = new ArrayList<String>();
        if (!generation.rootBlueId().equals(snapshot.rootBlueId())) {
            errors.add("rootBlueId");
        }
        if (generation.rootRevision() != snapshot.rootRevision()) {
            errors.add("rootRevision");
        }
        if (!generation.subscriptionDigest().equals(snapshot.digest())) {
            errors.add("subscriptionDigest");
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "projection generation does not bind snapshot: " + errors);
        }
    }

    private Map<String, List<String>> scopeChains(
            ProjectionGenerationKey generation,
            CoordinationSubscriptionSnapshot snapshot,
            Node root,
            NodeProvider exactProvider) {
        CoordinationExactNodeIndex identities =
                new CoordinationExactNodeIndex();
        Map<String, String> identitiesByPointer =
                new LinkedHashMap<String, String>();
        identitiesByPointer.put(
                JsonPointer.ROOT, generation.rootBlueId());
        Map<String, List<String>> result =
                new LinkedHashMap<String, List<String>>();
        for (CoordinationSubscriptionOccurrence occurrence
                : snapshot.occurrences()) {
            String scopePath = occurrence.scopePath();
            if (result.containsKey(scopePath)) continue;
            List<String> chain = new ArrayList<String>();
            chain.add(generation.rootBlueId());
            List<String> prefix = new ArrayList<String>();
            for (String segment : JsonPointer.split(scopePath)) {
                prefix.add(segment);
                String pointer = JsonPointer.toPointer(prefix);
                String identity = identitiesByPointer.get(pointer);
                if (identity == null) {
                    metrics.scopeTraversed();
                    Node selected = exactNodeAt(
                            root, pointer, exactProvider);
                    identity = exactIdentity(selected, identities);
                    identitiesByPointer.put(pointer, identity);
                }
                chain.add(identity);
            }
            if (!occurrence.scopeBlueId().equals(
                    chain.get(chain.size() - 1))) {
                throw new IllegalArgumentException(
                        "admitted planning scope identity is stale at "
                                + scopePath
                                + ": current="
                                + chain.get(chain.size() - 1)
                                + ", projected="
                                + occurrence.scopeBlueId());
            }
            result.put(
                    scopePath,
                    Collections.unmodifiableList(chain));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Node exactNodeAt(
            Node root,
            String pointer,
            NodeProvider exactProvider) {
        final Object selected;
        try {
            selected = NodePath.get(
                    root,
                    pointer,
                    reference -> reference != null
                            && reference.isReferenceOnly()
                            ? requireExact(
                                    reference.getBlueId(), exactProvider)
                            : reference);
        } catch (ExecutionEvidenceUnavailableException unavailable) {
            throw unavailable;
        }
        if (!(selected instanceof Node)) {
            throw new IllegalArgumentException(
                    "admitted planning scope is not structural at "
                            + pointer);
        }
        return (Node) selected;
    }

    private static Node requireExact(
            String blueId,
            NodeProvider exactProvider) {
        List<Node> candidates = exactProvider.fetchByBlueId(blueId);
        if (candidates.isEmpty()) {
            throw new ExecutionEvidenceUnavailableException(
                    "admitted planning reference is unavailable: "
                            + blueId,
                    Collections.singleton(blueId));
        }
        if (candidates.size() != 1) {
            throw new IllegalArgumentException(
                    "admitted planning reference must resolve exactly once: "
                            + blueId);
        }
        Node supplied = Objects.requireNonNull(
                candidates.get(0), "exact provider node");
        if (supplied.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "admitted planning reference remained unresolved: "
                            + blueId);
        }
        Node canonical = supplied.clone();
        String declared = canonical.getBlueId();
        if (declared != null) {
            if (!blueId.equals(declared)) {
                throw new IllegalArgumentException(
                        "admitted planning provider declared another identity");
            }
            canonical.blueId(null);
        }
        String calculated = DirectBlueIdCalculator.calculateBlueId(canonical);
        if (!blueId.equals(calculated)) {
            throw new IllegalArgumentException(
                    "admitted planning provider returned mismatched content");
        }
        return canonical;
    }

    private static String exactIdentity(
            Node node,
            CoordinationExactNodeIndex identities) {
        if (node.isReferenceOnly()) return node.getBlueId();
        String declared = node.getBlueId();
        if (declared == null) return identities.blueId(node);
        Node canonical = node.clone().blueId(null);
        String calculated = DirectBlueIdCalculator.calculateBlueId(canonical);
        if (!declared.equals(calculated)) {
            throw new IllegalArgumentException(
                    "admitted planning scope carries a mismatched identity");
        }
        return calculated;
    }
}
