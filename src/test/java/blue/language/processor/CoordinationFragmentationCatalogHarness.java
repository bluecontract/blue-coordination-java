package blue.language.processor;

import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.PointerUtils;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.NodePathEditor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Splitter-test fixture for synthetic roots that intentionally are not valid
 * processing documents.
 *
 * <p>The harness supplies a fixed effective catalog for one exact test root;
 * it is not production authored-contract fallback behavior.</p>
 */
public final class CoordinationFragmentationCatalogHarness {

    private CoordinationFragmentationCatalogHarness() {
    }

    public static CoordinationDocumentSplitter splitter(
            Node exactRoot,
            Map<String, List<String>>
                    executableBodyFieldsByType) {
        return splitter(
                exactRoot,
                executableBodyFieldsByType,
                Collections.<String, String>emptyMap(),
                null);
    }

    public static CoordinationDocumentSplitter splitter(
            Node exactRoot,
            Map<String, List<String>> executableBodyFieldsByType,
            blue.language.provider.NodeProvider localProvider) {
        return splitter(
                exactRoot,
                executableBodyFieldsByType,
                Collections.<String, String>emptyMap(),
                localProvider);
    }

    /**
     * Creates a fixed catalog with explicit effective roles for synthetic
     * contract types that the harness cannot infer from executable fields.
     *
     * @param exactRoot exact synthetic Root
     * @param executableBodyFieldsByType executable fields by effective type
     * @param contractRolesByType effective role by effective type
     * @return processor exposing the fixed effective catalog
     */
    public static CoordinationDocumentSplitter splitter(
            Node exactRoot,
            Map<String, List<String>>
                    executableBodyFieldsByType,
            Map<String, String>
                    contractRolesByType) {
        return splitter(
                exactRoot,
                executableBodyFieldsByType,
                contractRolesByType,
                null);
    }

