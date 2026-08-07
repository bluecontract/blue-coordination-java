package blue.coordination.examples.support;

import blue.coordination.engine.api.DocumentSessionId;
import blue.language.model.Node;

import java.util.Objects;

/** One admitted example document, preserving authored and exact initial forms. */
public record MyOsDemoDocument(
        String key,
        String authoredYaml,
        Node exactInitialDocument,
        String initialBlueId,
        DocumentSessionId sessionId) {

    public MyOsDemoDocument {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(authoredYaml, "authoredYaml");
        exactInitialDocument = Objects.requireNonNull(
                exactInitialDocument, "exactInitialDocument").clone();
        Objects.requireNonNull(initialBlueId, "initialBlueId");
        Objects.requireNonNull(sessionId, "sessionId");
    }

    @Override
    public Node exactInitialDocument() {
        return exactInitialDocument.clone();
    }
}
