package blue.coordination.basic;

import blue.coordination.examples.documents.OrderDocuments;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract test for the positive Process Embedded storage boundary. */
final class EmbeddedOnlyDocumentEnvironmentTest {

    @Test
    void cutsOnlyEffectiveProcessEmbeddedDocuments() throws Exception {
        try (EmbeddedOnlyDocumentEnvironment environment =
                     EmbeddedOnlyDocumentEnvironment.create()) {
            String source = BasicTestResources.read(
                    "examples/wadowice/package-order.yaml");
            assertEquals(OrderDocuments.PACKAGE_ORDER, source);

            var started = environment.start("package-order", source);
            var layout = started.document().layout();

            assertEquals(Set.of(
                            "/",
                            "/product",
                            "/product/products/hotel",
                            "/product/products/restaurant"),
                    layout.scopePaths());
            assertEquals(3, layout.declaredEmbeddedDocumentCount());
            assertEquals(4, layout.physicalObjectCount());
            assertEquals(3, layout.splitterCreatedEdgeCount());
            assertEquals(0, layout.authoredReferenceEdgeCount());
            assertEquals(started.document().currentRootBlueId(),
                    layout.rootBlueId());

            Node storedRoot = layout.storedRootObject();
            assertTrue(required(storedRoot, "/product").isReferenceOnly());
            assertFalse(required(storedRoot, "/customer").isReferenceOnly(),
                    "An ordinary child must stay inline");

            Node storedProduct = layout.storedDocumentObject("/product");
            assertTrue(required(
                    storedProduct, "/products/hotel").isReferenceOnly());
            assertTrue(required(
                    storedProduct, "/products/restaurant").isReferenceOnly());
            assertFalse(required(
                    storedProduct, "/productStates").isReferenceOnly(),
                    "An ordinary nested child must stay inline");

            Node reconstructed = layout.reconstructRoot();
            assertEquals(layout.rootBlueId(),
                    DirectBlueIdCalculator.calculateBlueId(reconstructed));
        }
    }

    private static Node required(Node root, String path) {
        Node selected = NodePathEditor.getOrNull(root, path);
        assertNotNull(selected, "Missing node at " + path);
        return selected;
    }
}
