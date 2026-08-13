package blue.coordination.integration;

import blue.coordination.api.DocumentSnapshot;
import blue.coordination.api.ExactValue;
import blue.language.model.Node;

import java.util.List;

/** Read-only physical-layout evidence projected from DocumentSnapshot. */
final class EmbeddedOnlyLayout {
    private final DocumentSnapshot snapshot;

    EmbeddedOnlyLayout(DocumentSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    String rootBlueId() {
        return snapshot.blueId();
    }

    int physicalObjectCount() {
        return snapshot.physicalObjectCount();
    }

    int embeddedDocumentCount() {
        return snapshot.embeddedChildren().size();
    }

    int splitterCreatedEdgeCount() {
        return snapshot.processEmbeddedBoundaries().size();
    }

    List<Boundary> boundaries() {
        return snapshot.processEmbeddedBoundaries().stream()
                .map(Boundary::new)
                .toList();
    }

    ExactValue stored(String scopePath) {
        return snapshot.physicalObject(scopePath);
    }

    Node reconstructRoot() {
        return snapshot.current().copyNode();
    }

    RoutingSurface routingSurface() {
        return new RoutingSurface(snapshot.routingDefinitions());
    }

    record Boundary(String childScopePath) {
    }

    record RoutingSurface(List<String> definitions) {
        RoutingSurface {
            definitions = List.copyOf(definitions);
        }
    }
}
