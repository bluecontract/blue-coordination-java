package blue.coordination.internal;

import blue.coordination.api.ExactValue;

import blue.language.processor.EmbeddedScopePlanView;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Reusable embedded-only cut and route plan keyed by exact contract identity. */
final class EmbeddedLayoutPlan {
    private static final String ABSENT = "<absent>";

    public record ScopeRule(
            String scopePath,
            List<String> explicitAbsolutePaths,
            List<String> collectionAbsolutePaths) {
        public ScopeRule {
            scopePath = requireText(scopePath, "scopePath");
            explicitAbsolutePaths = immutable(explicitAbsolutePaths);
            collectionAbsolutePaths = immutable(collectionAbsolutePaths);
        }

        public boolean hasCollections() {
            return !collectionAbsolutePaths.isEmpty();
        }
    }

    private final Map<String, AuthoredScopeIdentity> identitiesByScope;
    private final RoutingSurface routingSurface;
    private final Map<String, ScopeRule> rulesByScope;

    private EmbeddedLayoutPlan(
            Map<String, AuthoredScopeIdentity> identitiesByScope,
            RoutingSurface routingSurface,
            Map<String, ScopeRule> rulesByScope) {
        this.identitiesByScope = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(
                        identitiesByScope, "identitiesByScope")));
        this.routingSurface = Objects.requireNonNull(
                routingSurface, "routingSurface");
        this.rulesByScope = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(
                        rulesByScope, "rulesByScope")));
    }

    public static EmbeddedLayoutPlan compile(
            ExactValue exactRoot,
            EffectiveFragmentationCatalog catalog,
            Function<String, FrozenNode> exactScopeAt) {
        Objects.requireNonNull(exactRoot, "exactRoot");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(exactScopeAt, "exactScopeAt");
        if (!exactRoot.blueId().equals(catalog.rootBlueId())) {
            throw new IllegalArgumentException(
                    "Fragmentation catalog belongs to another Root");
        }
        Map<String, ScopeRule> rules = new LinkedHashMap<>();
        for (Map.Entry<String, EmbeddedScopePlanView> entry
                : catalog.scopePlansByScope().entrySet()) {
            String scopePath = entry.getKey();
            EmbeddedScopePlanView view = entry.getValue();
            List<String> explicit = view.explicitDeclarationPaths().stream()
                    .map(path -> PointerUtils.resolvePointer(scopePath, path))
                    .sorted(EmbeddingBinding.TEXT_ORDER)
                    .toList();
            List<String> collections = view.collectionDeclarationPaths().stream()
                    .map(path -> PointerUtils.resolvePointer(scopePath, path))
                    .sorted(EmbeddingBinding.TEXT_ORDER)
                    .toList();
            rules.put(scopePath, new ScopeRule(
                    scopePath, explicit, collections));
        }
        List<String> managed = managedBoundaries(catalog);
        Map<String, AuthoredScopeIdentity> identities = new LinkedHashMap<>();
        for (String scopePath : catalog.scopePlansByScope().keySet()) {
            if (!owned(scopePath, managed)) {
                continue;
            }
            FrozenNode scope = exactScopeAt.apply(scopePath);
            if (scope == null) {
                throw new IllegalStateException(
                        "Missing exact active scope " + scopePath);
            }
            identities.put(scopePath, AuthoredScopeIdentity.from(scope));
        }
        return new EmbeddedLayoutPlan(
                identities,
                RoutingSurface.from(catalog, managedBoundaries(catalog)),
                rules);
    }

    public RoutingSurface routingSurface() {
        return routingSurface;
    }

    public Map<String, ScopeRule> rulesByScope() {
        return rulesByScope;
    }

    /** Checks only authored scopes owned by this processing Root. */
    public boolean reusableFor(Function<String, FrozenNode> exactScopeAt) {
        Objects.requireNonNull(exactScopeAt, "exactScopeAt");
        for (Map.Entry<String, AuthoredScopeIdentity> entry
                : identitiesByScope.entrySet()) {
            FrozenNode current = exactScopeAt.apply(entry.getKey());
            if (current == null || !entry.getValue().equals(
                    AuthoredScopeIdentity.from(current))) {
                return false;
            }
        }
        return true;
    }

    public boolean hasCollections() {
        return rulesByScope.values().stream().anyMatch(ScopeRule::hasCollections);
    }

    private static List<String> managedBoundaries(
            EffectiveFragmentationCatalog catalog) {
        return catalog.scopePlansByScope().values().stream()
                .flatMap(view -> view.concreteChildPaths().stream())
                .distinct()
                .sorted(EmbeddingBinding.TEXT_ORDER)
                .toList();
    }

    private static String identity(FrozenNode node) {
        return node == null ? ABSENT : node.blueId();
    }

    private static boolean owned(
            String scopePath,
            List<String> managedBoundaries) {
        for (String boundary : managedBoundaries) {
            if (PointerUtils.descendantOrEqual(scopePath, boundary)) {
                return false;
            }
        }
        return true;
    }

    /** Excludes processor-owned lifecycle/checkpoint fields from plan identity. */
    private static String authoredContractsIdentity(FrozenNode contracts) {
        if (contracts == null) {
            return ABSENT;
        }
        return contracts
                .withProperty(ProcessorContractConstants.KEY_INITIALIZED, null)
                .withProperty(ProcessorContractConstants.KEY_CHECKPOINT, null)
                .withProperty(ProcessorContractConstants.KEY_TERMINATED, null)
                .blueId();
    }

    private static String requireIdentity(String value) {
        String checked = Objects.requireNonNull(value, "identity");
        if (checked.isBlank()) {
            throw new IllegalArgumentException("identity must not be blank");
        }
        return checked;
    }

    private static List<String> immutable(List<String> source) {
        List<String> copy = new ArrayList<>(Objects.requireNonNull(
                source, "source"));
        copy.sort(EmbeddingBinding.TEXT_ORDER);
        return Collections.unmodifiableList(copy);
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    private record AuthoredScopeIdentity(
            String typeBlueId,
            String contractsBlueId) {
        private AuthoredScopeIdentity {
            typeBlueId = requireIdentity(typeBlueId);
            contractsBlueId = requireIdentity(contractsBlueId);
        }

        private static AuthoredScopeIdentity from(FrozenNode scope) {
            return new AuthoredScopeIdentity(
                    identity(scope.getType()),
                    authoredContractsIdentity(scope.getContracts()));
        }
    }
}
