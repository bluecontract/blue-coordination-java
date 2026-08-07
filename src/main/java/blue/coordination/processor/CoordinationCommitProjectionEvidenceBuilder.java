package blue.coordination.processor;

import blue.coordination.engine.fastpath.VerifiedHybridResultFrontier;
import blue.coordination.fastpath.DeltaProjectionApplier;
import blue.coordination.processor.fragmentation.EffectiveCutCatalogReader;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.EffectiveContractSnapshot;
import blue.language.processor.EffectiveContractSnapshotConstants;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.processor.ExternalChannelDependencySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Produces complete commit-local evidence from a verified hybrid PROCESS
 * frontier. The ordinary path retains unchanged membership; a deliberately
 * narrow catalog-backed path accepts one proved Process Embedded append and
 * its authoritative companion membership transition.
 *
 * <p>This producer is intentionally conservative.  It retains semantic
 * subscription and topology evidence only when the hybrid result proves all
 * of the following: retained references stayed at their exact prior paths;
 * every expanded node kept the same payload shape; and every contracts,
 * declared-type and other semantic metadata subtree kept the same identity.
 * Scalar business values may change.  Scope identities are then recalculated
 * once per active scope and only occurrences whose scope changed are
 * refreshed.  Any broader mutation uses the typed cold projector.</p>
 */
public final class CoordinationCommitProjectionEvidenceBuilder {

    public CoordinationCommitProjectionEvidence build(
            CoordinationSubscriptionSnapshot previous,
            VerifiedHybridResultFrontier frontier,
            Node exactPriorRoot,
            Node exactResultingRoot,
            String resultingRootBlueId,
            long resultingRootRevision,
            ExternalOrderKey transitionOrderKey,
            SubscriptionDelta membershipDelta) {
        return build(
                previous,
                frontier,
                exactPriorRoot,
                exactResultingRoot,
                resultingRootBlueId,
                resultingRootRevision,
                transitionOrderKey,
                membershipDelta,
                null);
    }

