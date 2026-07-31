package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.CoordinationProcessHeaderBridge;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.BlueIds;
import blue.repo.BlueRepository;

/**
 * Compares Blue values by semantic identity rather than serialized
 * representation.
 *
 * <p>Reference-only nodes keep their declared identity. Equality completes
 * materialized values through Language, while exact event/checkpoint identity
 * hashes Language's canonical exact representation without recursively
 * opening opaque header references. Each comparison owns and closes its
 * Language facade, so context-free matching retains no thread-local registry
 * or cache state.</p>
 */
final class BlueSemanticIdentity {
    private BlueSemanticIdentity() {
    }

    static boolean equals(Node left, Node right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.isReferenceOnly()
                && right.isReferenceOnly()) {
            return referenceIdentity(left).equals(
                    referenceIdentity(right));
        }
        BlueRepository repository = BlueRepository.latest();
        try (Blue blue = repository.configure(new Blue())) {
            if (left.isReferenceOnly()) {
                return referenceMatches(
                        referenceIdentity(left),
                        right,
                        blue);
            }
            if (right.isReferenceOnly()) {
                return referenceMatches(
                        referenceIdentity(right),
                        left,
                        blue);
            }
            Node leftExact = exactCopy(left);
            Node rightExact = exactCopy(right);
            Class<?> leftClass =
                    semanticClass(leftExact, blue);
            Class<?> rightClass =
                    semanticClass(rightExact, blue);
            /*
             * A typed parent can legitimately omit the type on one of its
             * authored children. Resolution then materializes that inherited
             * child type. Compare the two values with the class known by
             * either representation, instead of treating the untyped
             * authored child as an unrelated standalone map.
             */
            return semanticIdentity(
                    leftExact,
                    blue,
                    leftClass != null
                            ? leftClass
                            : rightClass)
                    .equals(
                            semanticIdentity(
                                    rightExact,
                                    blue,
                                    rightClass != null
                                            ? rightClass
                                            : leftClass));
        }
    }

    static String identity(Node node) {
        if (node == null) {
            throw new IllegalArgumentException(
                    "Semantic identity node must be present");
        }
        if (node.isReferenceOnly()) {
            return BlueIds.requireBlueIdOrCyclicMember(
                    node.getBlueId(),
                    "Exact identity reference");
        }
        return BlueIdCalculator.calculateBlueId(
                CoordinationProcessHeaderBridge
                        .canonicalExactCopy(node));
    }

    private static String semanticIdentity(
            Node node,
            Blue blue) {
        if (node.isReferenceOnly()) {
            return referenceIdentity(node);
        }
        Node exact = exactCopy(node);
        return semanticIdentity(
                exact,
                blue,
                semanticClass(exact, blue));
    }

    private static String semanticIdentity(
            Node exact,
            Blue blue,
            Class<?> semanticClass) {
        return blue.calculateSemanticBlueId(
                normalize(
                        exact,
                        blue,
                        semanticClass));
    }

    private static Node normalize(
            Node exact,
            Blue blue,
            Class<?> semanticClass) {
        if (semanticClass != null) {
            /*
             * Repository completion contributes inherited field
             * descriptions and schemas to resolved generated values. The
             * generated-object round trip projects those values back to
             * their authored semantic fields before comparison, while the
             * canonical exact copy above prevents a resolved nominal type
             * from becoming an illegal mixed BlueId node.
             */
            exact = blue.objectToNode(
                    blue.nodeToObject(
                            exact, semanticClass));
        }
        return exact;
    }

    private static boolean referenceMatches(
            String referenceIdentity,
            Node materialized,
            Blue blue) {
        Node exact = exactCopy(materialized);
        Class<?> semanticClass =
                semanticClass(exact, blue);
        Node normalized =
                normalize(
                        exact,
                        blue,
                        semanticClass);
        if (referenceIdentity.equals(
                blue.calculateSemanticBlueId(
                        normalized))) {
            return true;
        }
        if (semanticClass == null) {
            return false;
        }
        /*
         * A reference can have been calculated while the value was an
         * untyped child of a typed parent. Retain the exact authored fields
         * and omit only the inferred root type when checking that legitimate
         * representation. Nested field types and all content remain
         * identity-bearing.
         */
        Node inferredChildProjection =
                normalized.clone()
                        .type((Node) null);
        return referenceIdentity.equals(
                blue.calculateSemanticBlueId(
                        inferredChildProjection));
    }

    private static Class<?> semanticClass(
            Node exact,
            Blue blue) {
        return blue.determineClass(exact)
                .filter(candidate ->
                        !Object.class.equals(candidate)
                                && !Node.class.equals(candidate))
                .orElse(null);
    }

    private static Node exactCopy(Node node) {
        return CoordinationProcessHeaderBridge
                .canonicalExactCopy(node);
    }

    private static String referenceIdentity(Node node) {
        return BlueIds.requireBlueIdOrCyclicMember(
                node.getBlueId(),
                "Semantic identity reference");
    }
}