    private static CoordinationDocumentSplitter splitter(
            Node exactRoot,
            Map<String, List<String>> executableBodyFieldsByType,
            Map<String, String> contractRolesByType,
            blue.language.provider.NodeProvider localProvider) {
        Node retainedRoot =
                Objects.requireNonNull(
                        exactRoot, "exactRoot")
                        .clone();
        if (retainedRoot.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Harness Root must contain exact content");
        }
        String rootBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        retainedRoot);
        EffectiveFragmentationCatalog catalog =
                catalog(
                        retainedRoot,
                        executableBodyFieldsByType,
                        immutableRoles(
                                contractRolesByType));
        return CoordinationDocumentSplitter.fromEffectiveCatalog(
                suppliedRoot -> {
                Node supplied =
                        Objects.requireNonNull(
                                suppliedRoot,
                                "suppliedRoot");
                String suppliedBlueId =
                        supplied.isReferenceOnly()
                                ? supplied.getBlueId()
                                : DirectBlueIdCalculator
                                .calculateBlueId(
                                        supplied);
                if (!rootBlueId.equals(
                        suppliedBlueId)) {
                    throw new IllegalArgumentException(
                            "Harness catalog is bound to Root "
                                    + rootBlueId
                                    + ", not "
                                    + suppliedBlueId);
                }
                return catalog;
            },
                localProvider);
    }

    private static EffectiveFragmentationCatalog catalog(
            Node root,
            Map<String, List<String>>
                    executableBodyFieldsByType,
            Map<String, String>
                    contractRolesByType) {
        Map<String, List<String>> bodyFields =
                immutableBodyFields(
                        executableBodyFieldsByType);
        Map<String, List<String>> pathsByScope =
                new LinkedHashMap<>();
        Map<String, List<EffectiveContractSnapshot>>
                contractsByScope =
                new LinkedHashMap<>();
        Deque<ScopeFrame> pending =
                new ArrayDeque<>();
        pending.addLast(
                new ScopeFrame("/", root));
        Set<String> scheduled =
                new LinkedHashSet<>();
        scheduled.add("/");

        while (!pending.isEmpty()) {
            ScopeFrame scope =
                    pending.removeFirst();
            List<String> embeddedPaths =
                    embeddedPaths(
                            scope.node);
            pathsByScope.put(
                    scope.path,
                    embeddedPaths);
            contractsByScope.put(
                    scope.path,
                    contracts(
                            scope.path,
                            scope.node,
                            bodyFields,
                            contractRolesByType));

            for (String declaredPath :
                    embeddedPaths) {
                String normalized =
                        PointerUtils
                                .assertValidRuntimePointer(
                                        declaredPath);
                String childPath =
                        PointerUtils.resolvePointer(
                                scope.path,
                                normalized);
                if (childPath.equals(scope.path)) {
                    throw new IllegalArgumentException(
                            "Process Embedded path "
                                    + declaredPath
                                    + " cannot embed its declaring scope");
                }
                if (!scheduled.add(childPath)) {
                    throw new IllegalArgumentException(
                            "Duplicate or cyclic Process Embedded path: "
                                    + declaredPath);
                }
                Node child =
                        NodePathEditor.getOrNull(
                                scope.node,
                                normalized);
                if (child == null) {
                    throw new IllegalArgumentException(
                            "Process Embedded path is absent: "
                                    + childPath);
                }
                if (child.isReferenceOnly()) {
                    throw new IllegalArgumentException(
                            "Harness does not materialize reference-backed scope "
                                    + childPath);
                }
                pending.addLast(
                        new ScopeFrame(
                                childPath,
                                child));
            }
        }

        return new EffectiveFragmentationCatalog(
                DirectBlueIdCalculator.calculateBlueId(
                        root),
                pathsByScope,
                contractsByScope);
    }

    private static List<String> embeddedPaths(
            Node scope) {
        Node contracts = scope.getContracts();
        if (contracts == null
                || contracts.getProperties() == null) {
            return Collections.emptyList();
        }
        for (Node contract :
                contracts.getProperties().values()) {
            if (!RuntimeBlueIds.PROCESS_EMBEDDED
                    .equals(typeBlueId(contract))) {
                continue;
            }
            Node paths =
                    contract.getProperties() != null
                            ? contract
                            .getProperties()
                            .get("paths")
                            : null;
            if (paths == null
                    || paths.getItems() == null) {
                return Collections.emptyList();
            }
            List<String> result =
                    new ArrayList<>();
            for (Node path : paths.getItems()) {
                Object value =
                        path != null
                                ? path.getRawValue()
                                : null;
                if (!(value instanceof String)) {
                    throw new IllegalArgumentException(
                            "Process Embedded path must be a string");
                }
                result.add(
                        (String) value);
            }
            return Collections.unmodifiableList(
                    result);
        }
        return Collections.emptyList();
    }

    private static List<EffectiveContractSnapshot>
    contracts(
            String scopePath,
            Node scope,
            Map<String, List<String>> bodyFields,
            Map<String, String> contractRolesByType) {
        Node contracts = scope.getContracts();
        if (contracts == null
                || contracts.getProperties() == null) {
            return Collections.emptyList();
        }
        Map<String, Node> ordered =
                new TreeMap<>(
                        contracts.getProperties());
        List<EffectiveContractSnapshot> result =
                new ArrayList<>();
        for (Map.Entry<String, Node> entry :
                ordered.entrySet()) {
            Node contract = entry.getValue();
            String typeBlueId =
                    typeBlueId(contract);
            if (typeBlueId == null) {
                continue;
            }
            List<String> declaredBodies =
                    bodyFields.get(typeBlueId);
            String declaredRole =
                    contractRolesByType.get(
                            typeBlueId);
            EffectiveContractSnapshot.Builder builder =
                    EffectiveContractSnapshot
                            .builder(
                                    scopePath,
                                    entry.getKey())
                            .effectiveTypeBlueId(
                                    typeBlueId)
                            .role(
                                    declaredRole != null
                                            ? declaredRole
                                            : declaredBodies != null
                                            ? EffectiveContractSnapshotConstants
                                            .Role.HANDLER
                                            : RuntimeBlueIds
                                            .PROCESS_EMBEDDED
                                            .equals(typeBlueId)
                                            ? EffectiveContractSnapshotConstants
                                            .Role.PROCESS_EMBEDDED
                                            : EffectiveContractSnapshotConstants
                                            .Role.MARKER)
                            .sourceContribution(
                                    DirectBlueIdCalculator
                                            .calculateBlueId(
                                                    contract));
            if (declaredBodies != null) {
                for (String field :
                        declaredBodies) {
                    Node body =
                            contract.getProperties()
                                    != null
                                    ? contract
                                    .getProperties()
                                    .get(field)
                                    : null;
                    if (body != null) {
                        builder.executableBody(
                                field,
                                body.isReferenceOnly()
                                        ? body.getBlueId()
                                        : DirectBlueIdCalculator
                                        .calculateBlueId(
                                                body));
                    } else {
                        builder.executableBodyField(
                                field);
                    }
                }
            }
            result.add(builder.build());
        }
        result.sort(
                Comparator.comparing(
                        EffectiveContractSnapshot::key));
        return Collections.unmodifiableList(
                result);
    }

    private static String typeBlueId(
            Node contract) {
        Node type =
                contract != null
                        ? contract.getType()
                        : null;
        if (type == null) {
            return null;
        }
        return type.isReferenceOnly()
                ? type.getBlueId()
                : DirectBlueIdCalculator.calculateBlueId(
                        type);
    }

    private static Map<String, List<String>>
    immutableBodyFields(
            Map<String, List<String>> source) {
        Map<String, List<String>> result =
                new LinkedHashMap<>();
        if (source != null) {
            for (Map.Entry<String, List<String>>
                    entry : source.entrySet()) {
                result.put(
                        entry.getKey(),
                        Collections.unmodifiableList(
                                new ArrayList<>(
                                        entry.getValue())));
            }
        }
        return Collections.unmodifiableMap(
                result);
    }

    private static Map<String, String> immutableRoles(
            Map<String, String> source) {
        Map<String, String> result =
                new LinkedHashMap<>();
        for (Map.Entry<String, String> entry
                : Objects.requireNonNull(
                        source,
                        "contractRolesByType")
                        .entrySet()) {
            result.put(
                    Objects.requireNonNull(
                            entry.getKey(),
                            "contract role type"),
                    Objects.requireNonNull(
                            entry.getValue(),
                            "contract role"));
        }
        return Collections.unmodifiableMap(
                result);
    }

    private static final class ScopeFrame {
        private final String path;
        private final Node node;

        private ScopeFrame(
                String path,
                Node node) {
            this.path = path;
            this.node = node;
        }
    }
}