    /**
     * Builds incremental commit evidence while reusing the one exact public
     * effective catalog already established for a semantic membership or
     * Process Embedded topology change.
     *
     * <p>The frozen platform companion remains authoritative for interval
     * membership.  The catalog contributes only immutable Coordination
     * persistence metadata: exact headers, selected-scope provenance and
     * embedded routes.  No subscription function is executed here.</p>
     */
    public CoordinationCommitProjectionEvidence build(
            CoordinationSubscriptionSnapshot previous,
            VerifiedHybridResultFrontier frontier,
            Node exactPriorRoot,
            Node exactResultingRoot,
            String resultingRootBlueId,
            long resultingRootRevision,
            ExternalOrderKey transitionOrderKey,
            SubscriptionDelta membershipDelta,
            EffectiveFragmentationCatalog fragmentationCatalog) {
        CoordinationSubscriptionSnapshot prior = Objects.requireNonNull(
                previous, "previous");
        VerifiedHybridResultFrontier proof = Objects.requireNonNull(
                frontier, "frontier");
        Node oldRoot = Objects.requireNonNull(
                exactPriorRoot, "exactPriorRoot");
        Node newRoot = Objects.requireNonNull(
                exactResultingRoot, "exactResultingRoot");
        String newRootBlueId = requireText(
                resultingRootBlueId, "resultingRootBlueId");
        ExternalOrderKey order = Objects.requireNonNull(
                transitionOrderKey, "transitionOrderKey");
        SubscriptionDelta delta = Objects.requireNonNull(
                membershipDelta, "membershipDelta");

        if (!prior.rootBlueId().equals(proof.priorRootBlueId())
                || !proof.bindsPriorRoot(oldRoot)
                || !proof.bindsResultRoot(newRoot)) {
            throw cold("hybrid frontier belongs to another Root binding");
        }
        if (!proof.retainedBindingsRemainExact(newRoot)) {
            throw cold("retained reference binding changed after proof");
        }
        if (resultingRootRevision != prior.rootRevision() + 1L) {
            throw new IllegalArgumentException(
                    "resulting revision must be the exact successor");
        }
        if (order.compareTo(prior.activationFrontier()) <= 0) {
            throw new IllegalArgumentException(
                    "transition order must advance the prior frontier");
        }

        boolean needsCatalog = !delta.isEmpty()
                || !proof.processEmbeddedBoundaryBlueIdByPath().isEmpty();
        if (needsCatalog && fragmentationCatalog == null) {
            throw cold("semantic membership/topology change has no exact "
                    + "fragmentation catalog");
        }
        CatalogEvidence catalogEvidence = needsCatalog
                ? CatalogEvidence.from(
                        fragmentationCatalog, newRoot, newRootBlueId)
                : null;
        Set<String> newScopeRoots = catalogEvidence == null
                ? Collections.<String>emptySet()
                : validateTopologyAndNewScopes(
                        prior,
                        proof,
                        oldRoot,
                        newRoot,
                        catalogEvidence);
        MembershipChange membership = validateMembershipChange(
                prior, delta, newScopeRoots, catalogEvidence);

        IdentityHashMap<Node, String> identities =
                new IdentityHashMap<Node, String>();
        for (String path : proof.expandedPaths()) {
            if (isWithinAny(path, newScopeRoots)) {
                continue;
            }
            Node oldNode = proof.priorNodeAt(path);
            Node newNode = proof.resultingNodeAt(newRoot, path);
            if (oldNode == null || newNode == null) {
                throw cold("expanded result changes payload topology at "
                        + path);
            }
            if (!samePayloadShape(
                    oldNode, newNode, path, proof, newScopeRoots)) {
                throw cold("expanded result changes payload shape at "
                        + path);
            }
            if (!sameSemanticMetadata(
                    oldNode, newNode, path, proof, identities)) {
                throw cold("expanded result changes semantic metadata at "
                        + path);
            }
        }

        Map<String, String> resultingScopeBlueIds =
                new LinkedHashMap<String, String>();
        List<CoordinationSubscriptionOccurrence> currentEvidence =
                new ArrayList<CoordinationSubscriptionOccurrence>();
        Set<String> affected = new LinkedHashSet<String>();
        for (CoordinationSubscriptionOccurrence occurrence
                : prior.occurrences()) {
            if (membership.removedInternalKeys.contains(
                    internalKey(occurrence))) {
                continue;
            }
            String scopeBlueId = resultingScopeBlueIds.get(
                    occurrence.scopePath());
            if (scopeBlueId == null) {
                scopeBlueId = exactScopeBlueId(
                        newRoot,
                        newRootBlueId,
                        occurrence.scopePath(),
                        identities);
                resultingScopeBlueIds.put(
                        occurrence.scopePath(), scopeBlueId);
            }
            if (!occurrence.scopeBlueId().equals(scopeBlueId)) {
                affected.add(occurrence.occurrenceKey());
                currentEvidence.add(
                        occurrence.withScopeBlueId(scopeBlueId));
            }
        }
        if (catalogEvidence != null) {
            for (SubscriptionDelta.Entry addition : delta.added()) {
                currentEvidence.add(catalogEvidence.occurrence(
                        addition,
                        newRoot,
                        newRootBlueId));
            }
        }

        return new CoordinationCommitProjectionEvidence(
                newRootBlueId,
                resultingRootRevision,
                order,
                delta,
                currentEvidence,
                affected,
                catalogEvidence == null
                        ? prior.processEmbeddedRoutes()
                        : catalogEvidence.processEmbeddedRoutes,
                catalogEvidence == null
                        ? prior.prunedScopePaths()
                        : catalogEvidence.prunedScopePaths,
                catalogEvidence == null
                        ? null
                        : catalogEvidence.catalog,
                true);
    }

