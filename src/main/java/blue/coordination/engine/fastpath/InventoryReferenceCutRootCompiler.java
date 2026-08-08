package blue.coordination.engine.fastpath;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.fastpath.ExactNodeHandle;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.model.wire.JsonPointer;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds an identity-equivalent sparse Root directly from canonical PROCESS
 * fragment representations, without reconstructing or cloning the complete
 * Root first.
 *
 * <p>The authoritative inventory already records every direct-fragment edge.
 * The compiler leaves each planned inactive subtree as the pure reference in
 * its owning physical fragment, materializes only the active ancestor chains,
 * and verifies the resulting Root identity before it can reach frozen
 * Contracts. This is the primary Round-4 performance primitive.</p>
 */
public final class InventoryReferenceCutRootCompiler {
    public static final String ALGORITHM_VERSION =
            "blue.coordination/reference-cut/inventory-assembly/3";

    private final ReferenceCutPlanner planner;
    private final ReferenceCutFragmentSource fragmentSource;
    private final ReferenceCutMetrics metrics;

    public InventoryReferenceCutRootCompiler(
            ReferenceCutPlanner planner,
            ReferenceCutFragmentSource fragmentSource,
            ReferenceCutMetrics metrics) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.fragmentSource = Objects.requireNonNull(
                fragmentSource, "fragmentSource");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public ReferenceCutRootArtifact compile(
            CoordinationFragmentInventory inventory,
            ActivePathSet activePaths) {
        CoordinationFragmentInventory checked = Objects.requireNonNull(
                inventory, "inventory");
        ActivePathSet active = Objects.requireNonNull(
                activePaths, "activePaths");
        return compile(checked, active, planner.plan(checked, active));
    }

    /** Compiles a preflighted plan without sorting/planning its edges again. */
    public ReferenceCutRootArtifact compile(
            CoordinationFragmentInventory inventory,
            ActivePathSet activePaths,
            ReferenceCutPlan suppliedPlan) {
        CoordinationFragmentInventory checked = Objects.requireNonNull(
                inventory, "inventory");
        ActivePathSet active = Objects.requireNonNull(
                activePaths, "activePaths");
        ReferenceCutPlan plan = Objects.requireNonNull(
                suppliedPlan, "suppliedPlan");
        validatePreflight(checked, active, plan);

        List<String> orderedBlueIds = plan.selectedBlueIds();
        Map<String, ExactNodeHandle> canonical = fragmentSource.loadCanonical(
                checked.inventoryIdentity(), orderedBlueIds);
        requireComplete(canonical, orderedBlueIds);

        LinkedHashMap<String, Node> occurrenceBodies =
                new LinkedHashMap<String, Node>();
        occurrenceBodies.put(
                JsonPointer.ROOT,
                copyBody(canonical, checked.rootBlueId()));
        for (ReferenceCutPlan.ExpandedEdge edge : plan.expandedEdges()) {
            String path = edge.absolutePointer();
            occurrenceBodies.putIfAbsent(
                    path,
                    copyBody(canonical, edge.childBlueId()));
        }

        for (ReferenceCutPlan.ExpandedEdge edge : plan.expandedEdges()) {
            String childPath = edge.absolutePointer();
            String ownerPath = edge.ownerPointer();
            Node child = occurrenceBodies.get(childPath);
            Node owner = occurrenceBodies.get(ownerPath);
            if (child == null || owner == null) {
                throw new IllegalStateException(
                        "Selected direct-fragment occurrence is incomplete: "
                                + ownerPath + " -> " + childPath);
            }
            putStructuralChild(
                    owner,
                    edge.ownerRelativePointer(),
                    child);
        }

        Node sparseRoot = occurrenceBodies.get(JsonPointer.ROOT);
        metrics.compilation();
        metrics.inventoryCompilation();
        metrics.canonicalFragmentsRead(plan.selectedFragmentCount());
        metrics.inventorySelection(
                plan.totalFragmentCount(),
                plan.selectedFragmentCount());
        metrics.cutEdges(plan.cuts().size());
        metrics.identityCheck();
        String actual = DirectBlueIdCalculator.calculateBlueId(sparseRoot);
        if (!checked.rootBlueId().equals(actual)) {
            metrics.identityFailure();
            throw new IllegalStateException(
                    "Inventory-assembled sparse Root changed identity: "
                            + "expected=" + checked.rootBlueId()
                            + ", actual=" + actual);
        }
        metrics.fullRootMaterializationAvoided();
        ReferenceCutRootArtifact artifact =
                ReferenceCutRootArtifact.fromInventoryAssembly(
                        checked.rootBlueId(),
                        checked.inventoryIdentity(),
                        sparseRoot,
                        plan.cuts(),
                        plan.totalFragmentCount(),
                        plan.selectedFragmentCount());
        metrics.sparseNodes(artifact.sparseStats().nodes());
        return artifact;
    }

    /** Exact selected-fragment estimate available before any body is read. */
    public static int estimatedMaterializedFragmentCount(
            CoordinationFragmentInventory inventory,
            ReferenceCutPlan plan) {
        CoordinationFragmentInventory checked = Objects.requireNonNull(
                inventory, "inventory");
        ReferenceCutPlan planned = Objects.requireNonNull(plan, "plan");
        if (!checked.rootBlueId().equals(planned.rootBlueId())
                || !checked.inventoryIdentity().equals(
                        planned.inventoryIdentity())
                || !planned.isValidatedPreflight()
                || checked.fragmentBlueIds().size()
                        != planned.totalFragmentCount()) {
            throw new IllegalArgumentException(
                    "Reference-cut plan belongs to another inventory");
        }
        return planned.selectedFragmentCount();
    }

