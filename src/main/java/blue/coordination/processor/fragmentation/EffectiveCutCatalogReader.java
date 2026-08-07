package blue.coordination.processor.fragmentation;

import blue.language.model.wire.JsonPointer;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.processor.EmbeddedScopePlanView;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.util.PointerUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads the public, structured Process Embedded catalog into immutable cut
 * occurrences used by Coordination's physical splitter.
 *
 * <p>This is deliberately the only Coordination splitter boundary that
 * interprets {@link EmbeddedScopePlanView}. It never reparses contract Blue
 * content and retains the exact declaration form and unescaped collection
 * key supplied by Language.</p>
 */
public final class EffectiveCutCatalogReader {

    private EffectiveCutCatalogReader() {
    }

    /**
     * Returns scope plans in deterministic parent-before-child order.
     *
     * @param catalog verified Language fragmentation catalog
     * @return immutable ordered scope plans
     */
    public static List<ScopePlan> read(
            EffectiveFragmentationCatalog catalog) {
        Map<String, EmbeddedScopePlanView> views =
                Objects.requireNonNull(catalog, "catalog")
                        .scopePlansByScope();
        List<String> paths = new ArrayList<>(views.keySet());
        paths.sort(
                Comparator
                        .comparingInt(
                                (String path) ->
                                        JsonPointer.split(path).size())
                        .thenComparing(
                                ExternalOrderKey::compareTextCodePoints));
        List<ScopePlan> result = new ArrayList<>(paths.size());
        for (String scopePath : paths) {
            EmbeddedScopePlanView view = Objects.requireNonNull(
                    views.get(scopePath),
                    "scope plan at " + scopePath);
            String canonicalScope = JsonPointer.canonicalize(scopePath);
            if (!canonicalScope.equals(view.scopePath())) {
                throw invalid(
                        "scope map key " + scopePath
                                + " disagrees with view path "
                                + view.scopePath());
            }
            result.add(new ScopePlan(
                    canonicalScope,
                    occurrences(view)));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<EmbeddedOccurrence> occurrences(
            EmbeddedScopePlanView view) {
        Map<String, Declaration> declarations = declarations(view);
        List<EmbeddedOccurrence> result = new ArrayList<>();
        for (String concretePath : view.concreteChildPaths()) {
            String canonicalConcrete = JsonPointer.canonicalize(concretePath);
            EmbeddedScopePlanView.Origin origin =
                    view.originsByConcretePath().get(concretePath);
            if (origin == null) {
                throw invalid(
                        "concrete path has no origin at " + concretePath);
            }
            Declaration declaration = declarations.get(canonicalConcrete);
            if (declaration == null || declaration.origin != origin) {
                throw invalid(
                        "concrete path has no matching declaration at "
                                + concretePath);
            }
            result.add(new EmbeddedOccurrence(
                    view.scopePath(),
                    canonicalConcrete,
                    origin,
                    declaration.explicitDeclarationPath,
                    declaration.collectionDeclarationPath,
                    declaration.collectionMemberKey));
        }
        if (result.size() != declarations.size()) {
            throw invalid(
                    "declaration expansion disagrees with concrete paths at "
                            + view.scopePath());
        }
        return Collections.unmodifiableList(result);
    }

    private static Map<String, Declaration> declarations(
            EmbeddedScopePlanView view) {
        Map<String, Declaration> result = new LinkedHashMap<>();
        java.util.Set<String> concretePaths = new java.util.LinkedHashSet<>();
        for (String concrete : view.concreteChildPaths()) {
            concretePaths.add(JsonPointer.canonicalize(concrete));
        }
        for (String declaration : view.explicitDeclarationPaths()) {
            String concrete = PointerUtils.resolvePointer(
                    view.scopePath(), declaration);
            if (!concretePaths.contains(
                    JsonPointer.canonicalize(concrete))) {
                continue;
            }
            putUnique(
                    result,
                    concrete,
                    new Declaration(
                            EmbeddedScopePlanView.Origin.EXPLICIT,
                            declaration,
                            null,
                            null));
        }
        for (String declaration : view.collectionDeclarationPaths()) {
            List<String> memberKeys = Objects.requireNonNull(
                    view.collectionMemberKeysByDeclaration().get(declaration),
                    "collection members for " + declaration);
            String collectionPath = PointerUtils.resolvePointer(
                    view.scopePath(), declaration);
            for (String memberKey : memberKeys) {
                String concrete = JsonPointer.append(
                        collectionPath,
                        Objects.requireNonNull(memberKey, "collection member key"));
                putUnique(
                        result,
                        concrete,
                        new Declaration(
                                EmbeddedScopePlanView.Origin.COLLECTION_MEMBER,
                                null,
                                declaration,
                                memberKey));
            }
        }
        return result;
    }

    private static void putUnique(
            Map<String, Declaration> target,
            String path,
            Declaration declaration) {
        String canonical = JsonPointer.canonicalize(path);
        if (target.putIfAbsent(canonical, declaration) != null) {
            throw invalid(
                    "more than one declaration generates " + canonical);
        }
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException(
                "Invalid structured embedded-scope catalog: " + detail);
    }

    /** Immutable view of one active declaring scope. */
    public static final class ScopePlan {
        private final String scopePath;
        private final List<EmbeddedOccurrence> occurrences;

        private ScopePlan(
                String scopePath,
                List<EmbeddedOccurrence> occurrences) {
            this.scopePath = scopePath;
            this.occurrences = occurrences;
        }

        public String scopePath() {
            return scopePath;
        }

        public List<EmbeddedOccurrence> occurrences() {
            return occurrences;
        }
    }

    /** Immutable declaration provenance for one concrete child occurrence. */
    public static final class EmbeddedOccurrence {
        private final String declaringScopePath;
        private final String concretePath;
        private final EmbeddedScopePlanView.Origin origin;
        private final String explicitDeclarationPath;
        private final String collectionDeclarationPath;
        private final String collectionMemberKey;

        private EmbeddedOccurrence(
                String declaringScopePath,
                String concretePath,
                EmbeddedScopePlanView.Origin origin,
                String explicitDeclarationPath,
                String collectionDeclarationPath,
                String collectionMemberKey) {
            this.declaringScopePath = declaringScopePath;
            this.concretePath = concretePath;
            this.origin = origin;
            this.explicitDeclarationPath = explicitDeclarationPath;
            this.collectionDeclarationPath = collectionDeclarationPath;
            this.collectionMemberKey = collectionMemberKey;
        }

        public String declaringScopePath() {
            return declaringScopePath;
        }

        public String concretePath() {
            return concretePath;
        }

        public EmbeddedScopePlanView.Origin origin() {
            return origin;
        }

        public String explicitDeclarationPath() {
            return explicitDeclarationPath;
        }

        public String collectionDeclarationPath() {
            return collectionDeclarationPath;
        }

        public String collectionMemberKey() {
            return collectionMemberKey;
        }
    }

    private static final class Declaration {
        private final EmbeddedScopePlanView.Origin origin;
        private final String explicitDeclarationPath;
        private final String collectionDeclarationPath;
        private final String collectionMemberKey;

        private Declaration(
                EmbeddedScopePlanView.Origin origin,
                String explicitDeclarationPath,
                String collectionDeclarationPath,
                String collectionMemberKey) {
            this.origin = origin;
            this.explicitDeclarationPath = explicitDeclarationPath;
            this.collectionDeclarationPath = collectionDeclarationPath;
            this.collectionMemberKey = collectionMemberKey;
        }
    }
}
