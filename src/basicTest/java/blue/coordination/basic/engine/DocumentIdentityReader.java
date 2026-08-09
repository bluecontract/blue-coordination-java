package blue.coordination.basic.engine;

import blue.language.snapshot.FrozenNode;

import java.util.Objects;

/** Reads stable process identity and activation policy through immutable indexes. */
public final class DocumentIdentityReader {
    private static final String DOCUMENT_ID = "/documentId";
    private static final String ACTIVATION_MODE = "/coordination/activationMode";

    private DocumentIdentityReader() {
    }

    public static DocumentId requireDocumentId(ExactNodeValue document) {
        Object value = valueAt(document, DOCUMENT_ID);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(
                    "Every managed Root and Process Embedded document must carry "
                            + "a stable non-blank /documentId");
        }
        return DocumentId.of(text);
    }

    public static void verifyOptionalDocumentId(
            ExactNodeValue document,
            DocumentId expected) {
        Object value = valueAt(document, DOCUMENT_ID);
        if (value == null) {
            return;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(
                    "/documentId must be non-blank Text when present");
        }
        if (!Objects.requireNonNull(expected, "expected").value().equals(text)) {
            throw new IllegalArgumentException(
                    "Managed DocumentId " + expected
                            + " does not match authored /documentId " + text);
        }
    }

    public static ActivationMode activationMode(ExactNodeValue document) {
        Object value = valueAt(document, ACTIVATION_MODE);
        if (value == null) {
            return ActivationMode.IMPORT_FULL_HISTORY;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(
                    "/coordination/activationMode must be Text");
        }
        return switch (text) {
            case "birth" -> ActivationMode.BIRTH_AT_ATTACHMENT;
            case "import-full-history" -> ActivationMode.IMPORT_FULL_HISTORY;
            case "import-from-frontier" -> ActivationMode.IMPORT_FROM_FRONTIER;
            case "passive-snapshot" -> ActivationMode.PASSIVE_SNAPSHOT;
            default -> throw new IllegalArgumentException(
                    "Unknown activation mode " + text);
        };
    }

    private static Object valueAt(ExactNodeValue document, String path) {
        FrozenNode selected = Objects.requireNonNull(document, "document")
                .canonicalAt(path);
        return selected == null ? null : selected.getValue();
    }
}
