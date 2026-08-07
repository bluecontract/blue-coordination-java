package blue.coordination.examples.documents;

import java.util.List;
import java.util.Objects;

/** Complete ordered catalog of the statically authored MyOS demo documents. */
public final class MyOsDemoDocumentCatalog {

    public static final String CATALOG_SOURCE_KIND = "catalog";
    public static final String GENERATED_FIXTURE_SOURCE_KIND =
            "generated-fixture";

    private MyOsDemoDocumentCatalog() {
    }

    public static List<DocumentSource> all() {
        return List.of(
                source("counter-basics", "basics-counter", BasicsCounterDocuments.COUNTER,
                        "BasicsCounterDocuments.COUNTER"),
                source("shared-counter", "shared-counter-a", SharedCounterDocuments.COUNTER_A,
                        "SharedCounterDocuments.COUNTER_A"),
                source("shared-counter", "shared-counter-b", SharedCounterDocuments.COUNTER_B,
                        "SharedCounterDocuments.COUNTER_B"),
                source("embedded-counter", "complete-fanout-root", CompleteFanoutDocuments.ROOT,
                        "CompleteFanoutDocuments.ROOT"),
                source("embedded-counter", "embedded-child-counter", EmbeddedCounterDocuments.COUNTER,
                        "EmbeddedCounterDocuments.COUNTER"),
                source("embedded-counter", "embedded-counter", EmbeddedCounterDocuments.EMBEDDED_COUNTER,
                        "EmbeddedCounterDocuments.EMBEDDED_COUNTER"),
                source("dynamic-activation", "dynamic-activation", DynamicActivationDocuments.DYNAMIC_ACTIVATION,
                        "DynamicActivationDocuments.DYNAMIC_ACTIVATION"),
                source("embedded-counter", "dynamic-managed-parent", ManagedLinkDocuments.DYNAMIC_PARENT,
                        "ManagedLinkDocuments.DYNAMIC_PARENT"),
                source("operation-mandate", "delegated-counter", MandateOperationDocuments.DELEGATED_COUNTER,
                        "MandateOperationDocuments.DELEGATED_COUNTER"),
                source("operation-mandate", "increment-mandate", MandateOperationDocuments.INCREMENT_MANDATE,
                        "MandateOperationDocuments.INCREMENT_MANDATE"),
                source("vet-visit", "vet-order", VetDocuments.VET_ORDER,
                        "VetDocuments.VET_ORDER"),
                source("vet-visit", "vet-order-paynote", VetDocuments.VET_ORDER_PAYNOTE,
                        "VetDocuments.VET_ORDER_PAYNOTE"),
                source("vet-visit", "vet-trainer-agreement", VetDocuments.VET_TRAINER_AGREEMENT,
                        "VetDocuments.VET_TRAINER_AGREEMENT"),
                source("vet-visit", "pupps-order", VetDocuments.PUPPS_ORDER,
                        "VetDocuments.PUPPS_ORDER"),
                source("pawstart-plan", "pawstart-plan-order", VetExtDocuments.PAWSTART_PLAN_ORDER,
                        "VetExtDocuments.PAWSTART_PLAN_ORDER"),
                source("pawstart-plan", "pawstart-plan-paynote", VetExtDocuments.PAWSTART_PLAN_PAYNOTE,
                        "VetExtDocuments.PAWSTART_PLAN_PAYNOTE"),
                source("pawstart-plan", "pupps-grooming-order", VetExtDocuments.PUPPS_GROOMING_ORDER,
                        "VetExtDocuments.PUPPS_GROOMING_ORDER"),
                source("pawstart-plan", "scheduling-mandate", VetExtDocuments.SCHEDULING_MANDATE,
                        "VetExtDocuments.SCHEDULING_MANDATE"),
                source("pawstart-plan", "vet-pupps-agreement", VetExtDocuments.VET_PUPPS_AGREEMENT,
                        "VetExtDocuments.VET_PUPPS_AGREEMENT"),
                source("wadowice-hotel-dinner", "package-paynote", OrderDocuments.PACKAGE_PAYNOTE,
                        "OrderDocuments.PACKAGE_PAYNOTE"),
                source("wadowice-hotel-dinner", "package-order", OrderDocuments.PACKAGE_ORDER,
                        "OrderDocuments.PACKAGE_ORDER"));
    }

    private static DocumentSource source(
            String exampleId,
            String documentKey,
            String authoredYaml,
            String sourceConstant) {
        return new DocumentSource(
                exampleId, documentKey, authoredYaml, sourceConstant);
    }

    /** One readable source constant and its stable example/document names. */
    public record DocumentSource(
            String exampleId,
            String documentKey,
            String authoredYaml,
            String sourceConstant,
            String sourceKind,
            List<String> sourceDependencies) {

        public DocumentSource(
                String exampleId,
                String documentKey,
                String authoredYaml,
                String sourceConstant) {
            this(
                    exampleId,
                    documentKey,
                    authoredYaml,
                    sourceConstant,
                    CATALOG_SOURCE_KIND,
                    List.of());
        }

        public DocumentSource {
            Objects.requireNonNull(exampleId, "exampleId");
            Objects.requireNonNull(documentKey, "documentKey");
            Objects.requireNonNull(authoredYaml, "authoredYaml");
            Objects.requireNonNull(sourceConstant, "sourceConstant");
            Objects.requireNonNull(sourceKind, "sourceKind");
            if (!sourceKind.equals(CATALOG_SOURCE_KIND)
                    && !sourceKind.equals(GENERATED_FIXTURE_SOURCE_KIND)) {
                throw new IllegalArgumentException(
                        "Unknown document source kind: " + sourceKind);
            }
            sourceDependencies = List.copyOf(
                    Objects.requireNonNull(
                            sourceDependencies,
                            "sourceDependencies"));
            if (sourceDependencies.stream().anyMatch(value ->
                    value == null
                            || value.isBlank()
                            || !value.equals(value.trim()))) {
                throw new IllegalArgumentException(
                        "Document source dependencies must be exact BlueIds");
            }
        }
    }
}