    /** Exact inventory-fragment reduction estimate for a preflighted plan. */
    public static double estimatedFragmentReduction(
            CoordinationFragmentInventory inventory,
            ReferenceCutPlan plan) {
        estimatedMaterializedFragmentCount(inventory, plan);
        return plan.fragmentReductionFraction();
    }

    private static void validatePreflight(
            CoordinationFragmentInventory inventory,
            ActivePathSet activePaths,
            ReferenceCutPlan plan) {
        if (!plan.isValidatedPreflight()
                || !inventory.rootBlueId().equals(plan.rootBlueId())
                || !inventory.inventoryIdentity().equals(
                        plan.inventoryIdentity())
                || inventory.fragmentBlueIds().size()
                        != plan.totalFragmentCount()) {
            throw new IllegalArgumentException(
                    "Reference-cut plan belongs to another inventory or "
                            + "is not a validated preflight");
        }
        if (!activePaths.identity().equals(plan.activePathIdentity())
                || !activePaths.paths().equals(plan.activePaths())) {
            throw new IllegalArgumentException(
                    "Reference-cut plan belongs to another active-path "
                            + "surface");
        }
    }

    private static Node copyBody(
            Map<String, ExactNodeHandle> canonical,
            String blueId) {
        ExactNodeHandle handle = canonical.get(blueId);
        if (handle == null || !blueId.equals(handle.blueId())) {
            throw new IllegalStateException(
                    "Verified canonical fragment handle is absent: "
                            + blueId);
        }
        Node selected = handle.copy();
        if (selected == null || selected.isReferenceOnly()) {
            throw new IllegalStateException(
                    "Concrete canonical fragment is absent: " + blueId);
        }
        return selected;
    }

    private static void requireComplete(
            Map<String, ExactNodeHandle> canonical,
            List<String> required) {
        if (!canonical.keySet().containsAll(required)) {
            LinkedHashSet<String> missing = new LinkedHashSet<String>(required);
            missing.removeAll(canonical.keySet());
            throw new IllegalStateException(
                    "Canonical sparse-Root batch is incomplete: " + missing);
        }
    }

    /** Grafts one canonical direct-fragment edge, including list/schema axes. */
    private static void putStructuralChild(
            Node owner,
            String pointer,
            Node child) {
        List<String> segments = JsonPointer.split(pointer);
        if (segments.size() == 1) {
            String first = segments.get(0);
            if ("type".equals(first)) {
                owner.type(child);
            } else if ("itemType".equals(first)) {
                owner.itemType(child);
            } else if ("keyType".equals(first)) {
                owner.keyType(child);
            } else if ("valueType".equals(first)) {
                owner.valueType(child);
            } else if ("contracts".equals(first)) {
                owner.contracts(child);
            } else if ("blue".equals(first)) {
                owner.blue(child);
            } else {
                if (owner.getProperties() == null) {
                    owner.properties(new LinkedHashMap<String, Node>());
                }
                owner.getProperties().put(first, child);
            }
            return;
        }
        if (segments.size() == 2 && "items".equals(segments.get(0))) {
            if (owner.getItems() == null) {
                throw new IllegalStateException(
                        "List edge has no direct-fragment items owner: "
                                + pointer);
            }
            owner.getItems().set(parseIndex(segments.get(1), pointer), child);
            return;
        }
        if (segments.size() >= 2 && "schema".equals(segments.get(0))) {
            putSchemaChild(owner.getSchema(), segments, pointer, child);
            return;
        }
        throw new IllegalStateException(
                "Unsupported direct-fragment edge pointer: " + pointer);
    }

    private static void putSchemaChild(
            Schema schema,
            List<String> segments,
            String pointer,
            Node child) {
        if (schema == null || schema.isReferenceOnly()) {
            throw new IllegalStateException(
                    "Schema edge has no exact direct-fragment owner: "
                            + pointer);
        }
        String field = segments.get(1);
        if ("minimum".equals(field)) {
            schema.minimum(child);
        } else if ("maximum".equals(field)) {
            schema.maximum(child);
        } else if ("exclusiveMinimum".equals(field)) {
            schema.exclusiveMinimum(child);
        } else if ("exclusiveMaximum".equals(field)) {
            schema.exclusiveMaximum(child);
        } else if ("multipleOf".equals(field)) {
            schema.multipleOf(child);
        } else if ("enum".equals(field)
                && segments.size() == 3
                && schema.getEnum() != null) {
            schema.getEnum().set(
                    parseIndex(segments.get(2), pointer), child);
        } else {
            throw new IllegalStateException(
                    "Unsupported schema direct-fragment edge pointer: "
                            + pointer);
        }
    }

    private static int parseIndex(String supplied, String pointer) {
        try {
            return Integer.parseInt(supplied);
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException(
                    "Direct-fragment edge has a non-numeric index: "
                            + pointer,
                    invalid);
        }
    }
}