    private static boolean samePayloadShape(
            Node oldNode,
            Node newNode,
            String path,
            VerifiedHybridResultFrontier proof,
            Set<String> newScopeRoots) {
        if ((oldNode.getItems() == null) != (newNode.getItems() == null)
                || (oldNode.getProperties() == null)
                        != (newNode.getProperties() == null)) {
            return false;
        }
        if (oldNode.getItems() != null
                && oldNode.getItems().size()
                        != newNode.getItems().size()) {
            return false;
        }
        if (oldNode.getProperties() == null) return true;
        if (oldNode.getProperties().keySet().equals(
                newNode.getProperties().keySet())) {
            return true;
        }
        Set<String> allowedAdditions = new LinkedHashSet<String>();
        String checkpointPath = append(path, "checkpoint");
        if (proof.newRuntimeBoundaryBlueIdByPath().containsKey(
                checkpointPath)
                && !oldNode.getProperties().containsKey("checkpoint")
                && newNode.getProperties().containsKey("checkpoint")) {
            allowedAdditions.add("checkpoint");
        }
        for (String newScope : newScopeRoots) {
            if (path.equals(parentPath(newScope))) {
                List<String> segments = JsonPointer.split(newScope);
                allowedAdditions.add(segments.get(segments.size() - 1));
            }
        }
        if (allowedAdditions.isEmpty()) return false;
        Set<String> withoutAllowedAdditions = new LinkedHashSet<String>(
                newNode.getProperties().keySet());
        withoutAllowedAdditions.removeAll(allowedAdditions);
        if (!oldNode.getProperties().keySet().equals(
                withoutAllowedAdditions)) {
            return false;
        }
        for (String addition : allowedAdditions) {
            if (oldNode.getProperties().containsKey(addition)
                    || !newNode.getProperties().containsKey(addition)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameSemanticMetadata(
            Node oldNode,
            Node newNode,
            String path,
            VerifiedHybridResultFrontier proof,
            IdentityHashMap<Node, String> identities) {
        if (!Objects.equals(oldNode.getName(), newNode.getName())
                || !Objects.equals(
                        oldNode.getDescription(), newNode.getDescription())
                || !Objects.equals(
                        oldNode.getBlueId(), newNode.getBlueId())
                || !Objects.equals(
                        oldNode.getMergePolicy(), newNode.getMergePolicy())
                || !Objects.equals(
                        oldNode.getPreviousBlueId(),
                        newNode.getPreviousBlueId())
                || !Objects.equals(
                        oldNode.getPosition(), newNode.getPosition())
                || oldNode.isInlineValue() != newNode.isInlineValue()
                || oldNode.isPreprocessingTransformationConfiguration()
                        != newNode
                        .isPreprocessingTransformationConfiguration()) {
            return false;
        }
        if (oldNode.getSchema() != newNode.getSchema()
                && (oldNode.getSchema() != null
                        || newNode.getSchema() != null)) {
            // Schema is mutable and has no public canonical identity value.
            // An expanded schema-bearing node therefore requires the cold
            // semantic path instead of an equality guess.
            return false;
        }
        boolean contractsEquivalent = hasSemanticContractsBoundary(
                proof, path)
                ? oldNode.getContracts() != null
                        && newNode.getContracts() != null
                : sameNodeIdentity(
                        oldNode.getContracts(),
                        newNode.getContracts(),
                        identities);
        return sameNodeIdentity(
                        oldNode.getType(), newNode.getType(), identities)
                && sameNodeIdentity(
                        oldNode.getItemType(),
                        newNode.getItemType(),
                        identities)
                && sameNodeIdentity(
                        oldNode.getKeyType(),
                        newNode.getKeyType(),
                        identities)
                && sameNodeIdentity(
                        oldNode.getValueType(),
                        newNode.getValueType(),
                        identities)
                && contractsEquivalent
                && sameNodeIdentity(
                        oldNode.getBlue(), newNode.getBlue(), identities);
    }

    private static boolean hasSemanticContractsBoundary(
            VerifiedHybridResultFrontier proof, String scopePath) {
        String contractsPath = append(scopePath, "$contracts");
        if (proof.newRuntimeBoundaryBlueIdByPath().containsKey(
                append(contractsPath, "checkpoint"))) {
            return true;
        }
        for (String boundary
                : proof.processEmbeddedBoundaryBlueIdByPath().keySet()) {
            if (contractsPath.equals(parentPath(boundary))) return true;
        }
        return false;
    }

    private static Set<String> validateTopologyAndNewScopes(
            CoordinationSubscriptionSnapshot prior,
            VerifiedHybridResultFrontier proof,
            Node oldRoot,
            Node newRoot,
            CatalogEvidence catalog) {
        Map<String, List<String>> previousRoutes =
                prior.processEmbeddedRoutes();
        Set<String> newScopeRoots = new LinkedHashSet<String>();
        for (String boundary
                : proof.processEmbeddedBoundaryBlueIdByPath().keySet()) {
            List<String> previous = previousRoutes.get(boundary);
            List<String> current = catalog.processEmbeddedRoutes.get(
                    boundary);
            if (previous == null || current == null) {
                throw cold("Process Embedded boundary is absent from exact "
                        + "topology evidence at " + boundary);
            }
            Node oldDeclaration = structuralNodeAt(oldRoot, boundary);
            Node newDeclaration = structuralNodeAt(newRoot, boundary);
            String appended = appendedExplicitPath(
                    oldDeclaration, newDeclaration);
            String declaringScope = declaringScopePath(boundary);
            String absolute = PointerUtils.resolvePointer(
                    declaringScope, appended);
            Set<String> expected = new LinkedHashSet<String>(previous);
            if (!expected.add(absolute)
                    || current.size() != expected.size()
                    || !expected.equals(
                            new LinkedHashSet<String>(current))
                    || structuralNodeAt(oldRoot, absolute) != null
                    || structuralNodeAt(newRoot, absolute) == null) {
                throw cold("Process Embedded append disagrees with the exact "
                        + "resulting catalog at " + boundary);
            }
            ScopeProvenance provenance = catalog.provenanceByScope.get(
                    absolute);
            if (provenance == null
                    || provenance.origin
                            != CoordinationSubscriptionOccurrence.Origin.EXPLICIT
                    || !declaringScope.equals(
                            provenance.declaringScopePath)
                    || !appended.equals(
                            provenance.explicitDeclarationPath)) {
                throw cold("Process Embedded append lacks exact explicit "
                        + "scope provenance at " + absolute);
            }
            newScopeRoots.add(absolute);
        }
        Set<String> minimalNewScopeRoots = minimalPaths(newScopeRoots);

        for (Map.Entry<String, List<String>> previous
                : previousRoutes.entrySet()) {
            List<String> current = catalog.processEmbeddedRoutes.get(
                    previous.getKey());
            if (current == null) {
                throw cold("existing Process Embedded route disappeared at "
                        + previous.getKey());
            }
            if (!proof.processEmbeddedBoundaryBlueIdByPath().containsKey(
                    previous.getKey())
                    && !previous.getValue().equals(current)) {
                throw cold("Process Embedded topology changed without an "
                        + "exact boundary at " + previous.getKey());
            }
        }
        for (String route : catalog.processEmbeddedRoutes.keySet()) {
            if (!previousRoutes.containsKey(route)
                    && !isContractWithinAnyScope(
                            route, minimalNewScopeRoots)) {
                throw cold("new Process Embedded route is outside a proved "
                        + "new scope at " + route);
            }
        }

        if (!catalog.prunedScopePaths.containsAll(
                prior.prunedScopePaths())) {
            throw cold("a previously pruned scope became active");
        }
        for (String pruned : catalog.prunedScopePaths) {
            if (!prior.prunedScopePaths().contains(pruned)
                    && !isWithinAny(pruned, minimalNewScopeRoots)) {
                throw cold("scope pruning changed outside a proved new "
                        + "scope at " + pruned);
            }
        }
        return minimalNewScopeRoots;
    }

    private static MembershipChange validateMembershipChange(
            CoordinationSubscriptionSnapshot prior,
            SubscriptionDelta delta,
            Set<String> newScopeRoots,
            CatalogEvidence catalog) {
        Map<String, CoordinationSubscriptionOccurrence> previous =
                new LinkedHashMap<String, CoordinationSubscriptionOccurrence>();
        for (CoordinationSubscriptionOccurrence occurrence
                : prior.occurrences()) {
            previous.put(internalKey(occurrence), occurrence);
        }
        Set<String> removed = new LinkedHashSet<String>();
        for (SubscriptionDelta.Entry retirement : delta.removed()) {
            String key = internalKey(retirement);
            if (!previous.containsKey(key) || !removed.add(key)) {
                throw cold("membership delta retires an unknown occurrence at "
                        + retirement.scopePath() + "/"
                        + retirement.channelKey());
            }
        }
        Set<String> added = new LinkedHashSet<String>();
        for (SubscriptionDelta.Entry activation : delta.added()) {
            String key = internalKey(activation);
            if (!added.add(key)) {
                throw cold("membership delta repeats an activation at "
                        + activation.scopePath() + "/"
                        + activation.channelKey());
            }
            boolean replacement = removed.contains(key);
            if (!replacement
                    && !isWithinAny(
                            activation.scopePath(), newScopeRoots)) {
                throw cold("new subscription occurrence is outside a proved "
                        + "Process Embedded scope at "
                        + activation.scopePath() + "/"
                        + activation.channelKey());
            }
            if (catalog == null) {
                throw cold("new subscription occurrence has no exact "
                        + "catalog evidence");
            }
            catalog.requireExternalContract(activation);
        }
        for (String key : removed) {
            if (!added.contains(key)) {
                throw cold("retirement-only membership changes require the "
                        + "authoritative cold projector");
            }
        }
        return new MembershipChange(removed);
    }

    private static String appendedExplicitPath(
            Node prior, Node result) {
        if (prior == null || result == null
                || prior.getProperties() == null
                || result.getProperties() == null) {
            throw cold("Process Embedded boundary is not materialized");
        }
        Node oldPaths = prior.getProperties().get("paths");
        Node newPaths = result.getProperties().get("paths");
        if (oldPaths == null || newPaths == null
                || oldPaths.getItems() == null
                || newPaths.getItems() == null
                || newPaths.getItems().size()
                        != oldPaths.getItems().size() + 1) {
            throw cold("Process Embedded boundary is not one path append");
        }
        Node appended = newPaths.getItems().get(
                newPaths.getItems().size() - 1);
        if (!(appended.getValue() instanceof String)) {
            throw cold("Process Embedded appended path is not Text");
        }
        return (String) appended.getValue();
    }

    private static String declaringScopePath(String contractPath) {
        String contracts = parentPath(contractPath);
        if (!"$contracts".equals(lastSegment(contracts))) {
            throw cold("runtime boundary is not a direct contract entry at "
                    + contractPath);
        }
        return parentPath(contracts);
    }

    private static Set<String> minimalPaths(Set<String> paths) {
        List<String> ordered = new ArrayList<String>(paths);
        Collections.sort(ordered, (left, right) -> {
            int depth = Integer.compare(
                    JsonPointer.split(left).size(),
                    JsonPointer.split(right).size());
            return depth != 0
                    ? depth
                    : ExternalOrderKey.compareTextCodePoints(left, right);
        });
        Set<String> result = new LinkedHashSet<String>();
        for (String path : ordered) {
            if (!isWithinAny(path, result)) result.add(path);
        }
        return Collections.unmodifiableSet(result);
    }

    private static boolean isWithinAny(
            String path, Set<String> ancestors) {
        for (String ancestor : ancestors) {
            if (path.equals(ancestor)
                    || ("/".equals(ancestor)
                            ? path.startsWith("/")
                            : path.startsWith(ancestor + "/"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isContractWithinAnyScope(
            String contractPath, Set<String> scopeRoots) {
        for (String scope : scopeRoots) {
            if (contractPath.startsWith(
                    append(scope, "$contracts") + "/")
                    || contractPath.startsWith(scope + "/")) {
                return true;
            }
        }
        return false;
    }

    private static String internalKey(
            CoordinationSubscriptionOccurrence occurrence) {
        return occurrence.scopePath() + "\u001f" + occurrence.channelKey();
    }

    private static String internalKey(SubscriptionDelta.Entry entry) {
        return entry.scopePath() + "\u001f" + entry.channelKey();
    }

    private static String parentPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }

    private static String lastSegment(String path) {
        List<String> segments = JsonPointer.split(path);
        return segments.isEmpty() ? "" : segments.get(segments.size() - 1);
    }

    private static Node structuralNodeAt(Node root, String pointer) {
        Node current = root;
        for (String segment : JsonPointer.split(pointer)) {
            if (current == null || current.isReferenceOnly()) return null;
            if ("$type".equals(segment)) {
                current = current.getType();
            } else if ("$itemType".equals(segment)) {
                current = current.getItemType();
            } else if ("$keyType".equals(segment)) {
                current = current.getKeyType();
            } else if ("$valueType".equals(segment)) {
                current = current.getValueType();
            } else if ("$contracts".equals(segment)) {
                current = current.getContracts();
            } else if ("$blue".equals(segment)) {
                current = current.getBlue();
            } else if (JsonPointer.isArrayIndexSegment(segment)
                    && current.getItems() != null) {
                int index = Integer.parseInt(segment);
                current = index < current.getItems().size()
                        ? current.getItems().get(index)
                        : null;
            } else {
                current = current.getProperties() == null
                        ? null
                        : current.getProperties().get(segment);
            }
        }
        return current;
    }

    private static final class MembershipChange {
        private final Set<String> removedInternalKeys;

        private MembershipChange(Set<String> removedInternalKeys) {
            this.removedInternalKeys = Collections.unmodifiableSet(
                    new LinkedHashSet<String>(removedInternalKeys));
        }
    }

    private static final class ScopeProvenance {
        private final String declaringScopePath;
        private final CoordinationSubscriptionOccurrence.Origin origin;
        private final String explicitDeclarationPath;
        private final String collectionDeclarationPath;
        private final String collectionMemberKey;

        private ScopeProvenance(
                String declaringScopePath,
                CoordinationSubscriptionOccurrence.Origin origin,
                String explicitDeclarationPath,
                String collectionDeclarationPath,
                String collectionMemberKey) {
            this.declaringScopePath = declaringScopePath;
            this.origin = origin;
            this.explicitDeclarationPath = explicitDeclarationPath;
            this.collectionDeclarationPath = collectionDeclarationPath;
            this.collectionMemberKey = collectionMemberKey;
        }

        private static ScopeProvenance root() {
            return new ScopeProvenance(
                    "/",
                    CoordinationSubscriptionOccurrence.Origin.ROOT,
                    null,
                    null,
                    null);
        }
    }

    private static final class CatalogEvidence {
        private final EffectiveFragmentationCatalog catalog;
        private final Map<String, EffectiveContractSnapshot> contracts;
        private final Map<String, ScopeProvenance> provenanceByScope;
        private final Map<String, List<String>> processEmbeddedRoutes;
        private final Set<String> prunedScopePaths;

        private CatalogEvidence(
                EffectiveFragmentationCatalog catalog,
                Map<String, EffectiveContractSnapshot> contracts,
                Map<String, ScopeProvenance> provenanceByScope,
                Map<String, List<String>> processEmbeddedRoutes,
                Set<String> prunedScopePaths) {
            this.catalog = catalog;
            this.contracts = contracts;
            this.provenanceByScope = provenanceByScope;
            this.processEmbeddedRoutes = processEmbeddedRoutes;
            this.prunedScopePaths = prunedScopePaths;
        }

        private static CatalogEvidence from(
                EffectiveFragmentationCatalog supplied,
                Node exactResultingRoot,
                String resultingRootBlueId) {
            EffectiveFragmentationCatalog catalog = Objects.requireNonNull(
                    supplied, "fragmentationCatalog");
            if (!resultingRootBlueId.equals(catalog.rootBlueId())) {
                throw new IllegalArgumentException(
                        "fragmentation catalog does not bind the resulting "
                                + "Root");
            }
            Map<String, EffectiveContractSnapshot> contracts =
                    new LinkedHashMap<String, EffectiveContractSnapshot>();
            for (Map.Entry<String, List<EffectiveContractSnapshot>> scope
                    : catalog.effectiveContractsByScope().entrySet()) {
                for (EffectiveContractSnapshot contract : scope.getValue()) {
                    String key = contract.scopePath() + "\u001f"
                            + contract.key();
                    if (!scope.getKey().equals(contract.scopePath())
                            || contracts.put(key, contract) != null) {
                        throw new IllegalArgumentException(
                                "fragmentation catalog contains a duplicate "
                                        + "or misbound contract at " + key);
                    }
                }
            }

            Map<String, ScopeProvenance> provenance =
                    new LinkedHashMap<String, ScopeProvenance>();
            provenance.put("/", ScopeProvenance.root());
            List<EffectiveCutCatalogReader.ScopePlan> plans =
                    EffectiveCutCatalogReader.read(catalog);
            for (EffectiveCutCatalogReader.ScopePlan plan : plans) {
                for (EffectiveCutCatalogReader.EmbeddedOccurrence occurrence
                        : plan.occurrences()) {
                    CoordinationSubscriptionOccurrence.Origin origin =
                            occurrence.origin()
                                    == blue.language.processor
                                            .EmbeddedScopePlanView.Origin.EXPLICIT
                                    ? CoordinationSubscriptionOccurrence
                                            .Origin.EXPLICIT
                                    : CoordinationSubscriptionOccurrence
                                            .Origin.COLLECTION_MEMBER;
                    ScopeProvenance previous = provenance.put(
                            occurrence.concretePath(),
                            new ScopeProvenance(
                                    occurrence.declaringScopePath(),
                                    origin,
                                    occurrence.explicitDeclarationPath(),
                                    occurrence.collectionDeclarationPath(),
                                    occurrence.collectionMemberKey()));
                    if (previous != null) {
                        throw new IllegalArgumentException(
                                "fragmentation catalog repeats scope "
                                        + occurrence.concretePath());
                    }
                }
            }

            Set<String> pruned = prunedScopes(
                    exactResultingRoot, plans);
            Map<String, List<String>> routes = routes(
                    catalog, plans, pruned);
            return new CatalogEvidence(
                    catalog,
                    Collections.unmodifiableMap(contracts),
                    Collections.unmodifiableMap(provenance),
                    routes,
                    pruned);
        }

        private EffectiveContractSnapshot requireExternalContract(
                SubscriptionDelta.Entry entry) {
            EffectiveContractSnapshot contract = contracts.get(
                    internalKey(entry));
            if (contract == null
                    || !EffectiveContractSnapshotConstants.Role
                            .EXTERNAL_CHANNEL.equals(contract.role())
                    || !entry.effectiveTypeBlueId().equals(
                            contract.effectiveTypeBlueId())
                    || entry.order() != contract.order()
                    || !entry.sourceContributionNodeBlueIds().equals(
                            contract.sourceContributionNodeBlueIds())) {
                throw cold("membership activation is absent from the exact "
                        + "effective catalog at " + entry.scopePath() + "/"
                        + entry.channelKey());
            }
            return contract;
        }

        private CoordinationSubscriptionOccurrence occurrence(
                SubscriptionDelta.Entry entry,
                Node exactResultingRoot,
                String resultingRootBlueId) {
            EffectiveContractSnapshot contract = requireExternalContract(entry);
            ScopeProvenance provenance = provenanceByScope.get(
                    entry.scopePath());
            if (provenance == null) {
                throw cold("membership activation has no exact scope "
                        + "provenance at " + entry.scopePath());
            }
            ExternalChannelDependencySnapshot.ChannelEntry header =
                    exactHeader(entry, contract);
            Map<String, String> headerFields =
                    new LinkedHashMap<String, String>();
            List<String> names = new ArrayList<String>(
                    contract.headerFields().keySet());
            Collections.sort(
                    names, ExternalOrderKey::compareTextCodePoints);
            for (String name : names) {
                FrozenNode value = contract.headerFields().get(name);
                headerFields.put(name, value.blueId());
            }
            String scopeBlueId = exactScopeBlueId(
                    exactResultingRoot,
                    resultingRootBlueId,
                    entry.scopePath(),
                    new IdentityHashMap<Node, String>());
            return new CoordinationSubscriptionOccurrence(
                    entry.scopePath(),
                    scopeBlueId,
                    provenance.declaringScopePath,
                    provenance.origin,
                    provenance.explicitDeclarationPath,
                    provenance.collectionDeclarationPath,
                    provenance.collectionMemberKey,
                    entry.channelKey(),
                    entry.sourceContributionNodeBlueIds(),
                    entry.effectiveTypeBlueId(),
                    entry.order(),
                    entry.checkpointDomainBlueId(),
                    header.headerIdentityBlueId(),
                    headerFields,
                    entry.subscriptionKeys(),
                    entry.activationRootRevision(),
                    entry.startAfterExternalOrderKey(),
                    entry.endAtRootRevision(),
                    entry.dependencies());
        }

        private static ExternalChannelDependencySnapshot.ChannelEntry
        exactHeader(
                SubscriptionDelta.Entry entry,
                EffectiveContractSnapshot contract) {
            ExternalChannelDependencySnapshot.ChannelEntry result = null;
            for (ExternalChannelDependencySnapshot.ChannelEntry candidate
                    : entry.dependencies().channelEntries()) {
                if (!entry.channelKey().equals(candidate.channelKey())) {
                    continue;
                }
                if (result != null
                        || !candidate.externalSource()
                        || candidate.order() != entry.order()
                        || !candidate.effectiveTypeBlueId().equals(
                                entry.effectiveTypeBlueId())
                        || !candidate.sourceContributionNodeBlueIds().equals(
                                entry.sourceContributionNodeBlueIds())
                        || !candidate.deterministicDependencyNodeBlueIds()
                                .equals(contract
                                        .deterministicDependencyNodeBlueIds())) {
                    throw cold("membership activation has inconsistent exact "
                            + "Channel header evidence at "
                            + entry.scopePath() + "/" + entry.channelKey());
                }
                result = candidate;
            }
            if (result == null) {
                throw cold("membership activation omits exact Channel header "
                        + "evidence at " + entry.scopePath() + "/"
                        + entry.channelKey());
            }
            return result;
        }

        private static Set<String> prunedScopes(
                Node exactRoot,
                List<EffectiveCutCatalogReader.ScopePlan> plans) {
            Set<String> pruned = new LinkedHashSet<String>();
            for (EffectiveCutCatalogReader.ScopePlan plan : plans) {
                String scope = plan.scopePath();
                if (isWithinAny(scope, pruned)) continue;
                Node selected = structuralNodeAt(exactRoot, scope);
                if (directTerminated(selected)) pruned.add(scope);
            }
            return Collections.unmodifiableSet(pruned);
        }

        private static Map<String, List<String>> routes(
                EffectiveFragmentationCatalog catalog,
                List<EffectiveCutCatalogReader.ScopePlan> plans,
                Set<String> pruned) {
            Map<String, List<String>> routes =
                    new LinkedHashMap<String, List<String>>();
            for (EffectiveCutCatalogReader.ScopePlan plan : plans) {
                if (isWithinAny(plan.scopePath(), pruned)) continue;
                EffectiveContractSnapshot processEmbedded = null;
                List<EffectiveContractSnapshot> contracts = catalog
                        .effectiveContractsByScope().get(plan.scopePath());
                for (EffectiveContractSnapshot candidate : contracts) {
                    if (!EffectiveContractSnapshotConstants.Role
                            .PROCESS_EMBEDDED.equals(candidate.role())) {
                        continue;
                    }
                    if (processEmbedded != null) {
                        throw new IllegalArgumentException(
                                "multiple effective Process Embedded "
                                        + "contracts at " + plan.scopePath());
                    }
                    processEmbedded = candidate;
                }
                if (processEmbedded == null) continue;
                List<String> children = new ArrayList<String>();
                for (EffectiveCutCatalogReader.EmbeddedOccurrence occurrence
                        : plan.occurrences()) {
                    children.add(occurrence.concretePath());
                }
                routes.put(
                        append(
                                append(plan.scopePath(), "$contracts"),
                                processEmbedded.key()),
                        Collections.unmodifiableList(children));
            }
            return Collections.unmodifiableMap(routes);
        }

        private static boolean directTerminated(Node scope) {
            Node contracts = scope == null ? null : scope.getContracts();
            Node marker = contracts != null
                    && contracts.getProperties() != null
                    ? contracts.getProperties().get(
                            ProcessorContractConstants.KEY_TERMINATED)
                    : null;
            return RuntimeBlueIds.PROCESSING_TERMINATED_MARKER.equals(
                    recognizedType(marker));
        }

        private static String recognizedType(Node node) {
            Node type = node == null ? null : node.getType();
            Set<Node> visited = Collections.newSetFromMap(
                    new IdentityHashMap<Node, Boolean>());
            while (type != null && visited.add(type)) {
                if (type.getBlueId() != null) return type.getBlueId();
                type = type.getType();
            }
            return null;
        }
    }

    private static String append(String base, String segment) {
        String escaped = JsonPointer.escape(segment);
        return "/".equals(base) ? "/" + escaped : base + "/" + escaped;
    }

    private static boolean sameNodeIdentity(
            Node left,
            Node right,
            IdentityHashMap<Node, String> identities) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return blueId(left, identities).equals(blueId(right, identities));
    }

    private static String exactScopeBlueId(
            Node exactResultingRoot,
            String resultingRootBlueId,
            String scopePath,
            IdentityHashMap<Node, String> identities) {
        if ("/".equals(scopePath)) return resultingRootBlueId;
        Node scope = NodePathEditor.getOrNull(
                exactResultingRoot, scopePath);
        if (scope == null) {
            throw cold("active subscription scope is absent at "
                    + scopePath);
        }
        return blueId(scope, identities);
    }

    private static String blueId(
            Node value,
            IdentityHashMap<Node, String> identities) {
        if (value.isReferenceOnly()) return value.getBlueId();
        String ready = identities.get(value);
        if (ready != null) return ready;
        String calculated = DirectBlueIdCalculator.calculateBlueId(value);
        identities.put(value, calculated);
        return calculated;
    }

    private static DeltaProjectionApplier.ColdProjectionRequiredException
            cold(String reason) {
        return new DeltaProjectionApplier.ColdProjectionRequiredException(
                reason);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(
                    label + " must be non-empty");
        }
        return value;
    }
}
