package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.VerifiedExecutionEvidence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * High-level, persistence-neutral hand-off from indexed planning and exact
 * physical fragmentation to an arbitrary exact {@code NodeProvider} host.
 *
 * <p>The preparation contains identities and immutable evidence only.  It
 * neither persists fragments nor authorizes, schedules, or executes PROCESS.</p>
 */
public final class CoordinationProcessingPreparation {

    private final CoordinationPreparedDelivery preparedDelivery;
    private final String fragmentationProfileIdentity;
    private final String edgeMetadataSchemaIdentity;
    private final String documentFragmentInventoryIdentity;
    private final String eventFragmentInventoryIdentity;
    private final List<CoordinationDocumentSplitter.EdgeOccurrence>
            documentEdgeOccurrences;
    private final List<CoordinationDocumentSplitter.EdgeOccurrence>
            eventEdgeOccurrences;
    private final Set<String> requiredSeedFragmentIdentities;

    private CoordinationProcessingPreparation(
            CoordinationPreparedDelivery preparedDelivery,
            CoordinationDocumentSplitter.SplitGraph document,
            CoordinationDocumentSplitter.SplitGraph event) {
        this.preparedDelivery = Objects.requireNonNull(
                preparedDelivery, "preparedDelivery");
        CoordinationDocumentSplitter.SplitGraph checkedDocument =
                Objects.requireNonNull(document, "document");
        CoordinationDocumentSplitter.SplitGraph checkedEvent =
                Objects.requireNonNull(event, "event");
        String rootBlueId =
                preparedDelivery.rootReference().getBlueId();
        String eventBlueId =
                preparedDelivery.eventReference().getBlueId();
        if (!rootBlueId.equals(checkedDocument.rootBlueId())
                || !eventBlueId.equals(checkedEvent.rootBlueId())) {
            throw new IllegalArgumentException(
                    "Fragment inventories do not bind to the prepared exact "
                            + "Root and event");
        }
        if (!checkedDocument.fragmentationProfileIdentity().equals(
                checkedEvent.fragmentationProfileIdentity())
                || !checkedDocument.edgeMetadataSchemaIdentity().equals(
                checkedEvent.edgeMetadataSchemaIdentity())) {
            throw new IllegalArgumentException(
                    "Document and event fragment inventories use different "
                            + "profiles");
        }
        this.fragmentationProfileIdentity =
                checkedDocument.fragmentationProfileIdentity();
        this.edgeMetadataSchemaIdentity =
                checkedDocument.edgeMetadataSchemaIdentity();
        this.documentFragmentInventoryIdentity =
                checkedDocument.inventoryIdentity();
        this.eventFragmentInventoryIdentity =
                checkedEvent.inventoryIdentity();
        this.documentEdgeOccurrences =
                immutableEdges(checkedDocument.edgeOccurrences());
        this.eventEdgeOccurrences =
                immutableEdges(checkedEvent.edgeOccurrences());
        LinkedHashSet<String> seeds = new LinkedHashSet<>(
                preparedDelivery.requiredSeedFragmentIdentities());
        seeds.add(checkedDocument.rootBlueId());
        seeds.add(checkedEvent.rootBlueId());
        this.requiredSeedFragmentIdentities =
                Collections.unmodifiableSet(seeds);
    }

    /**
     * Combines an exact indexed plan with independently prepared document and
     * event fragment inventories.
     *
     * @param preparedDelivery verified indexed delivery result
     * @param document exact document split graph
     * @param event exact event split graph
     * @return immutable generic processing preparation
     */
    public static CoordinationProcessingPreparation combine(
            CoordinationPreparedDelivery preparedDelivery,
            CoordinationDocumentSplitter.SplitGraph document,
            CoordinationDocumentSplitter.SplitGraph event) {
        return new CoordinationProcessingPreparation(
                preparedDelivery, document, event);
    }

    public Node rootReference() {
        return preparedDelivery.rootReference();
    }

    public Node eventReference() {
        return preparedDelivery.eventReference();
    }

    public VerifiedExecutionEvidence evidence() {
        return preparedDelivery.evidence();
    }

    public ExternalDeliveryPlan deliveryPlan() {
        return preparedDelivery.deliveryPlan();
    }

    public String deliveryPlanIdentity() {
        return preparedDelivery.deliveryPlanIdentity();
    }

    public String subscriptionSnapshotIdentity() {
        return preparedDelivery.subscriptionSnapshotIdentity();
    }

    public List<String> preselectedOccurrenceOrder() {
        return preparedDelivery.preselectedOccurrenceOrder();
    }

    public List<CoordinationDeliveryDiagnostic> sourceDeliveries() {
        return preparedDelivery.sourceDeliveries();
    }

    public Map<String, List<String>> selectedScopeChainIdentities() {
        return preparedDelivery.selectedScopeChainIdentities();
    }

    public CoordinationSemanticDemandBoundary demandBoundary() {
        return preparedDelivery.demandBoundary();
    }

    public List<String> prefetchIdentities() {
        return preparedDelivery.prefetchIdentities();
    }

    public Set<String> requiredSeedFragmentIdentities() {
        return requiredSeedFragmentIdentities;
    }

    public String fragmentationProfileIdentity() {
        return fragmentationProfileIdentity;
    }

    public String edgeMetadataSchemaIdentity() {
        return edgeMetadataSchemaIdentity;
    }

    public String documentFragmentInventoryIdentity() {
        return documentFragmentInventoryIdentity;
    }

    public String eventFragmentInventoryIdentity() {
        return eventFragmentInventoryIdentity;
    }

    public List<CoordinationDocumentSplitter.EdgeOccurrence>
    documentEdgeOccurrences() {
        return documentEdgeOccurrences;
    }

    public List<CoordinationDocumentSplitter.EdgeOccurrence>
    eventEdgeOccurrences() {
        return eventEdgeOccurrences;
    }

    private static List<CoordinationDocumentSplitter.EdgeOccurrence>
    immutableEdges(
            List<CoordinationDocumentSplitter.EdgeOccurrence> source) {
        return Collections.unmodifiableList(
                new ArrayList<>(
                        Objects.requireNonNull(
                                source, "edgeOccurrences")));
    }
}
