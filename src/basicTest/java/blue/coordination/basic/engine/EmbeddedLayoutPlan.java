package blue.coordination.basic.engine;

import blue.language.processor.EmbeddedScopePlanView;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reusable embedded-only cut and routing plan for one autonomous Root.
 * Ordinary values and request payloads do not participate in this plan.
 *
 * <p>Plan validity is checked from memoized immutable type/contracts subtree
 * identities. The previous implementation cloned and rehashed authored
 * contracts after every PROCESS call.</p>
 */
public final class EmbeddedLayoutPlan {
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

    private final String rootTypeBlueId;
    private final String rootContractsBlueId;
    private final RoutingSurface routingSurface;
    private final Map<String, ScopeRule> rulesByScope;

    private EmbeddedLayoutPlan(
            String rootTypeBlueId,
            String rootContractsBlueId,
            RoutingSurface routingSurface,
            Map<String, ScopeRule> rulesByScope) {
        this.rootTypeBlueId = requireIdentity(rootTypeBlueId);
        this.rootContractsBlueId = requireIdentity(rootContractsBlueId);
        this.routingSurface = Objects.requireNonNull(
                routingSurface, "routingSurface");
        this.rulesByScope = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(
                        rulesByScope, "rulesByScope")));
    }

    public static EmbeddedLayoutPlan compile(
            ExactNodeValue exactRoot,
            EffectiveFragmentationCatalog catalog) {
        Objects.requireNonNull(exactRoot, "exactRoot");
        Objects.requireNonNull(catalog, "catalog");
        Map<String, ScopeRule> rules = new LinkedHashMap<>();
        for (Map.Entry<String, EmbeddedScopePlanView> entry
                : catalog.scopePlansByScope().entrySet()) {
            String scopePath = entry.getKey();
            EmbeddedScopePlanView view = entry.getValue();
            List<String> explicit = view.explicitDeclarationPaths().stream()
                    .map(path -> PointerUtils.resolvePointer(scopePath, path))
                    .sorted()
                    .toList();
            List<String> collections = view.collectionDeclarationPaths().stream()
                    .map(path -> PointerUtils.resolvePointer(scopePath, path))
                    .sorted()
                    .toList();
            rules.put(scopePath, new ScopeRule(
                    scopePath, explicit, collections));
        }
        EmbeddedLayoutPlan plan = new EmbeddedLayoutPlan(
                identity(exactRoot.frozen().getType()),
                authoredContractsIdentity(exactRoot.frozen().getContracts()),
                RoutingSurface.from(catalog, autonomousBoundaries(catalog)),
                rules);
        if (plan.hasCollections()) {
            throw new IllegalArgumentException(
                    "The compact basic lane supports explicit Process Embedded "
                            + "paths only; collection declarations require the "
                            + "general Coordination engine");
        }
        return plan;
    }

    public RoutingSurface routingSurface() {
        return routingSurface;
    }

    public Map<String, ScopeRule> rulesByScope() {
        return rulesByScope;
    }

    /**
     * The compact lane freezes type/contracts at admission. Checking two
     * memoized subtree BlueIds is O(1) after first calculation and independent
     * of ordinary document size.
     */
    public boolean reusableFor(ExactNodeValue exactRoot) {
        Objects.requireNonNull(exactRoot, "exactRoot");
        return !hasCollections()
                && rootTypeBlueId.equals(identity(exactRoot.frozen().getType()))
                && rootContractsBlueId.equals(
                        authoredContractsIdentity(
                                exactRoot.frozen().getContracts()));
    }

    public boolean hasCollections() {
        return rulesByScope.values().stream().anyMatch(ScopeRule::hasCollections);
    }

    private static List<String> autonomousBoundaries(
            EffectiveFragmentationCatalog catalog) {
        return catalog.scopePlansByScope().values().stream()
                .flatMap(view -> view.concreteChildPaths().stream())
                .distinct()
                .sorted()
                .toList();
    }

    private static String identity(FrozenNode node) {
        return node == null ? ABSENT : node.blueId();
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
        copy.sort(Comparator.naturalOrder());
        return Collections.unmodifiableList(copy);
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
